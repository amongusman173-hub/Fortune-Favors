package com.fortuneandfavors.anticheat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The packet half of the anticheat: what the client sent, judged before the
 * server has turned it into anything.
 *
 * <p>Every other check in this package reads a <i>state</i> the server already
 * accepted - a position it applied, a break it timed, a hit it landed. By then
 * the packet that carried it is gone, and with it the evidence that says the
 * packet could not have come from a client: a coordinate that is not a number, a
 * body that moved two hundred blocks inside one packet, a client ticking forty
 * times a second. Those are not movement that is <i>too fast</i>; they are values
 * vanilla itself can never produce, and they are visible exactly once.
 *
 * <p>This class is deliberately free of Minecraft imports. Everything it decides
 * is decided from numbers - a position, a rotation, a slot, a packet count and a
 * clock - which is what lets the self-test drive real cheating and real laggy
 * play straight through it and assert the difference.
 *
 * <p>The three things it is built to avoid:
 *
 * <ul>
 *   <li><b>Punishing lag.</b> The budget is a token bucket refilled off the wall
 *       clock, not the tick loop, so a slow tick does not tighten it; and it banks
 *       enough for a client to spend its maximum ten catch-up ticks in one burst.
 *       A player who stalls for two seconds and then sends everything at once is
 *       the single most common shape of honest traffic there is.
 *   <li><b>Punishing the server.</b> A packet the server itself asked for - a
 *       teleport it issued, a dimension it switched - is never judged. Where the
 *       answer is not knowable at this layer, the number is measured and passed up
 *       as evidence for the check that can see the whole picture.
 *   <li><b>Firing on one number.</b> A single implausible packet is exactly what
 *       an unknown mod doing something clever also produces, so only the
 *       impossible is refused outright (a coordinate that is not a number, a
 *       rotation no client sends, a body that crossed more than a chunk inside one
 *       packet). The merely improbable is counted, and has to repeat inside a
 *       window before anything is done about it.
 * </ul>
 *
 * <p>Every method returns a {@link Verdict} per packet. {@code refuse} means the
 * packet must not be applied; the caller decides how, because the right answer
 * differs: a forged position wants a correction sent back, a flood wants the
 * packet quietly dropped, and answering every dropped packet in a flood with a
 * teleport would be a packet storm in both directions.
 */
public final class PacketEngine {
   // ------------------------------------------------------------- packet kinds
   /** Position and rotation packets - one per client tick from an ordinary client. */
   public static final int MOVE = 0;
   /** Swings, attacks, interactions, block actions. */
   public static final int ACTION = 1;
   /** Inventory and container packets. */
   public static final int WINDOW = 2;
   /** Mod payloads, client settings, anything on a custom channel. */
   public static final int PAYLOAD = 3;
   private static final int CATEGORIES = 4;

   // ------------------------------------------------------------------ budget
   /**
    * How many packets a client may have banked. A client that falls behind runs
    * at most ten catch-up ticks before it draws a frame, and each of those ticks
    * sends its own movement packet, so an honest burst is around ten to twenty.
    * This is four times that, on purpose: the cost of a generous bank is that a
    * flood is noticed a fraction of a second later.
    */
   public static final int BANK = 80;
   /**
    * Refill per second - per <i>second</i> rather than per tick, so a server
    * running at five ticks a second cannot tighten the budget by accident. An
    * ordinary client sends a movement packet and a tick-end packet each tick with
    * a handful of others: two to four a second at rest, about twenty-five while
    * fighting. 120 is a ceiling no honest client reaches.
    */
   public static final double REFILL_PER_SECOND = 120.0;
   /**
    * Extra inventory packets a client may have banked while Mouse Tweaks or an
    * inventory sort is switched on. A shift-drag across a chest, or sorting a full
    * inventory, is one gesture to the player and dozens of container clicks to the
    * server, all inside a tick or two - which is a burst, not a flood, and the
    * difference is that it ends. The flood limit below is what still catches the
    * thing that does not end, and it is untouched by this.
    */
   public static final int INVENTORY_BANK_ALLOWANCE = 80;
   /** Packets refused beyond the bank before it is a flood. */
   public static final int FLOOD_LIMIT = 200;
   /** The window a flood is counted over. */
   public static final long FLOOD_WINDOW_NANOS = 10_000_000_000L;

