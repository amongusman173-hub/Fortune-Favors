# Устанавливаем начальную позицию (глаза игрока)
execute as @s at @s anchored eyes run function orbital_strike_cannon:raycast/lawnuke_raycast/find_block
execute positioned ~ ~-16 ~ run kill @n[type=fishing_bobber,tag=!spawned,distance=..128]
item replace entity @s weapon with air
execute at @s run playsound minecraft:item.shield.break master @a ~ ~ ~
execute at @s run particle item{item:"fishing_rod"} ^ ^1.5 ^0.5 0.2 .4 0.2 0 5 normal