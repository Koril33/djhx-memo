#!/usr/bin/env python3
"""Generate every application icon and the identity sheet from one SVG master.

Development-only tools: Python 3.11+ standard library and ImageMagick 7.1.1-43.
No package install or network access is performed. See ADR-0010.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parent.parent
MASTER = ROOT / "assets/brand/master.svg"
MANIFEST = "assets/brand/generated-manifest.json"
SVG_NS = "http://www.w3.org/2000/svg"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
MAGICK_VERSION = "7.1.1-43"
SIZES = (16, 24, 32, 48, 64, 128, 256, 512, 1024)
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)
ADAPTIVE_SCALE = 0.64
ADAPTIVE_OFFSET = 54 - 64 * ADAPTIVE_SCALE
ALLOWED_PREFIXES = (
    "assets/brand/",
    "desktop/src-tauri/icons/",
    "android/app/src/main/res/",
    "website/",
)


def run(args: list[str], data: bytes | None = None) -> bytes:
    return subprocess.run(args, input=data, check=True, capture_output=True).stdout


def n(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".")


def untag(element: ET.Element) -> ET.Element:
    result = copy.deepcopy(element)
    for node in result.iter():
        node.tag = node.tag.split("}")[-1]
        node.attrib.pop("id", None)
    return result


def xml(element: ET.Element) -> str:
    return ET.tostring(untag(element), encoding="unicode", short_empty_elements=True)


def svg(content: str, size: int = 128, title: str = "DJHX Memo") -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<svg xmlns="{SVG_NS}" width="{size}" height="{size}" '
        f'viewBox="0 0 {size} {size}" role="img" aria-label="{escape(title)}">\n'
        f"{content}\n</svg>\n"
    ).encode()


def path_data(element: ET.Element) -> str:
    tag = element.tag.split("}")[-1]
    if tag == "path":
        return element.attrib["d"]
    if tag != "rect":
        raise ValueError(f"Unsupported master shape: {tag}; extend generator first")
    a = element.attrib
    x, y, w, h = (float(a[key]) for key in ("x", "y", "width", "height"))
    r = min(float(a.get("rx", "0")), w / 2, h / 2)
    if not r:
        return f"M{n(x)} {n(y)}h{n(w)}v{n(h)}h{n(-w)}Z"
    return (
        f"M{n(x+r)} {n(y)}H{n(x+w-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w)} {n(y+r)}"
        f"V{n(y+h-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w-r)} {n(y+h)}"
        f"H{n(x+r)}A{n(r)} {n(r)} 0 0 1 {n(x)} {n(y+h-r)}"
        f"V{n(y+r)}A{n(r)} {n(r)} 0 0 1 {n(x+r)} {n(y)}Z"
    )


def vector(shapes: list[ET.Element], adaptive: bool = False, mono: bool = False) -> bytes:
    size = 108 if adaptive else 128
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Generated from assets/brand/master.svg; do not edit. -->',
        f'<vector xmlns:android="{ANDROID_NS}" android:width="{size}dp" '
        f'android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">',
    ]
    indent = "    "
    if adaptive:
        lines.append(
            f'    <group android:translateX="{n(ADAPTIVE_OFFSET)}" '
            f'android:translateY="{n(ADAPTIVE_OFFSET)}" '
            f'android:scaleX="{n(ADAPTIVE_SCALE)}" android:scaleY="{n(ADAPTIVE_SCALE)}">'
        )
        indent += "    "
    if mono:
        lines.append(
            f'{indent}<path android:fillColor="#FF000000" android:fillType="evenOdd" '
            f'android:pathData="{" ".join(path_data(s) for s in shapes)}" />'
        )
    else:
        for shape in shapes:
            lines.append(
                f'{indent}<path android:fillColor="{shape.attrib["fill"]}" '
                f'android:pathData="{path_data(shape)}" />'
            )
    if adaptive:
        lines.append("    </group>")
    lines.append("</vector>")
    return ("\n".join(lines) + "\n").encode()


def png(source: bytes, size: int, supersample: bool = True) -> bytes:
    density = str(96 * 4) if supersample else "96"
    return run(
        ["magick", "-background", "none", "-density", density, "MSVG:-",
         "-filter", "Lanczos", "-resize", f"{size}x{size}", "-strip",
         "-define", "png:exclude-chunk=date,time", "-define", "png:compression-level=9", "PNG32:-"],
        source,
    )


def ico(images: dict[int, bytes]) -> bytes:
    # Modern Windows (the project baseline is Windows 10) accepts PNG frames.
    header = struct.pack("<HHH", 0, 1, len(ICO_SIZES))
    offset = 6 + 16 * len(ICO_SIZES)
    entries, payloads = [], []
    for size in ICO_SIZES:
        payload = images[size]
        entries.append(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(payload), offset))
        payloads.append(payload)
        offset += len(payload)
    return header + b"".join(entries) + b"".join(payloads)


def identity_sheet(mark: str, background: str, symbol: str, mono_path: str) -> bytes:
    def text(x: int, y: int, content: str, size: int = 18, fill: str = "#52645D", weight: int = 400) -> str:
        family = ' font-family="Noto Sans CJK SC"' if any("\u4e00" <= c <= "\u9fff" for c in content) else ""
        return f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" font-weight="{weight}"{family}>{escape(content)}</text>'

    def use(x: int, y: int, size: int, name: str = "mark") -> str:
        content = mark if name == "mark" else f'<path d="{mono_path}" fill="#294E3B" fill-rule="evenodd"/>'
        return f'<g transform="translate({x} {y}) scale({n(size/128)})">{content}</g>'

    def rule(y: int) -> str:
        return f'<rect x="72" y="{y}" width="1456" height="1" fill="#CAD6CC"/>'

    def rectangle_ring(x: float, y: float, width: float, height: float, thickness: float, color: str) -> str:
        return (
            f'<path d="M{n(x)} {n(y)}h{n(width)}v{n(height)}h{n(-width)}Z'
            f'M{n(x+thickness)} {n(y+thickness)}h{n(width-2*thickness)}v{n(height-2*thickness)}h{n(2*thickness-width)}Z" '
            f'fill="{color}" fill-rule="evenodd"/>'
        )

    adaptive = (
        f'<rect width="108" height="108" fill="{background}"/>'
        f'<g transform="translate({n(ADAPTIVE_OFFSET)} {n(ADAPTIVE_OFFSET)}) scale({n(ADAPTIVE_SCALE)})">{symbol}</g>'
    )

    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="{SVG_NS}" width="1600" height="2000" viewBox="0 0 1600 2000">',
        '<title>DJHX Memo — 品牌视觉规范 / identity system v1</title>',
        '<desc>可编辑双语设计板，由 master.svg 自动生成。完整规范及统一换版步骤见 docs/brand-guidelines.md。</desc>',
        '<defs>',
        '<clipPath id="circle"><circle cx="54" cy="54" r="36"/></clipPath>',
        '<clipPath id="square"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath>',
        '<clipPath id="squircle"><path d="M54 18C85 18 90 23 90 54S85 90 54 90 18 85 18 54 23 18 54 18Z"/></clipPath>',
        '</defs>',
        '<rect width="1600" height="2000" fill="#F7F8F4"/>',
        '<g font-family="Liberation Sans, sans-serif">',
        text(72, 82, "DJHX Memo", 48, "#213E32", 700),
        text(72, 119, "品牌视觉规范 / IDENTITY SYSTEM / V1.0 / 2026-11-07", 16),
        rule(150),
        text(72, 194, "01  主图标 / PRIMARY MARK", 18, "#213E32", 700),
        use(100, 237, 288),
        text(464, 288, "A quiet page, ready for your words.", 34, "#213E32", 700),
        text(464, 335, "所有平台共用一份母版，保留纸张轮廓与四种原色。", 22),
        text(464, 374, "绿色底 / 奶白纸张 / 右上折角 / 三条横线", 22),
        text(464, 413, "图标不含文字；界面品牌仅使用 DJHX Memo。", 22),
        text(464, 485, "应用、窗口和启动器名称 / APP NAME", 14),
        text(464, 524, "DJHX Memo", 30, "#213E32", 700),
        rule(572),
        text(72, 616, "02  构图与安全留白 / CONSTRUCTION + CLEAR SPACE", 18, "#213E32", 700),
        rectangle_ring(74, 624, 320, 320, 1, "#C4D3C7"),
        use(106, 656, 256),
        '<rect x="233.6" y="656" width=".8" height="256" fill="#DCEADC"/>',
        '<rect x="106" y="783.6" width="256" height=".8" fill="#DCEADC"/>',
        text(96, 977, "128 单位网格 / 四周留白 16 单位", 17),
        text(452, 694, "底形 / BACKGROUND", 14),
        text(452, 727, "128 x 128 / corner radius 28", 22, "#213E32"),
        text(452, 778, "纸张 / PAPER", 14),
        text(452, 811, "70 x 84 / origin 29,22 / radius 9", 22, "#213E32"),
        text(452, 862, "横线 / LINES", 14),
        text(452, 895, "38 / 38 / 27 units; 4-unit thickness", 22, "#213E32"),
    ]
    for x, color, label in ((1020, "#3D7963", "GREEN"), (1280, "#F6F4E9", "PAPER"), (1020, "#BED5BB", "FOLD"), (1280, "#6B927A", "INK")):
        y = 654 if label in ("GREEN", "PAPER") else 820
        parts.extend([f'<rect x="{x}" y="{y}" width="210" height="94" rx="14" fill="{color}" stroke="#CCD7CD"/>', text(x, y + 119, label + "  " + color, 16)])
    parts += [
        rule(1018),
        text(72, 1062, "03  尺寸阶梯 / SIZE LADDER", 18, "#213E32", 700),
        text(72, 1095, "16～128 px 按实际尺寸展示；256～1024 px 缩放展示，原尺寸文件均已提供。", 17),
    ]
    x = 88
    for size in SIZES:
        displayed = min(size, 128)
        parts += [use(x + (128-displayed)//2, 1124 + 128-displayed, displayed), text(x + 38, 1287, str(size), 17)]
        x += 160
    parts += [
        rule(1325),
        text(72, 1370, "04  平台遮罩与单色 / PLATFORM MASKS", 18, "#213E32", 700),
        f'<g transform="translate(72 1400) scale({n(236/108)})">',
        adaptive,
        rectangle_ring(21, 21, 66, 66, 0.6, "#FFFFFF"),
        '<path d="M21 54a33 33 0 1 0 66 0a33 33 0 1 0 -66 0ZM21.6 54a32.4 32.4 0 1 0 64.8 0a32.4 32.4 0 1 0 -64.8 0Z" fill="#FFFFFF" fill-rule="evenodd"/>',
        '</g>',
        text(72, 1672, "108 dp / 中央 66 dp 安全区", 17),
    ]
    for x, name, label in ((400, "circle", "圆形 / Circle"), (700, "square", "圆角方形 / Rounded square"), (1000, "squircle", "超椭圆 / Squircle")):
        parts += [f'<g transform="translate({x} 1400) scale({n(236/108)})"><g clip-path="url(#{name})">{adaptive}</g></g>', text(x + 20, 1672, label, 17)]
    parts += [
        '<rect x="1320" y="1424" width="160" height="160" rx="40" fill="#DCEADC"/>',
        '<g color="#294E3B">' + use(1336, 1440, 128, "mono") + '</g>',
        text(1310, 1672, "系统主题 / Monochrome", 17),
        rule(1710),
        text(72, 1756, "05  浅色与深色背景 / LIGHT + DARK", 18, "#213E32", 700),
        '<rect x="72" y="1780" width="700" height="132" rx="18" fill="#FFFFFF" stroke="#D9E1D9"/>',
        use(104, 1806, 80),
        text(216, 1858, "DJHX Memo", 28, "#213E32", 700),
        '<rect x="804" y="1780" width="724" height="132" rx="18" fill="#18251F"/>',
        use(836, 1806, 80),
        text(948, 1858, "DJHX Memo", 28, "#F6F4E9", 700),
        text(72, 1964, "SOURCE  assets/brand/master.svg   |   BUILD  python3 scripts/generate-brand-assets.py   |   CHECK  --check", 16),
        '</g></svg>\n',
    ]
    return "\n".join(parts).encode()


def build() -> dict[str, bytes]:
    root = ET.parse(MASTER).getroot()
    if root.attrib.get("viewBox") != "0 0 128 128":
        raise ValueError("Master viewBox must remain 0 0 128 128; update platform geometry if changing it")
    children = {node.attrib.get("id"): node for node in root.iter()}
    background = children["background"]
    shapes = list(children["symbol"])
    if [s.attrib.get("id") for s in shapes] != ["paper", "fold", "line-one", "line-two", "line-three"]:
        raise ValueError("Master symbol must contain paper, fold and three line shapes in paint order")
    for shape in [background, *shapes]:
        if not re.fullmatch(r"#[0-9A-Fa-f]{6}", shape.attrib.get("fill", "")):
            raise ValueError("Master shapes must use explicit six-digit RGB fills")
        path_data(shape)
    mark, symbol = xml(background) + xml(children["symbol"]), "".join(xml(s) for s in shapes)
    main_svg = svg(mark)
    mono_path = " ".join(path_data(s) for s in shapes)
    assets: dict[str, bytes] = {}
    assets["website/logo.svg"] = main_svg
    pngs = {size: png(main_svg, size) for size in SIZES}
    for size, data in pngs.items():
        assets[f"assets/brand/png/icon-{size}.png"] = data
    for size in (32, 128):
        assets[f"desktop/src-tauri/icons/{size}x{size}.png"] = pngs[size]
    assets["desktop/src-tauri/icons/128x128@2x.png"] = pngs[256]
    assets["desktop/src-tauri/icons/icon.ico"] = ico(pngs)
    assets["assets/brand/monochrome.svg"] = svg(f'<path d="{mono_path}" fill="#000000" fill-rule="evenodd"/>')
    assets["assets/brand/design-sheet.svg"] = identity_sheet(mark, background.attrib["fill"], symbol, mono_path)
    # Keep the design sheet as editable SVG; a PNG is also convenient to review.
    assets["assets/brand/design-sheet.png"] = run(
        ["magick", "-background", "none", "MSVG:-", "-strip", "-define", "png:exclude-chunk=date,time", "PNG32:-"],
        assets["assets/brand/design-sheet.svg"],
    )
    res = "android/app/src/main/res/"
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        assets[f"{res}mipmap-{density}/ic_launcher.png"] = png(main_svg, size)
        circle = svg(f'<circle cx="64" cy="64" r="64" fill="{background.attrib["fill"]}"/>{symbol}')
        assets[f"{res}mipmap-{density}/ic_launcher_round.png"] = png(circle, size)
    assets[f"{res}drawable/ic_memo.xml"] = vector([background, *shapes])
    assets[f"{res}drawable/ic_launcher_foreground.xml"] = vector(shapes, adaptive=True)
    assets[f"{res}drawable/ic_launcher_monochrome.xml"] = vector(shapes, adaptive=True, mono=True)
    assets[f"{res}values/ic_launcher_colors.xml"] = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated from assets/brand/master.svg; do not edit. -->\n'
        f'<resources>\n    <color name="ic_launcher_background">{background.attrib["fill"]}</color>\n</resources>\n'
    ).encode()
    for api in (26, 33):
        content = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated from assets/brand/master.svg; do not edit. -->\n'
            f'<adaptive-icon xmlns:android="{ANDROID_NS}">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            + ('    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n' if api >= 33 else "")
            + '</adaptive-icon>\n'
        ).encode()
        for suffix in ("", "_round"):
            assets[f"{res}mipmap-anydpi-v{api}/ic_launcher{suffix}.xml"] = content
    validate_geometry(shapes, assets)
    assets[MANIFEST] = (json.dumps({
        "format_version": 1,
        "master": "assets/brand/master.svg",
        "master_sha256": hashlib.sha256(MASTER.read_bytes()).hexdigest(),
        "renderer": f"ImageMagick {MAGICK_VERSION} / MSVG / RGBA8 / Lanczos",
        "adaptive": {"viewport": 108, "safe_zone": 66, "scale": ADAPTIVE_SCALE, "translate": ADAPTIVE_OFFSET},
        "files": {path: hashlib.sha256(data).hexdigest() for path, data in sorted(assets.items())},
    }, ensure_ascii=False, indent=2) + "\n").encode()
    return assets


def png_size(data: bytes) -> tuple[int, int]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid PNG signature")
    return struct.unpack(">II", data[16:24])


def validate_geometry(shapes: list[ET.Element], assets: dict[str, bytes]) -> None:
    for path, data in assets.items():
        if path.endswith(".png") and "design-sheet" not in path:
            if path.startswith("assets/brand/png/icon-"):
                expected = int(Path(path).stem.split("-")[-1])
            elif "mipmap-" in path:
                density = path.split("mipmap-", 1)[1].split("/", 1)[0]
                expected = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}[density]
            else:
                expected = 256 if "@2x" in path else int(Path(path).stem.split("x")[0])
            if png_size(data) != (expected, expected):
                raise ValueError(f"Wrong PNG dimensions: {path}")
        if path.endswith(".xml"):
            ET.fromstring(data)
    encoded = assets["desktop/src-tauri/icons/icon.ico"]
    if struct.unpack("<HHH", encoded[:6]) != (0, 1, len(ICO_SIZES)):
        raise ValueError("Wrong ICO header")
    for index, expected in enumerate(ICO_SIZES):
        w, h, _, _, planes, bits, length, offset = struct.unpack("<BBBBHHII", encoded[6 + 16*index:22 + 16*index])
        if ((w or 256), (h or 256), planes, bits) != (expected, expected, 1, 32):
            raise ValueError("Wrong ICO entry")
        if png_size(encoded[offset:offset+length]) != (expected, expected):
            raise ValueError("Wrong ICO PNG payload")
    # Render the actual adaptive foreground and check every occupied sample,
    # including antialiased edges, against the guaranteed central safe circle.
    content = f'<g transform="translate({n(ADAPTIVE_OFFSET)} {n(ADAPTIVE_OFFSET)}) scale({n(ADAPTIVE_SCALE)})">{"".join(xml(s) for s in shapes)}</g>'
    rgba = run(["magick", "-background", "none", "-density", "384", "MSVG:-", "-depth", "8", "rgba:-"], svg(content, 108))
    side = 432
    if len(rgba) != side * side * 4:
        raise ValueError("Unexpected foreground raster dimensions")
    occupied = [math.hypot((i % side + 0.5) / 4 - 54, (i // side + 0.5) / 4 - 54)
                for i in range(side * side) if rgba[4*i+3] > 8]
    if not occupied or max(occupied) > 33:
        raise ValueError("Adaptive foreground falls outside the central 66 dp safe circle")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Rebuild in memory and fail if any generated file drifted")
    args = parser.parse_args()
    if shutil.which("magick") is None:
        parser.error(f"ImageMagick {MAGICK_VERSION} is required; see docs/adr/0010-brand-assets.md")
    version = run(["magick", "-version"]).decode().splitlines()[0]
    if f"ImageMagick {MAGICK_VERSION} " not in version:
        parser.error(f"Pinned renderer required: ImageMagick {MAGICK_VERSION}; got {version}")
    expected = build()
    previous = ROOT / MANIFEST
    old_paths = set(json.loads(previous.read_text())["files"]) if previous.exists() else set()
    stale = old_paths - set(expected)
    changed = [path for path, data in expected.items() if not (ROOT/path).is_file() or (ROOT/path).read_bytes() != data]
    if args.check:
        for path in changed:
            print(f"DRIFT {path}", file=sys.stderr)
        for path in sorted(stale):
            print(f"STALE {path}", file=sys.stderr)
        if changed or stale:
            return 1
        print(f"Brand assets match master.svg: {len(expected)} files; PNG/ICO/XML dimensions and adaptive safe zone verified.")
        return 0
    for path, data in expected.items():
        if not path.startswith(ALLOWED_PREFIXES):
            raise ValueError(f"Refusing unmanaged target: {path}")
        dest = ROOT / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not dest.is_file() or dest.read_bytes() != data:
            dest.write_bytes(data)
    for path in stale:
        if not path.startswith(ALLOWED_PREFIXES) or ".." in Path(path).parts:
            raise ValueError(f"Refusing stale target: {path}")
        (ROOT / path).unlink(missing_ok=True)
    print(f"Generated {len(expected)} brand files from assets/brand/master.svg.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, subprocess.CalledProcessError, ET.ParseError) as error:
        print(f"Brand asset generation failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr.decode(errors="replace"), file=sys.stderr)
        raise SystemExit(1)
