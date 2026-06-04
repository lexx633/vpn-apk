package com.v2ray.ang.limm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Full connection diagnostic test.
 *
 * Sequence:
 *  0. Stop VPN if running, wait 2s
 *  1. Check-in (VPN off) — baseline
 *  2. Run 1: start VPN, measure cold-start timings:
 *       Phase A — TUN interface up (VPN key in status bar)
 *       Phase B — SOCKS port accepts connections (xray ready)
 *       Phase C — cold-start verdict: TUN < SOCKS = no traffic leak ✓
 *       Phase D — check-in at +32s (proves tunnel carries real traffic)
 *  3. Stop VPN, wait 2s
 *  4. Run 2: repeat
 *  5. Stop VPN
 *  6. Upload applog
 *
 * Note: HTTP probes via SOCKS/TUN were removed. The app is in addDisallowedApplication,
 * so its traffic bypasses VPN TUN. Direct requests to google.com are blocked in RU
 * without a proxy → always fail, misleading. Infrastructure timings (TUN/SOCKS order)
 * and the check-in result are the reliable cold-start indicators.
 */
object LimmDiagTest {

    private const val TAG = "LimmDiag"
    private const val POLL_INTERVAL_MS = 80L
    private const val RUN_TIMEOUT_MS = 20_000L
    private const val COLD_START_CHECK_MS = 32_000L  // check-in at +32s mark

