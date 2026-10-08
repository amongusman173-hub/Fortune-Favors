#!/usr/bin/env python3
"""Derives `enderheart_awakened.png` from `enderheart.png` by relighting it.

The two forms of the Enderheart are the same object awake and asleep, so they are the same
texture: the awakened one takes the base image's silhouette **pixel for pixel** - every opaque
cell stays opaque and every transparent one stays transparent - and changes only how it is lit.
That is the whole rule, and it is why this is a script rather than a second drawing: two hand-
drawn files drift apart, and the pair stops reading as one item.

The relight, in the order it is applied:

  * **gamma.** `v' = v ** 0.7` on the value channel. A gamma rather than a multiply, because a
    multiply on a palette that is mostly near-black (median brightness 0.17) does nothing to
    most of it, while a gamma lifts the darks and leaves the few bright pixels near the top -
    which is what "waking up" looks like as opposed to "being brighter".
  * **a slight desaturation** (0.86). Light washes colour out; without this the purples get
    louder as they get brighter, which reads as a different material rather than the same one
    turned on.
  * **the pack's own awakened accent.** The form this replaces used `(222, 176, 255)` for its
    highlights, so anything the gamma lifts above 0.72 is blended a quarter of the way to that
    exact violet. The two generations of the item therefore still share a highlight colour.

Run:  python3 tools/make_enderheart_awakened.py
"""

import colorsys
import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
ITEMS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
BASE = ITEMS / "enderheart.png"
OUT = ITEMS / "enderheart_awakened.png"

GAMMA = 0.7
DESATURATE = 0.86

# The highlight this item's awakened form has always used - kept so the two share a palette.
ACCENT = (222, 176, 255)
ACCENT_FROM = 0.72
ACCENT_MIX = 0.25


def relight(pixel):
    r, g, b, a = pixel
    if a == 0:
        # A transparent pixel carries no colour: keeping the old rgb under alpha 0 is how a
        # texture grows a halo wherever something blends it against the world.
        return (0, 0, 0, 0)
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    v = v**GAMMA
    s *= DESATURATE
    r2, g2, b2 = colorsys.hsv_to_rgb(h, s, v)
    out = [round(c * 255) for c in (r2, g2, b2)]
    if v >= ACCENT_FROM:
        out = [
            round(c * (1 - ACCENT_MIX) + accent * ACCENT_MIX)
            for c, accent in zip(out, ACCENT)
        ]
    return (out[0], out[1], out[2], a)


def main():
    base = Image.open(BASE).convert("RGBA")
    out = Image.new("RGBA", base.size)
    for y in range(base.height):
        for x in range(base.width):
            out.putpixel((x, y), relight(base.getpixel((x, y))))

    # The one thing this must not do is change the shape, so it is asserted rather than trusted.
    def mask(im):
        return [[im.getpixel((x, y))[3] > 0 for x in range(im.width)] for y in range(im.height)]

    if mask(base) != mask(out):
        raise SystemExit("the relight moved a pixel - the awakened form is meant to be the same shape")

    out.save(OUT, optimize=True)
    opaque = [(x, y) for y in range(16) for x in range(16) if out.getpixel((x, y))[3] > 0]
    values = sorted(
        colorsys.rgb_to_hsv(*[c / 255 for c in out.getpixel(p)[:3]])[2] for p in opaque
    )
    print(f"wrote {OUT.relative_to(ROOT)} - {len(opaque)} opaque cells, silhouette identical to the base")
    print(f"  brightness: min {values[0]:.2f}  median {values[len(values) // 2]:.2f}  max {values[-1]:.2f}")


if __name__ == "__main__":
    main()
