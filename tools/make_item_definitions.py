#!/usr/bin/env python3
"""Generates the resource pack's `assets/minecraft/items/*.json` overrides.

Fortune & Favors draws its items as *vanilla* items whose `custom_model_data`
selects a custom model. Vanilla item definitions that already dispatch on
something else (the Clock picks a dial from the in-game time) must not be
replaced outright, or ordinary clocks lose their dial - so the vanilla
definition is embedded verbatim as the `fallback` of our dispatch.

This script is checked in so the overrides are regenerable and reviewable, and so
adding an item is a one-line table edit instead of a 24KB copy/paste.

Run:  python3 tools/make_item_definitions.py
"""
import json
import pathlib
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack" / "assets" / "minecraft" / "items"
LOOM = pathlib.Path.home() / ".gradle" / "caches" / "fabric-loom" / "26.2"
CLIENT_JAR = LOOM / "minecraft-client.jar"

def bow_model(base: str):
    """A raw model node for one of our bows: idle, drawing, and fully drawn.

    A bow's icon is not one sprite. Vanilla swaps the model on `minecraft:using_item` and then
    on `minecraft:use_duration`, so the drawn shape changes as you pull - and a custom bow that
    ships only the idle sprite stands perfectly still while the player draws it. This copies
    vanilla's own node structure (`assets/minecraft/items/bow.json`) with our textures in it,
    which is why the three draw frames are generated alongside the idle ones.
    """
    def node(name: str):
        return {"type": "minecraft:model", "model": name}

    return {
        "type": "minecraft:condition",
        "property": "minecraft:using_item",
        "on_false": node(base),
        "on_true": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:use_duration",
            "scale": 0.05,
            "entries": [
                {"threshold": 0.65, "model": node(base + "_pulling_1")},
                {"threshold": 0.9, "model": node(base + "_pulling_2")},
            ],
            "fallback": node(base + "_pulling_0"),
        },
    }


def spear_model(gui_model: str, in_hand_model: str):
    """A raw model node: the flat icon in every GUI context, the long in-hand
    model everywhere else - copied from vanilla's own spear definition."""
    return {
        "type": "minecraft:select",
        "property": "minecraft:display_context",
        "cases": [
            {
                "when": ["gui", "ground", "fixed", "on_shelf"],
                "model": {"type": "minecraft:model", "model": gui_model},
            }
        ],
        "fallback": {"type": "minecraft:model", "model": in_hand_model},
    }


