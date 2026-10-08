#!/usr/bin/env python3
"""Item textures for the Time Lord set. Stdlib only.

Round items (the watch, the rift) are computed per pixel so their cases are true circles with
light from the top-left; the hourglass, shard and loot box are hand-drawn grids.
(The Wither loot box this script used to draw is left as it is.)

Run from the repo root:  PYTHONPATH=tools python3 tools/make_time_lord_textures.py
"""
import math

from make_scarlet_devil_textures import OUT, write_png

T = (0, 0, 0, 0)
PAL = {
    ".": T,
    "k": (70, 44, 14, 255),      # gold outline
    "g": (164, 114, 36, 255),    # gold shadow
    "G": (226, 176, 66, 255),    # gold
    "Y": (255, 234, 150, 255),   # gold highlight
    "W": (246, 240, 226, 255),   # dial
    "w": (204, 194, 180, 255),   # dial shadow
    "K": (26, 18, 34, 255),      # ink / void
    "P": (176, 96, 236, 255),    # violet
    "p": (104, 44, 166, 255),    # deep violet
    "V": (226, 198, 255, 255),   # pale violet
    "C": (118, 226, 232, 255),   # cyan
    "c": (58, 150, 176, 255),    # deep cyan
    "L": (220, 252, 255, 255),   # cyan glint
    "A": (240, 180, 70, 255),    # sand
    "a": (184, 120, 40, 255),    # sand shadow
    "B": (92, 50, 30, 255),      # dark wood
    "b": (132, 80, 46, 255),     # wood
    "S": (190, 220, 236, 150),   # glass
    "E": (54, 22, 80, 255),      # chest dark
    "F": (86, 40, 128, 255),     # chest
    "H": (124, 66, 176, 255),    # chest light
    "s": (52, 84, 168, 255),     # blued steel
}


def blank():
    return [[T] * 16 for _ in range(16)]


def case(rows, cx, cy, outer):
    """A round gold case lit from the top-left; returns the inner radius."""
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if outer - 0.7 < d <= outer:
                rows[y][x] = PAL["k"]
            elif outer - 1.7 < d <= outer - 0.7:
                lit = (x - cx) + (y - cy)
                rows[y][x] = PAL["Y"] if lit < -3.0 else PAL["g"] if lit > 3.0 else PAL["G"]
    return outer - 1.7


def pocket_watch():
    """An open hunter-case watch: the engraved lid swung open behind it, a big cream dial with
    heavy marks at the quarters, blued steel hands, a violet jewel at the pin."""
    rows = blank()
    # The lid, open up and to the left, behind the body: gold rim, a dark engraved inside.
    lx, ly, lr = 4.2, 4.6, 4.0
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - lx, y - ly)
            if d <= lr:
                if d > lr - 0.9:
                    rows[y][x] = PAL["k"]
                elif d > lr - 1.7:
                    rows[y][x] = PAL["G"] if (x - lx) + (y - ly) < 0 else PAL["g"]
                else:
                    rows[y][x] = PAL["p"] if (x + y) % 3 == 0 else PAL["E"]
    # The body.
    cx, cy = 9.0, 9.4
    inner = case(rows, cx, cy, 6.4)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= inner:
                rows[y][x] = PAL["w"] if d > inner - 0.9 and (x - cx) + (y - cy) > 1.0 else PAL["W"]
    for x, y in ((9, 5), (13, 9), (9, 13), (5, 9)):   # quarter marks, heavy
        rows[y][x] = PAL["K"]
    for x, y in ((12, 6), (12, 12), (6, 12), (6, 6)):   # the others, light
        rows[y][x] = PAL["w"]
    for y in range(6, 10):                             # minute hand, blued steel
        rows[y][9] = PAL["s"]
    for x, y in ((10, 10), (11, 11)):                  # hour hand
        rows[y][x] = PAL["s"]
    rows[9][9] = PAL["P"]                              # the jewel
    rows[6][7] = PAL["L"]                              # glass glint
    rows[7][6] = PAL["L"]
    # Crown and bow on top of the body, and the hinge to the lid.
    for x, y, ch in ((8, 1, "k"), (9, 1, "Y"), (10, 1, "k"), (8, 2, "k"), (9, 2, "G"), (10, 2, "k"), (6, 4, "G"), (7, 4, "g")):
        rows[y][x] = PAL[ch]
    return rows


