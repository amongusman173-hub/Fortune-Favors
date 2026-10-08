#!/usr/bin/env python3
"""Generates the item art for the two newest bosses.

This covers the Drowned Sovereign and the Gale Warden: 10 textures and the 10
matching `models/item/*.json` files. Like the other generators it is checked in,
so the art is reproducible and reviewable rather than a binary blob with no
provenance.

Each item also needs a dispatch entry in `tools/make_item_definitions.py`; this
script only draws the pixels and writes the models.

Run:  python3 tools/make_sea_and_sky_textures.py
"""
import json
import math
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

CLEAR = (0, 0, 0, 0)
BLACK = (10, 18, 26, 255)

# ---------------------------------------------------------------- the deep: the Drowned Sovereign
ABYSS = (14, 56, 88, 255)
ABYSS_DARK = (5, 26, 46, 255)
ABYSS_DEEP = (2, 14, 28, 255)
ABYSS_LIGHT = (34, 104, 146, 255)
TIDE = (62, 176, 196, 255)
TIDE_LIGHT = (146, 232, 236, 255)
GLOW_TEAL = (110, 255, 234, 255)
BONE = (206, 232, 224, 255)
BONE_DARK = (150, 184, 178, 255)
PEARL = (232, 248, 244, 255)
KELP = (44, 128, 96, 255)
GOLD = (232, 190, 74, 255)
GOLD_DARK = (156, 116, 30, 255)

# ---------------------------------------------------------------- the sky: the Gale Warden
SKY = (228, 240, 248, 255)
SKY_LIGHT = (252, 255, 255, 255)
SKY_DARK = (152, 176, 196, 255)
SKY_DEEP = (104, 128, 152, 255)
CYAN = (140, 226, 240, 255)
CYAN_DARK = (56, 142, 172, 255)
CYAN_DEEP = (30, 92, 118, 255)
STORM = (78, 96, 118, 255)
WIND = (198, 246, 255, 255)

# Items rendered as held weapons rather than flat icons.
HANDHELD = {
    "leviathans_grasp",
    "tidecaller",
    "abyssal_chain",
    "skybreaker",
}


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
    """The shared loot-box silhouette, so every boss box reads as a box."""
    d.rectangle([1, 2, 14, 7], fill=lid, outline=edge)
    d.rectangle([1, 7, 14, 14], fill=body, outline=edge)
    d.line([1, 7, 14, 7], fill=edge)
    d.line([4, 2, 4, 14], fill=band)
    d.line([11, 2, 11, 14], fill=band)
    d.rectangle([7, 7, 8, 10], fill=latch, outline=edge)


def plate(d, light, dark, edge=BLACK, trim=None):
    """The shared chestplate silhouette."""
    d.rectangle([3, 2, 12, 13], fill=light, outline=edge)
    d.rectangle([2, 2, 3, 7], fill=dark, outline=edge)
    d.rectangle([12, 2, 13, 7], fill=dark, outline=edge)
    d.line([3, 5, 12, 5], fill=dark)
    d.line([5, 6, 5, 13], fill=dark)
    d.line([10, 6, 10, 13], fill=dark)
    if trim:
        d.line([3, 12, 12, 12], fill=trim)


def swirl(d, cx, cy, radius, color, turns=1.6, points=26):
    """A spiral - the wind motif, on every piece of the sky set."""
    for i in range(points):
        t = i / (points - 1.0)
        a = t * math.pi * 2.0 * turns
        r = radius * (0.25 + 0.75 * t)
        x = cx + math.cos(a) * r
        y = cy + math.sin(a) * r
        d.point([int(round(x)), int(round(y))], fill=color)


def wave(d, x0, y0, x1, y1, color, crest=None, height=1):
    """A rolling wave between two x positions, cresting upward."""
    steps = max(2, x1 - x0)
    for i in range(steps + 1):
        t = i / steps
        x = x0 + t * (x1 - x0)
        y = y0 + (y1 - y0) * t - math.sin(t * math.pi) * height
        yy = int(round(y))
        d.point([int(round(x)), yy], fill=color)
        if crest:
            d.point([int(round(x)), yy + 1], fill=crest)


# ------------------------------------------------------------------ the Drowned Sovereign


