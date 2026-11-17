package com.gamut.onboarding.ytmusic

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.ink
import com.gamut.onboarding.importer.MiniJson

private const val MUSIC_ORIGIN = "https://music.youtube.com"

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/** Reads the identity the signed-in page is using, from the page's own config. Null when it isn't a YouTube page. */
private const val PAGE_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})()
"""

/** Where the Google and YouTube login lives: the pages the sign-in passes through, and the cookies they share. */
private val GOOGLE_ORIGINS = listOf(
    "https://music.youtube.com",
    "https://www.youtube.com",
    "https://youtube.com",
    "https://accounts.google.com",
    "https://www.google.com",
    "https://google.com",
)

/**
 * Forgets the Google login the in-app browser is holding, so the next sign-in starts clean and nothing is left behind.
 * Done the way BitChord does it: Google's cookies only, by name, rather than `removeAllCookies`, because the same cookie
 * jar can hold other logins and an import is no reason to sign out of those. There is no per-domain removal in the API,
 * so each cookie is overwritten with an expired one of the same name.
 *
 * Without this, Google recognises the account that was just used, redirects straight through to music.youtube.com and
 * hands back that same account, with no chance to pick another.
 *
 * Best effort throughout: CookieManager needs a WebView provider, and a device without one should get a less convenient
 * next sign-in, not a crash.
 */
fun clearYouTubeLogin() {
    val manager = try {
        CookieManager.getInstance()
    } catch (_: Exception) {
        return
    }
    try {
        GOOGLE_ORIGINS.forEach { origin ->
            val jar = manager.getCookie(origin) ?: return@forEach
            val host = origin.substringAfter("://")
            jar.split(';').forEach { entry ->
                val name = entry.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                // Secure on every one: Chrome ignores a "__Secure-" or "__Host-" cookie that isn't, even an expired one,
                // and those prefixed cookies are the ones Google's session actually rides on. Both the host-only and
                // the domain-wide forms, because a cookie set on ".google.com" isn't removed by expiring it on the host.
                val expired = "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure"
                manager.setCookie(origin, "$expired; Path=/")
                manager.setCookie(origin, "$expired; Path=/; Domain=$host")
                manager.setCookie(origin, "$expired; Path=/; Domain=.$host")
                manager.setCookie(origin, "$expired; Path=/accounts")
                manager.setCookie(origin, "$expired; Path=/ServiceLogin")
            }
        }
        val storage = WebStorage.getInstance()
        GOOGLE_ORIGINS.forEach { storage.deleteOrigin(it) }
        manager.flush()
    } catch (_: Exception) {
    }
}

/**
 * Full-screen sign-in to YouTube Music: Google's real login page in a WebView, so passkeys and two-step
 * verification work as usual. Once music.youtube.com has loaded, "Import my library" reads the session and hands
 * it over. Nothing is kept: Google's login in the browser is wiped when this opens and again after the import.
 */
@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtMusicLoginScreen(onCaptured: (YtSession) -> Unit, onClose: () -> Unit) {
    val palette = LocalCoverPalette.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var notReady by remember { mutableStateOf(false) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Column(Modifier.fillMaxSize().background(palette.base).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sign in to YouTube Music",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = ink(),
                modifier = Modifier.weight(1f),
            )
            Text(
                "Close",
                style = MaterialTheme.typography.titleSmall,
                color = ink(0.7f),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                clearYouTubeLogin()
                WebView(context).apply {
                    // A fresh browser each time, not just fresh cookies: cached pages and autofill can still lead Google
                    // to the account that was used last.
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = url?.startsWith(MUSIC_ORIGIN) == true
                            if (pageReady) notReady = false
                        }
                    }
                    webView = this
                    loadUrl(LOGIN_URL)
                }
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                if (notReady) "Finish signing in first, then tap again." else "Sign in, then tap the button below once YouTube Music has loaded. Gamut doesn\u2019t keep your login.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (notReady) MaterialTheme.colorScheme.error else ink(0.65f),
            )
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(CircleShape)
                    .background(ink(if (pageReady) 0.95f else 0.2f))
                    .clickable(enabled = pageReady) {
                        val view = webView
                        if (view == null) {
                            notReady = true
                        } else {
                            capture(view, onCaptured = { currentOnCaptured(it) }, onUnavailable = { notReady = true })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Import my library",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (pageReady) (if (palette.isLight) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black) else ink(0.5f),
                )
            }
        }
    }
}

private fun capture(view: WebView, onCaptured: (YtSession) -> Unit, onUnavailable: () -> Unit) {
    val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
    if (cookies == null || !YtSigning.hasApiSid(cookies)) {
        onUnavailable()
        return
    }
    CookieManager.getInstance().flush()
    view.evaluateJavascript(PAGE_PROBE) { raw ->
        val config = try {
            MiniJson.parse(raw ?: "null") as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        if (config == null || config["loggedIn"] != "true") {
            onUnavailable()
            return@evaluateJavascript
        }
        fun field(key: String) = (config[key] as? String)?.takeIf { it.isNotBlank() }
        val pageId = field("pageId")
        onCaptured(
            YtSession(
                cookie = cookies,
                dataSyncId = pageId ?: YtSigning.normalizeDataSyncId(field("dataSyncId")),
                pageId = pageId,
                authUser = field("authUser"),
                visitorData = field("visitorData"),
                clientVersion = field("clientVersion"),
            ),
        )
    }
}