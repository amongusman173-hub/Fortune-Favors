package com.fortuneandfavors.guild;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModCommands;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;

public final class GuildManager {
   private static final Map<UUID, Guild> guilds = new LinkedHashMap<>();
   private static final Map<UUID, UUID> playerGuild = new HashMap<>();
   private static final Map<UUID, Set<UUID>> invites = new HashMap<>();
   private static final Map<UUID, String> knownNames = new HashMap<>();
   private static Path dataFile;
   private static MinecraftServer serverRef;
   /** Per guild+member progress toward the next recurring PvE (Stab Shot) milestone. */
   private static final Map<String, Long> pveMilestoneProgress = new HashMap<>();
   private static final Map<UUID, List<Mail>> mailboxes = new HashMap<>();
   private static int nextMailId = 1;
   public static final int LEVEL_STEP = 25;
   public static final long PVP_MILESTONE = 30L;
   /** One Stab Shot is granted for every this-many PvE score a member earns (recurring). */
   public static final long PVE_MILESTONE_STEP = 30L;
   /** Max items stashed in one guild mail message. */
   public static final int MAX_MAIL_ITEMS = 9;
   public static final int MAX_MAILBOX = 27;
   /** Keep claimed mail around this many days so senders can't spam-void mail. */
   private static final long MAIL_RETENTION_MS = 14L * 24L * 60L * 60L * 1000L;
   public static final int MAX_PERK = 5;
   public static final long PERK_REFUND_FEE = 10000L;

   private GuildManager() {
   }

   public static Guild getGuild(UUID playerId) {
      if (playerId == null) {
         return null;
      }

      UUID gid = playerGuild.get(playerId);
      return gid == null ? null : guilds.get(gid);
   }

   public static boolean isGuildmate(UUID a, UUID b) {
      if (a != null && b != null && !a.equals(b)) {
         UUID ga = playerGuild.get(a);
         return ga != null && ga.equals(playerGuild.get(b));
      } else {
         return false;
      }
   }

   public static boolean friendlyFireOn(UUID playerId) {
      Guild g = getGuild(playerId);
      return g != null && g.friendlyFire;
   }

   public static Guild byName(String name) {
      for (Guild g : guilds.values()) {
         if (g.name.equalsIgnoreCase(name)) {
            return g;
         }
      }

      return null;
   }

   public static Guild byId(UUID guildId) {
      return guildId == null ? null : guilds.get(guildId);
   }

   public static List<Guild> all() {
      return new ArrayList<>(guilds.values());
   }

   public static void noteName(UUID playerId, String name) {
      if (playerId != null && name != null && !name.isEmpty()) {
         knownNames.put(playerId, name);
      }
   }

   public static String displayName(MinecraftServer server, UUID playerId, String fallback) {
      if (server != null && server.getPlayerList().getPlayer(playerId) != null) {
         return server.getPlayerList().getPlayer(playerId).getName().getString();
      }

      String known = knownNames.get(playerId);
      return known != null ? known : fallback;
   }

   public static long totalScore(Guild g) {
      return g.pvp + g.pve;
   }

   public static int levelOf(Guild g) {
      return 1 + (int)(totalScore(g) / 25L);
   }

   public static long progressToNext(Guild g) {
      return totalScore(g) % 25L;
   }

   // ------------------------------------------------------------------ skills

   /** Every guild skill, in the order the skills menu lays them out. Adding a perk
    *  is meant to be one entry here plus one branch in each switch below - never a
    *  new field threaded through four call sites. */
   public static final String PERK_WISDOM = "wisdom";
   public static final String PERK_MIGHT = "might";
   public static final String PERK_RALLY = "rally";
   public static final String PERK_WARD = "ward";
   public static final String PERK_FORTUNE = "fortune";
   public static final String[] PERKS = {PERK_WISDOM, PERK_MIGHT, PERK_RALLY, PERK_WARD, PERK_FORTUNE};

   public static boolean isValidPerk(String key) {
      for (String p : PERKS) {
         if (p.equals(key)) {
            return true;
         }
      }
      return false;
   }

   public static String perkName(String key) {
      return switch (key == null ? "" : key) {
         case PERK_WISDOM -> "Wisdom";
         case PERK_MIGHT -> "Might";
         case PERK_RALLY -> "Rally";
         case PERK_WARD -> "Ward";
         case PERK_FORTUNE -> "Fortune";
         default -> "Perk";
      };
   }

   /** What a given rank of a perk grants, phrased for the menu and the chat notice. */
   public static String perkEffect(String key, int rank) {
      return switch (key == null ? "" : key) {
         case PERK_WISDOM -> "+" + rank * 5 + "% skill XP for members";
         case PERK_MIGHT -> "+" + rank * 4 + "% damage to mobs for members";
         case PERK_RALLY -> "+" + rank * 5 + "% damage to raid bosses for members";
         case PERK_WARD -> "-" + rank * 4 + "% damage taken from mobs for members";
         case PERK_FORTUNE -> "+" + rank * 5 + "% earned cash for members";
         default -> "";
      };
   }

   public static int perkRank(Guild g, String key) {
      if (g == null) {
         return 0;
      }
      return switch (key == null ? "" : key) {
         case PERK_WISDOM -> g.xpPerk;
         case PERK_MIGHT -> g.mightPerk;
         case PERK_RALLY -> g.rallyPerk;
         case PERK_WARD -> g.wardPerk;
         case PERK_FORTUNE -> g.coinPerk;
         default -> 0;
      };
   }

   private static boolean setPerkRank(Guild g, String key, int rank) {
      int value = Math.max(0, Math.min(MAX_PERK, rank));
      switch (key == null ? "" : key) {
         case PERK_WISDOM -> g.xpPerk = value;
         case PERK_MIGHT -> g.mightPerk = value;
         case PERK_RALLY -> g.rallyPerk = value;
         case PERK_WARD -> g.wardPerk = value;
         case PERK_FORTUNE -> g.coinPerk = value;
         default -> {
            return false;
         }
      }
      return true;
   }

   public static int spentPerkPoints(Guild g) {
      if (g == null) {
         return 0;
      }
      return g.xpPerk + g.coinPerk + g.mightPerk + g.wardPerk + g.rallyPerk;
   }

   public static int availablePerkPoints(Guild g) {
      return Math.max(0, levelOf(g) - 1 - spentPerkPoints(g));
   }

   public static float xpMultiplier(UUID playerId) {
      Guild g = getGuild(playerId);
      return g != null && g.xpPerk > 0 ? 1.0F + 0.05F * g.xpPerk : 1.0F;
   }

   public static long applyCoinPerk(UUID playerId, long amount) {
      Guild g = getGuild(playerId);
      if (g != null && g.coinPerk > 0 && amount > 0L) {
         long bonus = amount * g.coinPerk * 5L / 100L;
         return amount + Math.max(0L, bonus);
      } else {
         return amount;
      }
   }

   /** Might: outgoing damage against non-player mobs. Applied in
    *  {@code CombatGear.apply} so every weapon and enchantment path shares it. */
   public static float damageMultiplier(UUID playerId) {
      Guild g = getGuild(playerId);
      return g != null && g.mightPerk > 0 ? 1.0F + 0.04F * g.mightPerk : 1.0F;
   }

