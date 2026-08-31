"""Convert selected trafikkskilt SVGs to transparent Android VectorDrawables."""

from __future__ import annotations

import re
import shutil
from pathlib import Path

SRC = Path(r"c:\Users\okho_\Desktop\trafikkskilt")
DST = Path(
    r"c:\Users\okho_\Documents\GitHub Projects\road-notifications"
    r"\app\src\main\res\drawable"
)
PNG_DIR = Path(
    r"c:\Users\okho_\Documents\GitHub Projects\road-notifications"
    r"\app\src\main\res\drawable-nodpi"
)

PATH_RE = re.compile(
    r'<path\s+fill="(#[0-9a-fA-F]+)"\s+opacity="([^"]*)"\s+d="([^"]+)"\s*/>',
    re.MULTILINE,
)
VIEWBOX_RE = re.compile(r'viewBox="([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)"')
WHITE_FILLS = {"#ffffff", "#ffffffff", "#fff", "#fefefe"}


def find_svg(*parts: str) -> Path | None:
    matches: list[Path] = []
    for path in SRC.rglob("*.svg"):
        folded = str(path).casefold()
        if all(part.casefold() in folded for part in parts):
            matches.append(path)
    if not matches:
        return None
    exact = [
        path
        for path in matches
        if path.name.casefold() == f"{parts[-1].casefold()}.svg"
        or path.name.casefold() == parts[-1].casefold()
    ]
    return exact[0] if exact else matches[0]


def clean_path_data(path_data: str) -> str:
    cleaned = " ".join(path_data.split())
    return cleaned


def svg_to_vector(svg_text: str, resource_name: str, keep_white_group: bool = False) -> str:
    viewbox = VIEWBOX_RE.search(svg_text)
    if viewbox is None:
        raise ValueError(f"Missing viewBox for {resource_name}")
    width = int(float(viewbox.group(3)))
    height = int(float(viewbox.group(4)))

    working_svg = svg_text
    if not keep_white_group:
        # Drop the pure-white background group used as a cut-out plate
        # on square warning signs. Underskilts like 556.2 keep it because
        # the SATK letters live in that group.
        working_svg = re.sub(
            r'<g id="#ffffffff">.*?</g>',
            "",
            svg_text,
            flags=re.DOTALL,
        )

    path_blocks: list[str] = []
    for fill, opacity, path_data in PATH_RE.findall(working_svg):
        fill_lower = fill.lower()
        is_corner_leftover = "M 0.00 0.00" in path_data or "L 0.00 0.00" in path_data
        if fill_lower in WHITE_FILLS and not keep_white_group:
            continue
        if fill_lower in WHITE_FILLS and is_corner_leftover:
            continue
        if fill_lower in {"#fbfbfb", "#fafafa"} and opacity == "1.00" and is_corner_leftover:
            continue
        alpha = float(opacity)
        fill_color = fill_lower
        if alpha < 0.999:
            alpha_byte = int(round(alpha * 255))
            rgb = fill_lower.lstrip("#")
            if len(rgb) == 6:
                fill_color = f"#{alpha_byte:02x}{rgb}"
        fill_type_attr = (
            '        android:fillType="evenOdd"\n' if keep_white_group else ""
        )
        path_blocks.append(
            "    <path\n"
            f'        android:fillColor="{fill_color}"\n'
            f"{fill_type_attr}"
            f'        android:pathData="{clean_path_data(path_data)}" />'
        )

    if not path_blocks:
        raise ValueError(f"No drawable paths left for {resource_name}")

    max_dp = 192
    if keep_white_group:
        if width >= height:
            width_dp = max_dp
            height_dp = max(1, round(max_dp * height / width))
        else:
            height_dp = max_dp
            width_dp = max(1, round(max_dp * width / height))
    else:
        width_dp = min(width, max_dp)
        height_dp = min(height, max_dp)

    body = "\n".join(path_blocks)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
        f'    android:width="{width_dp}dp"\n'
        f'    android:height="{height_dp}dp"\n'
        f'    android:viewportWidth="{width}"\n'
        f'    android:viewportHeight="{height}">\n'
        f"{body}\n"
        "</vector>\n"
    )


def resolve_sources() -> dict[str, Path]:
    sources: dict[str, Path | None] = {
        "sign_204": find_svg("204_0", "204_0.svg"),
        "sign_206": find_svg("206_0", "206_0.svg"),
        "sign_208": find_svg("208_0", "208_0.svg"),
        "sign_100_1": find_svg("skarp-sving-til-hoeyre.svg"),
        "sign_100_2": find_svg("farlig-sving-til-venstre.svg"),
        "sign_102_1": find_svg("farlige-svinger-den-foerste-til-hoeyre.svg"),
        "sign_102_2": find_svg("102_2", "102_2.svg"),
        "sign_106_1": find_svg("106_1", "106_1.svg"),
        "sign_106_2": find_svg("106_2", "106_2.svg"),
        "sign_106_3": find_svg("106_3", "106_3.svg"),
        "sign_120": find_svg("120_0", "120_0.svg"),
        "sign_122": find_svg("122_0", "122_0.svg"),
        "sign_124": find_svg("124_0", "124_0.svg"),
        "sign_202": find_svg("202_0", "202_0.svg"),
        "sign_134": find_svg("134_0", "134_0.svg"),
        "sign_556": find_svg("556_0", "556_0.svg"),
        "sign_556_2": find_svg("556_2", "556_2.svg"),
        "sign_792_30": find_svg("792_30", "792_30.svg"),
    }
    for index in range(1, 6):
        sources[f"sign_146_{index}"] = find_svg(f"146_{index}", f"146_{index}.svg")
    for speed in (30, 40, 50, 60, 70, 80, 90, 100, 110):
        sources[f"sign_362_{speed}"] = find_svg(f"362_{speed}", f"362_{speed}.svg")
    for speed in (30, 40, 50, 60, 70):
        sources[f"sign_364_{speed}"] = find_svg(f"364_{speed}", f"364_{speed}.svg")
    sources["sign_368"] = find_svg("368_0", "368_0.svg")

    missing = [name for name, path in sources.items() if path is None]
    if missing:
        raise SystemExit(f"Missing SVG sources: {missing}")
    return {name: path for name, path in sources.items() if path is not None}


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    sources = resolve_sources()
    converted = 0
    for resource_name, svg_path in sources.items():
        vector_xml = svg_to_vector(
            svg_path.read_text(encoding="utf-8"),
            resource_name,
            keep_white_group=resource_name == "sign_556_2",
        )
        target = DST / f"{resource_name}.xml"
        target.write_text(vector_xml, encoding="utf-8")
        print(f"OK {resource_name}.xml <- {svg_path.name} ({len(vector_xml)} bytes)")
        converted += 1

    if PNG_DIR.exists():
        removed = 0
        for png in PNG_DIR.glob("sign_*.png"):
            png.unlink()
            removed += 1
        print(f"Removed {removed} PNG files from drawable-nodpi")
        remaining = list(PNG_DIR.iterdir())
        if not remaining:
            PNG_DIR.rmdir()
            print("Removed empty drawable-nodpi")

    print(f"Converted {converted} SVGs to VectorDrawables")


if __name__ == "__main__":
    main()
