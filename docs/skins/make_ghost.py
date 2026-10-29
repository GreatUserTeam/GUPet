#!/usr/bin/env python3
"""Генератор тестового скина ghost.gupet (18x13 PNG + manifest).
Чистый stdlib: PNG пишется вручную через zlib/struct."""
import json
import os
import struct
import zipfile
import zlib

W, H = 18, 13
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)))
OUT_ZIP = os.path.join(OUT_DIR, "ghost.gupet")

WHITE = (255, 255, 255, 170)
DARK = (26, 26, 46, 255)


def new_canvas():
    return [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def ghost_base(blink=False, sleep=False):
    px = new_canvas()
    for x in range(6, 12):
        px[1][x] = WHITE
    for x in range(5, 13):
        px[2][x] = WHITE
    for y in range(3, 10):
        for x in range(4, 14):
            px[y][x] = WHITE
    for x in range(4, 14):
        px[10][x] = WHITE
    for x in (4, 6, 8, 10, 12):
        px[11][x] = WHITE
    for x in (5, 7, 9, 11, 13):
        px[12][x] = WHITE
    if blink or sleep:
        for x in (6, 7, 8):
            px[5][x] = DARK
        for x in (9, 10, 11):
            px[5][x] = DARK
    else:
        px[5][7] = DARK
        px[5][10] = DARK
    px[7][8] = DARK
    px[7][9] = DARK
    return px


def write_png(path, px):
    def chunk(typ, data):
        c = typ + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    raw = b""
    for row in px:
        raw += b"\x00"
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


frames = {
    "idle": ghost_base(),
    "blink": ghost_base(blink=True),
    "sleep": ghost_base(sleep=True),
    "happy": ghost_base(),
}
# happy: приподнятая волна низа
for x in range(4, 14):
    frames["happy"][12][x] = (0, 0, 0, 0)
for x in (4, 6, 8, 10, 12):
    frames["happy"][12][x] = WHITE

manifest = {
    "format": "GUPet",
    "formatVersion": 1,
    "width": W,
    "height": H,
    "id": "ghost",
    "name": "Призрак",
    "author": "GUTeam",
    "frames": {k: k + ".png" for k in frames},
}

tmp = []
for name, px in frames.items():
    p = os.path.join(OUT_DIR, name + ".png")
    write_png(p, px)
    tmp.append(p)
mp = os.path.join(OUT_DIR, "manifest.json")
with open(mp, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
tmp.append(mp)

with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(mp, "manifest.json")
    for name in frames:
        z.write(os.path.join(OUT_DIR, name + ".png"), name + ".png")
for p in tmp:
    os.remove(p)
print("written:", OUT_ZIP, os.path.getsize(OUT_ZIP), "bytes")
