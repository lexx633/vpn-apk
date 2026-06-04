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
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Full connection diagnostic test.
 *
 * Sequence:
 *  0. Stop VPN if running
 *  1. Check-in (VPN off)
 *  2. Start VPN, measure each startup phase:
 *       TUN interface up (ConnectivityManager sees TRANSPORT_VPN)
 *       SOCKS port accepts connections
 *       HTTP via SOCKS succeeds  (proxy path — same as LimmCheckinWorker probes)
 *       HTTP via TUN  succeeds   (real-browser path — bound to VPN network)
 *  3. Stop VPN, wait 2 s
 *  4. Start VPN again (second cold start), same measurements
 *  5. Check-in (VPN on)
 *  6. Stop VPN
 *  7. Upload applog
 *
 * All timings are measured from the moment startVService() is called.
 */
object LimmDiagTest {

    private const val TAG = "LimmDiag"
    private const val POLL_INTERVAL_MS = 80L
    private const val RUN_TIMEOUT_MS = 20_000L   // 20 s max per run

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

    /** HTTP GET through the local SOCKS proxy — same path the checkin worker uses. */
    private fun httpViaSocks(url: String, socksPort: Int, timeoutSec: Long = 10): Triple<Boolean, Int, Long> {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val t0 = System.currentTimeMillis()
        return try {
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                Triple(r.isSuccessful || r.code == 204, r.code, System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            Triple(false, -1, System.currentTimeMillis() - t0)
        }
    }

    /**
     * HTTP GET bound explicitly to the VPN network.
     * This is the same path Chrome and other excluded apps use — the request goes through
     * the TUN fd → hev-tun → SOCKS → VLESS.  The app is excluded from its own VPN via
     * addDisallowedApplication, but Network.openConnection() overrides that exclusion and
     * forces the socket onto the VPN network regardless.
     */
    private fun httpViaTun(ctx: Context, url: String, timeoutMs: Int = 8000): Triple<Boolean, Int, Long> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnNet = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return Triple(false, -2, -1L)

        val t0 = System.currentTimeMillis()
        return try {
            val conn = vpnNet.openConnection(URL(url))
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.connect()
            val code = (conn as? java.net.HttpURLConnection)?.responseCode ?: 200
            Triple(code in 200..299 || code == 204, code, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Triple(false, -1, System.currentTimeMillis() - t0)
        }
    }

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
            if (tunMs >= 0) "VPN ключ в статусбаре через +${tunMs}ms" else "TIMEOUT ${RUN_TIMEOUT_MS}ms"
        )
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
            if (socksMs >= 0) "+${socksMs}ms" else "TIMEOUT"
        )
        if (socksMs < 0) return phases

        // ── Phase C: HTTP via SOCKS (proxy path) ─────────────────────────────
        val (socksOk, socksCode, socksReqMs) = httpViaSocks(
            "https://www.google.com/generate_204", socksPort
        )
        phases += Phase(
            "HTTP(SOCKS)",
            socksOk,
            System.currentTimeMillis() - runT0,
            if (socksOk) "code=$socksCode, req=${socksReqMs}ms → google.com"
            else "FAIL code=$socksCode"
        )

        // ── Phase D: HTTP via TUN (real-browser path) ─────────────────────────
        // Give hev-tun a tiny moment to fully start processing after SOCKS is ready.
        delay(150)
        val (tunOk, tunCode, tunReqMs) = httpViaTun(ctx, "https://www.google.com/generate_204")
        phases += Phase(
            "HTTP(TUN)",
            tunOk,
            System.currentTimeMillis() - runT0,
            if (tunOk) "code=$tunCode, req=${tunReqMs}ms → google.com (браузерный путь)"
            else if (tunCode == -2) "VPN сеть исчезла"
            else "FAIL code=$tunCode ${tunReqMs}ms"
        )

        // ── Phase E: extra TUN test — example.com (non-Google) ───────────────
        if (tunOk) {
            val (exOk, exCode, exMs) = httpViaTun(ctx, "https://example.com/")
            phases += Phase(
                "HTTP(TUN) example.com",
                exOk,
                System.currentTimeMillis() - runT0,
                if (exOk) "${exMs}ms" else "FAIL code=$exCode"
            )
        }

        return phases
    }

    // ── public entry point ───────────────────────────────────────────────────

    /**
     * Runs the full test. Must be called from a coroutine (NOT from the main thread).
     * [onProgress] is called from the IO thread with each status line as it happens.
     */
    suspend fun run(ctx: Context, onProgress: (String) -> Unit): List<RunResult> {
        val results = mutableListOf<RunResult>()

        // ── 0. Stop VPN if running ────────────────────────────────────────────
        if (CoreServiceManager.isRunning() || vpnTransportUp(ctx)) {
            onProgress("⏹  Останавливаю VPN…")
            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
            delay(2500)
        }
        Log.i(TAG, "=== LIMM DIAG TEST START ===")

        // ── 1. Check-in (VPN off) ─────────────────────────────────────────────
        onProgress("📡  Чек-ин 1 (VPN выкл)…")
        val (ok1, msg1) = LimmCheckinWorker.sendNow(ctx)
        onProgress(if (ok1) "    ✓ $msg1" else "    ✗ $msg1")

        // ── 2. Run 1: first cold start ─────────────────────────────────────────
        onProgress("\n🔌  Соединение 1 — запускаю VPN…")
        val t01 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { CoreServiceManager.startVService(ctx) }
        delay(50)  // let the intent dispatch
        val phases1 = measureRun(ctx, t01)
        phases1.forEach { p ->
            onProgress("    ${if (p.ok) "✓" else "✗"}  ${p.name}: ${if (p.fromStartMs >= 0) "+${p.fromStartMs}ms" else "—"}   ${p.note}")
        }
        results += RunResult("Соединение 1 (холодный старт)", phases1)

        // ── 3. Stop ───────────────────────────────────────────────────────────
        onProgress("\n⏹  Останавливаю VPN…")
        withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
        delay(2500)

        // ── 4. Run 2: second cold start ────────────────────────────────────────
        onProgress("\n🔌  Соединение 2 — запускаю VPN…")
        val t02 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { CoreServiceManager.startVService(ctx) }
        delay(50)
        val phases2 = measureRun(ctx, t02)
        phases2.forEach { p ->
            onProgress("    ${if (p.ok) "✓" else "✗"}  ${p.name}: ${if (p.fromStartMs >= 0) "+${p.fromStartMs}ms" else "—"}   ${p.note}")
        }
        results += RunResult("Соединение 2 (повторный старт)", phases2)

        // ── 5. Check-in (VPN on) ──────────────────────────────────────────────
        onProgress("\n📡  Чек-ин 2 (VPN вкл)…")
        val (ok2, msg2) = LimmCheckinWorker.sendNow(ctx)
        onProgress(if (ok2) "    ✓ $msg2" else "    ✗ $msg2")

        // ── 6. Stop VPN ────────────────────────────────────────────────────────
        onProgress("\n⏹  Останавливаю VPN…")
        withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
        delay(1500)

        // Log summary
        results.forEach { run ->
            Log.i(TAG, "--- ${run.label} ---")
            run.phases.forEach { p ->
                Log.i(TAG, "  [${if (p.ok) "OK" else "FAIL"}] ${p.name}: +${p.fromStartMs}ms  ${p.note}")
            }
        }
        Log.i(TAG, "=== LIMM DIAG TEST END ===")

        // ── 7. Upload applog ────────────────────────────────────────────────────
        onProgress("\n📤  Отправляю лог на сервер…")
        val (logOk, logMsg) = LimmLogReporter.send(ctx)
        onProgress(if (logOk) "    ✓ Лог отправлен" else "    ✗ $logMsg")

        return results
    }
}
