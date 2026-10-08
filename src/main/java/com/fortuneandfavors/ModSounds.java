package com.fortuneandfavors;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

public final class ModSounds {
   public static final SoundEvent SELL = SoundEvents.EXPERIENCE_ORB_PICKUP;
   public static final SoundEvent BUY = SoundEvents.ITEM_PICKUP;
   public static final SoundEvent AUCTION_BID = (SoundEvent)SoundEvents.UI_BUTTON_CLICK.value();
   public static final SoundEvent TRANSFER = (SoundEvent)SoundEvents.NOTE_BLOCK_PLING.value();
   public static final SoundEvent ELEVATOR = SoundEvents.ENDERMAN_TELEPORT;
   public static final SoundEvent AUCTION_CREATE = SoundEvents.CHEST_OPEN;
   public static final SoundEvent SHOP_CREATE = SoundEvents.VILLAGER_YES;
   public static final SoundEvent PAGE_FLIP = SoundEvents.BOOK_PAGE_TURN;
   public static final SoundEvent CLAIM = SoundEvents.BEACON_ACTIVATE;
   public static final SoundEvent DENY = SoundEvents.VILLAGER_NO;
   public static final SoundEvent WORMHOLE_OPEN = SoundEvents.PORTAL_TRIGGER;
   public static final SoundEvent JOB_COMPLETE = SoundEvents.PLAYER_LEVELUP;
   public static final SoundEvent BOUNTY = SoundEvents.ZOMBIE_VILLAGER_CURE;
   public static final SoundEvent BOUNTY_CLAIMED = SoundEvents.ANVIL_USE;
   public static final SoundEvent SKILL_UP = SoundEvents.PLAYER_LEVELUP;
   public static final SoundEvent MYSTERY = SoundEvents.AMETHYST_BLOCK_CHIME;
   public static final SoundEvent BOSS_SPAWN = SoundEvents.WITHER_SPAWN;
   public static final SoundEvent BOSS_DEATH = SoundEvents.ENDER_DRAGON_GROWL;
   public static final SoundEvent BOSS_SLAM = SoundEvents.WITHER_SHOOT;
   public static final SoundEvent LOOT_TICK = (SoundEvent)SoundEvents.NOTE_BLOCK_PLING.value();
   public static final SoundEvent LOOT_REVEAL = SoundEvents.PLAYER_LEVELUP;
   public static final SoundEvent LOOT_WHOOSH = SoundEvents.BREEZE_WHIRL;
   public static final SoundEvent FORGE_ANVIL = SoundEvents.ANVIL_USE;
   public static final SoundEvent FORGE_SUCCESS = (SoundEvent)SoundEvents.NOTE_BLOCK_PLING.value();
   public static final SoundEvent FORGE_BURN = SoundEvents.FIRE_AMBIENT;
   public static final SoundEvent GOLEM_CRACK = SoundEvents.STONE_BREAK;
   public static final SoundEvent GOLEM_HIT = SoundEvents.STONE_HIT;
   public static final SoundEvent GOLEM_PLACE = SoundEvents.STONE_PLACE;
   public static final SoundEvent GOLEM_STEP = SoundEvents.STONE_STEP;
   public static final SoundEvent GOLEM_RUMBLE = SoundEvents.DEEPSLATE_BREAK;
   public static final SoundEvent GOLEM_GRAVEL = SoundEvents.GRAVEL_BREAK;
   public static final SoundEvent BACKPACK = SoundEvents.BUNDLE_INSERT;
   public static final SoundEvent BACKPACK_CLOSE = SoundEvents.BUNDLE_REMOVE_ONE;
   /**
    * The End's boss theme - the one sound in the mod that is a piece of music rather than an
    * effect.
    *
    * <p>It lives in the art pack ({@code resourcepack/assets/fortuneandfavors/sounds/
    * ender_dragon_theme.ogg}, declared in that folder's {@code sounds.json}) so it ships inside
    * the jar and inside {@code fortuneandfavors-resourcepack.zip} at the same time - the same
    * asset, reachable by a server that installs the pack and by a client that gets it from the
    * mod's always-on pack. It is created as a variable-range event rather than registered in the
    * sound registry, because a sound our own code plays by packet needs no registry entry - and a
    * registry entry that is never filled is a sound that is silent on a dedicated server.
    */
   public static final SoundEvent ENDER_DRAGON_THEME =
      SoundEvent.createVariableRangeEvent(com.fortuneandfavors.FortuneFavorsMod.id("ender_dragon_theme"));

   /**
    * The End's own opening theme: the track a body hears the first time it steps through the portal.
    *
    * <p>It lives in the art pack beside the dragon's theme
    * ({@code resourcepack/assets/fortuneandfavors/sounds/aria_math_epic.ogg}, declared in that
    * folder's {@code sounds.json}) and it is created as a variable-range event rather than registered
    * for the same reason: a sound this mod plays itself needs no registry entry, and a registry entry
    * nothing fills is silence on a dedicated server.
    *
    * <p>Played once, never looped, and let go the moment the dragon is on the field - see
    * {@code economy.EndIntroMusic} for the cue and {@code client.EndIntroMusic} for the fade.
    */
   public static final SoundEvent ARIA_MATH_EPIC =
      SoundEvent.createVariableRangeEvent(com.fortuneandfavors.FortuneFavorsMod.id("aria_math_epic"));

   /**
    * The reworked Wither's theme, as three cuts of one track.
    *
    * <p>Minecraft plays a sound file, never a range inside one, so "0-20s is the summon, 21s-2:32
    * loops the fight and 2:33 on is the death" has to be three files: {@link #WITHERED_INTRO} for
    * the charge-up, {@link #WITHERED_LOOP} for the body of the fight, and {@link #WITHERED_DEATH}
    * for the ceremony. They are variable-range events for the same reason the dragon's theme is -
    * a sound the mod itself plays by packet needs no registry entry, and a registry entry nothing
    * fills is silence on a dedicated server.
    *
    * <p>The client's player crossfades between them ({@code client.WitherMusic}), which is why the
    * cuts have to line up: the intro ends where the loop begins and the loop ends where the death
    * begins, so an overlap is a seam rather than two songs fighting.
    */
   public static final SoundEvent WITHERED_INTRO =
      SoundEvent.createVariableRangeEvent(com.fortuneandfavors.FortuneFavorsMod.id("withered_intro"));
   public static final SoundEvent WITHERED_LOOP =
      SoundEvent.createVariableRangeEvent(com.fortuneandfavors.FortuneFavorsMod.id("withered_loop"));
   public static final SoundEvent WITHERED_DEATH =
      SoundEvent.createVariableRangeEvent(com.fortuneandfavors.FortuneFavorsMod.id("withered_death"));

   private ModSounds() {
   }
}
