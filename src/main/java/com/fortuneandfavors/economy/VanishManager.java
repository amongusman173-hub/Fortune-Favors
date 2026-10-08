package com.fortuneandfavors.economy;

import com.fortuneandfavors.anticheat.SpectateKit;
import com.fortuneandfavors.util.Chat;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/** Toggleable admin vanish.
 *
 *  While vanished a player:
 *  - is removed from every other player's tab list,
 *  - is made invisible so nobody can see them in the world,
 *  - has their armor AND their held items unequipped and stashed (they are kept
 *    safe and re-equip on return) so neither the ghost armor layer nor a
 *    floating sword gives their position away,
 *  - is muted, so their footsteps don't announce them, and their sprint dust is
 *    suppressed (see VanishStepMixin),
 *  - is removed from the tab list again every few ticks, because the tab list is written by the
 *    rest of the server as well and one re-add is all it takes for the whole thing to stop
 *    hiding anybody,
 *  - is off the locator bar (see VanishMobTargetMixin's sibling, the waypoint injection in
 *    LivingEntityMixin), because the locator bar is a second tab list and invisibility is not
 *    part of it: vanilla tracks every living thing whose WAYPOINT_TRANSMIT_RANGE is positive,
 *    whatever else is true of it,
 *  - is not wearing or holding anything, as far as every other client is concerned: the armour
 *    is stashed and the hands are forcibly emptied <i>in the packets</i> rather than in the
 *    inventory, because the moderation kit has to stay right-clickable while it is invisible,
 *  - says nothing at all. No fake disconnect line and no fake join line: a vanish that
 *    announces itself is a vanish that changes what people do, and a yellow chat line in the
 *    feed is the loudest tell there is. An observer sees nothing, which is the entire point,
 *  - cannot send chat (blocked in ChatGarblerMixin),
 *  - and leaves no dotted line behind them either: a hidden body collects nothing. Experience
 *    orbs are never drawn toward it and never absorbed by it, and dropped items stay exactly
 *    where they fell rather than hopping into an invisible pocket. This is the one tell that is
 *    not about rendering at all - the orb trail is the world's own reaction to the body, drawn
 *    in the terrain rather than on the body, so no amount of hiding the player's model fixes it.
 *    See {@code VanishOrbMixin} and {@code ItemEntityMixin}. */
public final class VanishManager {
   /**
    * The scoreboard team every vanished body is parked on.
    *
    * <p>It exists for one reason: the mixins that cut the tells a vanish leaves behind run on the
    * <b>client</b> too ({@code VanishStepMixin} and friends), and a client cannot read the server's
    * ledger. It used to guess with {@code isInvisible() && !hasEffect(INVISIBILITY)} - "invisible,
    * and not because of a potion" - which was a fair guess until this mod started handing out
    * short invisibility effects itself (a Puppeteer escape, a scripted 30-tick flash), at which
    * point a vanished moderator could silently stop being recognised and start leaving footprints
    * again. A team is ours and only ours, it is synced to every client by vanilla's own scoreboard
    * packets, and it changes nothing about how the player is rendered (see {@link #markBody}).
    */
   public static final String VANISH_TEAM = "ff_vanish";
   private static final Set<UUID> VANISHED = new HashSet<>();
   // armor stashed on vanish; keyed by player. Stays off the player's persistent
   // inventory, and is re-equipped on return or (for safety) on disconnect, so no
   // item is ever lost.
   private static final Map<UUID, Map<EquipmentSlot, ItemStack>> WARDROBE = new java.util.HashMap<>();
   // The scoreboard teams a vanished player was on. A team can be told to render its members
   // even while they are invisible (see dropTeam), so the membership is parked rather than
   // discarded, and handed back on return.
   private static final Map<UUID, String> TEAMS = new java.util.HashMap<>();
   /**
    * How many times each vanished body has had to be made invisible again.
    *
    * <p>A counter rather than a boolean because the first write is the vanish itself: the second
    * one is proof that <i>something else</i> cleared the flag, and "vanish does not work" is a
    * report that needs a cause rather than another guess. See {@link #apply}.
    */
   private static final Map<UUID, Integer> REWRITES = new java.util.HashMap<>();
   /**
    * Ticks between the packets vanish re-sends.
    *
    * <p>Four, not twenty. Every one of these packets is written by something else as well - the
    * tab list by any scoreboard write - so the window between the other writer and this one is a
    * window in which a vanished moderator is visible. A fifth of a second is short enough that a
    * player cannot walk into it on purpose, and the packets are small enough that it does not
    * matter.
    */
   private static final int PACKET_TICKS = 4;
   /**
    * And the equipment gets its own clock: every tick.
    *
    * <p>Because the equipment is the one that <b>flashes</b>. Vanilla re-sends a full equipment
    * packet the instant a viewer starts tracking a body, so a player walking up to a vanished
    * moderator is shown whatever is in their hand until the next re-assertion - a fifth of a
    * second of floating sword, which is exactly long enough to see and exactly what "it shows
    * your items" describes. A tick closes it. It is one small packet per other player, and the
    * alternative is a tell that appears on its own.
    */
   private static final int EQUIPMENT_TICKS = 1;

