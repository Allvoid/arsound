"""
A theme from a Claude Design file: reads the design's colours and fonts and writes the theme into themes.json.

The phone component of an Arsound design (the .dc.html with the app's screens) keeps its look in an object of design
tokens, `const th = ...`, with the same names in every design: bg, surface, surface2, deep, border, accent, pink,
muted, text2, head (heading font), track (heading letter spacing), rc (artwork corners), coverBorder, mini,
miniBorder, tabbar. A component with several variants picks one with a condition (`anime ? {...} : {...}`).
From the tokens this script makes the whole theme: SoundCloud's palette, its grey scale in the theme's tint, the
fonts (downloaded from Google Fonts and cut by theme_fonts.py), the corners, the decoration of Arsound's screens and
the theme parts (parts/<part>/), filled with the tokens by the theme patch.

Run: python tools/branding/theme_from_design.py <design .dc.html or handoff .zip> <theme id>
         [--variant <name>] [--name-ru <name>] [--name-en <name>]
 or: python tools/branding/theme_from_design.py --from-palette <theme id>
     (no design: a theme that has only SoundCloud's two palettes gets tokens for its dark and light look, the parts,
     colours and decoration; its name, description, palettes, fonts and corners stay)
A theme with this id in themes.json is replaced, otherwise a new one is added at the end.
Then build as usual (build-and-install.cmd).
"""
import argparse
import colorsys
import io
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
THEMES = ROOT / "patches/src/main/resources/soundcloud/theme/themes.json"
PARTS = ROOT / "patches/src/main/resources/soundcloud/theme/parts"
TOKENS = ("bg", "surface", "surface2", "deep", "border", "accent", "pink", "muted", "text2", "tabbar", "mini", "miniBorder")
# SoundCloud's grey scale (extended_palette_grey_*) and its dark surface; the theme's scale keeps their lightness.
SC_GREYS = {"950": "#272727", "900": "#303030", "800": "#454545", "700": "#595959", "600": "#666666",
            "500": "#808080", "400": "#999999", "300": "#C6C6C6", "200": "#DDDDDD", "100": "#F3F3F3", "50": "#F9F9F9"}


# region Reading the design

def design_text(path):
    """The component file with the tokens: the file itself, or the one inside a handoff zip that has them."""
    if path.suffix.lower() != ".zip":
        return path.read_text(encoding="utf-8")
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith(".dc.html"):
                text = archive.read(name).decode("utf-8")
                if re.search(r"\bth\s*=", text) and "accent" in text:
                    return text
    sys.exit(f"No design component with theme tokens in {path}")


def object_at(text, start):
    """The {...} object literal that starts at this index (balanced braces, strings skipped)."""
    depth, i, quote = 0, start, None
    while i < len(text):
        c = text[i]
        if quote:
            if c == "\\":
                i += 1
            elif c == quote:
                quote = None
        elif c in "'\"`":
            quote = c
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1
    sys.exit("Unbalanced token object in the design")


def tokens_of(text, variant):
    """The token object of the variant: `th = cond ? {A} : {B}` gives A when cond is the variant, else B."""
    found = re.search(r"\bth\s*=\s*(?:(\w+)\s*\?\s*)?\{", text)
    if not found:
        sys.exit("The design has no `th = {...}` token object")
    first = object_at(text, found.end() - 1)
    chosen = first
    if found.group(1):
        rest = text[found.end() - 1 + len(first):]
        second_start = rest.index("{")
        second = object_at(rest, second_start)
        chosen = first if variant == found.group(1) else second
        print(f"Variant: {'the first' if chosen is first else 'the second'} token object "
              f"(condition \"{found.group(1)}\", asked for \"{variant or 'default'}\")")
    return {key: value for key, _, value in re.findall(r"(\w+)\s*:\s*(['\"])(.*?)\2", chosen)}


def body_font(text):
    found = re.search(r"body\s*\{[^}]*font-family\s*:\s*'([^']+)'", text)
    return found.group(1) if found else None


def first_family(css):
    found = re.search(r"['\"]([^'\"]+)['\"]", css)
    return found.group(1) if found else css.split(",")[0].strip()

# endregion

# region Colours

