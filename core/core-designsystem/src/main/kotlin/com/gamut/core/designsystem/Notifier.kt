package com.gamut.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class NoticeKind { Info, Success, Warning, Error, Loading }

/** [key] decides whether a new notice replaces the pill with an animation (different key) or just updates it in place (same key). */
@Immutable
data class Notice(val id: Long, val message: String, val kind: NoticeKind, val durationMs: Long, val key: Any)

/**
 * One notice at a time (a new one replaces the old one). Call from the main thread.
 *
 *   val notifier = LocalNotifier.current
 *   notifier.success("Added to playlist")
 *   val id = notifier.loading("Loading song…"); notifier.update(id, "Getting audio…"); notifier.dismiss(id)
 *
 * Use it only for events the user needs confirmation of (added, removed, failed, waiting), never for
 * things that already show on screen.
 */
@Stable
class Notifier {
    var current by mutableStateOf<Notice?>(null)
        private set
    private var counter = 0L

    fun show(message: String, kind: NoticeKind = NoticeKind.Info, durationMs: Long = 2200L, key: Any = kind to message): Long {
        val n = Notice(++counter, message, kind, durationMs, key)
        current = n
        return n.id
    }

    fun info(message: String) = show(message, NoticeKind.Info)
    fun success(message: String) = show(message, NoticeKind.Success)
    fun warning(message: String) = show(message, NoticeKind.Warning, 2800L)
    fun error(message: String) = show(message, NoticeKind.Error, 3200L)

    /** Sticky until [dismiss] is called with the returned id, or another notice replaces it. */
    fun loading(message: String): Long = show(message, NoticeKind.Loading, Long.MAX_VALUE, key = "loading")

    /** Changes the text of the notice [id] in place (no re-animation). Ignored if it is no longer showing. */
    fun update(id: Long, message: String) {
        val c = current ?: return
        if (c.id == id) current = c.copy(message = message)
    }

    /** With an [id], only dismisses that notice, so a late dismiss never kills a newer one. */
    fun dismiss(id: Long? = null) {
        val c = current ?: return
        if (id == null || c.id == id) current = null
    }
}

val LocalNotifier = staticCompositionLocalOf { Notifier() }

@Composable
fun rememberNotifier(): Notifier = remember { Notifier() }

/**
 * Put this ONCE at the root, above everything, inside the AcrylicHost and a CoverTheme.
 * Swipe the pill up to dismiss it (loading pills can't be swiped away).
 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier, notifier: Notifier = LocalNotifier.current) {
    val notice = notifier.current

    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        if (n.durationMs != Long.MAX_VALUE) {
            delay(n.durationMs)
            notifier.dismiss(n.id)
        }
    }

    Box(
        modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedContent(
            targetState = notice,
            // Same key (the same message again, or a loading pill changing its text): keep the pill, no re-animation.
            contentKey = { n -> n?.key },
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.72f, stiffness = 420f)) { -it } +
                    fadeIn(tween(140)) +
                    scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.88f)) togetherWith
                    (slideOutVertically(tween(180)) { -it } + fadeOut(tween(130)))
            },
            label = "notice",
        ) { n ->
            if (n != null) NoticePill(n, onDismiss = { notifier.dismiss(n.id) })
        }
    }
}

@Composable
private fun NoticePill(notice: Notice, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val tone = if (LocalCoverPalette.current.isLight) AcrylicTone.Light else AcrylicTone.Dark
    val swipeable = notice.kind != NoticeKind.Loading
    Row(
        Modifier
            .widthIn(max = 340.dp)
            .clip(shape)
            .acrylicFill(AcrylicMaterials.Thick, tone)
            .border(1.dp, ink(0.10f), shape)
            .then(
                if (swipeable) Modifier.pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { _, dy ->
                            total += dy
                            if (total < -24f) { total = 0f; onDismiss() }
                        },
                    )
                } else Modifier,
            )
            .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (notice.kind) {
            NoticeKind.Loading -> CircularProgressIndicator(color = ink(), strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp))
            // Black on light covers, white on dark ones (the two SVGs): ink() picks the right one.
            NoticeKind.Success -> Icon(SuccessTick, null, tint = ink(), modifier = Modifier.size(18.dp))
            NoticeKind.Error -> Icon(ErrorTick, null, tint = ink(), modifier = Modifier.size(18.dp))
            NoticeKind.Warning, NoticeKind.Info -> Icon(AlertTick, null, tint = ink(), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Crossfade(notice.message, animationSpec = tween(160), label = "notice-text") { text ->
            Text(
                text,
                color = ink(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