def pocket_watch_ii():
    """Version II: the same watch reforged - a netherite case with a gold rim, a dial lit violet."""
    rows = pocket_watch()
    swap = {PAL["G"]: (70, 62, 74, 255), PAL["Y"]: PAL["G"], PAL["g"]: (40, 34, 44, 255), PAL["W"]: (236, 222, 255, 255),
            PAL["w"]: (190, 160, 230, 255), PAL["P"]: PAL["C"], PAL["s"]: PAL["Y"]}
    return [[swap.get(px, px) for px in r] for r in rows]


def space_time_rift():
    rows = blank()
    cx, cy = 7.5, 8.0
    inner = case(rows, cx, cy, 7.5)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= inner:
                a = math.atan2(y - cy, x - cx)
                band = math.sin(a * 2.0 + d * 1.25)
                rows[y][x] = PAL["P"] if band > 0.55 else PAL["p"] if band > -0.1 else PAL["K"]
    # The tear down the middle: a jagged seam of light.
    seam = [(8, 2), (7, 3), (7, 4), (8, 5), (8, 6), (7, 7), (7, 8), (8, 9), (8, 10), (7, 11), (7, 12), (8, 13)]
    for i, (x, y) in enumerate(seam):
        rows[y][x] = PAL["W"] if 3 <= i <= 8 else PAL["V"]
        if 4 <= i <= 7:
            rows[y][x + (1 if x == 7 else -1)] = PAL["V"]
    for x, y in ((4, 5), (11, 11), (11, 5)):
        rows[y][x] = PAL["C"]
    return rows


GRIDS = {
    "chrono_shard": [
        "........k.......",
        ".......kLk......",
        "......kLCck.....",
        "......kLCck.....",
        ".....kLLCCck....",
        ".....kLCCCck....",
        "....kLCCYCcck...",
        "....kLCYGYcck...",
        "....kLCCYCcck...",
        "....kLCCCCcck...",
        ".....kLCCCck....",
        ".....kCCCcck....",
        "......kCcck.....",
        "......kcck......",
        ".......kk.......",
        "................",
    ],
    "hourglass_of_haste": [
        "..kkkkkkkkkkkk..",
        "..kYGGGGGGGGgk..",
        "..kBbbbbbbbbBk..",
        "...bSLAAAAAS b..",
        "...bSLAAAAaS b..",
        "...b.SLAAAS..b..",
        "...b..SLaS...b..",
        "...b...SA....b..",
        "...b...SA....b..",
        "...b..S.AS...b..",
        "...b.SL.A.S..b..",
        "...bS..AAa.S.b..",
        "...bSAAAAAaaSb..",
        "..kBbbbbbbbbBk..",
        "..kYGGGGGGGGgk..",
        "..kkkkkkkkkkkk..",
    ],
    "time_lord_loot_box": [
        "................",
        "..kkkkkkkkkkkk..",
        ".kGHHHHHHHHHHGk.",
        ".kGHVVVVVVVVHGk.",
        ".kGFFFFFFFFFFGk.",
        ".kGEEEEEEEEEEGk.",
        ".kkkkkkYYkkkkkk.",
        ".kGFFFkYWkFFFGk.",
        ".kGFFkYWKWkFFGk.",
        ".kGFFkGWWWkFFGk.",
        ".kGFFFkgGkFFFGk.",
        ".kGFFFFkkFFFFGk.",
        ".kGEEEEEEEEEEGk.",
        ".kYGGGGGGGGGGgk.",
        "..kkkkkkkkkkkk..",
        "................",
    ],
}


def main():
    write_png(f"{OUT}/pocket_watch.png", pocket_watch())
    write_png(f"{OUT}/space_time_rift.png", space_time_rift())
    write_png(f"{OUT}/pocket_watch_ii.png", pocket_watch_ii())
    for name, grid in GRIDS.items():
        grid = [r.replace(" ", ".") for r in grid]
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), name
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])


if __name__ == "__main__":
    main()