def rgba(value):
    """A CSS colour (#rgb, #rrggbb, #rrggbbaa, rgb(), rgba()) as (r, g, b, a) with a from 0 to 1."""
    value = value.strip()
    if value.startswith("#"):
        hex_ = value[1:]
        if len(hex_) == 3:
            hex_ = "".join(c * 2 for c in hex_)
        r, g, b = (int(hex_[i:i + 2], 16) for i in (0, 2, 4))
        a = int(hex_[6:8], 16) / 255 if len(hex_) == 8 else 1.0
        return r, g, b, a
    found = re.match(r"rgba?\(([^)]*)\)", value)
    if found:
        parts = [p.strip() for p in found.group(1).split(",")]
        r, g, b = (int(float(p)) for p in parts[:3])
        a = float(parts[3]) if len(parts) > 3 else 1.0
        return r, g, b, a
    if value == "transparent":
        return 255, 255, 255, 0.0
    raise ValueError(f"Not a colour: {value}")


def hex_of(r, g, b, a=1.0):
    text = f"#{round(r):02X}{round(g):02X}{round(b):02X}"
    return text if a >= 0.999 else text + f"{round(a * 255):02X}"


def mix(one, two, amount):
    """`one` moved toward `two` by amount (0..1)."""
    a, b = rgba(one), rgba(two)
    return hex_of(*(a[i] + (b[i] - a[i]) * amount for i in range(3)))


def lightness(color):
    r, g, b, _ = rgba(color)
    return colorsys.rgb_to_hls(r / 255, g / 255, b / 255)[1]


def grey_scale(tokens):
    """SoundCloud's greys in the theme's tint: each grey sits between the theme colours of nearby lightness."""
    anchors = sorted([
        (lightness("#121212"), tokens["bg"]), (lightness("#272727"), tokens["surface"]),
        (lightness("#454545"), tokens["surface2"]), (lightness("#C6C6C6"), tokens["muted"]),
        (lightness("#DDDDDD"), tokens["text2"]), (1.0, "#FFFFFF"),
    ])
    scale = {}
    for name, grey in SC_GREYS.items():
        level = lightness(grey)
        for (low, low_color), (high, high_color) in zip(anchors, anchors[1:]):
            if low <= level <= high:
                scale[f"extended_palette_grey_{name}"] = mix(low_color, high_color, (level - low) / (high - low))
                break
    return scale

# endregion

# region Fonts

def github_listing(path):
    """A GitHub API answer: through the GitHub CLI when it is signed in (no hourly limit worth noting), else directly
    (60 requests an hour without signing in). None if neither works."""
    try:
        result = subprocess.run(["gh", "api", path], capture_output=True, timeout=60)
        if result.returncode == 0:
            return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    try:
        with urllib.request.urlopen(f"https://api.github.com/{path}") as response:
            return json.load(response)
    except Exception:
        return None


def google_font_files(family, folder):
    """Downloads the family's font files from the Google Fonts repository into the folder."""
    directory = re.sub(r"[^a-z0-9]", "", family.lower())
    for licence in ("ofl", "apache", "ufl"):
        listing = github_listing(f"repos/google/fonts/contents/{licence}/{directory}")
        if not isinstance(listing, list):
            continue
        for entry in listing:
            name = entry["name"]
            if name.endswith(".ttf") or name in ("OFL.txt", "LICENSE.txt"):
                target = folder / (f"OFL-{directory}.txt" if name == "OFL.txt" else name)
                with urllib.request.urlopen(entry["download_url"]) as response:
                    target.write_bytes(response.read())
        return True
    return False


def font_name(family, weight, tracking=0):
    key = re.sub(r"[^a-z0-9]", "", family.lower())
    return f"{key}_t{tracking:02d}_{weight}" if tracking else f"{key}_{weight}"

# endregion