   // --------------------------------------------------------------- positions
   /**
    * The furthest one packet may move a player. Vanilla's own server tolerates a
    * hundred times this - it multiplies a per-tick allowance by how many packets
    * arrived - and only teleports the player back, which is how a client that
    * skips ahead between ticks survives it. An elytra dive is 3.3 blocks a tick
    * and a boat on packed ice under 4, so eight clears the fastest thing a player
    * can legitimately do and still leaves a hack one packet wide.
    */
   public static final double MAX_PACKET_JUMP = 8.0;
   /**
    * ...and the distance past which repetition is not needed. Nothing legitimate
    * crosses it inside a single packet: the server's own teleports set a pending
    * position this check stands down for, so what is left out here is a packet
    * nobody sent.
    */
   public static final double IMPOSSIBLE_PACKET_JUMP = 32.0;
   /**
    * Blocks of headroom granted for every second the client has not sent a position.
    *
    * <p>Eight blocks per packet is an allowance for a packet <i>every tick</i>, and a
    * client that has sent nothing for a moment is entitled to have moved for that whole
    * moment. Without this the very fastest honest thing there is decides the answer: a
    * body falling at terminal velocity is 3.9 blocks a tick, so a client whose connection
    * stalls for three ticks and then sends the packet that says where it actually is
    * arrives 12 blocks from the last accepted one, over the per-packet allowance, and the
    * next packet that arrives late is over it again - three of them in the window and a
    * player playing perfectly normally is told they have sent an impossible move and is
    * put back on the spot. That is the report this number exists to answer.
    *
    * <p>Seventy-eight is terminal velocity exactly (3.9 blocks a tick), and it is set
    * there rather than above it on purpose: an honest gap can then never cross the
    * allowance - a body that stalled for a moment has travelled at most terminal velocity
    * for that moment - while a client that fabricates a jump per packet still does, since
    * a single packet's twelve blocks is a quarter of a second of free fall. Two packets a
    * tick from a client with a slow connection are unaffected: the second one is a tick
    * further along, so it is allowed a tick's worth more.
    *
    * <p>The gap the headroom is granted for is capped at a second. A packet that claims to
    * have waited longer than that is not a late packet, and the distance it claims is the
    * speed check's business rather than this one's.
    */
   public static final double LAG_HEADROOM_PER_SECOND = 78.0;
   /** The longest gap the headroom above is granted for. */
   public static final double LAG_HEADROOM_MAX_SECONDS = 1.0;
   /**
    * Implausible (but possible) jumps tolerated before the repetition is <i>reported</i>.
    *
    * <p>This is an evidence threshold and not a refusal, deliberately. The class's own
    * rule - written at the top of this file and worth keeping - is that only the
    * <i>impossible</i> is refused outright, because a refusal is answered with a set-back
    * and a set-back on a merely improbable packet is a body being put back for a
    * stutter. A client that keeps claiming twelve blocks a packet is a client the speed
    * check can see and clamp, which costs it the distance without ever moving an honest
    * player anywhere. The report is what staff read; the refusal is for coordinates no
    * client produces at all. "It keeps saying invalid move" was this number refusing.
    */
   public static final int JUMP_LIMIT = 3;
   public static final long JUMP_WINDOW_NANOS = 10_000_000_000L;

   /**
    * A yaw past this is not a rotation, it is a number someone wrote. Rotation
    * arrives wrapped - the client keeps its own view inside plus or minus 180 - so
    * a thousand is four turns of headroom over anything a mouse can make.
    */
   public static final float MAX_YAW = 1000.0F;
   /**
    * The magnitude past which an angle is not an unwrapped direction any more.
    *
    * <p>Yaw is wrapped into [-180, 180) the way vanilla wraps it, because a client that has
    * been turning left all fight sends 2127.65 for -32.35 and means it. Only a number this
    * far past anything accumulated turning produces is treated as impossible.
    */
   public static final float MAX_RAW_ANGLE = 100_000.0F;

