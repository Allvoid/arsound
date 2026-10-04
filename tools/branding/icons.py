"""
Generates the app icon variants users can pick in Settings → Arsound → App icon.

Source: export/palettes/palettes.json of the "letter A" icon project (python palettes.py there).
Output:
- patches/src/main/resources/soundcloud/branding/drawable/arsound_icon_bg_<id>.xml and arsound_icon_fg_<id>.xml:
  the background and the letter of each variant as vector drawables (gradients included);
- .../mipmap-anydpi/arsound_icon_<id>.xml: the adaptive icon of each variant;
- patches/.../misc/branding/AppIcons.kt and extensions/.../branding/AppIconList.java: the list of variants.

Run: python tools/branding/icons.py <path to export/palettes/palettes.json>
"""
import json
import math
import pathlib
import sys

if len(sys.argv) < 2:
    sys.exit("Usage: python tools/branding/icons.py <path to palettes.json>")
SOURCE = pathlib.Path(sys.argv[1])
ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "patches/src/main/resources/soundcloud/branding"
KOTLIN = ROOT / "patches/src/main/kotlin/app/arsound/patches/soundcloud/misc/branding/AppIcons.kt"
JAVA = ROOT / "extensions/arsound/src/main/java/app/revanced/extension/soundcloud/branding/AppIconList.java"

data = json.loads(SOURCE.read_text(encoding="utf-8"))
CANVAS = data["canvas"]
LETTER_X, LETTER_Y, LETTER_BOX, _ = data["letterBox"]
LETTER_VIEWPORT = data["letterViewport"]
LETTER_PATH = data["letterPath"]
PALETTES = data["palettes"]


def android_color(color):
    """#rrggbb[aa] to Android's #aarrggbb."""
    color = color.lower()
    return "#" + (color[7:9] if len(color) == 9 else "ff") + color[1:7]


def linear_points(angle, x, y, w, h):
    """Start and end points of a CSS-style linear gradient over a box, as in palettes.py."""
    a = math.radians(angle)
    dx, dy = math.sin(a), -math.cos(a)
    half = (abs(w * dx) + abs(h * dy)) / 2
    cx, cy = x + w / 2, y + h / 2
    return (cx - dx * half, cy - dy * half), (cx + dx * half, cy + dy * half)


def fill(spec, box, indent):
    """A path fill: an attribute for a solid colour, an aapt:attr block for a gradient."""
    if spec["type"] == "solid":
        return f' android:fillColor="{android_color(spec["color"])}"', ""
    x, y, w, h = box
    if spec["type"] == "linear":
        (x1, y1), (x2, y2) = linear_points(spec["angle"], x, y, w, h)
        attributes = (f'android:type="linear" android:startX="{x1:.2f}" android:startY="{y1:.2f}" '
                      f'android:endX="{x2:.2f}" android:endY="{y2:.2f}"')
    else:
        cx, cy = spec["center"]
        attributes = (f'android:type="radial" android:centerX="{x + cx * w:.2f}" android:centerY="{y + cy * h:.2f}" '
                      f'android:gradientRadius="{spec["radius"] * w:.2f}"')
    items = "".join(
        f'{indent}            <item android:offset="{offset:.3f}" android:color="{android_color(color)}" />\n'
        for offset, color in spec["stops"]
    )
    block = (f'\n{indent}    <aapt:attr name="android:fillColor">\n'
             f'{indent}        <gradient {attributes}>\n{items}'
             f'{indent}        </gradient>\n{indent}    </aapt:attr>\n{indent}')
    return "", block


def path(path_data, spec, box, indent="    "):
    attribute, block = fill(spec, box, indent)
    if not block:
        return f'{indent}<path android:pathData="{path_data}"{attribute} />\n'
    return f'{indent}<path android:pathData="{path_data}">{block}</path>\n'


def vector(body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
        'xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{CANVAS}dp" android:height="{CANVAS}dp" '
        f'android:viewportWidth="{CANVAS}" android:viewportHeight="{CANVAS}">\n'
        f"{body}</vector>\n"
    )


def background(item):
    square = f"M0,0h{CANVAS}v{CANVAS}h-{CANVAS}z"
    return vector("".join(path(square, layer, (0, 0, CANVAS, CANVAS)) for layer in item["background"]))


def foreground(item):
    scale = LETTER_BOX / LETTER_VIEWPORT
    # The letter path and its gradient are in the letter's own box; the group places and scales it.
    letter = path(LETTER_PATH, item["letter"], (0, 0, LETTER_VIEWPORT, LETTER_VIEWPORT), "        ")
    return vector(
        f'    <group android:translateX="{LETTER_X:.2f}" android:translateY="{LETTER_Y:.2f}" '
        f'android:scaleX="{scale:.6f}" android:scaleY="{scale:.6f}">\n{letter}    </group>\n'
    )


def adaptive(item):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@drawable/arsound_icon_bg_{item["id"]}" />\n'
        f'    <foreground android:drawable="@drawable/arsound_icon_fg_{item["id"]}" />\n'
        '    <monochrome android:drawable="@drawable/arsound_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )


def java_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    for folder in ("drawable", "mipmap-anydpi"):
        (RES / folder).mkdir(parents=True, exist_ok=True)
    for old in list((RES / "drawable").glob("arsound_icon_bg_*.xml")) + list((RES / "drawable").glob("arsound_icon_fg_*.xml")) \
            + list((RES / "mipmap-anydpi").glob("arsound_icon_*.xml")):
        old.unlink()
    for item in PALETTES:
        (RES / f"drawable/arsound_icon_bg_{item['id']}.xml").write_text(background(item), encoding="utf-8")
        (RES / f"drawable/arsound_icon_fg_{item['id']}.xml").write_text(foreground(item), encoding="utf-8")
        (RES / f"mipmap-anydpi/arsound_icon_{item['id']}.xml").write_text(adaptive(item), encoding="utf-8")

    ids = ",\n".join(f'    "{item["id"]}"' for item in PALETTES)
    KOTLIN.write_text(
        "package app.arsound.patches.soundcloud.misc.branding\n\n"
        "// Generated by tools/branding/icons.py from the icon palettes. Do not edit by hand.\n\n"
        "/** The app icon variants, in the order of the picker. The first one is the default. */\n"
        f"internal val APP_ICONS = listOf(\n{ids},\n)\n",
        encoding="utf-8",
    )

    rows = ",\n".join(
        f"            {{{java_string(item['id'])}, {java_string(item['name'])}, {java_string(item['nameEn'])}, "
        f"{java_string(item['group'])}, {java_string(item['groupEn'])}}}"
        for item in PALETTES
    )
    JAVA.parent.mkdir(parents=True, exist_ok=True)
    JAVA.write_text(
        "package app.revanced.extension.soundcloud.branding;\n\n"
        "// Generated by tools/branding/icons.py from the icon palettes. Do not edit by hand.\n\n"
        "/** The app icon variants: id, name, English name, group, English group. The first one is the default. */\n"
        "final class AppIconList {\n"
        "    static final String[][] ICONS = {\n"
        f"{rows},\n"
        "    };\n\n"
        "    private AppIconList() {\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    print(f"{len(PALETTES)} icons written")


if __name__ == "__main__":
    main()
