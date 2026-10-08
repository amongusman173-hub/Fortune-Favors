#!/usr/bin/env python3
"""Draws Last Remembrance, the Distant Memory's own greatsword.

The Mirage Castle's monarch is the only boss in the mod who drops a blade that
was really in his hands, so his sword needs art of its own rather than the
netherite sword it is built on. It is deliberately nothing like the pack's other
two swords on the same base item:

    4550280  Excalibur          a broad gold-and-blue royal blade
    4550002  Wither Blade       bone-white, ragged, a necromancer's sword
    4550295  Last Remembrance   dark netherite steel, a gold fuller running the
                                whole length of it, and soul-fire in the fuller

Silhouette is what carries it: wider and heavier than the Distant Memory's
diamond sword (two pixels of blade against one), a full-length gold fuller
rather than a short echo core, and a crown-shaped pommel, so the one unique drop
in the castle reads as the greatsword it is claimed to be at a glance - from the
floor, from an inventory grid, and in a hotbar slot.

This generator is checked in for the same reason the rest of them are: the art
is reproducible and reviewable rather than a one-off binary.

Run:  python3 tools/make_monarch_relic_textures.py
"""
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

CLEAR = (0, 0, 0, 0)

# The pack's shared palette (see make_missing_item_textures.py), plus the two
# tones this blade needs that nothing else in the set does.
BLADE = (206, 214, 226, 255)
BLADE_DARK = (118, 128, 146, 255)
ECHO = (92, 226, 200, 255)
ECHO_DARK = (28, 96, 100, 255)
GOLD = (238, 196, 76, 255)
GOLD_DARK = (140, 104, 24, 255)
HANDLE = (96, 66, 40, 255)
HANDLE_DARK = (52, 36, 22, 255)

# Netherite steel: the darkest metal in the pack, because this is the blade of a
# court that is gone and it should not read as the same iron as everything else.
STEEL = (86, 82, 96, 255)
STEEL_LIGHT = (148, 144, 160, 255)
STEEL_DARK = (44, 42, 52, 255)


def canvas():
    img = Image.new("RGBA", (16, 16), CLEAR)
    return img, ImageDraw.Draw(img)


def save(img, name):
    TEX.mkdir(parents=True, exist_ok=True)
    img.save(TEX / f"{name}.png")
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / f"{name}.json").write_text(
        "{\n"
        '  "parent": "minecraft:item/generated",\n'
        '  "textures": {\n'
        f'    "layer0": "fortuneandfavors:item/{name}"\n'
        "  }\n"
        "}\n"
    )
    print(f"wrote {name}.png + {name}.json")


def last_remembrance():
    """The Monarch's greatsword: netherite steel, a gold fuller, soul-fire in it."""
    img, d = canvas()

    # Blade, bottom-left to top-right, three pixels wide - heavier than the
    # Distant Memory's two, which is the whole silhouette difference.
    d.line([(3, 13), (14, 2)], fill=STEEL, width=2)
    d.line([(2, 13), (13, 2)], fill=STEEL_DARK, width=1)
    d.line([(4, 13), (15, 2)], fill=STEEL_LIGHT, width=1)

    # The gold fuller down the spine of the blade, with soul-fire where it runs.
    d.line([(4, 12), (13, 3)], fill=GOLD, width=1)
    for step in range(3):
        d.point((6 + step * 2, 10 - step * 2), fill=ECHO)
        d.point((7 + step * 2, 11 - step * 2), fill=ECHO_DARK)
    d.point((12, 4), fill=ECHO)

    # Crossguard: gold, swept, with the crown's own colour at the tips.
    d.line([(1, 13), (6, 8)], fill=GOLD, width=2)
    d.point((0, 14), fill=GOLD_DARK)
    d.point((7, 7), fill=GOLD)

    # Grip and the crown pommel.
    d.line([(2, 15), (5, 12)], fill=HANDLE, width=2)
    d.point((1, 15), fill=HANDLE_DARK)
    d.point((3, 14), fill=GOLD_DARK)
    d.point((2, 13), fill=GOLD)

    save(img, "last_remembrance")


def main():
    last_remembrance()


if __name__ == "__main__":
    main()
