package com.gamut.core.designsystem

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class NoticeKind { Info, Success, Warning, Error, Loading }

/** [key] decides whether a new notice replaces the pill with an animation (different key) or just updates it in place (same key). */
@Immutable
data class Notice(val id: Long, val message: String, val kind: NoticeKind, val durationMs: Long, val key: Any)

/**
 * One notice at a time (a new one replaces the old one). Safe to call from ANY thread.
 *
 *   val notifier = LocalNotifier.current
 *   notifier.success("Added to playlist")
 *   val id = notifier.loading("Loading song…"); notifier.update(id, "Getting audio…"); notifier.dismiss(id)
 *
 * Use it only for events the user needs confirmation of (added, removed, failed, waiting), never for
 * things that already show on screen.
 *
 * Nothing can stay on screen forever: every notice, a loading one included, has a hard time limit
 * ([MAX_DURATION_MS] at most), and the user can always swipe it up or tap it away. So a caller that never gets its
 * answer (no internet, a hung request) and never calls [dismiss] can no longer leave a pill stuck.
 */
@Stable
class Notifier {
    var current by mutableStateOf<Notice?>(null)
        private set

    private val lock = Any()
    private var counter = 0L

    /**
     * Shows [message]. Returns the notice id (for [update] / [dismiss]), or [NO_ID] when the message is blank and nothing
     * was shown. [durationMs] is clamped to [MIN_DURATION_MS]..[MAX_DURATION_MS]; zero or negative means the default.
     */
    fun show(message: String, kind: NoticeKind = NoticeKind.Info, durationMs: Long = DEFAULT_DURATION_MS, key: Any = kind to message): Long {
        val text = message.trim().take(MAX_MESSAGE_CHARS)
        if (text.isEmpty()) return NO_ID
        val duration = if (durationMs <= 0L) DEFAULT_DURATION_MS else durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return synchronized(lock) {
            val n = Notice(++counter, text, kind, duration, key)
            current = n
            n.id
        }
    }

    fun info(message: String) = show(message, NoticeKind.Info)
    fun success(message: String) = show(message, NoticeKind.Success)
    fun warning(message: String) = show(message, NoticeKind.Warning, 2800L)
    fun error(message: String) = show(message, NoticeKind.Error, 3200L)

    /**
     * A "working on it" pill. It stays until [dismiss] is called with the returned id, another notice replaces it, the user
     * swipes it away, or [timeoutMs] has passed, whichever comes first. The timeout is what keeps a request that never
     * finishes (no connection) from leaving it up forever.
     */
    fun loading(message: String, timeoutMs: Long = LOADING_TIMEOUT_MS): Long = show(message, NoticeKind.Loading, timeoutMs, key = "loading")

    /** Changes the text of the notice [id] in place (no re-animation, the timer keeps running). Ignored if it is no longer showing. */
    fun update(id: Long, message: String) {
        val text = message.trim().take(MAX_MESSAGE_CHARS)
        if (text.isEmpty()) return
        synchronized(lock) {
            val c = current ?: return
            if (c.id == id) current = c.copy(message = text)
        }
    }

    /** With an [id], only dismisses that notice, so a late dismiss never kills a newer one. */
    fun dismiss(id: Long? = null) {
        synchronized(lock) {
            val c = current ?: return
            if (id == null || c.id == id) current = null
        }
    }

    companion object {
        const val NO_ID = -1L
        const val DEFAULT_DURATION_MS = 2200L
        const val MIN_DURATION_MS = 1200L
        /** The longest any notice can stay up, whatever the caller asked for. */
        const val MAX_DURATION_MS = 20_000L
        const val LOADING_TIMEOUT_MS = 15_000L
        private const val MAX_MESSAGE_CHARS = 200
    }
}

val LocalNotifier = staticCompositionLocalOf { Notifier() }

@Composable
fun rememberNotifier(): Notifier = remember { Notifier() }

// ---- motion: a fast drop from above the screen, a faster exit ----
private const val EXIT_MS = 130
/** A replacement waits until the pill it replaces has been readable for this long (rapid-fire notices skip to the latest). */
private const val MIN_VISIBLE_MS = 450L
private const val SWIPE_FLING_PX_S = 900f

