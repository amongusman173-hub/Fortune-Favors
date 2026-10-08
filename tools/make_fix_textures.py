#!/usr/bin/env python3
"""Re-does the item art that was either a flat placeholder or not good enough.

Three groups live here:

  * the two raid cloaks and two raid spellbooks, which were written as a single
    solid colour by an earlier pass and read as blank squares in the inventory,
  * three items that had no art at all - Excalibur, the Mystery Box and its four
    Mystery Keys - which rendered as the vanilla item they are built on,
  * the Clockwork King's Trophy and Gauntlet, redrawn with real form.

Like the other generators it is checked in so the art is reproducible and
reviewable. Dispatch entries for the new ids live in `tools/make_item_definitions.py`.

Run:  python3 tools/make_fix_textures.py
"""
import json
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

CLEAR = (0, 0, 0, 0)
BLACK = (24, 20, 28, 255)

# ------------------------------------------------------------------ raid palette
ROBE = (58, 46, 84, 255)
ROBE_DARK = (34, 26, 52, 255)
ROBE_LIGHT = (92, 76, 128, 255)
TRIM = (196, 156, 62, 255)
TRIM_DARK = (128, 96, 30, 255)

ILLUSION = (168, 196, 236, 255)
ILLUSION_DARK = (86, 112, 168, 255)
ILLUSION_LIGHT = (226, 240, 255, 255)
MIST = (196, 176, 236, 255)

# ------------------------------------------------------------------ excalibur
STEEL = (208, 216, 226, 255)
STEEL_MID = (150, 158, 172, 255)
STEEL_DARK = (84, 92, 108, 255)
GOLD = (238, 196, 76, 255)
GOLD_DARK = (156, 112, 26, 255)
SAPPHIRE = (86, 156, 236, 255)
SAPPHIRE_LIGHT = (186, 226, 255, 255)

# ------------------------------------------------------------------ mystery
PURPLE = (128, 66, 196, 255)
PURPLE_DARK = (72, 34, 120, 255)
PURPLE_LIGHT = (186, 132, 244, 255)
WOOD = (128, 92, 56, 255)
WOOD_DARK = (78, 54, 32, 255)

# ------------------------------------------------------------------ clockwork
BRASS = (196, 148, 58, 255)
BRASS_DARK = (128, 92, 30, 255)
BRASS_LIGHT = (240, 206, 118, 255)
STEEL2 = (150, 158, 168, 255)
STEEL2_DARK = (88, 96, 108, 255)
STEEL2_LIGHT = (208, 216, 226, 255)
IRON_RED = (170, 60, 44, 255)
GLOW_AMBER = (255, 196, 84, 255)


def canvas(size=16):
    img = Image.new("RGBA", (size, size), CLEAR)
    return img, ImageDraw.Draw(img)


HANDHELD = {"excalibur", "clockwork_gauntlet"}


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


# --------------------------------------------------------------------- cloaks

def evoker_cloak():
    """A heavy evoker's robe front: broad shoulders, gold trim, fang clasp."""
    img, d = canvas()
    # Shoulders and body
    d.polygon([(2, 4), (5, 2), (10, 2), (13, 4), (13, 13), (2, 13)], fill=ROBE, outline=ROBE_DARK)
    # Collar
    d.polygon([(5, 2), (7, 4), (8, 4), (10, 2), (10, 5), (5, 5)], fill=ROBE_LIGHT, outline=ROBE_DARK)
    # Robe folds
    d.line([(6, 6), (6, 13)], fill=ROBE_DARK)
    d.line([(9, 6), (9, 13)], fill=ROBE_DARK)
    d.line([(4, 6), (4, 12)], fill=ROBE_DARK)
    d.line([(11, 6), (11, 12)], fill=ROBE_DARK)
    # Gold trim along the hem and the front placket
    d.line([(2, 13), (13, 13)], fill=TRIM)
    d.line([(7, 5), (8, 5)], fill=TRIM)
    d.line([(3, 5), (5, 5)], fill=TRIM_DARK)
    d.line([(10, 5), (12, 5)], fill=TRIM_DARK)
    # Fang clasp at the throat
    d.line([(7, 6), (7, 9)], fill=(236, 232, 220, 255))
    d.point([(7, 10)], fill=(236, 232, 220, 255))
    d.point([(6, 8)], fill=TRIM)
    d.point([(8, 8)], fill=TRIM)
    save(img, "evoker_cloak")