   private VanishManager() {
   }

   public static boolean isVanished(ServerPlayer p) {
      return p != null && VANISHED.contains(p.getUUID());
   }

   public static boolean isVanished(UUID id) {
      return id != null && VANISHED.contains(id);
   }

   /**
    * Is this body one this mod is hiding?
    *
    * <p>This is the question the mixins ask, on both sides. The server answers from the ledger it
    * owns. Anything that is not a server player - which is every body on a client, including the
    * local player of an integrated server - answers from the marker team, because that is the only
    * part of the vanish a client is told about. A body neither side claims is an ordinary player
    * or an ordinary invisibility potion, and keeps all of its footsteps.
    */
   public static boolean isHiddenBody(Entity entity) {
      if (!(entity instanceof Player player)) {
         return false;
      }
      if (player instanceof ServerPlayer sp) {
         return VANISHED.contains(sp.getUUID());
      }
      PlayerTeam team = player.getTeam();
      return team != null && VANISH_TEAM.equals(team.getName());
   }

   /** Toggles vanish on/off for the player. Returns true if now vanished. */
   public static boolean toggle(ServerPlayer p) {
      return isVanished(p) ? unvanish(p) : vanish(p);
   }

   public static boolean vanish(ServerPlayer p) {
      if (VANISHED.contains(p.getUUID())) {
         return true;
      }
      VANISHED.add(p.getUUID());
      REWRITES.put(p.getUUID(), 0);
      apply(p);
      stashGear(p);
      // Immediate rather than on the packet clock: the tick between a vanish and the first
      // packet is a tick in which the moderator is standing in the open.
      hideEverywhere(p);
      hideGear(p);
      hideFromLocatorBar(p);
      hideFromUnpatchableViewers(p);
      return true;
   }

