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


def puppeteers_mask():
    """Porcelain over wood, with the smile painted on. The only part that is carved."""
    img, d = canvas()
    d.ellipse([2, 1, 13, 14], fill=PORCELAIN, outline=BLACK)
    # The painted eyes, and the shade the porcelain takes under its own brow.
    d.ellipse([4, 5, 6, 8], fill=BLACK)
    d.ellipse([9, 5, 11, 8], fill=BLACK)
    d.line([4, 3, 11, 3], fill=PORCELAIN_SHADE)
    # The smile: carved rather than painted, so it is cut in, not drawn on.
    d.line([5, 10, 10, 10], fill=PAINT)
    d.point([4, 9], fill=PAINT_DARK)
    d.point([11, 9], fill=PAINT_DARK)
    d.line([5, 11, 10, 11], fill=PAINT_DARK)
    d.line([3, 12, 3, 13], fill=PORCELAIN_DEEP)
    d.line([12, 12, 12, 13], fill=PORCELAIN_DEEP)
    save(img, "puppeteers_mask")


def empty_mask():
    """No face at all - the same porcelain, with nothing behind the eye holes.

    The slits glow because the mask is worn rather than held: something is looking
    out of it, and it is not a face.
    """
    img, d = canvas()
    d.ellipse([2, 1, 13, 14], fill=PORCELAIN_SHADE, outline=BLACK)
    d.ellipse([3, 2, 12, 12], fill=PORCELAIN)
    # Empty eye holes with a violet light behind them.
    d.line([4, 6, 7, 6], fill=BLACK)
    d.line([8, 6, 11, 6], fill=BLACK)
    d.line([4, 7, 7, 7], fill=VIOLET_DARK)
    d.line([8, 7, 11, 7], fill=VIOLET_DARK)
    d.point([5, 6], fill=VIOLET)
    d.point([10, 6], fill=VIOLET)
    # No mouth. The absence is the design.
    d.line([5, 11, 10, 11], fill=PORCELAIN_DEEP)
    d.line([6, 12, 9, 12], fill=PORCELAIN_DEEP)
    d.point([3, 4], fill=PORCELAIN_DEEP)
    d.point([12, 10], fill=PORCELAIN_DEEP)
    save(img, "empty_mask")


def marionette_strings():
    """A control bar with a bundle of strings wound onto it.

    Read at a glance as "a thing you hold that has strings coming off it", because
    the item's two verbs - tie one to somebody, then pull - both start here.
    """
    img, d = canvas()
    # The cross-bar, the way a marionette is actually held.
    d.rectangle([2, 2, 13, 3], fill=WOOD_LIGHT, outline=WOOD_DARK)
    d.point([2, 2], fill=WOOD_DARK)
    d.point([13, 3], fill=WOOD_DARK)
    d.line([7, 2, 7, 8], fill=WOOD_DARK)
    # Four strings leaving the bar and gathering into a bundle.
    for i, x in enumerate((4, 6, 9, 11)):
        d.line([x, 4, 8 + (i - 1), 9], fill=STRING if i % 2 == 0 else STRING_DIM)
    # The bundle itself: it never stops moving, so it is drawn mid-turn.
    d.ellipse([5, 9, 11, 14], fill=STRING_DIM, outline=BLACK)
    d.ellipse([6, 10, 10, 13], fill=STRING)
    d.line([6, 11, 10, 11], fill=STRING_DIM)
    d.line([6, 12, 10, 12], fill=STRING_DIM)
    d.point([8, 11], fill=VIOLET)
    save(img, "marionette_strings")


def puppeteer_loot_box():
    """His box: violet lacquer with a porcelain mask for a latch."""
    img, d = canvas()
    chest(d, VIOLET_DARK, VIOLET, PORCELAIN_SHADE, PORCELAIN, BLACK)
    # The latch is the mask, small enough to read at icon size.
    d.ellipse([6, 7, 9, 10], fill=PORCELAIN, outline=BLACK)
    d.point([7, 8], fill=BLACK)
    d.point([8, 8], fill=BLACK)
    d.line([7, 9, 8, 9], fill=PAINT)
    # A thread tied round the whole box, because nothing of his is loose.
    d.line([1, 12, 14, 12], fill=STRING_DIM)
    d.point([2, 3], fill=VIOLET_LIGHT)
    save(img, "puppeteer_loot_box")


if __name__ == "__main__":
    wooden_marionette()
    puppeteers_mask()
    empty_mask()
    marionette_strings()
    puppeteer_loot_box()