   /** Ward: incoming damage from mobs (never from players - PvP balance is
    *  governed by the guild's friendly-fire rule instead). */
   public static float damageTakenMultiplier(UUID playerId) {
      Guild g = getGuild(playerId);
      return g != null && g.wardPerk > 0 ? 1.0F - 0.04F * g.wardPerk : 1.0F;
   }

   /** Rally: extra damage against anything {@code BossManager.isBoss} recognises. */
   public static float bossDamageMultiplier(UUID playerId) {
      Guild g = getGuild(playerId);
      return g != null && g.rallyPerk > 0 ? 1.0F + 0.05F * g.rallyPerk : 1.0F;
   }

   public static List<Guild> invitesFor(UUID playerId) {
      List<Guild> out = new ArrayList<>();
      Set<UUID> ids = invites.get(playerId);
      if (ids == null) {
         return out;
      }

      for (UUID id : ids) {
         Guild g = guilds.get(id);
         if (g != null && !playerGuild.containsKey(playerId)) {
            out.add(g);
         }
      }

      return out;
   }

   private static String sanitizeName(String raw) {
      if (raw == null) {
         return null;
      }

      String name = raw.trim().replaceAll("\\s+", " ");
      if (name.length() >= 3 && name.length() <= 30) {
         for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != ' ' && c != '\'') {
               return null;
            }
         }