def illusioner_cloak():
    """A pale, misty cloak with a deep hood and two bright eyes inside it."""
    img, d = canvas()
    # Body of the cloak
    d.polygon([(3, 5), (6, 3), (9, 3), (12, 5), (13, 12), (2, 12)], fill=ILLUSION_DARK, outline=BLACK)
    d.polygon([(4, 6), (6, 4), (9, 4), (11, 6), (12, 11), (3, 11)], fill=ILLUSION, outline=ILLUSION_DARK)
    # Hood
    d.polygon([(5, 2), (10, 2), (11, 6), (4, 6)], fill=MIST, outline=ILLUSION_DARK)
    d.polygon([(6, 3), (9, 3), (10, 5), (5, 5)], fill=(46, 38, 66, 255))
    # Eyes in the hood
    d.point([(6, 4)], fill=ILLUSION_LIGHT)
    d.point([(9, 4)], fill=ILLUSION_LIGHT)
    # Mist hem + a little shimmer
    d.line([(2, 12), (13, 12)], fill=ILLUSION_DARK)
    d.line([(3, 13), (12, 13)], fill=(150, 176, 220, 140))
    d.point([(5, 8)], fill=ILLUSION_LIGHT)
    d.point([(10, 9)], fill=ILLUSION_LIGHT)
    save(img, "illusioner_cloak")


# ------------------------------------------------------------------ spellbooks

def _tome(d, cover, cover_dark, cover_light, band, page=(236, 230, 214, 255)):
    """Shared book silhouette: cover, spine, page block and a metal band."""
    d.rectangle([(2, 2), (13, 13)], fill=cover, outline=BLACK)
    d.rectangle([(2, 2), (3, 13)], fill=cover_dark)          # spine
    d.rectangle([(11, 3), (13, 12)], fill=page, outline=cover_dark)
    d.line([(11, 4), (13, 4)], fill=(206, 198, 178, 255))
    d.line([(11, 7), (13, 7)], fill=(206, 198, 178, 255))
    d.line([(11, 10), (13, 10)], fill=(206, 198, 178, 255))
    d.rectangle([(4, 2), (10, 3)], fill=band)                 # top band
    d.rectangle([(4, 12), (10, 13)], fill=band)               # bottom band
    d.point([(4, 3)], fill=cover_light)


def evoker_spellbook():
    """The Evoker's tome: dark violet cover, gold corners, a fang sigil."""
    img, d = canvas()
    _tome(d, ROBE, ROBE_DARK, ROBE_LIGHT, TRIM)
    # Corner plates
    for cx, cy in ((4, 4), (10, 4), (4, 11), (10, 11)):
        d.point([(cx, cy)], fill=TRIM)
    # Fang sigil in the middle of the cover
    d.polygon([(7, 5), (8, 5), (7, 10)], fill=(238, 234, 222, 255), outline=TRIM_DARK)
    d.point([(8, 6)], fill=(255, 255, 255, 255))
    d.point([(6, 5)], fill=TRIM)
    d.point([(9, 10)], fill=TRIM)
    save(img, "evoker_spellbook")


def illusioner_spellbook():
    """The Illusioner's tome: pale cover, silver corners, a mirrored eye."""
    img, d = canvas()
    _tome(d, ILLUSION_DARK, (58, 80, 128, 255), ILLUSION, STEEL2)
    for cx, cy in ((4, 4), (10, 4), (4, 11), (10, 11)):
        d.point([(cx, cy)], fill=STEEL2_LIGHT)
    # An eye sigil: lens outline, iris, glint
    d.polygon([(5, 8), (7, 5), (9, 5), (11, 8), (9, 11), (7, 11)], fill=ILLUSION_LIGHT, outline=(58, 80, 128, 255))
    d.ellipse([(7, 6), (9, 9)], fill=(58, 80, 128, 255))
    d.point([(7, 7)], fill=(255, 255, 255, 255))
    save(img, "illusioner_spellbook")


# ------------------------------------------------------------------- excalibur