def leviathans_grasp():
    """His claw: a closing gauntlet of abyssal plate with three talons and a teal seam."""
    img, d = canvas()
    # The wrist and palm, held at the lower left.
    d.polygon([(1, 14), (5, 15), (7, 11), (3, 9)], fill=ABYSS, outline=ABYSS_DEEP)
    d.polygon([(3, 12), (6, 13), (7, 11), (4, 10)], fill=ABYSS_LIGHT)
    d.line([2, 14, 6, 11], fill=ABYSS_DEEP)
    # The knuckles, and the seam that glows between them.
    d.rectangle([6, 6, 9, 12], fill=ABYSS, outline=ABYSS_DEEP)
    d.point([8, 7], fill=GLOW_TEAL)
    d.point([7, 10], fill=GLOW_TEAL)
    # Three talons closing to the upper right.
    for i, (bx, by, tx, ty) in enumerate(((7, 7, 12, 2), (8, 6, 14, 4), (8, 9, 13, 8))):
        d.line([bx, by, tx, ty], fill=BONE, width=1)
        d.line([bx + 1, by, tx, ty], fill=BONE_DARK, width=1)
        d.point([tx, ty], fill=PEARL)
    # Water running off the knuckles.
    d.point([5, 8], fill=TIDE)
    d.point([9, 13], fill=TIDE_LIGHT)
    d.point([10, 14], fill=TIDE)
    save(img, "leviathans_grasp")


def tidecaller():
    """His trident: an abyssal shaft with three prongs and a wave breaking off the middle."""
    img, d = canvas()
    # Shaft.
    d.line([4, 14, 10, 6], fill=ABYSS, width=2)
    d.line([5, 14, 10, 7], fill=ABYSS_LIGHT, width=1)
    d.line([4, 12, 5, 11], fill=GOLD_DARK)
    d.line([5, 13, 6, 12], fill=GOLD)
    # Head: a crossbar and three prongs.
    d.line([6, 6, 12, 4], fill=ABYSS_DARK)
    for (x0, y0, x1, y1) in ((6, 6, 5, 1), (9, 5, 10, 1), (12, 4, 14, 2)):
        d.line([x0, y0, x1, y1], fill=BONE)
        d.point([x1, y1], fill=PEARL)
    # The wave it is named for, breaking along the left prong.
    wave(d, 3, 10, 8, 5, TIDE, TIDE_LIGHT, height=2)
    d.point([7, 4], fill=GLOW_TEAL)
    d.point([8, 3], fill=GLOW_TEAL)
    d.point([3, 11], fill=TIDE_LIGHT)
    save(img, "tidecaller")


def abyssal_chain():
    """His chain: dark links running corner to corner, ending in a barbed hook."""
    img, d = canvas()
    # Links, drawn as alternating rings so the chain reads as one even at 16px.
    links = [(2, 14), (4, 12), (6, 11), (8, 9), (10, 7), (12, 5)]
    for i, (x, y) in enumerate(links):
        fill = ABYSS_LIGHT if i % 2 == 0 else ABYSS
        d.ellipse([x - 2, y - 2, x + 1, y + 1], outline=ABYSS_DEEP, fill=fill)
        d.point([x - 1, y], fill=ABYSS_DEEP)
    # The hook, barbs and all.
    d.arc([9, 1, 15, 7], 180, 360, fill=BONE)
    d.line([9, 4, 9, 6], fill=BONE)
    d.line([15, 4, 15, 6], fill=BONE)
    d.point([9, 2], fill=PEARL)
    d.point([12, 1], fill=PEARL)
    d.point([15, 7], fill=BONE_DARK)
    # One glowing mark, so the Depth Mark reads as part of the weapon.
    d.point([6, 9], fill=GLOW_TEAL)
    d.point([11, 4], fill=GLOW_TEAL)
    save(img, "abyssal_chain")


def sovereigns_heart():
    """His summon: a heart out of the deep, veined and still beating."""
    img, d = canvas()
    d.polygon(
        [(3, 4), (8, 2), (13, 4), (12, 8), (8, 15), (4, 8)],
        fill=ABYSS,
        outline=ABYSS_DEEP,
    )
    d.polygon([(5, 4), (8, 3), (11, 4), (10, 7), (8, 12), (6, 7)], fill=ABYSS_LIGHT)
    # Veins.
    d.line([8, 3, 8, 6], fill=GLOW_TEAL)
    d.line([8, 6, 5, 10], fill=GLOW_TEAL)
    d.line([8, 6, 11, 10], fill=GLOW_TEAL)
    d.line([8, 6, 8, 13], fill=GLOW_TEAL)
    d.point([8, 6], fill=PEARL)
    # The pulse.
    d.point([6, 5], fill=TIDE_LIGHT)
    d.point([10, 5], fill=TIDE_LIGHT)
    d.point([8, 8], fill=GOLD)
    save(img, "sovereigns_heart")


