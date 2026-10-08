package com.fortuneandfavors.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/**
 * A boss theme takes the room's music - all of it.
 *
 * <p>Both reworked fights have a soundtrack, and both of them were being played <i>over</i>
 * whatever else the game had running: the End's own ambient score, a record in a jukebox, and the
 * mod's Backpack Jukebox - which loops itself and re-issues itself every tick, so it is the one
 * piece of "other music" that will not stay stopped on its own. A boss fight that has to compete
 * with three other songs is a fight nobody is listening to.
 *
 * <p>So a theme claims the player the moment it starts: every sound on the two sources music can
 * live on is stopped, and the mod's own music systems are told to stand down until the claim is
 * handed back. The claim is held for the length of the cut that made it and refreshed while the
 * cut plays, so it survives a loop, a track change and a death cut that outlives its own fight.
 *
 * <p>Two sources, not one, because music in this game is split across them: the game's own
 * soundtrack plays on {@code MUSIC} and a record (a placed jukebox, the backpack's boombox) plays
 * on {@code RECORDS}. Claiming only one of them is how a theme ends up playing over the other.
 *
 * <p>The stop is deliberately sent <b>once</b>, before the theme's own first sound for that
 * player: a stop-all on the source a boss theme sits on would otherwise silence the boss theme
 * too, which is the one thing this must never do. That ordering is why {@link #takeOver} is called
 * before the theme is served rather than after it.
 */
public final class BossMusic {
   /**
    * Players whose music currently belongs to a boss theme, and the ticks left of that claim.
    *
    * <p>Ticks rather than a deadline, because the callers measure in their own clocks and a claim
    * is only ever "this much longer". A claim that has run out is the same thing as a fight that
    * has ended, so the countdown is also the release - there is no second piece of state to keep
    * in step with it.
    */
   private static final Map<UUID, Integer> HELD = new HashMap<>();

   /** How long a teardown gives a claim before it must hand the music back. */
   private static final int RELEASE_GRACE_TICKS = 40;

   private BossMusic() {
   }

   /**
    * A boss theme is playing for this player, or is about to: hold their music for at least
    * {@code holdTicks} more ticks.
    *
    * <p>Every extension after the first is silent. That is the whole reason the claim is a
    * countdown rather than a boolean: the first call silences the world, and the calls that follow
    * it - one per tick while the fight runs, one per loop of the theme - only keep the claim open.
    * Re-sending the stop on every one of them would be the boss theme silencing itself on the
    * second tick of its own fight.
    */
   public static void takeOver(ServerPlayer p, int holdTicks) {
      if (p == null) {
         return;
      }
      int want = Math.max(1, holdTicks);
      Integer existing = HELD.get(p.getUUID());
      if (existing != null) {
         if (existing < want) {
            HELD.put(p.getUUID(), want);
         }
         return;
      }
      HELD.put(p.getUUID(), want);
      silence(p);
   }

   /** Is this player's music currently owned by a boss theme? Asked by the mod's own music. */
   public static boolean holds(ServerPlayer p) {
      return p != null && HELD.containsKey(p.getUUID());
   }

   /** Hand the music back now - the fight ended, or this player left it. */
   public static void release(ServerPlayer p) {
      if (p != null && HELD.remove(p.getUUID()) != null) {
         BackpackJukebox.resumeAfterBossMusic(p);
      }
   }

   /**
    * Tick every claim down, and hand the music back when one runs out.
    *
    * <p>The one exit that works without anybody remembering to call it: a death cut that plays on
    * after its fight has finished is a claim whose last extension is the whole length of the cut,
    * so it expires exactly when the music does.
    */
   public static void tick(MinecraftServer server) {
      if (server == null || HELD.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Integer>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<UUID, Integer> e = it.next();
         int left = e.getValue() - 1;
         if (left > 0) {
            e.setValue(left);
            continue;
         }
         it.remove();
         ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
         if (p != null) {
            BackpackJukebox.resumeAfterBossMusic(p);
         }
      }
   }

   /**
    * A teardown with no theme left to wait for: cap every claim at a moment rather than at the
    * length of a cut that will never be heard, so nobody is left hushed by music that stopped.
    */
   public static void fadeOutAll() {
      for (Map.Entry<UUID, Integer> e : new ArrayList<>(HELD.entrySet())) {
         if (e.getValue() > RELEASE_GRACE_TICKS) {
            HELD.put(e.getKey(), RELEASE_GRACE_TICKS);
         }
      }
   }

   /**
    * Stop everything on the two sources music can live on, for one player.
    *
    * <p>An id-less stop packet means "all sounds in this source", which is the only instrument
    * that reaches a song the server never started: a disc someone dropped into a jukebox, the
    * game's own soundtrack, another mod's music. It cannot reach the mod's Backpack Jukebox on its
    * own - that one loops and re-issues itself - so the boombox is hushed separately.
    */
   private static void silence(ServerPlayer p) {
      try {
         p.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));
         p.connection.send(new ClientboundStopSoundPacket(null, SoundSource.RECORDS));
      } catch (Throwable ignored) {
      }
      BackpackJukebox.hushForBossMusic(p);
   }
}
