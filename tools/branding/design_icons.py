"""
App icons from the Claude Design icon set, in the colours of the Arsound themes.

The designer's icons (tools/branding/design-icons/<design>.svg, from "Arsound Icons" of the design project) are drawn
in scarlet: a tile with a gradient, sometimes a glow or flowers, and the letter. Each one is made once per theme colour
(the dark accent of every theme in themes.json; a new theme adds a colour by itself): every colour of the drawing is
turned from the scarlet hue to the theme's accent hue, white stays white. The icons are not tied to the themes, they
are picked on their own in Settings → Arsound → Appearance → App icon, in one group per colour.

An Android icon has two layers of 108dp, of which about the middle 72dp show: the tile is spread over the whole
background layer (so the launcher's own shape cuts it, without the drawing's rounded corners), the letter goes to the
foreground layer. Both are bitmaps (WebP, 432 px) like the other decorated icons.

Run: python tools/branding/design_icons.py
It writes the layers, the adaptive icons and puts the icons into AppIcons.kt and AppIconList.java (between the
"design icons" marks); tools/branding/icons.py keeps them when it writes the lists again.
Needs: pip install resvg-py pillow
"""
import colorsys
import io
import json
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/branding/design-icons"
THEMES = ROOT / "patches/src/main/resources/soundcloud/theme/themes.json"
RES = ROOT / "patches/src/main/resources/soundcloud/branding"
KOTLIN = ROOT / "patches/src/main/kotlin/app/arsound/patches/soundcloud/misc/branding/AppIcons.kt"
JAVA = ROOT / "extensions/arsound/src/main/java/app/revanced/extension/soundcloud/branding/AppIconList.java"
SVG = "http://www.w3.org/2000/svg"
LAYER = 432  # px of a 108dp layer at xxxhdpi
TILE = 512  # the drawing's tile; it is the 72dp middle of the layer
BASE = "scarlet"  # the colour the designs are drawn in

# Design id -> (name, English name), in the order of the picker.
DESIGNS = {
    "alaya": ("Плитка", "Tile"), "noir": ("Буква", "Letter"), "glow": ("Свечение", "Glow"),
    "blood": ("Глубокая", "Deep"), "sakura": ("Цветы", "Flowers"), "kawaii": ("Каваи", "Kawaii"),
    "night": ("Ночная", "Night"), "sticker": ("Стикер", "Sticker"),
}
# Theme id -> the group's name (the colour), Russian and English.
GROUPS = {
    "scarlet": ("Алые", "Scarlet"), "cobalt": ("Синие", "Blue"), "mint": ("Мятные", "Mint"),
    "sakura": ("Розовые", "Pink"), "lime": ("Лаймовые", "Lime"),
}
START, END = "// region design icons (tools/branding/design_icons.py)", "// endregion design icons"


