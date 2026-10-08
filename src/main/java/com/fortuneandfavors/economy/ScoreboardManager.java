package com.fortuneandfavors.economy;

import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

/**
 * The player-facing scoreboard: a sidebar each player owns, keeps and edits for themselves.
 *
 * <h2>Why this is not the duel scoreboard</h2>
 * The duel already draws a sidebar, but it does it the vanilla way - one objective on the
 * world's scoreboard, shown in a display slot - which is *global*: two duels at different
 * plots, or an FFA plus a duel, would fight over the same slot and the last writer would win.
 * That is acceptable for a match, which is a thing that ends.
 *
 * <p>A personal scoreboard is the opposite: it is always on, every player has their own, and
 * two players must be able to see different boards at the same time - one has turned the IP
 * line off, the next has the title renamed. Vanilla's display slots cannot express that, so
 * this one does not use them. Each player gets their own objective and *every* packet about it -
 * the objective itself, the display packet, and each row - is sent to that player alone, which
 * is the only mechanism the protocol offers for showing somebody a board nobody else can see.
 * Relying on the world's scoreboard to deliver it instead does not work at all: vanilla sends an
 * objective to a client only while that objective is in a global display slot, and broadcasts a
 * score only for an objective being tracked that way, so a personal board drawn through them is a
 * display packet naming something the client has never heard of - nothing at all.
 *
 * <h2>Who can see it</h2>
 * Every packet about a board is written to its owner's own connection and nowhere else, and the
 * objective is never put in a display slot - because a slot is *shared*: vanilla broadcasts a
 * slot's display packet to every player, then starts tracking whatever objective is in it and
 * broadcasts every score on it. Those two facts are the whole of the privacy story, and neither is
 * taken on trust. {@link #unshare} pulls the board back out of any slot it is ever found in, and
 * {@code board.only-its-owner-is-ever-sent-it} runs two boards at once and asserts that neither
 * player is ever handed a packet belonging to the other.
 *
 * <h2>What it costs, and what it may not cost</h2>
 * An objective is a real object on the world's scoreboard, so it is named, reused and eventually
 * removed rather than minted every refresh - that is what {@link #objectiveNames} and {@link Panel}
 * are for. Each objective's name is a short generated id, not the player's name or uuid: an
 * objective name goes on the wire and old protocol revisions cap it at sixteen characters, so the
 * id is what keeps that a non-question.
 *
 * <p>The board's *rows*, though, are pushed at the one player who can see them - see
 * {@link #push} - because vanilla's own scoreboard will only broadcast a score to everybody, and
 * only for an objective sitting in a global display slot, which a per-player sidebar is not: a
 * score on a board that nobody displays is silently dropped, and a score that *is* broadcast is a
 * packet per player on the server for a line one person reads. So the rows go out one connection at
 * a time, and only the ones that changed. A refresh with nothing to say therefore says nothing at
 * all, which used to be untrue (a display packet a second, and an objective rebuilt once a second
 * for any board whose address was promoted to its banner) and is now pinned by
 * {@code perf.the-sidebar-only-says-what-changed}.
 *
 * <h2>Editing</h2>
 * Every line is a toggle and the two text lines are editable, all of it filed per player in
 * {@code scoreboard.json}. Nothing here is an admin setting: a scoreboard is a thing you look
 * at, so the person looking at it is the person who decides what is on it.
 */
public final class ScoreboardManager {

   /** The {@code ModConfig} feature key - the scoreboard can be switched off server-wide. */
   public static final String FEATURE = "scoreboard";

   /** How long between refreshes: money and gems do not move faster than this, and a refresh that
    *  finds nothing changed sends nothing, so the interval is about how live a board feels rather
    *  than about the price of looking. */
   private static final int REFRESH_TICKS = 20;

   /** The default banner, used until a player renames it. */
   public static final String DEFAULT_TITLE = "&6&lFortune & Favors";
   /**
    * The address this board used to print when nobody had written one.
    *
    * <p>Kept for exactly one job: a board saved by an older build has this stored as though the
    * player had typed it, and reading it back as a *written* address would pin the hardcoded
    * address on that board forever - on every server, for every player - which is the whole problem
    * the real address solves. The load path treats it as "not written" and lets the board read the
    * address off the handshake instead.
    */
   public static final String LEGACY_DEFAULT_IP = "play.fortuneandfavors.net";

   /** Longest an editable line may be. Long enough to be useful, short enough to stay a sidebar. */
   public static final int MAX_TEXT = 32;

   /** What the player-written line says until its owner writes their own. */
   public static final String DEFAULT_CUSTOM = "&7Your text here";

   /**
    * The switchable body lines, by key.
    *
    * <p>{@code ip} is the "second text line": the board has exactly two written texts - the
    * banner and the address - and whichever one is not currently promoted to the banner is drawn
    * here, in this slot. Swapping the banner therefore changes what this line reads, but never the
    * set of lines a board can carry.
    *
    * <p>The last four are about the player rather than the server: how levelled they are, how long
    * they have played, the job they are closest to finishing, and a line they write themselves.
    * They exist because the first six are all things the server knows about you and none of them
    * are things you have done - a board that tells you the address and your balance is a login
    * screen, not a sidebar. Every one of them is a switch like the rest, in the same order list, so
    * they can be turned off, moved and reordered exactly as the originals can.
    */
   public static final String[] LINE_KEYS = {
      "ip", "tag", "guild", "gems", "money", "online", "skills", "playtime", "contracts", "custom"
   };

   /** The reading order a board starts with: the address first, then who you are, then your purse. */
   public static List<String> defaultOrder() {
      return new ArrayList<>(List.of(LINE_KEYS));
   }

