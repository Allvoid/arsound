"""
Static font files for the Arsound themes, cut from the OFL fonts of Google Fonts.

Each theme replaces SoundCloud's font files (Söhne) slot by slot: regular, semibold, bold and extra bold (headings)
and the numbers font. The files a theme uses are named in its "fonts" in themes.json:
<font>_<weight> (for example golostext_400), or <font>_t<NN>_<weight> with the letters NN hundredths of an em closer
to each other (golostext_t04_800; SoundCloud uses the bold and extra bold files only for headings, and designs
often draw headings tight). <font> is the family name in lower case without spaces.
Only Latin, Cyrillic and punctuation are kept, so the files stay small.

Run: python tools/branding/theme_fonts.py <folder with font files>
The folder holds Google Fonts files: a variable font (GolosText[wght].ttf or GolosText.ttf) or static ones
(MPLUSRounded1c-Bold.ttf). Only the fonts found there are cut again; files no theme uses any more are removed.
tools/branding/theme_from_design.py downloads the fonts of a design by itself.
Needs: pip install fonttools
"""
import json
import pathlib
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "patches/src/main/resources/soundcloud/theme/fonts"
THEMES = ROOT / "patches/src/main/resources/soundcloud/theme/themes.json"
NAME = re.compile(r"^(?P<font>[a-z0-9]+?)(?:_t(?P<tracking>\d\d))?_(?P<weight>\d{3})$")
# Names of static font files by weight, as Google Fonts writes them.
WEIGHTS = {100: "Thin", 200: "ExtraLight", 300: "Light", 400: "Regular", 500: "Medium", 600: "SemiBold",
           700: "Bold", 800: "ExtraBold", 900: "Black"}

UNICODES = [
    *range(0x20, 0x7F),      # Basic Latin
    *range(0xA0, 0x180),     # Latin-1 and Latin Extended-A
    *range(0x400, 0x530),    # Cyrillic and its supplement
    *range(0x2000, 0x2070),  # punctuation: dashes, quotes, ellipsis
    0x20AC, 0x20BD, 0x2116, 0x2122, 0x2190, 0x2192, 0x2212, 0x2026, 0x00D7,
]


def key(name):
    """A font name compared without case, spaces, dashes and the [wght] suffix."""
    return re.sub(r"\[.*?]|[^a-z0-9]", "", name.lower())


def find_source(folder, font, weight):
    """The variable font of the family, or its static file of this weight (or the nearest one); None if absent."""
    files = list(folder.glob("*.ttf"))
    for file in files:
        stem = file.stem
        if "-" not in stem and key(stem) == font and "Italic" not in stem:
            return file, True
    statics = {}
    for file in files:
        family, _, style = file.stem.partition("-")
        if key(family) == font:
            for value, style_name in WEIGHTS.items():
                if style == style_name:
                    statics[value] = file
    if not statics:
        return None
    nearest = min(statics, key=lambda value: abs(value - weight))
    return statics[nearest], False


def track(font, tracking):
    """Moves every letter closer to the next one by the tracking (in em): the advance widths get smaller."""
    delta = round(tracking * font["head"].unitsPerEm)
    metrics = font["hmtx"].metrics
    for glyph, (advance, lsb) in metrics.items():
        if advance > 0:
            metrics[glyph] = (max(0, advance + delta), lsb)


def cut(source, variable, weight, tracking):
    font = TTFont(source)
    if variable and "fvar" in font:
        axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
        axes["wght"] = max(min(weight, max(a.maxValue for a in font["fvar"].axes if a.axisTag == "wght")),
                           min(a.minValue for a in font["fvar"].axes if a.axisTag == "wght"))
        font = instancer.instantiateVariableFont(font, axes)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(font)
    if tracking:
        track(font, tracking)
    return font


def used_fonts():
    themes = json.loads(THEMES.read_text(encoding="utf-8"))["themes"]
    return sorted({name for theme in themes for name in theme.get("fonts", {}).values()})


def main():
    if len(sys.argv) < 2:
        sys.exit("Usage: python tools/branding/theme_fonts.py <folder with font files>")
    source_folder = pathlib.Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    needed = used_fonts()
    for old in OUT.glob("*.ttf"):
        if old.stem not in needed:
            old.unlink()
    for name in needed:
        match = NAME.match(name)
        if not match:
            sys.exit(f"Font name {name} is not <font>_<weight> or <font>_t<NN>_<weight>")
        weight = int(match["weight"])
        tracking = -int(match["tracking"]) / 100 if match["tracking"] else 0.0
        found = find_source(source_folder, match["font"], weight)
        target = OUT / f"{name}.ttf"
        if found:
            cut(found[0], found[1], weight, tracking).save(target)
        elif not target.is_file():
            sys.exit(f"No font file for {name} in {source_folder}")
    for license_file in source_folder.glob("OFL*.txt"):
        (OUT / license_file.name).write_bytes(license_file.read_bytes())
    total = sum(f.stat().st_size for f in OUT.glob("*.ttf"))
    print(f"{len(list(OUT.glob('*.ttf')))} font files, {total // 1024} KB")


if __name__ == "__main__":
    main()
