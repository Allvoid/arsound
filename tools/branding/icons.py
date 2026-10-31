"""
Generates the app icon variants users can pick in Settings → Arsound → App icon.

Source: export/palettes/palettes.json of the "letter A" icon project (python palettes.py, then fancy.py there).
Flat palettes become vector layers; the decorated ones ("fancy": glow, neon, art) are bitmaps from
export/palettes/fancy, saved as WebP; the "monet" ones are vectors in the system colours of Android 12+,
which follow the wallpaper.
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

import a9_android

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
FANCY = data.get("fancy", [])
FANCY_FOLDER = SOURCE.parent / "fancy"
# The А9 mark on a layer: inside the circle every launcher mask keeps.
MARK_FIT = CANVAS * a9_android.safe_fit()


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


def colours(spec):
    """The colours of a fill: one for a solid, the stops of a gradient."""
    return [spec["color"]] if spec["type"] == "solid" else [color for _, color in spec["stops"]]


def background_colour(item):
    """A rough picture of the tile: the average of its colours."""
    return a9_android.mix([c[:7] for layer in item["background"] for c in colours(layer)])


def accent(item):
    """The accent line of the variant: the tile's own hue, apart from the tile and the letter line."""
    return a9_android.accent_for(background_colour(item), a9_android.mix([c[:7] for c in colours(item["letter"])]))


def foreground(item):
    """
    The А9 mark: the letter line in the variant's letter colour (a gradient too), the accent line in a colour of the
    tile's hue. The mark takes the old letter's box.
    """
    spec = item["letter"]
    block = None
    if spec["type"] != "solid":
        x0, y0, x1, y1 = a9_android.a9.bbox()

        def block(indent):
            # The gradient over the mark's own box, in the lines' coordinates.
            _, gradient = fill(spec, (x0, y0, x1 - x0, y1 - y0), indent)
            return gradient.replace('name="android:fillColor"', 'name="android:strokeColor"')
    return vector(a9_android.vector_group(spec.get("color", "#ffffff"), accent(item), MARK_FIT,
                                          CANVAS / 2, CANVAS / 2, letter_block=block))


def adaptive(item):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@drawable/arsound_icon_bg_{item["id"]}" />\n'
        f'    <foreground android:drawable="@drawable/arsound_icon_fg_{item["id"]}" />\n'
        '    <monochrome android:drawable="@drawable/arsound_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )


def monet_background(mode):
    color = "@android:color/system_accent1_100" if mode == "light" else "@android:color/system_neutral1_900"
    return vector(f'    <path android:pathData="M0,0h{CANVAS}v{CANVAS}h-{CANVAS}z" android:fillColor="{color}" />\n')


def monet_foreground(mode):
    """The mark in the wallpaper's colours: the letter line in the main accent, the accent line in the third one."""
    letter = "@android:color/system_accent1_700" if mode == "light" else "@android:color/system_accent1_200"
    second = "@android:color/system_accent3_500" if mode == "light" else "@android:color/system_accent3_300"
    return vector(a9_android.vector_group(letter, second, MARK_FIT, CANVAS / 2, CANVAS / 2))


def average_colour(image, opaque_only):
    """The average colour of a bitmap: of its drawn pixels, or of its middle."""
    from PIL import Image
    image = image.convert("RGBA").resize((64, 64), Image.LANCZOS)
    pixels = [p for p in image.getdata() if (p[3] > 200 if opaque_only else True)]
    if not pixels:
        return "#ffffff"
    return a9_android.hex_of(*(sum(p[i] for p in pixels) / len(pixels) / 255 for i in range(3)))


