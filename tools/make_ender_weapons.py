#!/usr/bin/env python3
"""Voidfang and Starfall, drawn from scratch (not recoloured vanilla items). Stdlib only.

Starfall is laid out on vanilla's bow diagonal so the item/bow pose and the pulling animation fit
it: a crescent-moon bow, star ornaments at the tips, a glowing string that bends further back on
each draw frame, and a star-tipped bolt once drawn. Voidfang is a fang-curved blade.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_ender_weapons.py
"""
import math

from make_golem_king_textures import PAL, T, blade, blank
from make_scarlet_devil_textures import OUT, write_png

VIOLET = (110, 50, 170, 255)
DEEP = (46, 18, 76, 255)
EDGE = (190, 150, 255, 255)
CYAN = (110, 236, 255, 255)
STAR = (255, 236, 160, 255)


def line(rows, a, b, colour):
    steps = int(max(abs(b[0] - a[0]), abs(b[1] - a[1])) * 2) + 1
    for i in range(steps + 1):
        t = i / steps
        x, y = round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t)
        if 0 <= x < 16 and 0 <= y < 16:
            rows[y][x] = colour


def star(rows, x, y, core):
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        if 0 <= x + dx < 16 and 0 <= y + dy < 16:
            rows[y + dy][x + dx] = core if (dx, dy) == (0, 0) else STAR


def starfall(pull, awakened):
    """pull: -1 idle, 0..2 the draw frames."""
    rows = blank()
    # The crescent: an arc bowing toward the top-left, from the top-right tip to the bottom-left tip.
    cx, cy, r = 13.6, 13.6, 12.2
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            a = math.atan2(y - cy, x - cx)
            if -math.pi <= a <= -math.pi / 2 and r - 1.6 <= d <= r + 0.6:
                rows[y][x] = PAL["K"] if d > r + 0.1 or d < r - 1.2 else (EDGE if d > r - 0.5 else VIOLET)
    tip_a, tip_b = (13, 1), (1, 13)
    star(rows, *tip_a, CYAN if awakened else PAL["W"])
    star(rows, *tip_b, CYAN if awakened else PAL["W"])
    rows[4][4] = CYAN if awakened else STAR      # the grip gem on the belly of the crescent
    rows[5][4] = DEEP
    # The string, bent back toward the bottom-right as it is drawn.
    bend = 0.0 if pull < 0 else 1.5 + pull
    mid = (7.0 + bend, 7.0 + bend)
    glow = CYAN if awakened else (200, 240, 255, 255)
    line(rows, (12, 2), mid, glow)
    line(rows, mid, (2, 12), glow)
    if pull >= 0:
        # The bolt: from the string toward the top-left, a star at its point.
        tip = (mid[0] - 5.5 - pull, mid[1] - 5.5 - pull)
        line(rows, mid, tip, EDGE)
        star(rows, round(tip[0]), round(tip[1]), PAL["W"])
    return rows


def voidfang(awakened):
    rows = blank()
    blade(rows, [(4.5, 11.0), (8.5, 6.0), (12.5, 2.5), (14.6, 2.6)], [2.0, 1.7, 1.0, 0.3], EDGE, DEEP, PAL["K"], PAL["K"])
    crack = [(6, 8), (7, 7), (9, 6), (10, 4), (12, 3)]
    for x, y in crack:
        rows[y][x] = CYAN if awakened else VIOLET
    for x, y, c in ((3, 9, PAL["K"]), (2, 10, DEEP), (5, 12, DEEP), (6, 13, PAL["K"])):   # guard
        rows[y][x] = c
    for x, y in ((3, 12), (2, 13)):                                              # grip
        rows[y][x] = (60, 40, 70, 255)
    rows[14][1] = CYAN if awakened else VIOLET                                   # pommel gem
    if awakened:
        for x, y in ((15, 0), (11, 0), (14, 6)):
            rows[y][x] = EDGE
    return rows


def main():
    write_png(f"{OUT}/voidfang.png", voidfang(False))
    write_png(f"{OUT}/voidfang_awakened.png", voidfang(True))
    for awakened, base in ((False, "starfall"), (True, "starfall_awakened")):
        write_png(f"{OUT}/{base}.png", starfall(-1, awakened))
        for i in range(3):
            write_png(f"{OUT}/{base}_pulling_{i}.png", starfall(i, awakened))


if __name__ == "__main__":
    main()
