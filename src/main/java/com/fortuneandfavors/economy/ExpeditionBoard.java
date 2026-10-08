package com.fortuneandfavors.economy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

/**
 * The explorer's own sidebar: where the run is, how long is left, and what it has cost so far.
 *
 * <h2>Why it is not the action bar</h2>
 * The run used to narrate itself as one long action-bar line - type, loot, clock, depth, threat,
 * event and pack, separated by pipes and rewritten every half second. An action bar is one line of
 * transient text: it scrolls, it is overwritten by every pickup and every level-up, and a player
 * standing in a fight has to look away from the fight to read their own clock. A scoreboard is not
 * better because it is prettier; it is better because it is a list, the client keeps it on screen,
 * and a value that does not change does not have to be re-read.
 *
 * <h2>Why nobody else can see it</h2>
 * Vanilla's display slots are *global*: an objective put in the sidebar slot is broadcast to every
 * player, and so is every score on it. So this board never uses one. Each explorer gets their own
 * objective, and every packet about it - the objective, the display, each row - goes to that
 * player's own connection. A second player in the same site sees their own run; a player outside
 * the site sees nothing at all, which is the point: a dungeon board that everybody can read is a
 * dungeon board that tells the whole server where you are and how badly it is going.
 *
 * <p>The same technique carries {@link ScoreboardManager}'s personal board, and the two are
 * mutually exclusive on purpose - one client has one sidebar, so the site's board owns it for the
 * duration of a run and hands it back with {@link ScoreboardManager#reclaim(ServerPlayer)}.
 */
public final class ExpeditionBoard {
   /** The banner: the run's own name, not the server's. */
   public static final String TITLE = "§6§lExpedition";
   /** The most rows a sidebar can usefully carry. Vanilla draws about fifteen. */
   public static final int MAX_ROWS = 14;

   private static final Map<UUID, Panel> PANELS = new HashMap<>();
   private static int nextObjective;

   /** Where the board's packets go - one indirection, so a check can catch them. */
   @FunctionalInterface
   public interface Sink {
      void send(ServerPlayer player, Packet<?> packet);
   }

   private static final Sink TO_CLIENT = (player, packet) -> player.connection.send(packet);
   private static Sink sink = TO_CLIENT;

   private ExpeditionBoard() {
   }

   /** Test seam: catch the board's packets instead of sending them, or restore sending. */
   public static void useSinkForTest(Sink replacement) {
      sink = replacement == null ? TO_CLIENT : replacement;
   }

   /** One explorer's board: the objective it is drawn on and the rows it currently shows. */
   private static final class Panel {
      private final Objective objective;
      private List<Row> rows = List.of();
      /** The connection the board was last shown on; a reconnect knows nothing about it. */
      private ServerGamePacketListenerImpl shownOn;

      private Panel(Objective objective) {
         this.objective = objective;
      }
   }

   /** One row: what it says, and the key that identifies it to the client. */
   private record Row(String text, int value) {
   }

   /** How many rows this explorer's board is carrying - the numbers a check reads. */
   public static int rowsForTest(UUID id) {
      Panel panel = PANELS.get(id);
      return panel == null ? 0 : panel.rows.size();
   }

   /** Whether this explorer has a board up at all. */
   public static boolean shown(UUID id) {
      return PANELS.containsKey(id);
   }

   /** Forget every board - a server stop, or between checks. */
   public static void clear() {
      PANELS.clear();
   }

