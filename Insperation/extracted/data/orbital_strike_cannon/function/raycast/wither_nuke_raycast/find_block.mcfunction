execute if block ^ ^ ^0.5 air run function orbital_strike_cannon:raycast/wither_nuke_raycast/find_block_step
execute unless block ^ ^ ^0.5 air run summon minecraft:marker ^ ^ ^0.5 {NoGravity:1b,Invisible:0b,Marker:0b,Tags:["nuke1"]}
execute unless block ^ ^ ^0.5 air run function orbital_strike_cannon:raycast/wither_nuke_raycast/run_wither_nuke