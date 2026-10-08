#!/usr/bin/env python3
"""Item textures for the Slime King set: royal gelatin - glossy green, gold, one ruby. Stdlib only.

The gelatinous crown and the mythical gelatin are hand-made and deliberately not generated here.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_slime_king_textures.py
"""
import math

from make_scarlet_devil_textures import OUT, write_png

T = (0, 0, 0, 0)
PAL = {
    ".": T,
    "o": (18, 56, 22, 255),      # outline
    "d": (46, 122, 44, 255),     # dark gel
    "g": (92, 186, 70, 255),     # gel
    "l": (156, 230, 116, 255),   # light gel
    "h": (226, 255, 200, 255),   # gloss
    "k": (74, 48, 14, 255),      # gold outline
    "G": (226, 176, 66, 255),    # gold
    "Y": (255, 234, 150, 255),   # gold light
    "R": (200, 34, 62, 255),     # ruby
    "r": (116, 14, 36, 255),     # ruby dark
}

GRIDS = {
    "slime_boots": [
        "................",
        "................",
        "................",
        "................",
        "..oooo....oooo..",
        "..ohgo....ohgo..",
        "..olgo....olgo..",
        "..olgo....olgo..",
        "..olgdo...olgdo.",
        ".oolggdo.oolggdo",
        "olhggggdolhggggd",
        "oddddddooddddddo",
        "oGGGGGGooGGGGGGo",
        "oooooooooooooooo",
        "................",
        "................",
    ],
    "slime_loot_box": [
        "................",
        "..oooooooooooo..",
        ".oGhhhhhhhhhhGo.",
        ".oGllllllllllGo.",
        ".oGggggggggggGo.",
        ".oGddddddddddGo.",
        ".ooooooYYoooooo.",
        ".oGgggkYGkgggGo.",
        ".oGgggGYYGgggGo.",
        ".oGhggkGgkgglGo.",
        ".oGggggkkggggGo.",
        ".oGgglgggggggGo.",
        ".oGddddddddddGo.",
        ".oGGGGGGGGGGGGo.",
        "..oooooooooooo..",
        "................",
    ],
}


def blank():
    return [[T] * 16 for _ in range(16)]


def slime_launcher():
    """A gel cannon, side on and pointing right: a round glass tank of slime with bubbles in it,
    gold bands, a flared gold muzzle, and a grip underneath."""
    grid = [
        "................",
        "................",
        "................",
        "...oooooooo.....",
        "..ohhllllllok...",
        ".ohlgggglgggkGk.",
        ".olgglggggglkYGk",
        ".olggggggggokGGk",
        ".odgggglgggdkGk.",
        "..oddddddddok...",
        "...oookoooo.....",
        "......kGk.......",
        "......kGk.......",
        "......kgk.......",
        "......kkk.......",
        "................",
    ]
    rows = [[PAL[ch] for ch in r] for r in grid]
    for x, y in ((4, 6), (8, 5), (6, 8)):   # bubbles
        rows[y][x] = PAL["h"]
    return rows


def slime_shield():
    """A heater shield: gold rim, a field of gel lit from the top-left, a ruby-set crown."""
    rows = blank()
    for y in range(1, 16):
        half = 6.5 if y < 8 else max(0.0, 6.5 * (1.0 - ((y - 7.5) / 7.0) ** 1.6))
        for x in range(16):
            dx = abs(x - 7.5)
            if dx > half:
                continue
            edge = half - dx < 1.0 or y == 1
            rim = half - dx < 2.0 or y == 2
            if edge:
                rows[y][x] = PAL["k"]
            elif rim:
                rows[y][x] = PAL["Y"] if x < 8 and y < 8 else PAL["G"]
            else:
                lit = (x - 7.5) + (y - 6.0)
                rows[y][x] = PAL["l"] if lit < -4.0 else PAL["d"] if lit > 5.0 else PAL["g"]
    for x, y, ch in ((5, 5, "G"), (7, 5, "G"), (8, 5, "G"), (10, 5, "G"), (5, 6, "G"), (6, 6, "G"), (7, 6, "R"),
                     (8, 6, "R"), (9, 6, "G"), (10, 6, "G"), (5, 7, "k"), (6, 7, "G"), (7, 7, "G"), (8, 7, "G"),
                     (9, 7, "G"), (10, 7, "k"), (6, 9, "h"), (5, 10, "h")):
        rows[y][x] = PAL[ch]
    return rows


def main():
    for name, grid in GRIDS.items():
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), name
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])
    write_png(f"{OUT}/slime_launcher.png", slime_launcher())
    write_png(f"{OUT}/slime_shield.png", slime_shield())


if __name__ == "__main__":
    main()
