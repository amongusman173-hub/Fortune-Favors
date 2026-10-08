# Двигаемся шагами вперёд и ищем блок
execute if block ^ ^ ^0.5 air run function orbital_strike_cannon:raycast/stab_raycast_power_1/find_block_step
execute unless block ^ ^ ^0.5 air run summon minecraft:marker ^ ^ ^0.5 {NoGravity:1b,Invisible:0b,Marker:0b,Tags:["stab1"]}
execute unless block ^ ^ ^0.5 air run function orbital_strike_cannon:raycast/stab_raycast_power_1/run_stab