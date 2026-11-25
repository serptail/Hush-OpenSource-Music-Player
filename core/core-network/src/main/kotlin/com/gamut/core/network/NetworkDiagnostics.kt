package com.gamut.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Records why network and extraction work failed, and tells a blocked request (DNS filter, firewall, ad-blocker)
 * apart from a slow or broken one. Nothing here changes behaviour: it only classifies, logs and explains.
 *
 * After a failed playback, [probeHosts] resolves the hosts playback needs and [playbackMessage] turns the result
 * into text the person can act on.
 */
object NetworkDiagnostics {
    private const val TAG = "GamutNet"
    private const val MAX_EVENTS = 60
    private const val PROBE_TIMEOUT_MS = 2_500L

    /** Hosts playback needs. A filter that blocks any of these will show up as "Getting audio" never finishing. */
    val REQUIRED_HOSTS: List<String> =
        listOf(
            "www.youtube.com",
            "music.youtube.com",
            "youtubei.googleapis.com",
            "redirector.googlevideo.com",
            "i.ytimg.com",
        )

    enum class Kind {
        DNS_FAILED,
        DNS_SINKHOLED,
        CONNECT_FAILED,
        TLS_FAILED,
        TIMEOUT,
        HTTP_FORBIDDEN,
        HTTP_RATE_LIMITED,
        HTTP_ERROR,
        BOT_CHECK,
        NO_STREAM,
        UNKNOWN,
    }

    enum class HostStatus { OK, UNRESOLVED, SINKHOLED }

    data class HostProbe(
        val host: String,
        val status: HostStatus,
        val detail: String = "",
    )

    data class Event(
        val atMs: Long,
        val stage: String,
        val host: String?,
        val kind: Kind,
        val detail: String,
    )

    private val events = ArrayDeque<Event>()

    @Volatile var lastProbe: List<HostProbe> = emptyList()
        private set

    /** Classifies [t] by walking its causes, then falling back to the message text (yt-dlp reports failures only as text). */
    fun classify(t: Throwable?): Kind {
        var cur = t
        var depth = 0
        while (cur != null && depth < 8) {
            when (cur) {
                is UnknownHostException -> return Kind.DNS_FAILED
                is SSLException -> return Kind.TLS_FAILED
                is SocketTimeoutException -> return Kind.TIMEOUT
                is ConnectException -> return Kind.CONNECT_FAILED
            }
            cur = cur.cause.takeIf { it !== cur }
            depth++
        }
        val text = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(8).mapNotNull { it.message }.joinToString(" | ")
        return when {
            text.containsAny("Unable to resolve", "Failed to resolve", "name resolution", "Name or service not known", "No address associated") -> Kind.DNS_FAILED
            text.containsAny("Sign in to confirm") -> Kind.BOT_CHECK
            text.containsAny("HTTP Error 403", " 403", "Forbidden") -> Kind.HTTP_FORBIDDEN
            text.containsAny("HTTP Error 429", "Too Many Requests") -> Kind.HTTP_RATE_LIMITED
            text.containsAny("certificate", "SSL", "handshake") -> Kind.TLS_FAILED
            text.containsAny("timed out", "Timed out", "timeout") -> Kind.TIMEOUT
            text.containsAny("Connection refused", "Network is unreachable", "Failed to connect", "Connection reset") -> Kind.CONNECT_FAILED
            text.containsAny("no direct audio stream", "no playable audio URL") -> Kind.NO_STREAM
            text.containsAny("HTTP Error") -> Kind.HTTP_ERROR
            else -> Kind.UNKNOWN
        }
    }