# (base item, custom_model_data, model) - order does not matter, thresholds are
# sorted on the way out.
ENTRIES = {
    # The Clockwork King (4550240-4550246).
    "clock": [
        (4550210, "fortuneandfavors:item/space_time_rift"),
        (4550212, "fortuneandfavors:item/pocket_watch"),
        (4550213, "fortuneandfavors:item/pocket_watch_ii"),
        (4550240, "fortuneandfavors:item/clockwork_core"),
    ],
    "prismarine_crystals": [
        (4550232, "fortuneandfavors:item/bloodsoaked_core"),
        (4550242, "fortuneandfavors:item/mech_scrap"),
    ],
    "heart_of_the_sea": [
        (4550244, "fortuneandfavors:item/mechanical_heart"),
    ],
    "netherite_chestplate": [
        (4550246, "fortuneandfavors:item/automaton_armor"),
        (4550254, "fortuneandfavors:item/astral_mantle"),
        (4550264, "fortuneandfavors:item/colossus_plate"),
    ],
    # Starbound Magister (4550250-4550256).
    "compass": [
        (4550250, "fortuneandfavors:item/astral_compass"),
        # The Death Compass used to hand out 4550040, which the Sculk Medallion
        # already owned on a different base item. A model code can only have one
        # item's art under it, and the compass was the one that never got any, so
        # it rendered as a vanilla compass. It owns a code of its own now.
        (4550042, "fortuneandfavors:item/death_compass"),
    ],
    "nether_star": [
        (4550251, "fortuneandfavors:item/starbound_trophy"),
    ],
    "amethyst_shard": [
        (4550213, "fortuneandfavors:item/chrono_shard"),
        (4550252, "fortuneandfavors:item/magical_essence"),
    ],
    # Void Shaper (4550260-4550266).
    "ender_eye": [
        (4550260, "fortuneandfavors:item/void_anchor"),
    ],
    "end_crystal": [
        (4550261, "fortuneandfavors:item/colossus_trophy"),
    ],
    "netherite_scrap": [
        (4550262, "fortuneandfavors:item/voidsteel_scrap"),
    ],
    "echo_shard": [
        (4550265, "fortuneandfavors:item/shaping_sigil"),
    ],
    # Emerald Sovereign (4550270-4550276).
    "golden_helmet": [
        (4550270, "fortuneandfavors:item/sovereigns_crown"),
    ],
    "emerald_block": [
        (4550271, "fortuneandfavors:item/sovereign_trophy"),
    ],
    "emerald": [
        (4550272, "fortuneandfavors:item/royal_tribute"),
    ],
    "paper": [
        (4550273, "fortuneandfavors:item/royal_contract"),
    ],
    "bell": [
        (4550274, "fortuneandfavors:item/sovereigns_bell"),
    ],
    "firework_star": [
        (4550275, "fortuneandfavors:item/emerald_seal"),
    ],
    "honeycomb": [
        (4550214, "fortuneandfavors:item/hourglass_of_haste"),
    ],
    # 4550025 is the Wither Loot Box's own id: it used to share the King's
    # 4550005 on the same chest base, so only one of the two could ever render.
    "chest": [
        (4550025, "fortuneandfavors:item/wither_loot_box"),
        (4550050, "fortuneandfavors:item/sculk_loot_box"),
        (4550211, "fortuneandfavors:item/time_lord_loot_box"),
        (4550235, "fortuneandfavors:item/scarlet_loot_box"),
        (4550245, "fortuneandfavors:item/clockwork_loot_box"),
        # The two newest boxes (4550324, 4550329).
        (4550324, "fortuneandfavors:item/drowned_loot_box"),
        (4550329, "fortuneandfavors:item/gale_loot_box"),
        (4550256, "fortuneandfavors:item/starbound_loot_box"),
        (4550266, "fortuneandfavors:item/voidshaper_loot_box"),
        (4550276, "fortuneandfavors:item/sovereign_loot_box"),
        (4550291, "fortuneandfavors:item/puppeteer_loot_box"),
    ],
    # The Puppeteer's kit (4550290-4550294). These are the ids that were missing
    # entirely: the five items were minted against 4550280-4550284, which already
    # belong to Excalibur, the Mystery Box and the Mystery Keys, so there was no
    # base item for their art to hang off and nothing ever drew one. Every one of
    # the five base items below has no override of its own before this.
    "armor_stand": [
        (4550290, "fortuneandfavors:item/wooden_marionette"),
    ],
    "carved_pumpkin": [
        (4550292, "fortuneandfavors:item/puppeteers_mask"),
    ],
    "string": [
        (4550293, "fortuneandfavors:item/marionette_strings"),
    ],
    "skeleton_skull": [
        (4550294, "fortuneandfavors:item/empty_mask"),
    ],
    # The Scarlet Devil set. Ids 4550230-4550235 - keep in step with the
    # SCARLET_*_MODEL constants in ModItems.java.
    "glass_bottle": [
        (4550230, "fortuneandfavors:item/scarlet_blood"),
    ],
    "gold_block": [
        (4550231, "fortuneandfavors:item/scarlet_trophy"),
    ],

    "phantom_membrane": [
        (4550233, "fortuneandfavors:item/crimson_essence"),
    ],
    "netherite_sword": [
        (4550280, "fortuneandfavors:item/excalibur"),
        (4550002, "fortuneandfavors:item/wither_blade"),
        (4550032, "fortuneandfavors:item/wither_cloak_sword"),
        (4550243, "fortuneandfavors:item/clockwork_gauntlet"),
        (4550253, "fortuneandfavors:item/starpiercer"),
        (4550263, "fortuneandfavors:item/void_reaver"),
        # The Mirage Castle's monarch carries, and drops, exactly one of these.
        (4550295, "fortuneandfavors:item/last_remembrance"),
        # The Ender Dragon's three weapons (4550300-4550304); the two materials
        # that make them sit on their own base items below.
        (4550302, "fortuneandfavors:item/voidfang"),
        # The Gale Warden's greatsword (4550325) - another heavy blade on the same base item.
        (4550325, "fortuneandfavors:item/skybreaker"),
        # The awakened tier is the same three base items with their own art, so it
        # is a second entry on each - a reforged Voidfang is still a netherite sword.
        (4550312, "fortuneandfavors:item/voidfang_awakened"),
    ],
    "nautilus_shell": [
        (4550300, "fortuneandfavors:item/heart_of_the_end"),
    ],
    # The scute file is `turtle_scute` in this version - `minecraft:scute` no longer
    # ships an item definition of its own, so keying the pack on it silently produced
    # nothing at all.
    "turtle_scute": [
        (4550301, "fortuneandfavors:item/dragon_scale"),
    ],
    "bow": [
        (4550303, bow_model("fortuneandfavors:item/starfall")),
        (4550313, bow_model("fortuneandfavors:item/starfall_awakened")),
    ],
    "mace": [
        (4550304, "fortuneandfavors:item/enderheart"),
        (4550314, "fortuneandfavors:item/enderheart_awakened"),
    ],
    # The Scarlet Fang *is* a diamond spear (the base item, so it thrusts and
    # animates like one) - so it also needs the spear's two-part model: a flat
    # icon in the GUI and the long in-hand model while held. Vanilla ships the
    # same pairing for every spear (see diamond_spear.json).
    "diamond_spear": [
        (4550234, spear_model("fortuneandfavors:item/scarlets_fang", "fortuneandfavors:item/scarlets_fang_in_hand")),
    ],
    "book": [
        (4550236, "fortuneandfavors:item/scarlet_grimoire"),
        (4550255, "fortuneandfavors:item/magisters_codex"),
    ],
    "prismarine_shard": [
        (4550237, "fortuneandfavors:item/blood_prism"),
    ],
    # Art that had no override at all before: the Mystery Box rendered as a plain
    # barrel and every Mystery Key tier rendered as the same tripwire hook.
    "barrel": [
        (4550281, "fortuneandfavors:item/mystery_box"),
    ],
    "tripwire_hook": [
        (4550282, "fortuneandfavors:item/mystery_key_common"),
        (4550283, "fortuneandfavors:item/mystery_key_rare"),
        (4550284, "fortuneandfavors:item/mystery_key_epic"),
        (4550285, "fortuneandfavors:item/mystery_key_legendary"),
        # The Abyssal Chain is a hook, so it is drawn on the hook's base item - and it is the
        # only one of the ten that is, which is why it sits here rather than in the block below.
        (4550322, "fortuneandfavors:item/abyssal_chain"),
    ],
    # The Drowned Sovereign and the Gale Warden (4550320-4550329). Ids are kept in step with the
    # *_MODEL constants in ModItems.java; each one has to be minted on the base item listed here
    # or the item renders as the plain vanilla thing it is built on.
    "mace": [
        (4550320, "fortuneandfavors:item/leviathans_grasp"),
    ],
    # Both tridents. Vanilla's trident definition dispatches on display context rather than on a
    # model code, so these are the first entries this pack has ever put on the item - which works
    # because the dispatch keeps vanilla's own node as its fallback (see write_dispatch).
    "trident": [
        (4550321, "fortuneandfavors:item/tidecaller"),
        (4550326, "fortuneandfavors:item/gale_chakram"),
    ],
    "netherite_chestplate": [
        (4550327, "fortuneandfavors:item/wardens_mantle"),
    ],
    "nautilus_shell": [
        (4550328, "fortuneandfavors:item/gale_sigil"),
        # The Abyssal Pearl, the sea set's forge material. A shell base again, because the pack
        # already dispatches on this one and a pearl out of the deep is the same family of thing;
        # the art is ours either way, which is what the id is for.
        (4550330, "fortuneandfavors:item/abyssal_pearl"),
    ],
    # The Gale Core, the sky set's forge material. A new base for the pack: the Warden is a
    # breeze, and a breeze rod is the one vanilla item that already means "moving air".
    "breeze_rod": [
        (4550331, "fortuneandfavors:item/gale_core"),
    ],
    "heart_of_the_sea": [
        (4550323, "fortuneandfavors:item/sovereigns_heart"),
    ],
    # The Sorter Tag - the one item that names a container for an Item Sorter. Drawn on a sign
    # because that is the gesture it replaces, with its own art so it is not mistaken for a plain
    # sign in the hotbar.
    "oak_sign": [
        (4550332, "fortuneandfavors:item/sorter_tag"),
    ],
}

