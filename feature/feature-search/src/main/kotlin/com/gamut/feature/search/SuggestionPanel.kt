package com.gamut.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl

/**
 * Typeahead under the search field: artists, songs and albums with artwork, then the user's own history and
 * text completions, all ranked on one scale by [SuggestionRanker]. Tapping a row acts on it (open the
 * artist, search the song or album); the arrow on text rows drops the completion into the field to refine it.
 */
@Composable
internal fun SuggestionPanel(
    typed: String,
    suggestions: List<Suggestion>,
    onArtist: (Suggestion.Artist) -> Unit,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(start = DiscoverInset, end = DiscoverInset, bottom = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ink(0.06f)),
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                if (index > 0) {
                    val inset = if (suggestion is Suggestion.Text) 48.dp else 70.dp
                    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(ink(0.08f)))
                }
                when (suggestion) {
                    is Suggestion.Artist -> {
                        EntityRow(
                            title = suggestion.name,
                            subtitle = if (suggestion.followed) "Artist \u00B7 Following" else "Artist",
                            typed = typed,
                            imageUrl = suggestion.imageUrl,
                            circle = true,
                            verified = isVerifiedArtist(suggestion.fans),
                            onClick = { onArtist(suggestion) },
                        )
                    }

                    is Suggestion.Song -> {
                        EntityRow(
                            title = suggestion.title,
                            subtitle = "Song \u00B7 ${suggestion.artist}",
                            typed = typed,
                            imageUrl = suggestion.imageUrl,
                            circle = false,
                            verified = false,
                            onClick = { onSearch(suggestion.searchText) },
                        )
                    }

                    is Suggestion.Album -> {
                        EntityRow(
                            title = suggestion.title,
                            subtitle = if (suggestion.artist.isBlank()) "Album" else "Album \u00B7 ${suggestion.artist}",
                            typed = typed,
                            imageUrl = suggestion.imageUrl,
                            circle = false,
                            verified = false,
                            onClick = { onSearch(suggestion.searchText) },
                        )
                    }

                    is Suggestion.Text -> {
                        TextRow(
                            text = suggestion.text,
                            typed = typed,
                            icon = if (suggestion.origin == Suggestion.Origin.RECENT) TablerIcons.History else TablerIcons.Search,
                            onClick = { onSearch(suggestion.text) },
                            onFill = { onFill(suggestion.text) },
                        )
                    }
                }
            }
        }
    }
}

private val EntityArtSize: Dp = 42.dp

@Composable
private fun EntityRow(
    title: String,
    subtitle: String,
    typed: String,
    imageUrl: String?,
    circle: Boolean,
    verified: Boolean,
    onClick: () -> Unit,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(8.dp)
    val label = remember(title, typed) { highlightedMatch(title, typed) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .pressScale(onClick = onClick, scaleDown = 0.985f)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            url = remember(imageUrl) { imageUrl?.let { squareArtUrl(it, 160) } },
            modifier = Modifier.size(EntityArtSize).clip(shape),
            fallback = { ArtworkFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (verified) VerifiedBadge(Modifier.padding(start = 5.dp), size = 14.dp)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextRow(
    text: String,
    typed: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onFill: () -> Unit,
) {
    val label = remember(text, typed) { highlightedCompletion(text, typed) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pressScale(onClick = onClick, scaleDown = 0.985f)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(EntityArtSize - 14.dp), contentAlignment = Alignment.CenterStart) {
            Icon(icon, null, tint = ink(0.4f), modifier = Modifier.size(15.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = ink(0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .bounceClickable(role = Role.Button, onClickLabel = "Use suggestion", onClick = onFill),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2196", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = ink(0.4f))
        }
    }
}

/** Entity rows: the part the user typed is semi-bold, wherever it matches, so the eye lands on why the row is here. */
private fun highlightedMatch(
    text: String,
    typed: String,
): AnnotatedString {
    val needle = typed.trim()
    if (needle.isEmpty()) return AnnotatedString(text)
    val at = text.indexOf(needle, ignoreCase = true)
    if (at < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(at, at + needle.length)) }
        append(text.substring(at + needle.length))
    }
}

/** Text rows keep the app's completion style: what was typed stays regular, what the completion adds is bold. */
private fun highlightedCompletion(
    text: String,
    typed: String,
): AnnotatedString {
    val needle = typed.trim()
    if (needle.isEmpty() || !text.startsWith(needle, ignoreCase = true) || needle.length >= text.length) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, needle.length))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(needle.length)) }
    }
}

/** A chart entry offered while the field is focused and empty: tap to search for that song. */
@Composable
internal fun TrendingSearchRow(
    rank: Int,
    item: DiscoverItem,
    onClick: () -> Unit,
) {
    InteractiveSurface(
        shape = RowShape,
        pressedScale = 0.98f,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) { _ ->
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (rank <= 3) FontWeight.SemiBold else FontWeight.Normal),
                color = if (rank <= 3) TextPrimary else TextTertiary,
            )
        }
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 160) },
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            fallback = { ArtworkFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = ink(0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
