package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.net.FfJukeboxPayload;
import com.fortuneandfavors.util.Chat;
import com.mojang.serialization.DataResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fortuneandfavors.net.FfNet;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.component.CustomData;

/** The Backpack Jukebox - MUSIC on the go.
 *
 *  A music disc put into the backpack's jukebox slot disappears into the pack
 *  (stored in its custom data, so it survives relogs and the disc comes back
 *  when the jukebox is clicked again). The song then plays as a
 *  player-following sound: the modded client plays it relative to the listener
 *  with looping on, so the music never breaks or restarts - only the song's
 *  own end restarts it, seamlessly, forever, until the disc is ejected.
 *
 *  It is a real boombox, too: players within ~32 blocks of the owner receive
 *  the same broadcast as a positional sound that tracks the owner with linear
 *  volume falloff. Walking out of range fades them out (a little hysteresis
 *  keeps the edge from flickering); walking back in restarts the song. */
public final class BackpackJukebox {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-jukebox");

   private BackpackJukebox() {
   }

   /** Tag key on the backpack's CUSTOM_DATA holding the loaded disc. */
   private static final String DISC_KEY = "ff_disc";

   /** Players within this range start hearing the boombox... */
   private static final double SEND_RANGE_SQR = 40.0 * 40.0;
   /** ...and only stop once they are beyond this (hysteresis). */
   private static final double STOP_RANGE_SQR = 48.0 * 48.0;
   /** How often the nearby-audience list is refreshed. */
   private static final long BROADCAST_INTERVAL_TICKS = 20L;
   /** The volume a vanilla/Bedrock listener hears the boombox at: 2.0 = 32 blocks. */
   private static final float NATIVE_VOLUME = 2.0F;

   private record Playing(ItemStack disc, Holder<JukeboxSong> song, Set<UUID> listeners, Map<UUID, Long> carried) {
   }

   private static final Map<UUID, Playing> PLAYING = new HashMap<>();

   /** Ticks a player's backpack has been unaccounted for. The pack can briefly
    *  live outside the inventory (open in a menu, being moved, crossing a
    *  dimension), and stopping the music on the very first tick made it look
    *  like nothing ever played - so the music only dies after a real gap. */
   private static final Map<UUID, Integer> MISSING_TICKS = new HashMap<>();
   private static final int LOST_GRACE_TICKS = 200;

   /** Any music disc - vanilla or datapack. */
   public static boolean isDisc(ItemStack stack) {
      return stack != null && !stack.isEmpty() && stack.has(DataComponents.JUKEBOX_PLAYABLE);
   }

   /** The disc currently stored inside the backpack (EMPTY if none). */
   public static ItemStack storedDisc(ItemStack backpack, net.minecraft.core.HolderLookup.Provider holders) {
      if (backpack == null || backpack.isEmpty() || !ModItems.isBackpack(backpack)) {
         return ItemStack.EMPTY;
      }
      CustomData custom = (CustomData)backpack.get(DataComponents.CUSTOM_DATA);
      if (custom == null) {
         return ItemStack.EMPTY;
      }
      Tag discTag = custom.copyTag().get(DISC_KEY);
      if (discTag == null) {
         return ItemStack.EMPTY;
      }
      return ItemStack.OPTIONAL_CODEC
         .parse(RegistryOps.create(NbtOps.INSTANCE, holders), discTag)
         .result()
         .orElse(ItemStack.EMPTY);
   }

