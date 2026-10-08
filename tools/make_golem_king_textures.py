#!/usr/bin/env python3
"""Item textures for the Stone Golem (stone and amethyst) and the King Wither Skeleton (bone, black,
soul-blue, gold). Stdlib only.

Hand-made and deliberately not generated here: boulder_baby, wither_crown, withering_memory.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_golem_king_textures.py
"""
import math

from make_scarlet_devil_textures import OUT, vanilla_texture, write_png

T = (0, 0, 0, 0)
PAL = {
    ".": T,
    # stone
    "o": (30, 28, 32, 255), "1": (70, 68, 72, 255), "2": (110, 106, 108, 255),
    "3": (150, 146, 140, 255), "4": (192, 188, 178, 255),
    # amethyst
    "a": (100, 50, 160, 255), "A": (170, 100, 240, 255), "L": (234, 206, 255, 255),
    # bone, black, soul
    "K": (16, 14, 18, 255), "n": (60, 56, 64, 255), "B": (170, 160, 140, 255), "b": (226, 218, 196, 255),
    "c": (40, 120, 170, 255), "C": (90, 220, 255, 255), "W": (220, 250, 255, 255),
    # gold
    "k": (74, 48, 14, 255), "g": (164, 114, 36, 255), "G": (226, 176, 66, 255), "Y": (255, 234, 150, 255),
}

GRIDS = {
    "golem_fist": [
        "................",
        "................",
        "....oooooooo....",
        "...o43434343o...",
        "..o4o33o33o33o..",
        "..o3o32o32o32o..",
        "..o32222222221o.",
        ".oo4322Aa22221o.",
        "o432222aA22211o.",
        "o3222222222211o.",
        "o2222222222111o.",
        ".o22222222111o..",
        "..o222222111o...",
        "...o1111111o....",
        "....ooooooo.....",
        "................",
    ],
    "golem_loot_box": [
        "................",
        "..oooooooooooo..",
        ".o444443444444o.",
        ".o433333333332o.",
        ".o3332333323321o",
        ".o222222222222o.",
        ".ooooooLLoooooo.",
        ".o2223oALo3222o.",
        ".o222oAAAAo222o.",
        ".o232oaAAao212o.",
        ".o2222oaao2222o.",
        ".o22122oo23222o.",
        ".o111111111111o.",
        ".o11o111111o11o.",
        "..oooooooooooo..",
        "................",
    ],
    "stoneheart": [
        "................",
        "................",
        "...ooo....ooo...",
        "..o443o..o432o..",
        ".o44332oo33321o.",
        ".o43332AL22221o.",
        ".o3332aA2222211o",
        ".o322aA22222211o",
        "..o222aA222211o.",
        "...o222aA2211o..",
        "....o222a211o...",
        ".....o22211o....",
        "......o211o.....",
        ".......oo.......",
        "................",
        "................",
    ],
    "king_loot_box": [
        "................",
        "..KKKKKKKKKKKK..",
        ".KGnnnnnnnnnnGK.",
        ".KGnbbbbbbbbnGK.",
        ".KGnnnnnnnnnnGK.",
        ".KGKKKKKKKKKKGK.",
        ".KKKKKKYYKKKKKK.",
        ".KGnnnKbBKnnnGK.",
        ".KGnnKbCCbKnnGK.",
        ".KGnnKbbbbKnnGK.",
        ".KGnnnKbBKnnnGK.",
        ".KGnnnnKKnnnnGK.",
        ".KGKKKKKKKKKKGK.",
        ".KYGGGGGGGGGGgK.",
        "..KKKKKKKKKKKK..",
        "................",
    ],
}


def blank():
    return [[T] * 16 for _ in range(16)]


