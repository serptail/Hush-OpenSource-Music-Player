#!/usr/bin/env python3
"""Generates core-designsystem/.../TablerIcons.kt from Tabler Icons SVGs.

Usage: python3 tools/gen_tabler.py /path/to/@tabler/icons/icons/outline
  (npm i @tabler/icons; the outline SVGs are in node_modules/@tabler/icons/icons/outline)
Add a row to ICONS to expose another icon, then re-run.
Tabler Icons is MIT licensed, (c) Pawel Kuna.

Tabler outline icons are strokes (2px, round caps and joins, no fill), so each element is converted to path data and
drawn with a stroke. SOLID / FILLED list variants that are also filled with the same colour (e.g. a solid heart).
"""
import re, sys, pathlib

src = pathlib.Path(sys.argv[1])

# Kotlin name -> Tabler file name
ICONS = {
    "Play": "player-play", "Pause": "player-pause",
    "TrackPrev": "player-track-prev", "TrackNext": "player-track-next",
    "Shuffle": "arrows-shuffle", "Repeat": "repeat", "RepeatOne": "repeat-once",
    "Playlist": "playlist", "ListPlus": "playlist-add",
    "Heart": "heart", "Share": "share", "MagicWand": "wand",
    "MoreVertical": "dots-vertical", "ChevronDown": "chevron-down",
    "Close": "x", "Check": "check", "Info": "info-circle",
    "VolumeOff": "volume-3", "VolumeLow": "volume-2", "VolumeHigh": "volume",
    "MusicNote": "music", "Microphone": "microphone-2", "Video": "video",
    "CircleUser": "user-circle", "Timer": "stopwatch", "SquareArrowUp": "square-arrow-up",
    "Equalizer": "adjustments", "Bluetooth": "bluetooth", "BluetoothConnected": "bluetooth-connected",
    "BluetoothOff": "bluetooth-off", "Devices": "devices",
    "Vinyl": "disc", "Headphones": "headphones", "Waveform": "wave-sine", "Box": "box",
    "Expand": "arrows-maximize", "Collapse": "arrows-minimize",
    # Sheets: regular (outline) weight throughout.
    "RippleUp": "ripple-up", "PlayRegular": "player-play",
    "CircleCheck": "circle-check", "CirclePlus": "circle-plus", "CircleOutline": "circle",
    "CircleArrowDown": "circle-arrow-down", "Dots": "dots", "GripVertical": "grip-vertical",
    "Trash": "trash", "ArrowLeft": "arrow-left", "ArrowUp": "arrow-up", "ArrowDown": "arrow-down", "ChevronRight": "chevron-right",
    "User": "user", "Plus": "plus", "Pencil": "pencil", "RowInsertTop": "row-insert-top",
    "Photo": "photo", "Tag": "tag", "World": "world", "Link": "link",
    "History": "history", "BrandShazam": "brand-shazam", "Download": "download", "PackageImport": "package-import", "Archive": "archive",
}
# Kotlin name -> Tabler file name, drawn stroke + fill
FILLED = {"HeartFilled": "heart"}
# Icons in ICONS that are drawn solid (stroke + fill) instead of outlined
SOLID = {"Play", "TrackPrev", "TrackNext"}


def num(v):
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def attrs(tag):
    return {k: v for k, v in re.findall(r'([\w-]+)="([^"]*)"', tag)}


def pts(s):
    n = [float(x) for x in re.findall(r"-?\d*\.?\d+", s)]
    return list(zip(n[0::2], n[1::2]))


