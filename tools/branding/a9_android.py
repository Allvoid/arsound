"""
The А9 mark for Android: the four lines of the logo (a9.py of the letter-a-icon project) as vector drawables, an
animated vector for the start screen, a vector Lottie for the loading animation and bitmaps where a bitmap is needed.

А9 is the katakana ア drawn as two parallel lines: the letter line and the accent line. The animations are the logo's
"meet" (the letter line draws from the start of each stroke, the accent line from its end) without the settle and glow
it ends with in the logo: here it plays at every start and on every loading screen, where the breath looked odd.

Used by generate.py, icons.py and design_icons.py; run generate.py to write the branding resources.
"""
import colorsys
import json
import math
import pathlib
import sys

LETTER_ICON = pathlib.Path(__file__).resolve().parents[3].parent / "PycharmProjects/letter-a-icon"


def load_a9(folder=None):
    """The a9 module of the letter-a-icon project (its geometry, colours and timing)."""
    path = pathlib.Path(folder) if folder else LETTER_ICON
    if not (path / "a9.py").exists():
        sys.exit(f"a9.py not found in {path}: pass the letter-a-icon folder")
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))
    import a9
    return a9


a9 = load_a9()

AZURE = a9.AZURE            # the accent line on dark
AZURE_DEEP = a9.AZURE_DEEP  # the accent line on light
WHITE, INK = a9.WHITE, a9.INK
# The meet animation of the logo, without its settle and glow.
TIMING = a9.TIMING["meet"]
DRAW_MS = a9.motion_end("meet")
# The easing of the drawing, cubic-bezier(.65,0,.25,1).
EASE = (0.65, 0.0, 0.25, 1.0)


# --- colours -------------------------------------------------------------------------------

def rgb(hex_):
    hex_ = hex_.lstrip("#")
    return tuple(int(hex_[i:i + 2], 16) / 255 for i in (0, 2, 4))


def hex_of(r, g, b):
    return "#" + "".join(f"{round(max(0, min(1, c)) * 255):02x}" for c in (r, g, b))


def luminance(hex_):
    def channel(c):
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb(hex_)
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def mix(colours):
    """The average of colours, a rough picture of a gradient."""
    parts = [rgb(c) for c in colours]
    return hex_of(*(sum(p[i] for p in parts) / len(parts) for i in range(3)))


def accent_for(background, letter):
    """
    The colour of the accent line of an icon variant, matched to its background: the background's own hue, light on a
    dark tile and dark on a light one, far enough from the tile and from the letter line to read as a second line.
    A grey, black or white tile gets the logo's azure.
    """
    h, l, s = colorsys.rgb_to_hls(*rgb(background))
    dark = luminance(background) < 0.3
    if s < 0.14 or (l < 0.08 or l > 0.95):
        return AZURE if dark else AZURE_DEEP
    best = None
    for step in range(0, 41):
        lightness = (0.62 + step * 0.008) if dark else (0.42 - step * 0.008)
        candidate = hex_of(*colorsys.hls_to_rgb(h, max(0.08, min(0.92, lightness)), max(s, 0.72)))
        score = min(contrast(candidate, background) / 2.2, contrast(candidate, letter) / 1.35)
        if best is None or score > best[0]:
            best = (score, candidate)
        if contrast(candidate, background) >= 2.2 and contrast(candidate, letter) >= 1.35:
            return candidate
    return best[1]


def android_color(color):
    """#rrggbb[aa] to Android's #aarrggbb; references (@color/..., ?attr) stay."""
    if not color.startswith("#"):
        return color
    color = color.lower()
    return "#" + (color[7:9] if len(color) == 9 else "ff") + color[1:7]


# --- the vector -------------------------------------------------------------------------------

def lines(small=False):
    """[(points, role)] of the four lines, role "letter" or "accent", on the logo's 512 grid."""
    return a9.lines(small)


def path_data(points, reverse=False):
    return a9.d(points, reverse)


def placement(fit, cx, cy, small=False):
    return a9.placement(fit, cx, cy, small)


def stroke_attributes(width):
    return (f'android:strokeWidth="{width}" android:strokeLineCap="round" android:strokeLineJoin="round" '
            f'android:fillColor="#00000000"')


