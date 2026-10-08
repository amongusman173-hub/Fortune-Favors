#!/usr/bin/env python3
"""Draws the item art that was never made.

Six custom items have had a `custom_model_data` code for a while and no art to
go with it, so they rendered as the plain vanilla item they are built on:

    4550024  Mindbinder Shroud    (netherite_chestplate)
    4550033  Raid Banner          (banner)
    4550041  Bounty Compass       (compass)
    4550042  Death Compass        (compass)
    4550060  Distant Memory Shard (echo_shard)
    4550061  Distant Memory Sword (diamond_sword)
    4550241  Clockwork Trophy     (gold_block)   - art existed, entry was missing

The Clockwork Trophy already had a texture and model on disk; only its
`assets/minecraft/items/gold_block.json` entry was missing (see
`tools/make_item_definitions.py`). The other five had nothing at all, which is
why they looked broken: a Distant Memory Sword was a plain diamond sword and a
Bounty Compass was a plain compass.

This generator writes those five textures and their models, in the same palette
and style as the rest of the pack. It is checked in so the art is reproducible
and reviewable rather than a one-off binary.

Run:  python3 tools/make_missing_item_textures.py
"""
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

CLEAR = (0, 0, 0, 0)

# Shared palette, pulled from the pack's other items so the set reads as one mod.
BLADE = (206, 214, 226, 255)
BLADE_DARK = (118, 128, 146, 255)
ECHO = (92, 226, 200, 255)
ECHO_DARK = (28, 96, 100, 255)
ECHO_LIGHT = (188, 252, 236, 255)
GOLD = (238, 196, 76, 255)
GOLD_DARK = (140, 104, 24, 255)
HANDLE = (96, 66, 40, 255)
HANDLE_DARK = (52, 36, 22, 255)
CLOTH = (58, 46, 84, 255)
CLOTH_DARK = (30, 22, 46, 255)
MIND = (176, 92, 226, 255)
MIND_LIGHT = (232, 190, 255, 255)
WOOD = (128, 92, 56, 255)
FACE = (26, 24, 32, 255)


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


def distant_memory_sword():
    """The Distant Memory: a diamond sword's silhouette with an echo core."""
    img, d = canvas()
    # Blade, bottom-left to top-right, two pixels wide with a darker edge.
    d.line([(3, 12), (13, 2)], fill=BLADE, width=2)
    d.line([(4, 13), (14, 3)], fill=BLADE_DARK, width=1)
    # The echo running down the fuller.
    for step in range(3):
        d.point((6 + step, 9 - step), fill=ECHO)
        d.point((7 + step, 10 - step), fill=ECHO_DARK)
    d.point((5, 10), fill=ECHO_LIGHT)
    d.point((4, 11), fill=ECHO)
    # Crossguard, then the grip.
    d.line([(1, 12), (5, 8)], fill=GOLD, width=1)
    d.point((0, 13), fill=GOLD_DARK)
    d.line([(2, 14), (4, 12)], fill=HANDLE, width=2)
    d.point((1, 15), fill=HANDLE_DARK)
    d.point((3, 15), fill=HANDLE_DARK)
    save(img, "distant_memory_sword")


def distant_memory_shard():
    """A shard of the same memory: a faceted echo crystal."""
    img, d = canvas()
    d.polygon([(8, 1), (12, 6), (11, 13), (5, 13), (4, 6)], fill=ECHO, outline=ECHO_DARK)
    d.polygon([(8, 2), (10, 6), (9, 12), (7, 12), (6, 6)], fill=ECHO_LIGHT)
    d.line([(8, 2), (8, 12)], fill=ECHO)
    d.line([(5, 6), (11, 6)], fill=ECHO_DARK)
    d.point((6, 4), fill=ECHO_LIGHT)
    d.point((10, 9), fill=ECHO_LIGHT)
    save(img, "distant_memory_shard")