def make_theme(text, theme_id, variant, name_ru, name_en):
    th = tokens_of(text, variant)
    missing = [name for name in ("bg", "surface", "accent", "muted") if name not in th]
    if missing:
        sys.exit(f"The design tokens lack {', '.join(missing)}")
    tokens = {}
    for name in TOKENS:
        if name in th:
            tokens[name] = hex_of(*rgba(th[name]))
    tokens.setdefault("surface2", mix(tokens["surface"], "#FFFFFF", 0.1))
    tokens.setdefault("border", tokens["surface2"])
    tokens.setdefault("deep", mix(tokens["accent"], "#000000", 0.6))
    tokens.setdefault("pink", mix(tokens["accent"], "#FFFFFF", 0.45))
    tokens.setdefault("text2", mix(tokens["muted"], "#FFFFFF", 0.3))
    tokens.setdefault("tabbar", mix(tokens["bg"], tokens["surface"], 0.5))
    # Android draws the mini player without the design's blur behind it, so it is a little less see-through.
    r, g, b, a = rgba(th.get("mini", tokens["surface"]))
    tokens["mini"] = hex_of(r, g, b, min(1.0, a + 0.08))
    tokens.setdefault("miniBorder", hex_of(*rgba(tokens["muted"])[:3], 0.28))

    bg, surface, accent = tokens["bg"], tokens["surface"], tokens["accent"]
    border = th.get("coverBorder", "")
    border_color = re.search(r"(rgba?\([^)]*\)|#[0-9a-fA-F]{3,8})", border)
    image_borders = hex_of(*rgba(border_color.group(1))) if border_color and not border.startswith("0") else "#FFFFFF00"
    dark = {"surface": bg, "primary": "#FFFFFF", "secondary": tokens["muted"],
            "highlight": mix(surface, tokens["surface2"], 0.4), "special": accent, "error": "#FFB020",
            "overlay": bg + "B3", "imageBorders": image_borders, "dialog": surface}
    # The light palette only colours what SoundCloud keeps light on dark screens (the player's round buttons).
    ink = mix("#000000", accent, 0.12)
    light = {"surface": mix("#FFFFFF", accent, 0.03), "primary": ink, "secondary": mix(ink, "#FFFFFF", 0.4),
             "highlight": "#FFFFFFEB", "special": accent, "error": "#B45309", "overlay": ink + "66",
             "imageBorders": ink + "24", "dialog": "#FFFFFF"}

    body = body_font(text) or "Golos Text"
    head = first_family(th.get("head", f"'{body}'"))
    track = re.match(r"(-?[\d.]+)em", th.get("track", "0em"))
    tracking = max(0, round(-float(track.group(1)) * 100)) if track else 0
    fonts = {"regular": font_name(body, 400), "semibold": font_name(body, 700),
             "bold": font_name(head, 800, tracking), "extrabold": font_name(head, 900, tracking),
             "numbers": font_name(body, 500)}

    corner = re.match(r"(\d+)", th.get("rc", "4px"))
    radii = {"card": int(corner.group(1)) if corner else 4, "miniPlayer": 36}

    theme = {
        "id": theme_id,
        "name": {"ru": name_ru, "en": name_en},
        "description": {"ru": f"Из макета Claude Design, шрифт {head}. Всегда тёмная.",
                        "en": f"From a Claude Design mock-up, {head} font. Always dark."},
        "darkOnly": True, "dark": dark, "light": light, "fonts": fonts, "radii": radii,
    }
    theme.update(derived(tokens))
    return theme, {body, head}


def all_parts():
    parts = sorted(part.name for part in PARTS.iterdir() if part.is_dir())
    parts.sort(key=lambda part: part != "lucideIcons")
    return parts


def decor_of(tokens, hello):
    return {"settingsGlow": tokens["deep"], "settingsBadge": tokens["accent"],
            # One quiet colour for every row: the design's bright strip on every third row meant nothing.
            "settingsStrips": [mix(tokens["surface2"], tokens["accent"], 0.45)], "homeHelloColor": hello}


def derived(tokens, light_tokens=None):
    """Everything that follows from the tokens: parts, SoundCloud colours, decoration (and their light look)."""
    accent = tokens["accent"]
    colors = grey_scale(tokens)
    colors["extended_palette_orange_900"] = accent
    # Edges of the server's tiles (search genres and other sections): shades of the theme instead of a rainbow.
    shades = [accent, tokens["pink"], mix(accent, "#FFFFFF", 0.25), mix(accent, "#000000", 0.25), tokens["muted"]]
    for i, name in enumerate(("blue", "green", "magenta", "orange", "purple", "red", "teal", "violet", "yellow")):
        colors[f"sdui_{name}"] = shades[i % len(shades)]
    result = {"tokens": tokens}
    if light_tokens:
        result["tokensLight"] = light_tokens
    result["parts"] = all_parts()
    result["decor"] = dict(decor_of(tokens, tokens["pink"]), shortcutScrim="#00000040")
    if light_tokens:
        result["decorLight"] = decor_of(light_tokens, light_tokens["accent"])
    result["colors"] = colors
    return result


