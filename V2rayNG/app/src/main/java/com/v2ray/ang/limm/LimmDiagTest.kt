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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Full connection diagnostic test.
 *
 * Sequence:
 *  0. Stop VPN if running, wait 2s
 *  1. Check-in (VPN off) — baseline
 *  2. Run 1: start VPN, measure:
 *       Phase A — TUN interface up (VPN key in status bar)
 *       Phase B — SOCKS port accepts connections (xray ready)
 *       Phase C — cold-start verdict: TUN < SOCKS timing
 *       Phase D — HTTP via SOCKS to limm.space/healthz (with retry, 3×5s)
 *       Phase E — check-in at +5s (early connectivity)
 *       Phase F — check-in at +32s (steady-state, confirms tunnel)
 *  3. Stop VPN, wait 2s
 *  4. Run 2: repeat
 *  5. Stop VPN
 *  6. Upload applog
 *
 * Note on app exclusion: the app is in addDisallowedApplication — its traffic bypasses TUN.
 * Direct requests to blocked sites (google.com, example.com) always fail from the app process.
 * Solutions used here:
 *   - httpViaSocks: routes via xray SOCKS proxy (127.0.0.1:10808) → xray outbound → limm.space.
 *     limm.space is accessible from RU directly AND via tunnel. With retry for xray warmup.
 *   - check-ins: direct HTTP to limm.space; server records whether egress came via VPN server IP.
 */
object LimmDiagTest {

    private const val TAG = "LimmDiag"
    private const val POLL_INTERVAL_MS = 80L
    private const val RUN_TIMEOUT_MS = 20_000L
    private const val EARLY_CHECKIN_MS = 5_000L   // check-in at +5s
    private const val LATE_CHECKIN_MS  = 32_000L  // check-in at +32s

    // URL reachable from Russia directly AND via tunnel — used for SOCKS probe
    private const val PROBE_URL = "https://limm.space/healthz"

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

