package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Expeditions, out in a group.
 *
 * <p>A party is a lobby of at most {@link #MAX_MEMBERS}, and the first person in is its host. The
 * host decides who is still welcome - clicking a name in the party window sends them out - and when
 * the group goes underground.
 *
 * <p><b>The bag is the point.</b> The pieces a party finds live in one shared pack rather than one
 * each: when the first explorer takes a bone out of a chest, that bone is in everybody's pack a
 * moment later, because there is exactly one list of pieces and each member's pack is a copy of it.
 * The pack is sized by the party - nine pieces for every name on the roster, up to the pack's own
 * ceiling - so a pair carries eighteen, a trio twenty-seven, and a full party carries the biggest
 * pack the site can hand out.
 *
 * <p><b>And so is the payday.</b> A party run does not pay four separate hauls. Everything carried
 * out goes into one pot, and every explorer who reaches a pad is paid the whole pot, with the ones
 * who got out earlier topped up as it grows, so that by the time the last of you is standing on a pad
 * you have all been paid exactly the same. That cuts both ways and is meant to: loot that is lost is
 * lost for the group, so a party run's real question is no longer only what you are carrying but
 * whether the person beside you is going to make it back. An explorer who dies contributes nothing
 * and is paid nothing, exactly as they are alone.
 *
 * <p>Every entry point here is null-tolerant: with no party - which is every solo run - the pack and
 * the payout are the ones the solo run already had, so nothing in this class changes for a player who
 * never opens the party window.
 */
public final class PartyManager {
   /** How many explorers fit in one party. Four, because a pack holds four times its base size. */
   public static final int MAX_MEMBERS = 4;

   /** One party: who is in it, what is in its bag, and what its run has secured so far. */
   public static final class Party {
      private UUID host;
      /** The roster, host first, in join order. */
      private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
      /** The party's pieces - the one list every member's pack is a copy of. */
      private final List<ItemStack> bag = new ArrayList<>();
      /** Rows stitched in by a pack upgrader during this run. */
      private int extraRows;
      /** A party run is live: the bag is shared and the pot is being paid out. */
      private boolean onRun;
      /** What the party has carried out together. */
      private long pot;
      /** What each member has actually been paid of the pot, so nobody is paid twice. */
      private final Map<UUID, Long> paid = new HashMap<>();
      /** Members who have stood on a pad this run. */
      private final Set<UUID> extracted = new LinkedHashSet<>();

      private Party(UUID host) {
         this.host = host;
         this.members.add(host);
      }

      public UUID host() {
         return this.host;
      }

      /** The roster, host first, in the order people joined. */
      public List<UUID> members() {
         return List.copyOf(this.members);
      }

      public int size() {
         return this.members.size();
      }

      /** The pieces the party is carrying, in the order they were taken. */
      public List<ItemStack> bag() {
         return List.copyOf(this.bag);
      }

      /** How many pieces the party's pack holds: nine a name, plus any patch, to the ceiling. */
      public int capacity() {
         return capacityFor(this.members.size(), this.extraRows);
      }

      /** Is a party run live? */
      public boolean onRun() {
         return this.onRun;
      }

      /** What the party has secured together. */
      public long pot() {
         return this.pot;
      }

      /** How many members have stood on a pad this run. */
      public int extractedCount() {
         return this.extracted.size();
      }
   }

   private static final Map<UUID, Party> byPlayer = new HashMap<>();

   private PartyManager() {
   }

   /**
    * How big a party's pack is: nine pieces for every name on the roster, a row more per patch, and
    * never bigger than a pack is allowed to be.
    *
    * <p>Four is the number that makes this land exactly: four names is nine times four, which is
    * {@link LootBackpack#MAX_CAPACITY}, so the biggest party carries the biggest pack there is and a
    * patch found by a full party is honestly refused instead of silently clamped away.
    */
   public static int capacityFor(int members, int patchRows) {
      int names = Math.max(1, Math.min(MAX_MEMBERS, members));
      int rows = Math.max(0, patchRows);
      return Math.min(
         LootBackpack.MAX_CAPACITY,
         LootBackpack.BASE_CAPACITY * names + LootBackpack.UPGRADE_STEP * rows
      );
   }

   /**
    * What is still owed to a member who has already been paid.
    *
    * <p>The whole "everybody is paid the same" rule in one line: the pot only grows, an early
    * extractor is brought up to it, and a member already level with the pot is owed nothing.
    */
   public static long topUp(long pot, long alreadyPaid) {
      return Math.max(0L, pot - Math.max(0L, alreadyPaid));
   }

   /** The pot after one more explorer carries their loot out. */
   public static long potAfter(long pot, long carried) {
      return Math.max(0L, pot) + Math.max(0L, carried);
   }

   /** Is there room for one more name? */
   public static boolean canJoin(int members) {
      return members >= 0 && members < MAX_MEMBERS;
   }

   /**
    * Who holds the crown when somebody walks away: the next name on the roster, or nobody at all.
    *
    * <p>A lobby whose host has logged off cannot be started by anybody, so the crown is handed down
    * in join order rather than the party dying with its founder. The last name out leaves nothing
    * behind, which is what dissolves the party.
    */
   public static UUID hostAfter(UUID leaving, List<UUID> roster) {
      if (roster == null) {
         return null;
      }
      for (UUID candidate : roster) {
         if (leaving == null || !candidate.equals(leaving)) {
            return candidate;
         }
      }
      return null;
   }

   /** The party this player is in, or null. */
   public static Party partyOf(UUID uuid) {
      return uuid == null ? null : byPlayer.get(uuid);
   }

   /** How many are in this player's party - zero when they are alone. */
   public static int size(UUID uuid) {
      Party p = partyOf(uuid);
      return p == null ? 0 : p.size();
   }

   /** Every party that exists right now. */
   public static List<Party> parties() {
      return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(byPlayer.values())));
   }

   /** Every party with room in it and somebody to talk to, for the join list. */
   public static List<Party> openParties(MinecraftServer server) {
      List<Party> out = new ArrayList<>();
      for (Party p : parties()) {
         if (canJoin(p.size()) && firstOnline(server, p) != null) {
            out.add(p);
         }
      }
      return out;
   }

   /**
    * The party whose bag this player is carrying, or null when they are on their own.
    *
    * <p>Only a live party run shares a bag. A member who went in on their own - from the expedition
    * window rather than the party window - keeps the solo pack they were handed, because the group's
    * bag belongs to the group's run.
    */
   private static Party runPartyOf(UUID uuid) {
      Party p = partyOf(uuid);
      if (p == null || !p.onRun || p.size() < 2) {
         return null;
      }
      return p;
   }

   /** Forms a party with this player as its host. Null on success, or the reason it could not be. */
   public static String create(ServerPlayer host) {
      if (host == null) {
         return "Nobody to make a party for.";
      }
      if (byPlayer.containsKey(host.getUUID())) {
         return "You're already in a party - leave it first.";
      }
      byPlayer.put(host.getUUID(), new Party(host.getUUID()));
      Chat.raw(
         host,
         "§6§lPARTY FORMED. §r§7You're the host - click a name in the party window to remove somebody, "
            + "or send the group into a descent."
      );
      return null;
   }

   /** Adds a player to a host's party. Null on success, or the reason it could not be done. */
   public static String join(ServerPlayer joiner, UUID host) {
      if (joiner == null || host == null) {
         return "There's nothing to join.";
      }
      if (byPlayer.containsKey(joiner.getUUID())) {
         return "You're already in a party - leave it first.";
      }
      Party p = partyOf(host);
      if (p == null) {
         return "That party has broken up.";
      }
      if (!canJoin(p.size())) {
         return "That party is full (" + MAX_MEMBERS + ").";
      }
      if (ExpeditionManager.isInExpedition(joiner.getUUID())) {
         return "Finish the expedition you're on first - a party's bag is shared from the moment it goes in.";
      }
      p.members.add(joiner.getUUID());
      byPlayer.put(joiner.getUUID(), p);
      announce(
         p, joiner.level().getServer(),
         "§f" + joiner.getName().getString() + "§7 joined the party. §8(" + p.size() + "/" + MAX_MEMBERS + ")"
      );
      Chat.raw(
         joiner,
         "§6§lJOINED A PARTY. §r§7The host decides when the group goes in, and the bag you carry is "
            + "everyone's - §f" + p.capacity() + "§7 pieces between you."
      );
      return null;
   }

   /** Removes a member at the host's word. Null on success, or the reason it could not be done. */
   public static String kick(ServerPlayer host, UUID target) {
      if (host == null || target == null) {
         return "There's nobody to remove.";
      }
      Party p = partyOf(host.getUUID());
      if (p == null) {
         return "You're not in a party.";
      }
      if (!p.members.contains(target)) {
         return "They're not in your party.";
      }
      if (target.equals(host.getUUID())) {
         return "You're the host - leave the party instead and the crown passes down.";
      }
      if (!host.getUUID().equals(p.host)) {
         return "Only the host can remove somebody from the party.";
      }
      MinecraftServer server = host.level().getServer();
      String name = nameOf(server, target, "somebody");
      String who = host.getName().getString();
      drop(p, target, server);
      announce(p, server, "§c" + who + "§7 removed §f" + name + "§7 from the party.");
      ServerPlayer kicked = playerOf(server, target);
      if (kicked != null) {
         Chat.raw(kicked, "§c§lREMOVED FROM THE PARTY §r§7- " + who + " took you out of it.");
      }
      return null;
   }

   /** Walks out of a party. Null on success, or the reason it could not be done. */
   public static String leave(ServerPlayer leaver) {
      Party p = partyOf(leaver.getUUID());
      if (p == null) {
         return "You're not in a party.";
      }
      MinecraftServer server = leaver.level().getServer();
      drop(p, leaver.getUUID(), server);
      announce(p, server, "§f" + leaver.getName().getString() + "§7 left the party.");
      return null;
   }

   /**
    * Takes one name off a roster, hands the crown down if it was the host's, and dissolves the party
    * when the last name goes.
    *
    * <p>The crown is always the first name on the roster, which is why handing it over also moves the
    * new host to the front: the party window lists the host first, and a crown that lived in a field
    * of its own could disagree with the list it is drawn from.
    */
   private static void drop(Party p, UUID target, MinecraftServer server) {
      if (!p.members.remove(target)) {
         return;
      }
      byPlayer.remove(target);
      if (p.members.isEmpty() || !target.equals(p.host)) {
         return;
      }
      UUID next = hostAfter(target, new ArrayList<>(p.members));
      if (next == null) {
         return;
      }
      p.members.remove(next);
      p.members.add(next);
      p.host = next;
      announce(p, server, "§6" + nameOf(server, next, "Somebody") + "§7 is the host now.");
   }

   /**
    * Sends the whole party in together.
    *
    * <p>Host only, and only when nobody in the party is already underground: the bag is shared, so a
    * party that went in in two halves would be a party carrying two bags. The purse is emptied here
    * rather than when the last explorer gets out, because a fresh run under a stale pot would pay the
    * group for loot the site already took.
    *
    * @return null on success, or the reason it could not be done
    */
   public static String startRun(ServerPlayer host, ExpeditionManager.Type type) {
      if (host == null || type == null) {
         return "There's nothing to start.";
      }
      Party p = partyOf(host.getUUID());
      if (p == null) {
         return "You're not in a party.";
      }
      if (!host.getUUID().equals(p.host)) {
         return "Only the host starts the run.";
      }
      MinecraftServer server = host.level().getServer();
      if (server == null) {
         return "The server isn't ready yet.";
      }
      List<ServerPlayer> going = new ArrayList<>();
      for (UUID m : p.members) {
         ServerPlayer mp = playerOf(server, m);
         if (mp != null) {
            going.add(mp);
         }
      }
      if (going.size() < 2) {
         return "A party run needs at least two of you online.";
      }
      for (ServerPlayer mp : going) {
         if (ExpeditionManager.isInExpedition(mp.getUUID())) {
            return mp.getName().getString() + " is already on an expedition - finish it first.";
         }
      }
      // A fresh purse for a fresh run. The packs themselves are handed out again by the start below.
      p.bag.clear();
      p.extraRows = 0;
      p.pot = 0L;
      p.paid.clear();
      p.extracted.clear();
      p.onRun = true;
      // One run id for the whole party. The packs handed to four explorers answer to a single run,
      // which is exactly what makes them one bag - and, later, what a pack somebody kept is measured
      // against when the run has ended. See LootBackpack#belongsTo.
      long runId = ExpeditionManager.nextRunId();
      // One site, too. The host takes the ground alone and everybody else is put into it, so the
      // party is walking one maze with one ledger of chests rather than four mazes that happen to
      // start at the same moment. See ExpeditionManager#startParty.
      String problem = ExpeditionManager.startParty(host, going, type, runId);
      int started = ExpeditionManager.crewOf(host.getUUID());
      if (problem != null || started == 0) {
         p.onRun = false;
         return problem == null ? "Nobody could go in." : problem;
      }
      announce(
         p, server,
         type.color + type.name + "§7 - §f" + started + " of you are going into §bone site§7 together. §8The bag holds "
            + p.capacity() + " pieces between you, and everyone who gets out is paid the same."
      );
      return null;
   }

   /**
    * What a fresh pack should hold for this explorer: the party's size when they are going in with
    * the group, their own bought room when they are not.
    */
   public static int packRoomFor(ServerPlayer sp, int solo) {
      Party p = sp == null ? null : runPartyOf(sp.getUUID());
      return p == null ? solo : p.capacity();
   }

   /**
    * Pays an explorer for the run they have just carried out, and brings everybody who got out
    * earlier up to the same number.
    *
    * <p>No party - which is every solo run - hands back exactly what the solo run had. With a party,
    * the explorer's haul joins the pot and they are paid the whole of it, and everyone already on
    * record as extracted is topped up to the new figure, so the party's payday is the same for all of
    * them however early or late they got out.
    */
   public static long settle(ServerPlayer sp, long carried) {
      Party p = sp == null ? null : runPartyOf(sp.getUUID());
      if (p == null) {
         return Math.max(0L, carried);
      }
      p.pot = potAfter(p.pot, carried);
      UUID uuid = sp.getUUID();
      p.extracted.add(uuid);
      p.paid.put(uuid, p.pot);
      MinecraftServer server = sp.level().getServer();
      if (server != null) {
         for (UUID m : new LinkedHashSet<>(p.extracted)) {
            if (m.equals(uuid)) {
               continue;
            }
            long due = payTopUp(server, p, m);
            if (due > 0L) {
               Chat.raw(
                  sp,
                  "§6§l[Party] §r§7" + nameOf(server, m, "A member") + " was topped up §a" + Chat.moneyStr(due)
                     + "§7 so you both leave with the same §a" + Chat.moneyStr(p.pot) + "§7."
               );
            }
         }
      }
      return p.pot;
   }

   /**
    * Pays one member up to the pot.
    *
    * <p>A member who is not on the server is left owing rather than written off - the party's own
    * sweep retries every second, so a share is never lost to somebody being mid-login.
    *
    * @return what was actually paid, or 0 when they were owed nothing or were not there
    */
   private static long payTopUp(MinecraftServer server, Party p, UUID member) {
      ServerPlayer mp = playerOf(server, member);
      if (mp == null) {
         return 0L;
      }
      long due = topUp(p.pot, p.paid.getOrDefault(member, 0L));
      if (due <= 0L) {
         return 0L;
      }
      p.paid.put(member, p.pot);
      EconomyManager.addCash(member, due);
      Chat.raw(
         mp,
         "§6§lPARTY SHARE §r§7- the bag paid out §a" + Chat.moneyStr(p.pot) + "§7 and you were owed §a"
            + Chat.moneyStr(due) + "§7 of it. §f+" + Chat.moneyStr(due)
      );
      return due;
   }

   /** The line a party member reads under their payout, or null on a solo run. */
   public static String shareNote(ServerPlayer sp) {
      Party p = sp == null ? null : runPartyOf(sp.getUUID());
      if (p == null) {
         return null;
      }
      return "§6§lPARTY PAYDAY §r§7- the bag was §a" + Chat.moneyStr(p.pot)
         + "§7 and everyone who gets out is paid all of it. §8" + p.extractedCount() + " of " + p.size()
         + " out so far.";
   }

   /**
    * Closes the purse when the last member is out of the site.
    *
    * <p>Called as every run ends: while somebody from the party is still underground the pot stands,
    * because they are still owed it. The moment nobody is, the run is over and the next descent starts
    * from an empty bag.
    */
   public static void runEndedIfIdle(ServerPlayer sp) {
      Party p = sp == null ? null : partyOf(sp.getUUID());
      if (p == null || !p.onRun) {
         return;
      }
      for (UUID m : p.members) {
         if (ExpeditionManager.isInExpedition(m)) {
            return;
         }
      }
      p.onRun = false;
      p.pot = 0L;
      p.paid.clear();
      p.extracted.clear();
      p.bag.clear();
      p.extraRows = 0;
   }

   /** True when the pack the player is carrying has room for one more piece. */
   public static boolean hasRoom(ServerPlayer sp) {
      Party p = sp == null ? null : runPartyOf(sp.getUUID());
      if (p == null) {
         return LootBackpack.hasRoom(LootBackpack.held(sp));
      }
      return p.bag.size() < p.capacity();
   }

   /** How many pieces that pack holds - the party's size when the pack is a shared one. */
   public static int roomOf(ServerPlayer sp) {
      Party p = sp == null ? null : runPartyOf(sp.getUUID());
      return p == null ? LootBackpack.capacity(LootBackpack.held(sp)) : p.capacity();
   }

   /**
    * The pieces the player is carrying, read from the party's bag when it is a shared one.
    *
    * <p>Every member's pack is a copy, so reading the bag rather than the copy is what keeps "what am
    * I carrying" and "what has the party got" the same question.
    */
   public static List<ItemStack> entriesOf(ServerPlayer sp) {
      Party p = sp == null ? null : runPartyOf(sp.getUUID());
      return p == null ? LootBackpack.entries(LootBackpack.held(sp)) : p.bag();
   }

   /**
    * Puts one piece in the party's bag, and in everybody's pack.
    *
    * <p>The one list is the truth and the packs are copies of it, which is the whole of "player one
    * picks up a bone and player two finds a bone in their bag": there is one bone, and two packs
    * showing it.
    */
   public static boolean addPiece(ServerPlayer sp, ItemStack piece) {
      Party p = runPartyOf(sp.getUUID());
      if (p == null) {
         return LootBackpack.add(LootBackpack.held(sp), piece);
      }
      if (piece == null || piece.isEmpty() || p.bag.size() >= p.capacity()) {
         return false;
      }
      p.bag.add(piece.copy());
      mirror(p, sp.level().getServer());
      return true;
   }

   /** Throws one piece out of the party's bag. The empty stack means there was nothing to throw. */
   public static ItemStack removePiece(ServerPlayer sp, int index) {
      Party p = runPartyOf(sp.getUUID());
      if (p == null) {
         return LootBackpack.removeAt(LootBackpack.held(sp), index);
      }
      if (index < 0 || index >= p.bag.size()) {
         return ItemStack.EMPTY;
      }
      ItemStack taken = p.bag.remove(index);
      mirror(p, sp.level().getServer());
      return taken;
   }

   /**
    * Stitches a patch into the party's pack.
    *
    * <p>A patch is the party's, not the patcher's: a wider pack that only the person who found the
    * patch could use would be a bag that shrinks and grows depending on who opens it.
    *
    * @return the pack's new size, or -1 when it is already as big as it gets
    */
   public static int widen(ServerPlayer sp) {
      Party p = runPartyOf(sp.getUUID());
      if (p == null) {
         return LootBackpack.upgrade(LootBackpack.held(sp));
      }
      if (p.capacity() >= LootBackpack.MAX_CAPACITY) {
         return -1;
      }
      p.extraRows++;
      mirror(p, sp.level().getServer());
      return p.capacity();
   }

   /**
    * Writes the party's bag into every member's pack.
    *
    * <p>A pack is a real bundle in a real inventory, so "shared" is only ever true a moment after
    * something changes, and the writing has to happen every time: the tooltip, the chest window and
    * the pack window all read the pack the player is holding, and none of them know about parties.
    */
   private static void mirror(Party p, MinecraftServer server) {
      if (server == null) {
         return;
      }
      int room = p.capacity();
      while (p.bag.size() > room) {
         p.bag.remove(p.bag.size() - 1);
      }
      for (UUID m : p.members) {
         ServerPlayer mp = playerOf(server, m);
         if (mp == null) {
            continue;
         }
         ItemStack pack = LootBackpack.held(mp);
         if (pack.isEmpty()) {
            continue;
         }
         LootBackpack.setCapacity(pack, room);
         LootBackpack.setEntries(pack, p.bag);
      }
   }

   /**
    * The party's own clock: the roster is kept honest and the packs are kept in step.
    *
    * <p>Two things go wrong without it. A member who never comes back - a crash rather than a logout -
    * would hold a slot in a lobby forever, so names that are not on the server are dropped and the
    * crown is handed down. And a share can come due for somebody who was mid-login when it was paid,
    * so anyone who extracted and is still owed is paid again here. A second of latency on a shared
    * payday is invisible; a share that is simply never paid is not.
    */
   public static void tick(MinecraftServer server) {
      if (server == null || byPlayer.isEmpty()) {
         return;
      }
      for (Party p : parties()) {
         for (UUID m : p.members()) {
            if (playerOf(server, m) == null && !ExpeditionManager.isInExpedition(m)) {
               announce(p, server, "§8" + nameOf(server, m, "Somebody") + " is no longer here.");
               drop(p, m, server);
            }
         }
         if (!p.onRun || p.members.isEmpty()) {
            continue;
         }
         for (UUID m : new LinkedHashSet<>(p.extracted)) {
            payTopUp(server, p, m);
         }
         mirror(p, server);
      }
   }

   /** A body that left the server leaves its party. Mid-run, the site has already taken it back. */
   public static void onPlayerLogout(ServerPlayer sp) {
      if (sp == null) {
         return;
      }
      Party p = partyOf(sp.getUUID());
      if (p == null) {
         return;
      }
      MinecraftServer server = sp.level().getServer();
      announce(p, server, "§8" + sp.getName().getString() + " left the server.");
      drop(p, sp.getUUID(), server);
   }

   private static ServerPlayer firstOnline(MinecraftServer server, Party p) {
      if (server == null) {
         return null;
      }
      for (UUID m : p.members) {
         ServerPlayer mp = playerOf(server, m);
         if (mp != null) {
            return mp;
         }
      }
      return null;
   }

   private static ServerPlayer playerOf(MinecraftServer server, UUID uuid) {
      return server == null ? null : server.getPlayerList().getPlayer(uuid);
   }

   private static String nameOf(MinecraftServer server, UUID uuid, String fallback) {
      ServerPlayer sp = playerOf(server, uuid);
      return sp == null ? fallback : sp.getName().getString();
   }

   /** Sends a line to everyone in the party who is online. */
   private static void announce(Party p, MinecraftServer server, String message) {
      if (server == null) {
         return;
      }
      for (UUID m : p.members) {
         ServerPlayer mp = playerOf(server, m);
         if (mp != null) {
            Chat.raw(mp, "§6§l[Party] §r" + message);
         }
      }
   }
}
