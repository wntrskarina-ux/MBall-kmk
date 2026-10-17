#!/usr/bin/env python3
"""Generates the extension launcher icon (pure Python, no dependencies).

The icon is a manga-panel style ball: a rounded indigo tile, speed lines, and a
boldly outlined ball split into three comic-coloured segments.

Usage: python3 tools/make_icon.py
"""
import math
import pathlib
import struct
import zlib

SIZE = 192
SS = 3  # supersampling factor
OUT = pathlib.Path(__file__).resolve().parent.parent / (
    "extension/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
)


def blend(dst, src, alpha):
    return tuple(round(d + (s - d) * alpha) for d, s in zip(dst, src))


def render(size):
    """Renders one pixel per (x, y) at the given square size."""
    px = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]
    cs = size / 2.0
    radius_tile = size * 0.50
    corner = size * 0.22
    ball_c = cs
    ball_r = size * 0.30
    ring_w = size * 0.035

    for y in range(size):
        for x in range(size):
            fx, fy = x + 0.5, y + 0.5
            # ---- rounded tile background with a vertical gradient
            dx = max(abs(fx - cs) - (radius_tile - corner), 0.0)
            dy = max(abs(fy - cs) - (radius_tile - corner), 0.0)
            if dx * dx + dy * dy > corner * corner:
                continue
            t = fy / size
            top = (124, 92, 255)
            bottom = (58, 32, 130)
            colour = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))

            # ---- speed lines behind the ball
            for offset in (-0.30, -0.16, 0.16, 0.30):
                line_y = cs + offset * size
                thickness = size * 0.016
                span = math.hypot(fx - cs, fy - cs)
                if abs(fy - line_y - 0.22 * (fx - cs)) < thickness and span > ball_r * 1.15:
                    colour = blend(colour, (255, 255, 255), 0.22)

            # ---- the ball itself
            rx, ry = fx - ball_c, fy - ball_c
            dist = math.hypot(rx, ry)
            if dist <= ball_r + ring_w:
                if dist >= ball_r:
                    colour = (24, 20, 40)  # ink outline
                else:
                    angle = math.atan2(ry, rx)
                    segment = int(((angle + math.pi) / (2 * math.pi)) * 4) % 4
                    colour = [
                        (255, 255, 255),
                        (255, 196, 87),
                        (255, 255, 255),
                        (120, 214, 255),
                    ][segment]
                    # soft shading towards the lower right
                    shade = max(0.0, (rx * 0.6 + ry * 0.8) / ball_r)
                    colour = blend(colour, (30, 30, 50), shade * 0.18)
                    # highlight
                    hx, hy = fx - (ball_c - ball_r * 0.42), fy - (ball_c - ball_r * 0.46)
                    if math.hypot(hx, hy) < ball_r * 0.20:
                        colour = blend(colour, (255, 255, 255), 0.85)
            px[y][x] = (*colour, 255)
    return px


def downsample(px, factor, out_size):
    result = []
    for y in range(out_size):
        row = []
        for x in range(out_size):
            r = g = b = a = 0
            for sy in range(factor):
                for sx in range(factor):
                    p = px[y * factor + sy][x * factor + sx]
                    r += p[0] * p[3]
                    g += p[1] * p[3]
                    b += p[2] * p[3]
                    a += p[3]
            if a == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((round(r / a), round(g / a), round(b / a), round(a / (factor * factor))))
        result.append(row)
    return result


def write_png(path, pixels):
    height = len(pixels)
    width = len(pixels[0])
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("4B", *pixel) for pixel in row) for row in pixels
    )

    def chunk(kind, data):
        payload = kind + data
        return (
            struct.pack(">I", len(data))
            + payload
            + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"wrote {path} ({width}x{height}, {len(png)} bytes)")


if __name__ == "__main__":
    big = render(SIZE * SS)
    write_png(OUT, downsample(big, SS, SIZE))
