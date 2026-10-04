"""
Icons for the themes: SoundCloud's icons redrawn with the Lucide icon set (ISC licence, lucide.dev).

Each SoundCloud icon below gets a Lucide icon of the same meaning, as an Android vector drawable with the same name,
size and colour as SoundCloud's own (the colour is read from SoundCloud's file, so tinting and the light, dark and
active variants keep working; an icon on a round backing keeps the backing). The files make up the theme part
"lucideIcons" (patches/src/main/resources/soundcloud/theme/parts/lucideIcons): a theme that lists this part in
themes.json gets them.

Run: python tools/branding/theme_icons.py <lucide-static package folder>
Needs the decoded SoundCloud resources in local/analysis/res-decoded (see BUILDING.md) and picosvg.
The Lucide icons: https://registry.npmjs.org/lucide-static/-/lucide-static-0.460.0.tgz (the version the design used).
"""
import json
import pathlib
import re
import sys

from picosvg.svg_types import SVGPath

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOUNDCLOUD = ROOT / "local/analysis/res-decoded/res/drawable"

# SoundCloud icon -> Lucide icon; "+fill" fills the shape as well (for "active" icons such as a liked heart).
ICONS = {
    "ic_actions_chromecast": "cast",
    "ic_actions_cloud_upload": "circle-arrow-up",
    "ic_actions_upload": "circle-arrow-up",
    "ic_actions_email": "mail",
    "ic_actions_message": "mail",
    "ic_actions_notification": "bell",
    "ic_actions_notification_active": "bell+fill",
    "ic_bell_notification": "bell",
    "ic_actions_navigation_home": "house",
    "ic_actions_navigation_home_active": "house",
    "ic_actions_navigation_stream": "layers",
    "ic_actions_navigation_stream_active": "layers",
    "ic_actions_navigation_search_active": "search",
    "ic_actions_navigation_library": "library",
    "ic_actions_navigation_library_active": "library",
    "ic_actions_search": "search",
    "ic_actions_search_primary": "search",
    "ic_actions_heart": "heart",
    "ic_actions_heart_active": "heart+fill",
    "ic_actions_overflow_vertical": "ellipsis-vertical",
    "ic_more": "ellipsis-vertical",
    "ic_actions_shuffle": "shuffle",
    "ic_actions_shuffle_active": "shuffle",
    "ic_shuffle": "shuffle",
    "ic_shuffle_active": "shuffle",
    "ic_actions_repeat": "repeat",
    "ic_actions_repeat_all_active": "repeat",
    "ic_actions_repeat_once_active": "repeat-1",
    "ic_actions_download_initial": "circle-arrow-down",
    "ic_actions_user_follower": "user-plus",
    "ic_actions_user_following": "user-check",
    "ic_actions_user_following_active": "user-check",
    "ic_actions_user": "user",
    "ic_actions_cog": "settings",
    "ic_actions_back_primary": "arrow-left",
    "ic_navigation_back": "arrow-left",
    "ic_navigation_back_themed": "arrow-left",
    "ic_arrow_back_black_24": "arrow-left",
    "ic_actions_chevron_right": "chevron-right",
    "ic_actions_chevron_right_primary": "chevron-right",
    "ic_actions_chevron_down": "chevron-down",
    "ic_actions_chevron_up": "chevron-up",
    "ic_actions_close": "x",
    "ic_actions_close_primary": "x",
    "ic_navigation_close": "x",
    "ic_actions_comment": "message-square-text",
    "ic_actions_share": "share-2",
    "ic_actions_playlist_playqueue": "list-music",
    "ic_actions_playlist_queue": "list-music",
    "ic_actions_playlist": "list-music",
    "ic_actions_playlist_add_to_playlist": "list-plus",
    "ic_actions_plus": "plus",
    "ic_actions_plus_circle": "circle-plus",
    "ic_actions_sliders_vertical": "sliders-vertical",
    "ic_actions_station": "radio",
    "ic_actions_repost": "repeat-2",
    "ic_actions_repost_active": "repeat-2",
    "ic_actions_lock_closed": "lock",
    "ic_actions_import": "download",
    "ic_actions_delete_bin": "trash-2",
    "ic_actions_edit": "pencil",
    "ic_actions_link": "link",
    "ic_actions_info": "info",
    "ic_actions_copy": "copy",
    "ic_actions_report_flag": "flag",
    "ic_actions_thumbs_down": "thumbs-down",
    "ic_actions_timer": "timer",
    "ic_actions_musical_note": "music",
    "ic_album": "disc-3",
}
# Variants of an icon that differ only in colour or size; they follow their icon unless listed themselves.
MARK = "Made by tools/branding/theme_icons.py."
# Rows of the Library tab (the end of their view ids, library_header_<row>) -> Lucide icon, as in the design.
LIBRARY_ICONS = {
    "likes": "heart", "playlists": "list-music", "albums": "disc-3", "following": "users", "stations": "radio",
    "downloads": "download", "insights": "chart-column", "uploads": "folder-up",
}
VARIANTS = ("light", "dark", "primary", "secondary", "disabled", "small", "themed", "highlight", "large", "white", "black")