   /**
    * Yaw as the game reads it: any finite angle, wrapped into [-180, 180).
    *
    * <p>Vanilla does exactly this in {@code ServerGamePacketListenerImpl} before it stores a
    * rotation, so an unwrapped yaw is legal input rather than an exploit. Written out here so
    * the movement path and the self-test agree on one definition.
    */
   public static float normalizeYaw(float yaw) {
      float wrapped = yaw % 360.0F;
      if (wrapped >= 180.0F) {
         wrapped -= 360.0F;
      } else if (wrapped < -180.0F) {
         wrapped += 360.0F;
      }
      return wrapped;
   }

   /** Pitch as the game reads it: clamped to the ninety degrees a body can look. */
   public static float normalizePitch(float pitch) {
      return Math.max(-90.0F, Math.min(90.0F, pitch));
   }
   /**
    * A pitch past this the same argument with a much smaller number: the client
    * clamps its own aim to straight up and straight down, and the server has no
    * business being told the player is looking through their own spine.
    */
   public static final float MAX_PITCH = 91.0F;

   /**
    * Degrees a second of continuous rotation that stops being a person. Six full
    * turns a second is past what a wrist can do, and past what a mouse can be made
    * to do for a full second - but a player can flick a hundred and eighty degrees
    * inside one packet, so the measurement is the average over
    * {@link #SPIN_TICKS_MIN} consecutive packets, each of which has to have turned
    * most of the way round on its own. Anything that resets the streak - one
    * ordinary turn - resets the count. It is only ever evidence passed to the
    * killaura check, never a violation of its own.
    */
   public static final double SPIN_DEGREES_PER_SECOND = 2400.0;
   public static final double SPIN_AURA_PER_TICK = 0.05;
   /** How long a swing keeps the spin being counted as aimed rotation. */
   public static final long SPIN_ATTACK_WINDOW_NANOS = 1_000_000_000L;
   /** Consecutive wide turns before any of it is counted. */
   public static final int SPIN_TICKS_MIN = 10;

   // ------------------------------------------------------------- client tick
   /**
    * Client ticks per second. A client ticks twenty times a second whether it runs
    * at 15 frames or 500 - catch-up only ever repays ticks it lost - so a sustained
    * average above this is a client declaring it lives in a faster world than the
    * server, which is what a timer hack does.
    */
   public static final double TICK_RATE_LIMIT = 26.0;
   public static final long TICK_RATE_WINDOW_NANOS = 10_000_000_000L;
   /** Tick-end packets a window needs before its average means anything. */
   public static final int TICK_RATE_MIN_SAMPLES = 40;
   /**
    * Movement packets a second before the client's clock is outrunning the server's.
    *
    * <p>The companion to the tick-rate limit above, and it exists because the two are
    * <b>different channels and a timer module only has to lie on one of them</b>. Tick-
    * end packets are a courtesy - a client that stops sending them is not refused
    * anything - so a timer that suppresses them and simply moves more often is invisible
    * to that check. Movement packets are not optional: they are how the body gets
    * anywhere, and a client running its own tick loop at forty a second sends forty of
    * them, which is the number this reads.
    *
    * <p>Twenty is one per tick, so the limit sits half again over it. The allowance above
    * the honest figure is for the burst a stalled client produces when it catches up - up
    * to ten ticks in one frame - which averages out over the window below rather than
    * being excused, exactly as the budget does.
    */
   public static final double MOVE_RATE_LIMIT = 30.0;
   /** Long enough that a catch-up burst cannot hold the average up on its own. */
   public static final long MOVE_RATE_WINDOW_NANOS = 5_000_000_000L;
   /** Packets a window needs before its average is an average. */
   public static final int MOVE_RATE_MIN_SAMPLES = 80;

   /** Hotbar slots, the only range a client may select. */
   public static final int HOTBAR_SLOTS = 9;
   /** Invalid container clicks tolerated inside the window before flagging. */
   public static final int BAD_SLOT_LIMIT = 3;
   public static final long BAD_SLOT_WINDOW_NANOS = 10_000_000_000L;

