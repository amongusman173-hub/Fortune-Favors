#!/usr/bin/env python3
"""Draws the four Mystery Key tiers.

The old keys were one shape in four colours, so in a hotbar the only way to tell a common
key from a legendary one was to read the hue. These keep one family resemblance - a bow at
the upper left, a shaft to the lower right, teeth on the underside - and give each tier a
silhouette of its own:

* common    - plain iron ring, two teeth
* rare      - sapphire set into the ring, a notched bit
* epic      - a four-pointed amethyst bow, three teeth
* legendary - a crowned gold bow with a ruby, three teeth, and it glints

This replaces the key drawing in tools/make_fix_textures.py.

Run:  python3 tools/make_mystery_keys.py [--out DIR]
"""
import argparse
import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelkit import Canvas, ramp, rgba  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
SPARK = rgba("#ffffff")


def key(out, name, metal, gem=None, teeth=(20, 24), notch=False, bow="ring", sparks=()):
    deep, shadow, base, light = ramp(metal)
    c = Canvas()
    cx, cy = 4.5, 4.5

    # shaft: a two-pixel diagonal from the bow to the tip, lit on its upper side
    for u in range(9, 27):
        for d in (0, 1):
            # x - y = d, x + y = u
            if (u + d) % 2:
                continue
            x, y = (u + d) // 2, (u - d) // 2
            c.set(x, y, light if d == 1 else base)
    # a collar where the shaft leaves the bow
    for x, y in ((6, 7), (7, 6), (7, 7)):
        c.set(x, y, light)
    c.set(8, 8, shadow)

    # teeth hang off the underside (y > x) near the tip
    for x0 in teeth:
        # one tooth: a short spur straight down from the shaft at column x0, two pixels long,
        # with at least one empty column between teeth so the outline keeps them apart
        c.set(x0, x0 + 1, base)
        c.set(x0, x0 + 2, base)
        c.set(x0, x0 + 3, shadow)
    if notch:
        c.clear(11, 12)

    # the bow
    def ring(x, y, outer, inner):
        r = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        return inner <= r <= outer

    def lit(x, y):
        # upper-left light: brighter the further up-left of centre the pixel sits
        k = (cx - (x + 0.5)) + (cy - (y + 0.5))
        if k > 1.6:
            return light
        if k > -0.6:
            return base
        if k > -2.4:
            return shadow
        return deep

    if bow == "ring":
        c.fill_where(lambda x, y: ring(x, y, 3.7, 1.5 if gem is None else 0.0), lit)
    elif bow == "star":
        def star(x, y):
            dx, dy = abs(x + 0.5 - cx), abs(y + 0.5 - cy)
            return dx + dy <= 3.2 or (dx <= 0.6 and dy <= 4.2) or (dy <= 0.6 and dx <= 4.2)
        c.fill_where(star, lit)
    elif bow == "crown":
        c.fill_where(lambda x, y: ring(x, y, 3.4, 0.0) and y >= 2, lit)
        # a crown standing on the bow: three points and a band
        for x, y in ((2, 0), (4, 0), (6, 0)):
            c.set(x, y, light)
        for x in range(2, 7):
            c.set(x, 1, base if x % 2 else light)

    if gem is not None:
        g_deep, g_shadow, g_base, g_light = ramp(gem)
        for (x, y), col in {(4, 4): g_light, (5, 4): g_base, (4, 5): g_base, (5, 5): g_shadow}.items():
            c.set(x, y, col)

    c.outline(skip=[SPARK])
    for x, y in sparks:
        c.set(x, y, SPARK)
    out.mkdir(parents=True, exist_ok=True)
    c.image().save(out / f"{name}.png")
    print("wrote", name)


def main(out):
    key(out, "mystery_key_common", "#b9c0cb", teeth=(9, 12))
    key(out, "mystery_key_rare", "#4f9fe6", gem="#a8ecff", teeth=(9, 12), notch=False)
    key(out, "mystery_key_epic", "#a970f2", gem="#f0c8ff", teeth=(9, 12), bow="star")
    key(out, "mystery_key_legendary", "#f2c445", gem="#ff3d5a", teeth=(9, 12), bow="crown",
        sparks=((14, 2), (13, 3), (15, 3), (14, 4), (2, 14)))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    main(ap.parse_args().out)
