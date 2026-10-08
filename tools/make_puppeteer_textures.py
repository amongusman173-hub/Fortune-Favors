#!/usr/bin/env python3
"""Generates the item art for The Puppeteer's kit.

Five textures and their five `models/item/*.json` files: the Wooden Marionette
(his summon), the Puppeteer Loot Box, the Puppeteer's Mask, the Marionette
Strings and The Empty Mask.

Like every other boss's generator this is checked in, so the pixels are
reproducible and reviewable rather than a binary blob with no provenance.

The reason this script exists at all is a bug rather than a missing step. The five
items were minted against model ids 4550280-4550284, which already belong to
Excalibur (4550280), the Mystery Box (4550281) and the four Mystery Key tiers
(4550282-4550285). A `custom_model_data` id only has to be unique *per base item*,
so nothing ever failed - the ids the Puppeteer's kit pointed at simply belonged to
somebody else, and the proof of it is that there were no textures to draw for them
in the first place: nothing had ever been commissioned under those numbers. The ids
they now use are 4550290-4550294 (see `MARIONETTE_MODEL` and friends in
`ModItems.java`), which are theirs.

Each item also needs a dispatch entry in `tools/make_item_definitions.py`; this
script only draws the pixels and writes the models.

Run:  python3 tools/make_puppeteer_textures.py
"""
import json
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

# ------------------------------------------------------------- puppeteer palette
# Porcelain over wood, and the one colour the face has: the paint.
PORCELAIN = (238, 234, 226, 255)
PORCELAIN_SHADE = (198, 192, 182, 255)
PORCELAIN_DEEP = (150, 145, 136, 255)
PAINT = (176, 42, 86, 255)
PAINT_DARK = (116, 22, 54, 255)
WOOD = (150, 104, 58, 255)
WOOD_DARK = (92, 60, 32, 255)
WOOD_LIGHT = (192, 146, 92, 255)
STRING = (230, 228, 218, 255)
STRING_DIM = (166, 164, 154, 255)
VIOLET = (150, 110, 236, 255)
VIOLET_DARK = (86, 56, 158, 255)
VIOLET_LIGHT = (198, 174, 250, 255)
BLACK = (26, 24, 28, 255)
CLEAR = (0, 0, 0, 0)

# Nothing here is held like a weapon - every one of the five is a flat icon.
HANDHELD = set()


def canvas(size=16):
    img = Image.new("RGBA", (size, size), CLEAR)
    return img, ImageDraw.Draw(img)


def save(img, name):
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / f"{name}.png")
    model = {
        "parent": "minecraft:item/handheld" if name in HANDHELD else "minecraft:item/generated",
        "textures": {"layer0": f"fortuneandfavors:item/{name}"},
    }
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / f"{name}.json").write_text(json.dumps(model, indent=2) + "\n")
    print("wrote", name)


def chest(d, lid, body, band, latch, edge=BLACK):
    """The shared loot-box silhouette, so the box reads as a box beside its siblings."""
    d.rectangle([1, 2, 14, 7], fill=lid, outline=edge)
    d.rectangle([1, 7, 14, 14], fill=body, outline=edge)
    d.line([1, 7, 14, 7], fill=edge)
    d.line([4, 2, 4, 14], fill=band)
    d.line([11, 2, 11, 14], fill=band)
    d.rectangle([7, 7, 8, 10], fill=latch, outline=edge)


def string_run(d, x0, y0, x1, y1, colour=STRING):
    """A single taut string, drawn as a thin line with a highlight."""
    d.line([x0, y0, x1, y1], fill=colour)


# ------------------------------------------------------------------ the marionette

def wooden_marionette():
    """A little wooden man, jointed at the knees, on four strings going up.

    The strings leave the frame on purpose: it is hanging from something above the
    icon, which is the whole point of the item - the thing holding them is him.
    """
    img, d = canvas()
    # The four strings, out of frame at the top.
    string_run(d, 4, 0, 6, 4, STRING_DIM)
    string_run(d, 12, 0, 10, 4, STRING_DIM)
    string_run(d, 6, 0, 7, 5, STRING)
    string_run(d, 10, 0, 9, 5, STRING)

    # The head: a small painted block of wood.
    d.rectangle([6, 4, 10, 7], fill=WOOD_LIGHT, outline=WOOD_DARK)
    d.point([7, 5], fill=BLACK)
    d.point([9, 5], fill=BLACK)
    d.line([7, 6, 9, 6], fill=PAINT)

    # The body, the jointed arms, then the legs - knees marked, because that is
    # the detail the item's own lore promises.
    d.rectangle([6, 8, 10, 11], fill=WOOD, outline=WOOD_DARK)
    d.line([5, 8, 6, 10], fill=WOOD, width=1)
    d.line([10, 10, 11, 8], fill=WOOD, width=1)
    d.point([5, 10], fill=WOOD_DARK)
    d.point([11, 10], fill=WOOD_DARK)
    d.line([7, 12, 7, 15], fill=WOOD_DARK)
    d.line([9, 12, 9, 15], fill=WOOD_DARK)
    d.line([6, 13, 8, 13], fill=WOOD_LIGHT)
    d.line([8, 13, 10, 13], fill=WOOD_LIGHT)
    save(img, "wooden_marionette")


# The four loot-pool pieces are placed pixel by pixel through tools/pixelkit.py and get its
# selective outline; the marionette above is his summon, not loot, and keeps its older art.
import sys as _sys  # noqa: E402
_sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelkit import Canvas, ramp, rgba  # noqa: E402