/**
 * Put this ONCE at the root, above everything, inside the AcrylicHost and a CoverTheme.
 *
 * The pill drops in from OUTSIDE the top of the screen (status bar included) on a stiff spring, and leaves the same way
 * a little faster. It follows the finger: drag it up to dismiss (a fling or 36 dp is enough), tap it to dismiss. Only layout
 * placement is animated, so nothing recomposes while it moves.
 *
 * Presentation rules, all handled by one cancellable collector so no case can leave it stuck:
 *  - same [Notice.key]: the pill stays, only the text changes (and the timer restarts if it is a new notice);
 *  - different key: the old pill leaves, the new one drops in; an arrival while the old one is younger than
 *    [MIN_VISIBLE_MS] waits for that, and a newer arrival in the meantime replaces the waiting one;
 *  - no notice: the pill leaves.
 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier, notifier: Notifier = LocalNotifier.current) {
    var shown by remember(notifier) { mutableStateOf<Notice?>(null) }   // what is on screen (lags `current` while the old one leaves)
    val hide = remember(notifier) { Animatable(1f) }                    // 1 = parked above the screen, 0 = resting (the spring may dip a hair below)
    val drag = remember(notifier) { Animatable(0f) }                    // finger offset in px, negative = up
    var dragging by remember(notifier) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 36.dp.toPx() }

    LaunchedEffect(notifier) {
        val enter = spring<Float>(dampingRatio = 0.72f, stiffness = 800f)
        val exit = tween<Float>(EXIT_MS, easing = FastOutLinearInEasing)
        var shownAt = 0L
        snapshotFlow { notifier.current }.collectLatest { target ->
            val onScreen = shown
            when {
                target == null -> {
                    if (onScreen != null) hide.animateTo(1f, exit)
                    shown = null
                    drag.snapTo(0f)
                }
                onScreen == null -> {
                    drag.snapTo(0f)
                    hide.snapTo(1f)
                    shown = target
                    shownAt = SystemClock.elapsedRealtime()
                    hide.animateTo(0f, enter)
                }
                onScreen.key == target.key -> {
                    // Same pill: text only. If a dismissal had started and was cancelled by this arrival, bring it back.
                    shown = target
                    if (hide.value > 0.001f) hide.animateTo(0f, enter)
                }
                else -> {
                    val wait = MIN_VISIBLE_MS - (SystemClock.elapsedRealtime() - shownAt)
                    if (wait > 0L) delay(wait)
                    hide.animateTo(1f, exit)
                    drag.snapTo(0f)
                    shown = target
                    shownAt = SystemClock.elapsedRealtime()
                    hide.animateTo(0f, enter)
                }
            }
        }
    }

    // The clock. Keyed on the id, so updating the text keeps the same timer, a new notice starts a new one, and a
    // pill held under the finger is never pulled away mid-gesture. The duration is already clamped by the Notifier.
    val shownId = shown?.id
    LaunchedEffect(shownId) {
        val n = shown ?: return@LaunchedEffect
        delay(n.durationMs)
        while (dragging) delay(60)
        notifier.dismiss(n.id)
    }

    val n = shown ?: return
    val raw = remember { floatArrayOf(0f) }
    val dragState = rememberDraggableState { dy ->
        raw[0] += dy
        val visual = if (raw[0] > 0f) raw[0] * 0.2f else raw[0]   // a little rubber band downwards, free upwards
        scope.launch { drag.snapTo(visual) }
    }
    Box(
        modifier
            .fillMaxWidth()
            // Placement only: moves the whole host (status bar inset included) by its own height, so "hidden" really is
            // above the top edge of the screen. Layout-phase on purpose: the acrylic pill tracks its position.
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { p.place(0, (-hide.value * p.height + drag.value).roundToInt()) }
            }
            .statusBarsPadding()
            .padding(top = 10.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        NoticePill(
            notice = n,
            onTap = { notifier.dismiss(n.id) },
            modifier = Modifier.draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = {
                    dragging = true
                    drag.stop()
                    raw[0] = drag.value
                },
                onDragStopped = { velocity ->
                    dragging = false
                    if (drag.value < -dismissPx || velocity < -SWIPE_FLING_PX_S) {
                        notifier.dismiss(n.id)
                    } else {
                        drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 600f))
                    }
                    raw[0] = 0f
                },
            ),
        )
    }
}

@Composable
private fun NoticePill(notice: Notice, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val tone = if (LocalCoverPalette.current.isLight) AcrylicTone.Light else AcrylicTone.Dark
    Row(
        modifier
            .widthIn(max = 340.dp)
            // Before clip / fill so the whole pill (not just its content) takes the press scale.
            .pressScale(onClick = onTap, scaleDown = 0.97f, haptics = false)
            .clip(shape)
            .acrylicFill(AcrylicMaterials.Thick, tone)
            .border(1.dp, ink(0.10f), shape)
            // Screen readers announce a new notice without the user having to find it.
            .semantics { liveRegion = LiveRegionMode.Polite }
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
        Crossfade(notice.message, animationSpec = tween(120), label = "notice-text") { text ->
            Text(
                text,
                color = ink(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,   // a long error wraps instead of being cut to one line
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}