def fancy_foreground(item, target):
    """
    The А9 mark over a decorated tile: the letter line in the colour the old letter had, the accent line in the tile's
    hue, and for the glowing ones a soft glow of the accent under the lines.
    """
    from PIL import Image
    letter = average_colour(Image.open(FANCY_FOLDER / f"{item['id']}_fg.png"), True)
    tile = average_colour(Image.open(FANCY_FOLDER / f"{item['id']}_bg.png"), False)
    accent_line = a9_android.accent_for(tile, letter)
    fit = 512 * a9_android.safe_fit()
    glow = ""
    if item["id"].startswith(("glow", "neon")):
        glow = (f'<filter id="g" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="9"/></filter>'
                f'<g filter="url(#g)" opacity="0.8">{a9_android.a9.mark(accent_line, accent_line, fit)}</g>')
    svg = a9_android.a9.svg(glow + a9_android.a9.mark(letter, accent_line, fit))
    a9_android.png(svg, 432).save(target, "WEBP", quality=90, method=6)


def webp(source, target):
    from PIL import Image
    Image.open(source).save(target, "WEBP", quality=88, method=6)


def java_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    for folder in ("drawable", "drawable-nodpi", "mipmap-anydpi"):
        (RES / folder).mkdir(parents=True, exist_ok=True)
    for old in list((RES / "drawable").glob("arsound_icon_bg_*.xml")) + list((RES / "drawable").glob("arsound_icon_fg_*.xml")) \
            + list((RES / "drawable-nodpi").glob("arsound_icon_*_*.webp")) \
            + list((RES / "mipmap-anydpi").glob("arsound_icon_*.xml")):
        old.unlink()
    for item in PALETTES:
        (RES / f"drawable/arsound_icon_bg_{item['id']}.xml").write_text(background(item), encoding="utf-8")
        (RES / f"drawable/arsound_icon_fg_{item['id']}.xml").write_text(foreground(item), encoding="utf-8")
        (RES / f"mipmap-anydpi/arsound_icon_{item['id']}.xml").write_text(adaptive(item), encoding="utf-8")
    bitmaps = []
    for item in FANCY:
        if "monet" in item:
            (RES / f"drawable/arsound_icon_bg_{item['id']}.xml").write_text(monet_background(item["monet"]), encoding="utf-8")
            (RES / f"drawable/arsound_icon_fg_{item['id']}.xml").write_text(monet_foreground(item["monet"]), encoding="utf-8")
        else:
            webp(FANCY_FOLDER / f"{item['id']}_bg.png", RES / f"drawable-nodpi/arsound_icon_bg_{item['id']}.webp")
            fancy_foreground(item, RES / f"drawable-nodpi/arsound_icon_fg_{item['id']}.webp")
            bitmaps.append(item["id"])
        (RES / f"mipmap-anydpi/arsound_icon_{item['id']}.xml").write_text(adaptive(item), encoding="utf-8")
    everything = PALETTES + FANCY

    ids = ",\n".join(f'    "{item["id"]}"' for item in everything)
    bitmap_ids = ",\n".join(f'    "{id}"' for id in bitmaps)
    KOTLIN.write_text(
        "package app.arsound.patches.soundcloud.misc.branding\n\n"
        "// Generated by tools/branding/icons.py from the icon palettes. Do not edit by hand.\n\n"
        "/** The app icon variants, in the order of the picker. The first one is the default. */\n"
        f"internal val APP_ICONS = listOf(\n{ids},\n)\n\n"
        "/** The variants whose layers are WebP bitmaps (drawable-nodpi) instead of vector drawables. */\n"
        f"internal val BITMAP_APP_ICONS = setOf(\n{bitmap_ids},\n)\n",
        encoding="utf-8",
    )

    rows = ",\n".join(
        f"            {{{java_string(item['id'])}, {java_string(item['name'])}, {java_string(item['nameEn'])}, "
        f"{java_string(item['group'])}, {java_string(item['groupEn'])}}}"
        for item in everything
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
    size = sum(f.stat().st_size for f in (RES / "drawable-nodpi").glob("arsound_icon_*_*.webp"))
    print(f"{len(everything)} icons written, bitmaps {size // 1024} KB")
    # The icons from the Claude Design set (tools/branding/design_icons.py) are added back to the lists.
    import design_icons
    design_icons.main()


if __name__ == "__main__":
    main()
