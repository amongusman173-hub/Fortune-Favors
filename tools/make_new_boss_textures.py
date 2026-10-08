#!/usr/bin/env python3
"""Generates the item art for the four newest raid bosses.

This covers the Clockwork King, the Starbound Magister, the Void Shaper and the
Emerald Sovereign: 28 textures and the 28 matching `models/item/*.json` files.
Like the Scarlet Devil and Time Lord generators it is checked in, so the art is
reproducible and reviewable rather than a binary blob with no provenance.

Each item also needs a dispatch entry in `tools/make_item_definitions.py`; this
script only draws the pixels and writes the models.

Run:  python3 tools/make_new_boss_textures.py
"""
import json
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

# ---------------------------------------------------------------- clockwork palette
BRASS = (196, 148, 58, 255)
BRASS_DARK = (128, 92, 30, 255)
BRASS_LIGHT = (240, 206, 118, 255)
STEEL = (150, 158, 168, 255)
STEEL_DARK = (88, 96, 108, 255)
STEEL_LIGHT = (208, 216, 226, 255)
IRON = (110, 116, 126, 255)
GOLD = (232, 190, 74, 255)
IRON_RED = (170, 60, 44, 255)
GLOW_AMBER = (255, 196, 84, 255)
SMOKE = (60, 58, 62, 255)
BLACK = (26, 24, 28, 255)

# ---------------------------------------------------------------- astral palette
NIGHT = (28, 40, 92, 255)
NIGHT_LIGHT = (54, 78, 156, 255)
STAR_BLUE = (126, 196, 255, 255)
STAR_PALE = (216, 240, 255, 255)
VIOLET = (150, 110, 236, 255)
VIOLET_DARK = (86, 56, 158, 255)

# ---------------------------------------------------------------- void palette
VOID = (58, 30, 96, 255)
VOID_DARK = (34, 16, 60, 255)
VOID_LIGHT = (140, 96, 220, 255)
OBSIDIAN = (38, 26, 54, 255)
DEEPSLATE = (78, 80, 84, 255)
ENDER = (90, 220, 200, 255)

# ---------------------------------------------------------------- court palette
EMERALD = (40, 186, 92, 255)
EMERALD_DARK = (18, 110, 54, 255)
EMERALD_LIGHT = (126, 238, 158, 255)
ROYAL_GOLD = (232, 190, 74, 255)
ROYAL_GOLD_DARK = (156, 116, 30, 255)
PAPER = (232, 226, 206, 255)
PAPER_DARK = (188, 178, 152, 255)
INK = (48, 40, 34, 255)

CLEAR = (0, 0, 0, 0)


def canvas(size=16):
    img = Image.new("RGBA", (size, size), CLEAR)
    return img, ImageDraw.Draw(img)


def save(img, name):
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / f"{name}.png")
    model = {"parent": "minecraft:item/handheld" if name in HANDHELD else "minecraft:item/generated",
             "textures": {"layer0": f"fortuneandfavors:item/{name}"}}
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / f"{name}.json").write_text(json.dumps(model, indent=2) + "\n")
    print("wrote", name)


# Items rendered as held weapons rather than flat icons.
HANDHELD = {
    "clockwork_gauntlet", "starpiercer", "void_reaver",
}


def gear(d, cx, cy, r, body=BRASS, dark=BRASS_DARK, light=BRASS_LIGHT, teeth=8):
    """A gear: a filled disc with square teeth and a darker hub."""
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=body, outline=dark)
    for i in range(teeth):
        a = i * (3.14159265 * 2 / teeth)
        import math
        tx = cx + math.cos(a) * (r + 1.4)
        ty = cy + math.sin(a) * (r + 1.4)
        d.rectangle([tx - 1, ty - 1, tx + 1, ty + 1], fill=body, outline=dark)
    d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=dark)
    d.point([cx - 1, cy - 1], fill=light)


def chest(d, lid, body, band, latch, edge=BLACK):
    """The shared loot-box silhouette, so every boss box reads as a box."""
    d.rectangle([1, 2, 14, 7], fill=lid, outline=edge)
    d.rectangle([1, 7, 14, 14], fill=body, outline=edge)
    d.line([1, 7, 14, 7], fill=edge)
    d.line([4, 2, 4, 14], fill=band)
    d.line([11, 2, 11, 14], fill=band)
    d.rectangle([7, 7, 8, 10], fill=latch, outline=edge)