   // ------------------------------------------------------------------ results
   /** Nothing to say about this packet. */
   public static final Verdict ALLOW = new Verdict(null, null, false, 0.0);
   /** Drop it, and say nothing - the budget is simply spent. */
   private static final Verdict SPENT = new Verdict(null, null, true, 0.0);

   private static final Map<UUID, Client> CLIENTS = new HashMap<>();

   private PacketEngine() {
   }

   /** One packet's worth of judgement. */
   public record Verdict(String check, String detail, boolean refuse, double aura) {
   }

   private static Verdict flag(String check, String detail) {
      return new Verdict(check, detail, false, 0.0);
   }

   private static Verdict refuse(String check, String detail) {
      return new Verdict(check, detail, true, 0.0);
   }

   // -------------------------------------------------------------------- move

   /**
    * A position packet, as the server received it.
    *
    * <p>The rotation-only and position-only variants arrive here too: the caller
    * fills the missing half from the player's current state, which is what vanilla
    * does with the same packet, so a rotation-only packet contributes no movement
    * and a position-only packet no turn.
    *
    * @param teleportPending the server has a position in flight and is waiting for
    *        the client to confirm it. Nothing about this packet is judged while
    *        that is true: the client is answering the server, not moving.
    * @param scripted the server itself is driving this body - elytra, a vehicle,
    *        water, a time stop - so movement that looks impossible is not.
    */
   public static Verdict move(
      UUID id,
      long now,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      boolean teleportPending,
      boolean scripted
   ) {
      return move(id, now, x, y, z, yaw, pitch, teleportPending, scripted, false);
   }