def vector_group(letter, accent, fit, cx, cy, indent="    ", small=False, letter_block=None, extra=None):
    """
    A vector drawable group with the four lines placed and sized. letter and accent are colours (#rrggbb or a
    reference); letter_block(points) may give an aapt:attr block for a gradient letter line instead of the colour.
    extra(i, role) adds attributes to a line (the names of the animated paths).
    """
    tx, ty, s = placement(fit, cx, cy, small)
    out = [f'{indent}<group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" '
           f'android:scaleX="{s:.5f}" android:scaleY="{s:.5f}">\n']
    for i, (points, role) in enumerate(lines(small)):
        attrs = stroke_attributes(a9.width(small))
        more = extra(i, role) if extra else ""
        data = path_data(points, reverse=role == "accent" and extra is not None)
        if role == "letter" and letter_block:
            out.append(f'{indent}    <path android:pathData="{data}" {attrs}{more}>{letter_block(indent + "    ")}</path>\n')
        else:
            colour = android_color(letter if role == "letter" else accent)
            out.append(f'{indent}    <path android:pathData="{data}" android:strokeColor="{colour}" {attrs}{more} />\n')
    out.append(f"{indent}</group>\n")
    return "".join(out)


def reach():
    """How far the mark reaches from the middle of its box, line ends included, as a part of the box's longer side."""
    x0, y0, x1, y1 = a9.bbox()
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    radius = max(math.hypot(x - cx, y - cy) for points, _ in lines() for x, y in points) + a9.WIDTH / 2
    return radius / max(x1 - x0, y1 - y0)


def safe_fit():
    """
    The size of the mark on an adaptive icon layer, as a part of the layer: the mark stays inside the circle of 66dp
    of the 108dp layer that every launcher mask keeps.
    """
    return 0.30 / reach()


def vector(size_dp, viewport, body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
        'xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{size_dp}dp" android:height="{size_dp}dp" '
        f'android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
        f"{body}</vector>\n"
    )


# --- the start screen ---------------------------------------------------------------------------

def splash_avd(letter=WHITE, accent=AZURE, size_dp=108, fit_part=None):
    """
    The start screen's icon: the meet drawing as an animated vector (trimPathEnd of each line, the accent lines drawn
    from their end), on a transparent 108dp square like the start screen's icon. Android cuts that icon to the circle
    of its middle two thirds; the mark is made as big as fits in it with a little room, or its corners were cut off.
    """
    if fit_part is None:
        fit_part = 0.31 / reach()
    viewport = 512
    fit = viewport * fit_part
    names = [f"line{i}" for i in range(4)]
    body = vector_group(letter, accent, fit, viewport / 2, viewport / 2, "        ",
                        extra=lambda i, role: f' android:name="{names[i]}" android:trimPathEnd="0"')
    inner = vector(size_dp, viewport, body).split("\n", 1)[1]  # without the XML declaration
    inner = inner.replace(' xmlns:android="http://schemas.android.com/apk/res/android" '
                          'xmlns:aapt="http://schemas.android.com/aapt"', "")
    targets = []
    for i, (delay, duration) in enumerate(TIMING):
        targets.append(
            f'    <target android:name="{names[i]}">\n'
            '        <aapt:attr name="android:animation">\n'
            f'            <objectAnimator android:propertyName="trimPathEnd" android:valueFrom="0" android:valueTo="1"\n'
            f'                android:valueType="floatType" android:startOffset="{delay}" android:duration="{duration}">\n'
            '                <aapt:attr name="android:interpolator">\n'
            f'                    <pathInterpolator android:controlX1="{EASE[0]}" android:controlY1="{EASE[1]}" '
            f'android:controlX2="{EASE[2]}" android:controlY2="{EASE[3]}" />\n'
            '                </aapt:attr>\n'
            '            </objectAnimator>\n'
            '        </aapt:attr>\n'
            '    </target>\n')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- The А9 mark drawing itself (the logo\'s "meet" without its settle): tools/branding/generate.py. -->\n'
        '<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" '
        'xmlns:aapt="http://schemas.android.com/aapt">\n'
        '    <aapt:attr name="android:drawable">\n'
        + "".join("        " + line + "\n" for line in inner.strip().split("\n"))
        + "    </aapt:attr>\n"
        + "".join(targets)
        + "</animated-vector>\n"
    )