# Extra top-level keys the item definition needs (they sit beside `model`).
TOP_LEVEL = {
    "diamond_spear": {"swap_animation_scale": 1.95},
}



# ---------------------------------------------------------------------------
# What the shipped pack already carried.
#
# The overrides checked in under resourcepack/assets/minecraft/items were built
# by hand across many passes, and this table was not kept in step with them: it
# knew 28 of the 50 bases, so re-running this script would have DELETED 54
# entries - the Warlord's Axe, the Slime Shield, the Bounty Compass, the Distant
# Memory blade and 50 others - and quietly turned those items back into the
# vanilla things they are built on. The block below is those entries, read off
# the pack itself, so this script reproduces the pack instead of trimming it.
# Merged into ENTRIES at import: the table above is where new work goes.
ALREADY_SHIPPED = {
    "amethyst_shard": [
        (4550207, "fortuneandfavors:item/raiders_upgrader"),
    ],
    "blaze_rod": [
        (4550021, "fortuneandfavors:item/mindbinder_staff"),
        (4550028, "fortuneandfavors:item/ice_staff"),
        (4550051, "fortuneandfavors:item/sculk_mage_staff"),
    ],
    "bone": [
        (4550001, "fortuneandfavors:item/wither_staff"),
        (4550011, "fortuneandfavors:item/wither_essence"),
        (4550101, "fortuneandfavors:item/multidimensional_army"),
    ],
    "book": [
        (4550201, "fortuneandfavors:item/evoker_spellbook"),
        (4550205, "fortuneandfavors:item/illusioner_spellbook"),
    ],
    "bow": [
        (4550030, bow_model("fortuneandfavors:item/frostbound_bow")),
    ],
    "chest": [
        (4550005, "fortuneandfavors:item/king_loot_box"),
        (4550010, "fortuneandfavors:item/slime_loot_box"),
        (4550018, "fortuneandfavors:item/golem_loot_box"),
        (4550019, "fortuneandfavors:item/raid_loot_box"),
        (4550023, "fortuneandfavors:item/mindbinder_loot_box"),
        (4550027, "fortuneandfavors:item/snow_loot_box"),
    ],
    "chiseled_stone_bricks": [
        (4550017, "fortuneandfavors:item/boulder_baby"),
    ],
    "compass": [
        (4550041, "fortuneandfavors:item/bounty_compass"),
    ],
    "diamond_sword": [
        (4550061, "fortuneandfavors:item/distant_memory_sword"),
    ],
    "echo_shard": [
        (4550040, "fortuneandfavors:item/sculk_medallion"),
        (4550050, "fortuneandfavors:item/sculk_loot_box"),
        (4550054, "fortuneandfavors:item/sculk_orb"),
        (4550055, "fortuneandfavors:item/sculk_essence"),
        (4550060, "fortuneandfavors:item/distant_memory_shard"),
    ],
    "ender_eye": [
        (4550019, "fortuneandfavors:item/ominous_eye"),
    ],
    "goat_horn": [
        (4550053, "fortuneandfavors:item/wardens_call"),
        (4550202, "fortuneandfavors:item/captain_horn"),
    ],
    "gold_block": [
        (4550208, "fortuneandfavors:item/warlord_trophy"),
        (4550241, "fortuneandfavors:item/clockwork_trophy"),
    ],
    "iron_boots": [
        (4550009, "fortuneandfavors:item/slime_boots"),
    ],
    "iron_chestplate": [
        (4550016, "fortuneandfavors:item/stoneheart"),
        (4550031, "fortuneandfavors:item/glacier_cloak"),
    ],
    "iron_helmet": [
        (4550022, "fortuneandfavors:item/possessed_mask"),
    ],
    "iron_leggings": [
        (4550052, "fortuneandfavors:item/sculk_sensor_leggings"),
    ],
    "leather_boots": [
        (4550009, "fortuneandfavors:item/slime_boots"),
    ],
    "leather_chestplate": [
        (4550203, "fortuneandfavors:item/warlord_cloak"),
        (4550204, "fortuneandfavors:item/evoker_cloak"),
        (4550206, "fortuneandfavors:item/illusioner_cloak"),
    ],
    "netherite_axe": [
        (4550200, "fortuneandfavors:item/warlord_axe"),
    ],
    "netherite_chestplate": [
        (4550024, "fortuneandfavors:item/mindbinder_shroud"),
    ],
    "netherite_helmet": [
        (4550003, "fortuneandfavors:item/wither_crown"),
    ],
    "netherite_leggings": [
        (4550246, "fortuneandfavors:item/automaton_armor"),
    ],
    "phantom_membrane": [
        (4550020, "fortuneandfavors:item/shattered_mind"),
    ],
    "prismarine_crystals": [
        (4550029, "fortuneandfavors:item/frozen_heart"),
    ],
    "red_banner": [
        (4550033, "fortuneandfavors:item/raid_banner"),
    ],
    "shield": [
        (4550008, "fortuneandfavors:item/slime_shield"),
    ],
    "slime_ball": [
        (4550006, "fortuneandfavors:item/gelatinous_crown"),
        (4550007, "fortuneandfavors:item/slime_launcher"),
        (4550012, "fortuneandfavors:item/mythical_gelatin"),
    ],
    "snowball": [
        (4550026, "fortuneandfavors:item/cryogenic_core"),
    ],
    "stick": [
        (4550014, "fortuneandfavors:item/stone_staff"),
    ],
    "stone": [
        (4550013, "fortuneandfavors:item/golem_core"),
    ],
    "stone_axe": [
        (4550015, "fortuneandfavors:item/golem_fist"),
    ],
    "wither_skeleton_skull": [
        (4550004, "fortuneandfavors:item/withering_memory"),
    ],
}