   /**
    * As above, told whether the packet agrees with where the server already has this
    * body.
    *
    * <p>That is the one thing that separates a forged position from an honest client
    * answering a write the server made without leaving anything in flight - a rewind,
    * a scripted shove, an ability that put somebody down and never told the module.
    * Measured against the last accepted packet instead, that answer is a jump with
    * nothing to explain it, and it was refused and set back mid-ability.
    *
    * <p>Deliberately its own argument rather than folded into {@code teleportPending}:
    * a pending teleport stands the whole packet down and restarts the windows it feeds,
    * and borrowing it here reset the speed window on every clamp - which is how a body
    * that keeps running after three corrections stopped being told anything at all.
    */
   public static Verdict move(
      UUID id,
      long now,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      boolean teleportPending,
      boolean scripted,
      boolean agreesWithServer
   ) {
      Client client = client(id);
      Verdict budget = spend(client, MOVE, now);
      if (budget != ALLOW) {
         // Either the budget is simply spent or it has just tipped into a flood.
         // The caller knows whether a teleport is in flight and will leave the
         // packet alone if so; either way it is reported once it is a flood.
         return budget;
      }
      // The movement channel's own clock. See MOVE_RATE_LIMIT for why this is a
      // separate measurement from the tick-end one rather than a duplicate of it.
      if (client.moveWindow == 0L) {
         client.moveWindow = now;
         client.moveCount = 0;
      }
      client.moveCount++;
      long moveElapsed = now - client.moveWindow;
      if (moveElapsed >= MOVE_RATE_WINDOW_NANOS) {
         int moves = client.moveCount;
         client.moveCount = 0;
         client.moveWindow = now;
         double elapsedSeconds = moveElapsed / 1.0E9;
         double rate = moves / elapsedSeconds;
         if (moves >= MOVE_RATE_MIN_SAMPLES && rate > MOVE_RATE_LIMIT) {
            return flag(
               AntiCheat.TIMER,
               "sent " + moves + " movement packets in " + round(elapsedSeconds)
                  + "s, " + round(rate) + " a second against a twenty tick client"
            );
         }
      }

      // ---- values that are not values. Vanilla disconnects on these itself, so
      // this records the evidence and lets it: cancelling here would pre-empt a
      // disconnect that is correct, and the numbers are what staff need to see.
      if (!isFinite(x) || !isFinite(y) || !isFinite(z) || !isFinite(yaw) || !isFinite(pitch)) {
         client.hasPosition = false;
         client.hasRotation = false;
         return flag(
            AntiCheat.INVALID_MOVE,
            "a position packet carrying " + describe(x) + ", " + describe(y) + ", " + describe(z)
               + " facing " + describe(yaw) + "/" + describe(pitch)
         );
      }

      Verdict verdict = ALLOW;

      // ---- an angle is a direction, not a number.
      //
      // This used to refuse any packet whose yaw was past MAX_YAW, on the belief that no
      // client sends one. Clients do, constantly: vanilla itself wraps yaw into [-180, 180)
      // and clamps pitch to [-90, 90] when it APPLIES a rotation, precisely because the
      // number on the wire is not bounded - a client that has been turning left all fight
      // sends 2127.65, which is -32.35 and a perfectly ordinary direction. Third-party
      // client mods and camera work send the unwrapped form all the time. So the honest
      // answer is the game's own: wrap the angle here, use the wrapped value everywhere
      // downstream, and refuse only a number no client could produce at all.
      float rawYaw = yaw;
      float rawPitch = pitch;
      yaw = normalizeYaw(yaw);
      pitch = normalizePitch(pitch);

      if (Math.abs(rawYaw) > MAX_RAW_ANGLE || Math.abs(rawPitch) > MAX_RAW_ANGLE) {
         // A sanity bound, not a look rule: the biggest number a client accumulates by
         // turning is thousands, and this is a hundred times past it.
         verdict = refuse(
            AntiCheat.INVALID_MOVE,
            "facing " + round(rawYaw) + " / " + round(rawPitch)
               + " - an unwrapped angle is a direction, but not this one"
         );
      } else if (client.hasRotation && !scripted) {
         double turn = Math.abs(wrap(yaw - client.lastYaw));
         boolean swinging = now - client.lastAttackNanos < SPIN_ATTACK_WINDOW_NANOS;
         if (turn > SPIN_DEGREES_PER_SECOND / 20.0 && swinging) {
            client.spinDegrees += turn;
            client.spinTicks++;
            if (client.spinTicks >= SPIN_TICKS_MIN) {
               double perSecond = client.spinDegrees / client.spinTicks * 20.0;
               if (perSecond > SPIN_DEGREES_PER_SECOND) {
                  // Evidence, not a violation: the combat check owns the aura
                  // score and the decision to refuse a swing.
                  verdict = new Verdict(null, null, false, SPIN_AURA_PER_TICK);
               }
            }
         } else {
            client.spinDegrees = 0.0;
            client.spinTicks = 0;
         }
      }

      // ---- positions no client sends.
      boolean baseline = true;
      if (client.hasPosition && !teleportPending && !scripted) {
         double dx = x - client.lastX;
         double dy = y - client.lastY;
         double dz = z - client.lastZ;
         double jump = Math.sqrt(dx * dx + dy * dy + dz * dz);
         // How long this client has owned the jump it just took. A packet that arrives a
         // tick late is a packet that is allowed to be a tick further away.
         double elapsedSeconds = client.lastPositionNanos == 0L
            ? 0.0
            : Math.min(LAG_HEADROOM_MAX_SECONDS, Math.max(0.0, (now - client.lastPositionNanos) / 1.0E9));
         double allowed = MAX_PACKET_JUMP + LAG_HEADROOM_PER_SECOND * elapsedSeconds;
         if (jump > allowed && !agreesWithServer) {
            client.impossibleJumps++;
            if (now - client.jumpWindow > JUMP_WINDOW_NANOS) {
               client.jumpWindow = now;
               client.impossibleJumps = 1;
            }
            // Only the impossible is refused. A body that crossed more than a chunk inside
            // one packet is not something a client sends; a body that crossed twelve blocks
            // is something a client sends all the time when it is behind, and the answer to
            // it is the speed check's clamp rather than a set-back. The repetition is still
            // reported - once, at the moment it stops being one packet and becomes a pattern -
            // so the ledger and the staff alert see exactly what they saw before.
            if (jump >= IMPOSSIBLE_PACKET_JUMP) {
               client.impossibleJumps = 0;
               verdict = refuse(
                  AntiCheat.INVALID_MOVE,
                  round(jump) + " blocks inside one position packet, with no teleport in flight to explain it"
               );
               baseline = false;
            } else if (client.impossibleJumps == JUMP_LIMIT) {
               verdict = flag(
                  AntiCheat.INVALID_MOVE,
                  client.impossibleJumps + " position packets in a row each claimed more than a packet's"
                     + " worth of travel (" + round(jump) + " blocks on this one) - counted, not corrected"
               );
            }
         } else if (jump > MAX_PACKET_JUMP) {
            // A jump this packet was entitled to is not a mark against the next one. The
            // count is meant to be three impossible jumps, not three jumps over the
            // per-packet figure while the connection catches up.
            client.impossibleJumps = 0;
         }
      }

      // A refused packet is not where the player is, and treating it as if it were is
      // the loop that made a client that had stopped cheating keep being put back. The
      // forged position became the baseline, so the server's correction - and then every
      // honest packet that followed it - measured as the next forgery, and the module
      // spent the rest of the session answering its own last answer. Keeping the last
      // *accepted* position instead means a client that is really one place and claims
      // another is refused again on the next packet rather than being believed once and
      // then left alone, and one that has stopped is judged against the truth.
      if (baseline) {
         client.lastX = x;
         client.lastY = y;
         client.lastZ = z;
         client.lastPositionNanos = now;
         client.hasPosition = true;
      }
      client.lastYaw = yaw;
      client.hasRotation = true;
      return verdict;
   }

