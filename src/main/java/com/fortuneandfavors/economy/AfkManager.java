package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** /ff afk - manual toggle plus an idle auto-trigger, marked in the tab list.
 *  Anyone who stays completely still (no movement, no turning, no chat) for
 *  five minutes is automatically marked [AFK]; moving, turning, or typing a
 *  chat message clears it instantly. Fully server-side, session-only state. */
public final class AfkManager {
   /** Idle time before auto-AFK (server ticks): 5 minutes. */
   public static final int IDLE_TICKS = 6000;

   private static final Map<UUID, State> STATE = new HashMap<>();

   private AfkManager() {
   }

   private static final class State {
      boolean afk;
      long lastActive;
      Vec3 lastPos = Vec3.ZERO;
      float lastYRot;
      float lastXRot;
   }

   public static boolean isAfk(ServerPlayer p) {
      return p != null && isAfk(p.getUUID());
   }

   public static boolean isAfk(UUID id) {
      State s = STATE.get(id);
      return s != null && s.afk;
   }

   /** Manual toggle from /ff afk. Returns true when the player is now AFK. */
   public static boolean toggle(ServerPlayer p) {
      State s = STATE.computeIfAbsent(p.getUUID(), k -> new State());
      s.lastActive = p.level().getGameTime();
      if (s.afk) {
         s.afk = false;
         Chat.raw(p, "&7You are &a&lno longer AFK&7 - welcome back!");
         return false;
      } else {
         s.afk = true;
         Chat.raw(p, "&7You are now marked &e[AFK]&7 - move or type a message to clear it.");
         return true;
      }
   }

   /** Typing a chat message counts as activity (called from ChatGarblerMixin).
    *  Refreshes the tab list itself since it isn't always inside the tick loop. */
   public static void noteActivity(ServerPlayer p) {
      if (p == null) {
         return;
      }
      State s = STATE.get(p.getUUID());
      if (s == null) {
         return;
      }
      s.lastActive = p.level().getGameTime();
      if (s.afk) {
         s.afk = false;
         Chat.raw(p, "&7You are &a&lno longer AFK&7 - welcome back!");
         if (p.level().getServer() != null) {
            TagManager.refreshTabList(p.level().getServer());
         }
      }
   }

   /** Per-tick bookkeeping. Returns true when the AFK state flipped, so the
    *  caller can refresh the tab list once. */
   public static boolean tick(ServerPlayer p) {
      long now = p.level().getGameTime();
      State s = STATE.computeIfAbsent(p.getUUID(), k -> new State());

      boolean moving = !p.position().equals(s.lastPos) || p.getYRot() != s.lastYRot || p.getXRot() != s.lastXRot;
      s.lastPos = p.position();
      s.lastYRot = p.getYRot();
      s.lastXRot = p.getXRot();

      if (moving) {
         s.lastActive = now;
         if (s.afk) {
            s.afk = false;
            Chat.raw(p, "&7You are &a&lno longer AFK&7 - welcome back!");
            return true;
         }
         return false;
      }

      if (!s.afk && now - s.lastActive >= IDLE_TICKS) {
         s.afk = true;
         Chat.raw(p, "&7You're now marked &e[AFK]&7 - move or type a message to clear it.");
         return true;
      }
      return false;
   }

   /** AFK is session state - drop it on disconnect so a relog starts clean. */
   public static void onDisconnect(ServerPlayer p) {
      STATE.remove(p.getUUID());
   }
}