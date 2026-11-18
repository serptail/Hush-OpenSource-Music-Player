package com.gamut.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.ArrowUpRight
import com.adamglin.phosphoricons.bold.Code
import com.adamglin.phosphoricons.bold.GithubLogo
import com.adamglin.phosphoricons.bold.Globe
import com.adamglin.phosphoricons.bold.PaintBrush
import com.adamglin.phosphoricons.bold.Scales
import com.adamglin.phosphoricons.bold.User
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.R as DesignR

private const val REPO_URL = "https://github.com/serptail/gamut"
private const val REPO_LABEL = "github.com/serptail/gamut"
private const val LICENSE_URL = "$REPO_URL/blob/main/LICENSE"
private const val AUTHOR_URL = "https://github.com/serptail"
private const val AUTHOR_NAME = "serptail"
private const val LOGO_AUTHOR_URL = "https://github.com/serptail"
private const val LOGO_AUTHOR_NAME = "serptail"

private val Libraries =
    listOf(
        Choice("Jetpack Compose, Media3, Hilt", "AndroidX & Google", "Apache License 2.0"),
        Choice("NewPipe Extractor", "TeamNewPipe", "GPL-3.0"),
        Choice("Phosphor Icons", "Phosphor Icons", "MIT License"),
    )

private fun versionLabel(context: Context): String =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Version ${info.versionName}"
    }.getOrDefault("")

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val notifier = LocalNotifier.current
    val clipboard = LocalClipboardManager.current
    val version = remember { versionLabel(context) }
    var showLibraries by remember { mutableStateOf(false) }

    val open: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure { notifier.info("No app can open this link") }
    }
    val external: @Composable () -> Unit = {
        Icon(
            PhosphorIcons.Bold.ArrowUpRight,
            null,
            tint = ink(0.35f),
            modifier = Modifier.padding(start = 6.dp).size(14.dp),
        )
    }

    GamutPage(title = "About", onBack = onBack, nowPlaying = nowPlaying) {
        Hero(
            version = version,
            onVersionClick = {
                clipboard.setText(AnnotatedString("Gamut $version"))
                notifier.info("Version copied")
            },
        )

        GroupedHeader("Project")
        SettingsBlock(Modifier.padding(top = 0.dp)) {
            GroupedRow(
                "Website",
                PhosphorIcons.Bold.Globe,
                subtitle = REPO_LABEL,
                onClick = { open(REPO_URL) },
                trailing = external,
            )
            GroupedDivider()
            GroupedRow(
                "Source code",
                PhosphorIcons.Bold.GithubLogo,
                subtitle = "Browse, fork and contribute on GitHub",
                onClick = { open(REPO_URL) },
                trailing = external,
            )
        }

        GroupedHeader("Credits")
        SettingsBlock(Modifier.padding(top = 0.dp)) {
            GroupedRow(
                "Developer",
                PhosphorIcons.Bold.User,
                subtitle = AUTHOR_NAME,
                onClick = { open(AUTHOR_URL) },
                trailing = external,
            )
            GroupedDivider()
            GroupedRow(
                "Logo",
                PhosphorIcons.Bold.PaintBrush,
                subtitle = "Designed by $LOGO_AUTHOR_NAME",
                onClick = { open(LOGO_AUTHOR_URL) },
                trailing = external,
            )
        }

        GroupedHeader("Legal")
        SettingsBlock(Modifier.padding(top = 0.dp)) {
            GroupedRow(
                "Open source license",
                PhosphorIcons.Bold.Scales,
                subtitle = "Read the license on GitHub",
                onClick = { open(LICENSE_URL) },
                trailing = external,
            )
            GroupedDivider()
            GroupedRow(
                "Open source libraries",
                PhosphorIcons.Bold.Code,
                subtitle = "What Gamut is built on",
                onClick = { showLibraries = true },
            )
        }

        Spacer(Modifier.height(8.dp))
        SupportRow()
        Spacer(Modifier.height(16.dp))
    }

    if (showLibraries) {
        GamutChoiceDialog(
            title = "Open source libraries",
            choices = Libraries,
            selected = "",
            onSelect = {},
            onDismiss = { showLibraries = false },
            note = "Gamut stands on the work of these projects. Thank you.",
        )
    }
}

@Composable
private fun Hero(
    version: String,
    onVersionClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val logo =
            if (LocalCoverPalette.current.isLight) {
                DesignR.drawable.ic_gamut_stacked_color_darktext
            } else {
                DesignR.drawable.ic_gamut_stacked_color_lighttext
            }
        // A soft blurred copy under the logo gives it a little lift off the page.
        Box {
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.22f)),
                modifier = Modifier.size(112.dp).offset(y = 2.dp).blur(2.dp, BlurredEdgeTreatment.Unbounded),
            )
            Image(
                painter = painterResource(logo),
                contentDescription = "Gamut logo",
                modifier = Modifier.size(112.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "A free, open-source music player.",
            style = MaterialTheme.typography.bodyLarge,
            color = ink(0.75f),
            textAlign = TextAlign.Center,
        )

        if (version.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            // Tap to copy, handy when filing a bug report.
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ink(0.08f))
                    .pressScale(onClick = onVersionClick, scaleDown = 0.95f)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(version, style = MaterialTheme.typography.labelLarge, color = ink(0.8f))
            }
        }
    }
}