   /** Stores (or clears, with EMPTY) the disc inside the backpack. */
   public static void setStoredDisc(ItemStack backpack, ItemStack disc, net.minecraft.core.HolderLookup.Provider holders) {
      if (backpack == null || backpack.isEmpty()) {
         return;
      }
      CustomData existing = (CustomData)backpack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag tag = existing.copyTag();
      if (disc == null || disc.isEmpty()) {
         tag.remove(DISC_KEY);
      } else {
         DataResult<Tag> res = ItemStack.OPTIONAL_CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, holders), disc);
         res.result().ifPresent(t -> tag.put(DISC_KEY, t));
      }
      backpack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   /** Put a disc into the jukebox: the disc disappears into the backpack and
    *  the music starts. Consumes one disc from the carried stack. */
   public static void insert(ServerPlayer player, ItemStack backpack, ItemStack carriedDisc) {
      ItemStack disc = carriedDisc.copyWithCount(1);
      setStoredDisc(backpack, disc, player.registryAccess());
      if (carriedDisc.getCount() <= 1) {
         carriedDisc.setCount(0);
      } else {
         carriedDisc.shrink(1);
      }

      start(player, disc);
      // A boss theme owns the music right now, so the disc is loaded but not spinning. Saying so
      // is the difference between "the jukebox is broken" and "the jukebox is waiting its turn".
      Chat.raw(player, BossMusic.holds(player)
         ? "&b\u266a " + disc.getHoverName().getString() + "&b is loaded - it starts when the boss music lets go."
         : "&b\u266a " + disc.getHoverName().getString() + "&b is spinning - the music follows you, and everyone nearby can hear it.");
   }

   /** Click the jukebox again: ejects the disc (stops the music if it is the
    *  one playing) and hands it back. */
   public static ItemStack eject(ServerPlayer player, ItemStack backpack) {
      ItemStack disc = storedDisc(backpack, player.registryAccess());
      if (disc.isEmpty()) {
         return ItemStack.EMPTY;
      }
      Playing playing = PLAYING.get(player.getUUID());
      if (playing != null && ItemStack.isSameItemSameComponents(playing.disc(), disc)) {
         stopPlayback(player, playing);
      }
      setStoredDisc(backpack, ItemStack.EMPTY, player.registryAccess());
      return disc;
   }

   /** Resumes playback of the backpack's stored disc - without restarting a
    *  song that is already spinning (so reopening the backpack never breaks
    *  or restarts the music). */
   public static void ensurePlaying(ServerPlayer player, ItemStack backpack) {
      ItemStack disc = storedDisc(backpack, player.registryAccess());
      if (disc.isEmpty()) {
         return;
      }
      Playing playing = PLAYING.get(player.getUUID());
      if (playing != null && ItemStack.isSameItemSameComponents(playing.disc(), disc)) {
         return;
      }
      start(player, disc);
   }

   /** Stops this player's boombox entirely - the owner and every nearby
    *  listener fade out. */
   public static void stop(ServerPlayer player) {
      MISSING_TICKS.remove(player.getUUID());
      Playing playing = PLAYING.remove(player.getUUID());
      if (playing != null) {
         stopPlayback(player, playing);
      }
   }

   /**
    * Does this listener need the song as a plain vanilla sound rather than as our payload?
    *
    * <p>That is exactly the clients the payload cannot reach: a Bedrock client translated by
    * Geyser, a vanilla client, and a modded client whose channel never registered. Asking
    * {@code FfNet.send} is the only honest way to know, and this is the answer it gives -
    * written out so the rule can be pinned without a connection to send on. Every send in
    * this file reads it, so there is no path that plays the song twice or not at all.
    */
   public static boolean shouldServeNative(boolean payloadSent) {
      return !payloadSent;
   }

   /**
    * Per-player tick: keep every vanilla-served listener's song going one song-length at a
    * time, refresh the nearby audience, and stop only when the backpack carrying the disc
    * has really been gone for a while (dropped, traded, destroyed).
    *
    * <p>The exception is a boss theme: while one owns the music there is nothing to keep going,
    * because the boombox was hushed the moment the theme started and must stay hushed until the
    * theme lets go. The accounting still runs, so a backpack that really is lost during a fight
    * is still noticed - only the sound is held back.
    */
   public static void tick(ServerPlayer player) {
      Playing playing = PLAYING.get(player.getUUID());
      if (playing == null) {
         MISSING_TICKS.remove(player.getUUID());
         return;
      }

      if (!BossMusic.holds(player)) {
         repeatCarried(player, playing);

         if (player.level().getGameTime() % BROADCAST_INTERVAL_TICKS == 0L) {
            broadcast(player, playing);
         }
      }

      if (carriesLoadedJukebox(player)) {
         MISSING_TICKS.remove(player.getUUID());
         return;
      }
      int missing = MISSING_TICKS.merge(player.getUUID(), 1, Integer::sum);
      if (missing >= LOST_GRACE_TICKS) {
         LOGGER.info("Backpack jukebox: {} no longer carries a loaded backpack - stopping playback", player.getName().getString());
         stop(player);
      }
   }

   /**
    * Keeps the vanilla-served copies of the song spinning.
    *
    * <p>A sound packet is a one-shot: it plays the record once and stops. The modded
    * client loops its own instance instead, so this only exists for the listeners the
    * payload could not reach - and it is driven by the song's own length rather than a
    * fixed guess, so a datapack disc of any duration repeats at the seam. Walking out of
    * earshot silences their copy rather than leaving a record playing in the distance.
    */
   private static void repeatCarried(ServerPlayer owner, Playing playing) {
      if (playing.carried().isEmpty()) {
         return;
      }

      MinecraftServer server = owner.level().getServer();
      if (server == null) {
         return;
      }

      long now = owner.level().getGameTime();
      for (Map.Entry<UUID, Long> e : List.copyOf(playing.carried().entrySet())) {
         ServerPlayer lp = server.getPlayerList().getPlayer(e.getKey());
         if (lp == null || lp.level() != owner.level() || lp.distanceToSqr(owner) > STOP_RANGE_SQR) {
            stopNative(lp, playing);
            continue;
         }
         if (BossMusic.holds(lp)) {
            // A boss theme owns their music: their copy of the song is taken off rather than
            // repeated, and the owner's next broadcast hands it back once the theme lets go.
            stopNative(lp, playing);
            continue;
         }

         if (now >= e.getValue()) {
            serveNative(lp, playing);
         }
      }
   }

   /** Does this player still carry (or have open) a backpack with a disc in
    *  its jukebox? Hands are checked explicitly - a boombox switched into the
    *  OFF hand used to be invisible to the old inventory-only scan, which
    *  silently killed the music on the next tick. */
   private static boolean carriesLoadedJukebox(ServerPlayer player) {
      if (hasStoredDisc(player.getMainHandItem()) || hasStoredDisc(player.getOffhandItem())) {
         return true;
      }

      Inventory inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (hasStoredDisc(inv.getItem(i))) {
            return true;
         }
      }

      // Opened from a placed backpack (or mid-menu swap): the live stack is in
      // the menu, not the player's inventory, so keep the music alive for it.
      if (player.containerMenu instanceof com.fortuneandfavors.menu.BackpackMenu menu && hasStoredDisc(menu.jukeboxStack())) {
         return true;
      }

      return false;
   }

   private static boolean hasStoredDisc(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !ModItems.isBackpack(stack)) {
         return false;
      }
      CustomData custom = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return custom != null && custom.copyTag().contains(DISC_KEY);
   }

   private static void start(ServerPlayer player, ItemStack disc) {
      Holder<JukeboxSong> song = JukeboxSong.fromStack(disc).orElse(null);
      if (song == null) {
         LOGGER.warn("Backpack jukebox: {} has no jukebox song for {}", player.getName().getString(), disc.getHoverName().getString());
         return;
      }
      String soundId = soundEventId(song);
      if (soundId.isEmpty()) {
         LOGGER.warn("Backpack jukebox: could not resolve a sound event for song {}", songIdStr(song));
         return;
      }
      Playing previous = PLAYING.put(player.getUUID(), new Playing(disc.copy(), song, new HashSet<>(), new HashMap<>()));
      if (previous != null) {
         // Song switched: fade the old audience out, the fresh broadcast below
         // brings everyone back in range onto the new song.
         hushAudience(player, previous);
      }

      // The owner carries the boombox - full volume, glued to them. The
      // payload carries the SOUND EVENT id (not the song id): clients resolve
      // it from the plain sound registry, which avoids the datapack jukebox
      // lookup that could silently fail and kill the music.
      Playing playing = PLAYING.get(player.getUUID());
      if (BossMusic.holds(player)) {
         // A boss theme owns the music right now: the disc is loaded and remembered, but the song
         // does not start over the fight. It is handed back when the claim runs out - see
         // resumeAfterBossMusic - and the "now playing" line waits with it, because a toast for
         // music nobody can hear is a toast about nothing.
         return;
      }
      boolean sent = FfNet.send(player, FfJukeboxPayload.play(soundId, "", (float)player.getX(), (float)player.getY(), (float)player.getZ()));
      if (shouldServeNative(sent)) {
         // This client has no Fortune & Favors channel to play it on: a Bedrock client
         // behind Geyser, a vanilla client, or a modded one whose channel never
         // registered. They get the song as a real sound instead of silence. The old
         // build sent the payload and hoped, which is why the boombox was inaudible to
         // everyone who was not on a matching client.
         serveNative(player, playing);
      }
      player.sendSystemMessage(Component.literal("\u266a \u00a77Now playing: \u00a7b" + song.value().description().getString()), true);
      broadcast(player, playing);
   }

   /**
    * Plays the song to one player as a plain vanilla sound.
    *
    * <p>A {@code ClientboundSoundPacket} needs nothing from the client but the vanilla
    * sound registry, so it is heard by vanilla clients, by Bedrock clients translated by
    * Geyser, and by a modded client that cannot decode our payload. It does not loop by
    * itself the way the mod's own sound instance does, so the tick keeps re-issuing it a
    * song-length at a time - see {@link #tick}.
    */
   private static void serveNative(ServerPlayer p, Playing playing) {
      if (p == null || playing == null) {
         return;
      }

      try {
         MinecraftServer server = p.level().getServer();
         UUID ownerId = ownerOf(playing);
         ServerPlayer owner = server == null || ownerId == null ? null : server.getPlayerList().getPlayer(ownerId);
         double x = owner != null ? owner.getX() : p.getX();
         double y = owner != null ? owner.getY() : p.getY();
         double z = owner != null ? owner.getZ() : p.getZ();
         long seed = p.level().getRandom().nextLong();
         p.connection.send(new ClientboundSoundPacket(
            playing.song().value().soundEvent(), SoundSource.RECORDS, x, y, z, NATIVE_VOLUME, 1.0F, seed
         ));
         playing.carried().put(p.getUUID(), p.level().getGameTime() + Math.max(20, playing.song().value().lengthInTicks()));
      } catch (Throwable t) {
         LOGGER.warn("Backpack jukebox: could not play '{}' for {}", songIdStr(playing.song()), p.getName().getString(), t);
      }
   }

   /** Silences one player's vanilla copy of the song. */
   private static void stopNative(ServerPlayer p, Playing playing) {
      if (p == null) {
         return;
      }

      playing.carried().remove(p.getUUID());

      try {
         String soundId = soundEventId(playing.song());
         if (!soundId.isEmpty()) {
            p.connection.send(new ClientboundStopSoundPacket(Identifier.parse(soundId), SoundSource.RECORDS));
         }
      } catch (Throwable ignored) {
      }
   }

   /** Which player a boombox belongs to, by uuid, so a repeat can be re-anchored to them. */
   private static UUID ownerOf(Playing playing) {
      for (Map.Entry<UUID, Playing> e : PLAYING.entrySet()) {
         if (e.getValue() == playing) {
            return e.getKey();
         }
      }

      return null;
   }

   /**
    * Sends PLAY to nearby players who just came in range and STOP to those
    * who wandered off. A listener whose client cannot take the payload is
    * served the song as a plain vanilla sound instead, so the boombox is
    * audible to a Geyser/Bedrock client and to a vanilla one.
    */
   private static void broadcast(ServerPlayer owner, Playing playing) {
      MinecraftServer server = owner.level().getServer();
      if (server == null || playing == null) {
         return;
      }
      String songId = soundEventId(playing.song());
      Set<UUID> wanted = new HashSet<>();

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p == owner || p.level() != owner.level()) {
            continue;
         }
         double distSqr = p.distanceToSqr(owner);
         if (distSqr > SEND_RANGE_SQR) {
            continue;
         }
         if (BossMusic.holds(p)) {
            // Their music belongs to a boss theme right now, so they are not a listener of this
            // boombox at all: nothing to send, nothing to remember, and the next broadcast after
            // the theme ends is what puts them back in earshot.
            continue;
         }
         wanted.add(p.getUUID());
         if (playing.carried().containsKey(p.getUUID())) {
            // Already hearing the vanilla copy of it; the repeat in tick() keeps it going.
            continue;
         }
         if (!playing.listeners().add(p.getUUID())) {
            continue;
         }
         boolean sent = FfNet.send(
            p,
            FfJukeboxPayload.play(songId, owner.getUUID().toString(), (float)owner.getX(), (float)owner.getY(), (float)owner.getZ())
         );
         if (shouldServeNative(sent)) {
            playing.listeners().remove(p.getUUID());
            serveNative(p, playing);
         }
      }

      for (UUID listener : List.copyOf(playing.listeners())) {
         if (wanted.contains(listener)) {
            continue;
         }
         playing.listeners().remove(listener);
         ServerPlayer lp = server.getPlayerList().getPlayer(listener);
         if (lp != null) {
            FfNet.send(lp, FfJukeboxPayload.stop(owner.getUUID().toString()));
         }
      }
   }

   /**
    * Stand the boombox down because a boss theme has taken the music.
    *
    * <p>Three things, and all three are needed. The payload is what reaches a modded client's own
    * looping song - a stop-all, because the client keeps its boombox alive by restarting it, and a
    * per-song stop would only be undone on the next tick. The listener set says the server has
    * stopped treating this player as an audience member at all, so nothing re-sends them the song
    * while the fight runs. And the native set reaches a vanilla or Bedrock client, whose copy is a
    * plain sound the payload could never have stopped.
    */
   public static void hushForBossMusic(ServerPlayer player) {
      if (player == null) {
         return;
      }
      FfNet.send(player, FfJukeboxPayload.stopAll());
      for (Playing playing : PLAYING.values()) {
         playing.listeners().remove(player.getUUID());
         if (playing.carried().remove(player.getUUID()) != null) {
            stopNative(player, playing);
         }
      }
   }

   /**
    * Hand a player's boombox back once the boss theme has let go.
    *
    * <p>Only the owner needs this. A listener is put back in earshot by the owner's own broadcast
    * on its next beat - that is what the broadcast loop is for - but there is no such beat for the
    * person carrying the boombox, so their copy of the song is re-issued here.
    */
   public static void resumeAfterBossMusic(ServerPlayer player) {
      if (player == null) {
         return;
      }
      Playing playing = PLAYING.get(player.getUUID());
      if (playing == null) {
         return;
      }
      boolean sent = FfNet.send(player, FfJukeboxPayload.play(
         soundEventId(playing.song()), "", (float)player.getX(), (float)player.getY(), (float)player.getZ()
      ));
      if (shouldServeNative(sent)) {
         serveNative(player, playing);
      }
      broadcast(player, playing);
   }

   /** STOP for the owner + everyone currently listening, however they were being served. */
   private static void stopPlayback(ServerPlayer owner, Playing playing) {
      PLAYING.remove(owner.getUUID());
      FfNet.send(owner, FfJukeboxPayload.stop(""));
      if (playing.carried().containsKey(owner.getUUID())) {
         stopNative(owner, playing);
      }
      hushAudience(owner, playing);
   }

   private static void hushAudience(ServerPlayer owner, Playing playing) {
      MinecraftServer server = owner.level().getServer();
      if (server == null) {
         playing.listeners().clear();
         playing.carried().clear();
         return;
      }

      String ownerKey = owner.getUUID().toString();
      for (UUID listener : List.copyOf(playing.listeners())) {
         ServerPlayer lp = server.getPlayerList().getPlayer(listener);
         if (lp != null) {
            FfNet.send(lp, FfJukeboxPayload.stop(ownerKey));
         }
      }
      playing.listeners().clear();

      for (UUID listener : List.copyOf(playing.carried().keySet())) {
         stopNative(server.getPlayerList().getPlayer(listener), playing);
      }
      playing.carried().clear();
   }

   private static String songIdStr(Holder<JukeboxSong> song) {
      Identifier id = song.unwrapKey().map(key -> key.identifier()).orElse(null);
      return id == null ? "" : id.toString();
   }

   /** The registry id of the sound event the song actually plays. Clients use
    *  it directly, so a song added by a datapack still plays even if the
    *  client's jukebox-song registry can't resolve it. */
   private static String soundEventId(Holder<JukeboxSong> song) {
      try {
         Identifier id = song.value().soundEvent().unwrapKey().map(key -> key.identifier()).orElse(null);
         if (id != null) {
            return id.toString();
         }
         return net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getKey(song.value().soundEvent().value()).toString();
      } catch (Throwable t) {
         return "";
      }
   }
}