         return name;
      } else {
         return null;
      }
   }

   public static String create(ServerPlayer player, String rawName) {
      String name = sanitizeName(rawName);
      if (name == null) {
         return "Guild names are 3-30 characters (letters, numbers, spaces, apostrophes).";
      }

      if (playerGuild.containsKey(player.getUUID())) {
         return "You're already in a guild - /guild leave first.";
      }

      if (byName(name) != null) {
         return "A guild named \"" + name + "\" already exists.";
      }

      Guild g = new Guild(UUID.randomUUID(), name, player.getUUID());
      g.members.add(player.getUUID());
      guilds.put(g.id, g);
      playerGuild.put(player.getUUID(), g.id);
      noteName(player.getUUID(), player.getName().getString());
      Chat.raw(player, "§6§lYou founded the guild §e\"" + name + "\"§6§l!§r §7Invite friends with /guild invite <player>.");
      SoundUtil.play(player, ModSounds.CLAIM);
      return null;
   }

   // ---------- Ranks & permissions ----------

   /** Guild ranks, in descending order of power. */
   public enum Rank {
      FOUNDER("Founder"),
      OFFICER("Officer"),
      MEMBER("Member");

      public final String label;

      Rank(String label) {
         this.label = label;
      }

      public static Rank of(Guild g, UUID playerId) {
         if (g == null || playerId == null) {
            return null;
         }

         if (g.officers.contains(playerId)) {
            return OFFICER;
         }

         return g.owner.equals(playerId) ? FOUNDER : MEMBER;
      }
   }

   /** A message in a guild's mailbox. Attachments are raw ItemStacks. */
   public static class Mail {
      public final int id;
      public final UUID sender;
      public final String senderName;
      public final String subject;
      public final List<ItemStack> items = new ArrayList<>();
      public boolean claimed = false;
      public final long createdAt = System.currentTimeMillis();
      public long claimedAt = 0L;

      Mail(int id, UUID sender, String senderName, String subject) {
         this.id = id;
         this.sender = sender;
         this.senderName = senderName;
         this.subject = subject;
      }
   }

   public static Rank rankOf(Guild g, UUID playerId) {
      return Rank.of(g, playerId);
   }

   public static String rankLabel(Guild g, UUID playerId) {
      Rank r = Rank.of(g, playerId);
      return r == null ? "Member" : r.label;
   }

   /** Officers and the founder may manage the guild (ranks, mail, invites). */
   public static boolean canManage(Guild g, UUID playerId) {
      Rank r = Rank.of(g, playerId);
      return r == Rank.FOUNDER || r == Rank.OFFICER;
   }

   public static String promote(ServerPlayer actor, String targetName) {
      Guild g = getGuild(actor.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!canManage(g, actor.getUUID())) {
         return "Only officers and the founder can manage ranks.";
      }

      UUID target = resolveMember(g, targetName);
      if (target == null) {
         return "No guild member named \"" + targetName + "\".";
      }

      if (target.equals(g.owner)) {
         return "The founder's rank never changes.";
      }

      if (g.officers.add(target)) {
         Chat.raw(actor, "§aPromoted §f" + displayName(serverRef, target, targetName) + "§a to §6Officer§a.");
         ServerPlayer t = serverRef == null ? null : serverRef.getPlayerList().getPlayer(target);
         if (t != null) {
            Chat.raw(t, "§6§lYou've been promoted to §eOfficer§6§l in \"" + g.name + "\"!");
         }

         save(serverRef);
         return null;
      }

      return displayName(serverRef, target, targetName) + " is already an officer.";
   }

   public static String demote(ServerPlayer actor, String targetName) {
      Guild g = getGuild(actor.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!canManage(g, actor.getUUID()))
      {
         return "Only officers and the founder can manage ranks.";
      }

      UUID target = resolveMember(g, targetName);
      if (target == null) {
         return "No guild member named \"" + targetName + "\".";
      }

      if (target.equals(g.owner)) {
         return "The founder's rank never changes.";
      }

      if (g.officers.remove(target)) {
         Chat.raw(actor, "§7Demoted §f" + displayName(serverRef, target, targetName) + "§7 to §fMember§7.");
         ServerPlayer t = serverRef == null ? null : serverRef.getPlayerList().getPlayer(target);
         if (t != null) {
            Chat.raw(t, "§7You're now a §fMember§7 of \"" + g.name + "\".");
         }

         save(serverRef);
         return null;
      }

      return displayName(serverRef, target, targetName) + " isn't an officer.";
   }

   private static UUID resolveMember(Guild g, String name) {
      if (serverRef != null) {
         ServerPlayer online = serverRef.getPlayerList().getPlayerByName(name);
         if (online != null && g.members.contains(online.getUUID())) {
            return online.getUUID();
         }
      }

      for (UUID mid : g.members) {
         if (knownNames.getOrDefault(mid, "").equalsIgnoreCase(name)) {
            return mid;
         }
      }

      return null;
   }

   // ---------- Guild mail ----------

   /**
    * Send a mail message with one attached item to a guildmate by name.
    * Only officers and the founder can send; attachments transfer real items.
    */
   public static String sendMail(ServerPlayer sender, String targetName, String subject, ItemStack item, String note) {
      Guild g = getGuild(sender.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!canManage(g, sender.getUUID())) {
         return "Only officers and the founder can send guild mail.";
      }

      UUID target = resolveMember(g, targetName);
      if (target == null) {
         return "No guild member named \"" + targetName + "\".";
      }

      if (target.equals(sender.getUUID())) {
         return "You can't mail yourself - just keep the item!";
      }

      if (item == null || item.isEmpty()) {
         return "Attach an item to send.";
      }

      List<Mail> box = mailboxes.computeIfAbsent(target, k -> new ArrayList<>());
      if (box.size() >= MAX_MAILBOX) {
         return "Their mailbox is full (" + MAX_MAILBOX + " messages) - they must claim their mail first.";
      }

      Mail m = new Mail(nextMailId++, sender.getUUID(), sender.getName().getString(), subject == null || subject.isBlank() ? "A gift from " + sender.getName().getString() : subject.trim());
      m.items.add(item.copy());
      box.add(m);
      save(serverRef);

      ServerPlayer t = serverRef == null ? null : serverRef.getPlayerList().getPlayer(target);
      if (t != null) {
         Chat.raw(t, "§6§lGuild mail!§r §7" + sender.getName().getString() + " sent you \"§f" + m.subject + "§7\" with an attachment. Open §f/guild mail§7.");
         SoundUtil.play(t, ModSounds.MYSTERY);
      }

      return null;
   }

   public static List<Mail> mailboxOf(UUID playerId) {
      return mailboxes.getOrDefault(playerId, List.of());
   }

   /** True when the member has at least one unclaimed message. */
   public static boolean hasUnclaimedMail(UUID playerId) {
      for (Mail m : mailboxOf(playerId)) {
         if (!m.claimed) {
            return true;
         }
      }

      return false;
   }

   /** Claim a message's attachment; returns the item, or null if already claimed/gone. */
   public static ItemStack claimMail(UUID playerId, int mailId) {
      List<Mail> box = mailboxes.get(playerId);
      if (box == null) {
         return null;
      }

      for (Mail m : box) {
         if (m.id == mailId && !m.claimed && !m.items.isEmpty()) {
            m.claimed = true;
            m.claimedAt = System.currentTimeMillis();
            save(serverRef);
            return m.items.get(0).copy();
         }
      }

      return null;
   }

   /** Delete one claimed message from the mailbox. */
   public static void deleteMail(UUID playerId, int mailId) {
      List<Mail> box = mailboxes.get(playerId);
      if (box != null) {
         box.removeIf(m -> m.id == mailId);
         save(serverRef);
      }
   }

   /** Periodic cleanup: claimed mail older than the retention window is deleted. */
   public static void tickMailboxCleanup(MinecraftServer server) {
      if (mailboxes.isEmpty()) {
         return;
      }

      boolean changed = false;
      long cutoff = System.currentTimeMillis() - MAIL_RETENTION_MS;
      Iterator<Entry<UUID, List<Mail>>> it = mailboxes.entrySet().iterator();

      while (it.hasNext()) {
         List<Mail> box = it.next().getValue();
         changed |= box.removeIf(m -> m.claimed && m.claimedAt > 0L && m.claimedAt < cutoff);
         if (box.isEmpty()) {
            it.remove();
         }
      }

      if (changed) {
         save(server);
      }
   }

   public static String invite(ServerPlayer inviter, ServerPlayer target) {
      Guild g = getGuild(inviter.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!canManage(g, inviter.getUUID())) {
         return "Only officers and the founder can invite new members.";
      }

      if (inviter.getUUID().equals(target.getUUID())) {
         return "You can't invite yourself.";
      }


      UUID targetGuild = playerGuild.get(target.getUUID());
      if (g.id.equals(targetGuild)) {
         return target.getName().getString() + " is already in your guild.";
      }

      if (targetGuild != null) {
         return target.getName().getString() + " is already in another guild.";
      }

      invites.computeIfAbsent(target.getUUID(), k -> new LinkedHashSet<>()).add(g.id);
      Chat.raw(target, "§6§l" + inviter.getName().getString() + "§r §7invited you to join the guild §e\"" + g.name + "\"§7! Open §f/guild§7 to join.");
      SoundUtil.play(target, ModSounds.MYSTERY);
      Chat.raw(inviter, "§7Invited §f" + target.getName().getString() + "§7 to §e\"" + g.name + "\"§7.");
      return null;
   }

   public static String join(ServerPlayer player, UUID guildId) {
      if (playerGuild.containsKey(player.getUUID())) {
         return "You're already in a guild - /guild leave first.";
      }

      Guild g = guilds.get(guildId);
      if (g == null) {
         return "That guild no longer exists.";
      }

      Set<UUID> inv = invites.get(player.getUUID());
      if (inv != null && inv.contains(guildId)) {
         g.members.add(player.getUUID());
         playerGuild.put(player.getUUID(), g.id);
         noteName(player.getUUID(), player.getName().getString());
         inv.remove(guildId);
         if (inv.isEmpty()) {
            invites.remove(player.getUUID());
         }

         Chat.raw(player, "§a§lYou joined §e\"" + g.name + "\"§a§l!§r §7Say hi to your guildmates.");
         SoundUtil.play(player, ModSounds.JOB_COMPLETE);

         for (UUID mid : g.members) {
            ServerPlayer m = player.level().getServer() == null ? null : player.level().getServer().getPlayerList().getPlayer(mid);
            if (m != null && !mid.equals(player.getUUID())) {
               Chat.raw(m, "§e" + player.getName().getString() + "§7 joined the guild.");
            }
         }

         return null;
      } else {
         return "You haven't been invited to \"" + g.name + "\" - ask a member to invite you.";
      }
   }

   /** How long a {@code /guild leave} confirmation stays armed. */
   private static final long LEAVE_CONFIRM_WINDOW_MS = 30_000L;
   /** Players who typed {@code /guild leave} and are one confirmation away from
    *  actually leaving. Deliberately memory-only: a restart should never leave a
    *  half-committed departure lying around, and the window is only 30 seconds. */
   private static final Map<UUID, LeaveRequest> pendingLeave = new HashMap<>();

   private static final class LeaveRequest {
      final UUID guildId;
      final long until;

      LeaveRequest(UUID guildId, long until) {
         this.guildId = guildId;
         this.until = until;
      }
   }

   /**
    * Step one of leaving: <b>arms</b> the departure and explains what it costs.
    * Nothing is written yet - {@link #leaveConfirm(ServerPlayer)} does that.
    *
    * <p>Leaving used to be a single command (and a single misclick in the guild
    * menu) that silently dropped a member's claims, mine access and mail. Now the
    * destructive step can only happen after the player has seen exactly what they
    * lose, and a founder sees the extra warning that their whole guild folds.
    *
    * @return an error string when leaving is impossible, otherwise {@code null}
    *         (the request itself succeeded; it is not the departure)
    */
   public static String leave(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      boolean founder = player.getUUID().equals(g.owner);
      pendingLeave.put(player.getUUID(), new LeaveRequest(g.id, System.currentTimeMillis() + LEAVE_CONFIRM_WINDOW_MS));
      Chat.raw(player, " ");
      Chat.raw(player, "§eLeave §f\"" + g.name + "\"§e?");
      if (founder && g.members.size() > 1) {
         Chat.raw(player, "§cYou are the founder - leaving disbands the guild for all §f" + g.members.size() + "§c members.");
      } else if (founder) {
         Chat.raw(player, "§cYou are the founder - leaving disbands the guild.");
      } else {
         Chat.raw(player, "§7You'll lose access to its claims, guild mine, mail and skills.");
      }
      Chat.raw(player, "§7Type §f/guild leave confirm §7within 30s to go through, or §f/guild leave cancel §7to stay.");
      SoundUtil.play(player, ModSounds.CLAIM);
      return null;
   }

   /** Step two of leaving: executes a previously armed {@link #leave(ServerPlayer)}. */
   public static String leaveConfirm(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         pendingLeave.remove(player.getUUID());
         return "You're not in a guild.";
      }

      LeaveRequest request = pendingLeave.remove(player.getUUID());
      if (request == null || request.until < System.currentTimeMillis() || !request.guildId.equals(g.id)) {
         return "Nothing to confirm - run /guild leave first, then confirm within 30 seconds.";
      }

      performLeave(player, g);
      return null;
   }

   /** Step two, aborted: disarms a pending departure. Never destructive. */
   public static String leaveCancel(ServerPlayer player) {
      if (pendingLeave.remove(player.getUUID()) != null) {
         Chat.raw(player, "§7Staying in your guild.");
      } else {
         Chat.raw(player, "§7You don't have a guild leave waiting on confirmation.");
      }
      return null;
   }

   /** True while this player has an armed, unexpired departure. */
   public static boolean hasPendingLeave(ServerPlayer player) {
      LeaveRequest request = player == null ? null : pendingLeave.get(player.getUUID());
      return request != null && request.until >= System.currentTimeMillis();
   }

   private static void performLeave(ServerPlayer player, Guild g) {
      UUID leaving = player.getUUID();
      playerGuild.remove(leaving);
      g.members.remove(leaving);
      g.officers.remove(leaving);
      cleanupGuildClaims(player, g, leaving);
      if (!g.members.isEmpty() && !leaving.equals(g.owner)) {
         Chat.raw(player, "§7You left §e\"" + g.name + "\"§7.");

         for (UUID mid : g.members) {
            ServerPlayer m = player.level().getServer() == null ? null : player.level().getServer().getPlayerList().getPlayer(mid);
            if (m != null) {
               Chat.raw(m, "§e" + player.getName().getString() + "§7 left the guild.");
            }
         }
      } else {
         disband(player, g, "The guild \"" + g.name + "\" has been disbanded.");
      }
   }

   private static void cleanupGuildClaims(ServerPlayer player, Guild g, UUID leaving) {
      ClaimManager.stripFromGuildClaims(g, leaving);
   }

   public static String disband(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can disband it.";
      }

      disband(player, g, "§cThe guild \"" + g.name + "\" has been disbanded by its founder.");
      return null;
   }

   private static void disband(ServerPlayer player, Guild g, String msg) {
      for (UUID mid : new ArrayList<>(g.members)) {
         playerGuild.remove(mid);
         ClaimManager.stripFromGuildClaims(g, mid);
         ServerPlayer m = player.level().getServer() == null ? null : player.level().getServer().getPlayerList().getPlayer(mid);
         if (m != null) {
            Chat.raw(m, msg);
         }
      }

      for (Set<UUID> inv : invites.values()) {
         inv.remove(g.id);
      }

      invites.values().removeIf(Set::isEmpty);
      guilds.remove(g.id);
      SoundUtil.play(player, ModSounds.DENY);
   }

   public static String setMotd(ServerPlayer player, String raw) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can set the message of the day.";
      }

      String text = raw == null ? "" : raw.trim();
      if (text.length() > 60) {
         return "The message of the day is at most 60 characters.";
      }

      g.motd = text;
      if (text.isEmpty()) {
         Chat.raw(player, "§7Cleared the guild's message of the day.");
      } else {
         Chat.raw(player, "§6§lMOTD set:§r §e" + text);

         for (UUID mid : g.members) {
            ServerPlayer m = player.level().getServer() == null ? null : player.level().getServer().getPlayerList().getPlayer(mid);
            if (m != null && !mid.equals(player.getUUID())) {
               Chat.raw(m, "§6§l" + g.name + "§r §7MOTD: §e" + text);
            }
         }
      }

      SoundUtil.play(player, ModSounds.JOB_COMPLETE);
      return null;
   }

   public static String rankUpPerk(ServerPlayer player, String key) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can spend guild perk points.";
      }

      if (!isValidPerk(key)) {
         return "There's no guild skill called \"" + key + "\".";
      }

      if (availablePerkPoints(g) <= 0) {
         return "No unspent perk points - level the guild up with PvP and PvE.";
      }

      int cur = perkRank(g, key);
      if (cur >= MAX_PERK) {
         return perkName(key) + " is already maxed out.";
      }

      if (!setPerkRank(g, key, cur + 1)) {
         return "There's no guild skill called \"" + key + "\".";
      }

      String perk = perkName(key);
      int newRank = cur + 1;
      String effect = perkEffect(key, newRank);
      Chat.raw(player, "§6§l" + perk + " ranked up to " + newRank + "!§r §7" + effect + ".");

      for (UUID mid : g.members) {
         ServerPlayer m = player.level().getServer() == null ? null : player.level().getServer().getPlayerList().getPlayer(mid);
         if (m != null && !mid.equals(player.getUUID())) {
            Chat.raw(m, "§6§l" + g.name + "§r §7unlocked §e" + perk + " " + newRank + "§7 (" + effect + ").");
         }
      }

      SoundUtil.play(player, ModSounds.JOB_COMPLETE);
      return null;
   }

   public static String refundPerk(ServerPlayer player, String key) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can refund guild perks.";
      }

      if (!isValidPerk(key)) {
         return "There's no guild skill called \"" + key + "\".";
      }

      int cur = perkRank(g, key);
      if (cur <= 0) {
         return perkName(key) + " has no ranks to refund.";
      }

      if (!EconomyManager.hasCash(player.getUUID(), PERK_REFUND_FEE)) {
         return "Refunding a rank costs " + Chat.moneyStr(PERK_REFUND_FEE) + " - you don't have enough.";
      }

      EconomyManager.takeCash(player.getUUID(), PERK_REFUND_FEE);
      setPerkRank(g, key, cur - 1);

      String perk = perkName(key);
      int newRank = cur - 1;
      Chat.raw(player, "§6§l" + perk + " refunded to rank " + newRank + "§r §7for " + Chat.moneyStr(PERK_REFUND_FEE) + ". The perk point is back in the pool.");
      SoundUtil.play(player, ModSounds.CLAIM);
      return null;
   }

   public static String toggleFriendlyFire(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }

      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can change guild settings.";
      }

      g.friendlyFire = !g.friendlyFire;
      Chat.raw(
         player,
         g.friendlyFire
            ? "§cFriendly Fire ON§7 - guildmates deal §c100%§7 damage to each other."
            : "§aFriendly Fire OFF§7 - guildmates deal §a25% less§7 damage to each other."
      );

      for (UUID mid : g.members) {
         ServerPlayer m = player.level().getServer() == null ? null : player.level().getServer().getPlayerList().getPlayer(mid);
         if (m != null && !mid.equals(player.getUUID())) {
            Chat.raw(m, g.friendlyFire ? "§7Your guild turned §cFriendly Fire ON§7." : "§7Your guild turned §aFriendly Fire OFF§7 (25% less mate damage).");
         }
      }

      return null;
   }

   public static void addPvp(UUID playerId, long amount) {
      if (amount <= 0L) {
         return;
      }

      Guild g = getGuild(playerId);
      if (g != null) {
         int before = levelOf(g);
         g.pvp += amount;
         notifyLevelUp(g, before);
         checkPvpMilestone(g);
      }
   }

   // ---------- Guild wars ----------

   public static boolean atWar(Guild a, Guild b) {
      return a != null && b != null && a.warWith != null && a.warWith.equals(b.id) && b.warWith != null && b.warWith.equals(a.id);
   }

   public static boolean atWar(UUID playerId, UUID otherId) {
      Guild a = getGuild(playerId);
      Guild b = getGuild(otherId);
      return atWar(a, b);
   }

   public static String declareWar(ServerPlayer player, String guildName) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }
      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can declare war.";
      }
      Guild target = byName(guildName);
      if (target == null) {
         return "No guild named \"" + guildName + "\" exists.";
      }
      if (target.id.equals(g.id)) {
         return "You can't declare war on your own guild.";
      }
      if (target.warWith != null && !target.warWith.equals(g.id)) {
         return target.name + " is already at war with another guild.";
      }
      if (g.warWith != null && g.warWith.equals(target.id)) {
         return "You are already at war with " + target.name + ".";
      }
      if (EconomyManager.hasCash(player.getUUID(), 25000L)) {
         EconomyManager.takeCash(player.getUUID(), 25000L);
      } else {
         return "Declaring war costs " + Chat.moneyStr(25000L) + " - you don't have enough.";
      }
      g.warWith = target.id;
      target.warWith = g.id;
      for (UUID mid : g.members) {
         ServerPlayer m = serverRef == null ? null : serverRef.getPlayerList().getPlayer(mid);
         if (m != null) {
            Chat.raw(m, "§c§l⚔ WAR! §r§7" + g.name + " has declared war on §c" + target.name + "§7! PvP kills count double.");
         }
      }
      for (UUID mid : target.members) {
         ServerPlayer m = serverRef == null ? null : serverRef.getPlayerList().getPlayer(mid);
         if (m != null) {
            Chat.raw(m, "§c§l⚔ WAR! §r§7" + g.name + " has declared war on §c" + target.name + "§7! Defend your guild.");
         }
      }
      SoundUtil.play(player, ModSounds.BOSS_SPAWN);
      save(serverRef);
      return null;
   }

   public static String peace(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }
      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can negotiate peace.";
      }
      if (g.warWith == null) {
         return "Your guild isn't at war.";
      }
      Guild enemy = guilds.get(g.warWith);
      if (enemy != null) {
         enemy.warWith = null;
         for (UUID mid : enemy.members) {
            ServerPlayer m = serverRef == null ? null : serverRef.getPlayerList().getPlayer(mid);
            if (m != null) {
               Chat.raw(m, "§a☮ " + g.name + " has declared peace - the war is over.");
            }
         }
      }
      g.warWith = null;
      Chat.raw(player, "§a☮ Peace declared - your guild is no longer at war.");
      save(serverRef);
      return null;
   }

   public static void tickEnemyParticles(net.minecraft.server.MinecraftServer server) {
      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      if (players.isEmpty()) {
         return;
      }

      // O(P): group players by the war they are in so we never do a full
      // players x players scan every 4 ticks (the old loop was O(P^2) and
      // could stall the server at 40-50+ online).
      Map<UUID, List<ServerPlayer>> wartime = new HashMap<>();

      for (ServerPlayer p : players) {
         Guild g = getGuild(p.getUUID());
         if (g == null || g.warWith == null) {
            continue;
         }

         List<ServerPlayer> side = wartime.computeIfAbsent(g.id, k -> new ArrayList<>());
         side.add(p);

         List<ServerPlayer> enemy = wartime.computeIfAbsent(g.warWith, k -> new ArrayList<>());
         enemy.add(p);
      }

      if (wartime.isEmpty()) {
         return;
      }

      for (Entry<UUID, List<ServerPlayer>> e : wartime.entrySet()) {
         List<ServerPlayer> enemies = e.getValue();
         if (enemies.size() < 2) {
            continue;
         }

         int n = enemies.size();

         for (int a = 0; a < n; a++) {
            ServerPlayer viewer = enemies.get(a);
            if (viewer == null || !viewer.isAlive()) {
               continue;
            }

            for (int b = a + 1; b < n; b++) {
               ServerPlayer other = enemies.get(b);
               if (other == null || !other.isAlive()) {
                  continue;
               }

               if (viewer.getUUID().equals(other.getUUID()) || viewer.distanceToSqr(other) >= 900.0) {
                  continue;
               }

               ServerLevel level = other.level();
               double dx = (other.getX() - viewer.getX()) * 0.5;
               double dy = (other.getY() - viewer.getY()) * 0.5;
               double dz = (other.getZ() - viewer.getZ()) * 0.5;
               // Server broadcast centred between the pair so every client in
               // range sees the marker over their own screen.
               level.sendParticles(
                  net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
                  viewer.getX() + dx,
                  viewer.getY() + 2.0 + dy,
                  viewer.getZ() + dz,
                  1,
                  0.0F,
                  0.0F,
                  0.0F,
                  0.0
               );
            }
         }
      }
   }

   // ---------- Guild mine ----------

   public static String mineUpgrade(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }
      if (!player.getUUID().equals(g.owner)) {
         return "Only the guild's founder can upgrade the mine.";
      }
      if (g.mineLevel >= 6) {
         return "The guild mine is already maxed at level 6.";
      }
      long cost = mineUpgradeCost(g.mineLevel);
      if (!EconomyManager.hasCash(player.getUUID(), cost)) {
         return "Upgrading the mine to level " + (g.mineLevel + 1) + " costs " + Chat.moneyStr(cost) + " - not enough cash.";
      }
      EconomyManager.takeCash(player.getUUID(), cost);
      g.mineLevel++;
      // Stock is kept through upgrades so deposits are never wasted.
      g.mineNextTick = serverRef == null ? 0L : serverRef.getTickCount() + mineIntervalTicks(g.mineLevel);
      Chat.raw(player, "§6§lGuild Mine upgraded to level " + g.mineLevel + "!§r §7Cost " + Chat.moneyStr(cost) + ".");
      com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
         serverRef, "Guild \"" + g.name + "\" upgraded its mine to level " + g.mineLevel + " (" + Chat.moneyStr(cost) + ")."
      );
      for (UUID mid : g.members) {
         ServerPlayer m = serverRef == null ? null : serverRef.getPlayerList().getPlayer(mid);
         if (m != null && !mid.equals(player.getUUID())) {
            Chat.raw(m, "§6The guild mine is now level " + g.mineLevel + "§7 - resources will flow faster.");
         }
      }
      save(serverRef);
      return null;
   }

   public static long mineUpgradeCost(int level) {
      return switch (level) {
         case 0 -> 25_000L;      // Lv1 Coal Shaft
         case 1 -> 75_000L;      // Lv2 Iron Shaft
         case 2 -> 250_000L;     // Lv3 Gold Shaft
         case 3 -> 750_000L;     // Lv4 Diamond Shaft
         case 4 -> 2_500_000L;   // Lv5 Netherite Shaft
         default -> 10_000_000L; // Lv6 Mythic Shaft
      };
   }

   public static long mineIntervalTicks(int level) {
      return switch (level) {
         case 1 -> 6000L;
         case 2 -> 4000L;
         case 3 -> 2500L;
         case 4 -> 1500L;
         case 5 -> 800L;
         default -> 500L;
      };
   }

   public static long minePerTick(int level) {
      return switch (level) {
         case 1 -> 500L;
         case 2 -> 1500L;
         case 3 -> 4000L;
         case 4 -> 10000L;
         case 5 -> 25000L;
         default -> 60000L;
      };
   }

   public static long minePayoutThreshold() {
      return 100000L;
   }

   /** Display name of the mine at a given level (1-6), cheapest → most expensive. */
   public static String mineTierName(int level) {
      return switch (level) {
         case 1 -> "Coal Shaft";
         case 2 -> "Iron Shaft";
         case 3 -> "Gold Shaft";
         case 4 -> "Diamond Shaft";
         case 5 -> "Netherite Shaft";
         default -> "Mythic Shaft";
      };
   }

   public static int mineProgressPercent(Guild g) {
      return (int)Math.min(100L, g.mineStock * 100L / minePayoutThreshold());
   }

   /** Estimated seconds until the next automatic payout, or -1 if the mine isn't running. */
   public static long mineEtaSeconds(Guild g) {
      if (g.mineLevel <= 0 || serverRef == null) {
         return -1L;
      }
      long remaining = Math.max(0L, g.mineNextTick - serverRef.getTickCount());
      long ticksUntilPayout = remaining + (minePayoutThreshold() - g.mineStock) * mineIntervalTicks(g.mineLevel) / Math.max(1L, minePerTick(g.mineLevel));
      return ticksUntilPayout / 20L;
   }

   public static void tickMines(net.minecraft.server.MinecraftServer server) {
      long now = server.getTickCount();
      for (Guild g : guilds.values()) {
         if (g.mineLevel <= 0) {
            continue;
         }
         if (g.mineNextTick == 0L) {
            g.mineNextTick = now + mineIntervalTicks(g.mineLevel);
            continue;
         }
         if (now >= g.mineNextTick) {
            g.mineStock += minePerTick(g.mineLevel);
            g.mineNextTick = now + mineIntervalTicks(g.mineLevel);
            if (g.mineStock >= 100000L) {
               long payout = g.mineStock;
               g.mineStock = 0L;
               // Payout sharing: the founder takes 70% - every online member splits the
               // remaining 30% equally. If no members are online the founder gets it all.
               long founderShare = payout;
               List<UUID> onlineMembers = new ArrayList<>();
               for (UUID mid : g.members) {
                  if (!mid.equals(g.owner) && server.getPlayerList().getPlayer(mid) != null) {
                     onlineMembers.add(mid);
                  }
               }
               if (!onlineMembers.isEmpty()) {
                  long membersShare = payout * 30L / 100L;
                  long perMember = membersShare / onlineMembers.size();
                  if (perMember > 0L) {
                     founderShare = payout - perMember * onlineMembers.size();
                     for (UUID mid : onlineMembers) {
                        EconomyManager.addCash(mid, perMember);
                        ServerPlayer m = server.getPlayerList().getPlayer(mid);
                        if (m != null && !mineNotifyOff(g, mid)) {
                           Chat.raw(m, "§6§lGuild Mine payout: §a$" + perMember + "§6§l! §7Level " + g.mineLevel + " mine (member share).");
                        }
                     }
                  }
               }
               EconomyManager.addCash(g.owner, founderShare);
               ServerPlayer leader = server.getPlayerList().getPlayer(g.owner);
               if (leader != null && !mineNotifyOff(g, g.owner)) {
                  Chat.raw(leader, "§6§lGuild Mine payout: §a$" + founderShare + "§6§l! §7Level " + g.mineLevel + " mine" + (onlineMembers.isEmpty() ? "." : " (founder share)."));
               }
            }
         }
      }
   }

   public static String mineDeposit(ServerPlayer player, long amount) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }
      if (g.mineLevel <= 0) {
         return "Your guild doesn't have a mine yet - /guild mine to build one.";
      }
      if (amount <= 0L) {
         return "Use /guild mine <amount> to deposit cash into the mine.";
      }
      if (!EconomyManager.hasCash(player.getUUID(), amount)) {
         return "You don't have that much cash.";
      }
      EconomyManager.takeCash(player.getUUID(), amount);
      g.mineStock += amount;
      Chat.raw(player, "§6Deposited " + Chat.moneyStr(amount) + " into the guild mine (total §f" + Chat.moneyStr(g.mineStock) + "§6).");
      save(serverRef);
      return null;
   }

   public static boolean mineNotifyOff(Guild g, UUID uuid) {
      return Boolean.TRUE.equals(g.mineNotifyOff.get(uuid));
   }

   /** Toggles the mine payout chat notification for the calling member; returns the new state as a message. */
   public static String mineNotifyToggle(ServerPlayer player) {
      Guild g = getGuild(player.getUUID());
      if (g == null) {
         return "You're not in a guild.";
      }
      boolean off = !mineNotifyOff(g, player.getUUID());
      g.mineNotifyOff.put(player.getUUID(), off);
      save(serverRef);
      return off ? "Mine payout notifications are now OFF." : "Mine payout notifications are now ON.";
   }

   public static void addWealth(UUID playerId, long amount) {
      Guild g = getGuild(playerId);
      if (g != null && amount > 0L) {
         g.wealth += amount;
      }
   }

   public static void addPve(UUID playerId, long amount) {
      if (amount <= 0L) {
         return;
      }

      Guild g = getGuild(playerId);
      if (g != null) {
         int before = levelOf(g);
         g.pve += amount;
         notifyLevelUp(g, before);
         // Recurring Stab Shot: one per 30 PvE score, straight to the earner.
         checkPveMilestone(playerId);
      }
   }

   /**
    * Bosses whose PvE score has already been paid.
    *
    * <p>A raid boss hands the fight to its own ceremony and then ends it with the
    * mod's own blow, so vanilla records no attacker and every reward that asks
    * "who killed this" is skipped - which is why guild PvE score stopped moving
    * while the bosses themselves died perfectly well. It is credited where the
    * kill is decided now, and this ledger is what makes that safe: a boss that a
    * ceremony ends and a death event then reports again is still one kill, and a
    * boss that simply falls over is still one kill too.
    */
   private static final Set<UUID> SCORED_BOSSES = new java.util.HashSet<>();

   /**
    * Score for a boss kill, paid once per boss however many routes report it.
    * Every route - a death event, a boss's own ending - is the same kill, so the
    * ledger, not the caller, decides whether it counts.
    */
   public static void creditBossKill(UUID playerId, UUID bossId) {
      if (playerId == null || bossId == null || SCORED_BOSSES.contains(bossId)) {
         return;
      }

      if (getGuild(playerId) == null) {
         // Nobody to pay, so the kill is not spent: a boss whose first report comes
         // from a guildless player (a passer-by, an arena spectator) must still pay
         // the guild that actually killed it when the second report arrives.
         return;
      }

      SCORED_BOSSES.add(bossId);
      addPve(playerId, 1L);
   }

   /** True once a boss has paid its PvE score, whoever reported it. */
   public static boolean bossScorePaid(UUID bossId) {
      return bossId != null && SCORED_BOSSES.contains(bossId);
   }

   /**
    * Score for a duel a player won. A bout's losing blow is refused by the duel
    * itself, so the loser never dies and no death ever reports the win; the
    * winner is scored here, at the one place a duel is decided.
    */
   public static void creditDuelWin(ServerPlayer winner, boolean atWar) {
      if (winner != null) {
         addPvp(winner.getUUID(), atWar ? 2L : 1L);
      }
   }

   private static void checkPvpMilestone(Guild g) {
      if (g != null && !g.pvpMilestoneRewarded && g.pvp >= 30L && serverRef != null) {
         g.pvpMilestoneRewarded = true;
         save(serverRef);
         ServerPlayer leader = serverRef.getPlayerList().getPlayer(g.owner);
         if (leader != null) {
            CommandSourceStack src = leader.createCommandSourceStack().withMaximumPermission(LevelBasedPermissionSet.ADMIN).withSuppressedOutput();
            serverRef.getCommands().performPrefixedCommand(src, "function fortuneandfavors:get_stab");
            Chat.raw(leader, "§d§lGUILD MILESTONE!§r §7Your guild's PvP score reached §e30§7 - here's your §5Stab Shot§7!");
            SoundUtil.play(leader, ModSounds.JOB_COMPLETE);
         } else {
            EconomyManager.giveItem(g.owner, ModCommands.stabShotRod());
         }

         for (UUID mid : g.members) {
            ServerPlayer m = serverRef.getPlayerList().getPlayer(mid);
            if (m != null && !g.owner.equals(mid)) {
               Chat.raw(m, "§6§l" + g.name + "§r §7hit the §ePvP 30§7 milestone! The founder earned a §5Stab Shot§7.");
            }
         }
      }
   }

   /**
    * Recurring Stab Shot reward: every §ePVE_MILESTONE_STEP§7 points of PvE score a member
    * personally earns grants them (not the founder) one Stab Shot - straight to their
    * inventory, or their guild mailbox if they're offline.
    */
   private static void checkPveMilestone(UUID playerId) {
      if (serverRef == null) {
         return;
      }

      Guild g = getGuild(playerId);
      if (g == null) {
         return;
      }

      // Progress is per-member (keyed by guild+player) so score earned before this
      // system existed doesn't retroactively print a pile of rods.
      String key = g.id + ":" + playerId;
      long earned = g.pve;
      long before = pveMilestoneProgress.getOrDefault(key, Math.max(0L, earned - earned % PVE_MILESTONE_STEP));
      int due = (int)((earned / PVE_MILESTONE_STEP) - (before / PVE_MILESTONE_STEP));
      if (due <= 0) {
         return;
      }

      pveMilestoneProgress.put(key, earned - earned % PVE_MILESTONE_STEP);
      String name = displayName(serverRef, playerId, "A member");
      ServerPlayer p = serverRef.getPlayerList().getPlayer(playerId);
      if (p != null) {
         for (int i = 0; i < due; i++) {
            InventoryHelper.giveOrDrop(p, ModCommands.stabShotRod());
         }

         Chat.raw(p, "§d§lGUILD PVE MILESTONE!§r §7You've slain §e" + earned + "§7 bosses for §e\"" + g.name + "\"§7 - that's " + (due == 1 ? "a new" : due + " new") + " §5Stab Shot§7!");
         SoundUtil.play(p, ModSounds.JOB_COMPLETE);
      } else {
         // Offline: the rod waits in their guild mailbox (falls back to giveItem
         // if their box is full so the reward is never lost).
         ItemStack rod = ModCommands.stabShotRod();
         rod.setCount(Math.max(1, Math.min(due, rod.getMaxStackSize())));
         List<Mail> box = mailboxes.computeIfAbsent(playerId, k -> new ArrayList<>());
         if (box.size() < MAX_MAILBOX) {
            Mail m = new Mail(nextMailId++, g.id, g.name, "PvE Milestone Reward");
            m.items.add(rod);
            box.add(m);
         } else {
            EconomyManager.giveItem(playerId, rod);
         }
      }

      for (ServerPlayer viewer : serverRef.getPlayerList().getPlayers()) {
         Chat.raw(viewer, "§6§l" + g.name + "§r §d" + name + "§7 earned a §5Stab Shot§7 for §e" + earned + "§7 total PvE score!");
      }

      save(serverRef);
   }

   private static void notifyLevelUp(Guild g, int before) {
      int after = levelOf(g);
      if (after > before) {
         for (UUID mid : g.members) {
            ServerPlayer m = serverRef == null ? null : serverRef.getPlayerList().getPlayer(mid);
            if (m != null) {
               Chat.raw(
                  m,
                  "§6§l"
                     + g.name
                     + " reached guild level "
                     + after
                     + "!§r"
                     + (mid.equals(g.owner) ? " §7Open §f/guild skills§7 to spend the new perk point." : " §7The founder can spend a new guild perk point.")
               );
               SoundUtil.play(m, ModSounds.SKILL_UP);
            }
         }
      }
   }

   public static void load(MinecraftServer server) {
      guilds.clear();
      playerGuild.clear();
      invites.clear();
      serverRef = server;
      dataFile = EconomyManager.getDataDir(server).resolve("guilds.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("guilds") && root.get("guilds").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("guilds")) {
            try {
               JsonObject obj = el.getAsJsonObject();
               UUID id = UUID.fromString(obj.get("id").getAsString());
               String name = obj.get("name").getAsString();
               UUID owner = UUID.fromString(obj.get("owner").getAsString());
               Guild g = new Guild(id, name, owner);
               if (obj.has("members") && obj.get("members").isJsonArray()) {
                  for (JsonElement me : obj.getAsJsonArray("members")) {
                     g.members.add(UUID.fromString(me.getAsString()));
                  }
               }

               if (!g.members.contains(owner)) {
                  g.members.add(owner);
               }

               g.friendlyFire = JsonUtil.jsonBool(obj, "friendly_fire", false);
               g.motd = JsonUtil.jsonString(obj, "motd", "");
               if (obj.has("officers") && obj.get("officers").isJsonArray()) {
                  for (JsonElement oe : obj.getAsJsonArray("officers")) {
                     try {
                        UUID oid = UUID.fromString(oe.getAsString());
                        if (g.members.contains(oid) && !g.owner.equals(oid)) {
                           g.officers.add(oid);
                        }
                     } catch (Exception var16) {
                     }
                  }
               }

               g.pvp = JsonUtil.jsonLong(obj, "pvp", 0L);
               g.wealth = JsonUtil.jsonLong(obj, "wealth", 0L);
               g.pve = JsonUtil.jsonLong(obj, "pve", 0L);
               g.xpPerk = (int)JsonUtil.jsonLong(obj, "xp_perk", 0L);
               g.coinPerk = (int)JsonUtil.jsonLong(obj, "coin_perk", 0L);
               g.mightPerk = (int)JsonUtil.jsonLong(obj, "might_perk", 0L);
               g.wardPerk = (int)JsonUtil.jsonLong(obj, "ward_perk", 0L);
               g.rallyPerk = (int)JsonUtil.jsonLong(obj, "rally_perk", 0L);
               g.pvpMilestoneRewarded = JsonUtil.jsonBool(obj, "pvp_milestone_rewarded", false);
               String warWith = JsonUtil.jsonString(obj, "war_with", "");
               if (!warWith.isEmpty()) {
                  try {
                     g.warWith = UUID.fromString(warWith);
                  } catch (Exception var13) {
                  }
               }
               g.mineLevel = (int)JsonUtil.jsonLong(obj, "mine_level", 0L);
               g.mineStock = JsonUtil.jsonLong(obj, "mine_stock", 0L);
               g.mineNextTick = JsonUtil.jsonLong(obj, "mine_next_tick", 0L);
               if (obj.has("mine_notify_off") && obj.get("mine_notify_off").isJsonObject()) {
                  for (Entry<String, JsonElement> ne : obj.getAsJsonObject("mine_notify_off").entrySet()) {
                     try {
                        g.mineNotifyOff.put(UUID.fromString(ne.getKey()), ne.getValue().getAsBoolean());
                     } catch (Exception var15) {
                     }
                  }
               }
               guilds.put(g.id, g);

               for (UUID mid : g.members) {
                  playerGuild.put(mid, g.id);
               }

               if (g.pvp >= 30L && !g.pvpMilestoneRewarded) {
                  g.pvpMilestoneRewarded = true;
                  EconomyManager.giveItem(g.owner, ModCommands.stabShotRod());
               }

               if (obj.has("pve_milestone_progress") && obj.get("pve_milestone_progress").isJsonObject()) {
                  for (Entry<String, JsonElement> pe : obj.getAsJsonObject("pve_milestone_progress").entrySet()) {
                     try {
                        pveMilestoneProgress.put(pe.getKey(), pe.getValue().getAsLong());
                     } catch (Exception var17) {
                     }
                  }
               }
            } catch (Exception var12) {
            }
         }
      }

      if (root.has("invites") && root.get("invites").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("invites")) {
            try {
               JsonObject obj = el.getAsJsonObject();
               UUID who = UUID.fromString(obj.get("who").getAsString());
               Set<UUID> ids = new LinkedHashSet<>();

               for (JsonElement ge : obj.getAsJsonArray("guilds")) {
                  ids.add(UUID.fromString(ge.getAsString()));
               }

               if (!ids.isEmpty()) {
                  invites.put(who, ids);
               }
            } catch (Exception var11) {
            }
         }
      }

      // Mailboxes (attachments are raw ItemStacks resolved against the registry).
      net.minecraft.core.HolderLookup.Provider access = server.registryAccess();
      if (root.has("mailboxes") && root.get("mailboxes").isJsonObject()) {
         for (Entry<String, JsonElement> be : root.getAsJsonObject("mailboxes").entrySet()) {
            try {
               UUID who = UUID.fromString(be.getKey());
               List<Mail> box = mailboxes.computeIfAbsent(who, k -> new ArrayList<>());
               for (JsonElement me : be.getValue().getAsJsonArray()) {
                  JsonObject mo = me.getAsJsonObject();
                  Mail m = new Mail((int)JsonUtil.jsonLong(mo, "id", nextMailId++), UUID.fromString(mo.get("sender").getAsString()), JsonUtil.jsonString(mo, "sender_name", "?"), JsonUtil.jsonString(mo, "subject", "Mail"));
                  if (mo.has("items") && mo.get("items").isJsonArray()) {
                     for (JsonElement ie : mo.getAsJsonArray("items")) {
                        ItemStack s = JsonUtil.jsonToItem(ie, access);
                        if (!s.isEmpty()) {
                           m.items.add(s);
                        }
                     }
                  }

                  m.claimed = JsonUtil.jsonBool(mo, "claimed", false);
                  m.claimedAt = JsonUtil.jsonLong(mo, "claimed_at", 0L);
                  box.add(m);
                  if (m.id >= nextMailId) {
                     nextMailId = m.id + 1;
                  }
               }
            } catch (Exception var18) {
            }
         }
      }

      nextMailId = Math.max(nextMailId, (int)JsonUtil.jsonLong(root, "next_mail_id", 1L));
   }

   public static void save(MinecraftServer server) {
      try {
         dataFile = EconomyManager.getDataDir(server).resolve("guilds.json");
         JsonObject root = new JsonObject();
         JsonArray guildsArr = new JsonArray();

         for (Guild g : guilds.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", g.id.toString());
            obj.addProperty("name", g.name);
            obj.addProperty("owner", g.owner.toString());
            JsonArray members = new JsonArray();

            for (UUID mid : g.members) {
               members.add(mid.toString());
            }

            obj.add("members", members);
            JsonArray officers = new JsonArray();
            for (UUID oid : g.officers) {
               officers.add(oid.toString());
            }

            obj.add("officers", officers);
            obj.addProperty("friendly_fire", g.friendlyFire);
            obj.addProperty("motd", g.motd);
            obj.addProperty("pvp", g.pvp);
            obj.addProperty("wealth", g.wealth);
            obj.addProperty("pve", g.pve);
            obj.addProperty("xp_perk", g.xpPerk);
            obj.addProperty("coin_perk", g.coinPerk);
            obj.addProperty("might_perk", g.mightPerk);
            obj.addProperty("ward_perk", g.wardPerk);
            obj.addProperty("rally_perk", g.rallyPerk);
            obj.addProperty("pvp_milestone_rewarded", g.pvpMilestoneRewarded);
            if (g.warWith != null) {
               obj.addProperty("war_with", g.warWith.toString());
            }
            obj.addProperty("mine_level", g.mineLevel);
            obj.addProperty("mine_stock", g.mineStock);
            obj.addProperty("mine_next_tick", g.mineNextTick);
            JsonObject notifyObj = new JsonObject();
            for (Entry<UUID, Boolean> ne : g.mineNotifyOff.entrySet()) {
               notifyObj.addProperty(ne.getKey().toString(), ne.getValue());
            }
            obj.add("mine_notify_off", notifyObj);
            guildsArr.add(obj);
         }

         root.add("guilds", guildsArr);
         JsonArray invArr = new JsonArray();

         for (Entry<UUID, Set<UUID>> e : invites.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("who", e.getKey().toString());
            JsonArray gids = new JsonArray();

            for (UUID id : e.getValue()) {
               gids.add(id.toString());
            }

            obj.add("guilds", gids);
            invArr.add(obj);
         }

         root.add("invites", invArr);
         net.minecraft.core.HolderLookup.Provider access = server.registryAccess();
         JsonObject mailboxesObj = new JsonObject();

         for (Entry<UUID, List<Mail>> e : mailboxes.entrySet()) {
            JsonArray boxArr = new JsonArray();

            for (Mail m : e.getValue()) {
               JsonObject mo = new JsonObject();
               mo.addProperty("id", m.id);
               mo.addProperty("sender", m.sender.toString());
               mo.addProperty("sender_name", m.senderName);
               mo.addProperty("subject", m.subject);
               mo.addProperty("claimed", m.claimed);
               mo.addProperty("claimed_at", m.claimedAt);
               JsonArray items = new JsonArray();
               for (ItemStack s : m.items) {
                  items.add(JsonUtil.itemToJson(s, access));
               }

               mo.add("items", items);
               boxArr.add(mo);
            }

            mailboxesObj.add(e.getKey().toString(), boxArr);
         }

         root.add("mailboxes", mailboxesObj);
         root.addProperty("next_mail_id", nextMailId);
         JsonUtil.write(dataFile, root);
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.error("Failed to save guild data", e);
      }
   }


    static public final class Guild {
       public final UUID id;
       public String name;
       public final UUID owner;
       public final Set<UUID> members = new LinkedHashSet<>();
       public boolean friendlyFire = false;
       public String motd = "";
       public final Set<UUID> officers = new LinkedHashSet<>();
       public long pvp = 0L;
       public long wealth = 0L;
       public long pve = 0L;
       public int xpPerk = 0;
       public int coinPerk = 0;
       public int mightPerk = 0;
       public int wardPerk = 0;
       public int rallyPerk = 0;
       public boolean pvpMilestoneRewarded = false;
       public UUID warWith = null;
       public int mineLevel = 0;
       public long mineStock = 0L;
       public long mineNextTick = 0L;
       public final Map<UUID, Boolean> mineNotifyOff = new HashMap<>();
    
       Guild(UUID id, String name, UUID owner) {
          this.id = id;
          this.name = name;
          this.owner = owner;
       }
    }
}