   /** An attack packet. Records when, for the spin evidence above. */
   public static Verdict attack(UUID id, long now) {
      Client client = client(id);
      client.lastAttackNanos = now;
      return spend(client, ACTION, now);
   }

   /** Any other action packet: a swing, an interaction, a block action, a use. */
   public static Verdict action(UUID id, long now) {
      return spend(client(id), ACTION, now);
   }

   /** A container or inventory packet. */
   public static Verdict window(UUID id, long now) {
      return spend(client(id), WINDOW, now);
   }

   /** A mod payload, a client setting, anything on a custom channel. */
   public static Verdict payload(UUID id, long now) {
      return spend(client(id), PAYLOAD, now);
   }

   /**
    * A hotbar selection outside the hotbar. A client's own inventory has exactly
    * nine selectable slots and its scroll wheel cannot leave them, so this is not
    * a mistake a player makes - and vanilla already refuses to apply it, which
    * means refusing it here changes nothing except that it is now on the record.
    */
   public static Verdict carriedSlot(int slot) {
      if (slot >= 0 && slot < HOTBAR_SLOTS) {
         return ALLOW;
      }
      return refuse(
         AntiCheat.PACKET_ORDER,
         "selected hotbar slot " + slot + ", which does not exist (0-" + (HOTBAR_SLOTS - 1) + ")"
      );
   }

   /**
    * A container click on a slot the open menu does not have. Vanilla ignores these
    * and logs them, so refusing is identical behaviour; what makes it worth
    * counting rather than believing outright is that a client whose menu is one
    * server tick stale produces the same packet honestly, and a stale client
    * produces one or two of them, not a stream.
    */
   public static Verdict badSlot(UUID id, long now, int slot) {
      Client client = client(id);
      if (now - client.badSlotWindow > BAD_SLOT_WINDOW_NANOS) {
         client.badSlotWindow = now;
         client.badSlots = 0;
      }
      int count = ++client.badSlots;
      if (count < BAD_SLOT_LIMIT) {
         return SPENT;
      }
      client.badSlots = 0;
      return refuse(
         AntiCheat.PACKET_ORDER,
         count + " clicks on slot " + slot + ", which the menu the client has open does not have"
      );
   }

