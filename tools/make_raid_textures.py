#!/usr/bin/env python3
"""Regenerates the 16x16 art for the Raid Warlord's set.

The raid set is the oldest hand-made art in the pack and it shows: flat fills,
no outline discipline, and enough shared silhouette that the four drops read as
the same item at a glance in a hotbar. This redraws them with a single palette,
a real light direction (upper-left), and one unmistakable silhouette each.

Deliberately NOT touched: the Warlord's Axe and the Captain's Horn. Both are
already recognisable in the hand, and the axe ships a separate held model whose
art is keyed to its blade geometry - redrawing it would mean redrawing that too
for no gain.

Run:  python3 tools/make_raid_textures.py
"""
from PIL import Image, ImageDraw

OUT = "resourcepack/assets/fortuneandfavors/textures/item"

# --- one palette for the whole set -----------------------------------------
INK = (16, 12, 14, 255)
IRON_D = (54, 58, 66, 255)
IRON = (96, 102, 114, 255)
IRON_H = (150, 158, 172, 255)
GOLD_D = (128, 92, 26, 255)
GOLD = (214, 168, 60, 255)
GOLD_H = (255, 228, 146, 255)
WOOD_D = (44, 28, 18, 255)
WOOD = (74, 48, 28, 255)
WOOD_H = (108, 72, 42, 255)
BONE_D = (158, 148, 130, 255)
BONE = (214, 206, 188, 255)
BONE_H = (246, 242, 230, 255)
BLOOD_D = (92, 10, 22, 255)
BLOOD = (154, 24, 36, 255)
BLOOD_H = (206, 56, 66, 255)
EMBER_D = (168, 74, 12, 255)
EMBER = (244, 138, 32, 255)
EMBER_H = (255, 224, 168, 255)


def canvas(w=16, h=16):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def save(img, name):
    path = f"{OUT}/{name}.png"
    img.save(path)
    print("wrote", path)


def rect(d, x0, y0, x1, y1, fill, outline=None):
    """Filled rectangle. The outline is drawn as four 1px edges, never as a
    PIL rectangle outline - a 2px-tall PIL outline is nothing but outline, which
    is how the first draft of the trophy stand turned into two black lines."""
    d.rectangle([x0, y0, x1, y1], fill=fill)
    if outline is not None:
        d.line([x0, y0, x1, y0], fill=outline)
        d.line([x0, y1, x1, y1], fill=outline)
        d.line([x0, y0, x0, y1], fill=outline)
        d.line([x1, y0, x1, y1], fill=outline)


def raid_loot_box():
    """An iron-banded war chest with the Warlord's mark on the lid."""
    img, d = canvas()

    # lid, then body, with the lid overhanging by a pixel on each side
    rect(d, 1, 2, 14, 6, WOOD, INK)
    rect(d, 1, 6, 14, 14, WOOD, INK)

    # light from the upper left, shadow down the right and along the base
    d.line([2, 3, 13, 3], fill=WOOD_H)
    d.line([2, 4, 2, 13], fill=WOOD_H)
    d.line([13, 8, 13, 13], fill=WOOD_D)
    d.line([2, 13, 13, 13], fill=WOOD_D)

    # lid seam in gold, standing proud of the wood
    d.line([1, 6, 14, 6], fill=GOLD_D)
    d.line([1, 5, 14, 5], fill=GOLD)

    # iron bands, painted over lid and body alike
    for x0 in (3, 11):
        rect(d, x0, 2, x0 + 1, 14, IRON)
        d.line([x0, 3, x0, 13], fill=IRON_H)
        d.line([x0 + 1, 3, x0 + 1, 13], fill=IRON_D)
        d.point([x0, 4], fill=GOLD)
        d.point([x0 + 1, 9], fill=GOLD)
        # keep the box outlined after the band runs over it
        d.point([x0, 2], fill=IRON_H)
        d.point([x0 + 1, 14], fill=IRON_D)
    d.point([3, 2], fill=IRON_H)
    d.point([12, 2], fill=IRON_H)

    # the mark: a crimson banner over two crossed axe hafts
    d.line([6, 4, 9, 4], fill=BLOOD)
    d.line([6, 3, 9, 3], fill=BLOOD_H)
    d.line([5, 4, 5, 6], fill=BLOOD_D)
    d.line([10, 4, 10, 6], fill=BLOOD_D)
    d.line([7, 5, 7, 7], fill=BLOOD_D)
    d.line([8, 5, 8, 7], fill=BLOOD_D)
    d.point([6, 5], fill=BONE_D)
    d.point([9, 5], fill=BONE_D)

    # gold latch with a blood gem
    rect(d, 7, 7, 8, 10, GOLD, GOLD_D)
    d.point([7, 8], fill=GOLD_H)
    d.point([7, 9], fill=BLOOD)
    d.point([8, 9], fill=BLOOD_H)
    save(img, "raid_loot_box")


