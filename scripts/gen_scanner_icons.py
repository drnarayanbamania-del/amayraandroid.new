#!/usr/bin/env python3
"""Generate Android launcher raster icons (PNG) for scanner/ and app/ if missing.

Draws a simple document-scan glyph on a brand-blue rounded square using only
the Python standard library (zlib + struct to write PNGs). Idempotent: skips
icons that already exist.

Usage: python scripts/gen_scanner_icons.py [project_root]
"""
import struct
import sys
import zlib
from pathlib import Path

BLUE = (20, 105, 232)      # #1469E8
BLUE_LIGHT = (191, 215, 251)
WHITE = (255, 255, 255)


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: Path, pixels: list) -> None:
    h = len(pixels)
    w = len(pixels[0])
    raw = b"".join(
        b"\x00" + b"".join(bytes(p) for p in row) for row in pixels
    )
    png = (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(raw, 9))
        + _chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"wrote {path} ({w}x{h})")


def rounded_square(size: int, radius_frac: float = 0.18, bg=BLUE) -> list:
    """Blue rounded-square background as a pixel matrix (RGBA rows)."""
    radius = int(size * radius_frac)
    px = []
    for y in range(size):
        row = []
        for x in range(size):
            # Distance to the nearest corner center decides rounding.
            cx = min(x, size - 1 - x)
            cy = min(y, size - 1 - y)
            inside = True
            if cx < radius and cy < radius:
                dx, dy = radius - cx, radius - cy
                inside = dx * dx + dy * dy <= radius * radius
            row.append((*bg, 255) if inside else (0, 0, 0, 0))
        px.append(row)
    return px


def draw_document(px: list, size: int) -> None:
    """Draw a white document sheet with a folded corner and blue text lines."""
    margin = int(size * 0.26)
    sheet_w = size - 2 * margin
    sheet_h = int(sheet_w * 1.32)
    top = (size - sheet_h) // 2
    fold = int(sheet_w * 0.28)

    for y in range(top, min(top + sheet_h, size)):
        for x in range(margin, min(margin + sheet_w, size)):
            rel_y = y - top
            rel_x = x - margin
            if rel_x >= sheet_w - fold and rel_y < fold:
                continue  # folded corner cut-out
            color = WHITE
            if rel_x >= sheet_w - fold and rel_y < fold + int(fold * 0.4):
                color = BLUE_LIGHT  # fold shading
            # Blue "text" lines inside the lower half of the sheet
            line_h = max(2, sheet_h // 14)
            gap = max(2, sheet_h // 14)
            for i, line_top in enumerate(
                range(int(sheet_h * 0.45), sheet_h - line_h, line_h + gap)
            ):
                if line_top <= rel_y < line_top + line_h and rel_x > sheet_w * 0.12 and rel_x < sheet_w * (0.88 if i < 2 else 0.55):
                    color = BLUE
            px[y][x] = (*color, 255)


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for module in ("scanner", "app"):
        if not (root / module / "src" / "main").is_dir():
            continue
        for dpi, size in sizes.items():
            out = root / module / "src" / "main" / "res" / f"mipmap-{dpi}" / "ic_launcher.png"
            if out.exists():
                continue
            px = rounded_square(size)
            draw_document(px, size)
            write_png(out, px)
    return 0


if __name__ == "__main__":
    sys.exit(main())