def sword(d, blade, edge, guard, grip, tip=None):
    """The shared held-weapon silhouette, running lower-left to upper-right."""
    d.line([3, 13, 5, 11], fill=grip)
    d.line([4, 12, 6, 10], fill=grip)
    d.line([5, 10, 7, 12], fill=guard)
    d.line([6, 9, 8, 11], fill=guard)
    d.polygon([(10, 2), (13, 5), (7, 11), (4, 8)], fill=blade, outline=edge)
    d.line([9, 3, 12, 6], fill=tip or edge)


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


def shardfield(d, count, colors):
    """Scatter a few coloured glints - the shared 'magic dust' motif."""
    spots = [(4, 3), (11, 4), (7, 8), (3, 11), (12, 11), (8, 2), (13, 8), (2, 6)]
    for i in range(min(count, len(spots))):
        x, y = spots[i]
        d.point([x, y], fill=colors[i % len(colors)])


# ------------------------------------------------------------------ clockwork king

def clockwork_core():
    """His mainspring: a wound brass gear with a key still in it."""
    img, d = canvas()
    gear(d, 7, 9, 5)
    d.rectangle([6, 6, 8, 8], fill=GLOW_AMBER, outline=BRASS_DARK)
    # the winding key
    d.line([11, 5, 14, 2], fill=STEEL, width=1)
    d.line([13, 1, 14, 2], fill=STEEL_LIGHT)
    d.line([11, 1, 12, 2], fill=STEEL_LIGHT)
    d.point([7, 9], fill=BRASS_LIGHT)
    save(img, "clockwork_core")


def clockwork_trophy():
    """A cog mounted on a plinth: the bragging-rights drop."""
    img, d = canvas()
    d.rectangle([6, 11, 9, 14], fill=BRASS_DARK, outline=BLACK)
    d.rectangle([5, 14, 10, 15], fill=BRASS, outline=BLACK)
    gear(d, 7, 6, 4)
    d.ellipse([5, 4, 9, 8], fill=GLOW_AMBER, outline=BRASS_DARK)
    d.point([6, 5], fill=BRASS_LIGHT)
    d.point([9, 7], fill=BRASS_LIGHT)
    save(img, "clockwork_trophy")


def mech_scrap():
    """A heap of spare plates and bolts - the forge material."""
    img, d = canvas()
    d.polygon([(2, 12), (6, 8), (10, 11), (6, 14)], fill=STEEL, outline=STEEL_DARK)
    d.polygon([(6, 8), (11, 5), (14, 9), (10, 11)], fill=BRASS, outline=BRASS_DARK)
    d.polygon([(9, 3), (13, 3), (13, 6), (10, 6)], fill=STEEL_LIGHT, outline=STEEL_DARK)
    for x, y in ((4, 10), (8, 12), (11, 8)):
        d.point([x, y], fill=BLACK)
    d.point([11, 4], fill=GLOW_AMBER)
    save(img, "mech_scrap")


def clockwork_gauntlet():
    """His stamping arm: a piston-fist built onto a hilt."""
    img, d = canvas()
    # piston fist at the top
    d.rectangle([7, 2, 13, 7], fill=BRASS, outline=BRASS_DARK)
    d.rectangle([8, 3, 12, 4], fill=BRASS_LIGHT)
    d.line([7, 5, 13, 5], fill=STEEL_DARK)
    d.rectangle([9, 1, 11, 2], fill=STEEL, outline=STEEL_DARK)
    # wrist and grip
    d.rectangle([6, 7, 10, 9], fill=STEEL, outline=STEEL_DARK)
    d.line([4, 9, 9, 14], fill=IRON_RED)
    d.line([5, 10, 10, 15], fill=BLACK)
    d.point([12, 3], fill=GLOW_AMBER)
    save(img, "clockwork_gauntlet")