P_DEEP, P_SHADE, P_BASE, P_LIGHT = ramp("#e9e2d4")
V_DEEP, V_SHADE, V_BASE, V_LIGHT = ramp("#8d5cf0")
R_DEEP, R_SHADE, R_BASE, R_LIGHT = ramp("#c3283f")
G_DEEP, G_SHADE, G_BASE, G_LIGHT = ramp("#e2b24a")
W_DEEP, W_SHADE, W_BASE, W_LIGHT = ramp("#9a6a3c")
HOLE = rgba("#120e16")
GLOWV = rgba("#e6d6ff")
THREAD = rgba("#f3efe4")

MASK_PALETTE = {
    "U": P_LIGHT, "P": P_BASE, "S": P_SHADE, "D": P_DEEP,
    "k": HOLE, "v": V_SHADE, "V": V_LIGHT, "g": GLOWV,
    "R": R_BASE, "r": R_DEEP, "L": R_LIGHT, "G": G_BASE, "Y": G_LIGHT,
}


def _save_canvas(c, name):
    save(c.image(), name)


def puppeteers_mask():
    """His face: porcelain, harlequin paint round the eyes, a stitched grin, one tear."""
    c = Canvas()
    c.grid([
        "................",
        ".....UUUUUP.....",
        "...UUPPPPPPPS...",
        "..UPPPPPPPPPPS..",
        "..UPvVPPPPVvPS..",
        ".UPvkkVPPVkkvPS.",
        ".UPvkkvPPvkkvPS.",
        ".UPPvvPPPPvvPPS.",
        ".UPPPkPPPPPPPPS.",
        ".UPPPkPPPPPPPSS.",
        "..PLPPPPPPPPLSS.",
        "..PSRRrRRrRRSD..",
        "...SSRRRRRRSD...",
        "....SSSSSSDD....",
        "......DDDD......",
        "................",
    ], MASK_PALETTE)
    c.outline()
    _save_canvas(c, "puppeteers_mask")


def empty_mask():
    """The same porcelain with nothing behind it: cracked, mouthless, lit from inside."""
    c = Canvas()
    c.grid([
        "................",
        ".....UUUUUP.....",
        "...UUPPPPPPPS...",
        "..UPPPPPPPDPPS..",
        "..UPPPPPPDPPPS..",
        ".UPkkkPPPDkkkPS.",
        ".UPkgkPPDPkgkPS.",
        ".UPPkPPPDPPkPPS.",
        ".UPPPPPDPPPPPPS.",
        ".UPPPPPPPPPPPPS.",
        "..PPPPPPPPPPPSS.",
        "..PSPPPPPPPPSD..",
        "...SSPPPPPPSD...",
        "....SSSSSSDD....",
        "......DDDD......",
        "................",
    ], MASK_PALETTE)
    c.outline(skip=[GLOWV])
    _save_canvas(c, "empty_mask")


def marionette_strings():
    """The control bar, the way a marionette is held, with its strings and their knots."""
    c = Canvas()
    c.grid([
        "................",
        ".......VW.......",
        "..VVVVVVWWWWWw..",
        "..WWWWWWWWWWww..",
        "..t.t..Ww.t..t..",
        "..t.t..Ww.t..t..",
        "..t..t.Ww.t.t...",
        "..t..t..w.t.t...",
        "..t..t...t..t...",
        "...t..t..t.t....",
        "...t..t..t.t....",
        "...o..t..t.o....",
        "......t..t......",
        "......o..o......",
        "................",
        "................",
    ], {"V": W_LIGHT, "W": W_BASE, "w": W_SHADE, "t": THREAD, "o": V_LIGHT})
    # gold caps on the bar's ends
    for x, y, col in ((2, 2, G_LIGHT), (2, 3, G_BASE), (13, 2, G_BASE), (13, 3, G_SHADE)):
        c.set(x, y, col)
    c.outline(skip=[THREAD, V_LIGHT])
    _save_canvas(c, "marionette_strings")


def puppeteer_loot_box():
    """His box: violet lacquer, a stage curtain drawn back on either side, the mask for a latch."""
    c = Canvas()
    c.grid([
        "................",
        "................",
        "..VVVVVVVVVVVV..",
        "..VPPPPPPPPPPp..",
        "..VPPPPPPPPPPp..",
        "..VpppppppppPp..",
        "..YGGGGYYGGGGg..",
        "..LRrUUUUUUrRR..",
        "..RRrUkUUkUrRr..",
        "..RRrUUmmUUrRr..",
        "..RrPPUUUUPPrr..",
        "..RrPPPPPPPPrr..",
        "..rPPPPPPPPPPr..",
        "..VPPPPPPPPPPp..",
        "..dddddddddddd..",
        "................",
    ], {"V": V_LIGHT, "P": V_BASE, "p": V_SHADE, "d": V_DEEP,
        "G": G_BASE, "g": G_SHADE, "Y": G_LIGHT,
        "R": R_BASE, "r": R_SHADE, "L": R_LIGHT,
        "U": P_LIGHT, "k": HOLE, "m": R_BASE})
    c.outline()
    _save_canvas(c, "puppeteer_loot_box")


if __name__ == "__main__":
    wooden_marionette()
    puppeteers_mask()
    empty_mask()
    marionette_strings()
    puppeteer_loot_box()