# --- the loading animation ---------------------------------------------------------------------

LOADING_FPS = 60
LOADING_HOLD_MS = 450
LOADING_FADE_MS = 300
LOADING_REST_MS = 150


def lottie_loading(letter, accent, width=600, height=300, fit=150):
    """
    SoundCloud's loading animation (Lottie, played over and over) redrawn as the mark: four stroked shapes whose trim
    paths grow as in the meet drawing, a short hold, a fade and again. Vector shapes, so the theme recolours the lines
    by their names ("letter", "accent") instead of decoding frames.
    """
    fps = LOADING_FPS
    total_ms = DRAW_MS + LOADING_HOLD_MS + LOADING_FADE_MS + LOADING_REST_MS
    frames = round(total_ms * fps / 1000)
    tx, ty, s = placement(fit, width / 2, height / 2)

    def frame(ms):
        return round(ms * fps / 1000, 3)

    def colour(hex_):
        return [round(c, 4) for c in rgb(hex_)] + [1]

    shapes = []
    for i, (points, role) in enumerate(lines()):
        # The accent line runs from its end towards the letter line, as in the logo.
        pts = points[::-1] if role == "accent" else points
        delay, duration = TIMING[i]
        shapes.append({
            "ty": "gr", "nm": role,
            "it": [
                {"ty": "sh", "nm": "path", "ks": {"a": 0, "k": {
                    "c": False, "v": [[round(x, 2), round(y, 2)] for x, y in pts],
                    "i": [[0, 0]] * len(pts), "o": [[0, 0]] * len(pts)}}},
                {"ty": "tm", "nm": "draw", "s": {"a": 0, "k": 0}, "o": {"a": 0, "k": 0}, "m": 1,
                 "e": {"a": 1, "k": [
                     {"t": frame(delay), "s": [0], "o": {"x": [EASE[0]], "y": [EASE[1]]},
                      "i": {"x": [EASE[2]], "y": [EASE[3]]}},
                     {"t": frame(delay + duration), "s": [100]}]}},
                {"ty": "st", "nm": role, "c": {"a": 0, "k": colour(letter if role == "letter" else accent)},
                 "o": {"a": 0, "k": 100}, "w": {"a": 0, "k": a9.WIDTH}, "lc": 2, "lj": 2, "ml": 4},
                {"ty": "tr", "p": {"a": 0, "k": [0, 0]}, "a": {"a": 0, "k": [0, 0]}, "s": {"a": 0, "k": [100, 100]},
                 "r": {"a": 0, "k": 0}, "o": {"a": 0, "k": 100}},
            ],
        })
    fade_start = DRAW_MS + LOADING_HOLD_MS
    layer = {
        "ddd": 0, "ind": 1, "ty": 4, "nm": "mark", "sr": 1, "ao": 0, "ip": 0, "op": frames, "st": 0, "bm": 0,
        "ks": {
            "o": {"a": 1, "k": [{"t": frame(fade_start), "s": [100], "o": {"x": [0.4], "y": [0]}, "i": {"x": [0.6], "y": [1]}},
                                {"t": frame(fade_start + LOADING_FADE_MS), "s": [0]}]},
            "r": {"a": 0, "k": 0}, "p": {"a": 0, "k": [tx, ty, 0]}, "a": {"a": 0, "k": [0, 0, 0]},
            "s": {"a": 0, "k": [s * 100, s * 100, 100]},
        },
        "shapes": shapes,
    }
    return {"v": "5.7.4", "fr": fps, "ip": 0, "op": frames, "w": width, "h": height, "nm": "arsound_loading",
            "ddd": 0, "assets": [], "layers": [layer]}


# --- bitmaps -------------------------------------------------------------------------------------

def svg_mark(letter, accent, size, fit_part, small=False):
    """An SVG of the mark on a transparent square, for resvg."""
    return a9.svg(a9.mark(letter, accent, 512 * fit_part, small=small))


def png(svg_text, size):
    import io

    import resvg_py
    from PIL import Image
    data = bytes(resvg_py.svg_to_bytes(svg_string=svg_text, width=size, height=size))
    return Image.open(io.BytesIO(data)).convert("RGBA")
