#!/usr/bin/env python3
"""Frostbound Crown (the Snow Queen's bow), drawn from scratch: two faceted ice-crystal limbs that
recurve to icicle tips, crystal spurs at the bends, a silver grip round a frozen gem, a frost
string, and an ice-shard arrow when drawn. Idle plus three pulling frames, laid on vanilla's bow
diagonal so the item/bow display pose and the draw animation still fit. Stdlib only.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_frostbound_bow.py
"""
import json

from make_ender_weapons import line
from make_golem_king_textures import blade, blank
from make_scarlet_devil_textures import OUT, write_png

MODELS = "resourcepack/assets/fortuneandfavors/models/item"
K = (14, 26, 52, 255)
DEEP = (40, 90, 160, 255)
ICE = (120, 190, 245, 255)
FROST = (210, 240, 255, 255)
WHITE = (255, 255, 255, 255)
SILVER = (190, 200, 216, 255)
GEM = (90, 230, 255, 255)


def frostbound(pull):
    """pull: -1 idle, 0..2 the draw frames."""
    rows = blank()
    # Each limb kinks twice on its way out - a recurve in ice, not a curve in wood.
    blade(rows, [(5.2, 5.2), (7.4, 3.2), (10.4, 2.2), (13.4, 0.8)], [1.9, 1.5, 1.0, 0.45], WHITE, ICE, FROST, K)
    blade(rows, [(5.2, 5.2), (3.2, 7.4), (2.2, 10.4), (0.8, 13.4)], [1.9, 1.5, 1.0, 0.45], WHITE, ICE, FROST, K)
    for x, y, c in ((7, 1, FROST), (6, 1, K), (1, 7, FROST), (1, 6, K), (11, 0, WHITE), (0, 11, WHITE)):   # crystal spurs
        rows[y][x] = c
    for x, y in ((14, 0), (0, 14)):          # icicle tips
        rows[y][x] = WHITE
    for x, y in ((5, 6), (6, 5), (4, 6), (6, 4)):   # the silver grip
        rows[y][x] = SILVER
    rows[5][5] = GEM
    rows[4][4] = WHITE
    bend = 0.0 if pull < 0 else 1.5 + pull
    mid = (7.0 + bend, 7.0 + bend)
    line(rows, (13, 1), mid, (180, 230, 255, 255))
    line(rows, mid, (1, 13), (180, 230, 255, 255))
    if pull >= 0:
        tip = (mid[0] - 6.5 - pull, mid[1] - 6.5 - pull)
        line(rows, mid, tip, DEEP)
        tx, ty = round(tip[0]), round(tip[1])
        for dx, dy, c in ((0, 0, WHITE), (-1, 0, FROST), (0, -1, FROST), (1, 0, K), (0, 1, K)):   # the shard head
            if 0 <= tx + dx < 16 and 0 <= ty + dy < 16:
                rows[ty + dy][tx + dx] = c
    return rows


def main():
    frames = ["frostbound_bow"] + [f"frostbound_bow_pulling_{i}" for i in range(3)]
    for pull, name in zip((-1, 0, 1, 2), frames):
        write_png(f"{OUT}/{name}.png", frostbound(pull))
        with open(f"{MODELS}/{name}.json", "w") as f:
            json.dump({"parent": "minecraft:item/bow", "textures": {"layer0": f"fortuneandfavors:item/{name}"}}, f, indent=2)
            f.write("\n")


if __name__ == "__main__":
    main()
