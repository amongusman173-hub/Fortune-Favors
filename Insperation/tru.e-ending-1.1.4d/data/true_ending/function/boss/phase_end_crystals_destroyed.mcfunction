tag @s add trueEnding_inattack
execute if score @s trueEnding_bosstime matches 5000 run data modify entity @s NoAI set value 0b
execute if score @s trueEnding_bosstime matches 5000 run playsound block.glass.break hostile @a[distance=..128] ~ ~ ~ 7 2
execute if score @s trueEnding_bosstime matches 5000 run playsound block.glass.break hostile @a[distance=..128] ~ ~ ~ 7 .5
execute if score @s trueEnding_bosstime matches 5000 run playsound item.trident.return hostile @a[distance=..128] ~ ~ ~ 7 2
execute if score @s trueEnding_bosstime matches 5000 run playsound item.trident.return hostile @a[distance=..128] ~ ~ ~ 7 .7



execute if score @s trueEnding_bosstime matches 5000 as @a[distance=..128] at @s run playsound block.beacon.deactivate hostile @p[distance=..128] ~ ~ ~ 2 .66

execute if score @s trueEnding_bosstime matches 5020 run particle dragon_breath ~ ~1.5 ~ 0 0 0 0.2 100 force @a[distance=..128]
execute if score @s trueEnding_bosstime matches 5020 run particle dragon_breath ~ ~1.5 ~ 0 0 0 0.4 100 force @a[distance=..128]

execute if score @s trueEnding_bosstime matches 5020 run playsound minecraft:entity.ender_dragon.growl hostile @a[distance=..128] ~ ~ ~ 7 .85

execute if score @s trueEnding_bosstime matches 5000 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"gray"}
execute if score @s trueEnding_bosstime matches 5005 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"white"}
execute if score @s trueEnding_bosstime matches 5010 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"gray"}
execute if score @s trueEnding_bosstime matches 5015 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"white"}
execute if score @s trueEnding_bosstime matches 5020 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"gray"}
execute if score @s trueEnding_bosstime matches 5025 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"white"}
execute if score @s trueEnding_bosstime matches 5030 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"gray"}
execute if score @s trueEnding_bosstime matches 5035 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"white"}
execute if score @s trueEnding_bosstime matches 5040 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"gray"}
execute if score @s trueEnding_bosstime matches 5045 run data modify entity @s CustomName set value {"translate":"entity.minecraft.ender_dragon",color:"white"}


execute if score @s trueEnding_bosstime matches 5045 run tag @s remove trueEnding_inattack
execute if score @s trueEnding_bosstime matches 5045 run scoreboard players set @s trueEnding_bosstime 219
