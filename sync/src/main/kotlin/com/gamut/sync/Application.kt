package com.gamut.sync

import com.gamut.core.model.ResolvedSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

private val cache = ConcurrentHashMap<String, ResolvedSource>()

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    val ytDlp = YtDlpService()
    launch { ytDlp.warmUp() }
    install(ContentNegotiation) { json() }
    routing {
        get("/resolve/{isrc}") { val value = cache[call.parameters["isrc"]]; if (value == null) call.respond(HttpStatusCode.NotFound) else call.respond(value) }
        post("/resolve") {
            val submitted = call.receive<ResolvedSource>()
            cache.compute(submitted.isrc) { _, existing ->
                when {
                    existing == null -> submitted.copy(verifiedCount = 1)
                    existing.youtubeVideoId == submitted.youtubeVideoId -> existing.copy(verifiedCount = existing.verifiedCount + 1)
                    else -> existing
                }
            }
            call.respond(HttpStatusCode.Accepted)
        }
        get("/youtube/search") {
            val query = call.request.queryParameters["query"]?.trim()
            if (query.isNullOrEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "query is required")
            } else {
                runCatching { ytDlp.search(query) }
                    .onSuccess { call.respond(it) }
                    .onFailure { error ->
                        this@module.log.error("yt-dlp search failed", error)
                        call.respond(HttpStatusCode.BadGateway, error.message ?: "yt-dlp search failed")
                    }
            }
        }
        get("/youtube/stream/{videoId}") {
            val videoId = call.parameters["videoId"]
            if (videoId.isNullOrBlank() || !videoId.matches(Regex("[A-Za-z0-9_-]{6,}"))) {
                call.respond(HttpStatusCode.BadRequest, "invalid videoId")
            } else {
                runCatching { ytDlp.audioStream(videoId) }
                    .onSuccess { call.respond(it) }
                    .onFailure { error ->
                        this@module.log.error("yt-dlp stream extraction failed", error)
                        call.respond(HttpStatusCode.BadGateway, error.message ?: "yt-dlp stream extraction failed")
                    }
            }
        }
    }
}