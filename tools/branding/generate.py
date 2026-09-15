"""
Generates the Arsound branding resources bundled with the patches.

Source: the exported "letter A" icon set (PNG glyphs and the drawing animation).
Output: patches/src/main/resources/soundcloud/branding/...

Run: python tools/branding/generate.py [path to the export folder]
"""
import pathlib
import sys

from PIL import Image

EXPORT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "export")
ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "patches/src/main/resources/soundcloud/branding"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# Adaptive icons are 108dp; the launcher mask keeps the central 72dp, the splash screen 160/240 of the icon.
ADAPTIVE_PX = 432  # 108dp at xxxhdpi
LAUNCHER_GLYPH_PX = 200
SPLASH_GLYPH_PX = 230
SPLASH_FRAME_STEP = 2  # the source is 60 fps, two frames per step keep 30 fps
SPLASH_LAST_DRAW_FRAME = 48  # the letter is fully drawn by then; the last frame is held


def glyph(size: int) -> Image.Image:
    source = Image.open(EXPORT / "png/glyph-white/glyph-white-1024.png").convert("RGBA")
    return source.resize((size, size), Image.LANCZOS)


def centered(image: Image.Image, canvas: int) -> Image.Image:
    result = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    result.alpha_composite(image, ((canvas - image.width) // 2, (canvas - image.height) // 2))
    return result


def save(image: Image.Image, relative: str) -> None:
    path = OUT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


LOGO_NAMES = ["ic_logo_cloud", "ic_logo_cloud_active", "ic_logo_cloud_dark", "ic_logo_cloud_light", "ic_logo_cloud_launcher"]
LAUNCHER_FOREGROUND_NAMES = ["ic_launcher_foreground", "ic_launcher_foreground_black", "ic_launcher_foreground_orange", "ic_launcher_foreground_white"]


def lottie_loading(frames: list) -> dict:
    """SoundCloud's loading animation (Lottie) redrawn with the letter: drawing frames as image layers, then a hold."""
    import base64
    import io

    fps = 30
    hold = 15
    width, height, size = 600, 300, 150
    assets, layers = [], []
    for index, frame in enumerate(frames):
        buffer = io.BytesIO()
        frame.resize((size, size), Image.LANCZOS).save(buffer, format="PNG", optimize=True)
        asset_id = f"a{index}"
        assets.append({"id": asset_id, "w": size, "h": size, "e": 1, "u": "",
                       "p": "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()})
        last = index == len(frames) - 1
        layers.append({
            "ddd": 0, "ind": index + 1, "ty": 2, "nm": asset_id, "refId": asset_id, "sr": 1,
            "ks": {"o": {"a": 0, "k": 100}, "r": {"a": 0, "k": 0},
                   "p": {"a": 0, "k": [width / 2, height / 2, 0]}, "a": {"a": 0, "k": [size / 2, size / 2, 0]},
                   "s": {"a": 0, "k": [100, 100, 100]}},
            "ao": 0, "ip": index, "op": index + 1 + (hold if last else 0), "st": 0, "bm": 0,
        })
    return {"v": "4.8.0", "fr": fps, "ip": 0, "op": len(frames) + hold, "w": width, "h": height,
            "nm": "arsound_loading", "ddd": 0, "assets": assets, "layers": layers}


def main() -> None:
    # Launcher foreground, also used as the monochrome layer.
    save(centered(glyph(LAUNCHER_GLYPH_PX), ADAPTIVE_PX), "drawable-nodpi/arsound_launcher_foreground.png")

    # Small icons: notifications, the SoundCloud logo spots and the Arsound menu rows (24dp).
    for density, scale in DENSITIES.items():
        size = round(24 * scale)
        icon = centered(glyph(round(size * 0.84)), size)
        save(icon, f"drawable-{density}/arsound_icon.png")

    # SoundCloud logo spots: PNG instead of the vector, since Compose screens cannot draw bitmap XML.
    for density, scale in DENSITIES.items():
        size = round(24 * scale)
        for name in LOGO_NAMES:
            save(centered(glyph(round(size * 0.84)), size), f"drawable-{density}/{name}.png")
    for name in LAUNCHER_FOREGROUND_NAMES:
        save(centered(glyph(LAUNCHER_GLYPH_PX), ADAPTIVE_PX), f"drawable-nodpi/{name}.png")

    # Splash screen animation frames.
    animation = Image.open(EXPORT / "anim/a-draw-white.apng")
    frames = []
    loading_frames = []
    for index in range(0, SPLASH_LAST_DRAW_FRAME + 1, SPLASH_FRAME_STEP):
        animation.seek(index)
        frame = animation.convert("RGBA").resize((SPLASH_GLYPH_PX, SPLASH_GLYPH_PX), Image.LANCZOS)
        name = f"arsound_splash_{len(frames):02d}"
        save(centered(frame, ADAPTIVE_PX), f"drawable-nodpi/{name}.png")
        frames.append(name)
        loading_frames.append(animation.convert("RGBA"))

    duration = round(1000 / 60 * SPLASH_FRAME_STEP)
    items = "\n".join(
        f'    <item android:drawable="@drawable/{name}" android:duration="{duration if i < len(frames) - 1 else 2000}" />'
        for i, name in enumerate(frames)
    )
    (OUT / "drawable").mkdir(parents=True, exist_ok=True)
    (OUT / "drawable/arsound_splash.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<animation-list xmlns:android="http://schemas.android.com/apk/res/android" android:oneshot="true">\n'
        f"{items}\n"
        "</animation-list>\n",
        encoding="utf-8",
    )
    import json
    (OUT / "raw").mkdir(parents=True, exist_ok=True)
    # Light theme draws a dark letter, dark theme ("raw-night") a white one.
    dark_frames = []
    for frame in loading_frames:
        alpha = frame.getchannel("A")
        dark = Image.new("RGBA", frame.size, (0, 0, 0, 255))
        dark.putalpha(alpha)
        dark_frames.append(dark)
    (OUT / "raw/loading_animation.json").write_text(json.dumps(lottie_loading(dark_frames), separators=(",", ":")), encoding="utf-8")
    (OUT / "raw-night").mkdir(parents=True, exist_ok=True)
    (OUT / "raw-night/loading_animation.json").write_text(json.dumps(lottie_loading(loading_frames), separators=(",", ":")), encoding="utf-8")

    print(f"{len(frames)} splash frames, {duration} ms each")


if __name__ == "__main__":
    main()
