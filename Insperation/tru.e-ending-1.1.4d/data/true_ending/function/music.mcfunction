scoreboard players add @s trueEnding_music 1

execute if score @s trueEnding_music matches 1 run function true_ending:music_play with storage true_ending:storage settings

execute positioned 0 80 0 unless entity @s[distance=..128,predicate=true_ending:position/the_end] run function true_ending:music_stop
execute positioned 0 80 0 unless entity @e[type=ender_dragon,distance=..256] run function true_ending:music_stop


execute if score @s trueEnding_music matches 4100..4105 run scoreboard players set @s trueEnding_music 0


# ender dragon music video

    # kill @e[distance=..12,type=area_effect_cloud]

    # execute if score @s trueEnding_music matches 600 run data modify entity @n[type=ender_dragon] Health set value 150

    # execute if score @s trueEnding_music matches 1800 run data modify entity @n[type=ender_dragon] Health set value 50

    # execute if score @s trueEnding_music matches 3000 run data modify entity @n[type=ender_dragon] Health set value 30

    # execute if score @s trueEnding_music matches 3500 run data modify entity @n[type=ender_dragon] DragonPhase set value 3 
    # execute if score @s trueEnding_music matches 3750 run item replace entity @n[type=ender_dragon] weapon with air
    # execute if score @s trueEnding_music matches 3750.. as @n[type=ender_dragon] at @s run tp @s ~ ~ ~ ~0.5 ~

    # execute if score @s trueEnding_music matches 4100 as @n[type=ender_dragon] at @s run function true_ending:other/kill_dragon