for _base, _entries in ALREADY_SHIPPED.items():
    ENTRIES.setdefault(_base, []).extend(_entries)

def vanilla(item: str):
    """The vanilla item definition, read straight out of the client jar."""
    with zipfile.ZipFile(CLIENT_JAR) as z:
        with z.open(f"assets/minecraft/items/{item}.json") as f:
            return json.load(f)


def model_entry(threshold, model):
    if isinstance(model, dict):
        # A raw model node (the spear's display-context select, for example).
        return {"threshold": float(threshold), "model": model}
    return {
        "threshold": float(threshold),
        "model": {"type": "minecraft:model", "model": model},
    }


def write_dispatch(item, entries, fallback):
    entries = sorted(entries, key=lambda e: e["threshold"])
    doc = {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": entries,
            "fallback": fallback,
        }
    }
    doc.update(TOP_LEVEL.get(item, {}))
    path = PACK / f"{item}.json"
    path.write_text(json.dumps(doc, indent=2) + "\n")
    print(f"wrote {path.relative_to(ROOT)} ({len(entries)} entr{'y' if len(entries)==1 else 'ies'})")


def main():
    if not CLIENT_JAR.exists():
        raise SystemExit(f"vanilla client jar not found at {CLIENT_JAR}")

    for item, additions in ENTRIES.items():
        path = PACK / f"{item}.json"
        if path.exists():
            doc = json.loads(path.read_text())
            model = doc["model"]
            if model.get("type") != "minecraft:range_dispatch" or model.get("property") != "minecraft:custom_model_data":
                raise SystemExit(f"{path} is not a custom_model_data dispatch - refusing to rewrite it")
            existing = model.get("entries", [])
        else:
            existing = []

        # The fallback is always re-read from the client jar, never inherited from
        # the file we are rewriting.
        #
        # It used to be taken from the existing file whenever one was there, which
        # froze whatever an older generator had written: a version that flattened
        # the vanilla node into a single model left `bow` with no draw animation,
        # every armoured piece with no trim, the chest with no christmas texture and
        # the goat horn with no toot - and because the file then existed, no later
        # run ever repaired it. Vanilla's own `model` node is the only correct
        # fallback for a stack that is not ours, so read it fresh every time.
        van = vanilla(item)
        fallback = van["model"]
        # Carry over any vanilla top-level keys we replace (such as the spear's
        # swap_animation_scale) so the base item keeps its feel.
        for key, value in van.items():
            if key != "model":
                TOP_LEVEL.setdefault(item, {})[key] = value

        wanted = {float(t): m for t, m in additions}
        kept = [e for e in existing if e.get("threshold") not in wanted]
        kept.extend(model_entry(t, m) for t, m in additions)
        write_dispatch(item, kept, fallback)


if __name__ == "__main__":
    main()