   /**
    * One client tick ended. The client sends exactly one of these per tick of its
    * own, which makes it the only place the server is told how fast the client
    * believes time is passing. Measured over {@link #TICK_RATE_WINDOW_NANOS} so the
    * burst a client produces while catching up from a stall - legitimate, and
    * bounded by ten ticks a frame - averages out against the seconds it lost.
    */
   public static Verdict tickEnd(UUID id, long now) {
      Client client = client(id);
      if (client.tickWindow == 0L) {
         client.tickWindow = now;
         client.clientTicks = 0;
      }
      client.clientTicks++;
      long elapsed = now - client.tickWindow;
      if (elapsed < TICK_RATE_WINDOW_NANOS) {
         return ALLOW;
      }
      int ticks = client.clientTicks;
      client.clientTicks = 0;
      client.tickWindow = now;
      if (ticks < TICK_RATE_MIN_SAMPLES) {
         return ALLOW;
      }
      double rate = ticks * 1.0E9 / elapsed;
      if (rate <= TICK_RATE_LIMIT) {
         return ALLOW;
      }
      return flag(
         AntiCheat.CLIENT_TICK,
         "ran " + round(rate) + " client ticks a second across " + round(elapsed / 1.0E9) + "s, against the server's 20"
      );
   }

   // ------------------------------------------------------------------ budget

   /**
    * Takes one token for this category, or refuses the packet.
    *
    * <p>Per category rather than one shared bucket, which matters more than it
    * looks: a client flooding its mod channel must not starve the movement packets
    * through the same door, or the anticheat would create the desync it exists to
    * prevent.
    */
   private static Verdict spend(Client client, int category, long now) {
      refill(client, now);
      if (client.tokens[category] >= 1.0) {
         client.tokens[category] -= 1.0;
         return ALLOW;
      }
      if (now - client.floodWindow[category] > FLOOD_WINDOW_NANOS) {
         client.floodWindow[category] = now;
         client.refused[category] = 0;
      }
      int refused = ++client.refused[category];
      if (refused < FLOOD_LIMIT) {
         return SPENT;
      }
      client.refused[category] = 0;
      client.floodWindow[category] = now;
      return refuse(
         AntiCheat.PACKET_FLOOD,
         FLOOD_LIMIT + " " + name(category) + " packets from one client in "
            + (FLOOD_WINDOW_NANOS / 1_000_000_000L) + "s - vanilla logs this and lets it through"
      );
   }

   private static void refill(Client client, long now) {
      if (client.lastRefill == 0L) {
         client.lastRefill = now;
         return;
      }
      long elapsed = now - client.lastRefill;
      if (elapsed <= 0L) {
         return;
      }
      client.lastRefill = now;
      double gained = elapsed / 1.0E9 * REFILL_PER_SECOND;
      for (int i = 0; i < CATEGORIES; i++) {
         client.tokens[i] = Math.min(bankFor(i), client.tokens[i] + gained);
      }
   }

   /**
    * How many packets of this kind a client may bank. Only the inventory bucket
    * moves, and only while the compatibility mode for the mods that burst it is on.
    */
   private static int bankFor(int category) {
      if (category == WINDOW && QolCompat.on(QolCompat.QUICK_INVENTORY)) {
         return BANK + INVENTORY_BANK_ALLOWANCE;
      }
      return BANK;
   }

   // ------------------------------------------------------------------- state

   public static void forget(UUID id) {
      CLIENTS.remove(id);
   }

   /**
    * The server has written this body's position, and this layer does not know to where.
    *
    * <p>Whatever the client sends next is the new baseline rather than a jump away from
    * the last one. That is the honest reading of a teleport this layer cannot see the
    * destination of - a relative position packet, a dimension change - and it costs one
    * packet of judgement, once, in exchange for never measuring a client against a place
    * the server knows it is not.
    */
   public static void forgetPosition(UUID id) {
      Client client = id == null ? null : CLIENTS.get(id);
      if (client == null) {
         return;
      }
      client.hasPosition = false;
      client.impossibleJumps = 0;
      client.jumpWindow = 0L;
   }

   /**
    * The server put this body at a position this layer <i>does</i> know.
    *
    * <p>Used by this module's own correction, where the destination is the safe spot it
    * just chose. Anchoring on it is what makes a correction a correction: the client is
    * expected where the server put it, so a packet from the forged position is still a
    * forgery rather than the new truth.
    */
   public static void reanchor(UUID id, double x, double y, double z) {
      Client client = id == null ? null : CLIENTS.get(id);
      if (client == null) {
         return;
      }
      client.hasPosition = true;
      client.lastX = x;
      client.lastY = y;
      client.lastZ = z;
      // The gap is measured from the correction, not from the packet the correction
      // answered: the next packet is the client agreeing with where the server just put
      // it, and it is not owed the headroom the refusal itself took up.
      client.lastPositionNanos = System.nanoTime();
      client.impossibleJumps = 0;
      client.jumpWindow = 0L;
   }