    /**
     * HTTP GET through xray's SOCKS proxy → limm.space/healthz.
     * limm.space is accessible from Russia directly, so xray doesn't need to be
     * working perfectly — just needs to forward the request through XHTTP to our server.
     * Retries up to [maxRetries] times with [retryDelayMs] between attempts.
     */
    private suspend fun httpViaSocksWithRetry(
        socksPort: Int,
        maxRetries: Int = 3,
        timeoutSec: Long = 5L,
        retryDelayMs: Long = 2000L
    ): Triple<Boolean, Int, Long> {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val t0 = System.currentTimeMillis()
        var lastCode = -1
        repeat(maxRetries) { attempt ->
            try {
                val client = OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                    .readTimeout(timeoutSec, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder().url(PROBE_URL).build()).execute().use { r ->
                    if (r.isSuccessful) {
                        return Triple(true, r.code, System.currentTimeMillis() - t0)
                    }
                    lastCode = r.code
                }
            } catch (e: Exception) {
                lastCode = -1
                Log.w(TAG, "  httpViaSocks attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < maxRetries - 1) delay(retryDelayMs)
        }
        return Triple(false, lastCode, System.currentTimeMillis() - t0)
    }

    // ── one full VPN run ─────────────────────────────────────────────────────

    private suspend fun measureRun(ctx: Context, runT0: Long): List<Phase> {
        val phases = mutableListOf<Phase>()
        val socksPort = try { SettingsManager.getSocksPort() } catch (e: Exception) { 10808 }
        val deadline = runT0 + RUN_TIMEOUT_MS

        // ── Phase A: TUN interface ────────────────────────────────────────────
        var tunMs = -1L
        while (System.currentTimeMillis() < deadline) {
            if (vpnTransportUp(ctx)) { tunMs = System.currentTimeMillis() - runT0; break }
            delay(POLL_INTERVAL_MS)
        }
        phases += Phase(
            "TUN интерфейс",
            tunMs >= 0,
            tunMs,
            if (tunMs >= 0) "VPN ключ в статусбаре +${tunMs}ms" else "TIMEOUT"
        )
        Log.i(TAG, "  Phase A: TUN=${if (tunMs >= 0) "+${tunMs}ms" else "TIMEOUT"}")
        if (tunMs < 0) return phases

        // ── Phase B: SOCKS port ───────────────────────────────────────────────
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
        val tunFirst = tunMs < socksMs
        val bufferMs = socksMs - tunMs
        phases += Phase(
            "Холодный старт",
            tunFirst,
            socksMs,
            if (tunFirst)
                "TUN +${tunMs}ms → SOCKS +${socksMs}ms, буфер ${bufferMs}ms — фикс ✓"
            else
                "SOCKS +${socksMs}ms → TUN +${tunMs}ms — трафик утёк в ISP! БАГ ✗"
        )
        Log.i(TAG, "  Phase C: ${if (tunFirst) "cold-start OK (buffer ${bufferMs}ms)" else "cold-start BUG!"}")

        // ── Phase D: HTTP via SOCKS → limm.space (with retry) ────────────────
        Log.i(TAG, "  Phase D: HTTP via SOCKS → $PROBE_URL (3 tries × 5s)…")
        val (socksOk, socksCode, socksMs2) = httpViaSocksWithRetry(socksPort)
        phases += Phase(
            "HTTP(SOCKS→limm.space)",
            socksOk,
            System.currentTimeMillis() - runT0,
            if (socksOk) "код=$socksCode, ${socksMs2}ms ✓" else "FAIL код=$socksCode, ${socksMs2}ms — xray прокси не работает"
        )
        Log.i(TAG, "  Phase D: SOCKS probe ${if (socksOk) "OK ${socksMs2}ms" else "FAIL code=$socksCode"}")

        // ── Phase E: check-in at +5s ──────────────────────────────────────────
        val elapsed5 = System.currentTimeMillis() - runT0
        val wait5 = (EARLY_CHECKIN_MS - elapsed5).coerceAtLeast(0L)
        if (wait5 > 0) delay(wait5)
        Log.i(TAG, "  Phase E: check-in at +${System.currentTimeMillis() - runT0}ms")
        val (ci5ok, ci5msg) = LimmCheckinWorker.sendNow(ctx)
        phases += Phase(
            "Check-in @+5s",
            ci5ok,
            System.currentTimeMillis() - runT0,
            if (ci5ok) ci5msg else "FAIL: $ci5msg"
        )
        Log.i(TAG, "  Phase E: checkin@5s ${if (ci5ok) "OK: $ci5msg" else "FAIL: $ci5msg"}")

        // ── Phase F: check-in at +32s ─────────────────────────────────────────
        val elapsed32 = System.currentTimeMillis() - runT0
        val wait32 = (LATE_CHECKIN_MS - elapsed32).coerceAtLeast(0L)
        if (wait32 > 0) {
            Log.i(TAG, "  Phase F: ждём +32s, осталось ${wait32}ms…")
            delay(wait32)
        }
        Log.i(TAG, "  Phase F: check-in at +${System.currentTimeMillis() - runT0}ms")
        val (ci32ok, ci32msg) = LimmCheckinWorker.sendNow(ctx)
        phases += Phase(
            "Check-in @+32s",
            ci32ok,
            System.currentTimeMillis() - runT0,
            if (ci32ok) ci32msg else "FAIL: $ci32msg"
        )
        Log.i(TAG, "  Phase F: checkin@32s ${if (ci32ok) "OK: $ci32msg" else "FAIL: $ci32msg"}")

        return phases
    }

    // ── public entry point ───────────────────────────────────────────────────

    suspend fun run(ctx: Context, onProgress: (String) -> Unit): List<RunResult> {
        val results = mutableListOf<RunResult>()

        val permIntent = android.net.VpnService.prepare(ctx)
        if (permIntent != null) {
            onProgress("❌  Нет разрешения VPN.\n\nСначала подключитесь вручную кнопкой «Подключить», затем запустите тест снова.")
            Log.e(TAG, "DIAG ABORT: VPN permission not granted")
            return results
        }

        if (CoreServiceManager.isRunning() || vpnTransportUp(ctx)) {
            onProgress("⏹  Останавливаю VPN…")
            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
            delay(2500)
        }
        Log.i(TAG, "=== LIMM DIAG TEST START ===")

        onProgress("📡  Чек-ин (VPN выкл)…")
        val (ok1, msg1) = LimmCheckinWorker.sendNow(ctx)
        onProgress(if (ok1) "    ✓ $msg1" else "    ✗ $msg1")
        Log.i(TAG, "checkin-off: ok=$ok1 $msg1")

        for (run in 1..2) {
            onProgress("\n🔌  Соединение $run — запускаю VPN…")
            Log.i(TAG, "--- Соединение $run ---")
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.Main) { CoreServiceManager.startVService(ctx) }
            delay(50)
            val phases = measureRun(ctx, t0)
            phases.forEach { p ->
                val icon = if (p.ok) "✓" else "✗"
                val ms = if (p.fromStartMs >= 0) "+${p.fromStartMs}ms" else "—"
                onProgress("    $icon  ${p.name}: $ms   ${p.note}")
                Log.i(TAG, "  [${if (p.ok) "OK" else "FAIL"}] ${p.name}: $ms  ${p.note}")
            }
            results += RunResult("Соединение $run", phases)

            onProgress("\n⏹  Останавливаю VPN…")
            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
            delay(if (run == 1) 2500L else 1500L)
        }

        Log.i(TAG, "=== LIMM DIAG TEST END ===")

        onProgress("\n📤  Отправляю лог на сервер…")
        val (logOk, logMsg) = LimmLogReporter.send(ctx)
        onProgress(if (logOk) "    ✓ Лог отправлен" else "    ✗ $logMsg")

        return results
    }
}
