
tellraw @s [{"translate": " "}]



execute if score pearlbreaking trueEnding_settings matches 1 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"Dropped ender pearl items breaking from an Ender Dragon attack."},{"text":" Implemented to prevent massive farming of ender pearls when endermen are killed by the many new dragon attacks.","color": "gray"}]}},{"translate":"Pearls Break From Attacks: ","color":"gray"},{"translate":"Enabled","color":"green","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/pearlbreaking_0"}}]
execute if score pearlbreaking trueEnding_settings matches 0 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"Dropped ender pearl items breaking from an Ender Dragon attack."},{"text":" Implemented to prevent massive farming of ender pearls when endermen are killed by the many new dragon attacks.","color": "gray"}]}},{"translate":"Pearls Break From Attacks: ","color":"gray"},{"translate":"Disabled","color":"red","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/pearlbreaking_1"}}]



tellraw @s [{"translate": " "}]


execute if score guardphantom trueEnding_settings matches 1 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"The guarding phantom surronding caged towers. This feature also adds 4 extra blocks of obsidian on the corners of the caged towers."}]}},{"translate":"Caged Tower Phantoms: ","color":"gray"},{"translate":"Enabled","color":"green","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/guardphantom_0"}}]
execute if score guardphantom trueEnding_settings matches 0 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"The guarding phantom surronding caged towers. This feature also adds 4 extra blocks of obsidian on the corners of the caged towers."}]}},{"translate":"Caged Tower Phantoms: ","color":"gray"},{"translate":"Disabled","color":"red","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/guardphantom_1"}}]

tellraw @s [{"translate": " "}]



execute if score crystalcount trueEnding_settings matches 1 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"When an End Crystal is destroyed, a text display is shown briefly to players with how many End Crystals are left to destroy"}]}},{"translate":"End Crystal Counter: ","color":"gray"},{"translate":"Enabled","color":"green","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/crystalcount_0"}}]
execute if score crystalcount trueEnding_settings matches 0 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"When an End Crystal is destroyed, a text display is shown briefly to players with how many End Crystals are left to destroy"}]}},{"translate":"End Crystal Counter: ","color":"gray"},{"translate":"Disabled","color":"red","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/crystalcount_1"}}]



tellraw @s [{"translate": " "}]



tellraw @s [{"translate": "","color": "yellow"},{"translate":"<-- Page 2","hover_event":{"action":"show_text","value":[{"translate":"Previous Page"}]},"click_event":{"action":"run_command","command":"/function true_ending:settings/2"}},{"translate":"         - 3/4 -        ","color": "gray","italic": false},{"translate":"Page 4 -->","color":"yellow","underlined":false,"hover_event":{"action":"show_text","value":[{"translate":"Next Page"}]},"click_event":{"action":"run_command","command":"/function true_ending:settings/4"}}]

tellraw @s [{"translate": " "}]
