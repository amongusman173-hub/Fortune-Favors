
#if crystals are destroyed
execute as @s[tag=!trueEnding_inattack] if score @s trueEnding_health_percent matches ..999 run scoreboard players set @s trueEnding_bosstime 4000

summon minecraft:marker ~ ~ ~ {Tags:["trueEnding_ultrafireball"]}


execute as @n[type=marker,tag=trueEnding_ultrafireball] at @s run tp @s ~ ~ ~ facing entity @r[gamemode=!spectator,distance=..128] feet

execute unless entity @n[distance=..10,type=dragon_fireball] run playsound minecraft:entity.ender_dragon.shoot hostile @a[distance=..128] ~ ~ ~ 10 1
kill @n[distance=..20,type=dragon_fireball]