def number(value):
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return text if text not in ("-0", "") else "0"


def to_path(tag, a):
    """A Lucide shape as SVG path data."""
    f = lambda key, default=0.0: float(a.get(key, default))
    if tag == "path":
        return a["d"]
    if tag in ("circle", "ellipse"):
        cx, cy = f("cx"), f("cy")
        rx = f("r") if tag == "circle" else f("rx")
        ry = f("r") if tag == "circle" else f("ry")
        return (f"M{number(cx - rx)},{number(cy)}a{number(rx)},{number(ry)} 0 1,0 {number(2 * rx)},0"
                f"a{number(rx)},{number(ry)} 0 1,0 {number(-2 * rx)},0Z")
    if tag == "rect":
        x, y, w, h = f("x"), f("y"), f("width"), f("height")
        rx = f("rx", a.get("ry", 0))
        ry = f("ry", a.get("rx", 0))
        if not rx:
            return f"M{number(x)},{number(y)}h{number(w)}v{number(h)}h{number(-w)}Z"
        return (f"M{number(x + rx)},{number(y)}h{number(w - 2 * rx)}a{number(rx)},{number(ry)} 0 0,1 {number(rx)},{number(ry)}"
                f"v{number(h - 2 * ry)}a{number(rx)},{number(ry)} 0 0,1 {number(-rx)},{number(ry)}"
                f"h{number(-(w - 2 * rx))}a{number(rx)},{number(ry)} 0 0,1 {number(-rx)},{number(-ry)}"
                f"v{number(-(h - 2 * ry))}a{number(rx)},{number(ry)} 0 0,1 {number(rx)},{number(-ry)}Z")
    if tag == "line":
        return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
    if tag in ("polyline", "polygon"):
        points = re.findall(r"-?[\d.]+", a["points"])
        pairs = [f"{points[i]},{points[i + 1]}" for i in range(0, len(points), 2)]
        return "M" + "L".join(pairs) + ("Z" if tag == "polygon" else "")
    raise ValueError(f"Unknown shape {tag}")


def original(name):
    """Root attributes and paths (attributes of each) of SoundCloud's icon; None if it is not a vector."""
    text = (SOUNDCLOUD / f"{name}.xml").read_text(encoding="utf-8")
    root = re.search(r"<vector([^>]*)>", text)
    if not root:
        return None
    attrs = dict(re.findall(r'(android:\w+)="([^"]*)"', root.group(1)))
    paths = [dict(re.findall(r'(android:\w+)="([^"]*)"', path)) for path in re.findall(r"<path([^>]*)/>", text)]
    return attrs, paths


def path_color(path):
    fill = path.get("android:fillColor", "")
    if fill and "transparent" not in fill:
        return fill
    return path.get("android:strokeColor", fill or "?colorDrawablePrimary")


def lucide_paths(shapes, color, fill, stroke_width=2):
    return "\n".join(
        f'        <path android:pathData="{to_path(tag, a)}" android:strokeColor="{color}"'
        f' android:strokeWidth="{number(stroke_width)}" android:strokeLineCap="round" android:strokeLineJoin="round"'
        + (f' android:fillColor="{color}"' if fill else "") + " />"
        for tag, a in shapes)


