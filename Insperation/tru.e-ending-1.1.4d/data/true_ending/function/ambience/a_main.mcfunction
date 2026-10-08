execute if score ambience trueEnding_settings matches 1..2 as @a[distance=..128] at @s run function true_ending:ambience/particle

execute if score ambience trueEnding_settings matches 1 if predicate true_ending:chance/20_percent run function true_ending:ambience/dragon_entity_summon
execute if score ambience trueEnding_settings matches 1 positioned 0 80 0 in the_end if entity @n[type=ender_dragon,distance=..256] as @e[type=end_crystal,predicate=true_ending:position/the_end,distance=..256] at @s run function true_ending:ambience/end_crystal
execute if score crystal_beacon trueEnding_settings matches 1 positioned 0 80 0 in the_end if entity @n[type=ender_dragon,distance=..256] as @e[type=end_crystal,predicate=true_ending:position/the_end,distance=..256] at @s run particle end_rod ~ ~15 ~ 0 10 0 0.000000000000000000002 2 force @a[distance=..256]
