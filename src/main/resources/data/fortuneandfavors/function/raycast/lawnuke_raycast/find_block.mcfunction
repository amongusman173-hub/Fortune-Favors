# Двигаемся шагами вперёд и ищем блок
execute if block ^ ^ ^0.5 #minecraft:replaceable run function fortuneandfavors:raycast/lawnuke_raycast/find_block_step
execute unless block ^ ^ ^0.5 #minecraft:replaceable run summon minecraft:marker ^ ^ ^0.5 {NoGravity:1b,Invisible:0b,Marker:0b,Tags:["stab1"]}
execute unless block ^ ^ ^0.5 #minecraft:replaceable run function fortuneandfavors:raycast/lawnuke_raycast/run_stab