def vector(name, lucide, nodes):
    found = original(name)
    if found is None or not found[1]:
        return None
    attrs, paths = found
    fill = lucide.endswith("+fill")
    shapes = nodes[lucide.removesuffix("+fill")]
    keep = {key: value for key, value in attrs.items()
            if key in ("android:width", "android:height", "android:tint", "android:autoMirrored", "android:alpha")}
    root = " ".join(f'{key}="{value}"' for key, value in keep.items())
    width = float(attrs.get("android:viewportWidth", 24))
    height = float(attrs.get("android:viewportHeight", 24))

    # A round or square backing (a path filling most of the icon) stays as it is, in its own colour;
    # the Lucide icon takes the place of the drawing on it, in the drawing's colour.
    def covers(path):
        box = SVGPath(d=path["android:pathData"]).bounding_box()
        return box.w >= 0.8 * width and box.h >= 0.8 * height

    backing = [path for path in paths if len({path_color(p) for p in paths}) > 1 and covers(path)]
    drawing = [path for path in paths if path not in backing]
    if backing and drawing:
        boxes = [SVGPath(d=path["android:pathData"]).bounding_box() for path in drawing]
        left, top = min(b.x for b in boxes), min(b.y for b in boxes)
        right, bottom = max(b.x + b.w for b in boxes), max(b.y + b.h for b in boxes)
        size = max(right - left, bottom - top)
        # Lucide draws inside 2..22 of its 24 units.
        scale = size / 20
        dx, dy = (left + right) / 2 - 12 * scale, (top + bottom) / 2 - 12 * scale
        kept = "\n".join("    <path " + " ".join(f'{k}="{v}"' for k, v in path.items()) + " />" for path in backing)
        body = (f"{kept}\n    <group android:scaleX=\"{number(scale)}\" android:scaleY=\"{number(scale)}\""
                f" android:translateX=\"{number(dx)}\" android:translateY=\"{number(dy)}\">\n"
                f"{lucide_paths(shapes, path_color(drawing[-1]), fill)}\n    </group>")
        viewport = f'android:viewportWidth="{number(width)}" android:viewportHeight="{number(height)}"'
    else:
        body = lucide_paths(shapes, path_color(paths[0]), fill).replace("        <path", "    <path")
        viewport = 'android:viewportWidth="24" android:viewportHeight="24"'
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Lucide "{lucide}" (ISC licence) in place of SoundCloud\'s {name}. {MARK} -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" {root} {viewport}>\n{body}\n</vector>\n')


def main():
    if len(sys.argv) < 2:
        sys.exit("Usage: python tools/branding/theme_icons.py <lucide-static package folder>")
    nodes = json.loads((pathlib.Path(sys.argv[1]) / "icon-nodes.json").read_text(encoding="utf-8"))
    out = ROOT / "patches/src/main/resources/soundcloud/theme/parts/lucideIcons/drawable"
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.xml"):
        old.unlink()

    names = {}
    for name, lucide in ICONS.items():
        names[name] = lucide
        for variant in VARIANTS:
            if f"{name}_{variant}" not in ICONS:
                names.setdefault(f"{name}_{variant}", lucide)
    written = 0
    for name, lucide in sorted(names.items()):
        if not (SOUNDCLOUD / f"{name}.xml").is_file():
            continue
        xml = vector(name, lucide, nodes)
        if xml is None:
            continue
        (out / f"{name}.xml").write_text(xml, encoding="utf-8")
        written += 1
    print(f"{written} icons")

    # Part "libraryIcons": icons in front of the rows of the Library tab, which SoundCloud shows without icons.
    # The app looks for arsound_<theme>__arsound_library_<row> (ArsoundTheme.libraryRowIcon).
    library = ROOT / "patches/src/main/resources/soundcloud/theme/parts/libraryIcons/drawable"
    library.mkdir(parents=True, exist_ok=True)
    for old in library.glob("*.xml"):
        old.unlink()
    for row, lucide in LIBRARY_ICONS.items():
        paths = lucide_paths(nodes[lucide], "?colorDrawablePrimary", False).replace("        <path", "    <path")
        (library / f"arsound_library_{row}.xml").write_text(
            f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Lucide "{lucide}" (ISC licence) in front of the Library row "{row}". {MARK} -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"'
            f' android:viewportWidth="24" android:viewportHeight="24">\n{paths}\n</vector>\n', encoding="utf-8")
    print(f"{len(LIBRARY_ICONS)} library row icons")


if __name__ == "__main__":
    main()