   /**
    * One player's board: whether it is shown, which lines are on it, and the two strings that
    * are theirs to write.
    *
    * <p>Field-per-line rather than a set of line keys on purpose: the edit screen is exactly
    * this list, in this order, and a toggle that has to look its own name up in a map is how
    * a menu and its storage drift apart.
    */
   public static final class Settings {
      public boolean enabled;
      /**
       * The address line, off by default.
       *
       * <p>It starts off because it is the longest line a fresh board can carry and a sidebar is
       * exactly as wide as its widest line - a new board that leads with an address is a login
       * screen with your balance under it, not a sidebar. {@code /ff scoreboard ip <text>} writes
       * the address and switches the line on in one go, so turning it on costs one command.
       */
      public boolean showIp;
      public boolean showTag = true;
      public boolean showGuild = true;
      public boolean showGems = true;
      public boolean showMoney = true;
      public boolean showOnline = true;
      public boolean showSkills = true;
      public boolean showPlaytime = true;
      public boolean showContracts = true;
      public boolean showCustom = true;
      /** The banner at the top of the board. Colours welcome; {@code &} is colourised. */
      public String title = DEFAULT_TITLE;
      /**
       * The address the player wrote, if they wrote one.
       *
       * <p>Empty by default, and empty means *whichever address you joined on*: the board reads the
       * real one out of the handshake (see {@link #serverAddress}), so there is nothing to store
       * unless the player wants to override it - which is what this field is for, and why clearing
       * it goes back to the live address rather than blanking the line.
       */
      public String serverIp = "";
      /** The third written text: the one body line that is entirely the player's own. */
      public String customText = DEFAULT_CUSTOM;
      /**
       * Which of the two written texts is the banner: {@code false} = the title (the default),
       * {@code true} = the address. The other becomes the {@code ip} body line, so promoting the
       * address to the top is a swap rather than a loss.
       */
      public boolean bannerIsIp;
      /** The body lines, top to bottom, by key. Always a permutation of {@link #LINE_KEYS}. */
      public List<String> order = defaultOrder();
   }

   private static final Map<UUID, Settings> SETTINGS = new HashMap<>();
   /** The objective name handed to each player, so a rename reuses the same object. */
   private static final Map<UUID, String> objectiveNames = new HashMap<>();
   /** What each player's board is doing right now: see {@link Panel}. */
   private static final Map<UUID, Panel> PANELS = new HashMap<>();
   private static int nextObjective;
   private static Path dataFile;
   private static boolean dirty;

   /** Rows pushed at a client, objectives built, rows taken back off a board - the numbers
    *  {@code perf.the-sidebar-only-says-what-changed} reads, and see {@link #sink} for why. */
   private static long pushes;
   private static long builds;
   private static long drops;

   private ScoreboardManager() {
   }

   // ------------------------------------------------------------------ persistence