   /**
    * Draws one explorer's board from the lines of their run, top to bottom.
    *
    * <p>Diffed against what the client was last sent, so a clock whose seconds did not move costs
    * nothing: a site board rewritten ten times a second is a board that turns a hundred players
    * into a packet storm, and the sidebar exists to save the action bar from exactly that.
    */
   public static void show(ServerPlayer p, List<String> lines) {
      if (p == null || p.connection == null) {
         return;
      }
      Scoreboard sb = p.level().getServer().getScoreboard();
      Panel panel = PANELS.get(p.getUUID());
      if (panel == null) {
         String name = "ffexp" + nextObjective++;
         Objective existing = sb.getObjective(name);
         if (existing != null) {
            sb.removeObjective(existing);
         }
         Objective objective = sb.addObjective(
            name,
            ObjectiveCriteria.DUMMY,
            Component.literal(TITLE),
            RenderType.INTEGER,
            false,
            BlankFormat.INSTANCE
         );
         panel = new Panel(objective);
         PANELS.put(p.getUUID(), panel);
      }
      if (panel.shownOn != p.connection) {
         // A client that has only just been told to draw this objective knows nothing about it, or
         // has only just connected and knows nothing about anything. The objective itself goes to
         // its owner and only to its owner: an objective that is added to the world's scoreboard is
         // not broadcast by vanilla - only one sitting in a global display slot is - so without this
         // packet the client would be told to display a board it has never heard of.
         panel.shownOn = p.connection;
         panel.rows = List.of();
         sink.send(p, new ClientboundSetObjectivePacket(panel.objective, ClientboundSetObjectivePacket.METHOD_ADD));
         sink.send(p, new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, panel.objective));
      }
      unshare(sb, panel);
      push(p, panel, lines);
   }

   /**
    * Takes the board back out of any *shared* display slot it has ended up in.
    *
    * <p>A slot is global: vanilla broadcasts its display packet to every player, and then broadcasts
    * every score on the objective that sits in it. Anything that puts this objective in one -
    * an operator, another mod, a future call site here - therefore publishes somebody's run to the
    * whole server. So the board looks, every refresh, and takes itself back out.
    */
   private static void unshare(Scoreboard sb, Panel panel) {
      for (DisplaySlot slot : DisplaySlot.values()) {
         if (sb.getDisplayObjective(slot) == panel.objective) {
            sb.setDisplayObjective(slot, null);
         }
      }
   }

   /** Sends the rows that moved, and clears the ones that are gone. */
   private static void push(ServerPlayer p, Panel panel, List<String> lines) {
      int count = Math.min(lines.size(), MAX_ROWS);
      Row[] wanted = new Row[count];
      for (int i = 0; i < count; i++) {
         String text = lines.get(i);
         // Descending values: a sidebar is drawn from the highest score down, so the first line of
         // the run is the first line of the board.
         wanted[i] = new Row(text, MAX_ROWS - i);
      }
      Map<Integer, Row> before = new HashMap<>();
      for (Row row : panel.rows) {
         before.put(row.value(), row);
      }
      Map<Integer, Row> after = new HashMap<>();
      for (Row row : wanted) {
         after.put(row.value(), row);
      }
      boolean same = panel.rows.size() == count;
      for (int i = 0; same && i < count; i++) {
         same = panel.rows.get(i).text().equals(wanted[i].text());
      }
      if (same) {
         return;
      }
      String objective = panel.objective.getName();
      for (Row gone : panel.rows) {
         if (!after.containsKey(gone.value())) {
            // A row that is not reset stays on the client for good: clearing the score is what tells
            // the client to stop drawing the line.
            sink.send(p, new ClientboundResetScorePacket(key(objective, gone.value()), objective));
         }
      }
      for (Row row : wanted) {
         Row was = before.get(row.value());
         if (was != null && was.text().equals(row.text())) {
            continue;
         }
         sink.send(
            p,
            new ClientboundSetScorePacket(
               key(objective, row.value()),
               objective,
               row.value(),
               java.util.Optional.of(Component.literal(row.text())),
               java.util.Optional.empty()
            )
         );
      }
      panel.rows = List.of(wanted);
   }

   private static String key(String objective, int value) {
      return objective + "." + value;
   }

   /** Takes the board down for one explorer and cleans up the objective it was drawn on. */
   public static void hide(ServerPlayer p) {
      if (p == null || p.connection == null) {
         return;
      }
      Panel panel = PANELS.remove(p.getUUID());
      try {
         sink.send(p, new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
      } catch (Throwable ignored) {
      }
      if (panel == null) {
         return;
      }
      try {
         Scoreboard sb = p.level().getServer().getScoreboard();
         sb.removeObjective(panel.objective);
      } catch (Throwable ignored) {
         // The site keeps its own books; a sidebar that cannot be cleaned up is not worth a crash.
      }
   }
}