def drowned_loot_box():
    """His box: abyssal plate, tide-brass bands and a conch for a latch."""
    img, d = canvas()
    chest(d, ABYSS_DARK, ABYSS, GOLD_DARK, GOLD, ABYSS_DEEP)
    # A conch, sitting where the latch would be.
    d.arc([6, 8, 10, 12], 270, 90, fill=GOLD)
    d.line([8, 9, 8, 12], fill=GOLD_DARK)
    d.point([9, 10], fill=PEARL)
    # Water along the lid.
    wave(d, 2, 5, 13, 5, TIDE, TIDE_LIGHT, height=1)
    d.point([3, 3], fill=GLOW_TEAL)
    d.point([12, 3], fill=GLOW_TEAL)
    save(img, "drowned_loot_box")


# ------------------------------------------------------------------ the Gale Warden


def skybreaker():
    """His greatsword: a heavy blade with the wind caught along its edge."""
    img, d = canvas()
    # Grip and guard.
    d.line([2, 14, 4, 12], fill=STORM)
    d.line([3, 13, 5, 11], fill=SKY_DEEP)
    d.line([4, 9, 8, 13], fill=CYAN_DARK)
    d.line([5, 10, 9, 14], fill=CYAN_DEEP)
    # The blade, wide and heavy, running to the upper right corner.
    d.polygon([(9, 1), (15, 7), (8, 14), (4, 10)], fill=SKY, outline=STORM)
    d.polygon([(9, 2), (13, 6), (8, 11), (6, 9)], fill=SKY_LIGHT)
    d.line([9, 2, 13, 6], fill=WIND)
    d.line([5, 10, 7, 12], fill=SKY_DARK)
    # The wind it amplifies, curled along the flat.
    swirl(d, 8, 7, 3.2, CYAN, turns=1.3, points=18)
    d.point([12, 4], fill=WIND)
    d.point([10, 6], fill=CYAN)
    save(img, "skybreaker")


def gale_chakram():
    """His ring: a spinning hoop of compressed wind with three curved blades."""
    img, d = canvas()
    d.ellipse([2, 2, 13, 13], outline=SKY_DEEP, fill=None)
    d.ellipse([3, 3, 12, 12], outline=SKY, fill=None)
    d.ellipse([4, 4, 11, 11], outline=CYAN)
    # Three blades off the rim, each swept the same way.
    for a in (math.radians(20), math.radians(140), math.radians(260)):
        x0 = 7.5 + math.cos(a) * 5.0
        y0 = 7.5 + math.sin(a) * 5.0
        x1 = 7.5 + math.cos(a + 0.9) * 7.6
        y1 = 7.5 + math.sin(a + 0.9) * 7.6
        d.polygon(
            [
                (int(round(x0)), int(round(y0))),
                (int(round(x1)), int(round(y1))),
                (int(round(x0 + math.sin(a) * 1.6)), int(round(y0 - math.cos(a) * 1.6))),
            ],
            fill=SKY_LIGHT,
            outline=CYAN_DEEP,
        )
    # The eye of the storm.
    d.point([7, 7], fill=CYAN_DARK)
    d.point([8, 8], fill=CYAN)
    d.point([7, 8], fill=CYAN)
    d.point([8, 7], fill=CYAN)
    save(img, "gale_chakram")


def wardens_mantle():
    """His chestplate: sky-bright plate with a wind scythe across the breast."""
    img, d = canvas()
    plate(d, SKY, SKY_DARK, STORM, trim=CYAN)
    # The shoulder plates, swept back like feathers.
    d.polygon([(2, 2), (5, 1), (4, 5)], fill=SKY_LIGHT, outline=STORM)
    d.polygon([(13, 2), (10, 1), (11, 5)], fill=SKY_LIGHT, outline=STORM)
    # The swirl on the chest - the one marking that says what it does.
    swirl(d, 7.5, 8.5, 3.4, CYAN_DARK, turns=1.5, points=20)
    swirl(d, 7.5, 8.5, 2.0, WIND, turns=1.5, points=12)
    d.point([8, 8], fill=CYAN)
    d.point([4, 10], fill=SKY_LIGHT)
    d.point([11, 11], fill=SKY_LIGHT)
    save(img, "wardens_mantle")


def gale_sigil():
    """His summon: a windbound sigil, rings turning on a pale shell."""
    img, d = canvas()
    d.ellipse([2, 2, 13, 13], fill=SKY_LIGHT, outline=SKY_DEEP)
    d.ellipse([3, 3, 12, 12], fill=CYAN, outline=SKY_DEEP)
    d.ellipse([5, 5, 10, 10], fill=SKY, outline=CYAN_DEEP)
    # Counter-rotating rings, so it does not look like a coin.
    swirl(d, 7.5, 7.5, 4.6, CYAN_DEEP, turns=1.4, points=22)
    swirl(d, 7.5, 7.5, 2.4, WIND, turns=-1.4, points=14)
    d.point([7, 7], fill=CYAN_DARK)
    d.point([8, 8], fill=CYAN_DARK)
    d.point([4, 4], fill=SKY_LIGHT)
    d.point([11, 11], fill=SKY_LIGHT)
    save(img, "gale_sigil")