   public static void load(MinecraftServer server) {
      SETTINGS.clear();
      objectiveNames.clear();
      PANELS.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("scoreboard.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject o = e.getValue().getAsJsonObject();
               Settings s = new Settings();
               s.enabled = bool(o, "enabled", false);
               s.showIp = bool(o, "ip", false);
               s.showTag = bool(o, "tag", true);
               s.showGuild = bool(o, "guild", true);
               s.showGems = bool(o, "gems", true);
               s.showMoney = bool(o, "money", true);
               s.showOnline = bool(o, "online", true);
               s.showSkills = bool(o, "skills", true);
               s.showPlaytime = bool(o, "playtime", true);
               s.showContracts = bool(o, "contracts", true);
               s.showCustom = bool(o, "custom", true);
               s.title = str(o, "title", DEFAULT_TITLE);
               s.serverIp = str(o, "serverIp", "");
               if (LEGACY_DEFAULT_IP.equals(s.serverIp)) {
                  // Written by a build that shipped it as the default, so it is the board's old guess
                  // rather than anything the player typed. Clearing it is what lets the real address
                  // through on a board that has been carrying the hardcoded one since then.
                  s.serverIp = "";
               }
               s.customText = str(o, "customText", DEFAULT_CUSTOM);
               s.bannerIsIp = bool(o, "bannerIsIp", false);
               s.order = readOrder(o);
               SETTINGS.put(uuid, s);
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("scoreboard.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, Settings> e : SETTINGS.entrySet()) {
         Settings s = e.getValue();
         JsonObject o = new JsonObject();
         o.addProperty("enabled", s.enabled);
         o.addProperty("ip", s.showIp);
         o.addProperty("tag", s.showTag);
         o.addProperty("guild", s.showGuild);
         o.addProperty("gems", s.showGems);
         o.addProperty("money", s.showMoney);
         o.addProperty("online", s.showOnline);
         o.addProperty("skills", s.showSkills);
         o.addProperty("playtime", s.showPlaytime);
         o.addProperty("contracts", s.showContracts);
         o.addProperty("custom", s.showCustom);
         o.addProperty("title", s.title);
         o.addProperty("serverIp", s.serverIp);
         o.addProperty("customText", s.customText);
         o.addProperty("bannerIsIp", s.bannerIsIp);
         JsonArray order = new JsonArray();
         for (String key : orderOf(s)) {
            order.add(key);
         }
         o.add("order", order);
         players.add(e.getKey().toString(), o);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   /** Flush if anything changed since the last write. Called from the manager's own tick. */
   public static void saveIfDirty(MinecraftServer server) {
      if (dirty) {
         save(server);
      }
   }

   private static boolean bool(JsonObject o, String key, boolean fallback) {
      return o.has(key) ? o.get(key).getAsBoolean() : fallback;
   }

   private static String str(JsonObject o, String key, String fallback) {
      return o.has(key) ? o.get(key).getAsString() : fallback;
   }

   /**
    * Reads a stored order, repairing anything that is not a clean permutation.
    *
    * <p>A hand-edited file is the only way this can go wrong, and the failure mode is severe in a
    * quiet way: a missing key makes a line vanish from every board, and a duplicated one draws it
    * twice on the same score key. Both are fixed by falling back to the default rather than by
    * trying to patch a partial list.
    */
   private static List<String> readOrder(JsonObject o) {
      if (!o.has("order") || !o.get("order").isJsonArray()) {
         return defaultOrder();
      }
      List<String> out = new ArrayList<>();
      for (JsonElement el : o.getAsJsonArray("order")) {
         String key = el.getAsString();
         if (List.of(LINE_KEYS).contains(key) && !out.contains(key)) {
            out.add(key);
         }
      }
      for (String key : LINE_KEYS) {
         if (!out.contains(key)) {
            out.add(key);
         }
      }
      return out;
   }

   /**
    * A player's order, made whole on demand so every reader sees a full permutation.
    *
    * <p>Repaired by adding what is missing and dropping what no longer exists, rather than by
    * falling back to the default order. That distinction started to matter the moment this board
    * could grow a line: a stored order that predates the new lines is <i>still a good answer for
    * the lines it names</i>, and resetting it would silently throw away an order a player had spent
    * a screen arranging. A new line lands at the bottom, which is where a line nobody has seen
    * before belongs.
    */
   public static List<String> orderOf(Settings s) {
      if (s.order == null) {
         s.order = defaultOrder();
         return s.order;
      }
      boolean repaired = false;
      for (String key : LINE_KEYS) {
         if (!s.order.contains(key)) {
            s.order.add(key);
            repaired = true;
         }
      }
      for (java.util.Iterator<String> it = s.order.iterator(); it.hasNext(); ) {
         if (!List.of(LINE_KEYS).contains(it.next())) {
            it.remove();
            repaired = true;
         }
      }
      if (repaired) {
         markDirty();
      }
      return s.order;
   }

   /**
    * Moves a body line one place up or down the board.
    *
    * @return whether the line actually moved, so the caller can say so rather than assume
    */
   public static boolean moveLine(Settings s, String key, int direction) {
      List<String> order = orderOf(s);
      int from = order.indexOf(key);
      if (from < 0) {
         return false;
      }
      int to = from + (direction < 0 ? -1 : 1);
      if (to < 0 || to >= order.size()) {
         return false;
      }
      order.set(from, order.get(to));
      order.set(to, key);
      markDirty();
      return true;
   }

   /**
    * Promotes one of the two texts to the banner, demoting the other to the {@code ip} line.
    *
    * <p>Promoting the address also switches its line on, because the alternative is a banner the
    * player cannot turn off: the address is off by default, so with the line off and the banner set
    * to it there would be nothing on the board naming the address *and* no switch that brings it
    * back. It is the one request that can only mean one thing.
    */
   public static void setBanner(Settings s, boolean addressOnTop) {
      s.bannerIsIp = addressOnTop;
      if (addressOnTop) {
         s.showIp = true;
      }
      markDirty();
   }

   /**
    * What the board's own title reads right now.
    *
    * <p>The banner is the objective's display name, which is why swapping it means rebuilding the
    * objective rather than rewriting a line - see {@link #show}. The address is colourised like
    * any other text, so a server whose address carries codes still gets them across.
    */
   public static String banner(Settings s, ServerPlayer p) {
      String address = s.bannerIsIp ? serverAddress(p) : null;
      return colorize(address == null ? s.title : fit(address, addressBudget(p, s)));
   }

   /**
    * The address this player's board prints: the one they wrote, else the one they joined on, else
    * the one the server was configured to bind, else nothing at all.
    *
    * <p>The middle one is the point of the line. An address is the one thing on a board that is
    * neither about the player nor knowable by the server - it lives in the handshake the client sent
    * before it had a name - which is why the board used to have to be *given* one and printed the
    * same string to everybody, on every server. Read back per player, it is the address they
    * actually used, which is also the only one guaranteed to work for them: two players can arrive
    * through two hostnames and both be right.
    *
    * @return the address to print, or {@code null} when there is none - the line then draws nothing
    */
   public static String serverAddress(ServerPlayer p) {
      Settings s = settingsOf(p);
      if (s.serverIp != null && !s.serverIp.isBlank()) {
         return s.serverIp.trim();
      }
      String joined = com.fortuneandfavors.util.ServerAddress.of(p);
      if (joined != null && !joined.isBlank()) {
         return joined;
      }
      return com.fortuneandfavors.util.ServerAddress.local(p.level().getServer());
   }

   /**
    * How wide the address is allowed to be: the widest thing already on the board.
    *
    * <p>A sidebar is exactly as wide as its widest line, and the address is the one line on it that
    * the player did not write and cannot shorten - so a long hostname used to set the width of the
    * whole board, on every client, for a line that is mostly redundant to the person reading it.
    * The rule is the one the ask names: the address may not be wider than the longest of the *other*
    * texts, so switching it on can never make the board longer than it already was.
    *
    * <p>A board with nothing else on it is the address alone, and then there is nothing to compare
    * against: it may be as long as a stored text can be.
    */
   private static int addressBudget(ServerPlayer p, Settings s) {
      int widest = 0;
      if (!(s.bannerIsIp && serverAddress(p) != null)) {
         // The banner is a line's worth of width too - unless it *is* the address, which is the one
         // case where it cannot be its own yardstick.
         widest = width(colorize(s.title));
      }
      for (String key : orderOf(s)) {
         if ("ip".equals(key)) {
            continue;
         }
         String text = lineText(p, s, key);
         if (text != null) {
            widest = Math.max(widest, width(text));
         }
      }
      return widest == 0 ? MAX_TEXT : widest;
   }

   /**
    * How wide a line actually reads.
    *
    * <p>Counted in drawn characters, not in chars: colour codes are not width, and an address
    * written with them would otherwise be trimmed to nothing while looking short.
    */
   private static int width(String text) {
      int drawn = 0;
      for (int i = 0; text != null && i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '\u00a7' && i + 1 < text.length()) {
            i++;
            continue;
         }
         drawn++;
      }
      return drawn;
   }

   /** The first {@code budget} drawn characters of a line, colour codes left intact. */
   private static String fit(String text, int budget) {
      if (text == null || width(text) <= budget) {
         return text;
      }
      StringBuilder out = new StringBuilder();
      int drawn = 0;
      for (int i = 0; i < text.length() && drawn < budget; i++) {
         char c = text.charAt(i);
         if (c == '\u00a7' && i + 1 < text.length()) {
            out.append(c).append(text.charAt(i + 1));
            i++;
            continue;
         }
         out.append(c);
         drawn++;
      }
      return out.toString();
   }

   /**
    * Whether the address can be drawn at all in the world this player is in.
    *
    * <p>Single player has no address: the world is one you opened, there is nobody to invite and
    * nothing to type into a server list. So the line is refused there rather than drawn as
    * decoration with no referent - the switch is not stored, and the banner cannot be promoted to
    * something the board would not print anyway.
    */
   public static boolean addressDrawable(ServerPlayer p) {
      return p != null
         && p.level() != null
         && p.level().getServer() != null
         && !p.level().getServer().isSingleplayer();
   }

   /**
    * Whether a line's switch can be set here at all.
    *
    * <p>Only the address has a rule. A switch that stores a value the board then refuses to draw is
    * worse than one that says no, which is why the callers ask this before flipping it.
    */
   public static boolean lineAvailable(ServerPlayer p, String key) {
      return !"ip".equals(key) || addressDrawable(p);
   }

   // ------------------------------------------------------------------- the settings

   /** This player's board, created on first ask so a new player is never null-checked. */
   public static Settings settingsOf(ServerPlayer p) {
      return SETTINGS.computeIfAbsent(p.getUUID(), u -> new Settings());
   }

   public static boolean isEnabled(ServerPlayer p) {
      return settingsOf(p).enabled;
   }

   /** Records that a setting changed. The caller still has to save. */
   public static void markDirty() {
      dirty = true;
   }

   // ------------------------------------------------------------------ show and hide

   /**
    * Turns the board on for this player and paints it immediately.
    *
    * @return whether it is on now, so the caller can say so without guessing
    */
   public static boolean enable(ServerPlayer p) {
      Settings s = settingsOf(p);
      s.enabled = true;
      markDirty();
      show(p);
      return true;
   }

   /** Turns it off and takes it back down, clearing the objective behind it. */
   public static boolean disable(ServerPlayer p) {
      Settings s = settingsOf(p);
      s.enabled = false;
      markDirty();
      hide(p);
      return false;
   }

   /**
    * One row of a board: the line it belongs to, the text it reads, and the rank that puts it where
    * it is.
    *
    * <p>The key follows the *line* - {@code money}, {@code playtime} - under this board's own
    * objective name, and never the row's text or its position. That one choice is most of why this
    * class is cheap: an entry keyed by its text is a brand new entry every time a number moves, and
    * an entry keyed by its position slides under every row below it the moment one line is switched
    * off. A key that follows the line means a balance going from $1,000 to $1,001 *edits* a row, and
    * switching a line off costs one packet instead of ten.
    *
    * <p>The rank is the line's place in the *whole* order rather than in the drawn rows, so a line
    * that is switched off leaves the others where they were: the board reads top to bottom, and a
    * row's number only exists to put it there.
    */
   private record Row(String line, String text, int value) {
      /** This row's score key on the given board: the objective, then the line. The line is the
       *  row's identity, so a key survives a reorder, a repaint and a number moving. */
      private String keyOn(Panel panel) {
         return panel.key(this.line);
      }
   }

   /**
    * One player's board, as the server last described it to that player.
    *
    * <p>This is what makes a refresh cost nothing when nothing has moved: the rows are kept here so
    * a refresh can compare and stop, instead of re-deriving ten lines and re-sending a display
    * packet every second for a board that changes about once a minute. The old shape did exactly
    * that, and a board whose *address* was promoted to its banner went further - it rebuilt its
    * objective, and reset all ten of its rows, on every single refresh, forever.
    */
   private static final class Panel {
      /** The objective the board is drawn on. Reused for as long as the banner does not change. */
      private final Objective objective;
      /** The banner this objective was built with. An objective's display name *is* its banner -
       *  there is no rename call - so this is how a rename is told apart from a repaint. */
      private final String banner;
      /** The rows the owner has been sent, in board order. */
      private List<Row> order = List.of();
      /** The connection the board was last shown on: a player who reconnects knows nothing about
       *  it, and a board that is not re-sent to a new connection is a board that has vanished. */
      private ServerGamePacketListenerImpl shownOn;

      private Panel(Objective objective, String banner) {
         this.objective = objective;
         this.banner = banner;
      }

      /** One row's score key: this board's objective, then the line. */
      private String key(String lineKey) {
         return this.objective.getName() + "." + lineKey;
      }
   }

   /**
    * Puts a player's board back on the sidebar after something else owned it for a while.
    *
    * <p>Only the DISPLAY is re-sent, never the objective: this player's client has already been told
    * about the objective once, and re-adding an objective a client already knows is the one packet
    * in this file that is not safe to repeat. So an explorer whose run has just ended gets their
    * rows back without the board being rebuilt underneath them, and a player who had no board
    * enabled is simply left alone.
    */
   public static void reclaim(ServerPlayer p) {
      Settings s = settingsOf(p);
      if (s == null || !s.enabled || !ModConfig.is(FEATURE)) {
         return;
      }
      Panel panel = PANELS.get(p.getUUID());
      if (panel == null || panel.shownOn != p.connection) {
         // No board of this player's own exists yet: the ordinary path builds it, once.
         show(p);
         return;
      }
      try {
         sink.send(p, new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, panel.objective));
      } catch (Throwable ignored) {
      }
   }

   /**
    * Where the board's packets go.
    *
    * <p>One indirection, and it is here because the sidebar's *cost* is the thing that went wrong:
    * a board re-sent on a clock is a board nobody notices until the server is what pays for it.
    * "A refresh with nothing to say says nothing" is a claim, not a fact, until it is counted -
    * and it can only be counted if the packets can be caught on the way out, with no client on the
    * other end of them. See {@code perf.the-sidebar-only-says-what-changed}.
    */
   @FunctionalInterface
   public interface Sink {
      void send(ServerPlayer player, Packet<?> packet);
   }

   private static final Sink TO_CLIENT = (player, packet) -> player.connection.send(packet);
   private static Sink sink = TO_CLIENT;

   /** Shows the board for one player, creating the objective if it does not exist yet. */
   public static void show(ServerPlayer p) {
      Settings s = settingsOf(p);
      if (!s.enabled || !ModConfig.is(FEATURE)) {
         return;
      }
      if (ExpeditionManager.isInExpedition(p.getUUID())) {
         // One client has one sidebar, and while an expedition is running the site's board owns it -
         // a personal board that painted over it every second would leave the explorer reading about
         // the server address while their clock ran out. This is not a loss: the refresh that calls
         // this runs once a second, so the board is back the second the run ends, and
         // {@link ExpeditionManager} hands the sidebar back explicitly on the way out.
         return;
      }
      Scoreboard sb = p.level().getServer().getScoreboard();
      String name = objectiveNames.computeIfAbsent(p.getUUID(), u -> "ffsb" + nextObjective++);
      String banner = banner(s, p);
      Panel panel = PANELS.get(p.getUUID());
      if (panel != null && !panel.banner.equals(banner)) {
         // The banner is the objective, so a rename is a new objective. Once - not once a refresh,
         // which is what comparing a freshly built component against the live one did for every
         // board whose address had been promoted to the banner.
         drop(p, sb, panel);
         panel = null;
      }
      if (panel == null) {
         Objective existing = sb.getObjective(name);
         if (existing != null && Component.literal(banner).equals(existing.getDisplayName())) {
            // The board a restart left behind under the same name and the same banner: adopted
            // rather than rebuilt, so coming back up does not churn the world's scoreboard.
            panel = new Panel(existing, banner);
         } else {
            if (existing != null) {
               sb.removeObjective(existing);
            }
            panel = new Panel(
               sb.addObjective(
                  name, ObjectiveCriteria.DUMMY, Component.literal(banner), RenderType.INTEGER, false, BlankFormat.INSTANCE
               ),
               banner
            );
            builds++;
         }
         PANELS.put(p.getUUID(), panel);
      }
      if (panel.shownOn != p.connection) {
         // A client that has only just been told which objective to draw - or one that has only
         // just connected, and so knows nothing about any of this - starts with an empty board.
         panel.shownOn = p.connection;
         panel.order = List.of();
         // The objective itself goes to its owner, and only to its owner: an objective added to the
         // world's scoreboard is not sent to anyone by vanilla - only one put in a *global* display
         // slot is, which is the leak this board exists to avoid - so without this packet the client
         // is told to display a board it has never heard of and draws nothing at all. It is sent
         // once per connection (the add is the only half vanilla refuses to repeat: a second add of
         // the same name is an exception on the client, which is a disconnect).
         sink.send(p, new ClientboundSetObjectivePacket(panel.objective, ClientboundSetObjectivePacket.METHOD_ADD));
         sink.send(p, new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, panel.objective));
      }
      unshare(sb, panel);
      push(p, panel, rows(p));
   }