    /** Remembers a failure and writes it to logcat under the `GamutNet` tag. [host] is optional; [stage] says what was running. */
    fun record(
        stage: String,
        host: String?,
        t: Throwable?,
    ) {
        val kind = classify(t)
        val detail = describe(t)
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(Event(System.currentTimeMillis(), stage, host, kind, detail))
        }
        Log.w(TAG, "$stage failed [${kind.name}]${host?.let { " host=$it" }.orEmpty()}: $detail")
    }

    fun recent(): List<Event> = synchronized(events) { events.toList() }

    /** A plain-text report of recent failures and the last host probe, for a bug report. */
    fun report(): String =
        buildString {
            appendLine("Gamut network diagnostics")
            lastProbe.forEach { appendLine("probe ${it.host}: ${it.status}${if (it.detail.isNotEmpty()) " (${it.detail})" else ""}") }
            recent().forEach { appendLine("${it.atMs} ${it.stage} ${it.kind}${it.host?.let { h -> " $h" }.orEmpty()}: ${it.detail}") }
        }

    /**
     * Resolves each of [hosts] through the system resolver (the one a DNS filter sits behind). A filter either fails
     * the lookup or answers with an unusable address such as 0.0.0.0, so both count as blocked. Blocks the calling
     * thread for up to a few seconds: call it off the main thread.
     */
    fun probeHosts(hosts: List<String> = REQUIRED_HOSTS): List<HostProbe> {
        val pool = Executors.newFixedThreadPool(hosts.size.coerceIn(1, 6))
        try {
            val futures = hosts.map { host -> pool.submit(Callable { probeOne(host) }) }
            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            val result =
                futures.mapIndexed { i, f ->
                    try {
                        f.get((deadline - System.currentTimeMillis()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        f.cancel(true)
                        HostProbe(hosts[i], HostStatus.UNRESOLVED, "lookup timed out")
                    }
                }
            lastProbe = result
            result.filter { it.status != HostStatus.OK }.forEach {
                Log.w(TAG, "probe ${it.host}: ${it.status} ${it.detail}")
            }
            return result
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probeOne(host: String): HostProbe =
        try {
            val addresses = InetAddress.getAllByName(host)
            if (addresses.all { it.isAnyLocalAddress || it.isLoopbackAddress }) {
                HostProbe(host, HostStatus.SINKHOLED, addresses.joinToString { it.hostAddress.orEmpty() })
            } else {
                HostProbe(host, HostStatus.OK)
            }
        } catch (e: UnknownHostException) {
            HostProbe(host, HostStatus.UNRESOLVED, e.message.orEmpty())
        } catch (e: Exception) {
            HostProbe(host, HostStatus.UNRESOLVED, e.javaClass.simpleName)
        }

    /**
     * What to tell the person when a song would not play. [probes] come from [probeHosts]; pass an empty list when no
     * probe was run. Falls back to the generic wording when nothing points at the network.
     */
    fun playbackMessage(
        t: Throwable?,
        probes: List<HostProbe> = emptyList(),
    ): String {
        val blocked = probes.filter { it.status != HostStatus.OK }.map { it.host }
        if (blocked.isNotEmpty()) {
            return "Couldn\u2019t reach ${blocked.first()}. A DNS filter or ad blocker may be blocking it. " +
                "Allow youtube.com, googleapis.com and googlevideo.com, then try again."
        }
        return when (classify(t)) {
            Kind.DNS_FAILED -> "Couldn\u2019t look up YouTube\u2019s servers. Check your connection or DNS filter."
            Kind.TIMEOUT -> "Connection was slow. Try again."
            Kind.TLS_FAILED -> "A secure connection to YouTube failed. A filter or proxy may be interfering."
            Kind.BOT_CHECK, Kind.HTTP_RATE_LIMITED -> "YouTube is limiting requests right now. Try again in a minute."
            else -> "Couldn\u2019t play this song"
        }
    }

    private fun describe(t: Throwable?): String =
        generateSequence(t) { it.cause.takeIf { c -> c !== it } }
            .take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}" }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it, ignoreCase = true) }
}

/** Records every request that fails at the transport level, or that a server answers with 403 or 429. */
class DiagnosticsInterceptor(
    private val stage: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val response =
            try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                NetworkDiagnostics.record(stage, host, e)
                throw e
            }
        if (response.code == 403 || response.code == 429) {
            NetworkDiagnostics.record(stage, host, IOException("HTTP Error ${response.code} ${response.message}"))
        }
        return response
    }
}