def mechanical_heart():
    """A brass heart with valve-gears for chambers."""
    img, d = canvas()
    d.ellipse([2, 2, 7, 7], fill=BRASS, outline=BRASS_DARK)
    d.ellipse([8, 2, 13, 7], fill=BRASS, outline=BRASS_DARK)
    d.polygon([(2, 5), (13, 5), (8, 14)], fill=BRASS, outline=BRASS_DARK)
    d.polygon([(4, 5), (11, 5), (8, 12)], fill=IRON_RED)
    gear(d, 7, 6, 2, STEEL, STEEL_DARK, STEEL_LIGHT, 6)
    d.point([5, 4], fill=BRASS_LIGHT)
    d.line([8, 10, 8, 13], fill=STEEL_DARK)
    save(img, "mechanical_heart")


def clockwork_loot_box():
    """His box: iron-banded brass with a cog for a latch."""
    img, d = canvas()
    chest(d, BRASS_DARK, BRASS, STEEL_DARK, GLOW_AMBER, BLACK)
    gear(d, 7, 5, 3)
    d.point([10, 4], fill=STEEL_LIGHT)
    save(img, "clockwork_loot_box")


def automaton_armor():
    """His plating, cut down: a brass-and-steel chestplate."""
    img, d = canvas()
    plate(d, BRASS, BRASS_DARK, BLACK, STEEL_DARK)
    d.rectangle([6, 7, 9, 10], fill=STEEL, outline=STEEL_DARK)
    d.point([7, 8], fill=GLOW_AMBER)
    d.point([3, 4], fill=BRASS_LIGHT)
    save(img, "automaton_armor")


# --------------------------------------------------------------- starbound magister

