package com.fortuneandfavors.client;

import com.fortuneandfavors.net.FfJukeboxPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.JukeboxSong;

/** The client half of the Backpack Jukebox: plays the song as a sound that is
 *  glued to the player (relative + no attenuation = you always hear it at full
 *  volume, wherever you go) and loops forever - the client restarts it the
 *  moment the song ends, so nothing else can break or restart the music.
 *
 *  Nearby players receive the same song as a BOOMBOX: a positional instance
 *  that tracks the owner entity with linear falloff (volume 2.0 = 32 blocks),
 *  so it sounds like a real speaker walking around the world.
 *
 *  The server sends the SOUND EVENT id (a plain sound registry entry), so the
 *  music no longer depends on the client being able to resolve the datapack
 *  jukebox-song registry - that lookup is the fallback, not the main path. */
public final class BackpackMusic {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-jukebox");

   private BackpackMusic() {
   }

   /** A song we WANT to hear: the instance is re-created if the engine ever
    *  drops it (sound reload, level change, something else stopping it). */
   private record Desired(String soundId, FollowingSong instance) {
   }

   /** One live sound per boombox: "" is your own, otherwise the owner's UUID. */
   private static final Map<String, Desired> CURRENT = new HashMap<>();

   public static void init() {
      ClientPlayNetworking.registerGlobalReceiver(
         FfJukeboxPayload.TYPE,
         (payload, ctx) -> ctx.client().execute(() -> {
            if (payload.action() == FfJukeboxPayload.ACTION_PLAY) {
               play(payload.owner(), payload.song(), payload.x(), payload.y(), payload.z());
            } else if (payload.action() == FfJukeboxPayload.ACTION_STOP_ALL) {
               stopEverything();
            } else {
               stop(payload.owner());
            }
         })
      );
   }

   /** Client tick: if a boombox we still want is not sounding anymore, start it
    *  again. Keeps the jukebox self-healing instead of silently dead. */
   public static void tick() {
      if (CURRENT.isEmpty()) {
         return;
      }

      for (Map.Entry<String, Desired> entry : new ArrayList<>(CURRENT.entrySet())) {
         Desired desired = entry.getValue();
         FollowingSong instance = desired.instance();
         if (instance.isStopped() || desired.soundId().isEmpty()) {
            SoundInstance stopped = instance;
            play(
               entry.getKey(), desired.soundId(), (float)stopped.getX(), (float)stopped.getY(), (float)stopped.getZ()
            );
         }
      }
   }

   private static void play(String ownerKey, String soundId, float x, float y, float z) {
      stop(ownerKey);
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || soundId == null || soundId.isEmpty()) {
         return;
      }

      SoundEvent sound = resolveSound(soundId);
      if (sound == null) {
         LOGGER.warn("Backpack jukebox: client could not resolve sound '{}'", soundId);
         return;
      }

      try {
         FollowingSong instance;
         if (ownerKey == null || ownerKey.isEmpty()) {
            // You are carrying the boombox - full volume, glued to you.
            instance = new PersonalSong(sound);
         } else {
            // Someone nearby is playing theirs - positional, tracks the owner.
            UUID ownerId = UUID.fromString(ownerKey);
            instance = new BoomboxSong(sound, ownerId, x, y, z);
         }
         mc.getSoundManager().play(instance);
         CURRENT.put(ownerKey == null ? "" : ownerKey, new Desired(soundId, instance));
      } catch (Throwable t) {
         LOGGER.warn("Backpack jukebox: failed to start '{}'", soundId, t);
      }
   }

   /** The server sends a sound event id; older/servers-with-only-songs send a
    *  jukebox song id, so both are accepted. */
   private static SoundEvent resolveSound(String id) {
      try {
         SoundEvent direct = (SoundEvent)BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
         if (direct != null) {
            return direct;
         }
      } catch (Throwable ignored) {
      }

      try {
         ClientLevel level = Minecraft.getInstance().level;
         if (level == null) {
            return null;
         }
         JukeboxSong song = level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).getValue(Identifier.parse(id));
         return song == null ? null : song.soundEvent().value();
      } catch (Throwable t) {
         return null;
      }
   }

   private static void stop(String ownerKey) {
      String key = ownerKey == null ? "" : ownerKey;
      Desired desired = CURRENT.remove(key);
      if (desired != null) {
         Minecraft.getInstance().getSoundManager().stop(desired.instance());
         desired.instance().hush();
      }
   }

   /**
    * Silence every boombox this client is holding, and forget them.
    *
    * <p>Forgetting is the half that matters: {@link #tick} exists to restart a song the engine
    * dropped, so a stopped-but-still-wanted song would be back on the very next tick. With the
    * entries gone there is nothing left to heal, and the music only returns when the server sends
    * a fresh PLAY - which it does once the boss theme that asked for this lets go.
    */
   private static void stopEverything() {
      for (String key : new ArrayList<>(CURRENT.keySet())) {
         Desired desired = CURRENT.remove(key);
         if (desired != null) {
            Minecraft.getInstance().getSoundManager().stop(desired.instance());
            desired.instance().hush();
         }
      }
   }

   /** A jukebox song that auto-restarts when it ends. */
   private static abstract class FollowingSong extends AbstractTickableSoundInstance {
      private boolean hushed;

      private FollowingSong(SoundEvent sound) {
         super(sound, SoundSource.RECORDS, RandomSource.create());
         this.looping = true;
         this.volume = 1.0F;
         this.pitch = 1.0F;
         this.delay = 0;
      }

      void hush() {
         this.hushed = true;
      }

      protected boolean worldGone() {
         return Minecraft.getInstance().level == null;
      }

      @Override
      public void tick() {
         if (this.hushed || worldGone()) {
            this.stop();
         }
      }
   }

   /** The owner's own instance: relative + no attenuation + position (0,0,0)
    *  = dead center in your headphones, and it follows you everywhere by
    *  definition (relative sounds are positioned against the listener).
    *  Never write world coordinates into x/y/z here - with relative=true
    *  they would become a huge listener-relative offset and the music would
    *  pan hard to one side instead of playing in the middle. */
   private static final class PersonalSong extends FollowingSong {
      private PersonalSong(SoundEvent sound) {
         super(sound);
         this.relative = true;
         this.attenuation = SoundInstance.Attenuation.NONE;
         this.x = 0.0;
         this.y = 0.0;
         this.z = 0.0;
      }
   }

   /** Nearby players hear a positional boombox that tracks the owner with
    *  linear falloff - volume 2.0 means an audible radius of 32 blocks. */
   private static final class BoomboxSong extends FollowingSong {
      private final UUID ownerId;

      private BoomboxSong(SoundEvent sound, UUID ownerId, double x, double y, double z) {
         super(sound);
         this.ownerId = ownerId;
         this.attenuation = SoundInstance.Attenuation.LINEAR;
         this.relative = false;
         // 2.0 * 16 = the boombox carries 32 blocks, fading linearly.
         this.volume = 2.0F;
         this.x = x;
         this.y = y;
         this.z = z;
      }

      @Override
      public void tick() {
         super.tick();
         if (this.isStopped()) {
            return;
         }
         ClientLevel level = Minecraft.getInstance().level;
         if (level == null) {
            return;
         }
         // Track the owner every tick so the sound glides with them; until the
         // owner's entity resolves we hold the last known position.
         for (Player p : level.players()) {
            if (p.getUUID().equals(this.ownerId)) {
               this.x = p.getX();
               this.y = p.getY() + 1.0;
               this.z = p.getZ();
               break;
            }
         }
      }
   }
}