def warlord_trophy():
    """A horned golden helm on a stand - the bragging-rights drop."""
    img, d = canvas()

    # horns first, so the helm overlaps their bases
    d.polygon([(1, 3), (4, 2), (5, 5), (3, 6)], fill=BONE, outline=INK)
    d.polygon([(14, 3), (11, 2), (10, 5), (12, 6)], fill=BONE, outline=INK)
    d.line([2, 3, 3, 6], fill=BONE_H)
    d.line([13, 3, 12, 6], fill=BONE_H)
    d.point([1, 3], fill=BONE_D)
    d.point([14, 3], fill=BONE_D)

    # helm dome, lit from the upper left
    d.ellipse([3, 1, 12, 10], fill=GOLD, outline=INK)
    d.line([5, 2, 8, 2], fill=GOLD_H)
    d.line([4, 4, 4, 6], fill=GOLD_H)
    d.line([10, 3, 11, 6], fill=GOLD_D)
    d.line([6, 9, 11, 8], fill=GOLD_D)

    # crest
    rect(d, 7, 0, 8, 3, BLOOD, INK)
    d.point([7, 1], fill=BLOOD_H)

    # brow band and eye slits
    d.line([4, 5, 11, 5], fill=GOLD_D)
    d.point([5, 6], fill=INK)
    d.point([6, 6], fill=INK)
    d.point([9, 6], fill=INK)
    d.point([10, 6], fill=INK)

    # cheek guards down the sides
    d.line([4, 7, 4, 8], fill=GOLD_D)
    d.line([11, 7, 11, 8], fill=GOLD_D)

    # Stand. The neck is drawn WITHOUT an outline: a two-pixel-wide outlined
    # rectangle has no fill left once the four edges are painted, which is how
    # the first draft produced a helm floating over an invisible post.
    rect(d, 7, 9, 8, 11, WOOD)
    d.point([7, 9], fill=WOOD_D)
    d.point([8, 11], fill=WOOD_H)
    rect(d, 4, 11, 11, 14, WOOD, INK)
    d.line([5, 12, 10, 12], fill=WOOD_H)
    d.line([5, 13, 10, 13], fill=WOOD_D)
    save(img, "warlord_trophy")


def warlord_cloak():
    """A crimson war-cloak: gold pauldrons up top, fur at the hem."""
    img, d = canvas()

    # the cloth, flaring out towards the hem
    d.polygon([(6, 2), (9, 2), (11, 8), (13, 14), (2, 14), (4, 8)], fill=BLOOD, outline=INK)
    d.polygon([(7, 3), (8, 3), (10, 8), (11, 13), (4, 13), (5, 8)], fill=BLOOD_H)
    # folds
    d.line([7, 4, 7, 12], fill=BLOOD_D)
    d.line([8, 4, 8, 12], fill=BLOOD_D)
    d.line([5, 9, 4, 13], fill=BLOOD_D)
    d.line([10, 9, 11, 13], fill=BLOOD_D)

    # fur hem
    d.line([4, 13, 11, 13], fill=BONE)
    d.line([3, 14, 12, 14], fill=BONE_D)
    d.point([5, 13], fill=BONE_H)
    d.point([7, 13], fill=BONE_H)
    d.point([10, 13], fill=BONE_H)

    # gold pauldrons
    d.polygon([(1, 3), (5, 2), (5, 6), (1, 6)], fill=GOLD, outline=INK)
    d.polygon([(14, 3), (10, 2), (10, 6), (14, 6)], fill=GOLD, outline=INK)
    d.line([2, 3, 4, 3], fill=GOLD_H)
    d.line([13, 3, 11, 3], fill=GOLD_H)
    d.line([2, 6, 4, 6], fill=GOLD_D)
    d.line([13, 6, 11, 6], fill=GOLD_D)

    # collar clasp
    rect(d, 7, 1, 8, 3, GOLD, GOLD_D)
    d.point([7, 2], fill=GOLD_H)
    save(img, "warlord_cloak")


def raiders_upgrader():
    """A rune tablet: the Raider's forge mark, burning to be used."""
    img, d = canvas()

    # stone plate with a bevelled edge
    rect(d, 2, 1, 13, 14, IRON_D, INK)
    rect(d, 3, 2, 12, 13, (72, 76, 86, 255))
    d.line([3, 2, 12, 2], fill=IRON_H)
    d.line([3, 3, 3, 12], fill=IRON_H)
    d.line([12, 3, 12, 12], fill=IRON_D)
    d.line([4, 13, 11, 13], fill=IRON_D)

    # gold corner studs
    for x, y in ((4, 3), (11, 3), (4, 12), (11, 12)):
        d.point([x, y], fill=GOLD)
        d.point([x, min(15, y + 1)], fill=GOLD_D)

    # the sigil: one bold burning chevron pointing up, with a bright core
    d.line([5, 8, 7, 5], fill=EMBER)
    d.line([6, 8, 8, 5], fill=EMBER)
    d.line([10, 8, 8, 5], fill=EMBER)
    d.line([9, 8, 7, 5], fill=EMBER)
    d.line([6, 7, 7, 6], fill=EMBER_H)
    d.point([7, 5], fill=EMBER_H)
    d.point([8, 5], fill=EMBER_H)

    # a smaller second chevron beneath, so it reads as a stacked upgrade
    d.line([6, 11, 8, 9], fill=EMBER_D)
    d.line([9, 11, 8, 9], fill=EMBER_D)
    d.point([8, 9], fill=EMBER)

    # sparks escaping the plate
    d.point([1, 4], fill=EMBER)
    d.point([14, 6], fill=EMBER)
    d.point([14, 11], fill=EMBER_D)
    d.point([1, 11], fill=EMBER_D)
    save(img, "raiders_upgrader")


if __name__ == "__main__":
    raid_loot_box()
    warlord_trophy()
    warlord_cloak()
    raiders_upgrader()
