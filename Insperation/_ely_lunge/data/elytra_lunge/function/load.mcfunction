### Elytra Lunge Release 1.1.1 ###
    #updated pack format to 107 to support MC26.2
    #corrected pack.mcmeta to proper syntax to avoid warning

#Thx for downloading my pack! - DRE

#Create the scoreboard to track if the reload message should be sent to in game chat
scoreboard objectives add elytra_lunge_reload_msg dummy
#If the fake player "#elytra_lunge_reload_msg"'s score has not yet been set to a positive integer, set the default config to enable the reload msg (should only run on first startup)
execute unless score #elytra_lunge_reload_msg elytra_lunge_reload_msg matches 0.. run scoreboard players set #elytra_lunge_reload_msg elytra_lunge_reload_msg 1
#If the scoreboard value of the fake player boolean is set to 0, end this load function without showing the reload message
execute if score #elytra_lunge_reload_msg elytra_lunge_reload_msg matches 0 run return fail

#If the function has reached this point, the reload message is enabled - so send out the reload message
tellraw @a [{"color":"#c1c1c1","text":"[Elytra Lunge] successfully loaded!",click_event:{action:"run_command",command:"scoreboard players set #elytra_lunge_reload_msg elytra_lunge_reload_msg 0"},hover_event:{action:"show_text",value:{"text":"click to disable reload msg ♥"}}}]