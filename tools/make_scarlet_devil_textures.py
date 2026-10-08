#!/usr/bin/env python3
"""Generates the item textures for the Scarlet Devil set.

Stdlib only (zlib + struct), so it runs without Pillow. Most items are drawn as
character grids; the spear (Scarlet's Fang) is a recolour of vanilla's iron
spear, read out of the Loom cache, so its shaft sits exactly where the
spear_in_hand model expects it.

Run:  python3 tools/make_scarlet_devil_textures.py
"""
import glob
import os
import struct
import zipfile
import zlib

OUT = "resourcepack/assets/fortuneandfavors/textures/item"

PAL = {
    ".": (0, 0, 0, 0),
    "K": (40, 6, 16, 255),       # crimson outline
    "D": (88, 10, 26, 255),      # deep blood
    "R": (150, 18, 34, 255),     # blood
    "B": (205, 36, 52, 255),     # bright blood
    "H": (240, 112, 122, 255),   # rose highlight
    "W": (255, 218, 224, 255),   # glint
    "G": (226, 176, 66, 255),    # gold
    "g": (150, 104, 34, 255),    # gold shadow
    "Y": (255, 232, 150, 255),   # gold highlight
    "k": (64, 38, 12, 255),      # gold outline
    "E": (46, 18, 30, 255),      # ebony spine
    "P": (236, 222, 196, 255),   # page
    "p": (190, 168, 138, 255),   # page shade
    "O": (40, 32, 48, 255),      # glass outline
    "S": (176, 196, 210, 190),   # glass
    "L": (238, 246, 252, 230),   # glass glint
    "c": (138, 90, 58, 255),     # cork
}

ART = {
    "scarlet_blood": [
        "................",
        "......OOOO......",
        "......OccO......",
        "......OccO......",
        ".....OOOOOO.....",
        "......OSLO......",
        "......OSLO......",
        "....OOSSSLOO....",
        "...OSRBBBHLSO...",
        "..OSRBBBBBHWSO..",
        "..OSRRBBBBBHSO..",
        "..OSDRRBBBBBSO..",
        "..OSDDRRRRRBSO..",
        "...OSDDDRRRSO...",
        "....OOSSSSOO....",
        "......OOOO......",
    ],
    "scarlet_loot_box": [
        "................",
        "..KKKKKKKKKKKK..",
        ".KGBBBBBBBBBBGK.",
        ".KGBHHHHHHHHBGK.",
        ".KGRRRRRRRRRRGK.",
        ".KGDDDDDDDDDDGK.",
        ".KKKKKKGGKKKKKK.",
        ".KGRRRKYGKRRRGK.",
        ".KGRRRGYYGRRRGK.",
        ".KGRRRKGgKRRRGK.",
        ".KGRRRRKKRRRRGK.",
        ".KGRRRRRRRRRRGK.",
        ".KGDDDDDDDDDDGK.",
        ".KGGGGGGGGGGGGK.",
        "..KKKKKKKKKKKK..",
        "................",
    ],
    "scarlet_trophy": [
        "................",
        "..kkkkkkkkkkkk..",
        ".kYGGGGGGGGGGgk.",
        ".kGRBHBBBBBRDgk.",
        ".kYGGGGGGGGGGgk.",
        "..kYGGGRGGGGgk..",
        "..kYGGRBRGGGgk..",
        "...kYGGRGGGgk...",
        "....kYGGGGgk....",
        ".....kkYgkk.....",
        "......kYgk......",
        "......kYgk......",
        ".....kYGGgk.....",
        "....kYGGGGgk....",
        "....kkkkkkkk....",
        "................",
    ],
    "bloodsoaked_core": [
        "................",
        "................",
        "...KKK....KKK...",
        "..KBHBK..KBBRK..",
        ".KBHWBBKKBBBRDK.",
        ".KBHBBBBBBBBRDK.",
        ".KBBBBDBBBBRRDK.",
        ".KRBBBBDBBRRDDK.",
        "..KRBBBDDRRDDK..",
        "...KRBBBRRDDK...",
        "....KRRRRDDK....",
        ".....KRRDDK.....",
        "......KRDK......",
        ".......KK.......",
        "........D.......",
        "................",
    ],
    "scarlet_grimoire": [
        "................",
        "..KKKKKKKKKKKK..",
        ".KEGRRRRRRRRGKp.",
        ".KERDDDDDDDDRKP.",
        ".KERDRRGGRRDRKP.",
        ".KERDRGBBGRDRKP.",
        ".KERDGBHWBGDRKP.",
        ".KERDGBWHBGDRKP.",
        ".KERDRGBBGRDRKP.",
        ".KERDRRGGRRDRKP.",
        ".KERDDDDDDDDRKP.",
        ".KEGRRRRRRRRGKP.",
        ".KEEKKKKKKKKKKp.",
        ".........B......",
        ".........R......",
        "................",
    ],
    "blood_prism": [
        ".......K........",
        "......KWK.......",
        ".....KHWBK...W..",
        ".....KHWBRK.....",
        "....KHHWBRDK....",
        "....KHWBBRDK....",
        "...KHHWBBRRDK...",
        "...KHWBBBRRDK...",
        "...KGYGGGGGgK...",
        "...KHBBBBRRDK...",
        ".W..KHBBBRDK....",
        "....KBBBRRDK....",
        ".....KBBRDK.....",
        "......KBRK......",
        ".......KK.......",
        "................",
    ],
    "crimson_essence": [
        "................",
        "........W.......",
        ".......KHK......",
        "......KHBK......",
        ".....KHWBRK...W.",
        "....KHWBBRK.....",
        "....KHBBBRDK....",
        "...KHWBBBRRDK...",
        "...KHBBBBRRDK...",
        "...KBBBBRRRDK...",
        "...KRBBBRRDDK...",
        "....KRBRRDDK....",
        ".....KRRDDK.....",
        "......KKKK......",
        ".W..............",
        "................",
    ],
}