def golem_core():
    """A glowing amethyst held in a ring of stone."""
    rows = blank()
    cx, cy = 7.5, 7.5
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if 6.4 < d <= 7.2:
                rows[y][x] = PAL["o"]
            elif 4.6 < d <= 6.4:
                lit = (x - cx) + (y - cy)
                rows[y][x] = PAL["4"] if lit < -4 else PAL["3"] if lit < 0 else PAL["2"] if lit < 4 else PAL["1"]
                if (x * 7 + y * 3) % 11 == 0:
                    rows[y][x] = PAL["1"]
            elif d <= 4.6:
                # A cut gem: diamond facets around a bright heart.
                f = abs(x - cx) + abs(y - cy)
                rows[y][x] = PAL["L"] if f < 1.6 else PAL["A"] if f < 3.6 else PAL["a"] if f < 5.0 else PAL["o"]
    for x, y in ((6, 6), (6, 5), (5, 6)):
        rows[y][x] = PAL["L"]
    return rows


def staff(rows, ax, ay, bx, by, light, mid, dark, outline):
    """A two-pixel shaft along a diagonal, lit on its upper side."""
    for y in range(16):
        for x in range(16):
            vx, vy = bx - ax, by - ay
            t = ((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy)
            if not 0.0 <= t <= 1.0:
                continue
            d = math.hypot(x - (ax + vx * t), y - (ay + vy * t))
            if d <= 1.6:
                side = (x - (ax + vx * t)) + (y - (ay + vy * t))
                rows[y][x] = outline if d > 1.2 else light if side < 0 else mid if side < 0.6 else dark


def stone_staff():
    rows = blank()
    staff(rows, 2.5, 13.5, 10.0, 6.0, PAL["3"], PAL["2"], PAL["1"], PAL["o"])
    # The head: a floating rock studded with amethyst.
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 11.8, y - 4.0)
            if d <= 3.6:
                rows[y][x] = PAL["o"] if d > 3.0 else PAL["4"] if x + y < 14 else PAL["3"] if x + y < 17 else PAL["2"]
    for x, y, ch in ((11, 3, "A"), (12, 3, "L"), (12, 4, "A"), (11, 4, "a"), (13, 5, "a"), (10, 5, "A")):
        rows[y][x] = PAL[ch]
    for x, y in ((8, 2), (14, 8), (15, 2)):
        rows[y][x] = PAL["L"]
    return rows


def wither_staff():
    """A mysterious staff: a twisted black-wood shaft with soul-silver bands, ending in a gold claw
    that holds a floating orb of soul-fire and void. No skull - what it calls is left unsaid."""
    rows = blank()
    staff(rows, 2.5, 13.5, 9.2, 6.8, PAL["n"], PAL["K"], PAL["K"], PAL["K"])
    for t in (0.2, 0.5, 0.8):   # soul-silver bands
        x, y = int(round(2.5 + 6.7 * t)), int(round(13.5 - 6.7 * t))
        rows[y][x] = PAL["W"]
        rows[y - 1][x] = PAL["c"]
    # The claw: three gold prongs curling up round the orb.
    for x, y, ch in ((9, 6, "g"), (10, 7, "G"), (8, 5, "G"), (8, 4, "Y"), (11, 7, "G"), (12, 7, "Y"), (13, 6, "k"), (10, 5, "k")):
        rows[y][x] = PAL[ch]
    # The orb: void at the heart, soul-fire round it, a glint.
    cx, cy = 11.6, 3.6
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= 2.6:
                rows[y][x] = PAL["K"] if d < 1.0 else PAL["c"] if d < 1.8 else PAL["C"]
    rows[3][12] = PAL["E"] if "E" in PAL else PAL["K"]
    rows[2][11] = PAL["W"]
    for x, y in ((15, 1), (8, 1), (14, 6)):     # motes drifting off it
        rows[y][x] = PAL["C"]
    return rows