def gale_loot_box():
    """His box: sky-pale with whirlwind bands."""
    img, d = canvas()
    chest(d, SKY_DARK, SKY, CYAN_DARK, CYAN, STORM)
    # The latch is a swirl, which no other box's is.
    swirl(d, 7.5, 9.0, 2.6, CYAN_DEEP, turns=1.4, points=16)
    d.point([7, 9], fill=CYAN)
    d.point([8, 9], fill=SKY_LIGHT)
    # A gust across the lid.
    swirl(d, 7.5, 4.5, 3.4, WIND, turns=1.2, points=18)
    save(img, "gale_loot_box")


# ------------------------------------------------------------------ the two forge materials
#
# These are what the two sets are upgraded with, so they are drawn as treasures rather than as
# parts: a thing a boss would keep, not a thing that came off it.


def abyssal_pearl():
    """His material: a pale pearl held in a ring of abyssal plate, with a teal rim of light."""
    img, d = canvas()
    # The setting: an incomplete ring, so the pearl reads as held rather than painted on.
    d.ellipse([1, 1, 14, 14], fill=ABYSS_DARK, outline=ABYSS_DEEP)
    d.ellipse([2, 2, 13, 13], fill=ABYSS, outline=ABYSS_DEEP)
    d.arc([1, 1, 14, 14], start=200, end=340, fill=ABYSS_LIGHT)
    d.arc([1, 1, 14, 14], start=20, end=160, fill=ABYSS_LIGHT)
    # The pearl itself: opaque centre, one lit side and a cool shadow under it.
    d.ellipse([4, 4, 11, 11], fill=PEARL, outline=TIDE)
    d.ellipse([5, 5, 8, 8], fill=(255, 255, 255, 255))
    d.arc([4, 4, 11, 11], start=20, end=170, fill=TIDE)
    d.arc([4, 5, 11, 12], start=200, end=340, fill=TIDE_LIGHT)
    # Four points of light on the ring, so it glows in the hand.
    for x, y in ((2, 7), (13, 8), (7, 2), (8, 13)):
        d.point([x, y], fill=GLOW_TEAL)
    save(img, "abyssal_pearl")


def gale_core():
    """His material: a shard of still air, with the wind it is holding turning inside it."""
    img, d = canvas()
    # A standing shard rather than a sphere - the Warden's motif is a spiral, not a ball.
    d.polygon([(7, 0), (13, 6), (10, 15), (5, 15), (2, 6)], fill=SKY_DARK, outline=STORM)
    d.polygon([(7, 1), (12, 6), (9, 14), (6, 14), (3, 6)], fill=SKY, outline=SKY_DEEP)
    # The one edge the light comes in on, kept clear of the spiral.
    d.line([7, 2, 7, 13], fill=SKY_LIGHT)
    # The wind turning inside it: two half-turns, offset, so it reads as motion.
    swirl(d, 7.0, 7.0, 4.2, CYAN_DEEP, turns=1.1, points=20)
    swirl(d, 7.5, 8.0, 2.3, WIND, turns=-1.2, points=14)
    d.point([7, 7], fill=CYAN)
    d.point([8, 8], fill=CYAN)
    # Sparks off the crown, where the shard is thinnest.
    d.point([7, 0], fill=WIND)
    d.point([6, 1], fill=CYAN)
    d.point([8, 1], fill=CYAN)
    save(img, "gale_core")


if __name__ == "__main__":
    # Every icon this script drew has been redrawn by tools/make_reworked_textures_b.py; the
    # functions above stay for reference, and running this writes nothing.
    # leviathans_grasp() - drawn by tools/make_reworked_textures_b.py now
    # tidecaller() - drawn by tools/make_reworked_textures_b.py now
    # abyssal_chain() - drawn by tools/make_reworked_textures_b.py now
    # sovereigns_heart() - drawn by tools/make_reworked_textures_b.py now
    # drowned_loot_box() - drawn by tools/make_reworked_textures_b.py now
    # abyssal_pearl() - drawn by tools/make_reworked_textures_b.py now

    # skybreaker() - drawn by tools/make_reworked_textures_b.py now
    # gale_chakram() - drawn by tools/make_reworked_textures_b.py now
    # wardens_mantle() - drawn by tools/make_reworked_textures_b.py now
    # gale_sigil() - drawn by tools/make_reworked_textures_b.py now
    # gale_loot_box() - drawn by tools/make_reworked_textures_b.py now
    # gale_core() - drawn by tools/make_reworked_textures_b.py now
    pass