def hls(hex_):
    r, g, b = (int(hex_[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return colorsys.rgb_to_hls(r, g, b)


def luminance(r, g, b):
    """How bright a colour looks (relative luminance, sRGB)."""
    def channel(c):
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def recolor(hex_, base, target):
    """The colour turned from the base accent's hue to the target accent's, as bright to the eye as before:
    green and yellow look much brighter than red at the same HSL lightness, and a white letter on a pale lime
    tile would be lost. Greys and white stay as they are."""
    h, l, s = hls(hex_)
    if s < 0.08:
        return hex_
    wanted = luminance(*colorsys.hls_to_rgb(h, l, s))
    h = (h + target[0] - base[0]) % 1.0
    low, high = 0.0, 1.0
    for _ in range(30):
        middle = (low + high) / 2
        if luminance(*colorsys.hls_to_rgb(h, middle, s)) < wanted:
            low = middle
        else:
            high = middle
    r, g, b = colorsys.hls_to_rgb(h, (low + high) / 2, s)
    return f"#{round(r * 255):02x}{round(g * 255):02x}{round(b * 255):02x}"


def recolored(svg, base, target):
    return re.sub(r"#[0-9a-fA-F]{6}\b", lambda m: recolor(m.group(0), base, target), svg)


def is_letter(element):
    """The letter's group (and its shadow): it draws through a mask."""
    return any(child.get("mask") for child in element.iter())


def layers(svg):
    """The background and foreground layers as SVG text, on a 768 px canvas with the 512 px tile in the middle."""
    ET.register_namespace("", SVG)
    root = ET.fromstring(svg)
    background, foreground = [], []
    for child in list(root):
        tag = child.tag.split("}")[1]
        if tag == "defs":
            background.append(child)
            foreground.append(child)
        elif is_letter(child):
            foreground.append(child)
        else:
            if tag == "path" and child.get("d", "").startswith("M 512.00 256.00"):
                # The rounded tile: over the whole layer instead.
                child = ET.Element(f"{{{SVG}}}rect", {"x": "-128", "y": "-128", "width": "768", "height": "768",
                                                       "fill": child.get("fill", "#000")})
            background.append(child)

    def page(elements):
        body = "".join(ET.tostring(e, encoding="unicode") for e in elements)
        return (f'<svg xmlns="{SVG}" viewBox="-128 -128 768 768" width="{LAYER}" height="{LAYER}">{body}</svg>')
    return page(background), page(foreground)


def render(svg_text, target):
    import resvg_py
    from PIL import Image
    png = resvg_py.svg_to_bytes(svg_string=svg_text, width=LAYER, height=LAYER)
    Image.open(io.BytesIO(bytes(png))).convert("RGBA").save(target, "WEBP", quality=88, method=6)


def adaptive(icon_id):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@drawable/arsound_icon_bg_{icon_id}" />\n'
        f'    <foreground android:drawable="@drawable/arsound_icon_fg_{icon_id}" />\n'
        '    <monochrome android:drawable="@drawable/arsound_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )


def items():
    """The icons: id, name, English name, group, English group (for the lists), without writing anything."""
    themes = json.loads(THEMES.read_text(encoding="utf-8"))["themes"]
    result = []
    for theme in themes:
        group, group_en = GROUPS.get(theme["id"], (theme["name"]["ru"], theme["name"]["en"]))
        for design, (name, name_en) in DESIGNS.items():
            result.append({"id": f"d_{design}_{theme['id']}", "name": name, "nameEn": name_en,
                           "group": group, "groupEn": group_en, "design": design, "theme": theme})
    return result


def write_layers():
    base_theme = next(t for t in json.loads(THEMES.read_text(encoding="utf-8"))["themes"] if t["id"] == BASE)
    base = hls(base_theme["dark"]["special"])
    nodpi, anydpi = RES / "drawable-nodpi", RES / "mipmap-anydpi"
    for old in list(nodpi.glob("arsound_icon_*_d_*.webp")) + list(anydpi.glob("arsound_icon_d_*.xml")):
        old.unlink()
    result = items()
    for item in result:
        svg = (SOURCE / f"{item['design']}.svg").read_text(encoding="utf-8")
        if item["theme"]["id"] != BASE:
            svg = recolored(svg, base, hls(item["theme"]["dark"]["special"]))
        background, foreground = layers(svg)
        render(background, nodpi / f"arsound_icon_bg_{item['id']}.webp")
        render(foreground, nodpi / f"arsound_icon_fg_{item['id']}.webp")
        (anydpi / f"arsound_icon_{item['id']}.xml").write_text(adaptive(item["id"]), encoding="utf-8")
    return result


def java_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def replace_region(path, lines, indent):
    text = path.read_text(encoding="utf-8")
    region = f"{indent}{START}\n" + "".join(f"{indent}{line}\n" for line in lines) + f"{indent}{END}\n"
    found = re.search(rf"[ \t]*{re.escape(START)}\n.*?[ \t]*{re.escape(END)}\n", text, re.S)
    if found:
        text = text[:found.start()] + region + text[found.end():]
    else:
        return None
    path.write_text(text, encoding="utf-8")
    return True


def write_lists(result):
    """The icons go at the end of both lists, and into the bitmap set."""
    kotlin = KOTLIN.read_text(encoding="utf-8")
    java = JAVA.read_text(encoding="utf-8")
    if START not in kotlin:
        # At the end of both Kotlin lists (the picker shows a group heading whenever the group changes).
        marks = "    " + START + "\n    " + END + "\n"
        for name in ("APP_ICONS = listOf", "BITMAP_APP_ICONS = setOf"):
            found = re.search(r"internal val " + name + r"\(\n(?:    \"[^\"]+\",\n)*", kotlin)
            kotlin = kotlin[:found.end()] + marks + kotlin[found.end():]
        KOTLIN.write_text(kotlin, encoding="utf-8")
    if START not in java:
        java = java.replace("\n    };\n", "\n            " + START + "\n            " + END + "\n    };\n", 1)
        JAVA.write_text(java, encoding="utf-8")
    ids = [f'"{item["id"]}",' for item in result]
    kotlin = KOTLIN.read_text(encoding="utf-8")
    # Both regions of the Kotlin file get the same ids.
    regions = list(re.finditer(rf"[ \t]*{re.escape(START)}\n.*?[ \t]*{re.escape(END)}\n", kotlin, re.S))
    block = "    " + START + "\n" + "".join(f"    {line}\n" for line in ids) + "    " + END + "\n"
    for found in reversed(regions):
        kotlin = kotlin[:found.start()] + block + kotlin[found.end():]
    KOTLIN.write_text(kotlin, encoding="utf-8")
    rows = [f"{{{java_string(i['id'])}, {java_string(i['name'])}, {java_string(i['nameEn'])}, "
            f"{java_string(i['group'])}, {java_string(i['groupEn'])}}}," for i in result]
    replace_region(JAVA, rows, " " * 12)


def main():
    result = write_layers()
    write_lists(result)
    size = sum(f.stat().st_size for f in (RES / "drawable-nodpi").glob("arsound_icon_*_d_*.webp"))
    print(f"{len(result)} design icons, {size // 1024} KB")


if __name__ == "__main__":
    main()