def astral_compass():
    """Her compass: a star where the needle should be."""
    img, d = canvas()
    d.ellipse([1, 1, 14, 14], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.ellipse([2, 2, 13, 13], fill=NIGHT, outline=BLACK)
    d.ellipse([3, 3, 12, 12], fill=NIGHT_LIGHT)
    # a four-point star as the needle
    d.polygon([(7, 3), (9, 7), (13, 8), (9, 9), (7, 13), (6, 9), (2, 8), (6, 7)], fill=STAR_BLUE, outline=NIGHT)
    d.point([7, 7], fill=STAR_PALE)
    d.point([8, 8], fill=STAR_PALE)
    save(img, "astral_compass")


def starbound_trophy():
    """A star in a case, still rotating."""
    img, d = canvas()
    d.ellipse([2, 2, 13, 13], fill=NIGHT, outline=VIOLET_DARK)
    d.ellipse([4, 4, 11, 11], fill=NIGHT_LIGHT)
    d.polygon([(7, 2), (9, 6), (14, 8), (9, 10), (7, 14), (5, 10), (0, 8), (5, 6)], fill=STAR_BLUE, outline=VIOLET_DARK)
    d.polygon([(7, 4), (8, 7), (11, 8), (8, 9), (7, 12), (6, 9), (3, 8), (6, 7)], fill=STAR_PALE)
    d.point([7, 8], fill=(255, 255, 255, 255))
    save(img, "starbound_trophy")


def magical_essence():
    """A cluster of her ceiling, ground down - the forge material."""
    img, d = canvas()
    d.polygon([(8, 1), (13, 7), (8, 14), (3, 7)], fill=VIOLET, outline=VIOLET_DARK)
    d.polygon([(8, 3), (11, 7), (8, 12), (5, 7)], fill=STAR_BLUE)
    d.polygon([(8, 5), (9, 7), (8, 10), (7, 7)], fill=STAR_PALE)
    d.point([12, 3], fill=STAR_BLUE)
    d.point([3, 12], fill=STAR_BLUE)
    d.point([13, 12], fill=STAR_PALE)
    save(img, "magical_essence")


def starpiercer():
    """Her blade: a shard of sky on a hilt."""
    img, d = canvas()
    sword(d, NIGHT_LIGHT, VIOLET_DARK, ROYAL_GOLD, (74, 46, 30, 255), STAR_BLUE)
    d.polygon([(11, 3), (12, 5), (14, 6), (12, 7), (11, 9), (10, 7), (8, 6), (10, 5)], fill=STAR_PALE)
    d.point([11, 6], fill=(255, 255, 255, 255))
    save(img, "starpiercer")


def astral_mantle():
    """Her cloak, cut to fit: a chestplate full of stars."""
    img, d = canvas()
    plate(d, NIGHT_LIGHT, NIGHT, BLACK, VIOLET_DARK)
    shardfield(d, 6, [STAR_PALE, STAR_BLUE, STAR_PALE, VIOLET, STAR_BLUE, STAR_PALE])
    d.line([6, 7, 9, 13], fill=VIOLET_DARK)
    d.point([7, 4], fill=STAR_PALE)
    save(img, "astral_mantle")


def magisters_codex():
    """Her spellbook: night-blue leather, a star sigil, gold clasps."""
    img, d = canvas()
    d.rectangle([2, 1, 13, 14], fill=NIGHT, outline=BLACK)
    d.rectangle([3, 2, 12, 13], fill=NIGHT_LIGHT)
    d.rectangle([2, 1, 4, 14], fill=NIGHT)
    d.line([13, 2, 13, 13], fill=PAPER)
    d.line([12, 3, 12, 12], fill=PAPER_DARK)
    d.rectangle([6, 1, 7, 2], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.rectangle([6, 13, 7, 14], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.polygon([(8, 4), (9, 7), (11, 8), (9, 9), (8, 12), (7, 9), (5, 8), (7, 7)], fill=STAR_PALE)
    d.point([8, 8], fill=STAR_BLUE)
    save(img, "magisters_codex")


def starbound_loot_box():
    """Her box: night-blue with a star for a latch."""
    img, d = canvas()
    chest(d, NIGHT, NIGHT_LIGHT, VIOLET_DARK, STAR_BLUE, BLACK)
    d.polygon([(7, 3), (8, 5), (10, 6), (8, 7), (7, 9), (6, 7), (4, 6), (6, 5)], fill=STAR_PALE)
    d.point([7, 6], fill=(255, 255, 255, 255))
    save(img, "starbound_loot_box")


# --------------------------------------------------------------------- void shaper

def void_anchor():
    """His hook: an eye on a chain, dragged through stone."""
    img, d = canvas()
    d.ellipse([2, 4, 13, 13], fill=VOID, outline=VOID_DARK)
    d.ellipse([4, 6, 11, 11], fill=ENDER, outline=VOID_DARK)
    d.ellipse([6, 7, 9, 10], fill=VOID_DARK)
    d.point([7, 8], fill=VOID_LIGHT)
    # the chain running up out of the frame
    d.line([7, 4, 7, 1], fill=STEEL_DARK)
    d.line([8, 3, 11, 1], fill=STEEL)
    d.line([4, 3, 5, 1], fill=STEEL)
    save(img, "void_anchor")


def colossus_trophy():
    """A block he never threw, still trying to move."""
    img, d = canvas()
    d.polygon([(8, 0), (14, 4), (14, 10), (8, 15), (2, 10), (2, 4)], fill=VOID_LIGHT, outline=VOID_DARK)
    d.polygon([(8, 2), (12, 5), (12, 9), (8, 12), (4, 9), (4, 5)], fill=VOID)
    # the block suspended at the centre
    d.rectangle([6, 6, 11, 10], fill=DEEPSLATE, outline=OBSIDIAN)
    d.line([6, 6, 11, 10], fill=OBSIDIAN)
    d.point([7, 7], fill=VOID_LIGHT)
    save(img, "colossus_trophy")


def voidsteel_scrap():
    """A shard of the stuff he is made of - the forge material."""
    img, d = canvas()
    d.polygon([(2, 11), (7, 4), (13, 6), (9, 13)], fill=VOID, outline=VOID_DARK)
    d.polygon([(5, 10), (8, 6), (11, 7), (8, 12)], fill=VOID_LIGHT)
    d.polygon([(9, 3), (13, 2), (14, 5), (10, 6)], fill=DEEPSLATE, outline=OBSIDIAN)
    d.point([7, 8], fill=ENDER)
    d.point([11, 9], fill=ENDER)
    save(img, "voidsteel_scrap")


def void_reaver():
    """A blade with a block jammed on the end of it."""
    img, d = canvas()
    sword(d, VOID_LIGHT, VOID_DARK, OBSIDIAN, (60, 40, 70, 255), ENDER)
    d.rectangle([9, 1, 14, 6], fill=DEEPSLATE, outline=OBSIDIAN)
    d.line([9, 1, 14, 6], fill=OBSIDIAN)
    d.line([9, 4, 11, 6], fill=OBSIDIAN)
    d.point([10, 2], fill=VOID_LIGHT)
    d.point([12, 3], fill=ENDER)
    save(img, "void_reaver")


def colossus_plate():
    """Block plating, hammered flat and worn."""
    img, d = canvas()
    plate(d, DEEPSLATE, OBSIDIAN, BLACK, VOID_DARK)
    d.rectangle([5, 6, 9, 11], fill=VOID, outline=VOID_DARK)
    d.point([7, 8], fill=VOID_LIGHT)
    d.line([4, 3, 11, 3], fill=VOID_LIGHT)
    save(img, "colossus_plate")


def shaping_sigil():
    """His grip in miniature: an echo shard holding a block outline."""
    img, d = canvas()
    d.polygon([(8, 0), (12, 6), (8, 15), (4, 6)], fill=ENDER, outline=VOID_DARK)
    d.polygon([(8, 3), (10, 6), (8, 12), (6, 6)], fill=(150, 240, 226, 255))
    # the block outline it is shaping
    d.rectangle([2, 8, 7, 13], outline=VOID_LIGHT)
    d.point([4, 10], fill=DEEPSLATE)
    d.point([5, 11], fill=DEEPSLATE)
    d.point([8, 5], fill=VOID_DARK)
    save(img, "shaping_sigil")


def voidshaper_loot_box():
    """His box: obsidian with void bands and a floating block latch."""
    img, d = canvas()
    chest(d, OBSIDIAN, VOID, VOID_LIGHT, DEEPSLATE, BLACK)
    d.rectangle([6, 3, 9, 5], fill=DEEPSLATE, outline=VOID_DARK)
    d.point([7, 4], fill=VOID_LIGHT)
    save(img, "voidshaper_loot_box")


# ---------------------------------------------------------------- emerald sovereign

def sovereigns_crown():
    """Far too small for the head it was made for."""
    img, d = canvas()
    d.rectangle([2, 9, 13, 13], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    for x in (2, 6, 9, 13):
        d.polygon([(x, 9), (x + 1, 3), (x + 2, 9)], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.line([2, 11, 13, 11], fill=ROYAL_GOLD_DARK)
    d.point([3, 10], fill=EMERALD_LIGHT)
    d.point([7, 10], fill=EMERALD)
    d.point([12, 10], fill=EMERALD_LIGHT)
    d.point([6, 4], fill=EMERALD)
    d.point([10, 4], fill=EMERALD)
    save(img, "sovereigns_crown")


def sovereign_trophy():
    """His throne, one block of it."""
    img, d = canvas()
    d.rectangle([2, 3, 13, 12], fill=EMERALD, outline=EMERALD_DARK)
    d.rectangle([2, 3, 13, 5], fill=EMERALD_LIGHT, outline=EMERALD_DARK)
    d.rectangle([4, 1, 11, 3], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    for x in (4, 7, 11):
        d.polygon([(x, 1), (x + 1, -2), (x + 2, 1)], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.rectangle([5, 14, 6, 15], fill=ROYAL_GOLD_DARK)
    d.rectangle([10, 14, 11, 15], fill=ROYAL_GOLD_DARK)
    d.point([8, 8], fill=EMERALD_LIGHT)
    save(img, "sovereign_trophy")


def royal_tribute():
    """His tithe, stamped - the forge material."""
    img, d = canvas()
    d.polygon([(8, 1), (13, 6), (13, 11), (8, 15), (3, 11), (3, 6)], fill=EMERALD, outline=EMERALD_DARK)
    d.polygon([(8, 3), (11, 7), (11, 10), (8, 13), (5, 10), (5, 7)], fill=EMERALD_LIGHT)
    d.rectangle([6, 7, 10, 10], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.point([7, 8], fill=ROYAL_GOLD)
    save(img, "royal_tribute")


def royal_contract():
    """Signed at the bottom in a hand that is not yours."""
    img, d = canvas()
    d.rectangle([2, 2, 13, 14], fill=PAPER, outline=PAPER_DARK)
    d.line([2, 3, 13, 3], fill=PAPER_DARK)
    for y in (5, 7, 9):
        d.line([4, y, 11, y], fill=INK)
    # the seal
    d.ellipse([9, 10, 13, 14], fill=EMERALD, outline=EMERALD_DARK)
    d.point([11, 12], fill=EMERALD_LIGHT)
    d.line([4, 12, 7, 13], fill=INK)
    save(img, "royal_contract")


def sovereigns_bell():
    """It rings for a kingdom one man wide."""
    img, d = canvas()
    d.polygon([(8, 2), (12, 9), (4, 9)], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.rectangle([4, 9, 12, 11], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.line([3, 11, 13, 11], fill=ROYAL_GOLD_DARK)
    d.rectangle([7, 0, 9, 2], fill=STEEL_DARK)
    d.ellipse([6, 11, 10, 14], fill=EMERALD, outline=EMERALD_DARK)
    d.point([7, 5], fill=ROYAL_GOLD)
    d.point([10, 7], fill=EMERALD_LIGHT)
    save(img, "sovereigns_bell")


def emerald_seal():
    """A stamp for a treasury nobody has audited in years."""
    img, d = canvas()
    d.ellipse([2, 2, 13, 13], fill=EMERALD_DARK, outline=(10, 70, 34, 255))
    d.ellipse([3, 3, 12, 12], fill=EMERALD)
    d.ellipse([5, 5, 10, 10], fill=EMERALD_LIGHT)
    # the crown stamped into the wax
    d.line([6, 8, 6, 6], fill=EMERALD_DARK)
    d.line([6, 8, 10, 8], fill=EMERALD_DARK)
    d.line([10, 8, 10, 6], fill=EMERALD_DARK)
    d.point([8, 7], fill=EMERALD_DARK)
    d.point([8, 4], fill=ROYAL_GOLD)
    d.point([4, 4], fill=ROYAL_GOLD)
    d.point([11, 11], fill=ROYAL_GOLD)
    save(img, "emerald_seal")


def sovereign_loot_box():
    """His box: emerald and gold, with a crown for a latch."""
    img, d = canvas()
    chest(d, EMERALD_DARK, EMERALD, ROYAL_GOLD, ROYAL_GOLD_DARK, BLACK)
    d.polygon([(7, 3), (8, 2), (9, 3), (10, 2), (11, 3), (11, 6), (4, 6), (4, 3), (5, 2), (6, 3)], fill=ROYAL_GOLD, outline=ROYAL_GOLD_DARK)
    d.point([7, 4], fill=EMERALD_LIGHT)
    save(img, "sovereign_loot_box")


if __name__ == "__main__":
    # clockwork_core(), clockwork_trophy(), mech_scrap() - drawn by tools/make_reworked_textures_a.py now
    # clockwork_gauntlet() - drawn by tools/make_reworked_textures_a.py now
    # mechanical_heart() - drawn by tools/make_reworked_textures_a.py now
    # clockwork_loot_box() - drawn by tools/make_reworked_textures_a.py now
    # automaton_armor() - drawn by tools/make_reworked_textures_a.py now

    # astral_compass(), starbound_trophy(), magical_essence() - drawn by tools/make_reworked_textures_a.py now
    # starpiercer() - drawn by tools/make_reworked_textures_a.py now
    # astral_mantle() - drawn by tools/make_reworked_textures_a.py now
    # magisters_codex(), starbound_loot_box() - drawn by tools/make_reworked_textures_a.py now

    # void_anchor() - drawn by tools/make_reworked_textures_b.py now
    # colossus_trophy() - drawn by tools/make_reworked_textures_b.py now
    # voidsteel_scrap() - drawn by tools/make_reworked_textures_b.py now
    # void_reaver() - drawn by tools/make_reworked_textures_b.py now
    # colossus_plate() - drawn by tools/make_reworked_textures_b.py now
    # shaping_sigil() - drawn by tools/make_reworked_textures_b.py now
    # voidshaper_loot_box() - drawn by tools/make_reworked_textures_b.py now

    # sovereigns_crown() - drawn by tools/make_reworked_textures_b.py now
    # sovereign_trophy() - drawn by tools/make_reworked_textures_b.py now
    # royal_tribute() - drawn by tools/make_reworked_textures_b.py now
    # royal_contract() - drawn by tools/make_reworked_textures_b.py now
    # sovereigns_bell() - drawn by tools/make_reworked_textures_b.py now
    # emerald_seal() - drawn by tools/make_reworked_textures_b.py now
    # sovereign_loot_box() - drawn by tools/make_reworked_textures_b.py now
    pass
