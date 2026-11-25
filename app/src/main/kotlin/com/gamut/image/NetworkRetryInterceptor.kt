package com.gamut.image

import coil.intercept.Interceptor
import coil.network.HttpException
import coil.request.ErrorResult
import coil.request.ImageResult
import com.gamut.core.player.NetworkMonitor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.io.IOException

/**
 * Coil gives up on an image the moment its download fails, and the picture then stays blank until the screen is
 * rebuilt. This retries failed web images instead: while the device is offline it waits for the connection and
 * tries again when it returns, and while online it retries a couple of times for a flaky connection or server.
 *
 * Waiting is cheap: the request is a coroutine, and Coil cancels it when the image leaves the screen.
 */
class NetworkRetryInterceptor(
    private val network: NetworkMonitor,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        var result = chain.proceed(request)
        if (!request.data.toString().startsWith("http")) return result
        var onlineRetries = 0
        while (result is ErrorResult && retryable(result.throwable) && currentCoroutineContext().isActive) {
            if (!network.online.value) {
                network.online.first { it }
                delay(SETTLE_MS)
                onlineRetries = 0
            } else {
                if (onlineRetries >= MAX_ONLINE_RETRIES) break
                delay(BACKOFF_MS[onlineRetries])
                onlineRetries++
            }
            result = chain.proceed(request)
        }
        return result
    }

    private fun retryable(error: Throwable): Boolean =
        when (error) {
            is HttpException -> error.response.code.let { it == 408 || it == 429 || it >= 500 }
            is IOException -> true
            else -> false
        }

    private companion object {
        const val MAX_ONLINE_RETRIES = 2
        const val SETTLE_MS = 600L
        val BACKOFF_MS = longArrayOf(1_000L, 2_500L)
    }
}
