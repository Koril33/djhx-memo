#!/usr/bin/env python3
"""Generate offline desktop/Android adapters from the pinned Lucide SVG subset."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "assets/ui-icons"
TAGS = {"path", "circle", "ellipse", "line", "rect", "polyline", "polygon"}


def number(value: float) -> str:
    return f"{value:g}"


def path_data(tag: str, attrs: dict[str, str]) -> str:
    def n(key: str, default: float = 0) -> float:
        return float(attrs.get(key, default))

    def point(x: float, y: float) -> str:
        return f"{number(x)},{number(y)}"

    if tag == "path":
        return attrs["d"]
    if tag == "line":
        return f"M{point(n('x1'), n('y1'))} L{point(n('x2'), n('y2'))}"
    if tag in {"circle", "ellipse"}:
        cx, cy = n("cx"), n("cy")
        rx, ry = (n("r"), n("r")) if tag == "circle" else (n("rx"), n("ry"))
        return (
            f"M{point(cx-rx, cy)} a{point(rx, ry)} 0,1 0 {point(rx*2, 0)} "
            f"a{point(rx, ry)} 0,1 0 {point(-rx*2, 0)} Z"
        )
    if tag in {"polyline", "polygon"}:
        values = re.findall(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", attrs["points"])
        if len(values) < 4 or len(values) % 2:
            raise ValueError("Invalid SVG points")
        points = [f"{values[i]},{values[i+1]}" for i in range(0, len(values), 2)]
        return "M" + " L".join(points) + (" Z" if tag == "polygon" else "")
    x, y, width, height = n("x"), n("y"), n("width"), n("height")
    rx = min(n("rx", n("ry")), width / 2)
    ry = min(n("ry", rx), height / 2)
    if not rx or not ry:
        return f"M{point(x,y)} h{number(width)} v{number(height)} h{number(-width)} Z"
    return (
        f"M{point(x+rx,y)} H{number(x+width-rx)} A{point(rx,ry)} 0,0 1 {point(x+width,y+ry)} "
        f"V{number(y+height-ry)} A{point(rx,ry)} 0,0 1 {point(x+width-rx,y+height)} "
        f"H{number(x+rx)} A{point(rx,ry)} 0,0 1 {point(x,y+height-ry)} "
        f"V{number(y+ry)} A{point(rx,ry)} 0,0 1 {point(x+rx,y)} Z"
    )


def generate() -> dict[Path, bytes]:
    upstream = json.loads((SOURCE / "UPSTREAM.json").read_text())
    actual_sources = {"LICENSE.txt"} | {
        str(path.relative_to(SOURCE)) for path in (SOURCE / "lucide").glob("*.svg")
    }
    if actual_sources != set(upstream["files"]):
        raise ValueError("SVG source files must match the pinned upstream manifest")
    for alias, original in upstream.get("aliases", {}).items():
        if not re.fullmatch(r"[a-z][a-z0-9-]*", alias) or f"lucide/{original}.svg" not in actual_sources:
            raise ValueError("Invalid upstream icon alias")
    for name, expected in upstream["files"].items():
        if hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Pinned upstream source changed: {name}")
    icons = {}
    outputs = {}
    for source in sorted((SOURCE / "lucide").glob("*.svg")):
        public_name = next(
            (alias for alias, original in upstream.get("aliases", {}).items() if original == source.stem),
            source.stem,
        )
        svg = ET.fromstring(source.read_bytes())
        if svg.get("viewBox") != "0 0 24 24":
            raise ValueError(f"Unexpected icon viewBox: {source.name}")
        if any(svg.get(key) != expected for key, expected in {
            "fill": "none", "stroke": "currentColor", "stroke-width": "2",
            "stroke-linecap": "round", "stroke-linejoin": "round",
        }.items()):
            raise ValueError(f"Unexpected icon style: {source.name}")
        nodes = []
        paths = []
        for child in svg:
            tag = child.tag.rsplit("}", 1)[-1]
            if tag not in TAGS or "transform" in child.attrib:
                raise ValueError(f"Unsupported SVG node: {source.name}: {tag}")
            if set(child.attrib) - {"d", "cx", "cy", "r", "rx", "ry", "x", "y", "width", "height", "x1", "y1", "x2", "y2", "points"}:
                raise ValueError(f"Unsupported SVG attribute: {source.name}")
            nodes.append([tag, dict(child.attrib)])
            attrs = {
                "android:pathData": path_data(tag, child.attrib),
                "android:fillColor": "@android:color/transparent",
                "android:strokeColor": "#FF000000",
                "android:strokeWidth": "2",
                "android:strokeLineCap": "round",
                "android:strokeLineJoin": "round",
            }
            rendered = "\n".join(
                f'        {key}="{value}"' for key, value in attrs.items()
            )
            paths.append(f"    <path\n{rendered} />")
        icons[public_name] = nodes
        vector = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated from pinned Lucide SVG. See assets/ui-icons/UPSTREAM.json. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="24" android:viewportHeight="24">\n'
            + "\n".join(paths) + "\n</vector>\n"
        )
        ET.fromstring(vector)
        outputs[ROOT / "android/app/src/main/res/drawable" / f"ic_ui_{public_name.replace('-', '_')}.xml"] = vector.encode()
    # Format deterministically without requiring a generator-time npm dependency.
    # JSON-compatible object values are valid TypeScript; Prettier owns its presentation.
    definitions = []
    for name, nodes in icons.items():
        body = ",\n".join(
            "    [" + json.dumps(tag) + ", " + json.dumps(attrs, separators=(", ", ": ")) + "]"
            for tag, attrs in nodes
        )
        definitions.append(f'  "{name}": [\n{body},\n  ]')
    typescript = (
        "// Generated from pinned Lucide SVG. Do not edit; see assets/ui-icons/UPSTREAM.json.\n"
        "export type IconNode = readonly [\n"
        "  tag: 'path' | 'circle' | 'ellipse' | 'line' | 'rect' | 'polyline' | 'polygon',\n"
        "  attributes: Readonly<Record<string, string>>,\n];\n"
        "export const icons = {\n" + ",\n".join(definitions) + ",\n"
        "} as const satisfies Record<string, readonly IconNode[]>;\n"
        "export type IconName = keyof typeof icons;\n"
    )
    # Use the existing locked formatter, never install a package while generating assets.
    import subprocess
    formatter = ROOT / "desktop/node_modules/.bin/prettier"
    result = subprocess.run(
        [str(formatter), "--stdin-filepath", "src/lib/icons.ts"],
        input=typescript, text=True, capture_output=True, cwd=ROOT / "desktop", check=True,
    )
    outputs[ROOT / "desktop/src/lib/icons.ts"] = result.stdout.encode()
    outputs[ROOT / "android/app/src/main/assets/licenses/lucide.txt"] = (SOURCE / "LICENSE.txt").read_bytes()
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = generate()
    mismatched = []
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_bytes() != content:
                mismatched.append(str(path.relative_to(ROOT)))
        elif not path.exists() or path.read_bytes() != content:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if mismatched:
        print("Generated UI icons differ: " + ", ".join(mismatched), file=sys.stderr)
        return 1
    print(f"Lucide source hashes and {len(outputs)} desktop/Android outputs {'verified' if args.check else 'generated'}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
