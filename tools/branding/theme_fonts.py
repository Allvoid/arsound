"""
Static font files for the Arsound themes, cut from the OFL variable fonts of Google Fonts.

Each theme replaces SoundCloud's font files (Söhne) slot by slot:
regular (400), semibold (600), bold and extra bold (headings) and the numbers font (500).
Only Latin, Cyrillic and punctuation are kept, so the files stay small.

Run: python tools/branding/theme_fonts.py <folder with variable fonts: GolosText.ttf, Onest.ttf, Manrope.ttf, ...>
Output: patches/src/main/resources/soundcloud/theme/fonts/<font>_<weight>.ttf, one file per font and weight;
only the fonts found in the folder are cut again, files of fonts no theme uses any more are removed;
the slots of each theme are listed in ArsoundTheme (extension), which must match THEMES below.
Needs: pip install fonttools
"""
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

if len(sys.argv) < 2:
    sys.exit("Usage: python tools/branding/theme_fonts.py <folder with the variable fonts>")
SOURCE = pathlib.Path(sys.argv[1])
ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "patches/src/main/resources/soundcloud/theme/fonts"

# Slot -> (font, weight) or (font, weight, tracking in em). Slots are SoundCloud's font files: soehne_regular_400,
# soehne_semi_bold_600, soehne_bold_900, soehne_extrafett_900 and roboto_medium_numbers. SoundCloud uses the bold
# and extra bold files only for headings; a tracking makes their letters closer, as the design asks for headings.
# A font with a tracking is saved as <font>_tight_<weight>.ttf.
THEMES = {
    "scarlet": {"regular": ("GolosText", 400), "semibold": ("GolosText", 700), "bold": ("GolosText", 800, -0.04),
                "extrabold": ("GolosText", 900, -0.04), "numbers": ("GolosText", 500)},
    "cobalt": {"regular": ("Manrope", 500), "semibold": ("Manrope", 600), "bold": ("Manrope", 800),
               "extrabold": ("Manrope", 800), "numbers": ("Manrope", 500)},
    "mint": {"regular": ("Geologica", 400), "semibold": ("Geologica", 600), "bold": ("Geologica", 700),
             "extrabold": ("Geologica", 700), "numbers": ("Geologica", 500)},
    "sakura": {"regular": ("Nunito", 600), "semibold": ("Nunito", 700), "bold": ("Nunito", 900),
               "extrabold": ("Nunito", 900), "numbers": ("Nunito", 700)},
    "lime": {"regular": ("Onest", 400), "semibold": ("Onest", 600), "bold": ("Unbounded", 600),
             "extrabold": ("Unbounded", 600), "numbers": ("Onest", 500)},
}

UNICODES = [
    *range(0x20, 0x7F),      # Basic Latin
    *range(0xA0, 0x180),     # Latin-1 and Latin Extended-A
    *range(0x400, 0x530),    # Cyrillic and its supplement
    *range(0x2000, 0x2070),  # punctuation: dashes, quotes, ellipsis
    0x20AC, 0x20BD, 0x2116, 0x2122, 0x2190, 0x2192, 0x2212, 0x2026, 0x00D7,
]


def file_name(font_name, weight, tracking=0.0):
    return f"{font_name.lower()}_{'tight_' if tracking else ''}{weight}.ttf"


def track(font, tracking):
    """Moves every letter closer to the next one by the tracking (in em): the advance widths get smaller."""
    delta = round(tracking * font["head"].unitsPerEm)
    metrics = font["hmtx"].metrics
    for glyph, (advance, lsb) in metrics.items():
        if advance > 0:
            metrics[glyph] = (max(0, advance + delta), lsb)


def instance(font_name, weight, tracking=0.0):
    font = TTFont(SOURCE / f"{font_name}.ttf")
    axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
    axes["wght"] = weight
    static = instancer.instantiateVariableFont(font, axes)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(static)
    if tracking:
        track(static, tracking)
    return static


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    needed = sorted({value for slots in THEMES.values() for value in slots.values()})
    names = {file_name(*font) for font in needed}
    for old in OUT.glob("*.ttf"):
        if old.name not in names:
            old.unlink()
    for font in needed:
        target = OUT / file_name(*font)
        if (SOURCE / f"{font[0]}.ttf").is_file():
            instance(*font).save(target)
        elif not target.is_file():
            sys.exit(f"Missing {font[0]}.ttf in {SOURCE}")
    for license_file in SOURCE.glob("OFL-*.txt"):
        (OUT / license_file.name).write_bytes(license_file.read_bytes())
    total = sum(f.stat().st_size for f in OUT.glob("*.ttf"))
    print(f"{len(list(OUT.glob('*.ttf')))} font files, {total // 1024} KB")


if __name__ == "__main__":
    main()