def excalibur():
    """Excalibur as a **greatsword**: a broad two-handed blade, a heavy barred
    crossguard, a long wrapped grip and a sapphire pommel.

    The first pass drew it as a slim arming sword - a two-pixel blade on the same
    diagonal as every other vanilla sword - which made the mod's rarest duel drop
    look like an iron sword with better colours. A greatsword has to read as MASS:
    a blade roughly twice as wide as a vanilla sword's, a guard that spans the
    whole width of the canvas, and a grip long enough for the second hand.
    """
    img, d = canvas()

    # --- Blade ---------------------------------------------------------------
    # Drawn in three passes, dark -> steel -> highlight, so the width reads as
    # thickness rather than as a thicker outline.
    d.line([(5, 11), (14, 1)], fill=STEEL_DARK, width=7)
    d.line([(5, 11), (14, 1)], fill=STEEL, width=5)
    d.line([(6, 10), (13, 2)], fill=STEEL_MID, width=3)
    # Bright fuller down the middle: the one detail that says "finely made".
    d.line([(6, 10), (13, 2)], fill=(255, 255, 255, 255), width=1)
    # Shaded lower edge, so the blade has a lit face and a dark one.
    d.line([(7, 11), (14, 3)], fill=STEEL_DARK, width=1)
    # Tapered point.
    d.point([(14, 1)], fill=(255, 255, 255, 255))
    d.point([(15, 1)], fill=STEEL_DARK)
    d.point([(14, 2)], fill=STEEL_DARK)

    # --- Crossguard ----------------------------------------------------------
    # Perpendicular to the blade, spanning the canvas: the silhouette that tells
    # a greatsword from a longsword at a glance.
    d.line([(2, 8), (8, 14)], fill=GOLD_DARK, width=5)
    d.line([(2, 8), (8, 14)], fill=GOLD, width=3)
    # Flared tips, each set with a sapphire.
    d.point([(2, 8)], fill=SAPPHIRE)
    d.point([(3, 9)], fill=GOLD_DARK)
    d.point([(8, 14)], fill=SAPPHIRE)
    d.point([(7, 13)], fill=GOLD_DARK)

    # --- Grip and pommel -----------------------------------------------------
    # A long two-handed grip, below-left of the guard.
    d.line([(4, 12), (1, 15)], fill=(72, 44, 30, 255), width=3)
    d.line([(4, 12), (1, 15)], fill=(140, 96, 58, 255), width=1)
    for p in ((3, 13), (2, 14)):
        d.point(p, fill=(60, 36, 24, 255))     # wrap crosses
    # Gold pommel with a sapphire cap.
    d.rectangle([(0, 14), (1, 15)], fill=GOLD, outline=GOLD_DARK)
    d.point([(0, 15)], fill=SAPPHIRE_LIGHT)

    save(img, "excalibur")


# ---------------------------------------------------------------- mystery box

def mystery_box():
    """A purple mystery crate: gold straps, a wooden lid, a big question mark."""
    img, d = canvas()
    d.rectangle([(1, 5), (14, 14)], fill=PURPLE, outline=BLACK)
    d.rectangle([(1, 3), (14, 5)], fill=PURPLE_LIGHT, outline=BLACK)
    d.line([(1, 5), (14, 5)], fill=BLACK)
    # Wooden lid slats
    d.line([(2, 4), (13, 4)], fill=WOOD_DARK)
    # Gold straps
    d.rectangle([(4, 3), (5, 14)], fill=GOLD, outline=GOLD_DARK)
    d.rectangle([(10, 3), (11, 14)], fill=GOLD, outline=GOLD_DARK)
    d.rectangle([(1, 9), (14, 10)], fill=GOLD, outline=GOLD_DARK)
    # Question mark on the lid front
    d.line([(7, 11), (7, 12)], fill=BLACK)
    d.line([(7, 11), (8, 11)], fill=BLACK)
    d.line([(8, 11), (9, 12)], fill=BLACK)
    # Padlock
    d.rectangle([(6, 6), (9, 8)], fill=PURPLE_DARK, outline=BLACK)
    d.point([(7, 7)], fill=GOLD)
    save(img, "mystery_box")


# ---------------------------------------------------------------- mystery keys

KEY_TIERS = [
    ("mystery_key_common", (188, 192, 200, 255), (120, 126, 138, 255), (232, 236, 244, 255)),
    ("mystery_key_rare", (88, 168, 232, 255), (40, 96, 158, 255), (176, 224, 255, 255)),
    ("mystery_key_epic", (156, 104, 232, 255), (92, 52, 156, 255), (212, 176, 255, 255)),
    ("mystery_key_legendary", (238, 196, 76, 255), (156, 112, 26, 255), (255, 236, 160, 255)),
]


