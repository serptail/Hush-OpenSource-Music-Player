package com.gamut.feature.resolver

import java.util.Locale

fun formatBytes(bytes: Long): String =
    when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        bytes < 1_000_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    }

fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

fun formatEta(seconds: Long): String =
    when {
        seconds < 1 -> "almost done"
        seconds < 60 -> "${seconds}s left"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m left"
    }

/** One line that tells you what a download is doing right now. */
fun DownloadState.Active.describe(): String =
    when (stage) {
        DownloadStage.Preparing -> "Finding best source\u2026"
        DownloadStage.Finishing -> "Tagging & saving\u2026"
        DownloadStage.Downloading ->
            buildList {
                if (bytesTotal > 0) add("${formatBytes(bytesDone)} of ${formatBytes(bytesTotal)}")
                if (bytesPerSec > 0) add(formatSpeed(bytesPerSec))
                etaSeconds?.let { add(formatEta(it)) }
            }.joinToString(" \u00b7 ").ifEmpty { "Starting\u2026" }
    }
