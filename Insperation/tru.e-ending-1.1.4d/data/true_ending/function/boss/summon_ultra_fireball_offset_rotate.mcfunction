
execute store result score randomizer trueEnding_storage run random value 0..10

execute if score randomizer trueEnding_storage matches 0 run tp @s ~ ~ ~ ~ ~-5
execute if score randomizer trueEnding_storage matches 1 run tp @s ~ ~ ~ ~ ~-4.5
execute if score randomizer trueEnding_storage matches 2 run tp @s ~ ~ ~ ~ ~-4
execute if score randomizer trueEnding_storage matches 3 run tp @s ~ ~ ~ ~ ~-3.5
execute if score randomizer trueEnding_storage matches 4 run tp @s ~ ~ ~ ~ ~-3

# execute if score randomizer trueEnding_storage matches 5 run tp @s ~ ~ ~ ~ ~

execute if score randomizer trueEnding_storage matches 6 run tp @s ~ ~ ~ ~ ~3
execute if score randomizer trueEnding_storage matches 7 run tp @s ~ ~ ~ ~ ~3.5
execute if score randomizer trueEnding_storage matches 8 run tp @s ~ ~ ~ ~ ~4
execute if score randomizer trueEnding_storage matches 9 run tp @s ~ ~ ~ ~ ~4.5
execute if score randomizer trueEnding_storage matches 10 run tp @s ~ ~ ~ ~ ~5

#####################

execute store result score randomizer trueEnding_storage run random value 0..10


execute if score randomizer trueEnding_storage matches 0 at @s run tp @s ~ ~ ~ ~-5 ~
execute if score randomizer trueEnding_storage matches 1 at @s run tp @s ~ ~ ~ ~-4.5 ~
execute if score randomizer trueEnding_storage matches 2 at @s run tp @s ~ ~ ~ ~-4 ~
execute if score randomizer trueEnding_storage matches 3 at @s run tp @s ~ ~ ~ ~-3.5 ~
execute if score randomizer trueEnding_storage matches 4 at @s run tp @s ~ ~ ~ ~-3 ~

# execute if score randomizer trueEnding_storage matches 5 run tp @s ~ ~ ~ ~ ~

execute if score randomizer trueEnding_storage matches 6 at @s run tp @s ~ ~ ~ ~3 ~
execute if score randomizer trueEnding_storage matches 7 at @s run tp @s ~ ~ ~ ~3.5 ~
execute if score randomizer trueEnding_storage matches 8 at @s run tp @s ~ ~ ~ ~4 ~
execute if score randomizer trueEnding_storage matches 9 at @s run tp @s ~ ~ ~ ~4.5 ~
execute if score randomizer trueEnding_storage matches 10 at @s run tp @s ~ ~ ~ ~5 ~