# Vanilla iron spear colour -> Scarlet's Fang colour. Head goes crimson with a rose edge, the
# shaft goes dark blood-wood, and the copper grip/butt becomes a gold wrap.
SPEAR = {
    (68, 68, 68): PAL["K"], (24, 24, 24): (22, 2, 8, 255), (255, 255, 255): PAL["W"],
    (216, 216, 216): PAL["H"], (177, 177, 177): PAL["B"], (133, 133, 133): PAL["B"],
    (107, 107, 107): PAL["R"], (84, 87, 88): PAL["D"],
    (73, 54, 21): (52, 12, 18, 255), (40, 30, 11): (30, 6, 10, 255),
    (104, 78, 30): (92, 24, 30, 255), (137, 103, 39): (128, 38, 44, 255),
    (103, 41, 11): PAL["g"], (65, 24, 5): (96, 62, 20, 255), (186, 95, 52): PAL["Y"],
    (163, 75, 34): PAL["G"], (131, 65, 33): (196, 146, 50, 255),
}


def write_png(path, rows):
    h, w = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + bytes(c for px in r for c in px) for r in rows)

    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    print("wrote", path)


def read_png(data):
    """Decodes an 8-bit PNG (RGBA, RGB, grey or palette) to rows of RGBA tuples."""
    pos, idat, w, h, ctype, plte, trns = 8, b"", 0, 0, 6, None, None
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 or (depth < 8 and ctype in (0, 3)), "expected 8-bit samples (or a low-bit palette/grey image)"
        elif typ == b"PLTE":
            plte = [tuple(body[k:k + 3]) for k in range(0, n, 3)]
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat += body
    bpp = {6: 4, 2: 3, 3: 1, 4: 2, 0: 1}[ctype]
    if depth < 8:
        # Low bit-depth palette or grey: unfilter per byte, then unpack the bits of each row.
        per = 8 // depth
        stride = (w + per - 1) // per
        raw, prev, rows, i = zlib.decompress(idat), bytearray(stride), [], 0
        for _ in range(h):
            f, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
            i += 1 + stride
            for x in range(stride):
                a = line[x - 1] if x >= 1 else 0
                b = prev[x]
                c = prev[x - 1] if x >= 1 else 0
                if f == 1:
                    line[x] = (line[x] + a) & 255
                elif f == 2:
                    line[x] = (line[x] + b) & 255
                elif f == 3:
                    line[x] = (line[x] + (a + b) // 2) & 255
                elif f == 4:
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            prev = line
            row = []
            for x in range(w):
                v = (line[x // per] >> (8 - depth * (x % per + 1))) & ((1 << depth) - 1)
                if ctype == 3:
                    row.append(plte[v] + ((trns[v] if trns and v < len(trns) else 255),))
                else:
                    g = v * 255 // ((1 << depth) - 1)
                    row.append((g, g, g, 255))
            rows.append(row)
        return rows
    raw, stride, prev, rows, i = zlib.decompress(idat), w * bpp, bytearray(w * bpp), [], 0
    for _ in range(h):
        f, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        row = []
        for x in range(w):
            px = line[x * bpp:(x + 1) * bpp]
            if ctype == 6:
                row.append(tuple(px))
            elif ctype == 2:
                row.append(tuple(px) + (255,))
            elif ctype == 3:
                row.append(plte[px[0]] + ((trns[px[0]] if trns and px[0] < len(trns) else 255),))
            elif ctype == 4:
                row.append((px[0],) * 3 + (px[1],))
            else:
                row.append((px[0],) * 3 + (255,))
        rows.append(row)
    return rows


def vanilla_texture(name):
    jars = glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/*/minecraft-client.jar"))
    for jar in sorted(jars, reverse=True):
        with zipfile.ZipFile(jar) as z:
            try:
                return read_png(z.read(f"assets/minecraft/textures/item/{name}.png"))
            except KeyError:
                continue
    raise SystemExit(f"vanilla {name}.png not found - run a Gradle build once so Loom caches the client jar")


def recolour(rows, mapping):
    return [[px if px[3] == 0 else mapping.get(px[:3], px) for px in r] for r in rows]


def main():
    for name, grid in ART.items():
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), name
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])
    write_png(f"{OUT}/scarlets_fang.png", recolour(vanilla_texture("iron_spear"), SPEAR))
    write_png(f"{OUT}/scarlets_fang_in_hand.png", recolour(vanilla_texture("iron_spear_in_hand"), SPEAR))


if __name__ == "__main__":
    main()
