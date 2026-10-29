#!/usr/bin/env python3
"""Генератор скинов ghost.gupet и cat.gupet.
Все 9 кадров 18x13 (idle, blink, happy, sleep, dance, surprised, dizzy,
angry, love) + poop.png + manifest с settings+phrases.
Чистый stdlib: PNG пишется вручную через zlib/struct."""
import json
import os
import struct
import zipfile
import zlib

W, H = 18, 13
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)))

GHOST_BODY = (235, 235, 245, 179)  # полупрозрачный белый, alpha ~0.7
GHOST_EYE = (26, 26, 46, 255)
HEART = (255, 120, 150, 255)
ORANGE = (255, 170, 60, 255)
ORANGE_D = (200, 120, 30, 255)
PINK = (255, 150, 170, 255)
DARK = (26, 26, 46, 255)
POOP_BROWN = (139, 90, 43, 255)
POOP_BROWN_D = (100, 62, 28, 255)


def new_canvas():
    return [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def put(px, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


def ghost_body():
    px = new_canvas()
    for x in range(6, 12):
        px[1][x] = GHOST_BODY
    for x in range(5, 13):
        px[2][x] = GHOST_BODY
    for y in range(3, 10):
        for x in range(4, 14):
            px[y][x] = GHOST_BODY
    for x in range(4, 14):
        px[10][x] = GHOST_BODY
    for x in (4, 6, 8, 10, 12):
        px[11][x] = GHOST_BODY
    for x in (5, 7, 9, 11, 13):
        px[12][x] = GHOST_BODY
    return px


def ghost_eyes_dots(px):
    put(px, 7, 5, GHOST_EYE)
    put(px, 10, 5, GHOST_EYE)


def ghost_mouth(px):
    put(px, 8, 7, GHOST_EYE)
    put(px, 9, 7, GHOST_EYE)


def ghost_frame(mood):
    px = ghost_body()
    if mood == "blink" or mood == "sleep":
        for x in (6, 7, 8):
            put(px, x, 5, GHOST_EYE)
        for x in (9, 10, 11):
            put(px, x, 5, GHOST_EYE)
        if mood == "blink":
            ghost_mouth(px)
    elif mood == "happy":
        ghost_eyes_dots(px)
        for x in (7, 8, 9, 10):
            put(px, x, 8, GHOST_EYE)
    elif mood == "dance":
        ghost_eyes_dots(px)
        ghost_mouth(px)
        # маленькие нотки по бокам
        for (x, y) in ((2, 1), (2, 2), (2, 3), (3, 3), (15, 0), (15, 1), (15, 2), (14, 2)):
            put(px, x, y, GHOST_EYE)
    elif mood == "surprised":
        for x in (6, 7):
            for y in (4, 5):
                put(px, x, y, GHOST_EYE)
        for x in (10, 11):
            for y in (4, 5):
                put(px, x, y, GHOST_EYE)
        put(px, 8, 7, GHOST_EYE)
        put(px, 9, 7, GHOST_EYE)
    elif mood == "dizzy":
        for (x, y) in ((6, 4), (8, 4), (7, 5), (6, 6), (8, 6),
                       (10, 4), (12, 4), (11, 5), (10, 6), (12, 6)):
            put(px, x, y, GHOST_EYE)
        ghost_mouth(px)
    elif mood == "angry":
        for (x, y) in ((5, 3), (6, 3), (7, 4), (12, 3), (11, 3), (10, 4)):
            put(px, x, y, GHOST_EYE)
        ghost_eyes_dots(px)
        put(px, 8, 8, GHOST_EYE)
        put(px, 9, 8, GHOST_EYE)
    elif mood == "love":
        for (x, y) in ((6, 4), (8, 4), (6, 5), (7, 5), (8, 5), (7, 6),
                       (10, 4), (12, 4), (10, 5), (11, 5), (12, 5), (11, 6)):
            put(px, x, y, HEART)
        for x in (7, 8, 9, 10):
            put(px, x, 8, GHOST_EYE)
    else:  # idle
        ghost_eyes_dots(px)
        ghost_mouth(px)
    return px


def ghost_poop():
    """Белая полупрозрачная кучка 18x13."""
    px = new_canvas()
    pile = [(7, 7), (8, 7), (9, 7), (10, 7),
            (6, 8), (7, 8), (8, 8), (9, 8), (10, 8), (11, 8),
            (5, 9), (6, 9), (7, 9), (8, 9), (9, 9), (10, 9), (11, 9), (12, 9),
            (5, 10), (6, 10), (7, 10), (8, 10), (9, 10), (10, 10), (11, 10), (12, 10)]
    for (x, y) in pile:
        put(px, x, y, GHOST_BODY)
    for (x, y) in ((7, 8), (8, 7), (6, 9)):
        put(px, x, y, (255, 255, 255, 220))
    return px


def cat_body():
    px = new_canvas()
    for x in (4, 5, 12, 13):
        put(px, x, 2, ORANGE_D)
    put(px, 5, 2, ORANGE)
    put(px, 12, 2, ORANGE)
    put(px, 4, 3, PINK)
    put(px, 13, 3, PINK)
    for y in range(3, 10):
        for x in range(4, 14):
            put(px, x, y, ORANGE)
    for x in range(5, 13):
        put(px, x, 10, ORANGE)
    put(px, 8, 3, ORANGE_D)
    put(px, 9, 3, ORANGE_D)
    for x in (1, 2):
        put(px, x, 7, DARK)
    for x in (15, 16):
        put(px, x, 7, DARK)
    return px


def cat_eyes_dots(px):
    for x in (6, 7):
        put(px, x, 5, DARK)
    for x in (10, 11):
        put(px, x, 5, DARK)


def cat_nose_mouth(px):
    put(px, 8, 7, PINK)
    put(px, 9, 7, PINK)
    put(px, 8, 8, DARK)
    put(px, 9, 8, DARK)


def cat_frame(mood):
    px = cat_body()
    if mood == "blink" or mood == "sleep":
        for x in (6, 7):
            put(px, x, 5, DARK)
        for x in (10, 11):
            put(px, x, 5, DARK)
        if mood == "blink":
            cat_nose_mouth(px)
    elif mood == "happy":
        for x in (6, 7, 10, 11):
            put(px, x, 5, DARK)
        put(px, 6, 4, DARK)
        put(px, 11, 4, DARK)
        put(px, 8, 7, PINK)
        put(px, 9, 7, PINK)
        put(px, 7, 8, DARK)
        put(px, 10, 8, DARK)
    elif mood == "dance":
        cat_eyes_dots(px)
        cat_nose_mouth(px)
        for (x, y) in ((2, 1), (2, 2), (2, 3), (3, 3), (15, 0), (15, 1), (15, 2), (14, 2)):
            put(px, x, y, DARK)
    elif mood == "surprised":
        for x in (6, 7):
            for y in (4, 5):
                put(px, x, y, DARK)
        for x in (10, 11):
            for y in (4, 5):
                put(px, x, y, DARK)
        put(px, 8, 7, PINK)
        put(px, 9, 7, PINK)
    elif mood == "dizzy":
        for (x, y) in ((6, 4), (8, 4), (7, 5), (6, 6), (8, 6),
                       (10, 4), (12, 4), (11, 5), (10, 6), (12, 6)):
            put(px, x, y, DARK)
        cat_nose_mouth(px)
    elif mood == "angry":
        for (x, y) in ((5, 3), (6, 3), (7, 4), (12, 3), (11, 3), (10, 4)):
            put(px, x, y, ORANGE_D)
        cat_eyes_dots(px)
        put(px, 8, 8, DARK)
        put(px, 9, 8, DARK)
    elif mood == "love":
        for (x, y) in ((6, 4), (8, 4), (6, 5), (7, 5), (8, 5), (7, 6),
                       (10, 4), (12, 4), (10, 5), (11, 5), (12, 5), (11, 6)):
            put(px, x, y, HEART)
        put(px, 8, 7, PINK)
        put(px, 9, 7, PINK)
    else:  # idle
        cat_eyes_dots(px)
        cat_nose_mouth(px)
    return px


def cat_poop():
    """Коричневая кучка 18x13."""
    px = new_canvas()
    pile = [(7, 7), (8, 7), (9, 7), (10, 7),
            (6, 8), (7, 8), (8, 8), (9, 8), (10, 8), (11, 8),
            (5, 9), (6, 9), (7, 9), (8, 9), (9, 9), (10, 9), (11, 9), (12, 9),
            (5, 10), (6, 10), (7, 10), (8, 10), (9, 10), (10, 10), (11, 10), (12, 10)]
    for (x, y) in pile:
        put(px, x, y, POOP_BROWN)
    for (x, y) in ((7, 8), (8, 7), (6, 9)):
        put(px, x, y, (170, 120, 70, 255))
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


def phrase(pid, text, context, weight=1, enabled=True):
    return {"id": pid, "text": text, "context": context, "weight": weight, "enabled": enabled}


GHOST_PHRASES = [
    phrase("1", "*подглядывает*", "idle"),
    phrase("2", "Ку-ку!", "idle"),
    phrase("3", "Я тебя вижу", "idle"),
    phrase("4", "*тихо летит*", "walk"),
    phrase("5", "*мурчит призрачно*", "pet"),
    phrase("6", "*исчезает во сне*", "sleep"),
    phrase("7", "Бу!", "tap"),
]

CAT_PHRASES = [
    phrase("cat-idle-0", "Мяу...", "idle"),
    phrase("cat-idle-1", "*мурлычет*", "idle"),
    phrase("cat-idle-2", "*смотрит на тебя*", "idle"),
    phrase("cat-walk-0", "*ходит важно*", "walk"),
    phrase("cat-pet-0", "Мур-мур-мур~", "pet"),
    phrase("cat-pet-1", "*жмурится*", "pet"),
    phrase("cat-sleep-0", "*спит клубочком*", "sleep"),
    phrase("cat-tap-0", "Мяу!", "tap"),
    phrase("cat-tap-1", "*недовольно фыркает*", "tap"),
]

MOODS = ["idle", "blink", "happy", "sleep", "dance", "surprised", "dizzy", "angry", "love"]


def build(skin_id, name, author, frames, settings):
    manifest = {
        "format": "GUPet",
        "formatVersion": 1,
        "width": W,
        "height": H,
        "id": skin_id,
        "name": name,
        "author": author,
        "frames": {k: k + ".png" for k in frames},
        "settings": settings,
    }
    tmp = []
    mp = os.path.join(OUT_DIR, "manifest.json")
    with open(mp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    tmp.append(mp)
    for fname, px in frames.items():
        p = os.path.join(OUT_DIR, fname + ".png")
        write_png(p, px)
        tmp.append(p)
    out = os.path.join(OUT_DIR, skin_id + ".gupet")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(mp, "manifest.json")
        for fname in frames:
            z.write(os.path.join(OUT_DIR, fname + ".png"), fname + ".png")
    for p in tmp:
        os.remove(p)
    print("written:", out, os.path.getsize(out), "bytes")


ghost_frames = {m: ghost_frame(m) for m in MOODS}
ghost_frames["poop"] = ghost_poop()
build("ghost", "Призрак", "GUTeam", ghost_frames, {
    "species": "skin",
    "sizeDp": 4.0,
    "colorHex": None,
    "persistPosition": True,
    "weight": 0.6,
    "gravity": False,
    "gravityStrength": 1.0,
    "shake": True,
    "haptics": True,
    "edgeVibration": True,
    "phrases": True,
    "dance": True,
    "reactNotifications": False,
    "poop": True,
    "poopEnabled": True,
    "poopSize": 1.0,
    "poopInterval": 1.0,
    "poopColor": "#ffffff",
    "poopFadeMs": 500,
    "sleepMinutes": 3,
    "maxPoops": 5,
    "reactToPoop": True,
    "reactToOtherPets": True,
    "wakeOnPetTouch": True,
    "hideOnAppOpen": False,
    "canHang": True,
    "phraseColor": "#ffffff",
    "phraseFontSize": 14.0,
    "phraseBgColor": "#0b0b0b",
    "phraseBgAlpha": 0.85,
    "phraseOutlineColor": "#ff4d30",
    "phraseOutlineWidth": 2.0,
    "phraseDurationMs": 3000,
    "phraseFadeMs": 400,
    "phraseFadeStyle": "fade",
    "phrases": GHOST_PHRASES,
})

cat_frames = {m: cat_frame(m) for m in MOODS}
cat_frames["poop"] = cat_poop()
build("cat", "Кот", "GUTeam", cat_frames, {
    "species": "skin",
    "sizeDp": 4.0,
    "colorHex": "#ffaa3c",
    "persistPosition": True,
    "weight": 1.0,
    "gravity": False,
    "gravityStrength": 1.0,
    "shake": True,
    "haptics": True,
    "edgeVibration": True,
    "phrases": True,
    "dance": True,
    "reactNotifications": False,
    "poop": True,
    "poopEnabled": True,
    "poopSize": 1.0,
    "poopInterval": 1.0,
    "poopColor": "#8b5a2b",
    "poopFadeMs": 500,
    "sleepMinutes": 3,
    "maxPoops": 3,
    "reactToPoop": True,
    "reactToOtherPets": True,
    "wakeOnPetTouch": True,
    "hideOnAppOpen": False,
    "canHang": True,
    "phraseColor": "#ffffff",
    "phraseFontSize": 14.0,
    "phraseBgColor": "#0b0b0b",
    "phraseBgAlpha": 0.85,
    "phraseOutlineColor": "#ff4d30",
    "phraseOutlineWidth": 2.0,
    "phraseDurationMs": 3000,
    "phraseFadeMs": 400,
    "phraseFadeStyle": "fade",
    "phrases": CAT_PHRASES,
})