def blade(rows, pts, widths, edge, steel, spine, outline):
    """Paints a blade along a polyline: an outline, a steel body, a darker spine down the middle,
    and a bright edge on one side."""
    for y in range(16):
        for x in range(16):
            best = None
            for k in range(len(pts) - 1):
                (ax, ay), (bx, by) = pts[k], pts[k + 1]
                vx, vy = bx - ax, by - ay
                t = max(0.0, min(1.0, ((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy)))
                px, py = ax + vx * t, ay + vy * t
                d = math.hypot(x - px, y - py)
                w = widths[k] + (widths[k + 1] - widths[k]) * t
                side = (x - px) * vy - (y - py) * vx
                if best is None or d - w < best[0] - best[1]:
                    best = (d, w, side)
            d, w, side = best
            if d <= w + 0.55:
                rows[y][x] = outline if d > w - 0.45 else spine if d < w * 0.35 else edge if side > 0 else steel


def last_remembrance():
    """The King's greatsword, drawn from scratch: a broad pale blade with a soul-blue channel, a
    gold crossguard flaring into crown wings, a leather grip, a gold pommel round a soul gem, and
    a torn blue ribbon from the guard."""
    rows = blank()
    blade(rows, [(6.0, 9.6), (10.5, 5.1), (14.4, 1.2)], [1.9, 1.6, 0.35], PAL["W"], (204, 214, 226, 255), PAL["C"], PAL["K"])
    for x, y, ch in ((3, 7, "k"), (4, 7, "G"), (5, 8, "G"), (6, 9, "Y"), (7, 10, "G"), (8, 11, "G"), (8, 12, "k"),
                     (2, 6, "Y"), (9, 13, "Y"), (3, 8, "k"), (7, 11, "k")):
        rows[y][x] = PAL[ch]
    for x, y in ((5, 10), (4, 11), (3, 12)):
        rows[y][x] = PAL["n"]
    for x, y in ((4, 10), (3, 11)):
        rows[y][x] = (90, 56, 34, 255)
    for x, y, ch in ((1, 13, "k"), (2, 13, "G"), (2, 14, "G"), (1, 14, "k"), (2, 12, "k")):
        rows[y][x] = PAL[ch]
    rows[13][1] = PAL["C"]
    for x, y in ((8, 13), (8, 14), (9, 15)):
        rows[y][x] = PAL["c"]
    return rows


def wither_blade():
    """The Wither Skeleton Blade, drawn from scratch: a hooked black blade with a jagged edge and
    soul runes along its spine, a bone grip and a spiked black guard."""
    rows = blank()
    blade(rows, [(5.5, 10.0), (7.5, 5.5), (11.5, 2.0), (14.5, 3.5)], [1.5, 1.7, 1.3, 0.4], (168, 160, 186, 255), (98, 90, 112, 255), (40, 36, 48, 255), PAL["K"])
    for x, y in ((6, 5), (8, 3), (10, 1), (12, 1)):
        rows[y][x] = (168, 160, 186, 255)
    for x, y in ((7, 7), (9, 4), (11, 3)):
        rows[y][x] = PAL["C"]
    for x, y, ch in ((3, 9, "K"), (4, 10, "n"), (6, 11, "n"), (7, 12, "K"), (2, 8, "n"), (8, 13, "n")):
        rows[y][x] = PAL[ch]
    for x, y in ((4, 11), (3, 12), (2, 13)):
        rows[y][x] = PAL["b"]
    rows[12][2] = PAL["B"]
    rows[14][1] = PAL["C"]
    return rows


def main():
    for name, grid in GRIDS.items():
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), (name, [len(r) for r in grid])
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])
    write_png(f"{OUT}/golem_core.png", golem_core())
    write_png(f"{OUT}/stone_staff.png", stone_staff())
    write_png(f"{OUT}/wither_staff.png", wither_staff())
    write_png(f"{OUT}/wither_blade.png", wither_blade())
    write_png(f"{OUT}/last_remembrance.png", last_remembrance())


if __name__ == "__main__":
    main()