   public static boolean unvanish(ServerPlayer p) {
      if (!VANISHED.contains(p.getUUID())) {
         return false;
      }
      VANISHED.remove(p.getUUID());
      REWRITES.remove(p.getUUID());
      restoreGear(p);
      unmarkBody(p);
      restoreTeam(p);
      p.setInvisible(false);
      p.setSilent(false);
      p.setCustomNameVisible(p.getCustomName() != null);
      // Back on the locator bar, for everybody who can see them again.
      showOnLocatorBar(p);
      MinecraftServer server = p.level() != null ? p.level().getServer() : null;
      if (server != null) {
         PlayerList list = server.getPlayerList();
         ClientboundPlayerInfoUpdatePacket add = new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(Action.ADD_PLAYER), List.of(p)
         );
         ClientboundPlayerInfoUpdatePacket name = new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(Action.UPDATE_DISPLAY_NAME), List.of(p)
         );
         for (ServerPlayer other : list.getPlayers()) {
            if (!other.getUUID().equals(p.getUUID()) && other.connection != null) {
               other.connection.send(add);
               other.connection.send(name);
            }
         }
         // ...and only now back into the world of the viewers whose client had no body to draw.
         // After the tab list rather than before it: a Bedrock client handed a player entity
         // before it is told the player exists has nothing to attach it to.
         showToUnpatchableViewers(p, server);
      }
      return false;
   }

   /** A vanished player leaves: re-equip their stashed armor so it's saved even
    *  if the vanish state isn't, then clear vanish + wardrobe. */
   public static void onDisconnect(ServerPlayer p) {
      VANISHED.remove(p.getUUID());
      TEAMS.remove(p.getUUID());
      REWRITES.remove(p.getUUID());
      unmarkBody(p);
      Map<EquipmentSlot, ItemStack> saved = WARDROBE.remove(p.getUUID());
      if (saved != null) {
         for (Map.Entry<EquipmentSlot, ItemStack> e : saved.entrySet()) {
            if (!e.getValue().isEmpty() && p.getItemBySlot(e.getKey()).isEmpty()) {
               p.setItemSlot(e.getKey(), e.getValue());
            } else if (!e.getValue().isEmpty()) {
               dropForPlayer(p, e.getValue());
            }
         }
      }
   }

   public static Set<UUID> vanishedIds() {
      return new HashSet<>(VANISHED);
   }

   /**
    * How many times this body has had a vanish flag put back since it vanished.
    *
    * <p>The first write of each flag is the vanish itself, so the interesting reading is
    * anything above two: some other system on this server cleared invisibility, silence or
    * glowing on a hidden body, and this mod had to write it again. It is the difference between
    * "vanish is broken" and "something else here is fighting it", which is the whole content of a
    * vanish bug report - see {@link #noteRewrite}. Read by {@code /ff vanish status}, and by the
    * self-test rather than by any behaviour.
    */
   public static int rewrites(ServerPlayer p) {
      return p == null ? 0 : REWRITES.getOrDefault(p.getUUID(), 0);
   }

   /**
    * The vanished state, re-asserted once a second.
    *
    * <p>Every part of vanish is a flag somebody else can clear. Invisibility comes off when
    * another system writes the entity data; the tab list is re-sent whenever a scoreboard, a
    * guild, a duel or a vanilla join writes it; silence is a flag too. Latching any of them once
    * is how vanish "does not vanish anyone" - one later write and the whole thing is a player
    * walking around visible with a moderator's intentions. So the state is re-applied on a clock
    * rather than set once, and the tab-list removal is re-sent to everybody, which is the only
    * way to be sure a player who joined a minute ago cannot see the name that everybody else
    * cannot.
    *
    * <p>Cheap by construction: it returns immediately when nobody is vanished, which is always.
    */
   public static void tick(MinecraftServer server) {
      if (server == null || VANISHED.isEmpty()) {
         return;
      }
      PlayerList list = server.getPlayerList();
      long tick = server.getTickCount();
      boolean packets = tick % PACKET_TICKS == 0L;
      boolean gear = tick % EQUIPMENT_TICKS == 0L;
      for (UUID id : new HashSet<>(VANISHED)) {
         ServerPlayer p = list.getPlayer(id);
         if (p == null) {
            continue;
         }
         // Every tick: these are flags, they cost nothing, and each of them is writable by
         // something else (a status effect, a team, another system's data write).
         apply(p);
         if (gear) {
            hideGear(p);
         }
         if (packets) {
            // On the clock: these send packets, and the thing that undoes each of them is a
            // packet written by vanilla from now on - tracking an entity, writing a tab list.
            hideEverywhere(p);
            hideFromLocatorBar(p);
            hideFromUnpatchableViewers(p);
         }
      }
   }

   /** The flags that make a body invisible on every client, including its own. */
   private static void apply(ServerPlayer p) {
      if (!p.isInvisible()) {
         p.setInvisible(true);
         noteRewrite(p, "invisibility");
      }
      if (!p.isSilent()) {
         p.setSilent(true);
      }
      // The name above the head is drawn by the client from the entity, and it is drawn for the
      // player's own screen too - which is what "I can see myself" means in practice. This is
      // the one line that takes it away.
      if (p.isCustomNameVisible()) {
         p.setCustomNameVisible(false);
      }
      // Glowing is the one outline invisibility does not take with it: the flag is a separate
      // render pass, and a glowing invisible player is a moving silhouette on everybody's screen.
      // Something else owns this effect as well - the wanted aura hands it out on a clock - so it
      // is removed here on a clock rather than once at the moment of the vanish.
      if (p.hasEffect(MobEffects.GLOWING)) {
         p.removeEffect(MobEffects.GLOWING);
      }
      // And the other half of glowing, which is not the effect at all: the entity's own glowing
      // flag, which {@code /glowing}, a locator or a threat system sets directly. Removing the
      // effect does nothing to it, and the outline is drawn either way.
      if (p.hasGlowingTag()) {
         p.setGlowingTag(false);
         noteRewrite(p, "glowing");
      }
      // The potion cloud. An invisible body still carries the swirl of every effect it has unless
      // something clears the synced particle list, and vanilla clears it exactly once - when the
      // invisibility flag changes. Any effect added after that re-syncs the list, so a vanished
      // moderator with a speed potion, a night vision and this mod's own scripted effects walks
      // around inside a labelled cloud. The list is emptied every tick instead, which is what
      // {@code removeEffectParticles} is for and what {@code updateInvisibilityStatus} asks for
      // once; the mixin on the sync method is the half that closes the window from the other side.
      emptyEffectParticles(p);
      // Fire is drawn on an invisible body - the flame overlay is part of the model, not of the
      // skin - so a staff member who runs through a campfire is a moving flame with nothing in
      // it. Creative mode has no other reason to be on fire.
      if (p.isOnFire() || p.getRemainingFireTicks() > 0) {
         p.clearFire();
         noteRewrite(p, "fire");
      }
      dropTeam(p);
      markBody(p);
      // The armour and the hands are swept rather than stashed once: either can come back onto the
      // body during a vanish (a pickup, another system handing out a piece), and both are drawn by
      // layers that never ask whether the entity is invisible.
      stashGear(p);
   }

   /**
    * Empties the synced effect-particle list on this body.
    *
    * <p>This used to be {@code p.getClass().getMethod("removeEffectParticles")} into a
    * {@code catch (Throwable ignored)}, and it <b>never ran once</b>. {@code Class.getMethod} only
    * returns public members, {@code LivingEntity.removeEffectParticles()} is {@code protected}, so
    * the lookup threw {@code NoSuchMethodException} on every tick of every vanish - a tell this mod
    * has been reporting as fixed for several releases with nothing in the log to say otherwise. The
    * same string would have failed under production mappings too, where the method is not called
    * that. It is an {@code @Invoker} now: resolved at build time, an upstream rename is a compile
    * error, and the call either works or the mod does not build.
    */
   private static void emptyEffectParticles(ServerPlayer p) {
      if (p instanceof com.fortuneandfavors.mixin.VanishEffectParticleInvoker invoker) {
         invoker.fortuneandfavors$removeEffectParticles();
      }
   }

   /**
    * How many particle entries this body is currently syncing to its viewers.
    *
    * <p>The measurement the vanish is judged on, and the reason it is a count rather than a
    * boolean: the cloud is drawn by the client from exactly this list, so "is it empty" is the
    * whole of "is there a swirl following the moderator". Read through the mixin's accessor, so it
    * cannot drift from the list the client is sent.
    *
    * @return the number of synced entries, or -1 when the accessor could not be read - which the
    *         self-test treats as a failure rather than as "nothing to see".
    */
   public static int effectParticleCount(ServerPlayer p) {
      try {
         java.util.List<?> list = p.getEntityData().get(
            com.fortuneandfavors.mixin.VanishEffectParticleInvoker.fortuneandfavors$effectParticles()
         );
         return list == null ? 0 : list.size();
      } catch (Throwable t) {
         return -1;
      }
   }

   /**
    * Runs vanilla's own writer for the synced particle list on this body.
    *
    * <p>A test seam, and deliberately public: the write the vanish has to survive is private and
    * only ever runs on a tick, so without this a check could only ever observe a list that was
    * empty for the wrong reason. Filling a visible body and finding it full is what makes the
    * emptying of a hidden one mean something.
    */
   public static void syncEffectParticles(ServerPlayer p) {
      if (p instanceof com.fortuneandfavors.mixin.VanishEffectParticleInvoker invoker) {
         invoker.fortuneandfavors$updateSynchronizedMobEffectParticles();
      }
   }

   /**
    * Tags this body as ours, for the client half of the tell mutes.
    *
    * <p>The team is created once, with {@code seeFriendlyInvisibles} off - the flag that means
    * "draw this player to their teammates even while invisible", which is the one thing a vanish
    * cannot hide by being more careful. It carries no colour, no prefix and no name-tag rule, so
    * joining it changes nothing about how the player is drawn; it is a flag with a name on it.
    */
   private static void markBody(ServerPlayer p) {
      try {
         Scoreboard board = p.level().getScoreboard();
         PlayerTeam team = board.getPlayerTeam(VANISH_TEAM);
         if (team == null) {
            team = board.addPlayerTeam(VANISH_TEAM);
            team.setSeeFriendlyInvisibles(false);
         }
         PlayerTeam current = board.getPlayersTeam(p.getScoreboardName());
         if (current != team) {
            if (current != null) {
               board.removePlayerFromTeam(p.getScoreboardName(), current);
            }
            board.addPlayerToTeam(p.getScoreboardName(), team);
         }
      } catch (Throwable t) {
         // Not swallowed: this tag is the only part of a vanish a client is told about, and a
         // vanish whose marker never lands is a vanish whose footsteps, dust and potion cloud all
         // come back with nothing in the log to say why. Said once per failure, on the server.
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.warn(
            "Fortune & Favors: could not tag a vanished body with {} - the client-side tell mutes will not recognise it",
            VANISH_TEAM,
            t
         );
      }
   }

   /**
    * Does the body's own scoreboard name it as ours?
    *
    * <p>The server-side half of the marker question, and deliberately <b>not</b> read through
    * {@code Player.getTeam()}: on a dedicated server that call answers from the level's own
    * scoreboard view and comes back empty for a body the scoreboard has tagged (a fact this check
    * learned the hard way - see {@link #markerReport}). The client is the other way round: its
    * scoreboard is the copy the team packets kept up to date, and {@code getTeam()} there is
    * exactly the answer {@link #isHiddenBody} wants.
    */
   public static boolean carriesMarker(ServerPlayer p) {
      try {
         Scoreboard board = p.level().getScoreboard();
         PlayerTeam team = board.getPlayersTeam(p.getScoreboardName());
         return team != null && VANISH_TEAM.equals(team.getName());
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * What the manager believes a body's marker is, next to what the scoreboard says.
    *
    * <p>For a check, and for exactly one failure: the marker is the only part of a vanish a client
    * is told about, so when it does not land the interesting question is which link of it broke -
    * the name the body reports, the team's existence, or the membership itself. One string, read
    * once, rather than three assertions that all say "no team".
    */
   public static String markerReport(ServerPlayer p) {
      try {
         Scoreboard board = p.level().getScoreboard();
         PlayerTeam byName = board.getPlayersTeam(p.getScoreboardName());
         PlayerTeam team = board.getPlayerTeam(VANISH_TEAM);
         PlayerTeam viaBody = p.getTeam();
         return "name=" + p.getScoreboardName()
            + " level=" + p.level().dimension().identifier()
            + " byName=" + (byName == null ? "none" : byName.getName())
            + " teamExists=" + (team != null)
            + " members=" + (team == null ? "-" : team.getPlayers().size())
            + " viaBody=" + (viaBody == null ? "none" : viaBody.getName());
      } catch (Throwable t) {
         return "threw " + t;
      }
   }

   /** Takes the tag back off, on return or on leaving. */
   private static void unmarkBody(ServerPlayer p) {
      try {
         Scoreboard board = p.level().getScoreboard();
         PlayerTeam team = board.getPlayerTeam(VANISH_TEAM);
         if (team != null) {
            board.removePlayerFromTeam(p.getScoreboardName(), team);
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * Counts a re-assertion, and says so once when it is somebody else's doing.
    *
    * <p>The first write of each is the vanish itself. A second one means something in this server
    * cleared the flag, and "/ff vanish does not work for other players" is exactly what that
    * looks like from the outside - so the one line below turns the next report into a name. It is
    * said to the vanished player only, once, and never in public chat.
    */
   private static void noteRewrite(ServerPlayer p, String what) {
      int seen = REWRITES.merge(p.getUUID(), 1, Integer::sum);
      if (seen == 2) {
         Chat.msg(
            p,
            "&7Something other than vanish cleared your &f" + what + "&7 - it has been put back. "
               + "&7If you keep showing up to other players, this is the line to report."
         );
      }
   }

   /**
    * Puts this body off the locator bar.
    *
    * <p>The locator bar's server side tracks every living thing whose {@code WAYPOINT_TRANSMIT_RANGE}
    * is positive - no invisibility check anywhere in it - so it is a second tab list, and it is
    * the one that shows a <i>position</i>. The declarative half of this is the injection on
    * {@code LivingEntity.isTransmittingWaypoint} in VanishMixin: that is what stops a viewer who
    * comes into range getting a marker in the first place. This is the other half - the
    * connections that already exist are dropped now rather than at the next tick of whatever
    * asks, and it is re-asked on the packet clock for the same reason the tab list is.
    */
   private static void hideFromLocatorBar(ServerPlayer p) {
      try {
         if (p.level() instanceof ServerLevel level) {
            level.getWaypointManager().untrackWaypoint(p);
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * Takes this body out of the world of viewers that cannot be trusted to hide it.
    *
    * <p>Every mute a vanish has is a <b>client-side</b> one - the sprint dust, the landing puff,
    * the water splash and the boot steps are all drawn and played by the client of whoever is
    * looking, from state this server only syncs. {@link com.fortuneandfavors.mixin.VanishStepMixin}
    * and its siblings cut them at the source, and they are the reason the vanish is seamless on a
    * client running this mod. A Bedrock client arrives through Geyser, cannot be patched at all,
    * and draws its own particles from the entity state it is sent - which is why a vanished
    * moderator is still a plume of dust and a ring of splash to every Bedrock player on the server.
    *
    * <p>The one mechanism that works on a client nobody can patch is to not send it a body. This
    * removes the entity from Geyser's world outright, on the same four-tick clock the tab list
    * uses - so a re-track, a respawn or a chunk re-send cannot quietly put it back - and
    * {@link #showToUnpatchableViewers} hands it back on return.
    *
    * <p>Only Bedrock viewers, deliberately. A Java client is either running this mod (in which
    * case the client half already hides the body, and removing it would be a flicker bought for
    * nothing) or it is not (in which case it is a client nobody in this stack can reach, and the
    * same argument applies - but it is left alone because an unmodded Java viewer is a state this
    * mod has never claimed to fix, while Bedrock is one it must).
    */
   private static void hideFromUnpatchableViewers(ServerPlayer p) {
      MinecraftServer server = p.level() == null ? null : p.level().getServer();
      if (server == null) {
         return;
      }
      ClientboundRemoveEntitiesPacket remove = new ClientboundRemoveEntitiesPacket(p.getId());
      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         if (other.getUUID().equals(p.getUUID()) || other.connection == null) {
            continue;
         }
         if (!com.fortuneandfavors.util.ModPlatform.isBedrock(other)) {
            continue;
         }
         other.connection.send(remove);
      }
   }

   /** Hands the body back to the viewers it was removed from - see {@link #hideFromUnpatchableViewers}. */
   private static void showToUnpatchableViewers(ServerPlayer p, MinecraftServer server) {
      if (server == null) {
         return;
      }
      ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(p, 0, p.blockPosition());
      // The metadata too, and not as an afterthought: an AddEntity with no data attached is a
      // featureless body to a Bedrock client - the skin, the pose, whether it is standing or
      // swimming all arrive in this second packet, and vanilla itself sends exactly this pair when
      // a viewer starts tracking somebody. Sending the spawn alone is the version that produces a
      // staff member who comes back as a blank.
      ClientboundSetEntityDataPacket data = new ClientboundSetEntityDataPacket(
         p.getId(), p.getEntityData().getNonDefaultValues()
      );
      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         if (other.getUUID().equals(p.getUUID()) || other.connection == null) {
            continue;
         }
         if (!com.fortuneandfavors.util.ModPlatform.isBedrock(other)) {
            continue;
         }
         other.connection.send(add);
         other.connection.send(data);
      }
   }

   /** Back on the locator bar, for everybody who can see them again. */
   private static void showOnLocatorBar(ServerPlayer p) {
      try {
         if (p.level() instanceof ServerLevel level) {
            level.getWaypointManager().trackWaypoint(p);
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * The slots a vanished body is reported empty in: everything that renders.
    *
    * <p>Six slots, and all six are load-bearing - a later edit that decided the hands did not
    * need hiding, or that the boots were not really visible on an invisible player, is exactly
    * the edit that puts a floating sword back on somebody's screen. The self-test asserts this
    * list rather than trusting the loop that walks it.
    */
   public static List<EquipmentSlot> hidesEquipment() {
      List<EquipmentSlot> slots = new ArrayList<>();
      slots.add(EquipmentSlot.MAINHAND);
      slots.add(EquipmentSlot.OFFHAND);
      for (EquipmentSlot slot : ARMOR) {
         slots.add(slot);
      }
      return List.copyOf(slots);
   }

   /**
    * Empties this body's hands and armour <b>as far as every other client is concerned</b>.
    *
    * <p>Armour and held items are rendered by their own layers, and neither of those layers asks
    * whether the entity is invisible - which is why the old vanilla trick of an invisibility
    * potion leaves a suit of armour and a sword walking around on their own. The stash below takes
    * the armour and the hands away for real; this packet is what covers the one stack the stash
    * deliberately leaves alone (the moderation kit - see {@link #stashGear}), and it is re-sent on
    * a clock because a body's equipment is re-written by every pickup and slot change.
    */
   private static void hideGear(ServerPlayer p) {
      MinecraftServer server = p.level() == null ? null : p.level().getServer();
      if (server == null) {
         return;
      }
      List<Pair<EquipmentSlot, ItemStack>> bare = new ArrayList<>();
      for (EquipmentSlot slot : hidesEquipment()) {
         bare.add(Pair.of(slot, ItemStack.EMPTY));
      }
      ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(p.getId(), bare);
      PlayerList list = server.getPlayerList();
      for (ServerPlayer other : list.getPlayers()) {
         if (!other.getUUID().equals(p.getUUID()) && other.connection != null) {
            other.connection.send(packet);
         }
      }
   }

   /**
    * Takes this body out of its scoreboard teams while it is away.
    *
    * <p>A team has a flag - {@code seeFriendlyInvisibles} - whose entire meaning is "render this
    * player to me even when they are invisible", and it is drawn as a translucent copy of the
    * body: the one thing a vanish cannot hide by being more careful, because it is the client
    * doing the seeing. Membership is parked rather than dropped so the tag, the colour and the
    * guild come back with the player.
    */
   private static void dropTeam(ServerPlayer p) {
      try {
         Scoreboard board = p.level().getScoreboard();
         PlayerTeam team = board.getPlayersTeam(p.getScoreboardName());
         // The marker team is ours and is handled by markBody - parking it here would take the tag
         // off every tick and put it back on every tick, which is a packet storm with no purpose.
         if (team != null && !VANISH_TEAM.equals(team.getName())) {
            TEAMS.put(p.getUUID(), team.getName());
            board.removePlayerFromTeam(p.getScoreboardName(), team);
         }
      } catch (Throwable ignored) {
      }
   }

   /** Hands a vanished player's team membership back. */
   private static void restoreTeam(ServerPlayer p) {
      String name = TEAMS.remove(p.getUUID());
      if (name == null) {
         return;
      }
      try {
         Scoreboard board = p.level().getScoreboard();
         PlayerTeam team = board.getPlayerTeam(name);
         if (team != null) {
            board.addPlayerToTeam(p.getScoreboardName(), team);
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * Takes this body off everybody else's tab list.
    *
    * <p>Sent on every refresh rather than once, and to every player rather than only the ones
    * who were online, because a tab list entry is written by whoever asks for one - a join, a
    * scoreboard update, a team change - and the last writer wins. One packet a second is the
    * price of a vanish that cannot be undone by accident.
    */
   private static void hideEverywhere(ServerPlayer p) {
      MinecraftServer server = p.level() == null ? null : p.level().getServer();
      if (server == null) {
         return;
      }
      PlayerList list = server.getPlayerList();
      ClientboundPlayerInfoRemovePacket remove = new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID()));
      for (ServerPlayer other : list.getPlayers()) {
         if (!other.getUUID().equals(p.getUUID()) && other.connection != null) {
            other.connection.send(remove);
         }
      }
   }

   // ---------- Gear stashing (kept, but not worn or held, so nothing shows) ----------

   private static final EquipmentSlot[] ARMOR = {
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
   };
   /** The two slots that render as something in the body's hands. */
   private static final EquipmentSlot[] HAND = {
      EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
   };

   /**
    * The armour and the hands are taken off the body; the moderation kit is left where it is.
    *
    * <p>Both armour and held items are drawn by their own render layers, and neither of those
    * layers asks whether the entity is invisible - which is why the vanilla trick of a potion
    * leaves a suit of armour and a sword walking around on their own. The packets in
    * {@link #hideGear} tell every <i>other</i> client those slots are empty, and that is enough
    * for everyone except the one body that matters most to the person testing it: your own client
    * draws your own held item from your own inventory and never reads the packet, so a vanished
    * moderator looked at themselves in third person and saw the sword still there. Emptying the
    * slot for real is the only version of this that hides the item from its owner too.
    *
    * <p>The one stack that stays in a hand is the moderation kit, and it is the whole reason the
    * hands were not stashed before: the kit is handed over <i>after</i> the vanish, straight into
    * the open slot, and a kit item that has been parked in the wardrobe cannot be right-clicked -
    * so the staff member loses the tool that exists to be clicked. Skipping kit items gives both:
    * everything a vanished body is holding disappears, and the kit is still the kit.
    *
    * <p>The armour is swept rather than stashed once (it can come back onto the body during a
    * vanish - a pickup, another system handing out a piece), and so are the hands, for the same
    * reason: pick a sword up while vanished and it goes the way of the rest of them.
    */
   private static void stashGear(ServerPlayer p) {
      Map<EquipmentSlot, ItemStack> stash = WARDROBE.computeIfAbsent(p.getUUID(), k -> new EnumMap<>(EquipmentSlot.class));
      for (EquipmentSlot slot : ARMOR) {
         park(p, stash, slot, p.getItemBySlot(slot));
      }
      for (EquipmentSlot slot : HAND) {
         ItemStack held = p.getItemBySlot(slot);
         if (SpectateKit.isKitItem(held)) {
            continue;
         }
         park(p, stash, slot, held);
      }
   }

   /**
    * Moves one worn or held stack into the wardrobe, into the slot it came off.
    *
    * <p>Two pieces for one slot (the stash never left, and a second arrived): the one that was
    * already parked is the one that belongs to the player, so the newcomer goes back to the world
    * rather than replacing it. Merging instead of overwriting is also what makes this callable
    * every tick, which is what "the armour can come back" needs.
    */
   private static void park(ServerPlayer p, Map<EquipmentSlot, ItemStack> stash, EquipmentSlot slot, ItemStack piece) {
      if (piece == null || piece.isEmpty()) {
         return;
      }
      p.setItemSlot(slot, ItemStack.EMPTY);
      ItemStack parked = stash.get(slot);
      if (parked == null || parked.isEmpty()) {
         stash.put(slot, piece);
      } else {
         dropForPlayer(p, piece);
      }
   }

   /**
    * The slots a vanished body has been genuinely emptied in, as opposed to hidden in packets.
    *
    * <p>A test seam, and public on purpose. The two halves of hiding an item are different
    * mechanisms with different failure modes - a packet that is never sent, and a slot that is
    * never empty - and the self-test asserts the ledger rather than the loop that fills it.
    */
   public static List<EquipmentSlot> emptiedSlots(ServerPlayer p) {
      Map<EquipmentSlot, ItemStack> stash = WARDROBE.get(p.getUUID());
      return stash == null ? List.of() : List.copyOf(stash.keySet());
   }

   /**
    * True for the one stack a vanished body is allowed to keep in its hand: the moderation kit.
    *
    * <p>The exemption is a fact about the item rather than about the vanish, so it is asked of
    * the class that hands the kit out - the same predicate the click handler uses, which is what
    * keeps "the kit is usable" and "the kit is not stashed" from drifting apart.
    */
   public static boolean keepsInHand(ItemStack stack) {
      return SpectateKit.isKitItem(stack);
   }

   private static void restoreGear(ServerPlayer p) {
      Map<EquipmentSlot, ItemStack> stash = WARDROBE.remove(p.getUUID());
      if (stash == null) {
         return;
      }
      for (Map.Entry<EquipmentSlot, ItemStack> e : stash.entrySet()) {
         ItemStack toRestore = e.getValue();
         ItemStack occupant = p.getItemBySlot(e.getKey());
         if (!occupant.isEmpty()) {
            // something was worn over the vanish - keep it, park the stash item
            dropForPlayer(p, toRestore);
         } else {
            p.setItemSlot(e.getKey(), toRestore);
         }
      }
   }

   private static void dropForPlayer(ServerPlayer p, ItemStack stack) {
      try {
         if (p.level() instanceof ServerLevel lvl) {
            lvl.addFreshEntity(new ItemEntity(lvl, p.getX(), p.getY() + 0.5, p.getZ(), stack));
         }
      } catch (Exception ignored) {
      }
   }

}