   public static void clear() {
      CLIENTS.clear();
   }

   /** How many clients the engine is remembering, for the self-test. */
   public static int tracked() {
      return CLIENTS.size();
   }

   /**
    * A one-line readout of what the packet layer believes about a client, for
    * {@code /ff anticheat simulate}. The number that matters is the refusals:
    * a refusal is a packet the engine dropped, so a client with none has had
    * every packet it sent accepted, which is what an honest session looks like.
    */
   public static String summarize(UUID id) {
      Client client = id == null ? null : CLIENTS.get(id);
      if (client == null) {
         return "no traffic this session";
      }
      int worst = 0;
      String worstKind = "none";
      for (int i = 0; i < CATEGORIES; i++) {
         if (client.refused[i] > worst) {
            worst = client.refused[i];
            worstKind = name(i);
         }
      }
      StringBuilder buckets = new StringBuilder();
      for (int i = 0; i < CATEGORIES; i++) {
         if (buckets.length() > 0) {
            buckets.append(", ");
         }
         buckets.append(name(i)).append(' ').append(round(client.tokens[i])).append('/').append(bankFor(i));
      }
      return (worst == 0 ? "clean" : worst + " refused (" + worstKind + ")")
         + ", impossible jumps " + client.impossibleJumps
         + ", spin " + round(client.spinDegrees) + "deg"
         + ", bad slots " + client.badSlots
         + ", tick samples " + client.clientTicks
         + " &8| buckets: " + buckets;
   }

   private static Client client(UUID id) {
      return CLIENTS.computeIfAbsent(id, key -> new Client());
   }

   // ----------------------------------------------------------------- helpers

   private static boolean isFinite(double value) {
      return !Double.isNaN(value) && !Double.isInfinite(value);
   }

   private static boolean isFinite(float value) {
      return !Float.isNaN(value) && !Float.isInfinite(value);
   }

   /** The shortest way round the circle between two yaws. */
   static float wrap(float degrees) {
      float wrapped = degrees % 360.0F;
      if (wrapped >= 180.0F) {
         wrapped -= 360.0F;
      }
      if (wrapped < -180.0F) {
         wrapped += 360.0F;
      }
      return wrapped;
   }

   private static String name(int category) {
      return switch (category) {
         case MOVE -> "movement";
         case ACTION -> "action";
         case WINDOW -> "inventory";
         default -> "mod";
      };
   }

   private static String describe(double value) {
      if (Double.isNaN(value)) {
         return "NaN";
      }
      return Double.isInfinite(value) ? (value > 0 ? "+infinity" : "-infinity") : round(value);
   }

   private static String round(double value) {
      return String.format(java.util.Locale.ROOT, "%.2f", value);
   }

   /** Everything remembered about one client's traffic. */
   private static final class Client {
      private final double[] tokens = new double[CATEGORIES];
      private long lastRefill;
      private final int[] refused = new int[CATEGORIES];
      private final long[] floodWindow = new long[CATEGORIES];

      private boolean hasPosition;
      private double lastX;
      private double lastY;
      private double lastZ;
      /**
       * When the last accepted position packet arrived, in packet-clock nanos.
       *
       * <p>The per-packet jump allowance needs it: the distance a packet may legitimately
       * carry is the distance the client had to travel since its own last packet, so an
       * allowance that ignores the gap between them reads a late packet as a forged one.
       */
      private long lastPositionNanos;
      private int impossibleJumps;
      private long jumpWindow;

      private boolean hasRotation;
      private float lastYaw;
      private double spinDegrees;
      private int spinTicks;
      private long lastAttackNanos;

      private int badSlots;
      private long badSlotWindow;

      private int clientTicks;
      private long tickWindow;

      private int moveCount;
      private long moveWindow;

      private Client() {
         for (int i = 0; i < CATEGORIES; i++) {
            this.tokens[i] = bankFor(i);
         }
      }
   }
}
