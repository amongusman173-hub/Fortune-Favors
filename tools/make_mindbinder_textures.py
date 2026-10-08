#!/usr/bin/env python3
"""Item textures for the Mindbinder's loot: violet, lilac, a cyan eye, gold trim. Stdlib only.

Not generated here (hand-made, kept as it is): mindbinder_staff.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_mindbinder_textures.py
"""
import math

from make_scarlet_devil_textures import OUT, write_png

T = (0, 0, 0, 0)
PAL = {
    ".": T,
    "K": (18, 8, 28, 255), "v": (60, 24, 96, 255), "V": (110, 50, 170, 255), "L": (180, 130, 240, 255),
    "l": (230, 200, 255, 255), "C": (90, 230, 255, 255), "c": (40, 140, 180, 255), "W": (245, 240, 255, 255),
    "P": (236, 228, 240, 255), "p": (190, 180, 205, 255), "G": (226, 176, 66, 255), "g": (150, 104, 34, 255),
    "k": (70, 44, 14, 255),
}

GRIDS = {
    "mindbinder_loot_box": [
        "................",
        "..KKKKKKKKKKKK..",
        ".KGLLLLLLLLLLGK.",
        ".KGVlllllllLVGK.",
        ".KGVVVVVVVVVVGK.",
        ".KGvvvvvvvvvvGK.",
        ".KKKKKWWWWKKKKK.",
        ".KGVVKWCCWKVVGK.",
        ".KGVKWCKKCWKVGK.",
        ".KGVVKWCCWKVVGK.",
        ".KGVVVKWWKVVVGK.",
        ".KGVVVVKKVVVVGK.",
        ".KGvvvvvvvvvvGK.",
        ".KgGGGGGGGGGGgK.",
        "..KKKKKKKKKKKK..",
        "................",
    ],
    "mindbinder_shroud": [
        "................",
        ".....KKKKKK.....",
        "....KvVVVVvK....",
        "...KvVKKKKVvK...",
        "..KKvKllllKvKK..",
        ".KVVvKKKKKKvVVK.",
        ".KLVVVVVVVVVVLK.",
        ".KLVVVWCCWVVVLK.",
        ".KLVVWCKKCWVVLK.",
        ".KVVVVWCCWVVVVK.",
        ".KVVVVVVVVVVVVK.",
        ".KvVVVVVVVVVVvK.",
        ".KvvVVVVVVVVvvK.",
        ".KGgGgGgGgGgGgK.",
        "..KKKKKKKKKKKK..",
        "................",
    ],
    "possessed_mask": [
        "................",
        "....KKKKKKKK....",
        "...KPPPPPPPPK...",
        "..KPPPPPPPPPpK..",
        "..KPPPPPPKPPpK..",
        ".KPPVVPPKPVVPpK.",
        ".KPVlLVPKVlLVpK.",
        ".KPVLCVPKVLCVpK.",
        ".KPPVVPPPKVVPpK.",
        ".KPPPPPPPKPPPpK.",
        "..KPPPPPPPKPpK..",
        "..KPPpKKKpPPpK..",
        "...KPPPPPPPpK...",
        "....KpPPPPpK....",
        ".....KKKKKK.....",
        "................",
    ],
}


def shattered_mind():
    """A glass orb split into shards by cracks, glowing violet at the heart and cyan at the breaks."""
    rows = [[T] * 16 for _ in range(16)]
    cx, cy = 7.5, 7.5
    cracks = [(0.6, 1.0), (2.3, 1.0), (3.9, 1.0), (5.2, 1.0)]   # crack angles through the centre
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d > 7.0:
                continue
            a = math.atan2(y - cy, x - cx) % (2.0 * math.pi)
            on_crack = any(min(abs(a - c), 2.0 * math.pi - abs(a - c)) * d < 0.7 for c, _ in cracks)
            if d > 6.2:
                rows[y][x] = PAL["K"]
            elif on_crack and d > 1.2:
                rows[y][x] = PAL["C"] if d > 4.5 else PAL["W"]
            else:
                lit = (x - cx) + (y - cy)
                rows[y][x] = PAL["l"] if d < 1.6 else PAL["L"] if lit < -3.0 else PAL["V"] if lit < 3.0 else PAL["v"]
    for x, y in ((4, 4), (5, 3)):   # gloss on the glass
        rows[y][x] = PAL["W"]
    for x, y in ((14, 2), (1, 13), (15, 9)):   # shards breaking off
        rows[y][x] = PAL["L"]
    return rows


def main():
    for name, grid in GRIDS.items():
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), (name, [len(r) for r in grid])
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])
    write_png(f"{OUT}/shattered_mind.png", shattered_mind())


if __name__ == "__main__":
    main()