def mystery_key(name, body, dark, light):
    """One key tier: a bow with a gem, a shaft, and two teeth."""
    img, d = canvas()
    # Bow (the handle ring) at the top
    d.ellipse([(5, 1), (10, 6)], fill=body, outline=dark)
    d.ellipse([(6, 2), (9, 5)], fill=CLEAR, outline=dark)
    # Gem set into the bow
    d.point([(7, 3)], fill=light)
    d.point([(8, 2)], fill=light)
    # Shaft down to the bottom-right
    d.line([(7, 6), (7, 13)], fill=body)
    d.line([(8, 6), (8, 13)], fill=dark)
    # Teeth
    d.line([(8, 11), (11, 11)], fill=body)
    d.line([(8, 13), (11, 13)], fill=body)
    d.point([(11, 11)], fill=dark)
    d.point([(11, 13)], fill=dark)
    # Highlight
    d.point([(6, 2)], fill=light)
    d.point([(7, 9)], fill=light)
    save(img, name)


# ------------------------------------------------------------ clockwork trophy

def clockwork_trophy():
    """A brass cup crowned with a live cog - the King's bragging rights."""
    img, d = canvas()
    # Base and stem
    d.rectangle([(4, 14), (11, 15)], fill=BRASS_DARK, outline=BLACK)
    d.rectangle([(7, 10), (8, 12)], fill=BRASS, outline=BRASS_DARK)
    # Cup bowl
    d.polygon([(3, 4), (12, 4), (11, 8), (9, 10), (6, 10), (4, 8)], fill=BRASS, outline=BLACK)
    d.line([(4, 5), (4, 7)], fill=BRASS_LIGHT)
    d.line([(5, 4), (5, 4)], fill=BRASS_LIGHT)
    # Handles
    d.arc([(0, 4), (4, 8)], 90, 270, fill=BRASS_DARK)
    d.arc([(11, 4), (15, 8)], 270, 90, fill=BRASS_DARK)
    # Cog mounted on the rim
    d.ellipse([(5, 0), (10, 4)], fill=GLOW_AMBER, outline=BRASS_DARK)
    d.rectangle([(6, 0), (6, 0)], fill=BRASS_LIGHT)
    d.point([(4, 1)], fill=BRASS)
    d.point([(11, 2)], fill=BRASS)
    d.point([(7, 1)], fill=(255, 240, 200, 255))
    # Engraved plate on the bowl
    d.rectangle([(6, 6), (9, 7)], fill=BRASS_DARK)
    d.point([(7, 6)], fill=GLOW_AMBER)
    save(img, "clockwork_trophy")


# --------------------------------------------------------- clockwork gauntlet

def clockwork_gauntlet():
    """A piston-fisted gauntlet: knuckle plate, vents, wrist piston and grip."""
    img, d = canvas()
    # Fist block
    d.rectangle([(6, 2), (14, 8)], fill=BRASS, outline=BLACK)
    d.rectangle([(7, 3), (13, 4)], fill=BRASS_LIGHT)
    # Knuckle plate
    d.rectangle([(13, 3), (14, 7)], fill=STEEL2, outline=STEEL2_DARK)
    # Vent slits
    for y in (5, 6, 7):
        d.line([(8, y), (11, y)], fill=BRASS_DARK)
    # Thumb
    d.rectangle([(6, 6), (7, 9)], fill=BRASS_DARK, outline=BLACK)
    # Wrist collar
    d.rectangle([(5, 8), (11, 10)], fill=STEEL2, outline=STEEL2_DARK)
    d.line([(5, 9), (11, 9)], fill=STEEL2_LIGHT)
    # Piston rod running down into the grip
    d.line([(7, 10), (10, 13)], fill=STEEL2_LIGHT)
    d.line([(6, 10), (9, 13)], fill=STEEL2_DARK)
    # Grip
    d.line([(5, 11), (8, 14)], fill=IRON_RED)
    d.line([(4, 12), (7, 15)], fill=(96, 32, 24, 255))
    # Steam vent glow
    d.point([(13, 2)], fill=GLOW_AMBER)
    d.point([(14, 8)], fill=GLOW_AMBER)
    save(img, "clockwork_gauntlet")


if __name__ == "__main__":
    # The evoker and illusioner robes and spellbooks are drawn by tools/make_raid_textures.py and
    # the Mystery Keys by tools/make_mystery_keys.py; the functions above are kept for reference
    # but no longer run, so a rerun of this script cannot overwrite the newer art.
    excalibur()
    mystery_box()
    # clockwork_trophy() - drawn by tools/make_reworked_textures_a.py now
    # clockwork_gauntlet() - drawn by tools/make_reworked_textures_a.py now
