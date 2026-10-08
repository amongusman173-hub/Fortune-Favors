# Player Raid victory fanfare - runs at the raid center.
effect give @a[distance=..128] hero_of_the_village 600 1 true
summon minecraft:firework_rocket ~ ~1 ~ {Life:0,LifeTime:5,FireworksItem:{id:"minecraft:firework_rocket",count:1,components:{"minecraft:fireworks":{explosions:[{shape:"large_ball",colors:[I;16711680,65280]}]}}}}
