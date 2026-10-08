#!/usr/bin/env python3
"""Item textures for the Snow Queen's loot: glacier blue, frost white, a silver trim. Stdlib only.

Hand-made and deliberately not generated here: cryogenic_core, frozen_heart, ice_staff. The Frostbound
Crown's bow art lives in make_frostbound_bow.py.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_snow_queen_textures.py
"""
from make_scarlet_devil_textures import OUT, write_png

T = (0, 0, 0, 0)
PAL = {
    ".": T,
    "K": (14, 26, 52, 255),     # outline
    "d": (30, 70, 140, 255),    # deep ice
    "b": (60, 130, 210, 255),   # ice
    "B": (120, 190, 245, 255),  # light ice
    "w": (200, 236, 255, 255),  # frost
    "W": (255, 255, 255, 255),
    "s": (120, 130, 150, 255),  # silver dark
    "S": (190, 200, 216, 255),  # silver
    "n": (40, 44, 70, 255),     # dark wood / leather
}

GRIDS = {
    "snow_loot_box": [
        "................",
        "..KKKKKKKKKKKK..",
        ".KSwwwwwwwwwwSK.",
        ".KSBBBBBBBBBBSK.",
        ".KSbbbbbbbbbbSK.",
        ".KSddddddddddSK.",
        ".KKKKKKWWKKKKKK.",
        ".KSbbbKwBKbbbSK.",
        ".KSbbKWBWBKbbSK.",
        ".KSbbKBWBWKbbSK.",
        ".KSbbbKBwKbbbSK.",
        ".KSbBbbKKbbbbSK.",
        ".KSddddddddddSK.",
        ".KsSSSSSSSSSSsK.",
        "..KKKKKKKKKKKK..",
        "................",
    ],
    # A short mantle: ice-blue shoulders, a white fur collar, silver clasp, frost along the hem.
    "glacier_cloak": [
        "................",
        "...KKK....KKK...",
        "..KwWwK..KwWwK..",
        ".KwWwwwKKwwwWwK.",
        ".KBwwwwSSwwwwBK.",
        ".KbBBBKSSKBBBbK.",
        ".KbBBBBKKBBBBbK.",
        ".KbbBBBBBBBBbbK.",
        ".KdbbBBBBBBbbdK.",
        ".KdbbbBBBBbbbdK.",
        ".KdbbbbBBbbbbdK.",
        ".KddbbbbbbbbddK.",
        ".KddbbbbbbbbddK.",
        ".KwdWdwdWdwdWwK.",
        ".KKwKKWKKWKKwKK.",
        "................",
    ],
}


def main():
    for name, grid in GRIDS.items():
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), (name, [len(r) for r in grid])
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])


if __name__ == "__main__":
    main()