   /**
    * Takes the board back out of any *shared* display slot it has ended up in.
    *
    * <p>This is the one check that makes "only you can see it" a fact about the world rather than
    * about this class' call sites. A board is delivered one connection at a time and never put in a
    * slot, and that is the whole reason it is private - a slot is global, so vanilla broadcasts its
    * display packet to every player and starts broadcasting every score on the objective that sits
    * in it. Anything that puts this objective in one therefore hands the board to the whole server:
    * an admin's {@code /scoreboard objectives setdisplay}, another mod enumerating the world's
    * objectives, or a future call site here. So the board looks, every refresh, and takes itself
    * back out. It costs a handful of map lookups and it means no single forgotten call can publish
    * somebody's sidebar.
    */
   private static void unshare(Scoreboard sb, Panel panel) {
      for (DisplaySlot slot : DisplaySlot.values()) {
         if (sb.getDisplayObjective(slot) == panel.objective) {
            sb.setDisplayObjective(slot, null);
         }
      }
   }

   /**
    * Sends one player's board, and only the parts of it that moved.
    *
    * <p>Every packet here is addressed to the owner's own connection: a row is written as a score
    * with the row in its *display* component (so the key stays a key), and a line that has been
    * switched off is reset rather than left behind - a row nobody clears is a row that is still on
    * the client. Nothing about a board is ever broadcast, because a board belongs to one player.
    */
   private static void push(ServerPlayer p, Panel panel, List<Row> rows) {
      if (rows.equals(panel.order)) {
         return;
      }
      Map<String, Row> before = new HashMap<>();
      for (Row row : panel.order) {
         before.put(row.line(), row);
      }
      Map<String, Row> after = new HashMap<>();
      for (Row row : rows) {
         after.put(row.line(), row);
      }
      for (Row gone : panel.order) {
         if (!after.containsKey(gone.line())) {
            // The line is off the board. Clearing the score is what tells the owner's client to
            // stop drawing the row; leaving it would leave the row on screen until the next
            // reconnect.
            sink.send(p, new ClientboundResetScorePacket(gone.keyOn(panel), panel.objective.getName()));
            drops++;
         }
      }
      for (Row row : rows) {
         Row was = before.get(row.line());
         if (was != null && was.text().equals(row.text()) && was.value() == row.value()) {
            continue;
         }
         sink.send(
            p,
            new ClientboundSetScorePacket(
               row.keyOn(panel),
               panel.objective.getName(),
               row.value(),
               Optional.of(Component.literal(row.text())),
               Optional.empty()
            )
         );
         pushes++;
      }
      panel.order = List.copyOf(rows);
   }

