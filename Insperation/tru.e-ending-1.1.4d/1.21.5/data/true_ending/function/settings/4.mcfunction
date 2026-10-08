

tellraw @s [{"translate": " "}]


execute if score dragontrail trueEnding_settings matches 1 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"The particle trail of the Ender Dragon"}]}},{"translate":"Dragon Trail: ","color":"gray"},{"translate":"Enabled","color":"green","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/dragontrail_0"}}]
execute if score dragontrail trueEnding_settings matches 0 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"The particle trail of the Ender Dragon"}]}},{"translate":"Dragon Trail: ","color":"gray"},{"translate":"Disabled","color":"red","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/dragontrail_1"}}]


tellraw @s [{"translate": " "}]


execute if score crystal_beacon trueEnding_settings matches 1 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"Obsidian tower end crystals are given a white beacon of particles to signal their presence"}]}},{"translate":"End Crystal Beacon: ","color":"gray"},{"translate":"Enabled","color":"green","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/crystal_beacon_0"}}]
execute if score crystal_beacon trueEnding_settings matches 0 run tellraw @s [{"translate": "","color": "gray"},{"translate": "ⓘ ","hover_event":{"action":"show_text","value":[{"translate":"Obsidian tower end crystals are given a white beacon of particles to signal their presence"}]}},{"translate":"End Crystal Beacon: ","color":"gray"},{"translate":"Disabled","color":"red","underlined":true,"hover_event":{"action":"show_text","value":[{"translate":"Click to change this setting"}]},"click_event":{"action":"run_command","command":"/function true_ending:action/crystal_beacon_1"}}]

tellraw @s [{"translate": " "}]



tellraw @s [{"translate": " "}]



tellraw @s [{"translate": " "}]



tellraw @s [{"translate": "","color": "yellow"},{"translate":"<-- Page 3","hover_event":{"action":"show_text","value":[{"translate":"Previous Page"}]},"click_event":{"action":"run_command","command":"/function true_ending:settings/3"}},{"translate":"         - 4/4 -        ","color": "gray","italic": false}]

tellraw @s [{"translate": " "}]