def to_path(el, a):
    f = lambda k, d=0.0: float(a.get(k, d))
    if el == "path":
        return a["d"]
    if el in ("circle", "ellipse"):
        cx, cy = f("cx"), f("cy")
        rx = f("r") if el == "circle" else f("rx")
        ry = rx if el == "circle" else f("ry")
        return (f"M{num(cx - rx)} {num(cy)}a{num(rx)} {num(ry)} 0 1 0 {num(2 * rx)} 0"
                f"a{num(rx)} {num(ry)} 0 1 0 {num(-2 * rx)} 0")
    if el == "line":
        return f"M{num(f('x1'))} {num(f('y1'))}L{num(f('x2'))} {num(f('y2'))}"
    if el in ("polyline", "polygon"):
        p = pts(a["points"])
        d = "M" + "L".join(f"{num(x)} {num(y)}" for x, y in p)
        return d + ("Z" if el == "polygon" else "")
    if el == "rect":
        x, y, w, h = f("x"), f("y"), f("width"), f("height")
        rx = f("rx", a.get("ry", 0)) if "rx" in a or "ry" in a else 0.0
        ry = f("ry", rx) if "ry" in a else rx
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx == 0 and ry == 0:
            return f"M{num(x)} {num(y)}H{num(x + w)}V{num(y + h)}H{num(x)}Z"
        return (f"M{num(x + rx)} {num(y)}H{num(x + w - rx)}A{num(rx)} {num(ry)} 0 0 1 {num(x + w)} {num(y + ry)}"
                f"V{num(y + h - ry)}A{num(rx)} {num(ry)} 0 0 1 {num(x + w - rx)} {num(y + h)}"
                f"H{num(x + rx)}A{num(rx)} {num(ry)} 0 0 1 {num(x)} {num(y + h - ry)}"
                f"V{num(y + ry)}A{num(rx)} {num(ry)} 0 0 1 {num(x + rx)} {num(y)}Z")
    raise ValueError(f"unsupported element <{el}>")


def path_data(file):
    svg = (src / f"{file}.svg").read_text()
    parts = []
    for m in re.finditer(r"<(path|circle|ellipse|rect|line|polyline|polygon)\b([^>]*?)/?>", svg):
        a = attrs(m.group(2))
        # Every Tabler icon starts with an invisible 24x24 bounding path (stroke="none"); it draws nothing.
        if a.get("stroke") == "none":
            continue
        assert a.get("fill", "none") == "none", (file, "per-element fill")
        parts.append(to_path(m.group(1), a))
    assert parts, file
    # One path per element: a later element's leading relative "m" would otherwise be read relative to the end of the
    # previous element instead of the origin.
    return parts


out = [
    "package com.gamut.core.designsystem",
    "",
    "import androidx.compose.ui.graphics.Color",
    "import androidx.compose.ui.graphics.SolidColor",
    "import androidx.compose.ui.graphics.StrokeCap",
    "import androidx.compose.ui.graphics.StrokeJoin",
    "import androidx.compose.ui.graphics.vector.ImageVector",
    "import androidx.compose.ui.graphics.vector.PathParser",
    "import androidx.compose.ui.unit.dp",
    "",
    "// GENERATED by tools/gen_tabler.py from Tabler Icons. MIT, (c) Pawel Kuna.",
    "// Do not edit by hand; edit ICONS in the script and re-run.",
    "object TablerIcons {",
    "    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {",
    '        val builder = ImageVector.Builder("TablerIcons.$name", 24.dp, 24.dp, 24f, 24f)',
    "        for (d in paths) {",
    "            builder.addPath(",
    "                PathParser().parsePathString(d).toNodes(),",
    "                fill = if (filled) SolidColor(Color.Black) else null,",
    "                stroke = SolidColor(Color.Black),",
    "                strokeLineWidth = 2f,",
    "                strokeLineCap = StrokeCap.Round,",
    "                strokeLineJoin = StrokeJoin.Round,",
    "            )",
    "        }",
    "        return builder.build()",
    "    }",
    "",
]
for table, filled in ((ICONS, False), (FILLED, True)):
    for kt, file in table.items():
        extra = ", filled = true" if filled or kt in SOLID else ""
        solid = filled or kt in SOLID
        # Solid shapes are closed so the stroke joins at the start point instead of leaving two overlapping caps.
        ds = ", ".join(f'"{d}{"Z" if solid else ""}"' for d in path_data(file))
        out.append(f'    val {kt}: ImageVector by lazy {{ icon("{kt}", {ds}{extra}) }}')
out.append("}")
dest = pathlib.Path(__file__).resolve().parent.parent / "core/core-designsystem/src/main/kotlin/com/gamut/core/designsystem/TablerIcons.kt"
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text("\n".join(out) + "\n")
print("wrote", dest, len(ICONS) + len(FILLED), "icons")