   /** Takes the board down for one player and cleans up the objective it was drawn on. */
   public static void hide(ServerPlayer p) {
      sink.send(p, new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
      Scoreboard sb = p.level().getServer().getScoreboard();
      Panel panel = PANELS.remove(p.getUUID());
      if (panel != null) {
         drop(p, sb, panel);
         return;
      }
      // Nothing this session knows about: at most an objective a previous session left under this
      // player's name. Removing it takes it - and its entries - off the world's scoreboard.
      String name = objectiveNames.get(p.getUUID());
      Objective existing = name == null ? null : sb.getObjective(name);
      if (existing != null) {
         sb.removeObjective(existing);
      }
   }

   /**
    * Drops a board's objective, and with it everything a client had on it.
    *
    * <p>Both halves are vanilla's own: removing the objective takes its scores off every holder on
    * the way out (an objective removed on its own leaves the entries behind as ghost lines, one per
    * session), and the removal is broadcast, which is what makes a client that was *displaying* it
    * stop. That broadcast is the one thing about a board that everybody hears, and it is the price
    * of the objective: a client can only be told to draw one that exists.
    */
   private static void drop(ServerPlayer p, Scoreboard sb, Panel panel) {
      panel.order = List.of();
      panel.shownOn = null;
      sink.send(p, new ClientboundSetObjectivePacket(panel.objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
      if (sb.getObjective(panel.objective.getName()) == panel.objective) {
         sb.removeObjective(panel.objective);
      }
   }

   /** Test-only: where the board's packets go, answering with the sink that was in place. */
   public static Sink useSinkForTest(Sink replacement) {
      Sink previous = sink;
      sink = replacement == null ? TO_CLIENT : replacement;
      return previous;
   }

   /** Test-only: zeroes the counters a check reads. */
   public static void resetCostForTest() {
      pushes = 0L;
      builds = 0L;
      drops = 0L;
   }

   /** Test-only: rows pushed at a client since {@link #resetCostForTest()}. */
   public static long pushesForTest() {
      return pushes;
   }

   /** Test-only: objectives built (or rebuilt) since {@link #resetCostForTest()}. */
   public static long buildsForTest() {
      return builds;
   }

   /** Test-only: rows taken back off a board since {@link #resetCostForTest()}. */
   public static long dropsForTest() {
      return drops;
   }

   /** Test-only: the objective name this player's board is drawn under, or null if it has none. */
   public static String nameForTest(ServerPlayer p) {
      return objectiveNames.get(p.getUUID());
   }

   /** Test-only: how many rows this player's board is currently carrying. */
   public static int drawnForTest(ServerPlayer p) {
      Panel panel = PANELS.get(p.getUUID());
      return panel == null ? 0 : panel.order.size();
   }

   /** Test-only: forgets everything this manager knows about one player, so a probe cannot leak
    *  into the next check - the objective itself is left to {@link #hide}, which is the real path. */
   public static void forgetForTest(UUID id) {
      SETTINGS.remove(id);
      PANELS.remove(id);
      objectiveNames.remove(id);
   }

   /**
    * Every player currently showing a board is refreshed. Called on a timer, not per tick.
    *
    * <p>The interval is the *ceiling* on how fast a board can move, not what it costs: a refresh
    * that finds nothing changed sends nothing at all (see {@link #push}), so the once-a-second
    * shape of this loop is a decision about how live a sidebar should feel rather than a bill.
    */
   public static void tick(MinecraftServer server) {
      if (server == null || server.getTickCount() % REFRESH_TICKS != 0) {
         return;
      }
      if (!automaticDraw(server.isSingleplayer())) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         try {
            Settings s = SETTINGS.get(p.getUUID());
            if (s == null || !s.enabled) {
               continue;
            }
            if (!ModConfig.is(FEATURE)) {
               // Server-wide off. Only worth saying once: hide() takes the panel down, and with no
               // panel there is nothing left to take down on the next refresh.
               if (PANELS.containsKey(p.getUUID())) {
                  hide(p);
               }
               continue;
            }
            show(p);
         } catch (Throwable t) {
            // A sidebar is decoration. A sidebar that takes the tick with it is a broken server.
         }
      }
   }

   /**
    * Whether a board may come up *on its own* in a world of this kind.
    *
    * <p>Single player is the one world where an automatic sidebar has nothing to be: one player, no
    * address, nobody to read it, and a refresh every second for decoration. So the timer stands
    * down there, and a board switched on for a server does not follow the same account into its own
    * save. An explicit {@code /ff scoreboard} still draws one, which is what keeps the board
    * editable offline - the rule is about what happens by itself, not about what a player asks for.
    *
    * <p>A function of the fact rather than of the server, so the rule can be checked without a
    * single-player world to check it on. Public for the same reason every other probe here is: the
    * self-test lives in another package.
    */
   public static boolean automaticDraw(boolean singleplayer) {
      return !singleplayer;
   }

   // --------------------------------------------------------------------- the lines

   /**
    * The board's contents, top to bottom: one row per line that is switched on, each carrying the
    * key it is drawn under and the rank that puts it where it is.
    *
    * <p>This replaced a scheme where every row was prefixed with an invisible marker and the row's
    * own *text* was its score key. That worked - two lines that read the same cannot collide when
    * the text is the key - but it made the key move every time a number moved, and it left the
    * marker in front of every line for every other reader of this list to strip again. The key now
    * follows the line, and a line's text is only ever its text.
    */
   private static List<Row> rows(ServerPlayer p) {
      Settings s = settingsOf(p);
      List<String> order = orderOf(s);
      List<Row> out = new ArrayList<>();
      for (int i = 0; i < order.size(); i++) {
         String lineKey = order.get(i);
         String text = lineText(p, s, lineKey);
         if (text == null) {
            continue;
         }
         out.add(new Row(lineKey, text, order.size() - i));
      }
      return out;
   }

   /** The board's contents, top to bottom, as the text a player reads. */
   public static List<String> lines(ServerPlayer p) {
      List<String> out = new ArrayList<>();
      for (Row row : rows(p)) {
         out.add(row.text());
      }
      return out;
   }

   /**
    * One body line's text, or {@code null} if the line is off.
    *
    * <p>The {@code ip} slot is the interesting one: it draws the address when the title is the
    * banner and the title when the address is, so exactly one of the two written texts is ever the
    * banner and the other is always the line - the swap never has to hide either.
    */
   /**
    * One line of the board, in its own colour.
    *
    * <p>Every label used to be the same grey, so the board read as one paragraph of values rather
    * than as a list of things - you had to read each line to find out which one you were looking
    * at. Each line now owns a colour, and no two owners share one: the address dark aqua, the tag
    * yellow, the guild gold, gems dark purple, money dark green, the player count aqua, skills
    * green, playtime blue, the contract red. The *values* keep their own accents (money green,
    * gems light purple, the guild name aqua) so a glance down the board separates label from
    * readout. The line the player writes themselves is theirs and is left exactly as typed.
    */
   private static String lineText(ServerPlayer p, Settings s, String key) {
      switch (key) {
         case "ip":
            if (!s.showIp || !addressDrawable(p)) {
               return null;
            }
            if (s.bannerIsIp) {
               // The address is the banner, so this slot carries the other text instead.
               return colorize(s.title);
            }
            String address = serverAddress(p);
            // No address to print draws nothing: an empty line on every board that cannot know its
            // own address is worse than a board that is one line shorter.
            return address == null ? null : "§3" + colorize(fit(address, addressBudget(p, s)));
         case "tag": {
            if (!s.showTag) {
               return null;
            }
            TagManager.Tag tag = TagManager.getTag(p.getUUID());
            return "§eTag §r" + (tag == null ? "§8none" : tag.text());
         }
         case "guild": {
            if (!s.showGuild) {
               return null;
            }
            GuildManager.Guild guild = GuildManager.getGuild(p.getUUID());
            return "§6Guild §r" + (guild == null ? "§8none" : "§b" + guild.name);
         }
         case "gems":
            return s.showGems ? "§5Gems §d" + format(EconomyManager.gemBalance(p.getUUID())) : null;
         case "money":
            return s.showMoney ? "§2Money §a$" + format(EconomyManager.balance(p.getUUID())) : null;
         case "online":
            return s.showOnline
               ? "§bOnline §f" + p.level().getServer().getPlayerList().getPlayerCount()
               : null;
         case "skills":
            return s.showSkills ? "§aSkills §f" + skillLevels(p) + "§8/§f" + JobManager.MAX_TOTAL_LEVEL : null;
         case "playtime":
            return s.showPlaytime ? "§9Played §f" + PlaytimeManager.format(PlaytimeManager.totalSeconds(p.getUUID())) : null;
         case "contracts":
            return s.showContracts ? "§cContract " + nearestContract(p) : null;
         case "custom":
            // The one line that is not a readout of anything: whatever its owner typed, colour
            // codes and all. Blank is allowed here (unlike the two texts, which have a command
            // that would have nothing to store) - an empty line simply draws nothing.
            return s.showCustom && s.customText != null && !s.customText.isBlank() ? colorize(s.customText) : null;
         default:
            return null;
      }
   }

   /** The player's own standing: the six skill levels added up, the number their skill board shows. */
   private static int skillLevels(ServerPlayer p) {
      int total = 0;
      for (String skill : SkillManager.SKILLS) {
         total += SkillManager.level(p.getUUID(), skill);
      }
      return total;
   }

   /**
    * The job this player is closest to finishing.
    *
    * <p>The <i>closest</i> one rather than the first or the newest, because a sidebar is read as
    * "how am I doing" and the honest answer to that is the errand you are nearly done with. A
    * shortage counts by what has been delivered, which is the same progress its own board shows.
    */
   private static String nearestContract(ServerPlayer p) {
      DynamicContractsManager.Contract best = null;
      double bestFraction = -1.0;
      for (DynamicContractsManager.Contract c : DynamicContractsManager.activeContracts()) {
         if (c.amount <= 0) {
            continue;
         }
         int done = "shortage".equals(c.type) ? c.delivered : c.progress;
         double fraction = (double)done / c.amount;
         if (fraction > bestFraction) {
            bestFraction = fraction;
            best = c;
         }
      }
      if (best == null) {
         return "§8none";
      }
      int done = "shortage".equals(best.type) ? best.delivered : best.progress;
      return "§f" + Math.min(done, best.amount) + "§7/§f" + best.amount;
   }

   /** The human name of a line key, for the edit screen. */
   public static String lineName(String key) {
      return switch (key) {
         case "ip" -> "Address";
         case "tag" -> "Your Tag";
         case "guild" -> "Your Guild";
         case "gems" -> "Gems";
         case "money" -> "Money";
         case "online" -> "Players Online";
         case "skills" -> "Skill Levels";
         case "playtime" -> "Playtime";
         case "contracts" -> "Contract Progress";
         case "custom" -> "Your Own Line";
         default -> key;
      };
   }

   /** Whether a body line is switched on. */
   public static boolean lineOn(Settings s, String key) {
      return switch (key) {
         case "ip" -> s.showIp;
         case "tag" -> s.showTag;
         case "guild" -> s.showGuild;
         case "gems" -> s.showGems;
         case "money" -> s.showMoney;
         case "online" -> s.showOnline;
         case "skills" -> s.showSkills;
         case "playtime" -> s.showPlaytime;
         case "contracts" -> s.showContracts;
         case "custom" -> s.showCustom;
         default -> false;
      };
   }

   /**
    * Writes one of the board's two written texts, and answers what was actually stored.
    *
    * <p>Clamped here rather than in the callers, because there are three of them now - the two
    * commands and the rename prompt behind the edit screen - and a title is decoration: refusing
    * to save somebody's twelve extra characters would be a rule with nothing behind it, while
    * three copies of the same clamp is three chances for one of them to disagree.
    *
    * @param address {@code true} for the address line, {@code false} for the banner text
    */
   public static String setText(ServerPlayer p, boolean address, String raw) {
      Settings s = settingsOf(p);
      String text = raw == null ? "" : raw.trim();
      if (text.length() > MAX_TEXT) {
         text = text.substring(0, MAX_TEXT);
      }
      if (address) {
         s.serverIp = text;
         // Writing an address is a request to show one, so the line comes on with it - except in a
         // single-player world, where the address cannot be turned on at all and the switch is left
         // alone rather than stored as a promise the board will not keep.
         if (!text.isEmpty() && addressDrawable(p)) {
            s.showIp = true;
         }
      } else {
         s.title = text;
      }
      markDirty();
      return text;
   }

   /**
    * Sets a body line's switch to a known state, and says whether it actually moved.
    *
    * <p>{@code false} is the useful answer to a caller that has to report what happened: "off
    * money" on a board that already has money off is a no-op rather than a failure, and a caller
    * that could not tell the two apart would have to read the setting back and ask twice.
    */
   public static boolean setLine(Settings s, String key, boolean on) {
      if (lineOn(s, key) == on) {
         return false;
      }
      toggleLine(s, key);
      return true;
   }

   /** Flips a body line's switch. */
   public static void toggleLine(Settings s, String key) {
      switch (key) {
         case "ip" -> s.showIp = !s.showIp;
         case "tag" -> s.showTag = !s.showTag;
         case "guild" -> s.showGuild = !s.showGuild;
         case "gems" -> s.showGems = !s.showGems;
         case "money" -> s.showMoney = !s.showMoney;
         case "online" -> s.showOnline = !s.showOnline;
         case "skills" -> s.showSkills = !s.showSkills;
         case "playtime" -> s.showPlaytime = !s.showPlaytime;
         case "contracts" -> s.showContracts = !s.showContracts;
         case "custom" -> s.showCustom = !s.showCustom;
         default -> {
         }
      }
      markDirty();
   }

   /**
    * Writes the player-written line, and answers what was actually stored.
    *
    * <p>Same clamp as the other two texts ({@link #MAX_TEXT}) and for the same reason, but unlike
    * them an empty body is fine here: the banner and the address are the board's identity and have
    * to say something, while this line is decoration and may simply be blank.
    */
   public static String setCustomText(ServerPlayer p, String raw) {
      Settings s = settingsOf(p);
      String text = raw == null ? "" : raw.trim();
      if (text.length() > MAX_TEXT) {
         text = text.substring(0, MAX_TEXT);
      }
      s.customText = text;
      markDirty();
      return text;
   }

   /** {@code &} to {@code §}, so an edited line can carry colour the way every other string can. */
   public static String colorize(String s) {
      return s == null ? "" : s.replace('&', '§');
   }

   /** Plain, grouped digits - a sidebar is read at a glance, not parsed. */
   private static String format(long value) {
      return String.format("%,d", value);
   }

   /** How many lines a board would carry, so the edit screen can say so. */
   public static int lineCount(ServerPlayer p) {
      return lines(p).size();
   }

   /** Test-only: the toggle count the edit screen is expected to offer, one per line plus the master. */
   public static int toggleCount() {
      return LINE_KEYS.length + 1;
   }
}
