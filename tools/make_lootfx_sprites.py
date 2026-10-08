#!/usr/bin/env python3
"""GUI sprites for the loot box reveal (white; the client tints them by rarity). Stdlib only.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_lootfx_sprites.py
"""
import math

from make_scarlet_devil_textures import write_png

OUT = "resourcepack/assets/fortuneandfavors/textures/gui/sprites/lootfx"


def sprite(n, alpha_at):
    c = (n - 1) / 2.0
    return [[(255, 255, 255, int(max(0.0, min(1.0, alpha_at((x - c) / c, (y - c) / c))) * 255)) for x in range(n)] for y in range(n)]


def rays(u, v):
    # Twelve soft rays, fading out toward the edge.
    d = math.hypot(u, v)
    if d > 1.0:
        return 0.0
    a = math.atan2(v, u)
    beam = max(0.0, math.cos(a * 6.0)) ** 6
    return beam * (1.0 - d) ** 0.8


def glow(u, v):
    d = math.hypot(u, v)
    return (1.0 - d) ** 2 if d < 1.0 else 0.0


def star(u, v):
    arms = max(1.0 - abs(v) * 7.0 - abs(u), 1.0 - abs(u) * 7.0 - abs(v), 0.0)
    return min(1.0, arms * 1.4 + glow(u * 2.5, v * 2.5))


def main():
    write_png(f"{OUT}/rays.png", sprite(64, rays))
    write_png(f"{OUT}/glow.png", sprite(32, glow))
    write_png(f"{OUT}/star.png", sprite(16, star))


if __name__ == "__main__":
    main()