    data class Phase(val name: String, val ok: Boolean, val fromStartMs: Long, val note: String = "")
    data class RunResult(val label: String, val phases: List<Phase>)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun vpnTransportUp(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (e: Exception) { false }

    private fun socksAccepting(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
    } catch (e: Exception) { false }

    // ── one full VPN run: start → measure → return phases ───────────────────

    private suspend fun measureRun(ctx: Context, runT0: Long): List<Phase> {
        val phases = mutableListOf<Phase>()
        val socksPort = try { SettingsManager.getSocksPort() } catch (e: Exception) { 10808 }
        val deadline = runT0 + RUN_TIMEOUT_MS

        // ── Phase A: TUN interface appears in ConnectivityManager ─────────────
        var tunMs = -1L
        while (System.currentTimeMillis() < deadline) {
            if (vpnTransportUp(ctx)) { tunMs = System.currentTimeMillis() - runT0; break }
            delay(POLL_INTERVAL_MS)
        }
        phases += Phase(
            "TUN интерфейс",
            tunMs >= 0,
            tunMs,
            if (tunMs >= 0) "VPN ключ в статусбаре +${tunMs}ms" else "TIMEOUT ${RUN_TIMEOUT_MS}ms"
        )
        Log.i(TAG, "  Phase A: TUN=${if (tunMs >= 0) "+${tunMs}ms" else "TIMEOUT"}")
        if (tunMs < 0) return phases

        // ── Phase B: SOCKS port accepts connections ───────────────────────────
        var socksMs = -1L
        while (System.currentTimeMillis() < deadline) {
            if (socksAccepting(socksPort)) { socksMs = System.currentTimeMillis() - runT0; break }
            delay(POLL_INTERVAL_MS)
        }
        phases += Phase(
            "SOCKS :$socksPort",
            socksMs >= 0,
            socksMs,
            if (socksMs >= 0) "xray готов +${socksMs}ms" else "TIMEOUT"
        )
        Log.i(TAG, "  Phase B: SOCKS=${if (socksMs >= 0) "+${socksMs}ms" else "TIMEOUT"}")
        if (socksMs < 0) return phases

        // ── Phase C: cold-start verdict ───────────────────────────────────────
        // TUN must appear BEFORE SOCKS. If TUN < SOCKS → traffic buffered in TUN fd
        // while xray starts → no leak to ISP → cold-start FIXED ✓
        // If SOCKS < TUN → xray ready before TUN → traffic went direct → BUG ✗
        val tunFirst = tunMs < socksMs
        val bufferMs = socksMs - tunMs
        phases += Phase(
            "Холодный старт",
            tunFirst,
            socksMs,
            if (tunFirst)
                "TUN +${tunMs}ms < SOCKS +${socksMs}ms → буфер ${bufferMs}ms → фикс работает ✓"
            else
                "SOCKS +${socksMs}ms < TUN +${tunMs}ms → трафик утёк в ISP! БАГ ✗"
        )
        Log.i(TAG, "  Phase C: cold-start ${if (tunFirst) "OK (buffer ${bufferMs}ms)" else "BUG!"}")

        // ── Phase D: check-in at +32s ─────────────────────────────────────────
        // Waits until 32s from VPN start, then sends a check-in.
        // Check-in uses the collector URL (limm.space) — accessible both direct and via tunnel.
        // The server records egress_ip: if it matches VPN server → request went through tunnel.
        val elapsed = System.currentTimeMillis() - runT0
        val waitMs = (COLD_START_CHECK_MS - elapsed).coerceAtLeast(0L)
        if (waitMs > 0) {
            Log.i(TAG, "  Phase D: ждём +32s, осталось ${waitMs}ms…")
            delay(waitMs)
        }
        Log.i(TAG, "  Phase D: check-in at +${System.currentTimeMillis() - runT0}ms")
        val (ciOk, ciMsg) = LimmCheckinWorker.sendNow(ctx)
        phases += Phase(
            "Check-in @+32s",
            ciOk,
            System.currentTimeMillis() - runT0,
            if (ciOk) ciMsg else "FAIL: $ciMsg"
        )
        Log.i(TAG, "  Phase D: check-in ${if (ciOk) "OK: $ciMsg" else "FAIL: $ciMsg"}")

        return phases
    }

    // ── public entry point ───────────────────────────────────────────────────

    /**
     * Runs the full test. Must be called from a coroutine (NOT from the main thread).
     * [onProgress] is called with each status line as it happens.
     */
    suspend fun run(ctx: Context, onProgress: (String) -> Unit): List<RunResult> {
        val results = mutableListOf<RunResult>()

        // ── Pre-check: VPN permission must already be granted ─────────────────
        val permIntent = android.net.VpnService.prepare(ctx)
        if (permIntent != null) {
            onProgress("❌  Нет разрешения VPN.\n\nСначала подключитесь вручную кнопкой «Подключить» (Android покажет диалог разрешения), затем запустите тест снова.")
            Log.e(TAG, "DIAG ABORT: VPN permission not granted")
            return results
        }

        // ── 0. Stop VPN if running ────────────────────────────────────────────
        if (CoreServiceManager.isRunning() || vpnTransportUp(ctx)) {
            onProgress("⏹  Останавливаю VPN…")
            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
            delay(2500)
        }
        Log.i(TAG, "=== LIMM DIAG TEST START ===")

        // ── 1. Check-in (VPN off) ─────────────────────────────────────────────
        onProgress("📡  Чек-ин (VPN выкл)…")
        val (ok1, msg1) = LimmCheckinWorker.sendNow(ctx)
        onProgress(if (ok1) "    ✓ $msg1" else "    ✗ $msg1")
        Log.i(TAG, "checkin-off: ok=$ok1 $msg1")

        // ── 2. Run 1: first cold start ─────────────────────────────────────────
        onProgress("\n🔌  Соединение 1 — запускаю VPN…")
        Log.i(TAG, "--- Соединение 1 (холодный старт) ---")
        val t01 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { CoreServiceManager.startVService(ctx) }
        delay(50)
        val phases1 = measureRun(ctx, t01)
        phases1.forEach { p ->
            val icon = if (p.ok) "✓" else "✗"
            val ms = if (p.fromStartMs >= 0) "+${p.fromStartMs}ms" else "—"
            onProgress("    $icon  ${p.name}: $ms   ${p.note}")
            Log.i(TAG, "  [${if (p.ok) "OK" else "FAIL"}] ${p.name}: $ms  ${p.note}")
        }
        results += RunResult("Соединение 1 (холодный старт)", phases1)

        // ── 3. Stop ───────────────────────────────────────────────────────────
        onProgress("\n⏹  Останавливаю VPN…")
        withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
        delay(2500)

        // ── 4. Run 2: second cold start ────────────────────────────────────────
        onProgress("\n🔌  Соединение 2 — запускаю VPN…")
        Log.i(TAG, "--- Соединение 2 (повторный старт) ---")
        val t02 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { CoreServiceManager.startVService(ctx) }
        delay(50)
        val phases2 = measureRun(ctx, t02)
        phases2.forEach { p ->
            val icon = if (p.ok) "✓" else "✗"
            val ms = if (p.fromStartMs >= 0) "+${p.fromStartMs}ms" else "—"
            onProgress("    $icon  ${p.name}: $ms   ${p.note}")
            Log.i(TAG, "  [${if (p.ok) "OK" else "FAIL"}] ${p.name}: $ms  ${p.note}")
        }
        results += RunResult("Соединение 2 (повторный старт)", phases2)

        // ── 5. Stop VPN ────────────────────────────────────────────────────────
        onProgress("\n⏹  Останавливаю VPN…")
        withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
        delay(1500)

        Log.i(TAG, "=== LIMM DIAG TEST END ===")

        // ── 6. Upload applog ────────────────────────────────────────────────────
        onProgress("\n📤  Отправляю лог на сервер…")
        val (logOk, logMsg) = LimmLogReporter.send(ctx)
        onProgress(if (logOk) "    ✓ Лог отправлен" else "    ✗ $logMsg")

        return results
    }
}