def tokens_from_palette(palette, dark):
    """Design tokens for a theme that has only SoundCloud's palette (the first themes): bg is the surface, cards
    are the dialog colour, the rest is mixed from them, the text colours and the accent."""
    bg, card, highlight = palette["surface"], palette["dialog"], palette["highlight"][:7]
    accent, text, muted = palette["special"], palette["primary"], palette["secondary"]
    surface2 = mix(highlight, text, 0.08 if dark else 0.04)
    if dark:
        mini = hex_of(*rgba(mix(card, accent, 0.1))[:3], 0.9)
        return {"bg": bg, "surface": card, "surface2": surface2, "deep": mix(accent, bg, 0.65), "border": surface2,
                "accent": accent, "pink": mix(accent, "#FFFFFF", 0.45), "muted": muted, "text2": mix(muted, text, 0.4),
                "tabbar": mix(bg, card, 0.5), "mini": mini, "miniBorder": hex_of(*rgba(mix(accent, "#FFFFFF", 0.45))[:3], 0.28)}
    mini = hex_of(*rgba(mix(bg, accent, 0.06))[:3], 0.92)
    return {"bg": bg, "surface": card, "surface2": surface2, "deep": mix(bg, accent, 0.18),
            "border": mix(highlight, text, 0.1), "accent": accent, "pink": accent, "muted": muted,
            "text2": mix(muted, text, 0.4), "tabbar": mix(bg, "#FFFFFF", 0.5), "mini": mini,
            "miniBorder": hex_of(*rgba(accent)[:3], 0.25)}


def from_palette(theme_id):
    """The theme as it is, with tokens, parts and the rest derived from its two palettes."""
    themes = json.loads(THEMES.read_text(encoding="utf-8"))["themes"]
    old = next((t for t in themes if t["id"] == theme_id), None)
    if old is None:
        sys.exit(f"No theme {theme_id} in themes.json")
    keep = ("id", "name", "description", "darkOnly", "dark", "light", "fonts", "radii")
    theme = {key: old[key] for key in keep if key in old}
    dark_only = old.get("darkOnly", False)
    theme.update(derived(tokens_from_palette(old["dark"], True),
                         None if dark_only else tokens_from_palette(old["light"], False)))
    return theme


def theme_block(theme):
    """The theme as themes.json writes them: one line per group, as the hand-written themes are."""
    def obj(value):
        return json.dumps(value, ensure_ascii=False)
    lines = [f'      "{key}": {obj(value)}' for key, value in theme.items()]
    return "    {\n" + ",\n".join(lines) + "\n    }"


def write_theme(theme):
    text = THEMES.read_text(encoding="utf-8")
    block = theme_block(theme)
    existing = re.search(r'    \{\n      "id": "' + theme["id"] + r'".*?\n    }(?=,\n    \{|\n  ])', text, re.S)
    if existing:
        text = text[:existing.start()] + block + text[existing.end():]
    else:
        end = text.rindex("\n  ]")
        text = text[:end] + ",\n" + block + text[end:]
    json.loads(text)
    THEMES.write_text(text, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="A theme from a Claude Design file")
    parser.add_argument("design", type=pathlib.Path, nargs="?")
    parser.add_argument("id", nargs="?")
    parser.add_argument("--variant", default=None)
    parser.add_argument("--name-ru", default=None)
    parser.add_argument("--name-en", default=None)
    parser.add_argument("--from-palette", metavar="ID", default=None,
                        help="no design: put a theme of themes.json into the template from its own palettes")
    args = parser.parse_args()
    if args.from_palette:
        write_theme(from_palette(args.from_palette))
        print(f"Theme {args.from_palette} put into the template. Build with build-and-install.cmd.")
        return
    if not args.design or not args.id:
        parser.error("give the design file and the theme id, or --from-palette <id>")
    text = design_text(args.design)
    # A theme that is already there keeps its name and description unless new ones are given.
    old = next((t for t in json.loads(THEMES.read_text(encoding="utf-8"))["themes"] if t["id"] == args.id), None)
    name_en = args.name_en or (old["name"]["en"] if old else args.id.capitalize())
    name_ru = args.name_ru or (old["name"]["ru"] if old else name_en)
    theme, families = make_theme(text, args.id, args.variant, name_ru, name_en)
    if old and not (args.name_ru or args.name_en):
        theme["description"] = old["description"]
    write_theme(theme)
    print(f"Theme {args.id} written to themes.json")

    with tempfile.TemporaryDirectory() as folder:
        folder = pathlib.Path(folder)
        for family in sorted(families):
            print(f"Font {family}: " + ("downloaded" if google_font_files(family, folder) else "NOT FOUND on Google Fonts"))
        subprocess.run([sys.executable, str(ROOT / "tools/branding/theme_fonts.py"), str(folder)], check=True)
    print("Done. Build with build-and-install.cmd.")


if __name__ == "__main__":
    main()