def mindbinder_shroud():
    """The Mindbinder's cloak: an indigo mantle with a watching sigil."""
    img, d = canvas()
    d.polygon([(2, 3), (13, 3), (14, 7), (11, 14), (4, 14), (1, 7)], fill=CLOTH)
    # Shoulders and collar.
    d.line([(2, 3), (6, 2)], fill=CLOTH_DARK)
    d.line([(13, 3), (9, 2)], fill=CLOTH_DARK)
    d.polygon([(5, 3), (10, 3), (9, 6), (6, 6)], fill=CLOTH_DARK)
    # Gold trim along the hem, and the eye at the chest.
    d.line([(3, 13), (12, 13)], fill=GOLD_DARK)
    d.polygon([(5, 8), (10, 8), (7, 11)], fill=MIND)
    d.point((7, 9), fill=MIND_LIGHT)
    d.point((8, 9), fill=MIND_LIGHT)
    d.point((4, 5), fill=MIND)
    d.point((11, 5), fill=MIND)
    save(img, "mindbinder_shroud")


def raid_banner():
    """A raid banner on its pole, cloth hanging to the right."""
    img, d = canvas()
    d.line([(3, 1), (3, 14)], fill=WOOD)
    d.point((3, 15), fill=HANDLE_DARK)
    d.polygon([(4, 2), (13, 2), (13, 12), (4, 12)], fill=CLOTH)
    d.line([(4, 2), (4, 12)], fill=CLOTH_DARK)
    d.line([(13, 2), (13, 12)], fill=CLOTH_DARK)
    # The raid mark: a gold cross with a mind-eye at its centre.
    d.line([(8, 3), (8, 11)], fill=GOLD)
    d.line([(5, 7), (12, 7)], fill=GOLD)
    d.polygon([(7, 6), (10, 6), (8, 9)], fill=MIND)
    d.point((8, 7), fill=MIND_LIGHT)
    d.line([(4, 13), (10, 13)], fill=GOLD_DARK)
    save(img, "raid_banner")


def bounty_compass():
    """A bounty hunter's compass: brass, with a needle that always points to the mark."""
    img, d = canvas()
    d.ellipse([(2, 2), (13, 13)], fill=FACE, outline=GOLD, width=2)
    d.ellipse([(3, 3), (12, 12)], outline=GOLD_DARK)
    # Cardinal ticks.
    d.point((7, 3), fill=GOLD)
    d.point((8, 3), fill=GOLD)
    d.point((7, 12), fill=GOLD)
    d.point((8, 12), fill=GOLD)
    d.point((3, 7), fill=GOLD)
    d.point((3, 8), fill=GOLD)
    d.point((12, 7), fill=GOLD)
    d.point((12, 8), fill=GOLD)
    # Needle: gold toward the mark, dark behind it.
    d.polygon([(8, 8), (11, 4), (9, 8)], fill=GOLD)
    d.polygon([(8, 8), (4, 11), (7, 8)], fill=GOLD_DARK)
    d.point((8, 8), fill=(226, 74, 58, 255))
    save(img, "bounty_compass")


def death_compass():
    """The Death Compass: slate and soul-fire, the needle a thread to your grave.

    It is the second compass in the set, so it is deliberately nothing like the
    Bounty Compass in silhouette or colour - brass against slate, a gold needle
    against a soul-fire one - because two compasses that share a base item are
    exactly what a player has to be able to tell apart at a glance.
    """
    img, d = canvas()
    # Slate case, soul-fire rim: the inverse of the Bounty Compass's brass-on-dark.
    d.ellipse([(2, 2), (13, 13)], fill=FACE, outline=BLADE_DARK, width=2)
    d.ellipse([(3, 3), (12, 12)], outline=ECHO_DARK)
    # Cardinal ticks, bone-white rather than gold.
    for x, y in ((7, 3), (8, 3), (7, 12), (8, 12), (3, 7), (3, 8), (12, 7), (12, 8)):
        d.point((x, y), fill=BLADE_DARK)
    # The thread toward the grave: soul-fire ahead, dark behind, a red pin at the
    # pivot (the last breath) exactly where the Bounty Compass wears one.
    d.polygon([(8, 8), (11, 4), (9, 8)], fill=ECHO)
    d.polygon([(8, 8), (4, 11), (7, 8)], fill=ECHO_DARK)
    d.point((7, 7), fill=(226, 74, 58, 255))
    d.point((8, 8), fill=(226, 74, 58, 255))
    save(img, "death_compass")


def main():
    distant_memory_sword()
    distant_memory_shard()
    mindbinder_shroud()
    # raid_banner() - drawn by tools/make_reworked_textures_a.py now
    bounty_compass()
    death_compass()


if __name__ == "__main__":
    main()
