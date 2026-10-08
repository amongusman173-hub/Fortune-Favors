#!/usr/bin/env python3
"""Worn (on-body) textures for the custom armor, drawn by the client mod only.

Each piece starts from its vanilla base material's worn texture - so every pixel sits exactly
where the armor model expects it - and is recoloured into the item's own palette: the shading is
mapped onto four tones, and the brightest detail picks up the item's accent colour. The client's
EquipmentLayerRendererMixin swaps these in for our items; vanilla clients never see them.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_worn_armor.py
"""
import glob
import json
import os
import zipfile

from make_scarlet_devil_textures import read_png, write_png

ASSETS = "resourcepack/assets/fortuneandfavors"

# type: (vanilla base material, dark, mid, light, accent)
ARMOR = {
    "wither_crown":          ("netherite", (20, 16, 24), (60, 52, 70), (120, 110, 130), (226, 176, 66)),
    "sovereigns_crown":      ("gold", (14, 70, 40), (30, 150, 80), (226, 176, 66), (255, 234, 150)),
    "possessed_mask":        ("iron", (40, 20, 50), (110, 60, 140), (226, 218, 196), (190, 80, 255)),
    "slime_boots":           ("iron", (20, 70, 24), (70, 170, 60), (150, 230, 110), (226, 255, 200)),
    "stoneheart":            ("iron", (50, 48, 52), (110, 106, 108), (170, 166, 160), (190, 120, 255)),
    "glacier_cloak":         ("iron", (40, 90, 150), (120, 180, 230), (220, 245, 255), (255, 255, 255)),
    "mind_shroud":           ("netherite", (30, 10, 46), (90, 40, 140), (170, 110, 230), (240, 200, 255)),
    "automaton_armor":       ("netherite", (70, 44, 18), (150, 100, 40), (220, 170, 80), (120, 220, 255)),
    "astral_mantle":         ("netherite", (12, 16, 48), (40, 60, 150), (120, 160, 240), (255, 255, 255)),
    "colossus_plate":        ("netherite", (36, 32, 30), (90, 84, 78), (150, 142, 132), (255, 140, 60)),
    "sculk_sensor_leggings": ("iron", (10, 30, 40), (20, 80, 100), (60, 180, 190), (150, 255, 240)),
    "warlord_cloak":         ("leather", (70, 10, 16), (150, 24, 34), (210, 70, 70), (226, 176, 66)),
    "evoker_cloak":          ("leather", (20, 50, 30), (40, 100, 60), (90, 160, 100), (226, 176, 66)),
    "illusioner_cloak":      ("leather", (20, 30, 80), (50, 70, 170), (110, 140, 240), (200, 160, 255)),
    "wardens_mantle":        ("netherite", (6, 30, 36), (14, 70, 80), (40, 140, 150), (90, 240, 255)),
    "potion_belt":           ("leather", (60, 36, 20), (120, 76, 40), (180, 130, 80), (210, 80, 230)),
}


def vanilla(path):
    for jar in sorted(glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/*/minecraft-client.jar")), reverse=True):
        with zipfile.ZipFile(jar) as z:
            try:
                return read_png(z.read(path))
            except KeyError:
                continue
    raise SystemExit(f"{path} not found - run a Gradle build once so Loom caches the client jar")


def tone(dark, mid, light, t):
    a, b, u = (dark, mid, t / 0.5) if t < 0.5 else (mid, light, (t - 0.5) / 0.5)
    return tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))


def recolour(rows, dark, mid, light, accent):
    lums = [sum(p[:3]) / 3 for r in rows for p in r if p[3] > 0]
    lo, hi = min(lums), max(lums)
    span = max(1.0, hi - lo)
    out = []
    for r in rows:
        new = []
        for p in r:
            if p[3] == 0:
                new.append(p)
                continue
            t = (sum(p[:3]) / 3 - lo) / span
            new.append((accent if t > 0.9 else tone(dark, mid, light, t)) + (p[3],))
        out.append(new)
    return out


def main():
    os.makedirs(f"{ASSETS}/equipment", exist_ok=True)
    for kind in ("humanoid", "humanoid_leggings"):
        os.makedirs(f"{ASSETS}/textures/entity/equipment/{kind}", exist_ok=True)
    for name, (base, dark, mid, light, accent) in ARMOR.items():
        for kind in ("humanoid", "humanoid_leggings"):
            src = vanilla(f"assets/minecraft/textures/entity/equipment/{kind}/{base}.png")
            write_png(f"{ASSETS}/textures/entity/equipment/{kind}/{name}.png", recolour(src, dark, mid, light, accent))
        layer = [{"texture": f"fortuneandfavors:{name}"}]
        with open(f"{ASSETS}/equipment/{name}.json", "w") as f:
            json.dump({"layers": {"humanoid": layer, "humanoid_leggings": layer}}, f, indent=2)
            f.write("\n")


if __name__ == "__main__":
    main()
