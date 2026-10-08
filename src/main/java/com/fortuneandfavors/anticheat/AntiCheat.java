package com.fortuneandfavors.anticheat;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.AdvancedEnchantments;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.EquipmentAttributes;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.PermissionManager;
import com.fortuneandfavors.economy.TimeLordManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.ModPlatform;
import com.fortuneandfavors.util.PerfMonitor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A server-side anticheat: <b>off by default</b>, OP-exempt by default.
 *
 * <p>This is a mod, not a client, so every check here reads only what the server
 * is told: a position, a swing, a placement, a break. That has two consequences
 * worth stating plainly, because they are the honest limits of the tool:
 *
 * <ul>
 *   <li>Nothing here can see a hacked client. What it sees is a <i>consequence</i>
 *       - a player who crossed four blocks in one tick, broke obsidian in two, or
 *       attacked something behind them - so every check is a heuristic with a
 *       threshold, and the thresholds are deliberately loose.
 *   <li>This mod grants movement the vanilla rules do not: super jumps, Sonic
 *       Boom launches, elytra boosts, riptide, elevators, realm hops and a dozen
 *       scripted teleports. A check that fights those is worse than no check, so
 *       every check that judges movement stands down while a player is flying,
 *       spinning, swimming, riding, rooted by a time stop, or was moved by the
 *       server a moment ago.
 * </ul>
 *
 * <p>Checks: speed, flight, reach, killaura, scaffold, fast-mine, knockback
 * delay, ore vision (X-ray), no-fall, block reach, packet flood,
 * invalid move, packet order and client tick. The last four read the inbound
 * packets themselves, through {@link PacketEngine}, which is the only layer at
 * which a coordinate that is not a number or a client ticking twice as fast as
 * the server is visible at all. Alerts go to
 * staff only, and they are rate limited per check per player. The impossible
 * actions - a hit out of reach, a
 * swing at nothing you are facing, a block placed under a player who is falling,
 * a break faster than the tool allows, a position no client can produce - are
 * refused outright, and speed, flight
 * and knockback delay are corrected (set back / re-applied) rather than merely
 * reported. X-ray is the one thing that cannot be refused - a client does not
 * need the server's permission to look at blocks it can already see - so it is
 * counted and reported instead.
 *
 * <p>Default: <b>off</b>. {@code /ff anticheat on} turns it on,
 * {@code /ff anticheat op on} includes operators (which are skipped by default,
 * so staff can test it on themselves).
 */
public final class AntiCheat {
   // ------------------------------------------------------------- check names
   public static final String SPEED = "speed";
   /** Windowed speed prediction; distinct from the per-tick acceleration simulation. */
   public static final String SPEED_PREDICTION = "speed-prediction";
   public static final String FLIGHT = "flight";
   public static final String REACH = "reach";
   public static final String KILLAURA = "killaura";
   public static final String SCAFFOLD = "scaffold";
   public static final String FAST_MINE = "fast-mine";
   public static final String KNOCKBACK = "knockback";
   public static final String ORE_VISION = "ore-vision";
   /**
    * A rush of one specific ore inside one short window - see {@link #oreRush}.
    *
    * <p>Separate from {@link #ORE_VISION} rather than folded into it, because the two are
    * answers to different questions and staff need to be able to tell them apart. Ore vision asks
    * "how was this found" - a buried ore with no open face, a yield a seam cannot produce. A rush
    * asks "how <b>many</b>, how <b>fast</b>", which is the shape the other three cannot see: a
    * careful x-rayer walks to the ores it can already see through stone and mines normally, so it
    * never trips the face test, never makes a bee-line and keeps its session average respectable.
    * What it cannot do is arrive at thirty diamonds in five minutes, because there are not thirty
    * diamonds within five minutes of anybody who has to find them by digging.
    */
   public static final String XRAY_RUSH = "xray-rush";
   public static final String NO_FALL = "no-fall";
   /**
    * Claiming a floor that is not there: {@code onGround} with nothing under them.
    *
    * <p>Split out of {@link #FLIGHT} rather than added beside it. The two look like one
    * detector - a body in the air that should not be - but they are opposite hacks with
    * opposite signatures, and lumping them together is why an admin looking at the
    * ledger could not tell which one was firing. Flight hovers and admits it. AirWalk
    * <i>lies about the ground</i>, which is the thing that lets a client skip fall damage
    * and reach places it should fall out of.
    */
   public static final String AIR_WALK = "air-walk";
   /** One airborne arc that carried further than the sprint-jump model allows. */
   public static final String LONG_JUMP = "long-jump";
   /** Re-ascending in mid-air: a jump applied again inside a fall. */
   public static final String BUNNY_HOP = "bunny-hop";
   /** Moving across a water surface while not in the water and not falling through it. */
   public static final String JESUS = "jesus";
   public static final String BLOCK_REACH = "block-reach";
   /** A client sending more packets than any client sends. */
   public static final String PACKET_FLOOD = "packet-flood";
   /** A position or rotation vanilla's client cannot produce. */
   public static final String INVALID_MOVE = "invalid-move";
   /** A packet that contradicts the connection's own state. */
   public static final String PACKET_ORDER = "packet-order";
   /** A client ticking faster than the game it is playing. */
   public static final String CLIENT_TICK = "client-tick";
   /** Taps arriving faster than the weapon could use them, in a pattern no hand
    *  produces - see {@link CombatStats}. */
   public static final String AUTOCLICKER = "autoclicker";
   /** Hits landing on a moving target's centre over and over, to any degree, more
    *  precisely than a crosshair is held. See {@link CombatStats}. */
   public static final String AIM = "aim";
   /**
    * Gaining more momentum in one tick than the body's own input could give it.
    *
    * <p>This is the check {@link #SPEED} structurally cannot be. Speed judges a
    * <i>level</i>: how fast the player is, against a ceiling for the surface and
    * their own attributes. A speed module can stay under any level for a while and
    * still be obvious, because the thing it changes is not the level but the
    * <i>rate of change</i> - it adds velocity that the client's own input cannot
    * account for. See {@link MotionModel} for the arithmetic and, more
    * importantly, for why it is only ever applied to ticks where the body was
    * grounded on both sides of the step.
    */
   public static final String SIMULATION = "simulation";
   /**
    * A rise no jump can produce.
    *
    * <p>The vertical half of the simulation. A body that is airborne, did not
    * jump, and went <i>up</i> by more than a jump impulse has been lifted by
    * something, and the set of things that can lift a body legitimately is small
    * enough to enumerate - see {@link #scriptedMovement}. Deliberately about
    * rising and not about hovering: "not falling" is what standing on anything
    * looks like, which is why the hover checks need a full forty ticks of pattern
    * and this one needs three.
    */
   public static final String MOTION = "motion";
   /**
    * Altitude that gravity cannot account for, over a window.
    *
    * <p>The windowed companion to {@link #MOTION}: where that one asks whether a
    * single tick rose too far, this asks whether the whole arc held an altitude
    * the fall model says it should have lost. That is what separates a jump from
    * flight at a glance - a jump's observed path tracks the predicted one for its
    * whole length, and a flying body's diverges and never comes back.
    */
   public static final String FLIGHT_PREDICTION = "flight-prediction";
   /**
    * Claiming a floor that is not under you.
    *
    * <p>Narrower than {@link #AIR_WALK} on purpose, and it exists because the two
    * have different jobs. Air-walk needs twelve ticks of a lie and answers "is this
    * body standing on nothing". This one fires on the <i>tick</i> the lie is told,
    * and only when the lie is being used - the body claims ground while it is
    * descending through a space nothing occupies, which is not a step, a stair or a
    * slab, and is the flag that lets a client skip fall damage and earn a critical
    * hit it did not jump for.
    */
   public static final String GROUND_SPOOF = "ground-spoof";
   /**
    * A claimed airborne state at the moment a hit landed.
    *
    * <p>Vanilla decides a critical hit from the <i>server's</i> view - airborne,
    * descending, not climbing, not swimming - so a client cannot ask for one.
    * What it can do is make the server's view say so: report {@code onGround=false}
    * while standing, so the server's own critical-hit arithmetic produces a crit
    * the player never jumped for. This is that, correlated with the attack, which
    * is why it is its own check rather than a note on the spoof: a spoofed flag on
    * its own is a desync, and a spoofed flag on the tick of a hit is a hack.
    */
   public static final String CRITICAL_GROUND = "critical-ground";
   /**
    * A client whose own clock is outrunning the server's, measured from movement.
    *
    * <p>Distinct from {@link #CLIENT_TICK}, which counts tick-end packets. A timer
    * module does not have to send those at all: it speeds up its own tick loop and
    * lets the extra movement packets arrive as extra movement. So the two together
    * are the check - either channel outrunning the game is enough, and a client
    * that suppresses one to hide the other is still caught by the other.
    */
   public static final String TIMER = "timer";
   /** Full speed while holding an item that is supposed to cost most of it. */
   public static final String NO_SLOW = "no-slow";
   /**
    * Climbing faster than a ladder allows.
    *
    * <p>Vanilla's climb is a constant: a body on a ladder, vine or scaffolding rises 0.2
    * blocks a tick and no more, whatever its speed attribute says - which is why a fast-climb
    * module is not a speed check with a different name. Read from the body's own reported rise
    * while it is standing on a climbable, and only after a run of ticks, so a single strange
    * tick (a lag spike, a shove from a boss, a boat under a waterfall) is not a finding.
    */
   public static final String FAST_CLIMB = "fast-climb";
   /** One tick of climbing vanilla does not do. */
   public static final double FAST_CLIMB_STEP = 0.31;
   /** How many consecutive fast ticks make a module rather than an accident. */
   public static final int FAST_CLIMB_TICKS = 4;
   /** A consumable finishing faster than the item's own use duration allows. */
   public static final String FAST_USE = "fast-use";
   /** Long distances sprinted with no food or saturation paid for any of it. */
   public static final String ANTI_HUNGER = "anti-hunger";
   /**
    * A projectile avoided on the tick it became unavoidable.
    *
    * <p>The other half of the combat picture, and the one nothing else can see. Every
    * combat check so far reads what an attacker <i>did</i>; this reads what a defender
    * <i>knew</i>. The evidence is a coincidence rather than a speed: the body leaves the
    * line of an incoming projectile on the same tick, and within a tick of, the moment that
    * projectile could no longer be walked out of - fifty milliseconds, against the two
    * hundred a person needs to see and decide. One of those is a lucky strafe. Three inside
    * a window is a client that saw the arrow before the server did.
    */
   public static final String ARROW_DODGE = "arrow-dodge";
   /**
    * Hits that only landed because the target was treated as larger than it is.
    *
    * <p>Split from {@link #REACH} deliberately, because the two hacks are the same
    * arithmetic read from opposite ends and an admin needs to know which one it is.
    * Reach moves the <i>attacker's</i> aim target outward: the hit is farther than
    * the attacker's arm. Hitbox expansion leaves the attacker alone and grows the
    * <i>target</i>, so the client's ray reaches a body the server thinks is out of
    * arm's length. Server-side both arrive as one number - how far the hit was - so
    * the only honest way to tell them apart is which of the two distances is
    * impossible: the eye-to-centre distance (the attacker's aim was too far) or the
    * eye-to-surface distance (the attacker's arm was too short for the box they were
    * given). This check is the second one.
    */
   public static final String HITBOX = "hitbox";
   /** More swings inside one tick than the weapon can produce. */
   public static final String KILLAURA_6H = "killaura-6h";
   /** Successive rotation deltas that are exactly equal, to the last register. */
   public static final String AIM_CONSTANT = "aim-constant";
   /** Successive rotation deltas in an exactly constant ratio - an eased turn. */
   public static final String AIM_LINEAR = "aim-linear";
   /** A held turn at a constant rate with no jitter at all, for over a second. */
   public static final String AIM_MODULE_360 = "aim-module-360";
   /**
    * A click that arrived while the weapon's own cooldown had seconds left on it.
    *
    * <p>The autoclicker check reads a <i>pattern</i> and therefore needs
    * twenty-four taps. This reads a single one, and it is the narrowest and most
    * decisive of the combat checks: a client that swings a sword twice inside one
    * tick has not found a faster rhythm, it has ignored the cooldown entirely.
    */
   public static final String FAST_SWING = "fast-swing";

   /** Every check, in the order the status screen lists them. */
   public static final List<String> CHECKS = List.of(
      SPEED,
      SPEED_PREDICTION,
      SIMULATION,
      MOTION,
      FLIGHT,
      FLIGHT_PREDICTION,
      AIR_WALK,
      GROUND_SPOOF,
      LONG_JUMP,
      BUNNY_HOP,
      JESUS,
      NO_FALL,
      NO_SLOW,
      FAST_CLIMB,
      ANTI_HUNGER,
      ARROW_DODGE,
      TIMER,
      CLIENT_TICK,
      REACH,
      HITBOX,
      KILLAURA,
      KILLAURA_6H,
      CRITICAL_GROUND,
      FAST_SWING,
      AUTOCLICKER,
      AIM,
      AIM_CONSTANT,
      AIM_LINEAR,
      AIM_MODULE_360,
      SCAFFOLD,
      FAST_MINE,
      FAST_USE,
      BLOCK_REACH,
      KNOCKBACK,
      ORE_VISION,
      XRAY_RUSH,
      PACKET_FLOOD,
      INVALID_MOVE,
      PACKET_ORDER
   );

   /**
    * The checks that may never move a player on their own evidence, however sure
    * the level looks.
    *
    * <p>This list is the answer to the complaint that started it, and it is worth
    * being explicit about why each one is on it. Every check here judges a
    * <i>pattern</i> - a window average, an arc, a rate, a distribution - and a
    * pattern is a statement about what the last second looked like, not about where
    * the body is. Correcting a body's position on the strength of one is how an
    * anticheat gets a reputation for punishing lag, because the honest player on a
    * bad connection and the hacker both produce exactly one bad window, and the
    * honest one produces it far more often.
    *
    * <p>What these checks do instead is raise a level and an alert, and at most
    * clamp the body's velocity to the model so it stops gaining - which is a
    * correction that cannot move anybody backwards, because it is applied to the
    * velocity the body already had.
    */
   public static final List<String> NEVER_TELEPORTS = List.of(
      SPEED,
      SPEED_PREDICTION,
      SIMULATION,
      MOTION,
      FLIGHT,
      FLIGHT_PREDICTION,
      AIR_WALK,
      LONG_JUMP,
      BUNNY_HOP,
      JESUS,
      NO_SLOW,
      ANTI_HUNGER,
      ARROW_DODGE,
      TIMER,
      ORE_VISION,
      FAST_USE,
      KILLAURA,
      AIM,
      AIM_CONSTANT,
      AIM_LINEAR,
      AIM_MODULE_360,
      AUTOCLICKER
   );

   /**
    * The checks the automatic half may act on, and the whole of that list.
    *
    * <p>Every check in here is one whose verdict is a <b>physical impossibility with a model
    * behind it</b>: a body that crossed more distance than any combination of movement inputs
    * can produce, a body standing on nothing, a break that finished in fewer ticks than the
    * block has, a hit landed at a distance no arm reaches. Those are statements about where a
    * body is and what it did, and a wrong one is a bug in the model rather than a judgement
    * call.
    *
    * <p>Everything <i>not</i> in here is advisory: it raises a level, it alerts staff, it
    * appears in the ledger and on the moderation screen - and it never kicks, times out or
    * disconnects anybody. That line is drawn deliberately wide, because the failure the report
    * describes is not "the checks are wrong" but "the checks are measuring things that are
    * true of honest play": a pocket watch that stops time and moves a body, a Bedrock client
    * through Geyser whose hitboxes are translated, a lag spike that lands between a break
    * start and its stop, a client whose clock the server cannot see. Each of those is a real
    * observation about a player who is not cheating, and a rule that reads it as one is a rule
    * that will remove honest players from the game.
    *
    * <p>Restricting enforcement to a list rather than excluding checks from it is the point:
    * a check added tomorrow is advisory until somebody argues it belongs here, and the cost of
    * being wrong about an advisory check is a line in a log rather than a player who cannot
    * log in.
    */
   /**
    * What a check is called in plain English, and the line the body is removed with.
    *
    * <p>Two jobs in one record because they travel together. The label is for the moderator busy
    * reading a ledger line at a glance, and for the player's own disconnect screen - "ore-vision"
    * is a ticket number, "X-Ray" is a thing a person has an opinion about. The line is what the
    * player actually reads: a kick that says only what fired leaves somebody staring at a three-line
    * screen wondering whether they lagged, and the honest answer - you were obvious - is both more
    * useful and much harder to argue with.
    */
   public record HackLine(String label, String line) {
   }

   private static final Map<String, HackLine> HACK_LINES = Map.ofEntries(
      Map.entry(
         ORE_VISION,
         new HackLine(
            "X-Ray (seeing ore through stone)",
            "Legit, you were too obvious with that one - nobody digs straight to a diamond and calls it instinct."
         )
      ),
      Map.entry(
         XRAY_RUSH,
         new HackLine(
            "X-Ray (ore rush)",
            "Thirty diamonds in five minutes. The whole server's economy noticed before I did."
         )
      ),
      Map.entry(FLIGHT, new HackLine("Flight", "Really? Flying? That's just pathetic.")),
      Map.entry(FLIGHT_PREDICTION, new HackLine("Flight", "Really? Flying? That's just pathetic.")),
      Map.entry(SPEED, new HackLine("Speed", "You are fast. Not that fast. Walk, like the rest of us.")),
      Map.entry(SPEED_PREDICTION, new HackLine("Speed", "You are fast. Not that fast. Walk, like the rest of us.")),
      Map.entry(SIMULATION, new HackLine("Speed (momentum)", "You gained speed out of nothing. That is not walking, that is cheating with extra steps.")),
      Map.entry(TIMER, new HackLine("Timer", "Your clock runs faster than the server's. The server has the clock.")),
      Map.entry(MOTION, new HackLine("Lift / jump", "Up is not a direction you can just decide to go.")),
      Map.entry(REACH, new HackLine("Reach", "Your arm is not that long, and I measured it twice.")),
      Map.entry(BLOCK_REACH, new HackLine("Reach (placing)", "That block went down two blocks away from your hand.")),
      Map.entry(KILLAURA, new HackLine("Kill aura", "You hit things behind you. Cool. Do it with the mouse next time.")),
      Map.entry(AIM, new HackLine("Aimbot", "Your crosshair has not missed a moving body all week. Nobody is that good.")),
      Map.entry(AIM_CONSTANT, new HackLine("Aimbot", "Your crosshair has not missed a moving body all week. Nobody is that good.")),
      Map.entry(AIM_LINEAR, new HackLine("Aimbot", "Your crosshair has not missed a moving body all week. Nobody is that good.")),
      Map.entry(AIM_MODULE_360, new HackLine("Aimbot (360)", "Hitting what is behind you is not a skill, it is a setting.")),
      Map.entry(AUTOCLICKER, new HackLine("Auto-clicker", "Your mouse has been clicking at exactly the same interval for ten minutes. Mice get tired.")),
      Map.entry(FAST_MINE, new HackLine("Fast break / Nuker", "That block had a health bar, not a light switch.")),
      Map.entry(SCAFFOLD, new HackLine("Scaffold", "You built a tower without ever looking down.")),
      Map.entry(JESUS, new HackLine("Water walk", "The water would like a word.")),
      Map.entry(LONG_JUMP, new HackLine("Long jump", "One jump, no run-up, eleven blocks. Show me your keyboard.")),
      Map.entry(AIR_WALK, new HackLine("Air walk", "You walked on nothing. Gravity files a complaint.")),
      Map.entry(GROUND_SPOOF, new HackLine("Ground spoof", "You claimed a floor that is not there. Gravity files a complaint.")),
      Map.entry(NO_FALL, new HackLine("No fall damage", "That was a thirty-block drop and you are still on your feet.")),
      Map.entry(FAST_CLIMB, new HackLine("Fast climb", "Ladders do not go up that fast. Ask a ladder.")),
      Map.entry(FAST_USE, new HackLine("Fast use", "You finished that before you started it.")),
      Map.entry(ANTI_HUNGER, new HackLine("Anti-hunger", "Sprinting all day on an empty stomach. Pick one lie.")),
      Map.entry(CRITICAL_GROUND, new HackLine("Critical-hit spoof", "A critical you never jumped for.")),
      Map.entry(ARROW_DODGE, new HackLine("Projectile dodge", "Nice dodge. Shame about the fifty milliseconds.")),
      Map.entry(PACKET_FLOOD, new HackLine("Packet flood", "Your client is talking louder than the game it is playing.")),
      Map.entry(INVALID_MOVE, new HackLine("Invalid movement", "Vanilla's client cannot produce that, and neither can you.")),
      Map.entry(PACKET_ORDER, new HackLine("Packet order", "Your client contradicted itself, and then contradicted the server.")),
      Map.entry(CLIENT_TICK, new HackLine("Client tick speed", "Your client is ticking faster than the game it is playing.")),
      Map.entry(NO_SLOW, new HackLine("No slow-down", "That item is supposed to slow you down. It does, for everybody else.")),
      Map.entry(KNOCKBACK, new HackLine("Knockback", "Knockback is not a suggestion."))
   );

   /** The name a check is reported by - a person's name for it, not its key in the ledger. */
   public static String hackLabel(String check) {
      HackLine found = check == null ? null : HACK_LINES.get(check);
      return found != null ? found.label() : (check == null ? "suspicious behaviour" : check.replace('-', ' '));
   }

   /** The line a body is removed with for a check - see {@link HackLine}. */
   public static String hackLine(String check) {
      HackLine found = check == null ? null : HACK_LINES.get(check);
      return found != null
         ? found.line()
         : "Something about that did not add up, and it kept not adding up.";
   }

   public static final List<String> AUTO_ENFORCE = List.of(
      SPEED,
      SPEED_PREDICTION,
      SIMULATION,
      MOTION,
      FLIGHT,
      FLIGHT_PREDICTION,
      AIR_WALK,
      GROUND_SPOOF,
      LONG_JUMP,
      JESUS,
      NO_FALL,
      SCAFFOLD,
      FAST_MINE,
      BLOCK_REACH
   );

   /*
    * Why ore-vision is <b>not</b> in that list, having been in it.
    *
    * <p>The list's contract is that everything in it is a physical impossibility with a model
    * behind it - a body that crossed more distance than any combination of inputs produces, a
    * body standing on nothing, a break that finished in fewer ticks than the block has. Ore
    * vision is not that. It is three statistics read off a mining session: the share of broken
    * blocks that were valuable, how few ordinary blocks sat between two finds, and how many
    * ores came out of stone nobody had opened. Every one of them is a thing an <b>honest</b>
    * player produces - strip-mining a seam toward a vein is a straight line, a lucky deep dive
    * is a run of finds with almost nothing in between, and a mine that is mostly ore is what a
    * branch mine at diamond depth looks like. So the check keeps its level, its alert and its
    * ledger entry, and a person decides; it no longer carries a disconnect. It is the one entry
    * in that list whose evidence is a judgement about a player's habits rather than a statement
    * about what a body did, and the two do not belong behind the same switch.
    */

   /**
    * Checks a Bedrock player, arriving through Geyser, cannot be measured on.
    *
    * <p>Geyser is not a Java client. It translates Bedrock's own protocol into the server's,
    * which changes the three things these checks are built on: Bedrock hitboxes are not Java
    * hitboxes (so a swing that meets the body on the player's screen can miss the box the
    * server has), the proxy batches and re-orders packets rather than forwarding them (so
    * packet timing and rates are the proxy's, not the player's), and Bedrock's own movement
    * for vehicles, climbing and swimming is converted rather than reproduced. A finding from
    * any of them is a statement about the translation layer.
    *
    * <p>Reading the platform instead of trusting a permission is deliberate: it is asked of
    * Geyser's own API, so it does not depend on anybody remembering to grant a bypass, and it
    * is false for a Java client whose owner has claimed otherwise. The <b>physical movement
    * model is on this list too</b> - Bedrock's own movement (climbing, swimming, vehicles, the
    * way it resolves a step) is converted rather than reproduced, so a flight arc, a speed
    * window and a jump the server never watched are statements about the translation layer and
    * not about the player. It used to judge a Bedrock body exactly as it judged anyone else,
    * and the field report was a Bedrock player read as flying and speed-hacking for walking
    * around. What is still judged is what the platform does <i>not</i> change: ore vision,
    * scaffold, fast-mine, reach-by-breaking and the rest of the block-and-combat reasoning.
    */
   public static final List<String> GEYSER_BLIND = List.of(
      INVALID_MOVE,
      HITBOX,
      REACH,
      AIM,
      AIM_CONSTANT,
      AIM_LINEAR,
      AIM_MODULE_360,
      AUTOCLICKER,
      FAST_SWING,
      KILLAURA,
      KILLAURA_6H,
      TIMER,
      CLIENT_TICK,
      PACKET_FLOOD,
      PACKET_ORDER,
      KNOCKBACK,
      // The physical movement model. Geyser translates Bedrock's movement rather than
      // reproducing it, so every one of these is a measurement of the proxy.
      SPEED,
      SPEED_PREDICTION,
      SIMULATION,
      MOTION,
      FLIGHT,
      FLIGHT_PREDICTION,
      AIR_WALK,
      GROUND_SPOOF,
      LONG_JUMP,
      BUNNY_HOP,
      JESUS,
      NO_FALL,
      FAST_CLIMB
   );

   /**
    * Consecutive hits the aim ray has to disagree with before it is a finding.
    *
    * <p>Was one, and one is the wrong number for a check that has no model behind it: the
    * client's ray leaves against the box as of the packet it last received, and the server's
    * against the box now, so a target that stepped, a shove that landed, a teleport that a
    * scripted ability wrote and a stopped clock all put the two boxes in different places for
    * one hit. The pocket watch is the report that made this a number: it pins a body and moves
    * another, and every hit landed inside it missed by exactly the distance its own ability had
    * changed. A client aiming at a grown box misses every time, so a repeat is free - and a
    * pattern of two is still nothing like what an honest client produces.
    */
   private static final int HITBOX_MISSES = 2;

   // ---------------------------------------------------------------- tunables
   /** How much further than vanilla's 3.0 blocks a hit may land before it is
    *  refused. Vanilla's own server tolerates range + 3.0 (six blocks), which is
    *  why reach hacks survive it; this is the strict side of that. */
   private static final double REACH_MARGIN = 1.0;
   /**
    * A swing more than this far off the target's centre counts toward killaura.
    *
    * <p>Was seventy-five, which is most of a right angle and let a client swing
    * at anything in front of it. Fifty is still far outside anything a player
    * produces on purpose - a strafing player swinging at the body in front of
    * them is within twenty degrees - and it is inside the arc where the only
    * honest explanation is that the client is not using the crosshair at all.
    * The score still needs corroboration, so this does not convict on one swing.
    */
   private static final double AURA_ANGLE = 50.0;
   /** ...and this far off is worth two points on its own. */
   private static final double AURA_ANGLE_HARD = 85.0;
   /**
    * An off-target swing whose distance was also past the attacker's own reach.
    *
    * <p>The intersection, and the reason {@link #HITBOX} is a separate name: an
    * attack that was both out of arm's length <i>and</i> off the aim is not a reach
    * hack (which aims correctly) or a killaura (which stays near the body), it is a
    * client that had a bigger box to work with than the server gave it.
    */
   private static final double HITBOX_ANGLE = 55.0;
   /**
    * Swings at one entity inside a single tick before it is not a rhythm.
    *
    * <p>Three rather than two. A hand sends one attack per click and a click is a tick long,
    * which is why more than one is interesting at all - but the packets that carry those
    * clicks are delivered in bunches whenever the server's own tick runs late, and a bunch of
    * two is what an ordinary player spamming a sword produces on a hitchy server. The number
    * a client cannot reach by accident is the one this uses.
    */
   private static final int SWING_BURST = 3;
   /**
    * How many bursts inside {@link #SWING_BURST_WINDOW} before it is a finding.
    *
    * <p>This is the lag/behaviour split, and it is the same idea the movement checks use: a
    * client that ignores its cooldown re-sends swings <i>every</i> tick, so its bursts keep
    * coming, while a late tick produces one bunch and is then quiet. One burst is a delivery;
    * three inside two seconds is a weapon that no longer has a cooldown.
    */
   private static final int SWING_BURSTS = 3;
   /** The window the bursts above are counted over, in ticks. */
   private static final long SWING_BURST_WINDOW = 40L;
   /**
    * Below this server TPS no swing burst is judged at all.
    *
    * <p>The count is a count of deliveries, and a struggling server delivers a whole second of
    * a client's clicks in one tick. Standing the check down under load is not a hole - nothing
     * is gained by timing a cooldown bypass to a lag spike, because the server is already too
    * late to act on it - and it is the difference between an anticheat that blames a player
    * for the machine and one that does not.
    */
   private static final double SWING_MIN_TPS = 19.0;
   /** Distinct targets hit by swings inside one tick: the 6H signature, and a
    *  count no mouse produces. */
   private static final int MULTI_TARGET_BURST = 2;
   /** Killaura score at which the swing is refused. Decays every tick. */
   private static final double AURA_LIMIT = 10.0;
   private static final double AURA_DECAY_PER_TICK = 0.02;
   /** Swings inside this window that change target count double. Four ticks is
    *  200ms: a person can decide to hit a second mob in that time, but not acquire
    *  and swing at it, so only a client does it. */
   private static final long AURA_SWITCH_TICKS = 3L;
   /**
    * Unexplained single-tick steps before one of them is a finding.
    *
    * <p>Two, and the reason is the report this answers: "it keeps saying invalid move" while the
    * player was doing nothing unusual. One step past the per-tick limit has a dozen innocent
    * causes - a lag spike, a shove whose packet the module did not see, another mod moving the
    * body, a dismount - and every one of them produces exactly one. A client that is really
    * teleporting produces the second within the window, so the repetition costs nothing except
    * the false positive it removes.
    */
   private static final int UNEXPLAINED_STEP_CORROBORATION = 2;
   /** Consecutive ticks over the speed allowance before it is a violation. */
   private static final int SPEED_TICKS = 12;
   /**
    * Below this server TPS neither the speed window nor a break's timing is judged at all.
    *
    * <p>The same line {@link MovementPhysics#MAX_TICK_SCALE} is drawn at, and drawn here as well
    * on purpose: the scale corrects a sample that carries two of a client's ticks, and past that
    * there is no scale to apply, only a server that is not delivering the game. Nothing is gained
    * by timing a speed module, or a nuker, to a collapse - the server is already too late to act
    * on either - and the report this answers was an honest player on a struggling server being
    * flagged for moving exactly as fast as the tick it was measured over allowed them to.
    */
   private static final double MIN_READABLE_TPS = 10.0;
   /**
    * How high off the floor a rising body may be and still be read as having landed.
    *
    * <p>This is the second witness for the same landing the feet probe cannot see. A player who
    * holds jump and sprints leaves the ground again on the very tick the body touches down, so the
    * position the client sends for that tick is already a jump's worth of altitude above the
    * surface - and a probe that asks "are the feet against a collision shape right now" answers no
    * on every tick of the whole run. The world is still the witness in the other direction: a body
    * rising, after an airborne stretch that had already fallen, less than a jump off something solid
    * is a body that came down and jumped again. Three quarters of a block is the jump impulse plus
    * the packet's own rounding, and it is far below the altitude a body actually flying holds -
    * which is why the plain hover a few blocks up is untouched by this.
    */
   private static final double HOP_LANDING_HEIGHT = 0.75;
   /** Airborne ticks before hovering counts as flight. */
   private static final int FLIGHT_TICKS = 40;
   /** ...of those, how many may not lose height. */
   private static final int FLIGHT_NO_FALL_TICKS = 30;
   /** Ticks of "standing" on nothing before that counts as air-walking. */
   private static final int FAKE_GROUND_TICKS = 12;
   /**
    * How far a body may drift vertically across a whole airborne run and still be read as
    * hovering.
    *
    * <p>The second witness for the hover, and the one that follows from what a hover <i>is</i>: a
    * body being held up goes nowhere. The tick counts alone cannot tell that apart from a run of
    * sprint-jumps across ground that keeps stepping down, because a landing the server's samples
    * miss between two hops never closes the arc - and the body is descending the whole time, which
    * is exactly what a hover is not. A block and a half is well above the drift of a server that
    * reads a landing a tick late and well below the descent of a run down even one step.
    */
   private static final double HOVER_DRIFT = 1.5;
   /**
    * How long after a glide the body is still read as the wings' work rather than the
    * player's feet.
    *
    * <p>A glide does not begin and end on the ticks the server hears about it. The client
    * is the one applying elytra physics, and the flag that says "gliding" is set by a
    * packet that lands a tick either side of the movement it belongs to - the leap off a
    * ledge that starts one, the crash into a wall that ends one, the rocket fired on the
    * tick the wings open. Every one of those ticks used to be judged as if the body were
    * simply too fast and too high, which is why an elytra came back as "flight and speed".
    * Eight ticks is the width of that boundary and nothing more.
    */
   private static final long GLIDE_TAIL_TICKS = 8L;
   /**
    * How long a client's request to glide stays honourable.
    *
    * <p>Vanilla answers the request with {@code tryToStartFallFlying()}, and if the chest
    * slot has not caught up with the wings yet it answers by <i>stopping</i> the glide - and
    * the client, which is already gliding, never asks again. So a player who equips an
    * elytra and jumps climbs inside one tick flies for as long as they like while the
    * server believes they never asked. Half a second is enough for a slot to catch up and
    * short enough that a request which never becomes honourable is forgotten.
    */
   private static final long GLIDE_CLAIM_TICKS = 10L;
   /**
    * An airborne arc has to last this long before its length means anything.
    *
    * <p>Below it there is not enough flight to measure: a step off a block is six
    * ticks and three blocks, and a check that judges that is judging nothing.
    */
   private static final int LONGJUMP_MIN_TICKS = 10;
   /**
    * How long a descent has to run before an ascent after it is a second jump.
    *
    * <p>This is the whole false-positive defence on the check and it is deliberately
    * short: a body that has fallen for even three ticks and then rises without touching
    * anything has been jumped again, because vanilla applies a jump only from the ground.
    * Three is enough to clear the one honest way to gain height mid-air (clipping a
    * block and being stepped up onto it, which happens on the boundary tick).
    */
   private static final int BUNNYHOP_MIN_DESCENT = 3;
   /**
    * Re-jumps needed inside this window before it is a bunny-hop macro.
    *
    * <p>One is already impossible in vanilla, and one is what a server-side shove or a
    * modded launch looks like too. Two inside three seconds is not something a sequence
    * of accidents produces, and it is the shape an auto-jump actually has.
    */
   private static final int BUNNYHOP_EVENTS = 2;
   private static final long BUNNYHOP_WINDOW = 60L;
   /** Ticks of walking across a water surface before it is Jesus. */
   private static final int JESUS_TICKS = 12;
   /** ...during which height may drift by no more than this. A swimmer does not. */
   private static final double JESUS_DRIFT = 0.03;
   /** ...and it counts only while actually moving: floating is a different check. */
   private static final double JESUS_STEP = 0.02;
   /** A one-tick jump this large is a teleport, not movement to judge. */
   private static final double TELEPORT_DISTANCE = 8.0;
   private static final long TELEPORT_GRACE_TICKS = 20L;
   /**
    * Ticks between two reports of the same unexplained step.
    *
    * <p>The step itself is evidence, not a violation: a server teleport, a dimension
    * change and this mod's own scripted moves all produce one, and each of them is
    * recognised above. What is left is a client that crossed the threshold without the
    * server writing it there, and one report per two seconds is enough to put it on the
    * record without a moving client generating a message every tick.
    */
   private static final long UNEXPLAINED_STEP_COOLDOWN = 40L;
   /**
    * Consecutive ticks of <i>server</i>-driven movement before that itself is the
    * finding.
    *
    * <p>This closes a loop that made the movement checks unable to see the thing they
    * exist to catch. Every correction the module makes - and every correction vanilla
    * makes when it refuses an impossible packet - is a teleport, and a teleport stands
    * the checks down for two seconds. So against a client that is being corrected
    * constantly, the engine spent its whole time stood down: the hack kept the
    * anticheat blind by being detected. Six seconds of continuous server-driven
    * movement is the signature of that, and nothing honest produces it. A teleport, a
    * shove, an elevator and a duel spawn are all moments; elytra, boats, water,
    * levitation and ladders are physics exemptions and are not counted here.
    *
    * <p>Twelve seconds, deliberately far above the longest scripted carry in this mod, and
    * a single occurrence is only <i>recorded</i>: one finding is worth a level below the
    * staff alert bar, so it takes two separate windows of it - a minute apart at the
    * documented cooldown - before anybody is told. The false-positive budget here is spent
    * on the honest side, because the honest side is where a boss's scripted carry lives.
    */
   private static final int SERVER_GRACE_ABUSE_TICKS = 240;
   /**
    * How many of those long stretches must have ended in an actual refusal before it is a finding.
    *
    * <p>This is the half of the rule that was missing, and it is the whole of the false positive.
    * The check read "the server has been repositioning this body for twelve seconds without a
    * break", which is also an exact description of a boss that streams motion at somebody: a
    * tornado pulling every tick, a hurricane, a launched body whose tracker keeps sending the
    * velocity it was given. Every one of those refreshes the stand-down window, so the run counter
     * climbed to its ceiling on a player who was doing nothing but being thrown around - and what
    * came back was "it says invalid move while I am playing normally".
    *
    * <p>The sentence the finding prints has always been the right test - <i>"movement is being
    * refused as fast as it arrives"</i> - so it now requires that to be true. A server that is
    * moving a body is not abuse; a server that keeps having to <b>refuse</b> one is, because a
    * refusal means the client claimed a position the server would not take, over and over, for
    * twelve seconds. A boss streaming motion produces none of them: it is the server's own motion,
    * so the client agrees with every tick of it.
    */
   private static final int SERVER_GRACE_REFUSALS = 10;
   /** Blocks placed directly under a falling player, without looking down, before
    *  it is scaffold. */
   private static final int SCAFFOLD_LIMIT = 3;
   private static final long SCAFFOLD_WINDOW = 20L;
   /** Pitch at or below this is "looking down" - enough to aim at your own feet. */
   private static final float LOOK_DOWN_PITCH = -55.0F;
   /**
    * How close to the player's own column a placement has to be to count as
    * "under their feet" at all: 0.8 blocks, squared.
    *
    * <p>This is the single most important number in the scaffold check, and it is
    * 0.8 rather than the 1.5 it used to be for one reason: a bridge step is placed
    * <i>ahead of</i> the column, one or two blocks out, so narrowing this is what
    * stops the check from following every player who bridges. Everything that is
    * left inside this radius really is beneath the player.
    */
   private static final double SCAFFOLD_COLUMN_SQR = 0.64;
   /** Blocks per tick of climb that make a placement under your own feet a tower. */
   private static final double SCAFFOLD_RISE = 0.02;
   /**
    * ...and of descent that make it a fall being caught rather than a bridge step.
    *
    * <p>0.85 is above the whole of a jump arc: a jump leaves the ground at 0.42 a
    * tick, gravity subtracts about 0.08 each tick, and the descent at the end of
    * the longest possible jump is still only about half this. A real fall passes it
    * within a dozen ticks and keeps going. So this line separates "stepped off a
    * block" - which is bridging - from "falling" - which is what a scaffold client
    * is rebuilding its way out of.
    */
   private static final double SCAFFOLD_FALL = 0.85;
   /** A break must be at least this many ticks in the estimate before a short one
    *  means anything: instabreak blocks are legitimately broken in one tick. */
   private static final int FAST_MINE_MIN_ESTIMATE = 6;
   /**
    * How long an opened break keeps its start tick before it is dropped.
    *
    * <p>A break that is never finished - the player switched to another block, somebody else
    * took it, it was blown up - leaves its start behind, and a start that is never consumed is
    * a number the next break at that position would be measured from. Half a minute is longer
    * than any honest break of any block in the game, and short enough that an afternoon of
    * ordinary mining cannot collect enough of them to be read as a flood.
    */
   private static final long BREAK_START_TTL = 600L;
   /**
    * Short breaks this close together before one of them is refused, and how many.
    *
    * <p>The measurement is the difference between two packets, so a single short one has a long
    * tail of innocent causes: a spike between the two, a tool swapped mid-break, a block that
    * changed under the client, an estimate the server's own attributes disagree with by a tick
    * or two, two breaks chained back to back. One of those must never cost a player a block. A
    * nuker produces the second within a tick, so waiting for the repetition costs it nothing
    * and is the difference between refusing a cheat and refusing a lag spike. The window is two
    * seconds: a nuker never escapes it, and two isolated odd breaks minutes apart are not a
    * pattern.
    */
   private static final int FAST_MINE_CORROBORATION = 2;
   private static final long FAST_MINE_CORROBORATION_TICKS = 40L;
   /** Buried ores broken before ore vision is reported. */
   private static final int ORE_VISION_MIN = 3;
   /** Blocks a session must cut before an ore-yield ratio means anything: below
    *  this a lucky pair of seams reads as a percentage. */
   private static final int ORE_YIELD_MIN_BLOCKS = 40;
   /** Valuable ores as a percentage of everything broken that is x-ray, not
    *  mining: a real miner digs a corridor to find a seam, so the stone dwarfs
    *  the ore. This is deliberately far above honest play. */
   private static final int ORE_YIELD_PERCENT = 12;
   /** Valuable ores found back to back with this few plain blocks between them -
    *  a miner following a vein, or a client following the glowing outlines. */
   private static final int ORE_BEE_LINE_MIN = 5;
   private static final int ORE_BEE_LINE_GAP = 2;
   /** Ticks of no mining before the ore-yield window is forgotten. */
   private static final long ORE_WINDOW_IDLE_TICKS = 1200L;
   /** A hit landed with a solid block between the eyes and the body. */
   private static final double WALL_HIT_MIN_DISTANCE = 1.5;
   /** Ticks a knockback is expected to play out over. */
   private static final long KNOCKBACK_TICKS = 8L;
   // The enforcement dials (alert and enforce levels, decay, cooldowns) are NOT here:
   // they live in {@link AntiCheatPolicy}, which is the file of numbers an admin may
   // tune and persist. What stays below is physics - what is possible - which is
   // pinned by the self-test and deliberately not reachable from a command.
   /** Blocks of continuous descent before a landing with no damage is suspicious. */
   private static final double NO_FALL_DROP = 7.0;
   /** ...and how many ticks of descending it takes to count as a fall at all. */
   private static final int NO_FALL_TICKS = 15;
   /** Blocks above the nearest support a claimed ground has to sit at to be a lie
    *  rather than a landing, which is what makes one claim enough to hide a fall. */
   private static final double NO_FALL_CLAIM_HEIGHT = 2.0;
   /** Blocks a projectile has to be within to be worth reacting to at all. */
   private static final double DODGE_RANGE = 4.0;
   /**
    * Ticks before impact at which the projectile is unavoidable.
    *
    * <p>Three ticks is a block and a half at arrow speed. Inside it, where the body ends up
    * is decided, and walking out of it was decided earlier - which is what makes the turn at
    * that moment a statement about what the client knew rather than about where it went.
    */
   private static final double DODGE_TICKS = 3.0;
   /** Half-width of the body, plus a hand's width, for the projectile to be aimed at it. */
   private static final double DODGE_CORRIDOR = 0.85;
   /** Dodges inside this window before the coincidence stops being one. */
   private static final int DODGE_EVENTS = 3;
   private static final long DODGE_WINDOW = 400L;
   /** How far past vanilla's block range a placement may still land. */
   private static final double BLOCK_REACH_MARGIN = 1.0;
   /** The rolling window ore vision is judged over, in blocks broken. */
   private static final int ORE_WINDOW_BLOCKS = 100;
   /**
    * Diamonds inside the rush window before staff hear about it.
    *
    * <p>Thirty is the number that was asked for, and the rate it describes is the reason it works:
    * five minutes is six a minute, sustained, which is roughly ten times what a good player reaches
    * in a rich session - so the figure is generous to honest play by a wide margin, and being
    * generous only ever costs a missed alert. Counted in <b>blocks broken</b>, not items picked up:
    * Fortune multiplies the drop and would make an efficient miner's counter say ten where their
    * day said three.
    */
   public static final int XRAY_DIAMONDS = 30;
   /**
    * Ancient debris inside the same window before staff hear about it.
    *
    * <p>Ten, as asked. Worth being honest about this one: a bed-bombing run in the Nether is fast,
    * and a player who is good at it can approach this figure legitimately. That is exactly why the
    * check only alerts - a person reads the finding and can see what happened, and a number that
    * guesses wrong on a judgement call has no business removing anybody from the game.
    */
   public static final int XRAY_DEBRIS = 10;
   /**
    * The window a rush has to happen inside: five minutes.
    *
    * <p>This is the whole of "and not if it took them a long time". A window rather than a session
    * total, so thirty diamonds over an afternoon is thirty diamonds over an afternoon and nothing
    * is reported; the same thirty inside five minutes is a body that did not have to look for them.
    */
   public static final long XRAY_WINDOW_TICKS = 6000L;
   /** Valuable ores inside that window before it is x-ray rather than a seam. */
   private static final int ORE_WINDOW_VALUABLE = 9;
   /** Ticks of movement one speed window covers. One second: long enough that a
    *  client which lagged out and delivered four ticks at once has the same
    *  average as one that did not, and short enough that a fast fight is judged
    *  as it happens. */
   private static final int SPEED_WINDOW = 20;
   /** Consecutive rises past the jump impulse before the vertical check reports.
    *  One is a client that resolved a step differently; two is a bounce; three is
    *  a body being driven upward. */
   private static final int MOTION_TICKS = 3;
   /** Airborne ticks a flight-prediction arc needs before its shape means
    *  anything. A jump is twelve ticks; a fall from a ledge is longer and
    *  follows the same curve, which is exactly why this is a comparison against
    *  the predicted path rather than a count. */
   private static final int PREDICTION_MIN_TICKS = 12;
   /**
    * How far above the predicted fall path an arc may ride before it is flight.
    *
    * <p>In blocks, and it is generous on purpose. The prediction integrates the
    * body's own reported vertical steps under vanilla's gravity, so it tracks a
    * jump's whole arc to within a fraction of a block - but a client whose packets
    * arrive in a burst delivers several ticks of that arc at once, and the
    * comparison then sees a body that appears to have hovered between the packets.
    * One and a half blocks is above the whole disagreement a burst can produce and
    * well under what flying somewhere costs.
    */
   private static final double PREDICTION_DRIFT = 1.5;
   /** Blocks of downward travel inside one tick that make a ground claim a lie
    *  rather than a step. A step up is a rise; a slab is a centimetre. Six tenths
    *  of a block of descent while claiming the floor is the signature of a client
    *  reporting the flag to skip the fall. */
   private static final double SPOOF_FALL = 0.6;
   /** Ticks of item use before the no-slow comparison has a body to judge: a
    *  single tick of a bow draw is a player releasing, not a module. */
   private static final int NO_SLOW_TICKS = 5;

   /** Airborne ticks of *movement* per second above which a client's clock is
    *  outrunning the server's. Twenty ticks is 20 packets; a burst from a stalled
    *  client can deliver ten in one tick and still average under this over the
    *  window. */
   private static final double TIMER_PACKETS_PER_SECOND = 30.0;
   /** Violation level decayed per tick, per check. A hack that stops stops being
    *  punished: the score is about what a player is doing, not what they did. */
   private static final double VL_DECAY_PER_TICK = 0.02;
   /** The ceiling on one check's level, so an overnight bot cannot bank it. */
   private static final double VL_MAX = 40.0;
   /** Below this confidence a finding is written down and nothing else. */
   private static final double ALERT_CONFIDENCE = 0.55;
   /** ...and below this level it is not even written to staff. */
   private static final double ALERT_VL = 12.0;
   /** The level at which the failsafe may act, when nobody is watching. */
   private static final double ENFORCE_VL = 30.0;
   /** Between two automatic actions, so one bad minute cannot become three
    *  trips. Sixty seconds. */
   private static final long ENFORCE_COOLDOWN_TICKS = 1200L;
   /** Ticks after a correction during which nothing is judged, so a setback
    *  cannot be read as the player's own impossible movement. */
   private static final long SETBACK_COOLDOWN_TICKS = 40L;
   /** Ticks after the server itself moves a player - a teleport it sent, a
    *  velocity it pushed - during which their movement is the server's, not
    *  theirs. Two seconds: the correction arrives at the client inside a tick or
    *  two, and the client then has however long its own latency is to answer.
    *  This is the correlation section 3 asks for, done from the outbound side,
    *  where the server knows exactly what it told the client and when. */
   private static final long SERVER_MOTION_GRACE_TICKS = 40L;
   /**
    * Ticks of stand-down kept <i>after</i> the client has answered a server teleport.
    *
    * <p>The two seconds above are the right length for a window in which the client is
    * still catching up, and the wrong length for everything after it. Once a packet the
    * client sent lands where the server put it, the body is where the server put it and
    * anything it does next is its own feet - so the rest of the window is not a grace, it
    * is a hole. Half a second is what is left for the packets that were already on the
    * wire when the answer left the client, which is the only thing the tail is for.
    */
   private static final long TELEPORT_ACK_TAIL_TICKS = 10L;
   /**
    * How long a velocity this module writes to a body is remembered as its own.
    *
    * <p>Short on purpose, and paired to the thing that sends the packet. A write sets
    * {@code hurtMarked}, vanilla's entity tracker consumes that once and sends exactly
    * one {@code ClientboundSetEntityMotionPacket} to the player themself, so the mark
    * only has to survive the tick or two between the write and the tracker running.
    * Two ticks is that gap with room for a late entity tick; anything longer starts
    * swallowing shoves that were not ours.
    *
    * <p>It is also the length of time the tick loop will not count server-driven movement
    * against a player, because those two ticks are the ones in which the server is
    * reporting something this module did. See {@link #writeBody} - the mark and the
    * window are set together, in one place, so a write cannot be half-declared.
    */
   private static final long SELF_VELOCITY_TICKS = 2L;
   /**
    * How long being <i>thrown</i> stands every movement check down.
    *
    * <p>Longer than the two seconds a teleport or a plain shove gets, and for a reason that is
    * entirely about wind charges. A wind charge is a launch rather than a nudge: it puts the body
    * eight or ten blocks in the air and the arc it flies is a second and a half of climb followed by
    * a second of fall - and for all of it the body is rising with no input and then drifting off the
    * fall path a plain jump would have taken. Every one of those ticks is the shape the flight and
    * air-walk checks exist to catch, and at two seconds the grace was running out mid-arc, so the
    * descent the explosion paid for was being read as a body hovering itself across the map. Three
    * seconds covers the whole throw, the arc, and the landing.
    */
   private static final long LAUNCH_GRACE_TICKS = 60L;
   /** How far up a body has to be thrown, in blocks a tick, for a shove to read as a launch. */
   private static final double LAUNCH_VERTICAL = 0.32;
   /** Launches recognised, so a server can see the stand-down is firing for the right reason. */
   private static long launches;
   /**
    * Ticks over which repeated unexplained steps are counted as one pattern.
    *
    * <p>A single step past {@link #TELEPORT_DISTANCE} with no server write behind it is
    * an event - a lag spike that vanilla papered over, a packet nobody sent. A body that
    * does it again and again inside a few seconds is not having accidents: that is a
    * teleport module, or a client blinking between positions it was never given. The
    * first one is recorded; the repeats are worth more, up to double, which is what moves
    * a repeating blink from below the alert bar to above it in seconds instead of minutes.
    */
   private static final long UNEXPLAINED_STEP_WINDOW = 200L;
   /** Evidence lines kept per check per player, newest first. */
   private static final int EVIDENCE_LINES = 6;

   private static final Map<UUID, Track> TRACKS = new HashMap<>();
   /**
    * Glide requests the server could not answer on the tick they arrived.
    *
    * <p>Kept outside {@link #TRACKS} deliberately. Vanilla's answer to
    * {@code START_FALL_FLYING} is final: asked before the chest slot has the elytra in it,
    * it stops the glide, and the client - which is already gliding - never asks again. So
    * the request is held here and vanilla's own question is asked again for a few ticks.
    * That is a repair to the game rather than a check, which is why it runs on the server
    * tick whether or not the checks are switched on, and why it holds no per-player state
    * worth keeping: one long per player mid-request, dropped the moment it is answered or
    * expires.
    */
   private static final Map<UUID, Long> GLIDE_CLAIMS = new HashMap<>();
   /** Staff currently in watch mode, which raises the detail on their alerts. */
   private static final java.util.Set<UUID> WATCHING = new java.util.HashSet<>();
   /** Players who explicitly asked to see their own live test telemetry. */
   private static final java.util.Set<UUID> TEST_MODE = new java.util.HashSet<>();
   /** Production-path counters: these prove whether a live hook is reaching the engine. */
   /**
    * The one clock the whole engine reads.
    *
    * <p>There used to be two. The tick loop measured the movement grace against
    * {@code MinecraftServer.getTickCount()}, while everything reached from the packet
    * side - a teleport the server sent, a velocity it pushed, the damage hook - stamped
    * itself with {@code ServerLevel.getGameTime()}. Two counters that are *almost always*
    * the same are the worst kind: a difference of even one tick makes a grace window
    * self-refreshing, and a grace window that never expires is an engine that never judges
    * anybody, which is indistinguishable from an engine that finds nobody. Setting it once
    * per tick, at the tick boundary, makes the whole thing arithmetic instead of a
    * coincidence.
    */
   private static long clock;
   private static long tickCalls;
   private static long movePackets;
   private static long attackPackets;
   private static long attackEvents;
   private static long findings;
   /**
    * Teleports seen at the server's <i>own</i> teleport API, which is the half that never
    * sent a packet.
    *
    * <p>A counter rather than a comment because this number is the difference between
    * "nobody has teleported" and "the hook that recognises teleports is not in the built
    * jar", and the two produce identical ledgers. It is printed by
    * {@code /ff anticheat testmode} next to the packet counters for that reason: if
    * {@code moves} climbs and this stays at zero while a player is using {@code /tp}, the
    * hook is missing rather than idle.
    */
   private static long serverWrites;
   /**
    * Findings that reached the alert path, which is not the same number as findings.
    *
    * <p>The alert cooldown suppresses messages on purpose, and a suppressed message is
    * invisible from the outside: "the check fired and nobody was told" and "the check did
    * not fire" look identical at the console. This is the count that tells them apart.
    */
   private static long alertsSent;


   private AntiCheat() {
   }

   // ------------------------------------------------------------------ config

   /** Wired from the mod's event registration. */
   public static void register() {
      ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, amount, taken, blocked) -> {
         if (entity instanceof ServerPlayer player) {
            try {
               onDamage(player, source, taken);
            } catch (Throwable t) {
               FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat damage check failed", t);
            }
         }
      });
   }

   public static boolean enabled() {
      return ModConfig.anticheat();
   }

   public static boolean checksOps() {
      return ModConfig.anticheatOps();
   }

   /**
    * Toggles a player's private diagnostics mode. It is deliberately separate from
    * watch mode: watch is staff moderation, while this is a live proof that the
    * player's packets and server ticks are reaching the detector.
    */
   public static boolean toggleTestMode(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      UUID id = player.getUUID();
      if (!TEST_MODE.add(id)) {
         TEST_MODE.remove(id);
         return false;
      }
      return true;
   }

   public static boolean testMode(ServerPlayer player) {
      return player != null && TEST_MODE.contains(player.getUUID());
   }

   /**
    * What the fall tracker currently believes, for the readouts and the self-test.
    *
    * <p>"No fall was reported" has several causes - the fall was never seen, the landing
    * was cushioned, or the client's own ground claim was never recorded - and they need
    * different fixes. This is the line that says which one it was.
    */
   public static String fallReadout(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      if (track == null) {
         return "fall: no record";
      }
      return "fall: active=" + track.fallActive
         + " ticks=" + track.fallTicks
         + " startY=" + round(track.fallStartY)
         + " claims=" + track.fallClaims
         + " claimHeight=" + round(track.fallClaimAbove);
   }

   /**
    * Why this body would be skipped, named - or null when it would be judged.
    *
    * <p>{@code /ff anticheat why} already prints the gates one at a time, but the ordinary
    * readouts did not, and the two exemptions do not look the same from the outside.
    * "Operators are exempt" is on the screen; an <b>economy admin</b> is exempt through the
    * same code path and was mentioned nowhere, so staff who had granted themselves economy
    * permissions read "Operators: checked", moved with a speed client and got nothing at all
    * - which is indistinguishable from a detector that does not work, and was reported as
    * exactly that. This is the line that answers it on the screen an admin already looks at.
    */
   public static String exemptionReason(ServerPlayer player) {
      if (player == null) {
         return "no player";
      }
      if (!enabled()) {
         return "the anticheat is off (/ff anticheat on)";
      }
      if (checksOps()) {
         return null;
      }
      if (player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
         return "operators are exempt (/ff anticheat op on to check them too)";
      }
      if (PermissionManager.isEconomyAdmin(player.getUUID())) {
         return "economy staff are exempt (/ff anticheat op on to check them too)";
      }
      return null;
   }

   /** A one-line live heartbeat for `/ff anticheat testmode`. */
   public static String testStatus(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      String skip = exemptionReason(player);
      return "&8[AC TEST] &7enabled=" + enabled()
         + " ops=" + checksOps()
         + " active=" + (skip == null)
         + (skip == null ? "" : " &cskipped: " + skip)
         + " ticks=" + tickCalls
         + " moves=" + movePackets
         + " attacks=" + attackPackets
         + "/" + attackEvents
         + " serverwrites=" + serverWrites
         + " launches=" + launches
         + " findings=" + findings
         + " samples=" + (track == null ? 0 : track.speedSamples())
         + " &8(" + (player == null ? "no player" : PacketEngine.summarize(player.getUUID())) + ")";
   }

   /** True when any check should look at this player at all. */
   public static boolean active(ServerPlayer player) {
      return enabled() && !exempt(player);
   }

   /**
    * True when this particular check should look at this player.
    *
    * <p>Per check because that is what an exemption is for: a boss ability that
    * launches a player is a reason to stand the flight check down for three
    * seconds, and no reason at all to stop looking at their reach. Asked every
    * time rather than latched, so an exemption that has run out takes effect on
    * the next tick rather than at the next restart.
    */
   public static boolean active(ServerPlayer player, String check) {
      if (!enabled() || exempt(player)) {
         return false;
      }
      if (!checkEnabled(check)) {
         return false;
      }
      return !AntiCheatStore.exempt(player.getUUID(), check);
   }

   /**
    * Whether the owner has left this particular check switched on.
    *
    * <p>Only two checks have a switch of their own, and they are the two that
    * judge a pattern rather than an event: click timing and hit precision are the
    * ones most likely to annoy a server that does not want them, so they can be
    * silenced without giving up reach, speed or the packet layer. Everything else
    * answers true, because the specification's answer to "this check is noisy" is
    * to fix the check rather than to make it optional.
    */
   public static boolean checkEnabled(String check) {
      if (AUTOCLICKER.equals(check)) {
         return ModConfig.anticheatAutoclicker();
      }
      if (AIM.equals(check)) {
         return ModConfig.anticheatAim();
      }
      return true;
   }

   /**
    * Operators are skipped. That is the whole reason {@code /ff anticheat op} has
    * to exist as its own switch: the owner of the server cannot play the game
    * without the checks flagging them, but they are also the one person who has
    * to be able to test the checks on themselves.
    */
   public static boolean exempt(ServerPlayer player) {
      if (player == null) {
         return true;
      }
      if (checksOps()) {
         return false;
      }
      return rankExempt(player);
   }

   // -------------------------------------------------------------------- tick

   public static void tick(MinecraftServer server) {
      tickCalls++;
      long now = server.getTickCount();
      // The wings, either side of the tick the server hears about them. This is the one
      // thing in this module that is a repair rather than a check - vanilla's refusal of a
      // glide request is final, and a request refused on a slow tick is a player flying for
      // the rest of the flight while the server believes they never asked - so it runs
      // whether or not the checks are switched on.
      elytraPass(server, now);
      if (!enabled()) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         try {
            observe(player, now);
         } catch (Throwable t) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat tick failed for {}", player.getName().getString(), t);
         }
      }

      if (now % 600L == 0L) {
         // A player who logged out while flagged does not keep a slot forever.
         TRACKS.keySet().removeIf(id -> {
            boolean gone = server.getPlayerList().getPlayer(id) == null;
            if (gone) {
               PacketEngine.forget(id);
               LagCompensatedHistory.forget(id);
            }
            return gone;
         });
         // Expired exemptions are dropped rather than left to grow a file, and
         // the violation history is written out on the same slow cadence so a
         // crash costs minutes of it rather than all of it.
         AntiCheatStore.sweep(System.currentTimeMillis());
         AntiCheatStore.save(server);
      }
   }

   /**
    * One player's whole detection tick, separated from the loop that walks the
    * player list.
    *
    * <p>This extraction is not cosmetic. Before it, the only way to find out
    * whether the movement pipeline actually judged anybody was to install the mod,
    * join a server, and move - which is a terrible way to test a detector, and it
    * is precisely how a whole engine ended up quietly standing down in play while
    * every property-based self-test passed. Everything a live tick does to one body
    * is now reachable as a single call, so a test can drive a real
    * {@code ServerPlayer} through the real checks, in a real world, and assert what
    * comes out.
    *
    * <p>It deliberately still owns the stand-down decisions rather than the caller:
    * {@code exempt}, the operator exemption and the test-mode heartbeat are all part
    * of "what happens to this player on this tick", and a harness that bypassed them
    * would be testing something the server never runs.
    */
   public static void observe(ServerPlayer player, long now) {
      if (player == null) {
         return;
      }
      clock = now;
      // Keep the authoritative position history on the server tick. This is
      // deliberately before movement checks so a just-arrived packet can be
      // compared with the positions that could have been visible to the client.
      LagCompensatedHistory.record(player, now);
      WorldSnapshotHistory.capture(player, now);
      Track track = track(player);
      track.auraScore = Math.max(0.0, track.auraScore - AURA_DECAY_PER_TICK);
      track.decay();
      resolveKnockback(player, track, now);
      // The baseline the knockback check measures against, sampled once a tick at the
      // same point the measurement itself is resolved. See the field for why the
      // impulse has to be a change rather than a speed.
      Vec3 motion = player.getDeltaMovement();
      track.velX = motion.x;
      track.velZ = motion.z;
      track.hasVel = true;
      if (exempt(player)) {
         // Recorded rather than silent, because "the operator exemption is on" and
         // "the checks are broken" produce the same empty ledger and only one of
         // them is a bug.
         track.silence = "exempt (operator or economy admin)";
         return;
      }
      if (!enabled()) {
         track.silence = "anticheat off";
         return;
      }
      track.silence = null;
      movement(player, track, now);
      if (TEST_MODE.contains(player.getUUID()) && now % 20L == 0L) {
         Chat.raw(player, testStatus(player));
      }
   }

   private static void movement(ServerPlayer player, Track track, long now) {
      double x = player.getX();
      double y = player.getY();
      double z = player.getZ();

      if (!track.hasLast) {
         track.setLast(x, y, z);
         track.setSafe(x, y, z);
         track.hasLast = true;
         return;
      }

      double dx = x - track.lastX;
      double dy = y - track.lastY;
      double dz = z - track.lastZ;
      double horizontal = Math.sqrt(dx * dx + dz * dz);
      track.setLast(x, y, z);
      // One judged tick. Counted here rather than derived from the world clock, because
      // the question it answers - how long has this body been clean? - has to survive a
      // relog, a restart and a server whose ticks do not have the length they claim.
      track.judgedTicks++;

      // What one of this server's ticks is worth in a client's own ticks, and the correction every
      // measurement below is read through. A server tick is fifty milliseconds of movement only at
      // twenty ticks a second; on a server that is behind, one sample carries several of the
      // client's own ticks, and a model quoted per client tick - read a sample at a time - says an
      // honest player is running, rising and mining too fast, all at once and only while the server
      // is struggling. See MovementPhysics.tickScale.
      double ticksPerSample = MovementPhysics.tickScale(PerfMonitor.tps());

      // ------------------------------------------------------- what is driving it
      // Whether the *server* is the one moving this body, taken from what the server
      // actually said - a position or velocity packet this mod watched go out - and
      // never from how far the body went. The two are different questions and the old
      // code answered the first with the second, which is how a check ends up
      // excusing exactly the movement it exists to catch.
      boolean serverMoved = now - track.teleportTick < TELEPORT_GRACE_TICKS
         || now < graceEnd(track);
      String scriptedReason = scriptedReason(player, serverMoved);
      boolean scripted = scriptedReason != null;
      track.scripted = scripted;
      track.scriptedReason = scriptedReason;
      if (scripted) {
         track.graceRun++;
      } else {
         track.graceRun = 0;
      }
      // Only the server-driven half is counted, and only when the server driving it is
      // not this module. A boat, a pool and a ladder are permanent and honest; being
      // teleported for six unbroken seconds is neither. The subtraction matters because
      // a correction this module made is itself a teleport: counting those made a player
      // being corrected accumulate a *second* finding for the correction, which is a
      // finding raised by the engine's own reply rather than by anything the player did.
      // The one stand-down this run must not count is an ability holding the body itself: a
      // stopped clock pins one where it stands and writes positions every tick, and the run
      // below - "the server has been repositioning this body for N ticks", which is an
      // INVALID_MOVE finding - was landing on players who were held still by their own trinket.
      //
      // Deliberately not "any scripted reason": a body the server keeps *teleporting* is exactly
      // what a client abusing stand-downs looks like from here, and the existing check that a
      // constant correction is itself a finding depends on this run still counting it. An ability
      // that owns the body is a fact about the world; a teleport is a claim, and a claim that
      // keeps arriving is evidence.
      boolean heldByAbility = holdsBodyByAbility(scriptedReason) || adminFlight(player);
      if (serverMoved && now >= track.correctionUntil && !heldByAbility) {
         track.serverGraceRun++;
         // A refusal that has aged out is not part of this stretch of abuse: the run is rebuilt
         // from refusals inside the same window, so a shove somebody walked out of a minute ago
         // cannot be spent on the one happening now.
         if (now - track.refusalAt > SERVER_GRACE_ABUSE_TICKS) {
            track.refusals = 0;
         }
         if (track.serverGraceRun >= SERVER_GRACE_ABUSE_TICKS && track.refusals >= SERVER_GRACE_REFUSALS) {
            track.serverGraceRun = 0;
            track.refusals = 0;
            flag(
               player,
               INVALID_MOVE,
               "the server has been repositioning this body for " + SERVER_GRACE_ABUSE_TICKS
                  + " ticks without a break, and has had to refuse its movement " + SERVER_GRACE_REFUSALS
                  + " times inside that stretch"
            );
         }
      } else {
         track.serverGraceRun = 0;
      }

      String dimension = dimensionOf(player);
      if (!dimension.equals(track.lastDimension)) {
         // A body that has just arrived in a new dimension was moved by the server,
         // packet or no packet: arriving somewhere else is not a movement claim.
         track.lastDimension = dimension;
         track.teleportTick = now;
         track.setSafe(x, y, z);
         resetForTeleport(track, now, x, y, z);
         return;
      }

      // A step this large cannot be the player's own feet - and that makes it the
      // strongest thing a client can say, not a reason to stop listening. The previous
      // version treated every one of these as a teleport and stood down, which handed
      // the largest movement hacks a free pass: a module crossing more than eight
      // blocks a tick made every tick look like a teleport, so the movement checks
      // never judged a single one of its ticks. A step is only excused when the server
      // is the author of it.
      if (horizontal > TELEPORT_DISTANCE || Math.abs(dy) > TELEPORT_DISTANCE) {
         if (!scripted) {
            track.teleports++;
            long since = now - track.lastUnservedStep;
            if (since > UNEXPLAINED_STEP_COOLDOWN) {
               track.lastUnservedStep = now;
               // The count is what turns one unexplained step into a pattern. It ages out
               // on its own window, so an honest player who hits one lag spike an hour
               // keeps producing first occurrences and never escalates.
               if (now - track.unexplainedWindow > UNEXPLAINED_STEP_WINDOW) {
                  track.unexplainedWindow = now;
                  track.unexplainedSteps = 0;
               }
               track.unexplainedSteps++;
               int repeats = track.unexplainedSteps;
               // Two in a window, not one. A single eight-block step is a lag spike, a shove the
               // module never saw the packet for, a modded dash - and the report that produced
               // this rule was "it says invalid move while I am playing normally" with no way to
               // tell which of those it was. A body that keeps arriving somewhere the server
               // never sent it produces the second one within seconds; a spike does not.
               flag(
                  player,
                  INVALID_MOVE,
                  "moved " + round(horizontal) + " blocks sideways and " + round(dy)
                     + " vertically in a single tick, with no server teleport to explain it"
                     + (repeats > 1
                        ? " (" + repeats + " of " + track.teleports
                           + " unexplained steps in the last " + (UNEXPLAINED_STEP_WINDOW / 20L)
                           + "s - a body that keeps arriving somewhere it was never sent is not lagging)"
                        : " (first one this minute - recorded, and it has to repeat before it is worth anything)"),
                  // One step is worth a fifth of a finding: it is recorded, it is in the ledger,
                  // and it cannot reach chat on its own because the alert bar is priced in tens.
                  // The repetition is what the old version was missing, and it is what the weight
                  // below buys - the report was "it says invalid move while I am playing normally"
                  // and every innocent cause of a single eight-block step (a spike, a shove whose
                  // packet the module never saw, another mod moving the body) looked identical to
                  // a client doing it on purpose, once.
                  repeats >= UNEXPLAINED_STEP_CORROBORATION ? Math.min(2.0, 1.0 + 0.5 * (repeats - 1)) : 0.2
               );
               FortuneFavorsMod.LOGGER.debug(
                  "[FF-AC] {} unexplained step {} of {} at {} blocks - weight {}",
                  player.getName().getString(), repeats, track.teleports, round(horizontal),
                  repeats >= UNEXPLAINED_STEP_CORROBORATION ? "full" : "a fifth"
               );
            }
            // A step with no server write behind it breaks the windows the same way a
            // teleport does - a speed window cannot span a discontinuity - but it is
            // not given a teleport's stand-down, because there was no teleport. That
            // asymmetry was itself a way through: a module crossing eight blocks a tick
            // bought itself a second of unjudged movement with every step, which is
            // most of what a teleport module needs. The last place the body stood on its
            // own feet is kept too, because a position the client invented is not a
            // place the module should be willing to put anybody back.
            breakWindows(track);
            return;
         }
         // The server moved this body - unless the server here was this module. A client that
         // answers a correction by reporting itself back where it was hacking from has not
         // been teleported anywhere: it has refused the correction, and throwing the speed
         // window away for it would mean the module's own corrections kept buying the client
         // fresh unjudged seconds. Only the window is kept (see
         // {@link #breakWindows(Track, boolean)}); the stand-down itself is untouched, and a
         // vanilla client - which lands where it was put - never reaches this at all.
         if (now < track.ownPositionUntil) {
            breakWindows(track, true);
            return;
         }
         resetForTeleport(track, now, x, y, z);
         return;
      }

      boolean grounded = player.onGround() && hasGroundBelow(player, y);
      // A grounded tick on a stair or a slab is not the flat-ground friction model's to judge:
      // going up a staircase moves the body up and along inside one tick, which the model reads
      // as speed gained out of nothing. See onStepLike. Asked once here so every reader agrees.
      boolean stepLike = grounded && onStepLike(player, y);
      // A body in a cobweb is not hovering and is not walking on air, whatever its vertical
      // delta says. The web slows the descent to a crawl - the same near-zero dy a hover
      // produces - so the flight window ("airborne N ticks without losing height") and the
      // prediction (which integrates vanilla's gravity against a fall the web was never going
      // to let happen) both read a body stuck in a net as a body holding altitude. The web is
      // the world holding the body, which is exactly what the airborne counters exist to be
      // told, so it counts as being on foot here. See inWeb.
      boolean webbed = inWeb(player);
      if (grounded && !scripted) {
         track.setSafe(x, y, z);
      }
      // A landing the client's own flag did not report. Holding jump while sprinting
      // makes the body leave the ground again on the very tick it touches down, and a
      // client sends that tick as airborne - so vanilla's flag alone leaves the airborne
      // state open across a whole run of hops, and every check that measures an arc reads
      // the run as one continuous flight. The world is the second witness: if the feet are
      // against a collision shape, the body landed, whatever the packet said. It is used
      // only to close an arc, never to excuse one - a body that is really hovering is not
      // resting on anything, so this cannot hide the thing the checks exist to catch.
      boolean touchdown = !scripted && feetOnSomething(player, y);
      // ...and the landing a hop reports mid-flight, which the narrow probe cannot see because the
      // jump has already carried the body off the floor. See HOP_LANDING_HEIGHT: rising, after a
      // fall the arc already took, and less than a jump above something solid.
      boolean hopLanding = !scripted
         && track.airArcFell
         && dy > 0.0
         && aboveSupport(player, y, HOP_LANDING_HEIGHT);
      boolean onFoot = grounded || touchdown || hopLanding || webbed;

      // ------------------------------------------------------------------ flight
      boolean hovering = false;
      boolean fakeGround = false;
      if (scripted || onFoot) {
         track.airTicks = 0;
         track.noFallTicks = 0;
         track.airArcFell = false;
      } else {
         if (track.airTicks == 0) {
            track.airStartY = y;
         }
         track.airTicks++;
         if (dy >= -0.02) {
            track.noFallTicks++;
         } else {
            track.noFallTicks = 0;
            // The arc has come down. It is what separates a landing from a body that was only ever
            // going up, and so what a hop is allowed to close on.
            track.airArcFell = true;
         }
         // Every threshold here is the model's own number, in the client's ticks, and the server's
         // samples are not client ticks - see MovementPhysics.tickScale. A server behind by half
         // needs twice as many of its own ticks of air before the same claim can be made. A hover
         // is indefinite, so waiting longer to name one costs nothing; what the extra room is for
         // is a jump whose landing the sample between two hops simply skipped.
         int flightTicks = (int)Math.ceil(FLIGHT_TICKS * ticksPerSample);
         int noFallTicks = (int)Math.ceil(FLIGHT_NO_FALL_TICKS * ticksPerSample);
         int fakeGroundTicks = (int)Math.ceil(FAKE_GROUND_TICKS * ticksPerSample);
         // ...and the run has to have gone nowhere at all, which is the half of the claim the tick
         // counts cannot make on their own. See HOVER_DRIFT.
         boolean wentNowhere = Math.abs(y - track.airStartY) <= HOVER_DRIFT;
         hovering = track.airTicks >= flightTicks && track.noFallTicks >= noFallTicks && wentNowhere;
         fakeGround = player.onGround() && track.airTicks >= fakeGroundTicks;
      }

      // ----------------------------------------------------------------- no fall
      // A no-fall hack never changes the descent; it changes whether the server
      // ever hears about the landing. So the descent is measured here - from where
      // the drop started to where the player says it ended - and the cost is read
      // off the landing tick: a fall of this size that leaves no mark at all did
      // not happen, or the client lied about when it stopped.
      if (scripted) {
         track.fallActive = false;
         track.fallTicks = 0;
         track.fallClaims = 0;
      } else if (grounded) {
         if (track.fallActive && track.fallTicks >= NO_FALL_TICKS) {
            double drop = track.fallStartY - y;
            if (drop >= NO_FALL_DROP
               && player.level() instanceof ServerLevel fallLevel
               && !landingCushioned(player, fallLevel)) {
               // Two independent ways for a fall of this size to have been unpaid, and
               // they do not overlap. The first is that nothing hurt them at all, which
               // is what a landing nobody was charged for looks like. The second is the
               // one that used to be invisible: a client that reports itself standing on
               // the way down, blocks above anything the server can see, has deleted the
               // fall from the server's bookkeeping - and it can do that while being shot
               // at, which is the case the landing test alone could never see, because a
               // hurt marker of any origin hides it. A claim that high is not a landing,
               // a desync or a step; it is the flag being used for its purpose.
               boolean unpaid = player.hurtTime == 0;
               boolean hidden = track.fallClaims > 0;
               if (unpaid || hidden) {
                  flag(
                     player,
                     NO_FALL,
                     hidden
                        ? "fell " + round(drop) + " blocks over " + track.fallTicks
                           + " ticks and claimed the ground " + track.fallClaims + " times on the way, up to "
                           + round(track.fallClaimAbove) + " blocks above anything solid - the fall was deleted "
                           + "before the server could charge for it"
                        : "fell " + round(drop) + " blocks over " + track.fallTicks + " ticks and took nothing from it"
                  );
               }
            }
         }
         track.fallActive = false;
         track.fallTicks = 0;
         track.fallClaims = 0;
      } else {
         if (!track.fallActive) {
            track.fallActive = true;
            track.fallStartY = y;
            track.fallTicks = 0;
            track.fallClaims = 0;
            track.fallClaimAbove = 0.0;
         }
         track.fallTicks++;
         // Claiming the floor mid-descent is not judged here - that is the air-walk
         // check's job when it is a pattern. What is recorded here is the *height* of the
         // claim, because a claim made two or more blocks above the nearest solid thing
         // has exactly one purpose, and the fall it is attached to is where the evidence
         // belongs.
         if (player.onGround()) {
            double depth = supportDepth(player, y);
            if (depth >= NO_FALL_CLAIM_HEIGHT) {
               track.fallClaims++;
               track.fallClaimAbove = Math.max(track.fallClaimAbove, depth);
            }
         }
      }

      // ----------------------------------------------------------------- air walk
      // Split out of flight deliberately: hovering and claiming a floor are opposite
      // lies, and a check that owns both cannot tell an admin which one is happening.
      // The evidence is the physical claim plus what the body did with it, because
      // "standing on nothing" only matters if it is being used to walk.
      if (fakeGround) {
         flag(
            player,
            AIR_WALK,
            track.airTicks + " ticks of onGround with nothing under them, moving " + round(horizontal) + " blocks a tick"
         );
         correct(player, track, AIR_WALK);
         track.airTicks = 0;
         track.noFallTicks = 0;
         return;
      }

      if (hovering) {
         flag(player, FLIGHT, "airborne " + track.airTicks + " ticks without losing height");
         correct(player, track, FLIGHT);
         track.airTicks = 0;
         track.noFallTicks = 0;
         return;
      }

      // ------------------------------------------------- airborne arc, and water
      // These four all need the same precondition the speed model needs - the player's
      // own feet moving the body - so a tick that is scripted or served from above
      // resets every one of them rather than feeding them. A knockback, a teleport, a
      // firework dive and a shove out of a boss's hands all break an arc instead of
      // being measured by it.
      arcAndWater(player, track, now, scripted, onFoot, horizontal, dy, y, ticksPerSample);

      // -------------------------------------------- the simulation and its relatives
      // Deliberately before the speed block and deliberately not gated on it. The
      // speed check needs the window to be full before it has an opinion, and it
      // returns early while it fills; anything placed after it would be skipped for
      // the first second of every session and skipped entirely while the speed check
      // is switched off. These are separate findings with separate switches.
      useTracking(player, track, scripted);
      if (scripted) {
         track.gainEvents.clear();
         track.riseTicks = 0;
         track.predictionTicks = 0;
         track.predictedDrift = 0.0;
         track.predictionVelocity = 0.0;
         track.noSlowTicks = 0;
         track.groundedBefore = false;
      } else {
         simulation(player, track, grounded, horizontal, ticksPerSample, stepLike);
         vertical(player, track, grounded, scripted, dy, ticksPerSample);
         prediction(player, track, scripted, onFoot, dy, ticksPerSample);
         groundSpoof(player, track, scripted, grounded, dy, y, ticksPerSample);
         noSlow(player, track, scripted, grounded, horizontal, ticksPerSample);
         fastClimb(player, track, scripted);
         hunger(player, track, scripted, grounded, horizontal, ticksPerSample);
         arrowDodge(player, track, scripted, dx, dz, horizontal, now);
      }

      // ------------------------------------------------------------------- speed
      if (scripted) {
         track.speedTicks = 0;
         if (now < track.ownPositionUntil) {
            // This module put the body back, and the window is the evidence that decided to.
            // A correction is the module's movement rather than the player's - the samples
            // have already been re-anchored on the place the body was moved to - so it is
            // kept, and the check re-acquires a body that keeps hacking on the next sample
            // instead of after another full second. The stand-down itself is not cut short:
            // the client is still owed the arrival window, which is why nothing is judged
            // while it runs.
            track.lastSpeedNote = "standing down for this module's own correction - window kept and re-anchored";
            return;
         }
         track.clearSpeedWindow();
         track.lastSpeedNote = "standing down: " + track.scriptedReason;
         return;
      }
      if (!active(player, SPEED)) {
         track.clearSpeedWindow();
         track.lastSpeedNote = "the speed check is switched off or exempt for this player";
         return;
      }

      // ticksPerSample, taken once for the whole tick - see the top of movement(). The window is
      // one sample per server tick and the model is quoted per client tick, so on a server that is
      // behind - which is when a player is most likely to be running fast and least likely to be
      // doing anything wrong - a sample carries more movement than the model allows for one tick.
      if (PerfMonitor.tps() < MIN_READABLE_TPS) {
         // Past the correction's own limit the sample is no longer movement with a scale factor
         // on it, it is a server that cannot deliver the game. Standing down is not a hole - a
         // speed module gets nothing out of a server that late, which is already too slow to act
         // on anything - and it is the difference between blaming a player and blaming the box.
         track.clearSpeedWindow();
         track.lastSpeedNote = "standing down: the server is at " + round(PerfMonitor.tps())
            + " tps, below the " + round(MIN_READABLE_TPS) + " this window can be read at";
         return;
      }

      // The window is fed every tick and judged over a second (see
      // MovementPhysics for why it is not judged per tick). The surface and the
      // airborne flag come from the tick being sampled, so a player who ran over
      // ice and then kept sliding is measured against the ice they slid on.
      track.speedSample(x, z, grounded, slipperiness(player, grounded));
      track.lastWindowSamples = track.speedSamples();
      if (track.speedSamples() < SPEED_WINDOW) {
         track.lastSpeedNote = "window filling: " + track.speedSamples() + "/" + SPEED_WINDOW
            + " samples (it restarts whenever a stand-down fires, so this is also what "
            + "a flickering exemption looks like)";
         return;
      }

      double travelled = track.speedDistance();
      // Real seconds, not tick count over twenty: on a server running at half speed the window's
      // twenty samples are two seconds of a client's life, and a rate computed over one of them
      // reads double. The allowance below is scaled by the same factor, so the two stay in the
      // same units and the number in the finding keeps meaning blocks a second.
      double seconds = (track.speedSamples() - 1) / 20.0 * ticksPerSample;
      // The ceiling is summed over the window's own ticks rather than picked for the
      // window as a whole: a body that ran and jumped is on the ground for some of the
      // second and in the air for the rest, and the two states have different ceilings.
      // Judging the whole second by the ground ceiling is what flagged a player who was
      // just running around; handing it the jump ceiling for one airborne tick is what
      // would let a module hide. See MovementPhysics.windowAllowance.
      double slip = track.speedSlipperiness();
      double factor = movementFactor(player);
      // Grounded samples are read against two surfaces: the window's own (which is the slipperiest
      // it crossed, air included, and is what this line has always used) and the slipperiest one
      // the body's feet were actually stood on. The second one is the ice fix - a surface that
      // keeps momentum hands a grounded body the speed a jump holds, which is what an honest
      // player sprint-jumping across ice is doing; see MovementPhysics.groundedCeiling. Taking the
      // larger of the two leaves a window that never touched ice exactly as it was.
      double groundedStep = Math.max(
         MovementPhysics.steadyStep(slip, factor, false),
         MovementPhysics.groundedCeiling(track.speedGroundedSlipperiness(), factor)
      );
      double airborneStep = MovementPhysics.steadyStep(slip, factor, true);
      int airborneTicks = track.speedAirborneSamples();
      int groundedTicks = Math.max(0, track.speedSamples() - 1 - airborneTicks);
      double allowance = MovementPhysics.windowAllowance(
         groundedStep, airborneStep, groundedTicks, airborneTicks, ticksPerSample
      );
      track.lastObserved = travelled / Math.max(0.05, seconds);
      track.lastAllowed = allowance / Math.max(0.05, seconds);
      track.lastOverTicks = track.speedTicks;
      track.lastSpeedNote = "judged: " + round(track.lastObserved) + " blocks/s against an allowed "
         + round(track.lastAllowed) + ", " + track.speedTicks + "/" + SPEED_TICKS + " consecutive over";
      if (travelled > allowance) {
         track.speedTicks++;
         track.lastOverTicks = track.speedTicks;
         if (track.speedTicks >= SPEED_TICKS) {
            double perSecond = travelled / Math.max(0.05, seconds);
            double allowedPerSecond = allowance / Math.max(0.05, seconds);
            flag(
               player,
               SPEED,
               round(perSecond) + " blocks a second (speed-prediction allowed " + round(allowedPerSecond) + ") over the last "
                  + round(seconds) + "s",
               // The further over the model, the more of this is a verdict rather
               // than an artefact of one unusual second.
               // Twelve consecutive over-model ticks already make this a
               // corroborated speed window. Keep the evidence weight high enough
               // to notify staff immediately; the amount over the model still
               // affects the detail, while one noisy tick never reaches this path.
               Math.max(0.75, Math.min(1.0, (perSecond - allowedPerSecond) / allowedPerSecond))
            );
            correct(player, track, SPEED);
            track.speedTicks = 0;
            track.clearSpeedWindow();
         }
      } else {
         track.speedTicks = Math.max(0, track.speedTicks - 1);
      }
   }

   // ============================================================ the simulation
   //
   // Everything below this line is new, and it is all one idea: the previous block
   // judges whether a body is moving too fast for the surface it is on, and the
   // checks here judge whether the body is moving in a way that is *possible*. The
   // difference matters because a speed module can be configured to stay under any
   // level - under the walk ceiling, under the sprint ceiling, under the ceiling for
   // its surface - and can then never be caught by a level check at all. What it
   // cannot hide is the rate of change, because that is the thing it is there to
   // change, and the rate of change is arithmetic that has one answer.
   //
   // =============================================================================

   /**
    * The per-tick simulation: what this tick did that the last tick's momentum
    * cannot account for.
    *
    * <p>See {@link MotionModel} for the inequality and, more importantly, for why it
    * is only ever evaluated on ticks where the body was grounded on both sides of
    * the step. The short version is that vanilla's airborne horizontal handling is
    * not a friction model, so a sprint-jump legitimately gains momentum a ground
    * model would call impossible - which is why every naive speed check flags
    * bunny-hoppers and this one cannot.
    *
    * <p>The verdict is a count, never a single tick. One tick over the ceiling is a
    * client that resolved a slope differently from the server, a block placed into
    * its own space, a piston, or another entity's push; eight inside a second is a
    * module. And the action is a clamp rather than a teleport - see
    * {@link #correct} - because a window judgement must not be able to move a body.
    */
   private static void simulation(
      ServerPlayer player, Track track, boolean grounded, double horizontal, double ticksPerSample, boolean stepLike
   ) {
      if (!active(player, SIMULATION)) {
         return;
      }
      double slipperiness = slipperiness(player, grounded);
      MotionModel.Tick tick = MotionModel.evaluate(
         grounded,
         track.groundedBefore,
         track.previousStep,
         horizontal,
         slipperiness,
         movementFactor(player),
         ticksPerSample
      );
      track.groundedBefore = grounded;
      track.previousStep = horizontal;
      track.hasPreviousStep = true;
      // A stair or a slab resolves its step by moving the body up and along inside one tick,
      // which the flat-ground friction model has no term for - so a player running up a
      // staircase was reported as a speed module. The surface is asked rather than guessed at;
      // only the judgement is skipped, and the state above still advances so the tick after
      // the step is read normally.
      if (stepLike) {
         track.gainEvents.clear();
         return;
      }
      if (!tick.judged()) {
         return;
      }
      long now = tickOf(player);
      if (now - track.gainWindow > MotionModel.GAIN_WINDOW) {
         track.gainWindow = now;
         track.gainEvents.clear();
      }
      track.gainEvents.addLast(tick.overCeiling());
      while (track.gainEvents.size() > MotionModel.GAIN_WINDOW) {
         track.gainEvents.removeFirst();
      }
      int over = 0;
      for (boolean event : track.gainEvents) {
         if (event) {
            over++;
         }
      }
      if (over < MotionModel.GAIN_TICKS) {
         return;
      }
      int observedWindow = track.gainEvents.size();
      track.gainEvents.clear();
      flag(
         player,
         SIMULATION,
         over
            + " of the last "
            + observedWindow
            + " ground ticks gained more speed than the body's own input can add - "
            + round(tick.observed())
            + " blocks against a ceiling of "
            + round(tick.ceiling())
            + " ("
            + round(tick.ratio())
            + "x)",
         Math.min(1.0, (tick.ratio() - MotionModel.GAIN_MARGIN) / 0.5)
      );
      correct(player, track, SIMULATION);
   }

   /**
    * The vertical half: a body that rose further than a jump can lift it.
    *
    * <p>{@code dy} is the tick's own change in altitude, so this fires on the tick
    * the lift happened rather than forty ticks later. Everything that legitimately
    * holds a body up is already out of the way before this is called - flight, water,
    * climbing, levitation, a vehicle, slow falling and the mod's own time stop are all
    * {@link #scriptedMovement} - which leaves a jump as the only honest explanation
    * for going up at all.
    */
   private static void vertical(
      ServerPlayer player,
      Track track,
      boolean grounded,
      boolean scripted,
      double dy,
      double ticksPerSample
   ) {
      if (scripted || !active(player, MOTION)) {
         track.riseTicks = 0;
         return;
      }
      boolean jumped = grounded && dy > 0.0;
      if (!MotionModel.impossibleRise(dy, grounded, jumped, scripted, ticksPerSample)) {
         track.riseTicks = 0;
         return;
      }
      track.riseTicks++;
      if (track.riseTicks < MOTION_TICKS) {
         return;
      }
      track.riseTicks = 0;
      flag(
         player,
         MOTION,
         "rose " + round(dy) + " blocks in one tick while airborne and not jumping - a jump is "
            + round(MotionModel.JUMP_IMPULSE),
         Math.min(1.0, (dy - MotionModel.JUMP_IMPULSE) / 0.4)
      );
      correct(player, track, MOTION);
   }

   /**
    * Altitude that gravity cannot account for, over a whole airborne arc.
    *
    * <p>The prediction is deliberately <b>relative and self-referential</b>: it starts
    * from where the body actually was when the arc began and integrates the vertical
    * steps the body itself reported under vanilla's gravity. So an arc that climbs
    * under its own power both moves the prediction and moves the body, and the two stay
    * together - what separates flight from a jump is not how high the body got but
    * whether the two <i>diverged</i>, which is the thing a jump cannot do and a hover
    * does immediately. That also makes it immune to the thing that makes absolute
    * altitude checks useless: a server whose terrain, realms or boss arenas start a
    * player at any height at all.
    */
   private static void prediction(
      ServerPlayer player, Track track, boolean scripted, boolean onFoot, double dy, double ticksPerSample
   ) {
      if (scripted || onFoot) {
         track.predictionTicks = 0;
         track.predictedDrift = 0.0;
         track.predictionVelocity = 0.0;
         return;
      }
      if (!active(player, FLIGHT_PREDICTION)) {
         track.predictionTicks = 0;
         track.predictedDrift = 0.0;
         track.predictionVelocity = 0.0;
         return;
      }
      // What one of this server's samples is worth in the client's own ticks - the same
      // correction the speed, break and rise windows are read through. A sample that carried
      // two ticks of a hop is not a body that rose twice as fast, and a sample that carried
      // two ticks of a fall is not a body that refused to fall.
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
      int steps = Math.max(1, (int)Math.round(scale));
      // A rise the size of a jump is a new hop, not altitude the fall model failed to take.
      // This is the sprint-jump the world probe cannot always see: holding jump makes the client
      // re-jump on the very tick it touches down, so the sample that carries the re-jump is
      // already most of a jump off the floor - and when the packets around it coalesce on a
      // struggling server it arrives a whole jump above the floor, outside the probe's reach,
      // which left the arc open across the entire run and reported an honest player as flying.
      // The rise itself is the witness, and the cap is what makes forgiving it safe: a body that
      // <i>climbs under its own power</i> rises further than a jump can lift it through one
      // sample, so it is never handed the fresh arc - it is judged, and the tick it is judged on
      // is the one the vertical and hover checks already own.
      boolean freshHop = dy > 0.0
         && dy <= (MotionModel.JUMP_IMPULSE + MotionModel.RISE_MARGIN) * scale;
      if (track.predictionTicks == 0 || freshHop) {
         // Start from the vertical velocity the server actually observed when the player left
         // the ground. Starting from zero (the old implementation) made every ordinary jump
         // look like a flight: a normal jump's first airborne step is positive, while a
         // fall-from-rest prediction is already negative. The simulation must predict from the
         // state it was given, not from an imaginary player who stepped off a ledge.
         track.predictionVelocity = dy;
         track.predictedDrift = 0.0;
         track.predictionFell = false;
         track.predictionTicks = 1;
      } else {
         track.predictionTicks++;
      }
      // Walk the model the same number of client ticks this sample actually carried, so a
      // sample that held two ticks of a fall is compared against two ticks of predicted fall.
      double expectedStep = 0.0;
      for (int i = 0; i < steps; i++) {
         expectedStep += track.predictionVelocity;
         track.predictionVelocity = (track.predictionVelocity - MotionModel.GRAVITY) * MotionModel.VERTICAL_DRAG;
      }
      track.predictedDrift += dy - expectedStep;
      if (dy < -0.02) {
         track.predictionFell = true;
      }
      if (track.predictionTicks < (int)Math.ceil(PREDICTION_MIN_TICKS * scale)) {
         return;
      }
      if (track.predictedDrift <= PREDICTION_DRIFT) {
         return;
      }
      flag(
         player,
         FLIGHT_PREDICTION,
         "rode "
            + round(track.predictedDrift)
            + " blocks above the fall the body's own vertical steps predict, over "
            + track.predictionTicks
            + " airborne ticks",
         Math.min(1.0, track.predictedDrift / (PREDICTION_DRIFT * 4.0))
      );
      track.predictionTicks = 0;
      track.predictedDrift = 0.0;
      track.predictionVelocity = 0.0;
      track.predictionFell = false;
      correct(player, track, FLIGHT_PREDICTION);
   }

   /**
    * A claimed floor that is not there, on the tick the claim is made.
    *
    * <p>Narrow on purpose, and the narrowness is the entire reason it can be acted on
    * at all. Air-walk needs twelve ticks of a body standing on nothing and answers "is
    * this player levitating"; this answers "did this client just lie", and the only
    * case it admits is the one no step, stair, slab or fence produces: the body said it
    * was on the ground while it was <b>descending</b> through space that holds nothing.
    * Vertical lifts are excluded by sign, a rise is excluded by the step-up probe, and
    * anything the world can explain is excluded before the comparison happens.
    */
   private static void groundSpoof(
      ServerPlayer player,
      Track track,
      boolean scripted,
      boolean grounded,
      double dy,
      double y,
      double ticksPerSample
   ) {
      if (scripted || !active(player, GROUND_SPOOF)) {
         return;
      }
      if (!player.onGround() || grounded) {
         return;
      }
      // Descending, and by more than a step can account for. Claiming the floor while
      // falling is the flag a client sends to skip fall damage and to be handed the
      // server's own notion of airborne.
      //
      // The bar is one sample's worth, and a sample on a server behind by half is two of the
      // client's ticks - so it is scaled by the tick rate. Read against a one-tick descent,
      // an honest fall delivered in a late tick is a body dropping twice as fast as it can.
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
      if (dy > -SPOOF_FALL * scale) {
         return;
      }
      if (stepUpSupport(player, y)) {
         return;
      }
      flag(
         player,
         GROUND_SPOOF,
         "claimed ground while descending " + round(-dy) + " blocks through empty space"
      );
   }

   /**
    * Hunger that never runs out.
    *
    * <p>Vanilla charges a point of food or saturation per forty blocks sprinted, so a
    * body that has sprinted a hundred and twenty blocks without its food total moving at
    * all has an anti-hunger module - there is no other way to pay nothing for that much
    * running. The long window is the false-positive defence: dropping food is the normal
    * case and a player who eats mid-window resets it, so the check only ever fires on a
    * body that has genuinely sprinted the distance while every reading of its own food
    * said it paid nothing.
    *
    * <p>Read from the game's own food data rather than from any inference about
    * packets, which is what makes it decidable: {@code getSaturationLevel()} and
    * {@code getFoodLevel()} are the exact two numbers vanilla spends, so a client
    * cannot hide the cost from the server - it can only decline to have paid it.
    *
    * <p>And it is the <i>pace</i> that is measured, not the sprint flag, which is the
    * other half of the fix. This check used to stand down whenever
    * {@code isSprinting()} was false, and that is exactly the switch a no-hunger module
    * operates: vanilla charges hunger from the server's own sprint state, so a client
    * that runs at sprint speed without ever telling the server it is sprinting pays
    * nothing to run - and the food arithmetic here could never fire, because the flag it
    * insisted on was the thing the module was hiding. A body whose step is above what
    * walking can produce, on a surface that does not carry momentum, is running whether
    * or not the server was told.
    */
   private static void hunger(
      ServerPlayer player, Track track, boolean scripted, boolean grounded, double horizontal, double ticksPerSample
   ) {
      if (scripted || !active(player, ANTI_HUNGER) || player.isCreative() || player.isSpectator()) {
         track.resetHunger(-1, -1.0F);
         return;
      }
      int food = player.getFoodData().getFoodLevel();
      float saturation = player.getFoodData().getSaturationLevel();
      boolean hidden = !player.isSprinting() && hiddenSprint(player, grounded, horizontal, ticksPerSample);
      if (!player.isSprinting() && !hidden) {
         // A pause is not a window: the counters reset so that a player who stops to
         // eat is judged on the run after the meal, not across it.
         track.resetHunger(food, saturation);
         return;
      }
      if (track.lastFoodLevel < 0) {
         track.resetHunger(food, saturation);
         return;
      }
      // One number, because vanilla spends saturation first and food second: the
      // combined total is what a sprint is charged against, and the only thing that can
      // honestly fail to move while a body runs.
      double before = track.lastFoodLevel + track.lastSaturation;
      double after = food + saturation;
      if (after > before + 1.0E-4) {
         // They ate, or something fed them. Either way this window is spent.
         track.resetHunger(food, saturation);
         return;
      }
      track.sprintedBlocks += horizontal;
      if (hidden) {
         track.hiddenSprintBlocks += horizontal;
      }
      track.hungerTicks++;
      // A body already at zero food cannot pay anything, so starvation is not
      // evidence of anything - it is the state the check is trying to detect, and
      // treating it as proof would flag every player who ran out of food.
      if (track.sprintedBlocks < MotionModel.HUNGER_SPRINT_BLOCKS || food <= 0) {
         return;
      }
      if (MotionModel.hungerPaid(before, after)) {
         track.resetHunger(food, saturation);
         return;
      }
      flag(
         player,
         ANTI_HUNGER,
         "sprinted "
            + round(track.sprintedBlocks)
            + " blocks over "
            + track.hungerTicks
            + " ticks with food plus saturation stuck at "
            + round(before)
            + " (now "
            + round(after)
            + ")"
            + (track.hiddenSprintBlocks > 1.0
               ? ", " + round(track.hiddenSprintBlocks) + " of them at sprint pace the server was never told about"
               : "")
      );
      track.resetHunger(food, saturation);
   }

   /**
    * Whether this step is a sprint the server was never told about.
    *
    * <p>The honest ways for a body to be above walking pace without the sprint flag are
    * all named and removed before the pace is judged, because each of them moves a
    * player the server did not ask to move: a vehicle, a climbable, water and lava (which
    * move the body rather than its feet), elytra, a slippery surface that keeps momentum
    * after the run stopped, and being airborne, where a sprint-jump's own momentum
    * outlives the flag it was launched with. What is left is a grounded step that is
    * faster than walking can produce and no faster than sprinting can, which is a player
    * running.
    */
   private static boolean hiddenSprint(ServerPlayer player, boolean grounded, double horizontal, double ticksPerSample) {
      if (!grounded
         || player.getVehicle() != null
         || player.isPassenger()
         || player.onClimbable()
         || player.isFallFlying()
         || player.isInWater()
         || player.isInLava()) {
         return false;
      }
      if (slipperiness(player, true) > MovementPhysics.GROUND_SLIPPERINESS + 1.0E-4) {
         return false;
      }
      try {
         // The pace ceilings are per client tick and the step is one sample: on a server behind
         // by half an honest runner puts two ticks of run into a sample and would read as
         // sprint pace the server was never told about. Scaled, so the sample is measured
         // against what a sample is worth.
         double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
         double walk = MovementPhysics.steadyStep(MovementPhysics.GROUND_SLIPPERINESS, walkFactor(player), false) * scale;
         return MotionModel.sprintPace(horizontal, walk, walk * MovementPhysics.SPRINT_FACTOR);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * A projectile avoided on the tick it became unavoidable.
    *
    * <p>Read from the body's own path, because that is the only place the answer is. A dodge
    * module does not move faster than a player and does not fly - it decides earlier, and the
    * decision shows up as a turn that happened one tick after the threat became a threat. So
    * each tick asks two questions: is anything about to pass through this body, and did the
    * body turn sharply just now?
    *
    * <p>The second question is asked of two consecutive steps, which is why this needs the
    * body to be moving: a player standing still and a player being shoved are both excluded
    * before it is asked, because a turn somebody else made is not a dodge.
    */
   private static void arrowDodge(
      ServerPlayer player,
      Track track,
      boolean scripted,
      double dx,
      double dz,
      double horizontal,
      long now
   ) {
      double turn = MotionModel.turnDegrees(track.stepX, track.stepZ, dx, dz);
      boolean moving = horizontal > 0.02 && !(dx == 0.0 && dz == 0.0);
      track.stepX = dx;
      track.stepZ = dz;
      if (scripted || !active(player, ARROW_DODGE)) {
         track.threatTick = Long.MIN_VALUE / 4;
         return;
      }
      if (!moving || !(player.level() instanceof ServerLevel level)) {
         return;
      }
      if (incomingProjectile(player, level)) {
         track.threatTick = now;
      }
      if (!MotionModel.superhumanDodge(turn, now - track.threatTick)) {
         return;
      }
      if (now - track.dodgeWindow > DODGE_WINDOW) {
         track.dodgeWindow = now;
         track.dodges = 0;
      }
      track.dodges++;
      if (track.dodges < DODGE_EVENTS) {
         return;
      }
      track.dodges = 0;
      flag(
         player,
         ARROW_DODGE,
         "left a projectile's line "
            + round(turn)
            + "\u00b0 inside the tick it became unavoidable ("
            + DODGE_EVENTS
            + " times in "
            + (DODGE_WINDOW / 20L)
            + "s - a hand takes about four ticks to see it)"
      );
   }

   /**
    * True when a projectile the player does not own is a couple of ticks from passing through
    * this body.
    *
    * <p>The arithmetic is the closest approach: project the projectile's velocity onto the
    * vector from it to the body, and ask whether the miss distance at that moment is inside
    * the body. It is deliberately a corridor rather than a hit test, because the question is
    * not whether the arrow lands but whether it <i>would</i> - a dodge is only a dodge if
    * standing still was going to hurt.
    */
   private static boolean incomingProjectile(ServerPlayer player, ServerLevel level) {
      try {
         java.util.List<net.minecraft.world.entity.projectile.arrow.AbstractArrow> arrows =
            level.getEntitiesOfClass(
               net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
               player.getBoundingBox().inflate(DODGE_RANGE),
               arrow -> !arrow.isRemoved() && arrow.getOwner() != player
            );
         for (net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow : arrows) {
            Vec3 velocity = arrow.getDeltaMovement();
            double speed = velocity.length();
            if (speed < 0.15) {
               continue;
            }
            Vec3 to = new Vec3(player.getX() - arrow.getX(), player.getY() + 0.9 - arrow.getY(), player.getZ() - arrow.getZ());
            double ticks = (to.x * velocity.x + to.y * velocity.y + to.z * velocity.z) / (speed * speed);
            if (ticks < 0.0 || ticks > DODGE_TICKS) {
               continue;
            }
            double missX = to.x - velocity.x * ticks;
            double missZ = to.z - velocity.z * ticks;
            if (missX * missX + missZ * missZ <= DODGE_CORRIDOR * DODGE_CORRIDOR) {
               return true;
            }
         }
      } catch (Throwable t) {
         // Fails open, like every detection: a projectile scan that cannot be made is not
         // evidence of anything.
         return false;
      }
      return false;
   }

   /**
    * This player's movement multiplier with the sprint term left out - the ceiling the
    * body would be held to if the server's sprint flag were the truth.
    *
    * <p>Read from the attribute so a Speed potion, a modded attribute or a slowness
    * effect raises or lowers it by itself, and never from the sprint flag, which is the
    * number the check is asking about.
    */
   private static double walkFactor(ServerPlayer player) {
      try {
         return MovementPhysics.speedFactor(player.getAttributeValue(Attributes.MOVEMENT_SPEED), false);
      } catch (Throwable t) {
         return 1.0;
      }
   }

   /**
    * Movement at the speed the body would have had without the item it is holding.
    *
    * <p>Vanilla slows a body to a fifth of its speed while it is using an item - that
    * is the price of drawing a bow, blocking with a shield, drinking, eating or raising
    * a spyglass, and it is the whole reason those actions are decisions. A no-slow
    * module removes the price and nothing else, so the check is a direct comparison: the
    * ceiling the item implies against the speed the body actually had. The allowance is
    * four times the honest figure (see {@code NO_SLOW_KEEP}) because the client resolves
    * its own movement and a player who raises a bow mid-sprint keeps a tick or two of
    * momentum on the way in - and because a module has to keep most of the speed to be
    * worth running at all.
    */
   private static void noSlow(
      ServerPlayer player, Track track, boolean scripted, boolean grounded, double horizontal, double ticksPerSample
   ) {
      if (scripted || !active(player, NO_SLOW) || !player.isUsingItem()) {
         track.noSlowTicks = 0;
         return;
      }
      // The item's <i>own</i> declared price, read from the component the game itself
      // multiplies movement input by - never from the use animation, which is a
      // property of the pose and says nothing about the cost. See
      // {@link #useSpeedMultiplier} for the false positive that distinction fixes.
      double multiplier = useSpeedMultiplier(player);
      if (!MotionModel.slowsWhileUsing(multiplier)) {
         track.noSlowTicks = 0;
         return;
      }
      // The ceilings are quoted per client tick and the step this is compared against is one
      // sample, so on a server behind by half an honest body moving at its item's own price
      // reads as more than twice it and a no-slow module is called on a player who has paid.
      // Scaling the ceiling is the same correction the speed window makes; the reported rate
      // is divided by the same factor so "blocks a second" keeps meaning blocks a second.
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
      double slipping = slipperiness(player, grounded);
      double factor = movementFactor(player);
      double full = MovementPhysics.steadyStep(slipping, factor, !grounded) * scale;
      double slowed = full * multiplier;
      double kept = MotionModel.noSlowThreshold(multiplier, full);
      double perSecond = 20.0 / scale;
      // Only judged once the use has lasted long enough that the slowdown has
      // certainly bitten - a single tick of a bow draw is a release, not a module.
      if (track.useTicks < NO_SLOW_TICKS) {
         return;
      }
      if (horizontal <= kept) {
         track.noSlowTicks = 0;
         return;
      }
      track.noSlowTicks++;
      if (track.noSlowTicks < NO_SLOW_TICKS) {
         return;
      }
      track.noSlowTicks = 0;
      flag(
         player,
         NO_SLOW,
         "kept "
            + round(horizontal * perSecond)
            + " blocks a second while using "
            + describeUse(player)
            + ", which the item itself prices at "
            + round(slowed * perSecond)
            + " blocks a second",
         Math.min(1.0, (horizontal - slowed) / Math.max(0.05, full))
      );
      correct(player, track, NO_SLOW);
   }

   /** The threshold, as a predicate, so both sides of it can be pinned. */
   public static boolean climbIsFast(double risePerTick) {
      return risePerTick > FAST_CLIMB_STEP;
   }

   /**
    * Fast climb: a body rising up a climbable faster than the game's own constant.
    *
    * <p>Vanilla does not derive climb speed from the movement attribute at all - a ladder is
    * a fixed 0.2 blocks a tick, which is why this is its own check rather than a branch of
    * the speed model, and why a "noslow"-style module cannot produce it. The read is the
    * body's own reported rise, only while it is actually on something climbable, and only
    * after {@link #FAST_CLIMB_TICKS} ticks in a row: anything that can serve a body upward -
    * a shove, a boat, a bubble column, levitation, elytra - breaks the measurement instead of
    * pausing it, because a raised tick in the middle of a run must not be swallowed by it.
    *
    * <p>Deliberately advisory: it is recorded, alerted and left to a person rather than
    * correcting anybody's position, because the one thing that can move a body up a ladder
    * faster than vanilla is a mod this server does not know about.
    */
   private static void fastClimb(ServerPlayer player, Track track, boolean scripted) {
      if (scripted
         || !active(player, FAST_CLIMB)
         || player.isCreative()
         || player.isSpectator()
         || player.isFallFlying()
         || player.isInWater()
         || player.getVehicle() != null
         || player.hasEffect(MobEffects.LEVITATION)
         || player.hasEffect(MobEffects.SLOW_FALLING)) {
         track.climbTicks = 0;
         track.climbPeak = 0.0;
         return;
      }

      boolean climbing;
      try {
         climbing = player.getBlockStateOn().is(net.minecraft.tags.BlockTags.CLIMBABLE);
      } catch (Throwable t) {
         climbing = false;
      }

      double rise = player.getDeltaMovement().y;
      if (!climbing || !climbIsFast(rise)) {
         track.climbTicks = 0;
         track.climbPeak = 0.0;
         return;
      }

      track.climbTicks++;
      track.climbPeak = Math.max(track.climbPeak, rise);
      if (track.climbTicks < FAST_CLIMB_TICKS) {
         return;
      }

      track.climbTicks = 0;
      flag(
         player,
         FAST_CLIMB,
         "climbed " + round(track.climbPeak * 20.0) + " blocks a second for " + FAST_CLIMB_TICKS
            + " ticks in a row, and a ladder is " + round(FAST_CLIMB_STEP * 20.0) + " at the very most",
         Math.min(1.0, rise / Math.max(0.05, FAST_CLIMB_STEP))
      );
   }

   /**
    * The movement multiplier the item being used declares for itself.
    *
    * <p>Vanilla's rule, read from the game's own code rather than re-derived: the
    * client scales its movement input by the held item's {@code use_effects}
    * {@code speed_multiplier} whenever it is using an item, and an item with no such
    * component gets {@code UseEffects.DEFAULT}, which is 0.2. So this number is the
    * price of using the item, and it is the only honest basis for a no-slow check -
    * a bow, a shield and a potion all pay four fifths of their speed, and an item
    * that declares a gentler price simply pays less.
    *
    * <p>This is the fix for the false positive that produced this method. The check
    * inferred the penalty from the item's use animation and assumed four fifths for
    * any animation but {@code NONE} - but this mod grants the Distant Memory sword
    * {@code use_effects} at <i>full</i> speed on purpose, so blocking with it while
    * running is designed behaviour, and it read as a no-slow module every time it
    * was raised. An item's price is data, and data beats a guess about a pose.
    */
   public static double useSpeedMultiplier(ServerPlayer player) {
      try {
         ItemStack held = player == null ? ItemStack.EMPTY : player.getUseItem();
         if (held == null || held.isEmpty()) {
            return MotionModel.DEFAULT_USE_SPEED;
         }
         UseEffects effects = held.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT);
         return effects == null ? MotionModel.DEFAULT_USE_SPEED : effects.speedMultiplier();
      } catch (Throwable t) {
         // Fails to the game's own default, which is the careful direction: the body
         // is then judged against the ordinary price of using an item.
         return MotionModel.DEFAULT_USE_SPEED;
      }
   }

   /** The item being used, named the way a moderator reading an alert needs it. */
   private static String describeUse(ServerPlayer player) {
      try {
         ItemStack held = player.getUseItem();
         if (held == null || held.isEmpty()) {
            return "an item";
         }
         String name = held.getHoverName().getString();
         return name == null || name.isBlank() ? held.getItem().toString() : name;
      } catch (Throwable t) {
         return "an item";
      }
   }

   /**
    * A consumable that finished before its own duration was up.
    *
    * <p>The server counts the use itself - {@code useItemRemainingTicks} is decremented
    * on its own tick loop - so a client cannot simply declare a potion drunk. What a
    * fast-use module does is drop the item and take the effect early, which shows up
    * here as a use that ended with the server's own counter still meaningfully positive.
    * Both halves are required: the use has to have actually <i>consumed</i> something
    * (the stack changed or went down), because a player who lets go of a bow after two
    * ticks did nothing wrong.
    */
   /**
    * The item a tracked use is read back from: the hand it was started in, and no other.
    *
    * <p>A shield lives in the offhand, and reading the main hand at the end of a block sees the sword
    * the player is carrying - a different item entirely, which the consumption test below read as
    * "the used item vanished", so every shield block was a fast-use finding. The hand is the whole
    * answer, and exposing this is how a check proves it rather than the arithmetic around it.
    */
   public static ItemStack usedInTheHand(ItemStack mainHand, ItemStack offHand, InteractionHand hand) {
      return hand == InteractionHand.OFF_HAND ? offHand : mainHand;
   }

   /**
    * Whether a use ended by consuming its item: the stack is gone, has shrunk, or is a different item
    * than the one the use started with. Read only of {@link #usedInTheHand}, never of the main hand.
    */
   public static boolean useWasConsumed(ItemStack after, int countAtStart, String idAtStart) {
      return after.isEmpty()
         || after.getCount() < countAtStart
         || !after.getItem().toString().equals(idAtStart);
   }

   private static void useTracking(ServerPlayer player, Track track, boolean scripted) {
      try {
         ItemStack held = player.getUseItem();
         boolean using = !scripted && player.isUsingItem() && !held.isEmpty();
         long now = tickOf(player);
         if (using && !track.useActive) {
            track.useActive = true;
            track.useStartedTick = now;
            track.useTicks = 0;
            // Which hand is holding it. The item that finishes the use is read back from this same
            // hand at the end: a shield is usually in the offhand, and reading the main hand at the
            // end of a block sees the sword the player is carrying - a different item, which the
            // consumption test below read as "the used item vanished", so every shield block was a
            // fast-use finding.
            track.useHand = player.getUsedItemHand();
            track.useItemId = held.getItem().toString();
            track.useItemCount = held.getCount();
            track.useRemaining = player.getUseItemRemainingTicks();
            // The item's own duration, read here because it is the only moment the
            // stack is certainly the one being used.
            track.useDuration = held.getUseDuration(player);
            return;
         }
         if (using) {
            track.useTicks++;
            track.useRemaining = player.getUseItemRemainingTicks();
            return;
         }
         if (!track.useActive) {
            return;
         }
         track.useActive = false;
         int expected = track.useDuration;
         int used = track.useTicks;
         long started = track.useStartedTick;
         if (!active(player, FAST_USE) || expected <= 0) {
            return;
         }
         if (used <= 0) {
            return;
         }
         if (used >= expected * MotionModel.FAST_USE_RATIO) {
            return;
         }
         // The item has to have been consumed rather than let go of, and it is read from the hand it
         // was used in. See {@link #usedInTheHand}: an offhand shield read out of the main hand is
         // the sword beside it, and that is a different item - a shield block looked like a
         // consumable that vanished, once per block.
         ItemStack after = usedInTheHand(player.getMainHandItem(), player.getOffhandItem(), track.useHand);
         boolean consumed = useWasConsumed(after, track.useItemCount, track.useItemId);
         if (!consumed) {
            return;
         }
         flag(
            player,
            FAST_USE,
            "a "
               + track.useItemId
               + " finished in "
               + used
               + " ticks where the item takes "
               + expected
               + " (server had "
               + Math.max(0, track.useRemaining)
               + " left)",
            Math.min(1.0, (expected - used) / (double) Math.max(1, expected))
         );
      } catch (Throwable t) {
         track.useActive = false;
      }
   }

   /**
    * The slipperiness of the surface under the player's feet, read from the block
    * itself rather than from a table in here: a modded slippery block then behaves
    * like ice in the model for free, and a vanilla one can never drift out of it.
    */
   private static double slipperiness(ServerPlayer player, boolean grounded) {
      if (!grounded || !(player.level() instanceof ServerLevel level)) {
         // Not standing on anything, so vanilla uses its own air friction. 1.0
         // through the friction function is that value.
         return 1.0;
      }
      BlockState below = level.getBlockState(player.blockPosition().below());
      try {
         return below.getBlock().getFriction();
      } catch (Throwable t) {
         return MovementPhysics.GROUND_SLIPPERINESS;
      }
   }

   /**
    * How much faster than an unmodified player this one moves, from the game's own
    * movement-speed attribute - which already has every Speed and Slowness potion,
    * every attribute modifier and every item in it - times sprint. Read rather
    * than accumulated, so a mod that changes movement speed is believed without
    * the anticheat having to know it exists.
    */
   public static double movementFactor(ServerPlayer player) {
      try {
         return MovementPhysics.speedFactor(player.getAttributeValue(Attributes.MOVEMENT_SPEED), player.isSprinting());
      } catch (Throwable t) {
         return MovementPhysics.speedFactor(0.1, player.isSprinting());
      }
   }

   /**
    * Ticks of server-driven movement still to run for this player: non-zero from
    * the moment the server sends them a position or a velocity until the grace
    * that follows it ends. Read by {@code /ff anticheat simulate}, because "why is
    * nothing being flagged" has this as an answer, and by the self-test, because
    * the correlation is otherwise only observable in play.
    */
   public static long serverMotionGrace(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      Track track = TRACKS.get(player.getUUID());
      // Through the same function the tick loop decides with, and never through the field
      // directly. This used to read the long window on its own, so once the client had
      // answered a teleport the readout still said two seconds where the decision said
      // half of one - and a moderator asking "why is nothing being flagged" was shown a
      // window that no check was actually using.
      return track == null ? 0L : Math.max(0L, graceEnd(track) - tickOf(player));
   }

   /**
    * The step model this player is judged against, for {@code /ff anticheat
    * simulate}: the surface, the multiplier and the resulting blocks a second.
    */
   public static String speedModel(ServerPlayer player) {
      Track track = TRACKS.get(player.getUUID());
      boolean airborne = track != null && track.speedAirborne();
      double slip = track != null && track.speedSamples() > 0 ? track.speedSlipperiness() : slipperiness(player, player.onGround());
      double factor = movementFactor(player);
      double steady = airborne
         ? MovementPhysics.steadyStep(slip, factor, true)
         : Math.max(MovementPhysics.steadyStep(slip, factor, false), MovementPhysics.groundedCeiling(track == null ? slip : track.speedGroundedSlipperiness(), factor));
      // Real seconds: the window's samples are the server's own ticks, and on a server that is
      // behind, twenty of them are not one second (see MovementPhysics.tickScale).
      double ticksPerSample = MovementPhysics.tickScale(PerfMonitor.tps());
      double observed = track == null
         ? 0.0
         : track.speedDistance() / Math.max(0.05, (track.speedSamples() - 1) / 20.0 * ticksPerSample);
      return round(observed) + " blocks/s observed, " + round(steady * 20.0) + " modelled (friction "
         + round(MovementPhysics.friction(slip)) + ", x" + round(factor) + (airborne ? ", airborne" : "") + ")";
   }

   /**
    * True when this body is being driven by something other than the player's own
    * feet - flight, a vehicle, water, a climb, a time stop. Movement that looks
    * impossible in any of those is not impossible, and this is the one list both
    * the tick check and the packet check answer to, so the two layers can never
    * disagree about what counts as free movement.
    */
   public static boolean scriptedMovement(ServerPlayer player) {
      return scriptedReason(player, false) != null;
   }

   /**
    * <b>Which</b> stand-down applies to this body, by name, or {@code null} when the
    * player's own feet are driving it.
    *
    * <p>This is the single most important diagnostic in the module and it exists
    * because of a specific failure. Every one of these exemptions is correct on its
    * own - a boat, a ladder and a time stop really do move a body - but they compose
    * into one verdict, and when the verdict came out wrong there was no way to tell
    * which of a dozen conditions produced it. A player testing a speed client against
    * an engine that had silently classified them as "flying" saw an empty ledger and
    * nothing else. Naming the reason is what turns that from a mystery into a line of
    * output.
    *
    * <p>The physics exemptions and the administrative ones are deliberately still
    * separated here. Water, a vehicle, a climb, levitation, elytra and a server time
    * stop genuinely drive the body and stand the checks down even in test mode, because
    * a check that fights the game is worse than no check. Creative, flying and
    * spectator are <i>administrative</i>: they are how an operator plays, and
    * {@code /ff anticheat testmode} exists precisely so an operator can be judged, so
    * those stand down only when test mode is off.
    */
   public static String scriptedReason(ServerPlayer player, boolean serverMoved) {
      if (player == null) {
         return "no player";
      }
      // Ordered worst-last: the first condition that applies is reported, so the
      // readout names the thing most likely to surprise somebody.
      if (serverMoved) {
         // Named, not generalised: "a teleport the server sent" and "a teleport this
         // module performed" produce the same evidence and need opposite fixes.
         Track track = TRACKS.get(player.getUUID());
         String source = track == null || track.teleportSource == null
            ? "teleport or velocity"
            : track.teleportSource;
         return "server moved them (" + source + ")";
      }
      if (player.isFallFlying()) {
         return "elytra";
      }
      // Compared on the server's own tick rather than this module's clock, because the tail
      // is armed from {@link #elytra}, which runs whether or not the checks do. The two are
      // the same number whenever the module is ticking; they are not the same number on a
      // server whose checks have never run, which is exactly when the tail still matters.
      Track glide = TRACKS.get(player.getUUID());
      if (glide != null && serverTick(player) <= glide.glideUntil) {
         // The boundary, not the glide: the leap off the ledge that starts one, the crash
         // into a wall that ends one, the rocket fired on the tick the wings open. The
         // flag the check reads is set by a packet that lands a tick or two either side of
         // the movement it belongs to - which is exactly the window a glide used to come
         // back as "flight and speed hack".
         return glide.lateGlide ? "elytra (glide begun late, chest slot behind)" : "elytra (just out of a glide)";
      }
      if (player.isAutoSpinAttack()) {
         return "riptide spin";
      }
      if (player.getVehicle() != null) {
         return "riding " + player.getVehicle().getType().toString();
      }
      if (player.isInWater() || player.isInLava()) {
         return "in liquid";
      }
      if (player.onClimbable()) {
         return "on a ladder or vine";
      }
      if (player.isSleeping()) {
         return "asleep";
      }
      if (player.hasEffect(MobEffects.LEVITATION)) {
         return "levitation";
      }
      if (player.hasEffect(MobEffects.SLOW_FALLING)) {
         return "slow falling";
      }
      if (TimeLordManager.isTimeStopped(player)) {
         return "time is stopped";
      }
      boolean test = TEST_MODE.contains(player.getUUID());
      if (test) {
         // Test mode's whole point: the operator asked to be judged, so the
         // administrative stand-downs are lifted from them and named as lifted.
         return null;
      }
      if (player.getAbilities().flying) {
         return "ability flight";
      }
      if (player.isCreative()) {
         return "creative mode";
      }
      if (player.isSpectator()) {
         return "spectator mode";
      }
      return null;
   }

   /**
    * Whether a stand-down reason is an ability that owns the body rather than a claim about it.
    *
    * <p>Only the reasons that <i>hold</i> a body in place belong here: a stopped clock, and the
    * world itself pinning somebody asleep in a bed. A teleport, a shove or a launch is a claim
    * about where a body went and it is that claim which a hack abuses by generating one per tick,
    * so those keep counting towards the constant-correction rule.
    */
   private static boolean holdsBodyByAbility(String reason) {
      return reason != null && (reason.equals("time is stopped") || reason.equals("asleep"));
   }

   /**
    * True when this damage is a body being thrown rather than a body being hit.
    *
    * <p>Two independent readings, because the explosion tag alone is not enough. The tag is the
    * honest one and covers TNT, creepers, beds and fireballs. The wind charge is read by name,
    * because a wind charge is the one explosion in the game whose whole purpose is the shove and
    * not the damage - its blast is small and its launch is enormous, and a player firing one at
    * their own feet takes almost nothing from it while arriving forty blocks away, which is exactly
    * the report this is here to answer. Read off the registry path so both the player's wind charge
    * and the breeze's own are covered without naming either class.
    */
   public static boolean launchSource(net.minecraft.world.damagesource.DamageSource source) {
      if (source == null) {
         return false;
      }
      if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
         return true;
      }
      // The wind charge is read by name twice over, and it needs both readings, because it is
      // neither an explosion nor a projectile - it is a thing the game has a damage type of its
      // own for and no tag that recognises. The TYPE is what tells us the kind of damage regardless
      // of who caused it, which matters for the case the entity check cannot see: a wind charge
      // whose thrower has since died, or one fired by a dispenser, arrives with no entity on it at
      // all.
      //
      // Read from the registry key and NOT from {@code getMsgId()}, which is the trap here: a
      // wind charge's message id is "mob", the same as a mob's own projectile, so a check written
      // against the message id recognises every bow shot in the game and no wind charge at all.
      net.minecraft.core.Holder<net.minecraft.world.damagesource.DamageType> kind = source.typeHolder();
      if (kind != null && kind.unwrapKey().map(key -> key.identifier().getPath()).orElse("").contains("wind_charge")) {
         return true;
      }
      Entity direct = source.getDirectEntity();
      if (direct == null) {
         direct = source.getEntity();
      }
      return direct != null && launchName(direct).contains("wind_charge");
   }

   /** What threw the body, for the stand-down line and for the record. */
   private static String launchName(net.minecraft.world.damagesource.DamageSource source) {
      Entity direct = source == null ? null : source.getDirectEntity();
      if (direct == null && source != null) {
         direct = source.getEntity();
      }
      return direct == null ? "an explosion" : launchName(direct);
   }

   private static String launchName(Entity entity) {
      try {
         return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
      } catch (Throwable t) {
         return "an explosion";
      }
   }

   /** Test seam: is this damage a launch, and how long should it stand the checks down for? */
   public static long launchGraceTicks() {
      return LAUNCH_GRACE_TICKS;
   }

   /**
    * True when an operator's own flying is what this body is doing: creative, spectator, or the
    * ability flight the game hands out.
    *
    * <p>This exists for one report, and the report is exact: <i>"it says invalid move in creative
    * while I am flying and not moving."</i> A body that is hovering is not committing an invalid
    * move - but an operator hovering in creative is a body the <i>server</i> keeps repositioning,
    * because every scripted carry, every ability that writes a position, and every correction the
    * module itself issues shows up on that body's clock as a teleport. That is the reading the rule
    * below is built on: "the server has been repositioning this body for N ticks without a break"
    * is a shape a client abusing stand-downs makes, and it is also the shape of a staff member
    * flying around their own world, which is where it was firing.
    *
    * <p>The administrative stand-downs are lifted in test mode, exactly as they are everywhere else
    * in this file: {@code /ff anticheat testmode} is an operator asking to be judged, and a body
    * that has asked to be judged is not excused by being in creative. Checked here rather than
    * folded into {@link #scriptedReason} on purpose - {@code scriptedReason} answers "what is
    * driving this body", and it answers it first for the server's own writes, so a flying operator
    * reads as "the server moved them" and never reaches the flight branches at all.
    */
   /**
    * The same question, asked in public: is this body an operator's flight rather than a player's?
    *
    * <p>Exposed because it is now half of why the correction rule stays quiet, and "the rule does
    * not fire on a creative player" is a property worth a test rather than a comment. It is a
    * question about the game's own state - a game mode, an ability flag - and not about anything the
    * client sent, which is exactly why it is safe to answer without a packet in hand.
    */
   public static boolean adminStandDown(ServerPlayer player) {
      return adminFlight(player);
   }

   private static boolean adminFlight(ServerPlayer player) {
      if (player == null || TEST_MODE.contains(player.getUUID())) {
         return false;
      }
      try {
         return player.getAbilities().flying || player.isCreative() || player.isSpectator();
      } catch (Throwable t) {
         // A body whose abilities cannot be read is not assumed to be an operator - the finding
         // stays available, which is the safe direction for a rule that only ever flags.
         return false;
      }
   }

   private static String dimensionOf(ServerPlayer player) {
      try {
         return player.level().dimension().identifier().toString();
      } catch (Throwable t) {
         return "";
      }
   }

   /**
    * When the stand-down in force on this body ends.
    *
    * <p>Two windows meet here and the smaller one wins. The server's is the two seconds
    * from the moment it wrote the position; the client's is the half second after it
    * said it was there, which is when the grace stops being a grace and starts being a
    * place to hide. Before the client has answered, the long one stands - that is the
    * whole false-positive defence - and after it, the short one does.
    */
   private static long graceEnd(Track track) {
      long end = Math.max(track.serverMotionUntil, track.teleportTick + TELEPORT_GRACE_TICKS);
      // The client's answer speaks only for the teleport it answered. A window opened
      // afterwards - a knockback, an arrow, an explosion - is a different window, and an
      // acknowledgement left over from an older teleport must not be able to cut it down
      // to a ten-tick tail. That is the mirror image of the bug this collapses for: a
      // body the server has just shoved would have been left nearly unjudged, on the
      // strength of an answer to a question nobody had asked again.
      //
      // The equality below is the whole of the test. A teleport sets both windows to the
      // same tick; a shove moves only the longer one, so the answer stops applying the
      // moment the two stops being the same window.
      // Compared against the end of the window the teleport itself opened, rather than against
      // a constant, so this cannot quietly stop working if the two grace lengths are ever
      // tuned apart: the question is only whether the long window is still the teleport's.
      boolean answeredThisWindow = track.teleportAckTick > 0L
         && track.teleportWindowEnd >= track.serverMotionUntil;
      if (answeredThisWindow) {
         end = Math.min(end, track.teleportAckTick + TELEPORT_ACK_TAIL_TICKS);
      }
      return end;
   }

   /**
    * The server has written this body's position, by name of the path that did it.
    *
    * <p>One place, because there are now five of them and they must agree: the outbound
    * position packet, {@code ServerPlayer.teleport}, {@code teleportTo} (both overloads),
    * a respawn, and this module's own correction. Until this existed the only recognised
    * teleport was the one the server <i>sent a packet about</i>, which left the quiet ones
    * - a {@code /tp}, a pearl, a wormhole warp, a respawn - looking exactly like a forged
    * position, so the module flagged the honest player arriving and stood down for the
    * hack that asked to be moved.
    */
   private static void noteServerWrite(ServerPlayer player, Track track, long now, String source) {
      noteServerWrite(player, track, now, source, false);
   }

   /**
    * As above, told whether the write was this module's own.
    *
    * <p>The distinction is only about the windows: a teleport somebody else sent ends them,
    * and a correction this module made keeps the one that justified it (see
    * {@link #breakWindows(Track, boolean)}).
    */
   private static void noteServerWrite(
      ServerPlayer player,
      Track track,
      long now,
      String source,
      boolean ours
   ) {
      // The packet layer's idea of where the client is has just been invalidated by the
      // server, and it does not know where to. Dropping its baseline costs one packet of
      // judgement and is the difference between a teleport being a teleport and a
      // teleport being the next forgery.
      PacketEngine.forgetPosition(player.getUUID());
      track.serverMotionUntil = now + SERVER_MOTION_GRACE_TICKS;
      track.teleportWindowEnd = track.serverMotionUntil;
      track.teleportTick = now;
      track.teleportSource = source;
      // The previous teleport's answer does not speak for this one.
      track.teleportAckTick = 0L;
      track.airTicks = 0;
      track.noFallTicks = 0;
      track.airArcFell = false;
      track.speedTicks = 0;
      track.fallActive = false;
      track.fallTicks = 0;
      track.predictionTicks = 0;
      track.predictedDrift = 0.0;
      track.predictionVelocity = 0.0;
      track.riseTicks = 0;
      track.descentTicks = 0;
      track.waterWalkTicks = 0;
      track.noSlowTicks = 0;
      track.arcActive = false;
      track.arcTicks = 0;
      track.arcDistance = 0.0;
      track.knockPending = false;
      track.knockExpected = 0.0;
      track.gainEvents.clear();
      track.hopEvents.clear();
      if (!ours) {
         track.clearSpeedWindow();
      }
   }

   /**
    * The client answered the server's position with a packet of its own.
    *
    * <p>This is the moment the grace becomes a hole, and it is why it is read here rather
    * than anywhere else: the question "has this body arrived yet" is answered by the
    * client's own acknowledgement, which the packet layer is the only place that sees.
    * After it, the stand-down is a few ticks of latency and no more, so a player who
    * chains teleports - and a client that has worked out that being teleported is worth
    * two seconds of unjudged movement - gets a tenth of a second per teleport instead of
    * two. Nothing about the honest case changes: before the acknowledgement the long
    * window is still in force, which is what the false positive needed.
    */
   private static void noteTeleportAcknowledged(ServerPlayer player) {
      Track track = track(player);
      long now = tickOf(player);
      if (track.teleportAckTick != 0L || now - track.teleportTick > SERVER_MOTION_GRACE_TICKS) {
         return;
      }
      track.teleportAckTick = Math.max(now, track.teleportTick);
   }

   /**
    * Everything a discontinuity invalidates, without granting anything for it.
    *
    * <p>The half of {@link #resetForTeleport} that a step nobody can explain is entitled
    * to: the windows, the airborne run, the fall the body was in the middle of and the
    * prediction all end, because none of them can survive a jump from one place to
    * another. What it does not get is the stand-down or the safe spot, both of which are
    * for a body the <i>server</i> moved.
    */
   private static void breakWindows(Track track) {
      breakWindows(track, false);
   }

   /**
    * As above, with one window optionally left alive.
    *
    * <p>The exception exists for exactly one caller: this module's own correction. Nothing
    * else that reaches here is allowed to keep the speed window, because nothing else knows
    * how far the body was moved or why - and a window that survives an unexplained jump is a
    * window measuring movement across two places. A correction does know: the write path
    * records the delta and re-anchors the window on the place the body was put, so the window
    * still describes the player's own path, and the check keeps the evidence that justified
    * the correction instead of spending the next second re-earning it. Without this, a
    * correction was indistinguishable from deleting the case against the player - the more
    * thoroughly a hack was corrected, the less of the record survived.
    */
   private static void breakWindows(Track track, boolean keepSpeedWindow) {
      track.speedTicks = 0;
      track.airTicks = 0;
      track.noFallTicks = 0;
      track.airArcFell = false;
      track.groundedBefore = false;
      track.hasPreviousStep = false;
      track.gainEvents.clear();
      track.predictionTicks = 0;
      track.predictedDrift = 0.0;
      track.predictionVelocity = 0.0;
      track.fallActive = false;
      track.fallTicks = 0;
      if (!keepSpeedWindow) {
         track.clearSpeedWindow();
      }
   }

   /** Everything a body's stand-down resets: one place, so no check can be forgotten. */
   private static void resetForTeleport(Track track, long now, double x, double y, double z) {
      resetForTeleport(track, now, x, y, z, false);
   }

   /** As above, keeping the speed window for a discontinuity this module caused itself. */
   private static void resetForTeleport(
      Track track,
      long now,
      double x,
      double y,
      double z,
      boolean keepSpeedWindow
   ) {
      // The shared half is a call, not a copy. Three lists of "everything a discontinuity
      // invalidates" had grown up in this file and they had already drifted apart once; a
      // window that survives a teleport is a window measuring movement across two places.
      breakWindows(track, keepSpeedWindow);
      track.teleportTick = now;
      track.setSafe(x, y, z);
   }

   /**
    * True when a landing was legitimately soft, by any of the ways this mod and
    * the game provide.
    *
    * <p>Every one of these is a reason a player really can fall eight blocks and
    * walk away, and every one of them is cheap to read. A no-fall check that does
    * not excuse them is a check that flags hay bales, slime boots, elytra and the
    * mod's own chrono guard, which is how a check gets switched off for good.
    */
   private static boolean landingCushioned(ServerPlayer player, ServerLevel level) {
      if (player.isInWater()
         || player.isInLava()
         || player.onClimbable()
         || player.getVehicle() != null
         || player.isFallFlying()
         || player.hasEffect(MobEffects.LEVITATION)
         || player.hasEffect(MobEffects.SLOW_FALLING)
         || player.isSpectator()
         || player.isCreative()
         || player.getAbilities().flying
         || TimeLordManager.isFallGuarded(player)
         || ModItems.isSlimeBoots(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))
         || CustomEnchantments.levelOf(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST), CustomEnchantments.SAFE_LANDING) > 0) {
         return true;
      }

      for (double drop : new double[]{0.1, 1.0}) {
         BlockState below = level.getBlockState(BlockPos.containing(player.getX(), player.getY() - drop, player.getZ()));
         if (below.is(Blocks.WATER)
            || below.is(Blocks.SLIME_BLOCK)
            || below.is(Blocks.HAY_BLOCK)
            || below.is(Blocks.POWDER_SNOW)
            || below.is(Blocks.COBWEB)
            || below.is(Blocks.SCAFFOLDING)
            || below.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
            return true;
         }
      }
      return false;
   }

   /** Any collision shape within a block and a half under the player's feet. */
   /**
    * The four airborne checks, in one place because they share one precondition: the
    * player's own feet moved the body this tick.
    *
    * <p>Everything that serves a body from above - a teleport, a knockback, a firework
    * dive, a shove out of a boss's hands - arrives here as {@code scripted}, and the
    * answer is to <b>break</b> the measurements rather than skip a tick. A knockback
    * mid-jump that merely paused a tally would be counted as part of the jump that
    * carried it, which is how a movement check ends up flagging a player for being hit.
    *
    * <p>The three shape rules the specification asks for are all here: each check reads
    * the actual block under the body rather than assuming one, each takes its allowance
    * from the same model the ground speed check uses, and each needs a pattern rather
    * than an instant - a full airborne arc, two mid-air re-jumps, twelve ticks of walking
    * on water. None of them fires on one strange tick.
    */
   private static void arcAndWater(
      ServerPlayer player,
      Track track,
      long now,
      boolean scripted,
      boolean grounded,
      double horizontal,
      double dy,
      double y,
      double ticksPerSample
   ) {
      double previousStep = track.lastHorizontal;
      track.lastHorizontal = horizontal;
      // What one of this server's samples is worth in the client's own ticks - the same
      // correction the speed, rise and fall windows are read through. An arc is measured in
      // samples; the model it is judged against is quoted per client tick.
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));

      if (scripted) {
         track.arcActive = false;
         track.arcTicks = 0;
         track.arcDistance = 0.0;
         track.descentTicks = 0;
         track.hopEvents.clear();
         track.waterWalkTicks = 0;
         return;
      }

      boolean hasGround = hasGroundBelow(player, y);

      // ---------------------------------------------------------------- long jump
      if (grounded) {
         // The arc is judged once, when it ends. That is what makes this the LENGTH of a
         // jump rather than the speed of one - which is the thing a long-jump hack
         // changes, and the thing a per-tick speed check cannot see because each
         // individual tick of it is unremarkable.
         // The arc is counted in samples and the model it is judged against is quoted per
         // client tick, so its length is read as what it is worth in the client's own ticks.
         // On a server behind by half a sprint-jump's twelve ticks arrive as six samples, and
         // a length judged against six ticks is half the honest allowance - an honest long
         // jump refused on exactly the servers that can least afford the correction.
         double arcClientTicks = track.arcTicks * scale;
         if (track.arcActive && arcClientTicks >= LONGJUMP_MIN_TICKS) {
            // The allowance is the model OR what the body was actually doing when it left
            // the ground, whichever is larger. That one line is what keeps an ice take-off,
            // a Speed potion, a knockback they walked out of and a sprint off a ledge on the
            // legitimate side: each of them raises its own ceiling instead of tripping it.
            double modelled = MovementPhysics.steadyStep(track.arcSlip, track.arcFactor, true);
            double allowance = MovementPhysics.airborneAllowance(
               Math.max(modelled, track.arcTakeoffStep), (int)Math.ceil(arcClientTicks)
            );
            if (track.arcDistance > allowance) {
               flag(
                  player,
                  LONG_JUMP,
                  round(track.arcDistance)
                     + " blocks across "
                     + (int)Math.ceil(arcClientTicks)
                     + " airborne ticks (a sprint-jump of that length is "
                     + round(allowance)
                     + ")",
                  Math.min(1.0, (track.arcDistance - allowance) / Math.max(0.5, allowance))
               );
               correct(player, track, LONG_JUMP);
            }
         }
         track.arcActive = false;
         track.arcTicks = 0;
         track.arcDistance = 0.0;
      } else {
         if (!track.arcActive) {
            track.arcActive = true;
            track.arcTicks = 0;
            track.arcDistance = 0.0;
            // Measured, not assumed: the surface they left from and their own speed
            // attribute, so this cannot drift away from the game the way a fixed number
            // would.
            track.arcSlip = slipperiness(player, true);
            track.arcFactor = movementFactor(player);
            track.arcTakeoffStep = Math.max(previousStep, 0.0);
         }
         track.arcTicks++;
         track.arcDistance += horizontal;
      }

      // --------------------------------------------------------------- bunny hop
      // Vanilla applies a jump only from the ground, so a body that descends and then
      // rises without touching anything has been jumped again by something. One of those
      // is a server shove; two inside three seconds is an auto-jump macro.
      if (grounded) {
         track.descentTicks = 0;
         track.hopEvents.clear();
      } else if (dy < -0.02) {
         track.descentTicks++;
      } else if (dy > 0.02 && track.descentTicks >= BUNNYHOP_MIN_DESCENT && !stepUpSupport(player, y)) {
         track.descentTicks = 0;
         track.hopEvents.addLast(now);
         while (!track.hopEvents.isEmpty() && now - track.hopEvents.peekFirst() > BUNNYHOP_WINDOW) {
            track.hopEvents.removeFirst();
         }
         if (track.hopEvents.size() >= BUNNYHOP_EVENTS) {
            flag(
               player,
               BUNNY_HOP,
               track.hopEvents.size() + " mid-air re-jumps in " + BUNNYHOP_WINDOW / 20 + "s - a jump applied again inside a fall"
            );
            correct(player, track, BUNNY_HOP);
            track.hopEvents.clear();
         }
      }

      // ------------------------------------------------------------------- jesus
      // Walking on water is NOT "standing on water" - a player held up by a liquid
      // surface is a float, and the flight check already owns hovering. The name is about
      // movement, so the check requires movement: height has to hold still AND the body
      // has to be going somewhere sideways. Vanilla's own lily pad is excused, because a
      // lily pad is the one block a player can appear to stand on while the server still
      // counts them in the water.
      // Do not require `!hasGround` here. Shallow water normally has a solid
      // bottom within the old one-and-a-half-block probe, which made a player
      // walking on the water surface look grounded before Jesus was evaluated.
      // The immediate water/fluid probe is the relevant fact; swimming is already
      // excluded by scriptedMovement and lily pads are excluded by waterBelow.
      boolean overWater = waterBelow(player, y);
      if (!grounded && overWater && Math.abs(dy) < JESUS_DRIFT && horizontal > JESUS_STEP) {
         track.waterWalkTicks++;
         if (track.waterWalkTicks >= JESUS_TICKS) {
            flag(
               player,
               JESUS,
               "walked " + track.waterWalkTicks + " ticks across a water surface without entering it"
            );
            correct(player, track, JESUS);
            track.waterWalkTicks = 0;
         }
      } else {
         track.waterWalkTicks = 0;
      }
   }

   /**
    * True when something beside or beneath the player's feet could have stepped them up.
    *
    * <p>This is the false positive the bunny-hop check would otherwise have, and it is worth
    * being precise about which one: <b>falling down a stairwell</b>. A body dropping from
    * step to step descends, descends, then gets caught by the next block and rises - which is
    * exactly the shape descend-then-rise that a mid-air re-jump has, produced entirely by the
    * world. The same is true of any raised step height, modded or configured.
    *
    * <p>The fix is not an exemption, because an exemption cannot be tuned per server and would
    * have had to be written as "trust this mod". It is to ask the world the question the
    * assumption was standing in for: is there anything here that a step-up could have used?
    * The probe uses the player's <b>own</b> {@code maxUpStep}, so a raised step height raises
    * its own tolerance instead of needing anybody to declare anything.
    *
    * <p>Fails open on any error, like every geometry read in this package: a check that cannot
    * see the world must not convict with it.
    */
   private static boolean stepUpSupport(ServerPlayer player, double y) {
      if (!(player.level() instanceof ServerLevel level)) {
         return true;
      }
      try {
         if (hasGroundBelow(player, y)) {
            return true;
         }
         // With the step-assist allowance off, only vanilla's own step height is honoured -
         // which is what makes the toggle mean something rather than being decoration.
         double step = QolCompat.on(QolCompat.STEP_ASSIST) ? Math.max(0.6, player.maxUpStep()) : 0.6;
         BlockPos base = BlockPos.containing(player.getX(), y, player.getZ());
         for (BlockPos column : new BlockPos[]{base, base.north(), base.south(), base.east(), base.west()}) {
            for (double probe = y - 0.1; probe <= y + step; probe += 0.25) {
               BlockPos at = BlockPos.containing(column.getX() + 0.5, probe, column.getZ() + 0.5);
               if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                  return true;
               }
            }
         }
      } catch (Throwable t) {
         return true;
      }
      return false;
   }

   /**
    * True when the body is stood on something that changes its height mid-step - a stair or a
    * slab.
    *
    * <p>The momentum model judges a grounded tick's acceleration against vanilla's friction,
    * and that model assumes a flat surface. A stair is not: the client resolves the step by
    * moving the body up and forward in the same tick, which the model reads as horizontal speed
    * gained out of nothing - so a player running up a staircase was reported as a speed module.
    * This asks the world which one it is rather than guessing from the numbers, and it fails
    * open, like every geometry read in this package.
    */
   private static boolean onStepLike(ServerPlayer player, double y) {
      if (!(player.level() instanceof ServerLevel level)) {
         return true;
      }
      try {
         BlockPos pos = BlockPos.containing(player.getX(), y - 0.35, player.getZ());
         net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
         return state.is(net.minecraft.tags.BlockTags.STAIRS) || state.is(net.minecraft.tags.BlockTags.SLABS);
      } catch (Throwable t) {
         return true;
      }
   }

   /** Test hook: the surface question the momentum model asks, so the edge case can be pinned. */
   public static boolean onStepLikeForTest(ServerPlayer player, double y) {
      return onStepLike(player, y);
   }

   /**
    * True when the block under the player's feet is water, and is not a lily pad.
    *
    * <p>Read off the fluid state rather than the block, so flowing water, modded water
    * and waterlogged blocks all count without this needing a list of them.
    */
   private static boolean waterBelow(ServerPlayer player, double y) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      BlockPos pos = BlockPos.containing(player.getX(), y - 0.1, player.getZ());
      return level.getFluidState(pos).is(FluidTags.WATER) && !level.getBlockState(pos).is(Blocks.LILY_PAD);
   }

   /**
    * True when a cobweb is holding this body.
    *
    * <p>Asked of the two blocks the body occupies - the feet and the block above them - because
    * a web field is usually one block tall and a player climbs into a shaft or a netted doorway
    * with the web under their head rather than under their feet. Fails open, like every geometry
    * read in this package: a body the module cannot see the world for is not convicted with it.
    */
   private static boolean inWeb(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      try {
         BlockPos feet = player.blockPosition();
         return level.getBlockState(feet).is(Blocks.COBWEB) || level.getBlockState(feet.above()).is(Blocks.COBWEB);
      } catch (Throwable t) {
         return false;
      }
   }

   private static boolean hasGroundBelow(ServerPlayer player, double y) {
      if (!(player.level() instanceof ServerLevel level)) {
         return true;
      }
      for (double drop : new double[]{0.1, 0.6, 1.1, 1.6}) {
         if (hasSupportAt(level, player, y - drop)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Whether anything under the body's <b>footprint</b> is solid, at one depth.
    *
    * <p>Vanilla stands a player on whatever is under any part of their collision box - that is what a
    * box is for - so a probe that reads only the centre reports "nothing under them" for a body
    * standing on the last half-block of a ledge. That is exactly the pose a player takes to look at
    * what is below them: shift at the rim, lean over the edge, and the centre probe was over the drop
    * while both feet were still on the stone. Twelve ticks of it tripped the air-walk check, and the
    * flight check's forty tick window caught players who simply kept standing there, so the two
    * reports this answers were "walking to the edge of a block is flagged as flight" and "it says air
    * walk when I am standing still at the edge".
    *
    * <p>Shrinking the box - the first attempt at this - was the wrong direction, and the field report
    * that came back was a body <b>crouched at the very edge</b>: the probe samples were pulled two
    * centimetres inward, so a player whose collision box rested on the last centimetre of a block had
    * every sample inside the box land over the void, the world was read as holding nothing, and once
    * again the edge was flight. The box is now sampled at its <b>full</b> half-width - the box vanilla
    * actually collides with, nothing shrunk away - and on nine points rather than five (the centre,
    * the four corners and the middle of each edge), so a support smaller than the gap between two
    * corners can no longer fall between them. A block under any part of the footprint is support.
    */
   private static boolean hasSupportAt(ServerLevel level, ServerPlayer player, double y) {
      double half = player.getBbWidth() / 2.0;
      double[] xs = {0.0, half, -half, half, -half, half, -half, 0.0, 0.0};
      double[] zs = {0.0, half, half, -half, -half, 0.0, 0.0, half, -half};
      for (int i = 0; i < xs.length; i++) {
         BlockPos pos = BlockPos.containing(player.getX() + xs[i], y, player.getZ() + zs[i]);
         if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return true;
         }
      }
      return false;
   }

   /**
    * How far the nearest solid thing under the body is, in blocks.
    *
    * <p>Stepped rather than ray-cast because the caller only ever asks whether it is
    * beyond {@link #NO_FALL_CLAIM_HEIGHT}, and a quarter-block grid answers that with a
    * number that can be printed in an alert.
    */
   private static double supportDepth(ServerPlayer player, double y) {
      if (!(player.level() instanceof ServerLevel level)) {
         return 0.0;
      }
      for (double drop = 0.1; drop <= NO_FALL_CLAIM_HEIGHT + 4.0; drop += 0.25) {
         if (hasSupportAt(level, player, y - drop)) {
            return drop;
         }
      }
      return NO_FALL_CLAIM_HEIGHT + 4.0;
   }

   /**
    * Whether the body's feet are actually touching something, whichever way the packet
    * reported it.
    *
    * <p>This is {@link #hasImmediateGroundBelow} asked of the whole footprint rather than
    * the centre column, because a landing on the last half-block of a ledge puts the
    * centre over the drop without moving the feet off the stone - the same pose the
    * footprint was introduced for. The eight centimetres below the feet is the collision
    * shape itself and not a step: a body standing on a slab, a stair or a path reads true,
    * and a body a hand's width above any of them reads false.
    */
   private static boolean feetOnSomething(ServerPlayer player, double y) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      try {
         return hasSupportAt(level, player, y - 0.08);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Something solid within {@code reach} of the feet, footprint and all.
    *
    * <p>Deliberately bounded where {@link #hasGroundBelow} is generous: that one answers "would the
    * game call this standing", which is a question about the next block and a half of air. This one
    * answers "is this body a jump off the floor", and a body a block up is not - which is the whole
    * of what keeps the hop landing from becoming a licence to hover.
    */
   private static boolean aboveSupport(ServerPlayer player, double y, double reach) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      try {
         for (double drop = 0.08; drop <= reach; drop += 0.2) {
            if (hasSupportAt(level, player, y - drop)) {
               return true;
            }
         }
         return false;
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * The support directly under the feet, not a step, stair or floor several blocks
    * below. This narrower probe is for critical-ground only: a legitimate jumper is
    * above ground, while a ground-spoof client is reporting airborne with its feet
    * still sitting on the collision shape.
    */
   private static boolean hasImmediateGroundBelow(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      BlockPos pos = BlockPos.containing(player.getX(), player.getY() - 0.08, player.getZ());
      return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
   }

   /**
    * The only place this module is allowed to write a player's body.
    *
    * <p>Every write here has the same shadow: the server reports it straight back. A write
    * to the velocity sets {@code hurtMarked}, and vanilla's entity tracker answers that by
    * sending the player a {@code ClientboundSetEntityMotionPacket} <i>themselves</i>; a
    * write to the position sends a position packet. Both arrive at the outbound hooks
    * looking exactly like somebody else moving the player, and a check that believes them
    * stands down for the two seconds that follow - so the module's own correction becomes
    * the module's own blind spot, exactly when it has the most reason to be watching.
    *
    * <p>Three instances of that were found by hand before this existed (a clamp that hid
    * the speed it had just caught, a correction counted as the server constantly
    * repositioning a body, and a refunded knockback read as a fresh shove). The point of
    * this method is that the fourth cannot be written: the body is moved here with
    * arguments rather than through a callback, so the mutating calls themselves are in
    * this one method, and a self-test reads the compiled package and fails if any other
    * method names one of them. That check is what makes this a property of the code rather
    * than a convention - see {@code anticheat.only-one-method-writes-a-body}.
    *
    * @param reason what to call this write in {@code /ff anticheat why}, in the words a
    *        moderator should read - it is the story of the stand-down, so it must name
    *        the module rather than the server API it went through
    * @param dimension the level to move the body into, or null to leave it where it is
    * @param where the place to put the body, or null for a velocity write. A position
    *        write is owed the arrival stand-down, because only a position write requires
    *        the client to answer with a packet before the body can be judged again; a
    *        velocity write is not a place, so there is nothing to arrive at
    * @param velocity the velocity to leave the body with
    */
   private static void writeBody(ServerPlayer player, Track track, String reason, ServerLevel dimension, Vec3 where, Vec3 velocity) {
      long now = tickOf(player);
      // Read before the write, because it is the delta the speed window is re-anchored by.
      double dx = where == null ? 0.0 : where.x - player.getX();
      double dz = where == null ? 0.0 : where.z - player.getZ();
      track.selfWrites++;
      track.selfWriteReason = reason;
      // Recorded before the write rather than after, because a position write lands in the
      // server's own teleport API and its hook has no way to know who called it - so the
      // attribution has to be waiting there. The name a moderator reads should never be
      // "TeleportCommand" when it was this module.
      track.selfWriteTick = now;
      boolean moves = where != null;
      if (moves) {
         if (dimension != null && dimension != player.level()) {
            player.teleport(new TeleportTransition(
               dimension, where, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
            ));
         } else {
            player.teleportTo(where.x, where.y, where.z);
         }
      }
      player.setDeltaMovement(velocity);
      player.hurtMarked = true;
      // The entity tracker runs on the next entity tick, so the echo is a tick or two
      // away, and the tick loop must not count it as somebody else driving this body.
      track.selfVelocityTick = now;
      // How long the tick loop must refuse to count this as somebody else driving the body.
      // A velocity write is ours for the two ticks the tracker takes to relay it. A position
      // write is ours for the whole arrival window it opens, because that window is entirely
      // this module's doing: counting it as "the server keeps repositioning this player"
      // would make a body the module corrects twice file a finding about the module's own
      // corrections, which is the engine answering itself.
      track.correctionUntil = Math.max(
         track.correctionUntil, now + (moves ? SERVER_MOTION_GRACE_TICKS : SELF_VELOCITY_TICKS)
      );
      if (moves) {
         track.serverMotionUntil = now + SERVER_MOTION_GRACE_TICKS;
         track.teleportWindowEnd = track.serverMotionUntil;
         track.teleportTick = now;
         track.teleportSource = reason;
         // The previous teleport's answer does not speak for this one.
         track.teleportAckTick = 0L;
         // How long the movement checks must treat this as this module's own doing rather
         // than as the server moving a body it does not control: a correction is not a
         // teleport somebody sent, and the difference decides whether the windows are kept
         // or thrown away - see the speed block in {@link #movement}.
         track.ownPositionUntil = now + SERVER_MOTION_GRACE_TICKS;
         // ...and the speed window is re-anchored on the place the body actually went, so a
         // correction costs the check its evidence only for as long as it takes the client
         // to answer. This is not bookkeeping: throwing the window away on every correction
         // was how a body the module was correcting stayed undetected - the more thoroughly
         // it was corrected, the less of the evidence that justified the correction survived.
         track.shiftSpeedWindow(dx, dz);
      }
   }

   /**
    * Moves a body back to the last place it stood on its own feet.
    *
    * <p>Returns whether it actually moved anything, because the caller has evidence to
    * spend and must not spend it on a correction the cooldown declined to make.
    *
    * <p>A correction is itself a teleport, and a teleport is itself something the movement
    * check has to stand down for - but it is a teleport <i>this module performed</i>, so it
    * is marked as such twice over: the stand-down applies, and the window that counts
    * continuous server-driven movement ignores it, because a body being corrected for six
    * seconds is this module failing to catch up with itself, not a client refusing to move.
    */
   private static boolean setback(ServerPlayer player, Track track, String check) {
      if (!track.hasSafe) {
         return false;
      }
      long now = tickOf(player);
      if (now - track.setbackTick < AntiCheatPolicy.setbackCooldown()) {
         return false;
      }
      track.setbackTick = now;
      track.corrections++;
      // The speed window is deliberately <i>not</i> thrown away here, and that is a change
      // worth naming: the window is a record of the player's own path, and the write below
      // re-anchors every sample on the place the body was put, so the distances between them
      // still describe the movement that justified the correction. Clearing it here was how a
      // body being corrected stayed undetected - each correction spent the evidence that
      // produced it and handed the client a free second to keep hacking in, which is the
      // opposite of what a correction is for.
      // One write, through the one path, and it declares all three of the things a
      // corrected body needs declared: where it is, that the motion packet which follows
      // is ours, and that the client is owed a window to catch up in.
      writeBody(player, track, "this module's own correction", null, new Vec3(track.safeX, track.safeY, track.safeZ), Vec3.ZERO);
      // ...and the packet layer is told where, not merely that something happened. This is
      // the half of the loop that put a corrected player back on the next honest packet:
      // the baseline was a position they had never been.
      PacketEngine.reanchor(player.getUUID(), track.safeX, track.safeY, track.safeZ);
      Chat.raw(player, "&c§l[AC]&r &7You were moved back &8(" + check + "&8).");
      return true;
   }

   /** How long after a swing the aim is still "in use" and worth judging. */
   private static final long ROTATION_ATTACK_TICKS = 40L;

   /**
    * The correction a <i>pattern</i> check is allowed to make: stop the body
    * gaining, without moving it.
    *
    * <p>This is the answer to the complaint that honest sprinting and strafing were
    * being rubber-banded, and the reasoning is worth writing down because the
    * previous behaviour looked defensible. Every heuristic movement check judges a
    * window, an arc or a rate - a statement about the last second, not about where
    * the body is - and teleporting a body backwards on the strength of one is how an
    * anticheat becomes the thing that is visibly broken. A player on a bad
    * connection produces exactly the same bad window a speed module does, and does it
    * far more often, so the correction has to be one that is harmless when the window
    * was wrong.
    *
    * <p>Clamping the horizontal velocity is exactly that. It caps what the body has to
    * what the model allows and does nothing else: no position is written, no direction
    * is changed, no packet is contradicted. If the finding was wrong the player never
    * notices; if it was right the module stops gaining rather than being thrown
    * backwards, which means the honest-player complaint and the cheater's cost have
    * the same fix.
    *
    * <p>The teleport is still available for a check whose evidence is a position vanilla
    * cannot produce at all (the packet layer's {@code invalid-move}, which calls
    * {@link #setback} directly), for an operator who deliberately turns
    * {@link ModConfig#anticheatHardSetback} on to restore the old behaviour, and - since
    * the report that a pattern check never moves anybody - for a pattern that has stopped
    * being a pattern. See {@link #maybeConfirmedSetback}, which is the only way a check in
    * {@link #NEVER_TELEPORTS} reaches {@link #setback} and which needs several separate
    * rounds of evidence before it says anything.
    */
   private static void correct(ServerPlayer player, Track track, String check) {
      Track.Violation violation = track.of(check);
      boolean patternOnly = NEVER_TELEPORTS.contains(check);
      if (patternOnly && !ModConfig.anticheatHardSetback()) {
         // The clamp is only counted as a correction when it actually changed the body -
         // a body already at the ceiling was told nothing, so it has not been corrected
         // and it must not build toward the escalation below on evidence nobody acted on.
         if (clampHorizontal(player, track)) {
            violation.clamped++;
         }
         maybeConfirmedSetback(player, track, check, violation);
         return;
      }
      // Both halves of the bar, and the level one is high: a correction that needs
      // two independent windows of evidence cannot be produced by one lag spike.
      if (violation.level < AntiCheatPolicy.correctLevel()
         || violation.confidence < AntiCheatPolicy.alertConfidence()) {
         clampHorizontal(player, track);
         return;
      }
      // The bar above is about how much has been accumulated, and accumulation is the
      // wrong question once a player has already been moved. What a correction needs is
      // evidence it has not already acted on, so a level that has been sitting above the
      // bar for the last half-minute - decaying at two hundredths a tick, a hundred
      // seconds from the ceiling - cannot be spent over and over on the same five
      // seconds of behaviour. This is the half of the fix to repeated corrections that
      // no threshold can provide, and it is why the level cleared below is not a bribe:
      // a player who is still hacking re-earns it in two findings, and a player who has
      // stopped cannot earn it at all.
      if (violation.count <= violation.corrected) {
         clampHorizontal(player, track);
         return;
      }
      // A body that cannot be safely moved (nothing on the record to move it to, or a
      // correction inside the cooldown) must not spend its evidence either.
      if (!setback(player, track, check)) {
         clampHorizontal(player, track);
         return;
      }
      violation.corrected = violation.count;
      violation.level = 0.0;
      // A real refusal, on the record: the client was told to take a position it did not claim,
      // which is the one thing the constant-correction rule is about. See SERVER_GRACE_REFUSALS.
      track.refusals++;
      track.refusalAt = tickOf(player);
   }

   /**
    * Caps the body's horizontal speed to what the model allows for the surface it is
    * on, leaving its direction alone.
    *
    * <p>Read from the player's own attribute and the block actually under them, so a
    * Speed potion, a modded speed attribute and a modded slippery block all raise
    * their own cap. Applied to the velocity rather than the position because a velocity
    * the server sets is a thing the client is told about, which is what makes this the
    * correction that cannot desync anybody.
    */
   private static boolean clampHorizontal(ServerPlayer player, Track track) {
      try {
         Vec3 motion = player.getDeltaMovement();
         double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
         if (!(horizontal > 1.0E-4)) {
            return false;
         }
         double slip = slipperiness(player, player.onGround());
         double cap = MovementPhysics.steadyStep(slip, movementFactor(player), !player.onGround());
         // A little over the model, so the clamp is a gate and not a drag: a body at
         // the ceiling is never touched, and one above it is brought to the ceiling
         // rather than below it.
         cap *= MovementPhysics.TOLERANCE * 1.05;
         if (horizontal <= cap) {
            return false;
         }
         double scale = cap / horizontal;
         // Declared, because the write below and the motion packet it produces are one act:
         // the packet is about to go back to the client, and the outbound hook must read it
         // as this module's own doing rather than as the server pushing the player around.
         // It is not a place, so it does not open an arrival window.
         writeBody(player, track, "this module's own speed clamp", null, null, new Vec3(motion.x * scale, motion.y, motion.z * scale));
         return true;
      } catch (Throwable t) {
         // Fails open, like every correction: a clamp that throws must not be the
         // reason somebody cannot walk.
         FortuneFavorsMod.LOGGER.debug("[FF-AC] velocity clamp failed", t);
         return false;
      }
   }

   /**
    * How many findings, and how many unheeded clamps, make a pattern a fact.
    *
    * <p>Eight and three. Both numbers are about the same idea: a pattern check's single
    * window is a statement about the last second, and only its <i>repetition</i> is a
    * statement about the player. Eight findings is at least three separate evidence
    * windows for every movement check here, and three clamps is three occasions on which
    * the check already had that much evidence and answered it by refusing the body any
    * more speed - and the body produced more anyway.
    */
   public static final int CONFIRMED_FINDINGS = 8;
   public static final int CONFIRMED_CLAMPS = 3;
   /**
    * How long a body must have been judged cleanly before its word counts for more.
    *
    * <p>Ten minutes of judged movement with nothing found. This is the number that
    * answers "the anticheat keeps happening to players who are not hacking": a body that
    * has been watched for ten minutes and produced nothing is not a hacker having a bad
    * second, and the module should not be able to spend a correction on one messy
    * moment of theirs. Note what it deliberately is <i>not</i> - it is not immunity. A
    * module still reaches the higher bar within a second or two of actually cheating,
    * because findings arrive at twelve to twenty a second, and every finding is still
    * recorded and announced at the lower bar throughout.
    */
   public static final long CLEAN_RECORD_TICKS = 12_000L;
   /**
    * The extra findings a long-clean body must produce before it is moved.
    *
    * <p>Four. Small on purpose: the clamp still happens at the ordinary bar, so the body
    * never gains what the model does not allow - what moves is only the escalation to a
    * real setback, which is the correction a player can feel.
    */
   public static final int CLEAN_RECORD_PATIENCE = 4;

   /**
    * The findings a body must have on one check before a real setback is even on the table.
    *
    * <p>A function of clean judged time and nothing else, so it can be pinned without a
    * server, and so no other number in the engine can quietly change what "clean" means.
    */
   public static int confirmedFindingsFor(long cleanTicks) {
      return cleanTicks >= CLEAN_RECORD_TICKS ? CONFIRMED_FINDINGS + CLEAN_RECORD_PATIENCE : CONFIRMED_FINDINGS;
   }

   /**
    * How recently the last finding has to have arrived for a pattern to still be a
    * pattern.
    *
    * <p>Two seconds. This is the half of the rule that answers the complaint that players
    * who had <i>stopped</i> kept being put back: a checksum of eight findings and three
    * clamps describes an afternoon, and a correction may only be spent on what the player
    * is doing now.
    */
   public static final long CONFIRMED_RECENCY_TICKS = 40L;

   /**
    * Whether a pattern check has earned the right to move a body.
    *
    * <p>This is the answer to "the correction does not work": a hack that keeps going is
    * clamped, and clamping costs a client nothing because it can re-send the speed it
    * wanted on the next tick. So the module has to be able to escalate, and the shape of
    * the escalation is that it is not an escalation at all - it is the same amount of
    * evidence the level bar already asks for, gathered repeatedly and recently, with the
    * module's own clamped corrections counted as the attempts they are.
    *
    * <p>All five conditions are required. Level and confidence are the policy's own bars,
    * so no setting of them can lower the evidence this needs; count and clamps are the
    * repetition; recency is what stops it being a sentence for something the player has
    * stopped doing. Confidence in particular is discounted by the server's tick health
    * and the player's ping, which is the property that keeps an honest player on a bad
    * connection out of it: their finding cannot reach the confidence bar in the first
    * place.
    *
    * <p>A function of five numbers because it is the rule that decides whether a body
    * gets moved, and a rule like that should be checkable without a server: see
    * {@code anticheat.a-confirmed-pattern-may-move-a-body}.
    */
   public static boolean maySetBackConfirmed(
      int count,
      int clamped,
      double level,
      double confidence,
      long sinceLastFinding
   ) {
      return maySetBackConfirmed(count, clamped, level, confidence, sinceLastFinding, 0L);
   }

   /**
    * The same rule, with the player's clean record as a sixth input.
    *
    * <p>Kept as a separate overload rather than a changed signature so that the older
    * callers and their test keep expressing the same thing: {@code cleanTicks = 0} is
    * "a body we know nothing good about", which is exactly the old behaviour.
    */
   public static boolean maySetBackConfirmed(
      int count,
      int clamped,
      double level,
      double confidence,
      long sinceLastFinding,
      long cleanTicks
   ) {
      return count >= confirmedFindingsFor(cleanTicks)
         && clamped >= CONFIRMED_CLAMPS
         && level >= AntiCheatPolicy.correctLevel()
         && confidence >= AntiCheatPolicy.alertConfidence()
         && sinceLastFinding >= 0L
         && sinceLastFinding <= CONFIRMED_RECENCY_TICKS;
   }

   /**
    * The one way a pattern check earns a real setback, and the last thing it does.
    *
    * <p>The evidence is spent exactly as it is for the checks that always teleported: the
    * count this correction acted on is recorded, so the check cannot be answered twice for
    * the same finding, and the level it used is cleared. A player still hacking re-earns
    * it within a second or two; a player who has stopped cannot re-earn it at all, which is
    * what keeps the report "it kept teleporting me after I stopped" from coming back.
    */
   private static void maybeConfirmedSetback(
      ServerPlayer player,
      Track track,
      String check,
      Track.Violation violation
   ) {
      if (violation.count <= violation.corrected) {
         return;
      }
      long now = tickOf(player);
      if (!maySetBackConfirmed(
         violation.count,
         violation.clamped,
         violation.level,
         violation.confidence,
         now - violation.lastTick,
         track.cleanTicks()
      )) {
         return;
      }
      int clamps = violation.clamped;
      if (!setback(player, track, check)) {
         return;
      }
      violation.corrected = violation.count;
      violation.clamped = 0;
      violation.level = 0.0;
      FortuneFavorsMod.LOGGER.warn(
         "[FF-AC] {} is not a pattern any more on {}: {} findings, {} of them answered by a clamp that did not stop it - moved back",
         check, player.getName().getString(), violation.count, clamps
      );
   }

   // ----------------------------------------------------------------- packets

   /**
    * The inbound packet layer, called from the listener before the server applies
    * anything. See {@link PacketEngine} for what each decision is made of.
    *
    * <p>Returns false when the packet must not be applied. Everything here fails
    * <i>open</i>: a check that throws leaves the packet alone, because the packet
    * layer is the one place where getting it wrong strands a player mid-air.
    */
   public static boolean allowMovePacket(ServerPlayer player, ServerboundMovePlayerPacket packet, boolean teleportPending) {
      movePackets++;
      if (!active(player)) {
         return true;
      }
      try {
         // The missing half of a partial packet is filled the way vanilla fills
         // it - from the position and rotation the server already has - so a
         // rotation-only packet measures no movement at all.
         double x = packet.getX(player.getX());
         double y = packet.getY(player.getY());
         double z = packet.getZ(player.getZ());
         float yaw = packet.getYRot(player.getYRot());
         float pitch = packet.getXRot(player.getXRot());
         long stamp = System.nanoTime();
         // The client answering the server's own position is what ends the stand-down
         // that position earned. Read here because it is the only layer that sees it.
         if (teleportPending) {
            noteTeleportAcknowledged(player);
         }
         // A packet that agrees with where the *server* has this body is not a forged
         // position, whatever the last accepted packet claimed. The two baselines only
         // differ when the server moved the body itself - a rewind, a scripted shove, an
         // ability that put somebody down somewhere and never left a position packet in
         // flight - and the client answering with the place it was just put is the honest
         // thing to do. Measured against the last packet instead, that answer is a jump
         // with nothing to explain it: the "invalid move" and the set-back that landed
         // on players in the middle of somebody else's ability. A cheater cannot use it,
         // because a cheater is not where the server thinks they are.
         boolean agreesWithServer = !teleportPending
            && (packet instanceof ServerboundMovePlayerPacket.Pos || packet instanceof ServerboundMovePlayerPacket.PosRot)
            && Math.abs(x - player.getX()) <= PacketEngine.MAX_PACKET_JUMP
            && Math.abs(y - player.getY()) <= PacketEngine.MAX_PACKET_JUMP
            && Math.abs(z - player.getZ()) <= PacketEngine.MAX_PACKET_JUMP;
         PacketEngine.Verdict verdict = PacketEngine.move(
            player.getUUID(), stamp, x, y, z, yaw, pitch, teleportPending, scriptedMovement(player), agreesWithServer
         );
         boolean allowed = report(player, verdict);
         // The rotation stream is sampled here, at the packet, because this is the only
         // layer that sees the aim as it was sent - by the time the server has applied
         // it, the previous sample is gone. It is fed only while a swing is recent, so a
         // player panning their camera at the scenery is never judged at all.
         if (allowed && !scriptedMovement(player)) {
            Track rotationTrack = track(player);
            boolean fighting = tickOf(player) - rotationTrack.lastAttackTick <= ROTATION_ATTACK_TICKS;
            note(player, rotationTrack.rotation.sample(stamp, yaw, pitch, fighting));
         }
         if (allowed || teleportPending) {
            // A packet the server asked for is never refused: the client is
            // answering our teleport, and dropping the answer strands it.
            return true;
         }
         if (INVALID_MOVE.equals(verdict.check())) {
            // A forged position is answered with the last place the server
            // believed. A dropped packet in a flood is not: 200 corrections a
            // second would be a flood of our own.
            setback(player, track(player), "invalid-move");
         }
         return false;
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat move packet check failed - letting it through", t);
         return true;
      }
   }

   /** A hotbar selection, which vanilla refuses to apply when it is out of range. */
   public static boolean allowCarriedSlot(ServerPlayer player, int slot) {
      if (!active(player)) {
         return true;
      }
      try {
         return report(player, PacketEngine.carriedSlot(slot));
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat carried-item check failed - letting it through", t);
         return true;
      }
   }

   /** A container click, checked against the menu the client actually has open. */
   public static boolean allowContainerClick(ServerPlayer player, ServerboundContainerClickPacket packet) {
      if (!active(player)) {
         return true;
      }
      try {
         // The inventory budget is charged here, at the only door a container click
         // comes through. It was not charged anywhere before this: the bucket
         // existed, the self-test drove it, and no live packet ever reached it - so
         // a shift-drag or a sort was outside the budget entirely. That is fixed
         // rather than documented, and it is the reason
         // {@link QolCompat#QUICK_INVENTORY} exists: charging the bucket is the
         // honest thing to do, and sizing it for the mods players actually run is
         // what keeps the honest thing from being the punishing one.
         PacketEngine.Verdict budget = PacketEngine.window(player.getUUID(), System.nanoTime());
         if (budget != PacketEngine.ALLOW && !report(player, budget)) {
            return false;
         }
         if (player.containerMenu != null && !player.containerMenu.isValidSlotIndex(packet.slotNum())) {
            return report(player, PacketEngine.badSlot(player.getUUID(), System.nanoTime(), packet.slotNum()));
         }
         return true;
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat container check failed - letting it through", t);
         return true;
      }
   }

   /** A swing, an interaction, a block action, a use: all the same budget. */
   public static void onActionPacket(ServerPlayer player) {
      if (!active(player)) {
         return;
      }
      try {
         report(player, PacketEngine.action(player.getUUID(), System.nanoTime()));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat action packet check failed", t);
      }
   }

   /**
    * The client asked to open its wings.
    *
    * <p>Vanilla answers this with {@code tryToStartFallFlying()} and, when the answer is
    * no, stops the glide - and the client does not ask twice, because as far as it is
    * concerned it is already gliding. The one way that happens to an honest player is
    * ordering: the elytra is on the chest as far as the <i>client</i> is concerned, the
    * chest slot the server is holding has not caught up yet, and vanilla's own question -
    * "is there something on this body that can glide?" - is asked a tick too early and
    * answered for good. From then on the player flies and the server has never been told,
    * which is every tick of a very long glide reported as flight and speed.
    *
    * <p>So the request is kept, not decided: {@link #elytra} asks vanilla's own question
    * again for as long as the claim is live, and grants the glide the moment the chest
    * slot can answer it. A claim that never becomes honourable expires, and nothing is
    * granted for it.
    */
   public static void onGlideRequest(ServerPlayer player) {
      if (player == null) {
         return;
      }
      try {
         GLIDE_CLAIMS.put(player.getUUID(), serverTick(player));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat glide claim could not be kept", t);
      }
   }

   /**
    * The wings, either side of the tick the server hears about them.
    *
    * <p>Two jobs, and both of them are about the boundary rather than the glide. While the
    * body is fall flying, the tail is armed - so the ticks before the flag arrives and the
    * ticks after it leaves are read as the wings' work, named, rather than as a body that
    * is too fast and too high. And a claim the server refused is honoured late, by asking
    * vanilla's own question again, so the server and the client end up agreeing about the
    * glide instead of disagreeing about it for its whole length.
    *
    * <p>Nothing here grants movement. Starting a glide the client is not in is not a
    * possibility worth worrying about (vanilla ends it on the first tick the body is on the
    * ground, in water, or unable to glide), and the alternative - judging a player's whole
    * flight as a hack because a slot was a tick late - is the bug this exists to fix.
    */
   public static void elytra(ServerPlayer player) {
      if (player == null) {
         return;
      }
      long now = serverTick(player);
      boolean granted = false;
      try {
         Long claim = GLIDE_CLAIMS.get(player.getUUID());
         if (claim != null) {
            if (now - claim > GLIDE_CLAIM_TICKS) {
               GLIDE_CLAIMS.remove(player.getUUID());
            } else if (!player.isFallFlying() && player.tryToStartFallFlying()) {
               GLIDE_CLAIMS.remove(player.getUUID());
               granted = true;
            }
         }
      } catch (Throwable t) {
         GLIDE_CLAIMS.remove(player.getUUID());
      }
      if (!enabled()) {
         return;
      }
      Track track = TRACKS.get(player.getUUID());
      if (track == null) {
         return;
      }
      if (player.isFallFlying()) {
         track.glideUntil = now + GLIDE_TAIL_TICKS;
         track.lateGlide = false;
      } else if (granted) {
         track.glideUntil = now + GLIDE_TAIL_TICKS;
         track.lateGlide = true;
      }
   }

   /** The server's own tick, for the half of this that runs whether or not the checks do. */
   private static long serverTick(ServerPlayer player) {
      try {
         return player.level().getServer() == null ? tickOf(player) : player.level().getServer().getTickCount();
      } catch (Throwable t) {
         return tickOf(player);
      }
   }

   /**
    * The per-player half of {@link #elytra}, for whoever it can apply to.
    *
    * <p>Everything, while the checks are on, because the tail that keeps a glide's boundary
    * ticks from reading as flight is armed from the gliding itself rather than from a claim.
    * Only the players actually waiting on a refused request otherwise, so a server with the
    * anticheat off pays a map lookup per player and nothing else.
    */
   private static void elytraPass(MinecraftServer server, long now) {
      GLIDE_CLAIMS.values().removeIf(claimed -> now - claimed > GLIDE_CLAIM_TICKS);
      boolean checking = enabled();
      if (!checking && GLIDE_CLAIMS.isEmpty()) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (!checking && !GLIDE_CLAIMS.containsKey(player.getUUID())) {
            continue;
         }
         try {
            elytra(player);
         } catch (Throwable t) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: elytra reconciliation failed for {}", player.getName().getString(), t);
         }
      }
   }

   /** The glide boundary tail, pinned by the self-test. */
   public static long glideTailTicks() {
      return GLIDE_TAIL_TICKS;
   }

   /** The window a refused glide request stays honourable, pinned by the self-test. */
   public static long glideClaimTicks() {
      return GLIDE_CLAIM_TICKS;
   }

   /**
    * An attack packet. Also stamps the swing, which is what the spin evidence
    * needs - and feeds the click-timing window, because how often the button went
    * down is only visible here, at the packet.
    */
   public static void onAttackPacket(ServerPlayer player) {
      attackPackets++;
      if (!active(player)) {
         return;
      }
      long now = System.nanoTime();
      try {
         report(player, PacketEngine.attack(player.getUUID(), now));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat attack packet check failed", t);
      }
      try {
         if (!active(player, AUTOCLICKER)) {
            track(player).clicks.clear();
            return;
         }
         // The weapon's own interval is the whole basis of the check: anything at
         // or above it is a held key or a patient player, and is never recorded.
         double cooldownMs = player.getCurrentItemAttackStrengthDelay() * 50.0;
         note(player, track(player).clicks.click(now, cooldownMs));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat click timing failed", t);
      }
   }

   /**
    * Files a statistic's reading. Kept separate from {@link #flag} because a
    * reading already carries its own weight and only ever arrives corroborated -
    * the statistics refuse to report on one signal, so there is nothing left to
    * discount here.
    */
   private static void note(ServerPlayer player, CombatStats.Reading reading) {
      if (reading == null || reading.check() == null) {
         return;
      }
      flag(player, reading.check(), reading.detail(), reading.weight());
      Chat.raw(player, "&c§l[AC]&r &7That pattern is not one a hand makes.");
   }

   /** One client tick, from the packet that says the client finished one. */
   public static void onClientTickPacket(ServerPlayer player) {
      if (!active(player)) {
         return;
      }
      try {
         report(player, PacketEngine.tickEnd(player.getUUID(), System.nanoTime()));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat client tick check failed", t);
      }
   }

   /** A mod payload or client setting. Counted, never judged. */
   public static void onPayloadPacket(ServerPlayer player) {
      if (!active(player)) {
         return;
      }
      try {
         report(player, PacketEngine.payload(player.getUUID(), System.nanoTime()));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat payload check failed", t);
      }
   }

   // ------------------------------------------------------- the server's own hand

   /**
    * The server just sent this player a position. From here until
    * {@link #SERVER_MOTION_GRACE_TICKS} have passed, wherever they are and
    * however fast they got there is the server's doing, not theirs.
    *
    * <p>This is the correlation section 3 asks for, and it is deliberately
    * triggered by the packet <i>leaving</i> rather than by the jump being
    * observed. Observing the jump means the grace only starts once the player has
    * already made an impossible-looking move - and if the check fires first, the
    * grace was too late. Starting from the send means the window is open before
    * the client could possibly have answered, and it stays open long enough for
    * the answer to arrive over any plausible latency.
    *
    * <p>The position itself is not used. A {@code ClientboundPlayerPositionPacket}
    * may carry absolute coordinates or offsets depending on its relatives set, and
    * reading it would mean re-implementing that resolution to get a number this
    * layer has no use for: the point is <i>that</i> the server moved them, not
    * where to.
    */
   public static void onOutboundTeleport(ServerPlayer player, Vec3 to) {
      if (player == null || !enabled()) {
         return;
      }
      try {
         noteServerWrite(player, track(player), tickOf(player), "a teleport the server sent");
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat outbound teleport hook failed", t);
      }
   }

   /**
    * The server itself wrote this body's position - the half of a teleport that never
    * sent a packet, and therefore the half this module could not see.
    *
    * <p>Called from the server's own teleport API by {@code ServerPlayerTeleportMixin}.
    * A {@code /tp}, an ender pearl, a portal, a wormhole potion, the prison and boss
    * scripted carries and a duel's spawn all arrive here, and none of them used to. What
    * that cost was not a missed detection, it was a false one: the body appeared somewhere
    * it had never walked, no position packet had been watched going out, and the only
    * reading left was that the client had forged it - so the honest player was filed as
    * {@code invalid-move} and answered with a setback, which is the complaint this method
    * exists to fix. A teleport is now correlated with its cause by name, at the source.
    *
    * @param reason what moved them, in the words a moderator should read in
    *        {@code /ff anticheat why}
    */
   public static void onServerTeleport(ServerPlayer player, String reason) {
      if (player == null || !enabled()) {
         return;
      }
      try {
         serverWrites++;
         Track track = track(player);
         long now = tickOf(player);
         // A teleport this module is in the middle of making is not somebody else moving
         // the player, and the moderator reading the reason should not be told the server
         // did something the module did. The truth here is the difference between "a
         // command teleported me" and "the anticheat put you back", and those need
         // opposite responses from whoever reads the ledger.
         boolean ours = now - track.selfWriteTick <= 1L;
         noteServerWrite(
            player,
            track,
            now,
            ours && track.selfWriteReason != null
               ? track.selfWriteReason
               : reason == null ? "a server teleport" : reason,
            ours
         );
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat server teleport hook failed", t);
      }
   }

   /**
    * A body was replaced rather than moved: the player died and respawned.
    *
    * <p>A respawn is the one teleport with nothing in common with the body it replaces.
    * Death can be hundreds of blocks away, in another dimension, in mid-air, and the
    * record of the previous body - where it stood, how long it had been airborne, the
    * fall it was in the middle of - is not a description of the new one. Not resetting it
    * meant every respawn arrived as an unexplained step <i>and</i> a fall that had never
    * started, which is two findings produced by dying. The history of what the player has
    * been failing is kept: dying is a reason to forget where they stood, not a reason to
    * forget what they did.
    */
   public static void onRespawn(ServerPlayer player) {
      if (player == null) {
         return;
      }
      try {
         Track track = track(player);
         long now = tickOf(player);
         double x = player.getX();
         double y = player.getY();
         double z = player.getZ();
         // The next tick anchors on the new body rather than judging the jump to it.
         track.hasLast = false;
         track.hasPreviousStep = false;
         track.groundedBefore = false;
         resetForTeleport(track, now, x, y, z);
         noteServerWrite(player, track, now, "a respawn");
         track.setSafe(x, y, z);
         track.hasSafe = true;
         track.scripted = true;
         track.scriptedReason = "just respawned";
         FortuneFavorsMod.LOGGER.debug(
            "[FF-AC] {} respawned - movement anchored at {}, {}, {}",
            player.getName().getString(), round(x), round(y), round(z)
         );
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat respawn reset failed", t);
      }
   }

   /**
    * The server just sent this player a velocity - knockback, an explosion, a
    * boss shove, a riptide. Two things follow.
    *
    * <p>First, the same movement stand-down a teleport gets, because a player who
    * has been launched is moving in a way their own feet did not choose.
    *
    * <p>Second, and this is the part no server-side check can do alone: the
    * impulse recorded here is what the client was actually <i>told</i>. The
    * knockback check normally measures the impulse from the player's own velocity
    * at the moment of the hit, which is authoritative for a hit but useless for a
    * body that was already moving toward its attacker. When that measurement comes
    * back unusable, this fills the expected travel in instead - so a client that
    * quietly drops the knockback it was sent is still measured against the figure
    * it was given. That is a second, independent source of evidence for the same
    * event, which is what section 28 is for.
    */
   public static void onOutboundVelocity(ServerPlayer player, Vec3 sent) {
      if (player == null || !enabled() || sent == null) {
         return;
      }
      try {
         Track track = track(player);
         long now = tickOf(player);
         // ...unless the velocity in this packet is one this module just wrote. The clamp
         // is not a shove: the body was not launched anywhere, it was told to stop gaining.
         // Reading these as server-driven movement is what made a sustained speed hack
         // undetectable - each finding clamped the body, the tracker sent the clamp back,
         // and the resulting two-second stand-down threw away the speed window that had
         // just caught it. The check blinded itself by detecting.
         if (now - track.selfVelocityTick <= SELF_VELOCITY_TICKS) {
            track.selfVelocityTick = Long.MIN_VALUE / 4;
            track.correctionUntil = now + SELF_VELOCITY_TICKS;
            return;
         }
         track.serverMotionUntil = now + SERVER_MOTION_GRACE_TICKS;
         // A shove that points sharply upward is a LAUNCH rather than a hit, and it is worth the
         // longer stand-down. Nobody is knocked up the way they are knocked sideways: nine tenths
         // of upward velocity in this game came from a wind charge, a firework, a trident or a
         // piston, and whatever it was, the body that just gained it is not flying itself anywhere.
         // This is the branch that catches the launch that carried no damage at all - a wind charge
         // blast whose shove reached a body standing outside its injury radius - because that one
         // never reaches the damage hook and this packet is the only evidence it happened.
         if (!player.isFallFlying() && !player.onGround() && sent.y >= LAUNCH_VERTICAL) {
            track.serverMotionUntil = now + LAUNCH_GRACE_TICKS;
            track.teleportWindowEnd = track.serverMotionUntil;
            track.teleportSource = "thrown upward by something that is not their feet";
            launches++;
         }
         // A hit this check could not measure from the victim's velocity arms the
         // measurement with an expectation of zero, and this packet is where the real
         // figure arrives: it is the velocity the server itself applied, sent to the
         // client, so the component along the impulse is what this body is obliged to
         // travel. Anything else here (an expectation already set, or no knockback
         // pending) is not this packet's business.
         //
         // Only the packet the hit itself produced may fill it in, which is one entity
         // tick's worth of slack: a knockback sets the mark once, so the first motion
         // packet after the damage is that vector. Accepting a later one would let an
         // arrow or an explosion a few ticks afterwards be read as the size of this
         // knockback, and an expectation modelled from a shove pointing somewhere else is
         // an expectation the player would fail.
         if (!track.knockPending || track.knockExpected > 0.0 || now - track.knockStartTick > 2L) {
            return;
         }
         if (!track.hasVel) {
            return;
         }
         // The packet carries the body's whole motion, so the shove is the part of it the
         // hit added - the same subtraction the damage path does, for the same reason.
         double along = (sent.x - track.velX) * track.knockX + (sent.z - track.velZ) * track.knockZ;
         if (!MovementPhysics.knockbackApplied(along)) {
            // A motion packet that shoves nobody: this hit applied no knockback either,
            // and there is no distance to have kept. Stand the measurement down.
            track.knockPending = false;
            return;
         }
         double friction = player.onGround()
            ? MovementPhysics.friction(slipperiness(player, true))
            : MovementPhysics.AIR_FRICTION;
         track.knockExpected = MovementPhysics.knockbackTravel(along, friction, (int)KNOCKBACK_TICKS);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat outbound velocity hook failed", t);
      }
   }

   /**
    * Files a packet verdict: the evidence goes to the combat score when it is
    * combat evidence, the violation goes on the record, and the answer is whether
    * the packet may proceed.
    */
   private static boolean report(ServerPlayer player, PacketEngine.Verdict verdict) {
      if (verdict == null || verdict == PacketEngine.ALLOW) {
         return true;
      }
      if (verdict.aura() > 0.0) {
         track(player).auraScore += verdict.aura();
      }
      if (verdict.check() != null) {
         flag(player, verdict.check(), verdict.detail());
      }
      return !verdict.refuse();
   }

   // ------------------------------------------------------------------ attack

   /**
    * How much interaction range this body's equipment is entitled to that its own
    * attribute is not showing yet.
    *
    * <p>Zero for everybody without a Crab Claw, and - because the correction above runs
    * first - zero for a body whose attribute has already caught up. It is read twice for
    * that reason: once to decide whether the correction is needed, and once after it to
    * find out whether it worked.
    */
   private static double rangePadding(ServerPlayer player, boolean entity) {
      try {
         return EquipmentAttributes.pendingInteractionRange(player, entity);
      } catch (Throwable t) {
         return 0.0;
      }
   }

   /**
    * Vanilla's own range test, with the reach margin and whatever the body's equipment is
    * entitled to that its attribute is not showing yet.
    *
    * <p>The padding is the honest half of this: a Crab Claw that has not been written onto
    * the body yet is still a weapon the server can see on it, and the distance a body may
    * swing over is the range that weapon grants. Nothing here widens for anybody without
    * one - see {@link EquipmentAttributes}, which reads the six worn slots and nothing
    * else - and the correction the caller makes first means this padding is usually zero.
    */
   public static boolean withinEntityReach(ServerPlayer attacker, Entity target) {
      if (target == null || target.isRemoved()) {
         return false;
      }
      return withinEntityReach(attacker, target.getBoundingBox());
   }

   /** The same question about a box, which is the form the self-test can pose without a world. */
   public static boolean withinEntityReach(ServerPlayer attacker, net.minecraft.world.phys.AABB box) {
      return attacker.isWithinEntityInteractionRange(box, REACH_MARGIN + rangePadding(attacker, true));
   }

   /**
    * Reach and killaura. Returns false when the swing must not land.
    *
    * <p>No exception is made for a high-ping attacker: the server measures the
    * distance it is given, and {@link #REACH_MARGIN} is the allowance for the tick
    * it is behind. Anything beyond that is a hit vanilla would not have produced.
    */
   public static boolean onAttack(ServerPlayer attacker, Entity target) {
      attackEvents++;
      if (!active(attacker) || target == null) {
         return true;
      }
      Track track = track(attacker);
      long now = tickOf(attacker);

      if (target instanceof LivingEntity living && !living.isSpectator() && !living.isRemoved()) {
         // The arm is measured against the range the weapon grants, not only against the
         // range the attribute is showing. The Crab Claw is written onto the <i>player</i>
         // by a tick hook, and the swing arrives on the connection's thread before that
         // tick has run: the server was still holding vanilla's three blocks while the
         // client was reaching with a weapon it can see. That is the mod's own grant, so
         // the equipment is the authority - the server can see it without trusting a word
         // the client says - and the correction is made first so the number is real
         // rather than merely excused.
         double pendingRange = rangePadding(attacker, true);
         if (pendingRange > 0.0) {
            AdvancedEnchantments.restoreInteractionRange(attacker);
            pendingRange = rangePadding(attacker, true);
         }
         double range = attacker.entityInteractionRange() + pendingRange;
         double surface = eyeDistance(attacker, target.getBoundingBox());
         if (!withinEntityReach(attacker, target)) {
            int rewind = latencyTicks(attacker);
            if (!LagCompensatedHistory.entityWasInRange(attacker, target, REACH_MARGIN + pendingRange, now, rewind)) {
               flag(
                  attacker,
                  REACH,
                  round(surface) + " blocks to the body (" + round(range) + " allowed"
                     + (pendingRange > 0.0 ? ", " + round(pendingRange) + " of it still to be written onto the body" : "")
                     + ", " + round(REACH_MARGIN) + " margin; no position in the latency history explains it)"
               );
               Chat.raw(attacker, "&c§l[AC]&r &7That hit was out of reach.");
               return false;
            }
         }

         // The other direction, and the reason hitbox is its own name. An attack only
         // leaves a client when the client's own crosshair ray meets the target's box -
         // that is how attacking works - so an attack whose ray misses the box the
         // <i>server</i> has is a client that was aiming at a bigger one. Reach puts the
         // failure in the arm; this puts it in the target. Both arrive at the server as
         // "a hit landed", which is why they have to be told apart by which measurement
         // is impossible, and why this one is allowed to be a single event rather than a
         // pattern: an honest client's ray always meets its own box.
         //
         // The inflation is one tick of the target's own movement, because the box the
         // attacker's client saw is the box as of the packet it last received. Without it
         // this check would fire on every hit landed against a sprinting opponent, which
         // is to say on every good player in the game.
         //
         // The tolerance is deliberately wide: both boxes are inflated by a third of a
         // block on top of a tick of the target's own movement, and the historical box by
         // two reach margins rather than one. Two independent rays have to miss by a
         // margin this size before the only explanation left is a grown target, which is
         // what keeps a hit landed on a strafing body through a doorway - the case this
         // check used to answer - out of the report.
         boolean boxMissed = !lookRayMeetsBox(attacker, target, range)
            && !LagCompensatedHistory.rayMetHistoricalBox(
               attacker, target, range + REACH_MARGIN * 3.0, now, latencyTicks(attacker)
            )
            // A stopped clock is not a grown hitbox for the same reason it is not a knockback
            // hack: the ability pins one body and moves another, so the box the attacker's ray
            // was aimed at is not the box the server has when the hit arrives.
            && !TimeLordManager.isTimeStopped(attacker);
         if (boxMissed) {
            track.hitboxMisses++;
            if (track.hitboxMisses >= HITBOX_MISSES) {
               track.hitboxMisses = 0;
               flag(
                  attacker,
                  HITBOX,
                  "landed " + HITBOX_MISSES + " hits in a row "
                     + "while the current and latency-compensated aim rays missed the body (the last at "
                     + round(surface) + " blocks) - the target was treated as larger than it is"
               );
               // Half a point rather than a whole one: the hitbox finding is already recorded on
               // its own, and letting the same measurement also fill the aura meter is how one
               // stale box became two findings against a player who was aiming correctly.
               track.auraScore += 0.5;
            } else {
               // One miss is a stale box, not a verdict: said in the log, never levelled.
               FortuneFavorsMod.LOGGER.debug(
                  "[FF-AC] {} missed the aim ray once ({} blocks) - waiting for a second before it counts",
                  attacker.getName().getString(), round(surface)
               );
            }
         } else {
            track.hitboxMisses = 0;
         }

         // Aura hits through walls. Reach and angle both pass for a client that
         // swings at whatever is nearest through the rock, so the one thing that
         // settles it is whether the server thinks there is anything between the
         // eyes and the body. There is no honest swing for which the answer is yes.
         boolean throughWall = attacker.level() instanceof ServerLevel level && swingsThroughWall(level, attacker, target);
         if (throughWall) {
            track.auraScore += 3.0;
            track.lastAuraAngle = 180.0;
         }

         Vec3 eye = attacker.getEyePosition();
         double centreY = target.getY() + target.getBbHeight() * 0.5;
         Vec3 toTarget = new Vec3(target.getX() - eye.x, centreY - eye.y, target.getZ() - eye.z);
         double length = toTarget.length();
         if (length > 0.001) {
            Vec3 look = attacker.getLookAngle();
            double dot = Math.max(-1.0, Math.min(1.0, look.dot(toTarget.scale(1.0 / length))));
            double angle = Math.toDegrees(Math.acos(dot));
            if (angle >= AURA_ANGLE_HARD) {
               track.auraScore += 2.0;
            } else if (angle >= AURA_ANGLE) {
               track.auraScore += 1.0;
            }
            if (angle >= AURA_ANGLE) {
               track.lastAuraAngle = angle;
            }

            // The aim window. This is a different question from the aura score:
            // aura asks whether the swing could have been aimed at all, and this
            // asks how *well* the ones that landed were aimed. A client that
            // tracks a strafing opponent through the centre twenty times running
            // scores nothing on aura and everything here.
            if (active(attacker, AIM)) {
               double targetSpeed = 0.0;
               try {
                  targetSpeed = target.getDeltaMovement().horizontalDistance();
               } catch (Throwable ignored) {
               }
               note(attacker, track.aim.hit(System.nanoTime(), angle, targetSpeed, throughWall));
            } else {
               track.aim.clear();
            }
         }

         boolean switched = track.lastTargetId != null && !track.lastTargetId.equals(target.getUUID())
            && now - track.lastAttackTick <= AURA_SWITCH_TICKS;
         if (switched) {
            track.auraScore += 2.0;
         }
         track.lastTargetId = target.getUUID();
         track.lastAttackTick = now;

         // -------- swings inside one tick. A person sends one attack per click and a
         // click is a tick long, so the pair of checks below are the two ways a client
         // can produce something a hand cannot: swinging several times on one tick, and
         // swinging at several bodies on one tick. They are separated because the hacks
         // are: a cooldown bypass re-sends the same swing, and a 6H aura fans one swing
         // across everything in range.
         if (track.swingTick != now) {
            track.swingTick = now;
            track.swingCount = 0;
            track.swingTargets.clear();
         }
         track.swingCount++;
         track.swingTargets.add(target.getUUID());
         if (track.swingCount > SWING_BURST && !legacyInstantSwing(attacker)) {
            int burst = track.swingCount;
            int targets = track.swingTargets.size();
            track.swingCount = 0;
            track.swingTargets.clear();
            // Six targets on one tick is a count nothing a hand does produces, whatever the
            // server's tick time was; a repeat burst on one target is only a finding when the
            // server was healthy enough for the deliveries to mean what they say.
            if (targets >= MULTI_TARGET_BURST) {
               flag(
                  attacker,
                  KILLAURA_6H,
                  burst + " swings at " + targets + " different targets inside one tick"
               );
               return false;
            }
            if (Double.isFinite(PerfMonitor.tps()) && PerfMonitor.tps() < SWING_MIN_TPS) {
               // The tick ran late and carried several of the client's clicks at once. Nothing
               // here is evidence, so nothing here is counted.
               track.swingBursts = 0;
               return false;
            }
            if (now - track.swingBurstWindow > SWING_BURST_WINDOW) {
               track.swingBurstWindow = now;
               track.swingBursts = 0;
            }
            track.swingBursts++;
            if (track.swingBursts < SWING_BURSTS) {
               // One bunch of a late tick, a double-click that arrived with the next one, or a
               // player who really was clicking that fast once: recorded, not accused.
               return false;
            }
            track.swingBursts = 0;
            flag(
               attacker,
               FAST_SWING,
               burst + " swings inside one tick on a weapon whose own cooldown is "
                  + round(attacker.getCurrentItemAttackStrengthDelay() * 50.0)
                  + "ms, repeated " + SWING_BURSTS + " times in " + (SWING_BURST_WINDOW / 20L) + "s"
            );
            return false;
         }

         // -------- a critical hit that was manufactured rather than earned. Vanilla
         // computes a crit from the server's own view of the attacker, so a client cannot
         // ask for one; what it can do is make the view say so by claiming to be airborne
         // while standing. Only counted when the claim is one the world contradicts, and
         // only correlated with the attack - a spoofed flag on its own is a desync.
         if (!attacker.onGround()
            && track.airTicks >= 2
            && attacker.fallDistance > 0.0F
            && hasImmediateGroundBelow(attacker)) {
            flag(
               attacker,
               CRITICAL_GROUND,
               "landed a hit while claiming to be falling - the body is on the ground and has been "
                  + "for the whole approach"
            );
         }

         if (track.auraScore >= AURA_LIMIT) {
            flag(attacker, KILLAURA, "swing " + round(track.lastAuraAngle) + "° off target" + (switched ? " and snapping between targets" : ""));
            track.auraScore = 0.0;
            Chat.raw(attacker, "&c§l[AC]&r &7That swing was refused.");
            return false;
         }
      }

      return true;
   }

   /**
    * True when the attacker's own aim ray meets the target's server-side box.
    *
    * <p>Not a reach test and not an aim test - it is a test of whether the two
    * machines agree about where the target <i>is</i>. A client only emits an attack
    * when its crosshair ray meets its own copy of the box, so a miss here means the
    * copy was larger than the original, which is the entire definition of a hitbox
    * expansion. The box is inflated by one tick of the target's own movement first, so
    * a hit against a sprinting opponent - where the attacker's copy is genuinely a
    * tenth of a second stale - is not mistaken for one.
    */
   private static boolean lookRayMeetsBox(ServerPlayer attacker, Entity target, double range) {
      try {
         Vec3 eye = attacker.getEyePosition();
         Vec3 look = attacker.getLookAngle();
         // A third of a block, which is the width of the desync this can honestly have:
         // the client's copy of the box is a tick old, the two machines round positions
         // differently, and a body mid-knockback is between two places at once. A hitbox
         // hack is a body treated as half again as wide, not a third of a block wider.
         double inflate = 0.35;
         try {
            inflate += target.getDeltaMovement().horizontalDistance();
         } catch (Throwable ignored) {
         }
         net.minecraft.world.phys.AABB box = target.getBoundingBox().inflate(inflate);
         if (box.contains(eye)) {
            return true;
         }
         Vec3 end = eye.add(look.scale(Math.max(1.0, range + REACH_MARGIN)));
         return box.clip(eye, end).isPresent();
      } catch (Throwable t) {
         // Fails open: a geometry read that cannot be made must not convict.
         return true;
      }
   }

   /**
    * Whether this weapon has no cooldown to be bypassed.
    *
    * <p>The same stand-down the autoclicker takes, for the same reason: this mod's
    * Legacy 1.8 enchantment adds +1024 attack speed, which makes a sword's interval
    * about a millisecond - so holding the attack key and re-sending it on every tick
    * are the same thing, there is nothing to gain, and flagging it would mean flagging
    * every player who holds attack in 1.8 mode.
    */
   private static boolean legacyInstantSwing(ServerPlayer attacker) {
      try {
         return attacker.getCurrentItemAttackStrengthDelay() * 50.0 < CombatStats.CLICK_COOLDOWN_FLOOR_MS;
      } catch (Throwable t) {
         return true;
      }
   }

   /**
    * True when a solid block sits between the attacker's eyes and the target's
    * body.
    *
    * <p>Kept deliberately blunt: the ray only counts as blocked when it stops
    * somewhere in open space along the way - not at the attacker's own feet (a
    * player standing in a hole clips their own column) and not so close to the
    * target that the block is really the target's own cover being squeezed past.
    */
   private static boolean swingsThroughWall(ServerLevel level, ServerPlayer attacker, Entity target) {
      try {
         Vec3 from = attacker.getEyePosition();
         Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
         if (from.distanceTo(to) < WALL_HIT_MIN_DISTANCE + 0.5) {
            return false;
         }
         BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
         if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
         }
         double travelled = hit.getLocation().distanceTo(from);
         return travelled > 0.6 && travelled < from.distanceTo(to) - 0.5;
      } catch (Throwable t) {
         return false;
      }
   }

   // ----------------------------------------------------------------- placing

   /**
    * Scaffold: a block placed under an airborne player's own feet.
    *
    * <p>The old shape of this check was "a block under you, while airborne, without
    * looking down", and that is not a description of a cheat - it is a description
    * of bridging and of jump-towering, both of which are ordinary competitive play.
    * So the check now starts by removing every case it can name an honest reason
    * for, and only counts what is left:
    *
    * <ul>
    *   <li><b>Not under the player's own feet:</b> a bridge step lands one or two
    *       blocks ahead of the column. Nothing to do with this check.</li>
    *   <li><b>Standing on something:</b> a player laying a floor from a solid
    *       footing is building, not scaffolding.</li>
    *   <li><b>Rising:</b> a player whose feet are going up is jumping, or jumping
    *       and placing to climb, which is a skill players practise. This check does
    *       not judge it - and the flight check, which is the one that owns bodies
    *       that go up without meaning to, still does.</li>
    *   <li><b>Looking down:</b> a player whose crosshair is on the face they are
    *       placing against has aimed at it, which is the whole question this check
    *       exists to ask.</li>
    * </ul>
    *
    * <p>What is left is a block landing under an airborne player who is not rising
    * and not looking at it - and of those, the ones while <i>descending faster than
    * any jump can descend</i> are the ones no legitimate play produces more than
    * once. A single one is a block clutch and is left alone; three inside a second
    * is a client rebuilding the floor beneath itself, which is what scaffold is for.
    *
    * <p>The pitch test is the crosshair, and the crosshair is only evidence when it
    * means what it says - see {@link QolCompat#crosshairIsNotEvidence()}.
    *
    * <p>What never moves: the reach test above and the fall pattern. A precise-placement
    * mod can change where a block is allowed to land; it cannot reach further and cannot
    * change how fast a body falls.
    */
   public static boolean onBlockPlaceAttempt(ServerPlayer player, BlockPos placePos) {
      if (!active(player) || player.isCreative()) {
         return true;
      }
      Track track = track(player);
      long now = tickOf(player);

      // ---- block reach. The server is told where a placement landed, so the one
      // thing a placement cannot do is reach past the arm that made it. Vanilla
      // tolerates a generous amount of this on the client and enforces it loosely,
      // which is why a reach hack survives it. The same Crab Claw correction as the
      // swing above applies, on the block half of the same grant: one item, two
      // ranges, and the clients that place and swing with it in the same tick.
      double pendingBlockRange = rangePadding(player, false);
      if (pendingBlockRange > 0.0) {
         AdvancedEnchantments.restoreInteractionRange(player);
         pendingBlockRange = rangePadding(player, false);
      }
      if (!player.isWithinBlockInteractionRange(placePos, BLOCK_REACH_MARGIN + pendingBlockRange)) {
         double distance = eyeDistance(player, new net.minecraft.world.phys.AABB(placePos));
         flag(
            player,
            BLOCK_REACH,
            "placed " + round(distance) + " blocks away (range "
               + round(player.blockInteractionRange() + pendingBlockRange) + ")"
         );
         Chat.raw(player, "&c§l[AC]&r &7That block was out of reach.");
         return false;
      }

      // Under the player's OWN feet, and nowhere near it: a bridge step is one to
      // two blocks out and fails this immediately.
      boolean under = WorldSnapshotHistory.wasUnder(player, placePos, now, latencyTicks(player))
         || LagCompensatedHistory.wasUnder(player, placePos, now, latencyTicks(player))
         || (placePos.getY() <= player.getY() - 1.0
            && player.distanceToSqr(placePos.getX() + 0.5, player.getY(), placePos.getZ() + 0.5) <= SCAFFOLD_COLUMN_SQR);
      if (!under) {
         return true;
      }
      // Standing on something: building a floor, not scaffolding one.
      if (player.onGround() && hasGroundBelow(player, player.getY())) {
         return true;
      }

      double rise = player.getDeltaMovement().y;
      // Rising: jumping, or jumping and placing to climb. Handled by the flight
      // check if the rise is not the player's own doing.
      if (rise >= SCAFFOLD_RISE) {
         return true;
      }

      // Descending faster than any jump arc descends: a fall this client is
      // refusing to take. One placement is a clutch; three in the window is a
      // floor being rebuilt every tick.
      boolean fallingHard = rise <= -SCAFFOLD_FALL;
      if (!fallingHard) {
         // Level or drifting. The only thing left that can tell a bridge from a
         // scaffold here is the crosshair - and a precise-placement mod raycasts
         // the face itself, so where a player is looking is not evidence. When that
         // is switched on this check does not guess, which is the trade the
         // compatibility mode states out loud.
         if (QolCompat.crosshairIsNotEvidence()) {
            return true;
         }
         if (player.getXRot() <= LOOK_DOWN_PITCH) {
            return true;
         }
      }

      if (now - track.scaffoldWindow > SCAFFOLD_WINDOW) {
         track.scaffoldCount = 0;
         track.scaffoldWindow = now;
      }
      track.scaffoldCount++;
      if (track.scaffoldCount >= SCAFFOLD_LIMIT) {
         flag(
            player,
            SCAFFOLD,
            track.scaffoldCount + (fallingHard
               ? " blocks rebuilt under themselves while falling " + round(-rise) + " blocks a tick"
               : " blocks placed under themselves, looking level")
         );
         track.scaffoldCount = 0;
         Chat.raw(player, "&c§l[AC]&r &7That placement was refused.");
         return false;
      }
      return true;
   }

   // ------------------------------------------------------------------ mining

   /**
    * Records where and when a break began, so its length can be measured.
    *
    * <p>The map is bounded, and the way it was bounded was an escape from the check that reads
    * it. Overflowing it <i>cleared</i> it, so a client that opened a break on sixty-four
    * positions it never finished wiped the start of the one block it actually meant to break -
    * and a break with no recorded start has no elapsed time, so it is never compared against
    * the estimate at all. That is a fast-mine check switched off by the party it is aimed at,
    * and it was reachable by writing sixty-five packets.
    *
    * <p>An overflow is evidence now rather than a silent reset. A vanilla client has one break
    * in flight at a time and ends it before starting the next, so an honest player never gets
    * near this many; a client that does is opening breaks it never reports the end of, which is
    * the shape of both an evasion and a flood. Weighted low, corroborated like everything else,
    * and the map is cleared afterwards so the report is once per burst rather than once per
    * packet.
    */
   public static void noteBreakStart(ServerPlayer player, BlockPos pos, long tick) {
      if (!enabled() || !active(player)) {
         return;
      }
      Track track = track(player);
      long stale = tick - BREAK_START_TTL;
      track.breakStarts.values().removeIf(started -> started < stale);
      // A claim that was never consumed is dropped on the same clock as the start it came from:
      // a break whose block was taken by somebody else, or whose client left, must not be
      // measured against whatever is mined at that position later.
      track.breakClaims.values().removeIf(claim -> claim.atTick() < stale);
      if (track.breakStarts.size() > 64) {
         flag(
            player,
            PACKET_FLOOD,
            "more than 64 breaks opened and never finished inside " + (BREAK_START_TTL / 20L)
               + "s - the mine check cannot measure a block whose start it never saw or has been made to forget",
            0.5
         );
         track.breakStarts.clear();
      }
      track.breakStarts.put(pos.asLong(), tick);
   }

   /**
    * The clock every break measurement is taken on.
    *
    * <p>The server's own tick counter, read at the moment the packet arrives, and used at both
    * ends of the subtraction. It has to be this counter and not the level's game time, which is
    * the number a break used to be stamped with: {@code ServerLevel.tickTime} is only true for
    * the overworld - every other dimension, including this server's own prison, realms and
    * arenas, is created with it false, so {@code getGameTime()} never advances in them and every
    * break taken there measured zero ticks. And it cannot be the tick loop's clock either, which
    * is sampled once a tick at the tick boundary and therefore lags every packet handled in
    * between. This counter advances once per tick for the server as a whole, is the same number
    * on both sides of the packet, and does not care what dimension the block is in.
    */
   public static long breakClock(ServerPlayer player) {
      MinecraftServer server = player == null || player.level() == null ? null : player.level().getServer();
      return server == null ? tickOf(player) : server.getTickCount();
   }

   /**
    * Called when a break's <i>stop</i> arrives, which is the packet that carries the client's own
    * claim about how long the break took.
    *
    * <p>This is where the measurement is taken, and the interval is start-to-stop - the client's
    * claim - rather than start-to-destroy, which is what the check used to compare. The
    * difference is the whole of the false positive it used to produce. Start-to-destroy is not a
    * measurement of the client's speed at all: the server destroys the block when its <i>own</i>
    * progress arithmetic says the block is done, so the interval is the server's own clock, and
    * the client's claim is not in it. Worse, that interval is reset by every start packet - a
    * player who clicks at a block repeatedly restarts the server's progress clock on every
    * click, so the interval between the last click and the destroy is a fragment of the real
    * break, and it is a fragment for a player who is doing nothing but clicking. That is exactly
    * the report: sand and fists, or spam clicking a fast block, flagged as fast mining.
    *
    * <p>Start-to-stop cannot be shortened that way. The vanilla client sends the stop when its
    * own prediction of the block says the block is broken, and its prediction runs the same
    * arithmetic the server does - so an honest client's claim is the break's real length, every
    * time, whatever the server's progress clock was reset to. A client that claims a block in a
    * fraction of the time it takes is claiming something the game cannot do.
    */
   public static void noteBreakStop(ServerPlayer player, BlockPos pos) {
      if (!enabled() || !active(player)) {
         return;
      }
      Track track = track(player);
      Long start = track.breakStarts.remove(pos.asLong());
      if (start != null) {
            long now = breakClock(player);
         track.breakClaims.put(pos.asLong(), new BreakClaim(now, Math.max(0L, now - start)));
      }
   }

   /**
    * Called when a break is abandoned, the one ending that throws the claim away.
    *
    * <p>An abandoned break has no claim: the client never said the block was broken, so there is
    * nothing to compare against the estimate and nothing is judged. This is also what keeps a
    * spam clicker out of the check entirely - every click that is released before the block falls
    * ends here.
    */
   public static void noteBreakEnd(ServerPlayer player, BlockPos pos) {
      if (!enabled()) {
         return;
      }
      Track track = track(player);
      track.breakStarts.remove(pos.asLong());
      track.breakClaims.remove(pos.asLong());
   }

   /**
    * Fast-mine and ore vision, evaluated at the moment of the break. Returns false
    * when the break must not happen.
    */
   public static boolean allowBreak(ServerPlayer player, BlockPos pos) {
      if (!active(player) || !(player.level() instanceof ServerLevel level)) {
         return true;
      }
      BlockState state = level.getBlockState(pos);
      if (state.isAir()) {
         return true;
      }
      if (player.isCreative()) {
         return true;
      }

      Track track = track(player);
      long now = tickOf(player);

      // ---- fast mine: how long did the client say that took?
      float hardness = state.getDestroySpeed(level, pos);
      ItemStack tool = player.getMainHandItem();
      // The bar is the game's own number: the speed this player's body breaks this block at,
      // which carries the tool, the Efficiency attribute, Haste, Conduit Power, mining fatigue
      // and every modifier a piece of gear has added to it. It is the number the client
      // predicted its own break with and the number the server has just validated the break
      // against inside ServerPlayerGameMode, so a break that matches it is a break the game
      // itself called honest. The arithmetic over the tool alone sees only the tool's own
      // statistics and the two effects it knows how to read, so a pickaxe this server has made
      // faster - a custom enchantment, a piece of its gear, an attribute - is estimated slower
      // than it is, and an honest break of it comes out under the bar and is refused, which is
      // exactly the report this answers. The formula is kept for what it is good at: the answer
      // when the engine has none (a destroy speed of zero means this tool cannot break this
      // block, which no honest destroy reaches), and the pure function the self-test pins.
      int engine = breakTicksFromDestroySpeed(hardness, player.getDestroySpeed(state), player.hasCorrectToolForDrops(state));
      int estimate = engine == Integer.MAX_VALUE
         ? breakTicks(
            hardness,
            tool.getDestroySpeed(state),
            tool.isCorrectToolForDrops(state),
            efficiencyLevel(level, tool),
            hasteAmplifier(player)
         )
         : engine;
      // The client's own claim, taken at its stop packet. See noteBreakStop for why the interval
      // measured is this one and not the destroy's.
      BreakClaim claim = track.breakClaims.remove(pos.asLong());
      track.breakStarts.remove(pos.asLong());
      int ping = latencyTicks(player);
      if (claim != null) {
         int measured = breakClientTicks(claim.lengthTicks(), PerfMonitor.tps());
         if (measured >= 0 && PerfMonitor.tps() >= MIN_READABLE_TPS && fastBreakFails(measured, estimate, ping)) {
            // Corroborated before anything is done at all: one short break is a measurement, two
            // inside the window are a client. Nothing is refused and nothing is filed on the
            // first, which is the half of this that removes the false positives - see
            // FAST_MINE_CORROBORATION. Dropping a single one costs almost nothing in detection,
            // because this is a second opinion on physics the game already enforces itself: a
            // stop whose break progress has not reached the bar makes vanilla hold the block and
            // finish it on its own clock, so a client breaking only a few times too fast is
            // already denied before this check sees it. What reaches here is a break vanilla
            // allowed, and one of those is not worth a block.
            int repeats = noteFastBreak(track, now);
            if (repeats >= FAST_MINE_CORROBORATION) {
               flag(
                  player,
                  FAST_MINE,
                  state.getBlock().getName().getString() + " broken in " + measured + " of " + estimate
                     + " ticks (" + ping + " of them borrowed for the connection), " + repeats
                     + " of them inside " + (FAST_MINE_CORROBORATION_TICKS / 20L) + "s"
               );
               Chat.raw(player, "&c§l[AC]&r &7That block would not break that fast.");
               track.fastBreakEvents.clear();
               return false;
            }
         }
      }

      // ---- ore vision.
      //
      // One tell is not enough, because each on its own is a heuristic that a
      // careful cheater can sit under: dig a tunnel to every diamond and the ore
      // is "exposed" by the tunnel, while stone between the seams keeps an average
      // low. Three independent tells, all fed by the same break, are much harder
      // to satisfy at once, and all three are statistics a player who digs
      // honestly never produces:
      //   * an ore with no face open to it at all, and
      //   * a session that is mostly ore and barely stone, and
      //   * five valuable ores found back to back with nothing dug in between.
      if (now - track.lastMineTick > ORE_WINDOW_IDLE_TICKS) {
         track.blocksMined = 0;
         track.valuableOres = 0;
         track.unseenOres = 0;
         track.unseenValuable = 0;
      }
      track.lastMineTick = now;
      track.blocksMined++;

      if (isOre(state)) {
         track.oresMined++;            if (!oreExposed(level, pos)
               && !LagCompensatedHistory.oreWasVisible(player, level, pos, now)
               && !WorldSnapshotHistory.oreHadOpenFace(player, pos, now, latencyTicks(player))) {
               track.unseenOres++;
               if (isValuableOre(state)) {
                  track.unseenValuable++;
               }
               // The bar is on the VALUABLE ones and on a session that has cut real rock, and both
               // halves are there to stop this check crying wolf: three buried coals is a hillside
               // with a cave in it, and three buried diamonds in a body that has broken eleven
               // blocks is a person who cannot find the door, not a client that can see through one.
               if (track.unseenValuable >= ORE_VISION_MIN && track.blocksMined >= ORE_YIELD_MIN_BLOCKS) {
                  flag(
                     player,
                     ORE_VISION,
                     track.unseenValuable + " valuable ores buried with no face to see them from - "
                        + track.unseenOres + " buried ores out of " + track.blocksMined + " blocks broken"
                  );
               }
            }
         oreRush(player, track, state, now);

         if (isValuableOre(state)) {
            track.valuableOres++;
            boolean inARow = track.stoneSinceValuable < ORE_BEE_LINE_GAP;
            track.stoneSinceValuable = 0;
            // The rolling window is the tell a session total cannot give: an x-rayer
            // can bury their average by mining stone afterwards, but they cannot
            // un-mine the hundred blocks they just walked through. Ordinary mining
            // puts a valuable ore in a hundred blocks a couple of times, not nine.
            track.yieldWindow.addLast(Boolean.TRUE);
            if (track.yieldWindow.size() > ORE_WINDOW_BLOCKS) {
               track.yieldWindow.removeFirst();
            }
            int inWindow = 0;
            for (boolean valuable : track.yieldWindow) {
               if (valuable) {
                  inWindow++;
               }
            }
            if (track.yieldWindow.size() == ORE_WINDOW_BLOCKS && inWindow >= ORE_WINDOW_VALUABLE) {
               flag(
                  player,
                  ORE_VISION,
                  inWindow + " valuable ores inside the last " + ORE_WINDOW_BLOCKS + " blocks broken - that is not a seam, that is a map"
               );
            }
            int percent = track.blocksMined > 0 ? track.valuableOres * 100 / track.blocksMined : 0;
            if (track.blocksMined >= ORE_YIELD_MIN_BLOCKS && percent >= ORE_YIELD_PERCENT) {
               flag(
                  player,
                  ORE_VISION,
                  track.valuableOres + " valuable ores out of only " + track.blocksMined + " blocks broken (" + percent + "%) - seams are not found like that"
               );
            } else if (inARow && track.valuableOres >= ORE_BEE_LINE_MIN) {
               flag(player, ORE_VISION, track.valuableOres + " valuable ores found with almost nothing dug in between");
            }
         } else {
            // Coal and copper are everywhere; they say nothing about vision, but
            // they do break a bee-line.
            track.stoneSinceValuable++;
            track.yieldWindow.addLast(Boolean.FALSE);
            if (track.yieldWindow.size() > ORE_WINDOW_BLOCKS) {
               track.yieldWindow.removeFirst();
            }
         }
      } else {
         track.stoneSinceValuable++;
         track.yieldWindow.addLast(Boolean.FALSE);
         if (track.yieldWindow.size() > ORE_WINDOW_BLOCKS) {
            track.yieldWindow.removeFirst();
         }
      }
      return true;
   }

   /**
    * The rush: how many of one ore a body pulls out of the ground inside one short window.
    *
    * <p>Three other ore checks already live here and none of them can see this. A body that is
    * reading the world through stone picks the ores it can already see, walks to them in a straight
    * line and mines them like anybody else - so every buried ore it breaks has a face the tunnel it
    * dug itself opened, its session stays mostly stone, and nothing is ever found with "almost
    * nothing dug in between", because it did dig. What it cannot fake is the <b>arrival rate</b>.
    * There are not thirty diamonds within five minutes of a player who has to find them.
    *
    * <p>Counted per ore family, in blocks broken, with a separate window for each:
    *
    * <ul>
    *   <li><b>Diamonds, {@value #XRAY_DIAMONDS} inside {@value #XRAY_WINDOW_TICKS} ticks.</b>
    *   <li><b>Ancient debris, {@value #XRAY_DEBRIS} inside the same.</b> Debris is its own family
    *       with its own figure because it is a different ore in a different dimension with a
    *       different honest rate: one number covering both would be wrong for both.
    * </ul>
    *
    * <p>The window is a window and not a session total, which is the whole of "and not if it took
    * them a long time": thirty diamonds over an afternoon never reaches the figure, and the counter
    * cannot creep up on it, because anything older than the window is dropped before the count is
    * read. The window is then <b>spent</b> by the report - the same discipline the click and aim
    * windows use - so one rush is one finding rather than one per ore, and a body that carries on
    * rushing has to produce a fresh thirty to be reported again.
    *
    * <p>Alerts staff and nobody else. This is a rate, not a physical impossibility, so it is absent
    * from {@link #AUTO_ENFORCE}: the miner is never told and is never corrected, which is exactly
    * what was asked for and is also the correct doctrine for a statistic. Creative is skipped -
    * removing a vein with a builder's brush is not a rush - and the call site is inside the ore
    * branch of a break that already passed the reach and speed checks.
    */
   /**
    * How many of these ores inside the window, and whether that is a rush.
    *
    * <p>Both of these are pure functions so the two halves of the rule the report was about can be
    * checked as arithmetic rather than by driving a live server: the limit, and the window. See
    * {@code anticheat.a-rush-is-a-rate-not-a-total}.
    */
   public static int oreRushLimit(boolean diamond) {
      return diamond ? XRAY_DIAMONDS : XRAY_DEBRIS;
   }

   public static boolean isOreRush(int countInWindow, boolean diamond) {
      return countInWindow >= oreRushLimit(diamond);
   }

   /**
    * Files one broken ore in its family's window and returns the count that window now holds.
    *
    * <p>Everything older than {@link #XRAY_WINDOW_TICKS} is dropped on the way in, which is the
    * whole of "thirty over a long time is not thirty": the counter cannot creep up on the limit,
    * because the only entries it can ever hold are the ones inside the window it is being measured
    * over. The self-test drives this function itself rather than a copy of it.
    */
   public static int rushCount(Deque<Long> window, long now) {
      window.addLast(now);
      while (!window.isEmpty() && now - window.peekFirst() > XRAY_WINDOW_TICKS) {
         window.removeFirst();
      }
      return window.size();
   }

   private static void oreRush(ServerPlayer player, Track track, BlockState state, long now) {
      boolean diamond = state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
      boolean debris = state.is(Blocks.ANCIENT_DEBRIS);
      if (!diamond && !debris) {
         return;
      }
      if (player.isCreative()) {
         return;
      }
      Deque<Long> window = diamond ? track.diamondRush : track.debrisRush;
      int found = rushCount(window, now);
      if (!isOreRush(found, diamond)) {
         return;
      }
      // The count is read before the window is cleared, because the line below is about what was
      // measured rather than about zero - and the window is cleared before the flag, so a second
      // firing cannot be produced by the same thirty ores.
      window.clear();
      flag(
         player,
         XRAY_RUSH,
         found + (diamond ? " diamond" : " ancient debris") + " blocks broken inside "
            + (XRAY_WINDOW_TICKS / 20L) + "s - that is not a seam, that is a route"
      );
   }

   /**
    * How long the fastest possible break of a block should take, in ticks, using
    * vanilla's own numbers: the tool's speed, the Efficiency bonus, the Haste
    * level, and the hundred-tick penalty for mining with the wrong tool.
    *
    * <p>Kept as a pure function taking the numbers rather than a player, so the
    * self-test can pin it and the estimate cannot quietly drift away from the
    * formula the check compares against.
    */
   public static int breakTicks(float hardness, float toolSpeed, boolean correctTool, int efficiency, int haste) {
      if (hardness < 0.0F) {
         return Integer.MAX_VALUE;
      }
      if (hardness == 0.0F) {
         return 1;
      }
      float speed = Math.max(1.0F, toolSpeed);
      if (correctTool) {
         if (efficiency > 0) {
            speed += efficiency * efficiency + 1;
         }
         if (haste > 0) {
            speed *= 1.0F + 0.2F * haste;
         }
      } else {
         speed /= 5.0F;
      }
      return breakTicksFromDestroySpeed(hardness, speed, correctTool);
   }

   /**
    * The same estimate, from the destroy speed the game itself would use.
    *
    * <p>{@link #breakTicks} resolves the speed out of a tool and the two enchantment effects it
    * can see; this one takes the number the engine has already resolved, which is the only way to
    * account for a modifier that did not come from the tool's own statistics - a server's custom
    * enchantment, a piece of its gear, a mining-speed attribute. It is the live check's primary
    * estimate for exactly that reason: the client predicted its break with the engine's number, so
    * comparing the break against anything else is comparing it against a number nobody used.
    */
   public static int breakTicksFromDestroySpeed(float hardness, float destroySpeed, boolean correctTool) {
      if (hardness < 0.0F) {
         return Integer.MAX_VALUE;
      }
      if (hardness == 0.0F) {
         return 1;
      }
      float speed = Math.max(0.0F, destroySpeed);
      float perTick = speed / hardness / (correctTool ? 30.0F : 100.0F);
      if (perTick <= 0.0F) {
         return Integer.MAX_VALUE;
      }
      return Math.max(1, (int)Math.ceil(1.0F / perTick));
   }

   /**
    * How long a break took, in the client's own ticks - the unit every estimate is quoted in.
    *
    * <p>A break is measured between two packets, and the clock it is measured on is the
    * server's, so on a server that is behind one of its ticks covers several of the client's and
    * the interval reads short by exactly that factor. See {@link MovementPhysics#tickScale}.
    *
    * @return the measured length, or -1 when the measurement cannot be read at all
    */
   public static int breakClientTicks(long elapsedServerTicks, double tps) {
      double scale = MovementPhysics.tickScale(tps);
      if (elapsedServerTicks <= 0L) {
         // The client opened and closed the break inside one of the server's ticks. On a tick
         // fifty milliseconds long that is the client claiming a block between two packets that
         // cannot have been a tick apart, and it is a fair reading. On a tick longer than that -
         // a server behind by any amount - several of the client's own ticks fit inside one, so
         // the zero is the server's resolution and not the client's speed, and there is nothing
         // here to judge. Answering -1 rather than a number is what keeps the check from
         // refusing honest blocks on exactly the servers it is least entitled to judge.
         return scale > 1.0 ? -1 : 0;
      }
      return (int)Math.min(Integer.MAX_VALUE, Math.round(elapsedServerTicks * scale));
   }

   /**
    * Files one short break and answers how many are inside the corroboration window.
    *
    * <p>Bounded by the window rather than by a size: entries older than
    * {@link #FAST_MINE_CORROBORATION_TICKS} are dropped before the count is read, so a body that
    * produced one odd break a while ago carries one entry and nothing has to be pruned on a
    * timer. The window is spent by a refusal rather than by the report, so a body that keeps
    * doing it keeps being refused instead of earning a fresh exemption per burst.
    */
   private static int noteFastBreak(Track track, long now) {
      track.fastBreakEvents.addLast(now);
      while (!track.fastBreakEvents.isEmpty()
         && now - track.fastBreakEvents.peekFirst() > FAST_MINE_CORROBORATION_TICKS) {
         track.fastBreakEvents.removeFirst();
      }
      return track.fastBreakEvents.size();
   }

   /** True when the break finished in less than half the time it should take.
    *
    * <p>Two arguments is the no-latency form, kept because a check about a formula should be
    * testable as a formula; every live call site goes through the three-argument one.
    */
   public static boolean fastBreakFails(int elapsedTicks, int vanillaTicks) {
      return fastBreakFails(elapsedTicks, vanillaTicks, 0);
   }

   /**
    * True when the break finished in less than half the time it should take, with the
    * connection's own round trip added to the measurement.
    *
    * <p>The elapsed time here is measured between two packets that travelled the same wire,
    * which is why the estimate is usually right: a start delayed by a hundred milliseconds and
    * a stop delayed by the same hundred milliseconds leave the difference intact. What it does
    * not survive is a <i>spike between the two</i>. A stop that gets out while the start is
    * still queued shortens the measurement by the length of the spike, and the client that
    * suffers it is an honest one on a bad connection - which is exactly the player this check
    * used to refuse a block to. Adding the player's own measured latency is not a fudge: it is
    * the size of the error the wire can introduce, and it is the same correction the reach,
    * placement and ore checks already make through {@link #latencyTicks}.
    *
    * <p>Everything above the correction is unchanged, including the bar itself: half the time
    * the block should have taken is still the line, so a client that is genuinely breaking at
    * twice the speed comes out on the wrong side of it whatever its ping.
    */
   public static boolean fastBreakFails(int elapsedTicks, int vanillaTicks, int latencyTicks) {
      if (vanillaTicks < FAST_MINE_MIN_ESTIMATE || vanillaTicks == Integer.MAX_VALUE) {
         return false;
      }
      int measured = elapsedTicks + Math.max(0, latencyTicks);
      return measured * 2 < vanillaTicks;
   }

   private static int efficiencyLevel(ServerLevel level, ItemStack tool) {
      if (tool == null || tool.isEmpty() || tool.getEnchantments().isEmpty()) {
         return 0;
      }
      try {
         Holder<Enchantment> efficiency = level.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.EFFICIENCY);
         return tool.getEnchantments().getLevel(efficiency);
      } catch (Exception e) {
         return 0;
      }
   }

   private static int latencyTicks(ServerPlayer player) {
      try {
         return Math.min(LagCompensatedHistory.MAX_REWIND_TICKS, Math.max(0, (player.connection.latency() + 49) / 50));
      } catch (Throwable ignored) {
         return 0;
      }
   }

   private static int hasteAmplifier(ServerPlayer player) {
      MobEffectInstance haste = player.getEffect(MobEffects.HASTE);
      if (haste != null) {
         return haste.getAmplifier() + 1;
      }
      MobEffectInstance conduit = player.getEffect(MobEffects.CONDUIT_POWER);
      return conduit != null ? conduit.getAmplifier() + 1 : 0;
   }

   /** Any ore, or ancient debris. */
   public static boolean isOre(BlockState state) {
      try {
         String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
         return path.endsWith("_ore") || path.equals("ancient_debris");
      } catch (Exception e) {
         return false;
      }
   }

   /**
    * The ores worth seeing through a wall for. Coal and copper generate in beds
    * everywhere and would drown the signal in ordinary mining; these are the ones
    * a player stops and digs to on purpose.
    */
   public static boolean isValuableOre(BlockState state) {
      try {
         String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
         return path.equals("diamond_ore")
            || path.equals("deepslate_diamond_ore")
            || path.equals("emerald_ore")
            || path.equals("deepslate_emerald_ore")
            || path.equals("ancient_debris")
            || path.equals("gold_ore")
            || path.equals("deepslate_gold_ore");
      } catch (Exception e) {
         return false;
      }
   }

   /**
    * True when at least one of the six neighbours is see-through, which is what
    * makes an ore visible at all. An ore broken with none of them open cannot have
    * been looked at: a legitimate miner has to break the covering block first, and
    * that break is what opens the face.
    */
   public static boolean oreExposed(ServerLevel level, BlockPos pos) {
      for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
         BlockState neighbour = level.getBlockState(pos.relative(direction));
         if (neighbour.isAir() || neighbour.canBeReplaced() || !neighbour.getFluidState().isEmpty()) {
            return true;
         }
         if (neighbour.getCollisionShape(level, pos.relative(direction)).isEmpty()) {
            return true;
         }
      }
      return false;
   }

   // --------------------------------------------------------------- knockback

   /**
    * Expects the knockback vanilla just applied and watches for it.
    *
    * <p>A knockback-delay hack does not change anything the server can see at the
    * moment of the hit; it changes whether the player then moves. So the impulse
    * is recorded here and the travel is measured over the following ticks.
    */
   private static void onDamage(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source, float taken) {
      if (!active(player) || taken <= 0.0F) {
         return;
      }
      // An explosion is a launch, and it is handled before anything else here because it is the one
      // case this method's knockback arithmetic cannot describe: a creeper has no attacker to
      // measure a direction away from, and a wind charge fired at somebody's feet has the SHOOTER
      // as its causing entity, which the checks below read as "the victim shoved themselves". Both
      // were therefore skipped entirely, and the movement that followed was judged as the player's
      // own - which is how a wind charge came back as flight. See LAUNCH_GRACE_TICKS.
      if (launchSource(source)) {
         long blowTick = tickOf(player);
         Track blowTrack = track(player);
         noteServerWrite(player, blowTrack, blowTick, "thrown by " + launchName(source));
         blowTrack.serverMotionUntil = blowTick + LAUNCH_GRACE_TICKS;
         blowTrack.teleportWindowEnd = blowTrack.serverMotionUntil;
         launches++;
         return;
      }
      // Nothing to measure on a body the server is driving: a stopped clock, a rewind,
      // a scripted shove. Starting the watch here and then excusing it at the end would
      // read exactly like a player who took the shove and did not move.
      if (scriptedMovement(player)) {
         return;
      }
      Entity attacker = source.getEntity();
      if (attacker == null || attacker == player) {
         return;
      }
      Vec3 away = player.position().subtract(attacker.position());
      Vec3 flat = new Vec3(away.x, 0.0, away.z);
      if (flat.lengthSqr() < 1.0E-6) {
         return;
      }
      Vec3 direction = flat.normalize();

      // Knockback that had nowhere to go is not a hack. The two honest reasons for
      // the distance to come back short are a wall in the way and ground that eats
      // momentum, and both are cheap to rule out here rather than to guess at
      // afterwards from a number that is already too small.
      if (!(player.level() instanceof ServerLevel level) || !knockbackPathClear(player, level, direction) || stuckIn(player, level)) {
         return;
      }

      Track track = track(player);
      track.knockX = direction.x;
      track.knockZ = direction.z;
      // The impulse is the <b>change</b> this hit made to the victim's velocity along the
      // line away from the attacker - not the velocity itself.
      //
      // Reading the velocity itself was the bug behind "it fires on hits that deal no
      // knockback": the figure was mostly the victim's own movement, so a player
      // sprinting away from an attacker was credited with a shove the server never
      // applied, and the moment they stopped moving - released the keys, stood to drop a
      // stack, went still in a time stop - the check read that as having eaten the
      // knockback. A hit that applies nothing now measures nothing, and a hit that
      // applies a shove is measured against exactly the figure the server just wrote,
      // which is a number no client can influence: a knockback-delay hack never changes
      // what the server puts on the body, only whether the client honours it.
      if (!track.hasVel) {
         // No baseline yet (this is the body's first tick): nothing to compare against,
         // and a total-speed reading is the reading this is here to avoid.
         return;
      }
      Vec3 velocity = player.getDeltaMovement();
      double impulse = (velocity.x - track.velX) * direction.x + (velocity.z - track.velZ) * direction.z;
      track.knockTravel = 0.0;
      track.knockStartTick = tickOf(player);
      track.knockUntil = tickOf(player) + KNOCKBACK_TICKS;
      track.prevX = player.getX();
      track.prevZ = player.getZ();
      if (!MovementPhysics.knockbackApplied(impulse)) {
         // This hit did not shove them. A tap with the knockback stripped out, a parry, a
         // scripted ability: there is no distance they were obliged to travel, so the
         // check stands down rather than modelling an expectation out of their own feet.
         // The pending mark is cleared too, so a measurement cannot be left hanging from
         // an earlier hit and settled against this one.
         track.knockPending = false;
         track.knockExpected = 0.0;
         return;
      }
      if (impulse < 0.05) {
         // A shove the server has applied shows up in this change on the tick it lands,
         // so a figure this small under the applied-impulse threshold is a hit whose
         // knockback has not been written yet. That is not the end of the measurement:
         // the server pushes the resulting motion to the client as a packet, and the
         // packet carries exactly the figure this line could not read. The measurement
         // is deferred to {@link #onOutboundVelocity} with the expectation left at zero,
         // which keeps a hit whose motion never arrives judged as nothing at all.
         track.knockPending = true;
         track.knockExpected = 0.0;
         return;
      }
      double friction = player.onGround() ? MovementPhysics.friction(slipperiness(player, true)) : MovementPhysics.AIR_FRICTION;
      track.knockExpected = MovementPhysics.knockbackTravel(impulse, friction, (int)KNOCKBACK_TICKS);
      track.knockPending = true;
   }

   /** True when the first two blocks along the impulse are passable. */
   private static boolean knockbackPathClear(ServerPlayer player, ServerLevel level, Vec3 direction) {
      double feet = player.getY();
      for (double distance : new double[]{1.0, 1.6}) {
         for (double height : new double[]{0.2, 1.2}) {
            BlockPos pos = BlockPos.containing(player.getX() + direction.x * distance, feet + height, player.getZ() + direction.z * distance);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
               return false;
            }
         }
      }
      return true;
   }

   /** Blocks that eat movement, and therefore eat knockback with it. */
   private static boolean stuckIn(ServerPlayer player, ServerLevel level) {
      BlockState at = level.getBlockState(player.blockPosition());
      return at.is(net.minecraft.world.level.block.Blocks.COBWEB)
         || at.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
         || at.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
   }

   private static void resolveKnockback(ServerPlayer player, Track track, long now) {
      if (!track.knockPending) {
         return;
      }
      double dx = player.getX() - track.prevX;
      double dz = player.getZ() - track.prevZ;
      track.knockTravel += dx * track.knockX + dz * track.knockZ;
      track.prevX = player.getX();
      track.prevZ = player.getZ();

      if (now < track.knockUntil) {
         return;
      }

      track.knockPending = false;
      // Obstruction, and the ways a player is not subject to knockback, are the
      // two ways to arrive here honestly.
      boolean excused = player.getVehicle() != null
         || player.isFallFlying()
         || (player.level() instanceof ServerLevel level && stuckIn(player, level))
         || player.isInWater()
         || player.isInLava()
         || player.onClimbable()
         || player.horizontalCollision
         || player.isDeadOrDying()
         || !player.isAlive()
         // A body that is hard to shove is not a body that hid the shove. Vanilla scales
         // the shove it writes by this attribute, so the figure measured above is already
         // the reduced one - but at half resistance and above what is left to travel is a
         // fraction of a block, and a fraction of a block is where the floor stops
         // separating "kept the shove" from "never moved at all". One worn item (the
         // Colossus Plate) sits exactly on that line.
         || player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) >= 0.5
         // A stopped clock is not a knockback-delay hack. Time is not running for this
         // body at all: the stop pins it where it stands, so the shove has nowhere to
         // land and the shortfall is the ability rather than the player. Named at the
         // source (a time stop, not "movement we decided not to judge") so wearing a
         // Slow Falling cloak cannot quietly buy the same exemption.
         || TimeLordManager.isTimeStopped(player)
         // A body the server teleported inside the measurement was moved by the server,
         // and the last thing it is allowed to be judged on is the distance that write
         // ate. Only a real position write counts: a shove's own velocity packet moves
         // the longer grace window, and reading that as "the server moved them" would
         // excuse every knockback there is.
         || track.teleportTick >= track.knockStartTick;
      if (excused) {
         return;
      }
      if (!active(player, KNOCKBACK)) {
         return;
      }
      if (track.knockExpected <= 0.0) {
         return;
      }
      // "Low knockback stuff" is not a measurement. A tap, a sprint-hit into a wall of
      // your own momentum, a glancing arrow: the travel being asked about is a fraction
      // of a block, and a fraction of a block is what an ordinary step, a slab, a berry
      // bush and one tick of lag all look like. Below this the check has nothing to say.
      if (!MovementPhysics.knockbackMeasurable(track.knockExpected)) {
         return;
      }
      if (MovementPhysics.knockbackCancelled(track.knockTravel, track.knockExpected)) {
         // How much of the model they are missing is how much of this is a
         // verdict: a player who was walking into the hit keeps half of it, and
         // that is a third of the way to a violation rather than a whole one.
         double kept = track.knockTravel / Math.max(0.01, track.knockExpected);
         flag(
            player,
            KNOCKBACK,
            "kept " + round(track.knockTravel) + " of " + round(track.knockExpected) + " blocks of knockback ("
               + percent(Math.max(0.0, kept)) + ")",
            Math.min(1.0, MovementPhysics.KNOCKBACK_FLOOR - kept + 0.4)
         );
         // Put the knockback back - the point of the hack is that it never lands. Declared
         // for the same reason a clamp is: this motion packet is ours, and reading it as a
         // fresh shove stood the module down for two seconds immediately after it had
         // caught somebody - which is the moment it is least allowed to look away.
         Vec3 cur = player.getDeltaMovement();
         writeBody(
            player,
            track,
            "this module's own knockback correction",
            null,
            null,
            new Vec3(cur.x + track.knockX * 0.4, cur.y, cur.z + track.knockZ * 0.4)
         );
      }
   }

   // ------------------------------------------------------------ alerts + data

   /**
    * Files one finding, at the weight the check itself puts on it.
    *
    * <p>A finding is two numbers and an action, in that order. The violation level
    * is how much of this the player has been doing, and it decays - a player who
    * stopped is not punished for last week. The confidence is how much this
    * particular evidence is worth: the check's own weight, reduced by a server
    * that is not keeping time and a connection that is not keeping up, because a
    * movement measured at eight ticks a second is not a measurement. Only a
    * finding that has both crossed the alert level and cleared the confidence bar
    * reaches staff, and only the failsafe - off by default, and never with a
    * moderator online - may act on one.
    *
    * <p>The weight bounds two different questions and they are deliberately not the
    * same bound. <b>How much</b> this is worth runs from a fifth of a finding to two of
    * them, because a pattern - a body that keeps arriving somewhere nobody sent it - is
    * worth strictly more than the single event it is made of, and a check that cannot say
    * so forces a repeating module to be caught at the rate of its first attempt.
    * <b>How sure</b> we are stays inside one: a weight above it is a statement about
    * quantity, and letting it read as certainty as well would be two claims for one
    * number. {@link #confidence} applies that ceiling itself.
    */
   private static void flag(ServerPlayer player, String check, String detail) {
      flag(player, check, detail, 1.0);
   }

   /**
    * The checks the prison switches off on its own floor.
    *
    * <p>The mine is a place built to be mined: the seams are laid out as walls of ore, the galleries
    * are dug by design, and the whole loop the block is for is standing in front of a rich face and
    * taking it. Ore vision and the ore rush are checks for a world where ore is *hidden* - they read
    * a yield no honest digging could produce, and in a world where the walls are ore by construction
    * they read the block working as intended. Staff still see every one of these outside the prison;
    * inside it they are dropped before a level, a count or an alert exists, the same way the Bedrock
    * blind spots are, and for the same reason - a check that fires on the game working is worse than
    * no check at all.
    */
   private static final Set<String> PRISON_BLIND = Set.of(ORE_VISION, XRAY_RUSH);

   /** True when this body is standing in the prison dimension, where being able to see ore is the point. */
   public static boolean inPrison(ServerPlayer player) {
      return player != null
         && player.level().dimension().equals(com.fortuneandfavors.economy.PrisonManager.PRISON_DIM);
   }

   /** Test hook: the ground question as the tick asks it, so the edge case can be pinned. */
   public static boolean hasGroundBelowForTest(ServerPlayer player) {
      return hasGroundBelow(player, player.getY());
   }

   private static void flag(ServerPlayer player, String check, String detail, double weight) {
      // The prison's own floors are the one place two of these checks are structurally wrong; see
      // PRISON_BLIND.
      if (PRISON_BLIND.contains(check) && inPrison(player)) {
         return;
      }
      // A measurement this class cannot make about a Bedrock client is not evidence about the
      // player behind it, so it is dropped before a level, a count or an alert exists - see
      // GEYSER_BLIND. Named in the log, so the silence is never mistaken for an outage.
      if (GEYSER_BLIND.contains(check) && bedrock(player)) {
         FortuneFavorsMod.LOGGER.debug(
            "[FF-AC] {} {} on a Bedrock client through Geyser - not a measurement this module can make: {}",
            player.getName().getString(), check, detail
         );
         return;
      }
      findings++;
      Track track = track(player);
      long now = tickOf(player);
      Track.Violation violation = track.of(check);
      violation.count++;
      violation.lastTick = now;
      violation.level = Math.min(
         AntiCheatPolicy.levelCeiling(), violation.level + Math.max(0.2, Math.min(2.0, weight)) * 10.0
      );
      violation.confidence = confidence(player, weight);
      violation.push(detail, EVIDENCE_LINES);
      // Corroboration: one check firing again and again is often one quirk (lag, an odd block);
      // different checks agreeing on the same player in the same half-minute is real evidence.
      // Each other recently active check (level above 5, within 600 ticks) lifts confidence by
      // 15%, up to three of them. A lone check is left exactly as it was.
      StringBuilder agreeing = new StringBuilder();
      int corroborating = 0;
      for (java.util.Map.Entry<String, Track.Violation> other : track.violations.entrySet()) {
         Track.Violation v = other.getValue();
         if (!other.getKey().equals(check) && v.level > 5.0 && now - v.lastTick < 600L) {
            corroborating++;
            agreeing.append(agreeing.length() == 0 ? "" : ", ").append(other.getKey());
         }
      }
      if (corroborating > 0) {
         violation.confidence = Math.min(1.0, violation.confidence * (1.0 + 0.15 * Math.min(3, corroborating)));
         violation.push("corroborated by " + agreeing, EVIDENCE_LINES);
      }
      // The client's self-report rides along with a finding that was raised on its own
      // evidence, as a note for the moderator reading it. It is read here and nowhere that
      // could act: it cannot add to the level above, cannot change the confidence, and
      // cannot raise a finding of its own. See ClientContext for why that is structural.
      String context = ClientContext.noteFor(player.getUUID());
      if (!context.isEmpty()) {
         violation.push(context, EVIDENCE_LINES);
      }
      track.lastViolation = now;
      track.judgedAtLastFinding = track.judgedTicks;
      AntiCheatStore.note(player, check, detail, violation.level);
      // Test mode is intentionally more verbose than production moderation: the
      // tester must see that a detector fired even when the normal staff alert bar
      // or alert cooldown would hide the second message. It never changes the
      // violation level, confidence, enforcement, or correction behavior.
      if (TEST_MODE.contains(player.getUUID())) {
         Chat.raw(
            player,
            "&8[AC TEST] &cobserved &f" + check + " &7(" + detail + ") &8vl=" + round(violation.level)
               + " confidence=" + percent(violation.confidence)
         );
      }

      boolean watch = WATCHING.contains(player.getUUID());
      // A speed finding reaches this point only after a full prediction window and
      // SPEED_TICKS consecutive over-model samples. That is already independent
      // corroboration, so do not make a modest but sustained speed hack wait for a
      // second window just because its distance margin was small. This exception is
      // narrowly scoped to SPEED; no single movement tick, combat event, packet, or
      // heuristic gets the same notification path.
      boolean corroboratedSpeedWindow = SPEED.equals(check) && violation.confidence >= 0.55;
      // A check staff have called wrong before has to shout harder to be heard again, and this is
      // the only place that rule is applied - see AntiCheatStore.reviewCaution. Both numbers that
      // decide whether a finding reaches chat are divided by it, so a wrong check goes quiet and
      // a right one is unaffected: no verdict has ever been filed against a check that was not.
      // Nothing downstream moves: the level, the ledger, the case file and the allowlist that
      // decides who may be removed are all read before this line and are unchanged by it.
      double caution = AntiCheatStore.reviewCaution(check);
      boolean announce = caution <= 1.0
         ? mayAnnounce(weight, violation.confidence, violation.level, violation.count, corroboratedSpeedWindow)
         : mayAnnounce(
            weight,
            violation.confidence / caution,
            violation.level / caution,
            violation.count,
            corroboratedSpeedWindow
         );
      if (!announce) {
         FortuneFavorsMod.LOGGER.debug(
            "[FF-AC] {} {} (vl {}, confidence {}, weight {}, count {}) - below the alert bar, recorded only: {}",
            player.getName().getString(), check, round(violation.level), percent(violation.confidence),
            round(weight), violation.count, detail
         );
      } else {
         long last = track.alertedAt.getOrDefault(check, Long.MIN_VALUE / 4);
         int alertedAt = track.alertedCount.getOrDefault(check, 0);
         if (watch || mayAlert(violation.count, alertedAt, now - last, AntiCheatPolicy.alertCooldown())) {
            track.alertedAt.put(check, now);
            track.alertedCount.put(check, violation.count);
            alert(player, check, detail, violation);
         }
      }
      // The automatic half is offered the finding whether or not a message went out about it.
      //
      // It used to be reached only from inside the alert branch, which quietly made the alert
      // dials part of the kick: with `alert-vl` set above `enforce-vl` - a legal pair of
      // settings, and one an owner reaches for when the alerts are too chatty - a finding could
      // be over the enforcement bar and still never reach the failsafe, because it fell out of
      // this method one branch earlier. A dial that silently switches the kick off is the worst
      // shape a bug in an anticheat can take, and it is exactly what "the kick does not work"
      // looks like from the outside: everything is switched on, and nobody is ever removed.
      //
      // Deciding to enforce from the finding and deciding to speak to staff about it are two
      // different questions with two different dials, so they are asked separately now - and
      // enforce() keeps its own gates, so this call can only act where the old one could.
      enforce(player, check, violation, now);
   }

   /**
    * Whether a finding may be announced, given the cooldown and the last announcement.
    *
    * <p>The cooldown exists so that one bad minute is one message. It is not a reason for a
    * check that is still firing to be invisible, and the report that produced this rule read
    * "it was detected once and never again" - which is exactly what a cooldown looks like
    * from the outside. Two things let a finding through: the cooldown having expired, or a
    * count that has <i>doubled</i> since the last announcement. The second is new evidence
    * rather than the same evidence again, so a sustained hack keeps announcing itself while
    * an occasional flag stays exactly as quiet as it was. The spacing still grows - each
    * doubling is further from the last than the one before it - so this cannot become a
    * spam channel.
    *
    * <p>Extracted as a function of four numbers because it is a rule about evidence, and a
    * rule about evidence should be checkable without a server: see
    * {@code anticheat.a-correction-names-itself}.
    */
   public static boolean mayAlert(int count, int alertedAtCount, long sinceLastAlert, long cooldown) {
      return count >= Math.max(2, alertedAtCount * 2) || sinceLastAlert >= cooldown;
   }

   /**
    * How many findings at full evidence weight it takes for repetition itself to be the
    * corroboration, without any help from the server's health.
    *
    * <p>Six, because the evidence a check produces is not the same thing as the confidence
    * that arrived with it. Confidence is discounted by how well the server is keeping time
    * and how far behind the connection is (see {@link #confidence}), which is the right
    * instinct - a measurement taken on a server at thirteen ticks a second is worth less
    * than one taken at twenty. What it must not do is make evidence that keeps repeating
    * invisible, because a lagging server cannot manufacture a body that trips the same
    * pattern six times: the discount is a property of the <i>server</i> and the repetition is
    * a property of the <i>player</i>. The report that produced this was written from exactly
    * that blind spot - the checks were working, the server was busy, and the alert queue said
    * nothing at all.
    */
   public static final int CORROBORATION_FINDINGS = 6;

   /**
    * Whether a finding may be announced, as a function of evidence rather than of the server
    * it arrived on.
    *
    * <p>Two things open the alert bar. The level has to say that enough has accumulated, or
    * the check has to have reached the bar in one window - a full prediction window is already
    * corroboration and should not have to wait for a second one. And something has to say the
    * evidence is worth believing: either the discounted confidence clears the bar, or the
    * finding is one of {@link #CORROBORATION_FINDINGS} repeats at a full evidence weight.
    *
    * <p>A function of numbers rather than of a player because it is the rule that decides who
    * hears about what, and a rule like that should be checkable without a server: see
    * {@code anticheat.only-one-method-writes-a-body}.
    */
   public static boolean mayAnnounce(
      double weight,
      double confidence,
      double level,
      int count,
      boolean windowIsCorroboration
   ) {
      double bar = AntiCheatPolicy.alertConfidence();
      boolean enough = windowIsCorroboration || level >= AntiCheatPolicy.alertLevel();
      boolean sure = confidence >= bar || (count >= CORROBORATION_FINDINGS && weight >= bar);
      return enough && sure;
   }

   private static void alert(ServerPlayer player, String check, String detail, Track.Violation violation) {
      alertsSent++;
      String name = player.getName().getString();
      FortuneFavorsMod.LOGGER.warn(
         "[FF-AC] {} failed {} x{} (vl {}, confidence {}): {}",
         name, check, violation.count, round(violation.level), percent(violation.confidence), detail
      );
      MinecraftServer server = player.level().getServer();
      if (server == null || !AntiCheatStore.alerts()) {
         return;
      }
      String line = "&c&l[AC] &f" + name + " &7failed &f" + hackLabel(check) + " &8(" + check + ") &8x" + violation.count
         + " &7- vl &f" + round(violation.level) + " &7confidence &f" + percent(violation.confidence) + "&7: " + detail;

      // The two buttons the spec asks for, and they are the whole point of a
      // clickable alert: the fastest way to a verdict on a flag is to watch the
      // player who produced it, and the second fastest is the record.
      MutableComponent alert = Component.literal(Chat.colorize("&c&l[AC] &f" + name + " &7failed &f" + check + " &8x" + violation.count + "&7: " + detail));
      MutableComponent actions = Component.literal(Chat.colorize("&8  "))
         .append(Component.literal(Chat.colorize("&b[ \ud83d\udd0e SPECTATE ]"))
            .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/ff anticheat spectate " + name))))
         .append(Component.literal(Chat.colorize("&8  ")))
         .append(Component.literal(Chat.colorize("&6[ \u26a0 MODERATION ]"))
            .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/ff anticheat info " + name))));

      for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
         if (isStaff(staff)) {
            Chat.raw(staff, line);
            if (WATCHING.contains(staff.getUUID())) {
               Chat.raw(staff, alert);
               Chat.raw(staff, actions);
            }
         }
      }
   }

   /**
    * The automatic half: kicks and a short timeout, and only ever with nobody
    * watching.
    *
    * <p>Three conditions, all required. The failsafe has to be switched on. No
    * moderator may be online - the entire reason this exists is the hours nobody
    * is around, and an admin who is present is a better judge than a threshold.
    * And the finding has to be at the enforcement level, not merely alertable,
    * with a cooldown between actions so that one bad minute is one action.
    *
    * <p>It never bans. The worst it does is five minutes.
    */
   private static void enforce(ServerPlayer player, String check, Track.Violation violation, long now) {
      if (!ModConfig.anticheatFailsafe()) {
         return;
      }
      // Only the checks with a physical model behind them may move a player out of the game.
      // See AUTO_ENFORCE: everything else is recorded, alerted and left to a person, because
      // the alternative is the report this answers - kicked mid-fight for something the server
      // measured wrongly, with no way to tell what it was.
      if (!AUTO_ENFORCE.contains(check)) {
         FortuneFavorsMod.LOGGER.info(
            "[FF-AC] {} failed {} at the enforcement level (vl {}) but it is an advisory check - recorded, never acted on: {}",
            player.getName().getString(), check, round(violation.level), violation.last()
         );
         return;
      }
      // An operator is not punished for being an operator, but the rank is not a veto either:
      // `/ff anticheat op on` now means "the checks measure staff, and the failsafe may act on
      // them", and `/ff anticheat op kick on` is the same yes for an owner who wants staff
      // kickable while the checks still skip them. See operatorConsent.
      //
      // The stand-down is logged rather than silent. This gate used to return without a word,
      // so a finding that had cleared every other bar looked from the outside exactly like a
      // detection that never happened - which is what "the warn kick does not kick" reads like
      // when the body being tested is an operator who has not consented.
      if (!mayPunish(player)) {
         FortuneFavorsMod.LOGGER.info(
            "[FF-AC] {} failed {} at the enforcement level (vl {}) but is staff and has not consented - not acting: {} (run /ff anticheat op on or /ff anticheat op kick on)",
            player.getName().getString(), check, round(violation.level), violation.last()
         );
         return;
      }
      // Staff presence used to be the failsafe's own veto - "a person present is a better
      // judge than a threshold". On a server that always has an admin connected that theory
      // quietly turned the automatic half into dead code, which is the report this answers:
      // "nobody is ever kicked at all", from a server with admins online. The findings are
      // still alerted to staff; what has changed is that the failsafe no longer waits for
      // them to log off before it does its job. Whether staff were online is recorded with
      // the action below.
      Track track = track(player);
      // Either this one check is at the enforcement bar, or enough of the others are lit
      // alongside it to make the pattern itself the evidence - see mayEnforce. Both roads end
      // at the same place, and neither of them moves the bar a single honest player stands on.
      int agreeing = agreeingChecks(player);
      if (!mayEnforce(agreeing, violation.level, violation.confidence, violation.count)
            || now - track.enforcedAt < AntiCheatPolicy.enforceCooldown()) {
         return;
      }
      // The level is spent by the action, so the next one needs new evidence. The figure is
      // kept first because the log line below is about what was measured, not about zero.
      double seenLevel = violation.level;
      violation.level = 0.0;

      // ---- how many alerts, not one reading.
      //
      // ENFORCE_VL is a high bar for one measurement, but a high bar is still one
      // measurement, and a server that acts on the first one acts on whatever its own
      // arithmetic got wrong that tick. The automatic half now waits on the number of
      // alerts the module has already raised against this body - the count the ledger, the
      // moderation screen and this decision all share, so "check how many alerts happen"
      // has one answer - rather than on a private strike counter nothing outside could read.
      // The player is told which finding is stacking and how many alerts there have been.
      int alerts = alertCount(player);
      track.enforceStrikes = alerts;
      if (!mayStrike(alerts)) {
         // Note what is deliberately NOT done here: the cooldown is not spent. See where it is
         // stamped, below the ladder - a warning is not an action, and a warning that spent the
         // action cooldown turned the two strikes the player is supposed to see into sixty
         // seconds of silence followed by a removal they were never warned about.
         Chat.raw(
            player,
            "&c&l[AC] &7Suspicious &f" + hackLabel(check) + " &7(&f" + alerts + "&7/&f" + ENFORCE_ALERTS
               + "&7 alerts): &f"
               + violation.last()
               + "\n&8" + hackLine(check)
               + "\n&7Nothing has been done to you yet. If this keeps up the connection is cut, and the record is at &f/ff anticheat info "
               + player.getName().getString()
         );
         FortuneFavorsMod.LOGGER.warn(
            "[FF-AC] alert {}/{} on {} for {} (vl {}): not acting yet - one measurement is not a pattern: {}",
            alerts, ENFORCE_ALERTS, player.getName().getString(), check, round(seenLevel), violation.last()
         );
         return;
      }
      // The action is being taken, so the cooldown starts now - not when the decision was first
      // reached. It is a window between *actions* (see AntiCheatPolicy.ENFORCE_COOLDOWN), and the
      // warnings above deliberately do not spend it: they are the two strikes the player is meant
      // to be shown, and a rung that consumes the gap before the rung that acts makes the ladder
      // read from the outside as "it warns and then never does anything".
      track.enforcedAt = now;
      int stage = AntiCheatStore.trip(player.getUUID(), player.getName().getString());
      // The punishment names the hack in words a person recognises and then says the quiet part out
      // loud. "ore-vision: 3 buried ores..." told a player nothing they could act on; "X-Ray" plus
      // the line under it tells them exactly what they were caught doing and how obvious it was - and
      // the same two strings are what the ledger and the moderation screen show, so nobody is left
      // reading a key of the week.
      String reason = hackLabel(check) + " (" + check + ", " + alerts + " alerts): " + violation.last() + "\n" + hackLine(check);
      // The finding is said out loud before the connection goes, because a kick screen is
      // three lines and a player who is removed has nowhere else to read it: what fired, what
      // it measured, and where the record is. "I do not know what I am getting kicked for" is
      // the complaint this answers.
      Chat.raw(
         player,
         "&c&l[AC] &cThe anticheat removed you for &f" + hackLabel(check) + "&c.\n&7" + hackLine(check)
            + "\n&8What it measured: &7" + violation.last()
            + "\n&7If that is wrong it is a bug - show an admin &f/ff anticheat info " + player.getName().getString()
      );
      if (stage >= AntiCheatStore.TIMEOUT_STAGE) {
         long until = System.currentTimeMillis() + AntiCheatStore.TIMEOUT_MILLIS;
         AntiCheatStore.punish(player, "timeout", reason, "anticheat", until);
         disconnect(player, AntiCheatStore.timeoutText(reason, until));
      } else {
         // A WARNING kick, filed as its own type. It is a rung on the ladder rather than a
         // punishment somebody handed out, which is why it must never be listed as a ban -
         // and why a moderator's own kick (see {@link #kick}) is a different record.
         //
         // It carries a re-entry window now. Without one it was a disconnect the kicked player
         // undid by reconnecting, so from the outside the first three rungs of the ladder did
         // nothing at all - which is the report this answers. The window is the ladder's own
         // (WARN_KICK_REENTRY_MILLIS, half a moderator's) and it is filed through warnKick rather
         // than punish, because punish writes any future duration into the *timeout* field and a
         // warning that reads as a live timeout is a five-minute sentence with a rung's name on it.
         AntiCheatStore.warnKick(player.getUUID(), player.getName().getString(), reason);
         disconnect(player, AntiCheatStore.kickText(stage, reason));
      }
      FortuneFavorsMod.LOGGER.warn(
         "[FF-AC] automatic action on {} at stage {} for {} (vl {}, {} physical check(s) agreeing, {} alerts, staff online: {}, opted-in: {}): {}",
         player.getName().getString(), stage, check, round(seenLevel), agreeing, alerts, staffOnline(player),
         optedInOperator(player), violation.last()
      );
   }

   /**
    * Whether this body is one of the exempt-but-opted-in kind: an operator or economy admin,
    * with the automatic half explicitly switched on for them.
    *
    * <p>Asked separately from {@link #mayPunish} because the two answer different questions
    * and only one of them is about the player's rank. {@code mayPunish} is "does the rank
    * stop a punishment", and this is "is there any of that permission left to spend".
    */
   public static boolean optedInOperator(ServerPlayer player) {
      return player != null && rankExempt(player) && operatorConsent();
   }

   /**
    * Whether an operator's rank has been consented away - see {@link #mayPunishRank}.
    *
    * <p>Two ways to say it and either is enough. {@code /ff anticheat op on} is the one an owner
    * reaches for while testing, and it now means both halves: once the checks are told to measure
    * staff, the failsafe may act on them. "It ignores OP no matter what" was the report, and a
    * switch named "op on" that leaves the kick switched off is the same silent veto that made
    * the automatic half dead code on a server with an admin connected. {@code /ff anticheat op
    * kick on} remains for the opposite case - an owner who wants staff kickable while the checks
    * still skip them - and either switch is a single, explicit yes.
    */
   public static boolean operatorConsent() {
      return checksOps() || ModConfig.anticheatOpKick();
   }

   /**
    * Whether the automatic half may act on this body.
    *
    * <p>False for every operator and economy admin until they have consented - see
    * {@link #operatorConsent}. It is deliberately narrower than {@link #exempt}: a rank is not
    * punished for being a rank, it is punished after somebody with that rank has said the
    * failsafe may act on them, which is also the only way the ladder is testable at all.
    */
   public static boolean mayPunish(ServerPlayer player) {
      return player != null && mayPunishRank(rankExempt(player), operatorConsent());
   }

   /**
    * The rule itself, as arithmetic, so it can be pinned without a server.
    *
    * <p>A staff rank means the automatic half does not act on it unless operators have been
    * consented to: {@code opsChecked} is "the checks were told to measure staff" and
    * {@code opKick} is "staff may be kicked whatever else is switched on", and either answers
    * the question. Written out here rather than inlined into {@link #mayPunish} so the self-test
    * asserts the decision instead of a copy of it - which is the same reason every other
    * threshold in this package has a function.
    */
   public static boolean mayPunishRank(boolean staffRank, boolean opsConsented) {
      return !staffRank || opsConsented;
   }

   /** An operator or economy admin - the rank half of the exemption, without the switch. */
   private static boolean rankExempt(ServerPlayer player) {
      return player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
         || PermissionManager.isEconomyAdmin(player.getUUID());
   }

   /** True when a moderator is online, which stands the automatic half down. */
   private static boolean staffOnline(ServerPlayer from) {
      MinecraftServer server = from.level().getServer();
      if (server == null) {
         return true;
      }
      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         if (isStaff(other)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Stops making one body wait: lifts a moderator's kick window, a live timeout, and the
    * automatic ladder, and clears the live strikes so the next finding is judged fresh.
    *
    * <p>The answer to "/ff anticheat kick says they can rejoin in a minute" and to a ladder
    * that has run hot. It is the store's arithmetic plus this module's own in-memory state,
    * so an owner does not have to restart the server to un-stick a player the failsafe got
    * wrong. Bans are untouched - see {@link AntiCheatStore#clearHold(java.util.UUID)}.
    *
    * @return true when there was something to clear
    */
   public static boolean clear(ServerPlayer player) {
      return player != null && clear(player.getUUID());
   }

   /**
    * The same clear for a body that is not here, addressed by id.
    *
    * <p>A player who is waiting out a kick window or a timeout is, by definition, not online -
    * that is what waiting means - so resolving the command by name through the player list made
    * {@code /ff anticheat clear} useless for exactly the case it was written for. What is left to
    * clear for somebody offline is the store's: the live track is dropped when they log out
    * (see {@link #forget}), so there are no strikes in memory to speak of and the ladder, the
    * timeout and the kick window are all on the record.
    */
   public static boolean clear(UUID id) {
      if (id == null) {
         return false;
      }
      Track track = TRACKS.get(id);
      if (track != null) {
         track.enforceStrikes = 0;
         track.strikeWindowAt = Long.MIN_VALUE / 4;
         track.enforcedAt = Long.MIN_VALUE / 4;
      }
      return AntiCheatStore.clearHold(id);
   }

   /**
    * How many findings the module has recorded against this body across the checks that may
    * move them - the count the ledger and the moderation screen already show.
    *
    * <p>The automatic half waits on this rather than on a private strike counter, so "how
    * many alerts have there been" has one answer in the ledger, the screen and the decision,
    * and it survives a relog because the count is the record's, not the session's.
    */
   public static int alertCount(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      if (track == null) {
         return 0;
      }
      int total = 0;
      for (String check : AUTO_ENFORCE) {
         Track.Violation violation = track.violations.get(check);
         if (violation != null) {
            total += violation.count;
         }
      }
      return total;
   }

   /** Who may see alerts and open the moderation screen. */
   public static boolean isStaff(ServerPlayer player) {
      return player != null
         && (player.permissions().hasPermission(Permissions.COMMANDS_ADMIN) || PermissionManager.isEconomyAdmin(player.getUUID()));
   }

   /**
    * How much this finding is worth, from 0 to 1: the check's own weight, cut
    * down by a server that is missing its ticks and a connection that is missing
    * its packets. Nothing under half sends an alert, so a finding made while the
    * server is unstable is recorded and reviewed rather than acted on.
    */
   /** How long a platform answer is trusted. Asked of Geyser's API, not of a permission. */
   private static final long BEDROCK_TTL_MILLIS = 30_000L;
   private static final Map<UUID, Boolean> BEDROCK = new HashMap<>();
   private static final Map<UUID, Long> BEDROCK_ASKED_AT = new HashMap<>();

   /**
    * True when this body is a Bedrock client connected through Geyser.
    *
    * <p>Cached for half a minute, because the answer is asked on every finding and the lookup
    * is a reflected call into another plugin's API - cheap, but not free, and it cannot change
    * inside a fight. {@link ModPlatform#isBedrock} answers false when Geyser is not installed
    * at all, which is the common case and the reason nothing here needs a config switch.
    */
   public static boolean bedrock(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      UUID id = player.getUUID();
      long now = System.currentTimeMillis();
      Long asked = BEDROCK_ASKED_AT.get(id);
      if (asked != null && now - asked < BEDROCK_TTL_MILLIS) {
         return Boolean.TRUE.equals(BEDROCK.get(id));
      }
      boolean is = ModPlatform.isBedrock(player);
      BEDROCK.put(id, is);
      BEDROCK_ASKED_AT.put(id, now);
      return is;
   }

   /** The platform answer as a portable rule, so a test can pin it without Geyser installed. */
   public static boolean geyserBlind(String check, boolean isBedrock) {
      return isBedrock && GEYSER_BLIND.contains(check);
   }

   /**
    * How many enforcement-level findings inside the window it takes to cut a connection.
    *
    * <p>Three, because the two ways this number can be wrong are not symmetric: at two, a
    * check with a bad tick still removes an honest player mid-fight, and at four the only
    * cost is that a real cheater is warned twice more before the connection goes. The
    * strikes in between are the point - they tell the player which finding is stacking.
    */
   public static final int ENFORCE_ALERTS = 3;
   /**
    * The same number the ladder has always used, kept under its old name so the rule reads
    * the same everywhere it is quoted. The automatic half counts <i>alerts</i> now - the
    * findings the ledger already records - rather than a private strike counter, so this is
    * an alias of {@link #ENFORCE_ALERTS} rather than a second threshold to keep in step.
    */
   public static final int ENFORCE_STRIKES = ENFORCE_ALERTS;
   /** The window the strikes are counted in: ten minutes, so it is a pattern and not a mood. */
   public static final long ENFORCE_STRIKE_WINDOW_NANOS = 600_000_000_000L;

   /** The rule itself, as a predicate, so the test can pin both sides of it. */
   public static boolean mayStrike(int strikes) {
      return strikes >= ENFORCE_ALERTS;
   }

   /** The enforcement rule, as a predicate over names - see {@link #AUTO_ENFORCE}. */
   public static boolean mayAutoEnforce(String check) {
      return AUTO_ENFORCE.contains(check);
   }

   /*
    * ------------------------------------------------------------------ corroboration
    *
    * A body does not have to fail one check loudly to be cheating. A client that is careful
    * with its speed and careful with its reach and careful with its flight can sit under the
    * enforcement bar on every one of them forever, because each bar is a bar for a single
    * model and nothing here has ever added them up. That is the shape of a hacked client that
    * knows an anticheat reads one check at a time, and the answer is not to lower any of the
    * bars - lowering them is what removes honest players.
    *
    * So the evidence is allowed to be the agreement. Three separate physical models, each hot
    * at once, is a statement no honest second produces: speed is the distance a body moved,
    * flight is whether anything held it up, reach is where its arm ended, and a body has to be
    * doing something specific and wrong to keep all of them lit together. The floor each one
    * has to reach is the alert bar rather than the enforcement bar, which is deliberately the
    * stronger requirement of the two possible readings - one check at the enforcement level, or
    * three checks at the level that would each have alerted staff on their own.
    */

   /** Distinct physical checks that have to agree before the pattern itself is evidence. */
   public static final int ENFORCE_CORROBORATION = 3;
   /**
    * The level each of those has to hold: the alert bar's tested default.
    *
    * <p>The alert bar rather than the enforcement bar, and rather than a number invented here,
    * so "three checks that would each have been worth telling staff about" is the whole rule and
    * there is no fourth number to keep in step with the other two.
    */
   public static final double ENFORCE_CORROBORATION_LEVEL = ALERT_VL;
   /** How recently they have to have fired: half a minute, inside one fight or one tunnel. */
   public static final long ENFORCE_CORROBORATION_TICKS = 600L;

   /**
    * Whether the automatic half may act: one check at the enforcement level, or
    * {@link #ENFORCE_CORROBORATION} of them agreeing at the alert level.
    *
    * <p>A function of four numbers rather than of a player, because it is the rule that decides
    * whether somebody is removed from the game, and a rule like that has to be checkable
    * without a server: see {@code anticheat.the-failsafe-is-not-switched-off-by-the-alert-dial}
    * and {@code anticheat.corroboration-is-what-catches-a-quiet-cheater}.
    *
    * <p>Two things are required, and the second is the one that keeps this from being a knob
    * that quietly drops a safety when the caller changes. The <b>level</b> rule is the bar, read
    * from the policy rather than from the alert dials. The <b>worth believing</b> rule is the
    * same one {@link #mayAnnounce} uses - the confidence the finding was discounted to, or
    * enough repetition, or the agreement of independent physical models - because a finding
    * taken while the server is missing its ticks or the connection is far behind is worth less
    * whether or not a message about it was sent. The automatic half is <i>reached</i> without
    * going through the alerts; it is not made less careful for it.
    */
   public static boolean mayEnforce(int agreeingChecks, double level, double confidence, int count) {
      boolean enough = level >= AntiCheatPolicy.enforceLevel() || agreeingChecks >= ENFORCE_CORROBORATION;
      boolean sure = confidence >= AntiCheatPolicy.alertConfidence()
         || agreeingChecks >= ENFORCE_CORROBORATION
         || count >= CORROBORATION_FINDINGS;
      return enough && sure;
   }

   /** The level half of the rule on its own, for the readouts and for a caller with no discount. */
   public static boolean mayEnforce(int agreeingChecks, double level) {
      return mayEnforce(agreeingChecks, level, 1.0, Integer.MAX_VALUE);
   }

   /**
    * How many physical models are lit on this body right now.
    *
    * <p>Counted rather than remembered, and counted over {@link #AUTO_ENFORCE} rather than over
    * every check, because the corroboration is evidence about what a body did - and the checks
    * that judge a pattern rather than a body are exactly the ones this module has already
    * decided may not move anybody. A check that has been quiet for longer than the window does
    * not count, so three models tripped an hour apart are three findings and not a pattern.
    */
   public static int agreeingChecks(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      if (track == null) {
         return 0;
      }
      long now = tickOf(player);
      int agreeing = 0;
      for (String check : AUTO_ENFORCE) {
         Track.Violation violation = track.violations.get(check);
         if (violation == null || violation.level < ENFORCE_CORROBORATION_LEVEL) {
            continue;
         }
         if (now - violation.lastTick > ENFORCE_CORROBORATION_TICKS) {
            continue;
         }
         agreeing++;
      }
      return agreeing;
   }

   public static double confidence(ServerPlayer player, double weight) {
      double health = 1.0;
      double tps = PerfMonitor.tps();
      if (tps > 0.0) {
         // 20 tps is perfect, 10 is half the world missing; below that a movement
         // measurement is not a measurement at all.
         health = Math.max(0.25, Math.min(1.0, (tps - 8.0) / 12.0));
      }
      double latency = 1.0;
      try {
         int ping = player.connection == null ? 0 : player.connection.latency();
         latency = ping <= 150 ? 1.0 : Math.max(0.4, 1.0 - (ping - 150) / 700.0);
      } catch (Throwable ignored) {
      }
      return Math.max(0.05, Math.min(0.99, Math.max(0.2, Math.min(1.0, weight)) * health * latency));
   }

   /**
    * One line per player who has failed anything, worst first, with the name of
    * whoever is still online (and a short id for anyone who has left).
    */
   public static List<String> ledger(MinecraftServer server) {
      List<String> out = new ArrayList<>();
      List<Map.Entry<UUID, Track>> rows = new ArrayList<>(TRACKS.entrySet());
      rows.sort(Comparator.comparingInt((Map.Entry<UUID, Track> e) -> -e.getValue().total()));
      for (Map.Entry<UUID, Track> row : rows) {
         Track track = row.getValue();
         if (track.total() == 0) {
            continue;
         }
         StringBuilder detail = new StringBuilder();
         for (String check : CHECKS) {
            Track.Violation violation = track.violations.get(check);
            if (violation != null && violation.count > 0) {
               if (detail.length() > 0) {
                  detail.append("&7, ");
               }
               detail.append("&f").append(check).append(" &8x").append(violation.count)
                  .append(" &7vl&f").append(round(violation.level))
                  .append(" &8(").append(firedAgo(violation, clock)).append(") ");
            }
         }
         String name = "&8" + row.getKey().toString().substring(0, 8);
         ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(row.getKey());
         if (online != null) {
            name = "&f" + online.getName().getString();
         }
         out.add("&7- " + name + "&7: " + detail);
         if (out.size() >= 10) {
            break;
         }
      }
      return out;
   }

   public static void clear() {
      TRACKS.clear();
      GLIDE_CLAIMS.clear();
      TEST_MODE.clear();
      tickCalls = 0L;
      movePackets = 0L;
      attackPackets = 0L;
      attackEvents = 0L;
      findings = 0L;
      serverWrites = 0L;
      LagCompensatedHistory.clear();
      WorldSnapshotHistory.clear();
      PacketEngine.clear();
      // The context goes with it: with the module off there is nothing for a self-report to
      // be context FOR, and holding it would be collecting data for no purpose.
      ClientContext.clear();
   }

   /**
    * Records a refusal on the record for a body, without moving it.
    *
    * <p>A refusal is what the module does when it catches something - it tells the client to
    * take a position it did not claim - and it is the second half of the constant-correction
    * rule. The self-test harness cannot produce one honestly: a refusal requires
    * {@link #setback}, which is the failsafe, which the harness turns off so that no test can
    * move a real body. So the harness states the refusal and tests the rule that reads it.
    */
   public static void noteRefusalForTest(ServerPlayer player) {
      Track track = track(player);
      track.refusals++;
      track.refusalAt = tickOf(player);
   }

   public static void onServerStopping(MinecraftServer server) {
      // Staff first: anyone still in spectator mode is put back where they stood
      // before the world goes away, and before the records are cleared.
      try {
         restoreSpectators(server);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not restore spectators on shutdown", t);
      }
      try {
         AntiCheatStore.save(server);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not save anticheat history on shutdown", t);
      }
      clear();
   }

   public static void onLogout(ServerPlayer player) {
      TEST_MODE.remove(player.getUUID());
      TRACKS.remove(player.getUUID());
      LagCompensatedHistory.forget(player.getUUID());
      WorldSnapshotHistory.forget(player.getUUID());
      PacketEngine.forget(player.getUUID());
   }

   // ------------------------------------------------------ moderation surface


   /**
    * Grants an exemption: permission for one check to stop looking at one player
    * for a while.
    *
    * <p>This is the API the mod's own mechanics use - a boss that launches a player
    * for a scripted dash, an elevator, a realm hop - and the one staff use to rule
    * a false positive out while they work out what happened. It is deliberately
    * awkward to make permanent: it expires, it is scoped to a single check (or to
    * {@code "*"} deliberately), it records who granted it and why, and the checks
    * ask for it every time rather than latching it.
    */
   public static boolean allow(ServerPlayer player, String check, long seconds, String reason, String by) {
      if (player == null || check == null) {
         return false;
      }
      AntiCheatStore.exempt(player.getUUID(), player.getName().getString(), check, seconds, reason == null ? "unspecified" : reason, by == null ? "console" : by);
      FortuneFavorsMod.LOGGER.info(
         "[FF-AC] {} exempted {} from {} for {}s: {}", by == null ? "console" : by, player.getName().getString(), check, seconds, reason
      );
      return true;
   }

   /** Lifts every exemption matching a check, or all of them with null. */
   public static int clearExemptions(ServerPlayer player, String check) {
      return player == null ? 0 : AntiCheatStore.clearExemption(player.getUUID(), check);
   }

   public static List<AntiCheatStore.Exemption> exemptions(ServerPlayer player) {
      return player == null ? List.of() : AntiCheatStore.exemptions(player.getUUID());
   }

   /**
    * Watch mode: the alert lines for this player's failures are printed in full to
    * every watching moderator, and the per-check cooldown stops suppressing them.
    * Nothing about the player changes - this is a pair of binoculars, not a
    * punishment.
    */
   public static boolean watch(ServerPlayer staff, ServerPlayer target) {
      if (staff == null || target == null) {
         return false;
      }
      boolean added = WATCHING.add(target.getUUID());
      if (added) {
         Chat.msg(staff, "&7Now watching &f" + target.getName().getString() + "&7. Every failure they trip will be shown to you in full.");
         FortuneFavorsMod.LOGGER.info("[FF-AC] {} started watching {}", staff.getName().getString(), target.getName().getString());
      }
      return added;
   }

   public static boolean unwatch(ServerPlayer staff, ServerPlayer target) {
      if (target == null) {
         return false;
      }
      boolean removed = WATCHING.remove(target.getUUID());
      if (removed && staff != null) {
         Chat.msg(staff, "&7Stopped watching &f" + target.getName().getString() + "&7.");
      }
      return removed;
   }

   /** The players currently under watch, for the moderation screen. */
   public static java.util.Set<UUID> watched() {
      return java.util.Collections.unmodifiableSet(WATCHING);
   }

   public static boolean isWatched(UUID id) {
      return WATCHING.contains(id);
   }

   public static boolean watching(ServerPlayer staff) {
      return staff != null && !WATCHING.isEmpty();
   }

   /**
    * Puts a moderator in spectator mode next to a player, having first written
    * down where they were.
    *
    * <p>The record is what makes this safe to use: a moderator who crashes, is
    * kicked, or logs out mid-watch is put back where they stood rather than left
    * floating in somebody else's fight. It is stored in the anticheat's own file
    * for the same reason - a restart must not be able to strand staff.
    */
   public static boolean spectate(ServerPlayer staff, ServerPlayer target) {
      if (staff == null || target == null) {
         return false;
      }
      if (staff.getUUID().equals(target.getUUID())) {
         return false;
      }
      GameType previous = staff.gameMode.getGameModeForPlayer();
      AntiCheatStore.setSpectate(
         staff.getUUID(),
         new AntiCheatStore.Spectate(
            staff.getName().getString(),
            staff.level().dimension().identifier().toString(),
            staff.getX(),
            staff.getY(),
            staff.getZ(),
            staff.getYRot(),
            staff.getXRot(),
            previous.getName()
         )
      );
      // Creative and vanished rather than a spectator: a spectator cannot open a chest, break a
      // block or hold an item, and every one of those is a thing a moderator needs to do while
      // deciding. Vanished-creative is invisible to the player, collides with nothing and
      // triggers nothing, which is the whole requirement - see SpectateKit.
      if (!SpectateKit.begin(staff, target)) {
         AntiCheatStore.clearSpectate(staff.getUUID());
         return false;
      }
      // Through the write path like every other move this module makes, even though it is a
      // moderator being moved rather than a player being corrected: the alternative is a
      // teleport nobody attributed and a stand-down counted against the staff member as the
      // server failing to keep them in one place.
      writeBody(
         staff,
         track(staff),
         "the anticheat put a moderator beside the player they are watching",
         null,
         new Vec3(target.getX(), target.getY(), target.getZ()),
         staff.getDeltaMovement()
      );
      Chat.msg(
         staff,
         "&7Watching &f" + target.getName().getString()
            + "&7 - invisible, in creative, holding the kit. &eSlot 9&7 stops, or &f/ff anticheat unspectate&7."
      );
      // Deliberately nothing to the target. A spectate that announces itself is a spectate that
      // changes what the player does, which is the one thing it must not do.
      FortuneFavorsMod.LOGGER.info("[FF-AC] {} is spectating {}", staff.getName().getString(), target.getName().getString());
      return true;
   }

   /** Puts a spectating moderator back exactly where they were. */
   public static boolean unspectate(ServerPlayer staff) {
      if (staff == null) {
         return false;
      }
      AntiCheatStore.Spectate back = AntiCheatStore.spectate(staff.getUUID());
      if (back == null) {
         return false;
      }
      // The kit goes back and the gear comes out of the stash before the place is restored: a
      // moderator put back in survival with nine moderation items in their hotbar has been
      // handed a second bug.
      SpectateKit.end(staff);
      restore(staff, back);
      AntiCheatStore.clearSpectate(staff.getUUID());
      Chat.msg(staff, "&7You are back where you were, with your items.");
      return true;
   }

   /**
    * Puts a player back at a spectate snapshot: their mode first, then their
    * place. The order matters - teleporting a spectator and then changing the mode
    * drops them at the snapshot in spectator mode, which is how a moderator ends
    * up stuck.
    */
   private static void restore(ServerPlayer player, AntiCheatStore.Spectate back) {
      try {
         player.setGameMode(GameType.byName(back.mode(), GameType.SURVIVAL));
      } catch (Throwable t) {
         player.setGameMode(GameType.SURVIVAL);
      }
      try {
         MinecraftServer server = player.level().getServer();
         ServerLevel home = server == null
            ? null
            : server.getLevel(
               net.minecraft.resources.ResourceKey.create(
                  net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(back.dimension())
               )
            );
         // The rotation is set before the write, because the transition the write builds is
         // what tells the client which way to face on arrival - a rotation applied afterwards
         // would be overwritten by the position packet the client sends back.
         player.setYRot(back.yaw());
         player.setXRot(back.pitch());
         writeBody(
            player,
            track(player),
            "the anticheat put a moderator back where they were before spectating",
            home,
            new Vec3(back.x(), back.y(), back.z()),
            player.getDeltaMovement()
         );
      } catch (Throwable t) {
         writeBody(
            player,
            track(player),
            "the anticheat put a moderator back where they were before spectating",
            null,
            new Vec3(back.x(), back.y(), back.z()),
            player.getDeltaMovement()
         );
      }
   }

   public static boolean spectating(ServerPlayer player) {
      return player != null && AntiCheatStore.spectate(player.getUUID()) != null;
   }

   /**
    * Called when a player joins, before anything else looks at them: enforces a
    * timeout that was handed out while they were offline, and puts a moderator
    * who disconnected mid-watch back where they were.
    *
    * @return true when the player was disconnected and must not be let in.
    */
   public static boolean onJoin(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      AntiCheatStore.Record record = AntiCheatStore.get(player.getUUID());
      if (record != null && record.timedOut(System.currentTimeMillis())) {
         long left = (record.timeoutUntil - System.currentTimeMillis()) / 1000L;
         disconnect(player, AntiCheatStore.KICK_TIMEOUT + " (" + left + "s)");
         return true;
      }
      // A kick with a window on it, which is how either kind is felt rather than undone by
      // reconnecting. Without this the kick was a disconnect the player could undo by
      // reconnecting, so from the outside it looked like the anticheat had simply stopped
      // kicking people.
      //
      // Both kinds are held here and they are named differently, because the automatic ladder's
      // rung is not a moderator's action: a player told "you were kicked by a moderator" for
      // something no moderator did has been told a lie about a person, and the record is the
      // only thing that can tell the two apart.
      long kicked = AntiCheatStore.kickHoldsFor(record, System.currentTimeMillis());
      if (kicked > 0L) {
         boolean ladder = AntiCheatStore.WARN_KICK.equals(AntiCheatStore.kickHoldType(record, System.currentTimeMillis()));
         disconnect(
            player,
            (ladder ? "The anticheat kicked you" : "You were kicked by a moderator")
               + " (" + ((kicked + 999L) / 1000L) + "s)"
         );
         return true;
      }
      AntiCheatStore.Spectate back = AntiCheatStore.spectate(player.getUUID());
      AntiCheatStore.clearSpectate(player.getUUID());
      if (back != null) {
         restore(player, back);
         Chat.msg(player, "&7You were spectating when you left - you are back where you were.");
      }
      return false;
   }

   /** Puts every spectating moderator back. Called on shutdown. */
   public static void restoreSpectators(MinecraftServer server) {
      for (Map.Entry<UUID, AntiCheatStore.Spectate> entry : List.copyOf(AntiCheatStore.spectates().entrySet())) {
         ServerPlayer staff = server == null ? null : server.getPlayerList().getPlayer(entry.getKey());
         if (staff != null) {
            restore(staff, entry.getValue());
         }
         AntiCheatStore.clearSpectate(entry.getKey());
      }
   }

   /** Exactly who is watching whom, for {@code /ff anticheat status}. */
   public static List<String> watchers() {
      List<String> out = new ArrayList<>();
      for (UUID id : WATCHING) {
         out.add("&7- &f" + id.toString().substring(0, 8));
      }
      return out;
   }

   /**
    * Everything known about one player, for {@code /ff anticheat info} and the
    * moderation screen: their levels, the confidence on each, the evidence behind
    * it, any live exemption and any punishment.
    */
   public static List<String> report(ServerPlayer player) {
      List<String> out = new ArrayList<>();
      long now = System.currentTimeMillis();
      AntiCheatStore.Record record = AntiCheatStore.get(player.getUUID());
      out.add("&6&lAntiCheat: &f" + player.getName().getString());
      out.add("&7uuid: &f" + player.getUUID());
      out.add("&7checked: &f" + (exempt(player) ? "&cno (exempt)" : "&ayes") + " &8| &7watched: &f" + WATCHING.contains(player.getUUID()));
      out.add("&7server: &f" + round(PerfMonitor.tps()) + " tps / " + round(PerfMonitor.mspt()) + " mspt");
      try {
         out.add("&7ping: &f" + (player.connection == null ? "?" : player.connection.latency() + "ms"));
      } catch (Throwable ignored) {
      }
      Track track = TRACKS.get(player.getUUID());
      if (track != null) {
         for (String check : CHECKS) {
            Track.Violation violation = track.violations.get(check);
            if (violation == null || violation.count == 0) {
               continue;
            }
            out.add(
               "&7- &f" + check + " &7vl &f" + round(violation.level) + " &7confidence &f" + percent(violation.confidence)
                  + " &8x" + violation.count + " &8(" + firedAgo(violation, clock) + "&8) &7" + violation.last()
            );
         }
      } else {
         out.add("&7live levels: &fthis session has nothing recorded (the anticheat may be off)");
      }
      if (record != null) {
         out.add("&7history: &f" + record.total() + " failure(s) on file, worst vl " + round(record.worstLevel()));
         out.add("&7today: &f" + record.dailyTriggers + " trigger(s), enforcement stage " + record.stage + " &8(next reset in " + (AntiCheatStore.millisUntilReset(now) / 60000L) + "m)");
         AntiCheatStore.Punishment active = record.activePunishment(now);
         if (active != null) {
            out.add("&7punishment: &c" + active.type() + " &7by &f" + active.by() + " &7- " + active.reason());
         }
      }
      for (AntiCheatStore.Exemption exemption : exemptions(player)) {
         long left = (exemption.until() - System.currentTimeMillis()) / 1000L;
         out.add("&7exempt: &a" + exemption.check() + " &7for another " + left + "s &8- " + exemption.reason() + " (by " + exemption.by() + ")");
      }
      return out;
   }

   /**
    * The live model, for {@code /ff anticheat simulate}: what the checks believe
    * about this player right now, and the numbers that belief is made of. This is
    * the answer to "why did it flag me", and to "why did it not".
    */
   public static List<String> simulate(ServerPlayer player) {
      List<String> out = new ArrayList<>();
      out.add("&6&lAntiCheat simulation: &f" + player.getName().getString());
      out.add("&7speed model: &f" + speedModel(player));
      out.add("&7server: &f" + round(PerfMonitor.tps()) + " tps &8(packet and movement findings are worth less below 20)");
      out.add("&7confidence of a clean finding now: &f" + percent(confidence(player, 1.0)));
      out.add("&7packets: &f" + PacketEngine.summarize(player.getUUID()));
      out.add("&7lag history: &f" + LagCompensatedHistory.tracked() + " position history / "
         + WorldSnapshotHistory.tracked() + " local world snapshots; up to "
         + LagCompensatedHistory.MAX_SAMPLES + " samples / player; reach, scaffold and ore visibility are latency-compensated");
      Track track = TRACKS.get(player.getUUID());
      out.add(
         "&7movement: &f"
            + (track == null
               ? "no samples this session"
               : (track.speedSamples() + " samples, " + round(track.speedDistance()) + " blocks, friction "
                  + round(MovementPhysics.friction(track.speedSlipperiness())) + (track.speedAirborne() ? ", airborne" : ", grounded")))
      );
      // The two pattern windows, which are the checks an admin testing the
      // anticheat on themselves cannot otherwise see: nothing single shows up in a
      // readout of these, only their shape, and "is it watching me at all?" is the
      // question `/ff anticheat op on` exists to answer.
      out.add(
         "&7click timing: &f"
            + (track == null || track.clicks.samples() == 0
               ? "no taps yet &8(only taps faster than your weapon's own cadence count)"
               : track.clicks.samples() + " taps, mean " + round(track.clicks.meanMs()) + "ms")
      );
      out.add(
         "&7aim window: &f"
            + (track == null || track.aim.samples() == 0
               ? "no hits on a moving target yet"
               : track.aim.samples() + " hits, mean " + round(track.aim.meanDegrees()) + "deg off centre")
      );
      out.add(
         "&7server-driven movement: &f"
            + (serverMotionGrace(player) > 0L
               ? serverMotionGrace(player) + " ticks left &8(a teleport or shove this mod sent)"
               : "none")
      );
      out.add("&7exempt now: &f" + (antiCheatExempted(player) ? "&ayes" : "&7no"));
      return out;
   }

   /** True when any exemption at all is covering this player, for the readouts. */
   public static boolean antiCheatExempted(ServerPlayer player) {
      return player != null && AntiCheatStore.hasAnyExemption(player.getUUID());
   }

   // -------------------------------------------------------------- test hooks

   /** The extra distance beyond vanilla's range a hit may land, in blocks. */
   /**
    * Why this player is or is not being judged, in plain lines.
    *
    * <p>Written for the one question an empty ledger cannot answer: <i>is this player
    * clean, or is nothing looking?</i> Every gate that can swallow a detection is
    * printed with its current value, in the order it is applied, so the first line that
    * reads wrong is the bug. It is used by {@code /ff anticheat why} and by the live
    * self-test, which is deliberate: the test asserts the same numbers a human would
    * read, so a gate that closes in production closes the build.
    */
   /**
    * How long this body has been judged with nothing found, in words.
    *
    * <p>Shared by {@code /ff anticheat info}, {@code diagnostics} and the case file so
    * that a moderator reading any of the three is reading the same number - a record
    * that means one thing in one surface and another in the next is worse than no record.
    */
   public static String cleanRecordText(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      return track == null ? "not judged yet" : cleanRecordText(track);
   }

   /**
    * The same record, by id, for the surfaces that hold a UUID rather than a player -
    * notably the case file, which lists players who are not online.
    */
   public static String cleanRecordText(UUID id) {
      Track track = id == null ? null : TRACKS.get(id);
      return track == null ? "not online / not judged" : cleanRecordText(track);
   }

   private static String cleanRecordText(Track track) {
      long ticks = track.cleanTicks();
      if (ticks < 20L) {
         return "nothing found in the last second";
      }
      long seconds = ticks / 20L;
      String since = seconds < 120L
         ? seconds + "s"
         : (seconds / 60L) + "m " + (seconds % 60L) + "s";
      return "clean for " + since + (ticks >= CLEAN_RECORD_TICKS ? " (treated as a clean record)" : "");
   }

   public static List<String> diagnostics(ServerPlayer player) {
      List<String> out = new ArrayList<>();
      if (player == null) {
         out.add("no player");
         return out;
      }
      Track track = TRACKS.get(player.getUUID());
      out.add("anticheat " + (enabled() ? "ON" : "OFF") + ", operators "
         + (checksOps() ? "checked and kickable" : (ModConfig.anticheatOpKick() ? "exempt but kickable" : "exempt"))
         + ", alerts " + (AntiCheatStore.alerts() ? "on" : "off")
         + ", failsafe " + (ModConfig.anticheatFailsafe() ? "on" : "off")
         // Findings and announcements are different numbers, and the gap between them is the
         // cooldown working. A ledger that shows findings with no alert beside them is the
         // one that gets a module switched off for being invisible.
         + ", alerts sent this session: " + alertsSent + " of " + findings + " findings");
      out.add("this player: exempt=" + exempt(player) + " active=" + active(player)
         + " testmode=" + TEST_MODE.contains(player.getUUID())
         + " gamemode=" + (player.isCreative() ? "creative" : player.isSpectator() ? "spectator" : "survival-ish")
         + " flying=" + player.getAbilities().flying
         + " serverMotionGrace=" + serverMotionGrace(player) + " ticks");
      // The clean record is printed because it is the half of the correction rule a
      // moderator cannot see anywhere else: the same finding count means a different
      // thing on a body that has been watched for ten minutes and one that has just
      // logged in, and "why was he moved for that?" is usually this number.
      out.add("clean record: " + (track == null ? "not judged yet" : cleanRecordText(track))
         + " - a real setback needs " + (track == null ? CONFIRMED_FINDINGS : confirmedFindingsFor(track.cleanTicks()))
         + " findings on one check");
      out.add("last server write: " + (track == null || track.teleportSource == null
         ? "nothing has moved this body"
         : track.teleportSource + (track.teleportAckTick > 0L ? " - answered by the client" : " - not answered yet"))
         + ", corrections this session: " + (track == null ? 0L : track.corrections));
      // Printed because the difference between "nothing is being flagged" and "nothing is
      // looking" has been the wrong one twice, and because the audit this readout serves is
      // the one a moderator cannot do from the outside: how much of the stand-down on this
      // body is the anticheat's own doing.
      out.add("this module's own writes to this body: " + (track == null ? 0L : track.selfWrites)
         + (track == null || track.selfWriteReason == null ? "" : " (last: " + track.selfWriteReason + ")")
         + " - declared as ours for " + SELF_VELOCITY_TICKS + " ticks, so none of them can stand a check down");
      if (track == null) {
         out.add("no detection state for this player yet - the tick hook has never seen them");
         return out;
      }
      // The stand-down is printed as a named thing rather than a boolean, because
      // "flight" and "creative" produce identical evidence and opposite fixes.
      out.add("driving: " + (track.scripted ? "NOT the player's feet - " + track.scriptedReason : "the player's own movement")
         + (track.graceRun > 0 ? " (" + track.graceRun + " ticks stood down)" : "")
         + (track.serverGraceRun > 0
            ? " | server-driven for " + track.serverGraceRun + "/" + SERVER_GRACE_ABUSE_TICKS
               + " ticks, which is flaggable on its own"
            : ""));
      out.add("seen: dimension " + (track.lastDimension.isEmpty() ? "unknown" : track.lastDimension)
         + ", unexplained steps " + track.unexplainedSteps + " reported of " + track.teleports + " seen"
         + ", sample " + (track.speedSamples()) + "/" + SPEED_WINDOW
         + ", airTicks " + track.airTicks + "/" + FLIGHT_TICKS
         + ", waterWalk " + track.waterWalkTicks + "/" + JESUS_TICKS);
      out.add("speed: " + track.lastSpeedNote);
      out.add("speed window: last full window read " + round(track.lastObserved) + " blocks/s against an allowed "
         + round(track.lastAllowed) + ", best consecutive run " + track.lastOverTicks + "/" + SPEED_TICKS);
      out.add("alert bar: level " + AntiCheatPolicy.alertLevel() + ", confidence "
         + percent(AntiCheatPolicy.alertConfidence()) + ", cooldown " + AntiCheatPolicy.alertCooldown() + " ticks");
      if (track.violations.isEmpty()) {
         out.add("violations: none recorded");
      } else {
         for (Map.Entry<String, Track.Violation> entry : track.violations.entrySet()) {
            Track.Violation violation = entry.getValue();
            out.add("violation " + entry.getKey() + ": x" + violation.count
               + " level " + round(violation.level) + " confidence " + percent(violation.confidence)
               + " (" + firedAgo(violation, clock) + ")"
               + (violation.last().isEmpty() ? "" : " - " + violation.last()));
         }
      }
      return out;
   }

   /**
    * A synthetic movement trace pushed through the real speed window, for
    * {@code /ff anticheat probe} and for the self-test.
    *
    * <p>This is the answer to a problem no amount of reading detects: every live test
    * of a speed check needs a hacked client, so "the detector is broken" and "the
    * client is broken" look identical. A trace is arithmetic, so it separates them. It
    * drives the production window and the production model - not a copy of them - which
    * means a change that breaks detection changes this answer too.
    *
    * @param blocksPerTick the step the body takes each tick
    * @return the verdict of the last full window, or a single line saying why none
    */
   public static String probeSpeed(double blocksPerTick) {
      return probeTrace(blocksPerTick, MovementPhysics.GROUND_SLIPPERINESS, false, true, "");
   }

   /**
    * The same trace along a surface that keeps momentum, which is the floor this check used to
    * refuse.
    *
    * <p>Ice is the case the report was about: it barely slows a body at all - its friction, 0.89,
    * is almost the air's own 0.91 - so a player who arrives having just sprint-jumped keeps
    * nearly all of that speed on the ground and jumps again to renew it. Their honest step there
    * is the jump's, not the lower one a body grinding at held input decays to, and the ceiling
    * the check hands out now says so (see {@link MovementPhysics#groundedCeiling}). Driven
    * through the production window and the production ceiling, like the traces above.
    */
   public static String probeIce(double blocksPerTick) {
      return probeTrace(
         blocksPerTick, MovementPhysics.ICE_SLIPPERINESS, true, true, " on a surface that keeps momentum"
      );
   }

   /**
    * One trace through the real window, at a step, on a surface, with or without sprinting.
    *
    * <p>The accounting here is the accounting the live check does, and that is the point: a
    * probe that modelled the check differently would be able to go on agreeing with itself
    * while the check refused honest players. {@code ticksPerSample} is left at one - a probe is
    * a trace of a healthy server, and the tick-rate correction has its own test.
    */
   private static String probeTrace(
      double blocksPerTick, double slipperiness, boolean sprinting, boolean grounded, String label
   ) {
      if (!(blocksPerTick > 0.0) || !Double.isFinite(blocksPerTick)) {
         return "a trace needs a positive step";
      }
      Track track = new Track();
      double x = 0.0;
      double perSecond = 0.0;
      double allowedPerSecond = 0.0;
      boolean flagged = false;
      for (int tick = 0; tick < SPEED_WINDOW * 3; tick++) {
         track.speedSample(x, 0.0, grounded, slipperiness);
         x += blocksPerTick;
         if (track.speedSamples() < SPEED_WINDOW) {
            continue;
         }
         double travelled = track.speedDistance();
         double seconds = (track.speedSamples() - 1) / 20.0;
         double factor = MovementPhysics.speedFactor(0.1, sprinting);
         double groundedStep = Math.max(
            MovementPhysics.steadyStep(track.speedSlipperiness(), factor, false),
            MovementPhysics.groundedCeiling(track.speedGroundedSlipperiness(), factor)
         );
         int airborneTicks = track.speedAirborneSamples();
         int groundedTicks = Math.max(0, track.speedSamples() - 1 - airborneTicks);
         double allowance = MovementPhysics.windowAllowance(
            groundedStep,
            MovementPhysics.steadyStep(track.speedSlipperiness(), factor, true),
            groundedTicks,
            airborneTicks,
            1.0
         );
         perSecond = travelled / Math.max(0.05, seconds);
         allowedPerSecond = allowance / Math.max(0.05, seconds);
         if (travelled > allowance) {
            track.speedTicks++;
            if (track.speedTicks >= SPEED_TICKS) {
               flagged = true;
            }
         } else {
            track.speedTicks = Math.max(0, track.speedTicks - 1);
         }
      }
      return "trace " + round(blocksPerTick) + "/tick" + label + " = " + round(perSecond)
         + " blocks/s, model allows " + round(allowedPerSecond) + " -> "
         + (flagged ? "FLAGGED" : "not flagged");
   }

   /**
    * The whole self-check as printable lines: the honest ceilings must come back clean
    * and the hacked ones flagged, and if that pair ever stops holding, the detector is
    * broken rather than the client.
    */
   public static List<String> probeReport() {
      List<String> out = new ArrayList<>();
      out.add("&7the honest ceilings - these must never flag:");
      out.add("&8  &7" + probeLegitimate(0.2158, false, false) + " &8(vanilla walk, 4.317 b/s)");
      out.add("&8  &7" + probeLegitimate(0.2806, true, false) + " &8(vanilla sprint, 5.61 b/s)");
      out.add("&8  &7" + probeLegitimate(0.356, true, true) + " &8(vanilla sprint-jump, 7.12 b/s)");
      double iceStep = MovementPhysics.steadyStep(MovementPhysics.ICE_SLIPPERINESS, MovementPhysics.SPRINT_FACTOR, true);
      out.add("&7the floor this used to refuse - a sprint-jump's speed held on ice:");
      out.add("&8  &7" + probeHonestIce(iceStep, true) + " &8(vanilla sprint-jump step, 7.12 b/s)");
      out.add("&8  &7" + probeIce(0.6) + " &8(a 12 b/s module, on the same floor)");
      out.add("&7the hacks - these must flag:");
      out.add("&8  &7" + probeSpeed(0.35) + " &8(7.0 b/s)");
      out.add("&8  &7" + probeSpeed(0.6) + " &8(12 b/s)");
      out.add("&8  &7" + probeSpeed(1.0) + " &8(20 b/s)");
      out.add("&8  &7" + probeSpeed(2.5) + " &8(50 b/s)");
      return out;
   }

   /**
    * The same trace driven at an honest ceiling, for the fairness half of the check.
    *
    * <p>{@code airborne} is not decoration. A sprint-jump is faster than a sprint on the
    * ground and reaches it by <i>leaving</i> the ground, so a trace that called it grounded
    * would be modelling a body sliding at jump speed along a floor - which is impossible,
    * and which the model is right to flag. The honest ceilings are three, not two: a walk,
    * a sprint, and a sprint-jump, and the last one is only honest in the air.
    */
   public static String probeLegitimate(double blocksPerTick, boolean sprinting, boolean airborne) {
      return probeHonest(blocksPerTick, sprinting, airborne, MovementPhysics.GROUND_SLIPPERINESS);
   }

   /**
    * The same honest trace on a surface that keeps momentum, for the sprint-jump across ice.
    *
    * <p>{@code airborne} still means the tick ended in the air, and a body hopping along ice
    * spends most of its ticks there. What the ice adds is the other half: the ticks it spends
    * <i>on</i> the ice are judged against the speed it carried out of a jump, because on that
    * surface that is what an honest player is doing. See {@link MovementPhysics#groundedCeiling}.
    */
   public static String probeHonestIce(double blocksPerTick, boolean sprinting) {
      return probeHonest(blocksPerTick, sprinting, false, MovementPhysics.ICE_SLIPPERINESS);
   }

   private static String probeHonest(
      double blocksPerTick, boolean sprinting, boolean airborne, double slipperiness
   ) {
      Track track = new Track();
      double x = 0.0;
      boolean flagged = false;
      for (int tick = 0; tick < SPEED_WINDOW * 3; tick++) {
         track.speedSample(x, 0.0, !airborne, slipperiness);
         x += blocksPerTick;
         if (track.speedSamples() < SPEED_WINDOW) {
            continue;
         }
         double travelled = track.speedDistance();
         double seconds = (track.speedSamples() - 1) / 20.0;
         double factor = MovementPhysics.speedFactor(0.1, sprinting);
         double groundedStep = Math.max(
            MovementPhysics.steadyStep(track.speedSlipperiness(), factor, false),
            MovementPhysics.groundedCeiling(track.speedGroundedSlipperiness(), factor)
         );
         int airborneTicks = track.speedAirborneSamples();
         int groundedTicks = Math.max(0, track.speedSamples() - 1 - airborneTicks);
         double allowance = MovementPhysics.windowAllowance(
            groundedStep,
            MovementPhysics.steadyStep(track.speedSlipperiness(), factor, true),
            groundedTicks,
            airborneTicks,
            1.0
         );
         if (travelled > allowance) {
            flagged = true;
         }
      }
      return (sprinting ? "sprint" : "walk") + (airborne ? " in the air" : " on the ground")
         + " at " + round(blocksPerTick) + "/tick -> "
         + (flagged ? "FLAGGED (this is the bug)" : "clean");
   }

   public static double reachMargin() {
      return REACH_MARGIN;
   }



   /** Vanilla allows range + 3.0 server-side; this is the strict comparison. */
   public static boolean reachExceeds(double distance, double range) {
      return distance > range + REACH_MARGIN;
   }

   public static int speedTickLimit() {
      return SPEED_TICKS;
   }

   public static int flightTickLimit() {
      return FLIGHT_TICKS;
   }

   /** Ticks of false ground before air-walk is called. */
   public static int airWalkTickLimit() {
      return FAKE_GROUND_TICKS;
   }

   /** The shortest airborne arc whose length is judged at all. */
   public static int longJumpMinTicks() {
      return LONGJUMP_MIN_TICKS;
   }

   /** Mid-air re-jumps needed, in one window, before it is a bunny-hop macro. */
   public static int bunnyHopEvents() {
      return BUNNYHOP_EVENTS;
   }

   /** Ticks of descent that have to come before an ascent counts as a second jump. */
   public static int bunnyHopMinDescent() {
      return BUNNYHOP_MIN_DESCENT;
   }

   /** Ticks of walking a water surface before it is called. */
   public static int jesusTickLimit() {
      return JESUS_TICKS;
   }

   public static int scaffoldLimit() {
      return SCAFFOLD_LIMIT;
   }

   public static int oreVisionMinimum() {
      return ORE_VISION_MIN;
   }

   /**
    * How many blocks a session has to cut before a buried-ore finding means anything.
    *
    * <p>The second half of the ore-vision bar, and the half that stops the check crying wolf: a
    * body that has broken eleven blocks and met three buried ores is somebody who cannot find their
    * way out of a cave, and calling that x-ray is how a check earns a reputation it never loses.
    */
   public static int oreVisionNeedsRock() {
      return ORE_YIELD_MIN_BLOCKS;
   }

   /** How far past vanilla's block range a placement may land. */
   public static double blockReachMargin() {
      return BLOCK_REACH_MARGIN;
   }

   /** Blocks of descent that must leave a mark to be believed. */
   public static double noFallDrop() {
      return NO_FALL_DROP;
   }

   /** Disconnects a player with a message. Only the failsafe and a timeout use this. */
   /**
    * A removal a moderator asked for from the kit: the same disconnect, with the reason said to
    * the player first. Public because the kit lives in its own class and the door it must use is
    * this one - a second way to remove a player is a second set of reasons nobody reads.
    */
   /**
    * A moderator's kick: attributed, filed, and felt.
    *
    * <p>This is the one kick that is a real action rather than a ladder rung, so it gets a
    * re-entry window ({@link AntiCheatStore#KICK_REENTRY_MILLIS}) and the join path enforces
    * it. Every deliberate kick in the mod goes through here - the kit's button and
    * {@code /ff anticheat kick} - so there is one behaviour and not two.
    */
   public static boolean kick(ServerPlayer target, String reason, String by) {
      if (target == null) {
         return false;
      }

      String why = (reason == null || reason.isBlank()) ? "no reason given" : reason;
      long until = System.currentTimeMillis() + AntiCheatStore.KICK_REENTRY_MILLIS;
      AntiCheatStore.punish(target.getUUID(), target.getName().getString(), AntiCheatStore.KICK, why, by, until);
      // The record is filed either way, and that is what keeps the re-entry window true even if
      // the body turns out to be gone - but the return value is the honest answer to "did that
      // do anything", because "the kick does not work" and "the kick worked and they came
      // straight back" are indistinguishable from the outside otherwise.
      boolean removed = kickQuietly(target, "Kicked by " + by + ": " + why);
      FortuneFavorsMod.LOGGER.info(
         "[FF-AC] {} kicked {} ({}), connection closed: {}", by, target.getName().getString(), why, removed
      );
      return removed;
   }

   /**
    * The same removal without the moderation record or the return value's ambiguity.
    *
    * @return whether the connection was actually closed - see {@link #disconnect}
    */
   public static boolean kickQuietly(ServerPlayer player, String reason) {
      if (player == null) {
         return false;
      }
      Chat.raw(player, "&c&l[AC] &c" + reason);
      return disconnect(player, reason);
   }

   /**
    * The kit's "go to them": one attributed position write, so a moderator moving is not read
    * as a moderator teleporting. Returns false rather than throwing when the move does not take.
    */
   public static boolean teleportStaffTo(ServerPlayer staff, ServerPlayer target) {
      if (staff == null || target == null) {
         return false;
      }
      try {
         writeBody(
            staff,
            track(staff),
            "the moderation kit moved a moderator to the player they are watching",
            null,
            new Vec3(target.getX(), target.getY(), target.getZ()),
            staff.getDeltaMovement()
         );
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Cuts a connection, and <b>says which of the two things happened</b>.
    *
    * <p>The old body did its work inside an {@code if} and swallowed everything else, so the
    * three ways a kick can fail to remove anybody - the body already gone, the connection
    * already null, the call throwing - all looked exactly like a successful kick from every
    * caller, every log line and every test. That is the state in which "the anticheat kick
    * does not work" is unfalsifiable, so the failure modes are named here instead.
    *
    * @return true when the connection was closed, false when there was nothing to close
    */
   private static boolean disconnect(ServerPlayer player, String message) {
      if (player == null) {
         return false;
      }
      try {
         if (player.connection == null) {
            FortuneFavorsMod.LOGGER.warn(
               "[FF-AC] asked to disconnect {} but the connection is already gone - nothing was cut: {}",
               player.getName().getString(), message
            );
            return false;
         }
         player.connection.disconnect(Component.literal(Chat.colorize("&c" + message)));
         FortuneFavorsMod.LOGGER.info("[FF-AC] disconnected {}: {}", player.getName().getString(), message);
         return true;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error(
            "Fortune & Favors: could not disconnect {} - the kick was filed but not delivered",
            player.getName().getString(), t
         );
         return false;
      }
   }

   private static String percent(double value) {
      return Math.round(value * 100.0) + "%";
   }

   /**
    * How many times one check has failed on this player. The ledger is a display
    * of the same number; this is the number, so a test can assert the file was
    * written rather than that a line of chat appeared.
    */
   public static int violations(ServerPlayer player, String check) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      Track.Violation violation = track == null ? null : track.violations.get(check);
      return violation == null ? 0 : violation.count;
   }

   /**
    * How long ago a check last fired, in the words a moderator reads.
    *
    * <p>"silent since" is not the same finding as "still firing", and the alert cooldown
    * means the alerts alone cannot tell them apart - which is how a check that had fired
    * once and was firing every second afterwards came to be described as "spotted once
    * and never again".
    */
   private static String firedAgo(Track.Violation violation, long now) {
      if (violation.lastTick <= Long.MIN_VALUE / 8) {
         return "never";
      }
      long ticks = Math.max(0L, now - violation.lastTick);
      if (ticks < 20L) {
         return "still firing";
      }
      if (ticks < 1200L) {
         return "last " + (ticks / 20L) + "s ago";
      }
      return "last " + (ticks / 1200L) + "min ago";
   }

   /**
    * One check's accumulated level, which is what a correction is spent against. Exposed
    * for the readouts and for the test that has to tell "worth more" from "worth the
    * same", since the finding count alone cannot show the weight a finding carried.
    */
   public static double levelOf(ServerPlayer player, String check) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      Track.Violation violation = track == null ? null : track.violations.get(check);
      return violation == null ? 0.0 : violation.level;
   }

   /**
    * How many times this module has physically moved this body, for a test that has to
    * tell "stop correcting them" from "correcting them the same number of times".
    */
   public static long corrections(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      return track == null ? 0L : track.corrections;
   }

   /** What last wrote this player's position, or null when nothing has. */
   public static String teleportSource(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      return track == null ? null : track.teleportSource;
   }

   /** Teleports seen at the server's own API this session, for the wiring readouts. */
   public static long serverWrites() {
      return serverWrites;
   }

   /**
    * Findings that reached the alert path, which is where a cooldown can hide one.
    *
    * <p>Counted rather than only logged because "the check fired and nobody was told" is
    * indistinguishable from "the check did not fire" at the console, and it is the second
    * of those that gets a module switched off.
    */
   public static long alertsSent() {
      return alertsSent;
   }

   /**
    * Every write this module has made to this body, of any kind.
    *
    * <p>Pinned by a self-test that drives each of the three corrections and requires this
    * to move for each of them, so a future correction cannot be added outside
    * {@link #writeBody} and quietly start standing the checks down again.
    */
   public static long bodyWrites(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      return track == null ? 0L : track.selfWrites;
   }

   /**
    * What the dodge check currently believes, for the readouts and the self-test.
    *
    * <p>Its evidence is a coincidence, which means "it did not fire" has two very different
    * causes - nothing was in the air, or the body did not turn - and a moderator looking at a
    * player who swore they were dodging arrows needs to know which.
    */
   public static String dodgeReadout(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      if (track == null) {
         return "dodge: no record";
      }
      long now = tickOf(player);
      return "dodge: lastStep=(" + round(track.stepX) + "," + round(track.stepZ) + ")"
         + " threat=" + (track.threatTick == Long.MIN_VALUE / 4 ? "never" : (now - track.threatTick) + "t ago")
         + " dodges=" + track.dodges
         + " windowOpen=" + (now - track.dodgeWindow <= DODGE_WINDOW);
   }

   /** What this module's last write to this body was, in words, or null if it has not. */
   public static String lastOwnWrite(ServerPlayer player) {
      Track track = player == null ? null : TRACKS.get(player.getUUID());
      return track == null ? null : track.selfWriteReason;
   }

   /**
    * When the break at this position was started, or null when none is being
    * tracked. A test hook with a purpose: the module that records this reads a
    * packet the server only ever sees from a real client, so the self-test drives
    * one through {@code ServerPlayerGameMode} and checks it landed here.
    */
   /**
    * One finished break: when the client said it stopped, and how long it said the break took.
    *
    * <p>The timestamp is kept as well as the length because the length is a measurement and the
    * timestamp is what says whether it is still one - a claim that is several minutes old belongs
    * to a block somebody else took.
    */
   private record BreakClaim(long atTick, long lengthTicks) {
   }

   /** The length, in server ticks, the client claimed for the break at this block. */
   public static Long breakClaimTicks(ServerPlayer player, BlockPos pos) {
      Track track = TRACKS.get(player.getUUID());
      BreakClaim claim = track == null ? null : track.breakClaims.get(pos.asLong());
      return claim == null ? null : claim.lengthTicks();
   }

   public static Long breakStartTick(ServerPlayer player, BlockPos pos) {
      Track track = TRACKS.get(player.getUUID());
      return track == null ? null : track.breakStarts.get(pos.asLong());
   }

   // ------------------------------------------------------------------ helpers

   private static Track track(ServerPlayer player) {
      return TRACKS.computeIfAbsent(player.getUUID(), id -> new Track());
   }

   /**
    * The detection clock. Set by {@link #observe} at the tick boundary and read by
    * everything else, so a grace window, a cooldown and a timestamp are all measured
    * against the same counter rather than against a coincidence between two of them.
    */
   private static long tickOf(ServerPlayer player) {
      return clock;
   }

   private static double eyeDistance(ServerPlayer player, net.minecraft.world.phys.AABB box) {
      Vec3 eye = player.getEyePosition();
      double dx = Math.max(0.0, Math.max(box.minX - eye.x, eye.x - box.maxX));
      double dy = Math.max(0.0, Math.max(box.minY - eye.y, eye.y - box.maxY));
      double dz = Math.max(0.0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
      return Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   private static String round(double value) {
      return String.format(java.util.Locale.ROOT, "%.2f", value);
   }

   /** Everything remembered about one player. */
   private static final class Track {
      private double lastX;
      private double lastY;
      private double lastZ;
      private boolean hasLast;
      private double safeX;
      private double safeY;
      private double safeZ;
      private boolean hasSafe;
      private long teleportTick = Long.MIN_VALUE / 4;
      private int airTicks;
      private int noFallTicks;
      /** Where the current airborne run started, so a hover can be told from a descent. */
      private double airStartY;
      /** Whether the airborne run has fallen at all - the shape a hop closes on. */
      private boolean airArcFell;
      private int speedTicks;
      private double auraScore;
      /** Consecutive hits whose aim ray missed the box - see {@link #HITBOX_MISSES}. */
      private int hitboxMisses;
      private double lastAuraAngle;
      private UUID lastTargetId;
      private long lastAttackTick;
      private int scaffoldCount;
      private long scaffoldWindow;
      private final Map<Long, Long> breakStarts = new HashMap<>();
      /**
       * The length each finished break was claimed to have taken, in server ticks, by position.
       *
       * <p>Set by the stop packet and consumed by the destroy, which can be several ticks later -
       * vanilla holds the block and finishes it on its own progress clock when the client's claim
       * outran it - so the claim has to outlive the packet that made it. Removed on an abandon and
       * aged out with the starts, so a claim that is never consumed cannot be measured against a
       * later break.
       */
      private final Map<Long, BreakClaim> breakClaims = new HashMap<>();
      /** Start ticks of this body's short breaks, newest last - see
       *  {@link #FAST_MINE_CORROBORATION}. */
      private final Deque<Long> fastBreakEvents = new ArrayDeque<>();
      private int oresMined;
      private int unseenOres;
      /** Buried ores that were actually worth burying - see the ore-vision bar. */
      private int unseenValuable;
      private int valuableOres;
      private int blocksMined;
      private int stoneSinceValuable;
      private long lastMineTick;
      private double fallStartY;
      private int fallTicks;
      private boolean fallActive;
      /** The airborne arc being measured: how long, how far, and off what. */
      private boolean arcActive;
      private int arcTicks;
      private double arcDistance;
      private double arcSlip = MovementPhysics.GROUND_SLIPPERINESS;
      private double arcFactor = 1.0;
      /** The step the body actually had when it left the ground, so a fast take-off raises
       *  its own allowance instead of looking like a long-jump hack. */
      private double arcTakeoffStep;
      private double lastHorizontal;
      /** Ticks of descent inside the current fall, for the mid-air re-jump test. */
      private int descentTicks;
      private final Deque<Long> hopEvents = new ArrayDeque<>();
      /** Ticks spent walking a water surface without entering it. */
      private int waterWalkTicks;
      /**
       * The last tick the wings were out, plus the tail above.
       *
       * <p>Set while the body is fall flying and read by {@link #scriptedReason}, so the
       * boundary ticks around a glide are the wings' work rather than a body that is
       * inexplicably fast - and named as such, because the ledger has to say why a check
       * stood down rather than quietly looking away from a fast body.
       */
      private long glideUntil = Long.MIN_VALUE / 4;
      /** Whether the glide this body is in, or just left, began late - see the claim above. */
      private boolean lateGlide;
      private final Deque<Boolean> yieldWindow = new ArrayDeque<>();
      /**
       * The broken blocks of one ore family inside the rush window, newest last.
       *
       * <p>Bounded by the window rather than by a size: entries older than
       * {@link #XRAY_WINDOW_TICKS} are dropped on the way in, so the deque holds exactly what the
       * count should be and nothing has to be pruned on a timer. A body that mines twelve diamonds
       * an hour for a month carries at most one entry in it.
       */
      private final Deque<Long> diamondRush = new ArrayDeque<>();
      private final Deque<Long> debrisRush = new ArrayDeque<>();
      private boolean knockPending;
      /**
       * The tick the hit landed.
       *
       * <p>Kept because the knockback's own motion packet has to be told apart from any
       * other shove that arrives during the ten ticks the measurement runs for.
       */
      private long knockStartTick = Long.MIN_VALUE / 4;
      private long knockUntil;
      private double knockX;
      private double knockZ;
      private double knockExpected;
      private double knockTravel;
      private double prevX;
      private double prevZ;
      /**
       * The body's velocity as of the end of the previous server tick.
       *
       * <p>The knockback check measures the <i>change</i> a hit made to this rather than
       * the velocity itself, because a body's velocity at the moment it is struck is
       * mostly the player's own feet: sprinting away from an attacker at 0.35 blocks a
       * tick read as a shove worth a third of a block of travel, and a player who then
       * stopped - or stood still to drop a stack - had "kept none of it".
       */
      private double velX;
      private double velZ;
      private boolean hasVel;
      private long setbackTick = Long.MIN_VALUE / 4;
      private long enforcedAt = Long.MIN_VALUE / 4;
      /**
       * How many enforcement-level findings this player has produced recently.
       *
       * <p>A single enforcement-level reading used to be enough to remove somebody from the
       * game, and the reported experience of that is being kicked the moment you do
       * something the server measured wrongly. One measurement is not a pattern: the
       * connection is only cut once the finding has been made {@link #ENFORCE_STRIKES}
       * times inside {@link #ENFORCE_STRIKE_WINDOW_NANOS}, and the strikes in between are
       * said to the player out loud and written to the ledger.
       */
      private int enforceStrikes;
      private long strikeWindowAt = Long.MIN_VALUE / 4;
      /** How many times this module has moved this body. A test hook, and the number a
       *  moderator wants when a player says the anticheat will not leave them alone. */
      private long corrections;
      /**
       * Until this tick, the server-driven movement on this body is this module's own
       * correction rather than anybody else's teleport.
       *
       * <p>Kept because the two look identical from the tick loop and mean the opposite
       * thing: a server that keeps repositioning a body is a finding, and a server that
       * keeps repositioning a body <i>because this module keeps refusing its movement</i>
       * is the module talking to itself.
       */
      private long correctionUntil = Long.MIN_VALUE / 4;
      /**
       * Refusals inside the current stretch of server-driven movement, and when the last one was.
       *
       * <p>A refusal is a setback this module actually performed - it asked the client to take a
       * position it had not claimed. That is the evidence the constant-correction rule was always
       * describing, and counting it rather than counting ticks of stand-down is the difference
       * between catching a body that keeps arriving somewhere impossible and flagging one that a
       * boss is throwing across an arena. See {@link #SERVER_GRACE_REFUSALS}.
       */
      private int refusals;
      private long refusalAt = Long.MIN_VALUE / 4;
      /**
       * The tick this module last wrote this body's velocity on, which is the tick the
       * client is about to be told about it.
       *
       * <p>Kept because the packet that follows a correction is indistinguishable from the
       * packet that follows being launched, and the movement checks stand down for the
       * second one. Mistaking a correction for a shove made every clamp silence the check
       * that made it - see {@link #onOutboundVelocity}.
       */
      private long selfVelocityTick = Long.MIN_VALUE / 4;
      /** The tick this module last wrote this body, and what to call that write. */
      private long selfWriteTick = Long.MIN_VALUE / 4;
      private String selfWriteReason;
      /** Every write this module has made to this body, for the audit readouts. */
      private long selfWrites;
      /** What last moved this body, in the words a moderator should read. */
      private String teleportSource;
      /** When the client answered the server's last position, or zero. */
      private long teleportAckTick;
      /**
       * When that teleport's own stand-down was due to end.
       *
       * <p>The client's answer may only shorten <i>this</i> window. A shove that arrives
       * afterwards opens a longer one, and comparing these two numbers is how the code knows
       * the answer has stopped applying - which is structural, rather than a coincidence of
       * two grace constants happening to be set to the same value.
       */
      private long teleportWindowEnd = Long.MIN_VALUE / 4;
      /**
       * Until this tick, a position this module wrote to this body is still the reason the
       * movement checks are standing down.
       *
       * <p>Separate from {@code serverMotionUntil} on purpose, because the two answer what
       * to do with the <i>windows</i>: a teleport the server sent breaks them, and a
       * correction this module made does not - see the speed block in {@link #movement}.
       */
      private long ownPositionUntil = Long.MIN_VALUE / 4;
      /** Ticks of unexplained steps, and the window they are counted over. */
      private int unexplainedSteps;
      private long unexplainedWindow = Long.MIN_VALUE / 4;
      /** Until this tick, this body is being moved by the server, not by the
       *  player - a teleport or a velocity this mod sent it. Set from the
       *  outbound packet layer, which is the only place that knows for certain. */
      private long serverMotionUntil = Long.MIN_VALUE / 4;
      /** The speed window: the last second of steps, in order. */
      private final double[] windowX = new double[SPEED_WINDOW];
      private final double[] windowZ = new double[SPEED_WINDOW];
      /**
       * The surface and the airborne flag each sample was taken on, kept per slot
       * rather than as one latched value.
       *
       * <p>This is a real bug that was reported from play and it is worth being
       * precise about, because neither of the old fields looked wrong on its own.
       * They were a boolean and a double that were <i>set</i> on every sample and
       * only ever <i>cleared</i> when the whole window was thrown away - so the
       * window's idea of the ground was the ground of the worst tick that had ever
       * been in it, and once a player jumped, every window they produced for the
       * rest of the session was judged as a jump. A jump's ceiling is 28 percent
       * above a sprint's, which is the size of the gap a speed module lives in: the
       * check was not wrong about the physics, it had simply stopped looking at the
       * physics it was given. Reading the window's own samples back fixes it, and
       * the samples age out with the steps they belong to.
       */
      private final boolean[] windowGrounded = new boolean[SPEED_WINDOW];
      private final double[] windowSlip = new double[SPEED_WINDOW];
      private int windowIndex;
      private int windowCount;
      /** Per-tick ground-motion samples for the simulation check. */
      private final Deque<Boolean> gainEvents = new ArrayDeque<>();
      private long gainWindow;
      private double previousStep;
      private boolean hasPreviousStep;
      /** Consecutive rises past the jump impulse. */
      private int riseTicks;
      /** Whether the body was on the ground on the previous tick, which is what makes
       *  the per-tick friction term the right one. */
      private boolean groundedBefore;
      /** Ticks of a held slowing item while the body kept most of its speed. */
      private int noSlowTicks;
      /** Consecutive ticks of climbing faster than the game's own constant. */
      private int climbTicks;
      private double climbPeak;
      /** The item use being timed, for the fast-use check. */
      private int useTicks;
      private int useDuration;
      private int useItemCount;
      private int useRemaining;
      private String useItemId = "";
      /**
       * The hand the tracked use item was held in, so the end of the use is judged against the same
       * hand it started in. Null until the first use is seen.
       */
      private InteractionHand useHand;
      /** Altitude the fall model says the body should be at, and where it is. */
      private double predictedDrift;
      private double predictionVelocity;
      private int predictionTicks;
      /** Whether the current airborne arc has come down at least once - see
       *  {@link #prediction}: a hop that lands between two samples is only readable
       *  as a fresh jump once the arc it interrupts has already fallen. */
      private boolean predictionFell;
      /** Consumable uses, for the fast-use check. */
      private long useStartedTick = Long.MIN_VALUE / 4;
      private boolean useActive;
      private int lastFoodLevel = -1;
      private float lastSaturation = -1.0F;
      private double sprintedBlocks;
      private int hungerTicks;
      /**
       * How much of the distance counted above was run without the server being told.
       *
       * <p>Kept apart from the total because it answers a different question in the alert:
       * the total says "this much running was not paid for", and this says "and this is how
       * much of it the client was hiding". A no-hunger module is the second number; a
       * player whose food simply did not tick is the first.
       */
      private double hiddenSprintBlocks;
      /** Ground claims made mid-fall, and how high above anything they were made. */
      private int fallClaims;
      private double fallClaimAbove;
      /** The last step this body took, for the turn between two of them. */
      private double stepX;
      private double stepZ;
      /** The tick a projectile last became unavoidable, and the dodges since the window opened. */
      private long threatTick = Long.MIN_VALUE / 4;
      private long dodgeWindow = Long.MIN_VALUE / 4;
      private int dodges;
      /** Swings inside the current tick, for the burst checks. */
      private long swingTick = Long.MIN_VALUE / 4;
      private int swingCount;
      private final Set<UUID> swingTargets = new HashSet<>();
      /**
       * One-tick swing bunches, and when the count started.
       *
       * <p>A burst on its own is a delivery and not a behaviour - a server whose tick ran late
       * hands the same second's clicks over in one go - so a burst is only carried when it keeps
       * happening: see {@link #SWING_BURSTS}.
       */
      private long swingBurstWindow = Long.MIN_VALUE / 4;
      private int swingBursts;
      /** The rotation window, for the aim-pattern checks. */
      private final CombatStats.Rotation rotation = new CombatStats.Rotation();
      /** Click intervals, for the autoclicker statistics. */
      private final CombatStats.Clicks clicks = new CombatStats.Clicks();
      /** How well the connecting hits were aimed, for the aim statistics. */
      private final CombatStats.Aim aim = new CombatStats.Aim();
      private final Map<String, Violation> violations = new LinkedHashMap<>();
      private final Map<String, Long> alertedAt = new HashMap<>();
      /**
       * How many findings this check had the last time it alerted.
       *
       * <p>Kept so that a check which is still firing can cut through its own cooldown: a
       * count that has doubled is not the same alert being repeated, it is a second round
       * of evidence, and staff who only ever see the first message cannot tell a player
       * who flagged once from a player who is flagging right now.
       */
      private final Map<String, Integer> alertedCount = new HashMap<>();
      private long lastViolation;
      /**
       * Judged ticks, and the value of that counter when this body was last found doing
       * something.
       *
       * <p>The difference is the clean record {@link #cleanTicks()} reads, and it is
       * counted rather than derived from the world clock so that a relog, a restart or a
       * server running its ticks at half speed cannot manufacture a clean ten minutes for
       * somebody nobody has actually watched. A fresh track starts at zero, which is the
       * correct answer: a body nobody has judged has not been clean, it has not been
       * looked at.
       */
      private long judgedTicks;
      private long judgedAtLastFinding;

      /** How many judged ticks since this body was last found doing anything. */
      private long cleanTicks() {
         return Math.max(0L, this.judgedTicks - this.judgedAtLastFinding);
      }
      /** Why this body was not judged on the last tick, or null when it was. A nil
       *  ledger has exactly two causes - nothing cheated, or nothing looked - and
       *  without this field the two are indistinguishable from the outside. */
      private String silence;
      /** The stand-down that applied to the last movement tick, by name. */
      private boolean scripted;
      private String scriptedReason;
      /** The last speed window, kept for the readout so "why did this not fire" is
       *  answerable without guessing at the model. */
      private int lastWindowSamples;
      private double lastObserved;
      private double lastAllowed;
      private int lastOverTicks;
      private String lastSpeedNote = "no movement judged yet";
      private long lastUnservedStep = Long.MIN_VALUE / 4;
      /** Ticks this body has spent standing down since the last judged tick. */
      private int graceRun;
      private int serverGraceRun;
      private int teleports;
      private String lastDimension = "";

      private void setLast(double x, double y, double z) {
         this.lastX = x;
         this.lastY = y;
         this.lastZ = z;
      }

      private void setSafe(double x, double y, double z) {
         this.safeX = x;
         this.safeY = y;
         this.safeZ = z;
         this.hasSafe = true;
      }

      /**
       * Ends a hunger window, starting the next one from the food the player has now.
       *
       * <p>Negative food means "no baseline", which is the state a body starts in and the
       * state a scripted or exempt tick leaves it in - the next window then begins with a
       * reading rather than with a comparison against a number from another life.
       */
      private void resetHunger(int food, float saturation) {
         this.sprintedBlocks = 0.0;
         this.hiddenSprintBlocks = 0.0;
         this.hungerTicks = 0;
         this.lastFoodLevel = food;
         this.lastSaturation = saturation;
      }

      /** One check's running state: how much, how sure, and what it saw. */
      private static final class Violation {
         private double level;
         private double confidence;
         private int count;
         /**
          * The tick this check last fired on.
          *
          * <p>Kept because the alert cooldown means the one thing staff cannot see from
          * the alerts alone is whether a check is <i>still</i> firing or fired once and
          * went quiet, and those are the two answers a moderator is choosing between.
          * "x1, forty seconds ago" and "x24, two seconds ago" are the same alert line
          * without it.
          */
         private long lastTick = Long.MIN_VALUE / 4;
         /**
          * The finding count a correction has already been spent on.
          *
          * <p>A count rather than a flag, so it means "no evidence collected since the
          * last time this check moved this body". Comparing counts is what makes the
          * rule immune to how long the level takes to decay.
          */
         private int corrected;
         /**
          * How many times this check has answered a finding with a clamp - that is, with a
          * correction that stopped the body gaining without moving it anywhere.
          *
          * <p>Counted rather than assumed because it is the measure of a client's
          * indifference: a clamp costs a module nothing, since its next packet can carry
          * the speed it wanted, so a body that keeps producing findings after three clamps
          * is a body that is not being corrected at all. See
          * {@link #maybeConfirmedSetback}.
          */
         private int clamped;
         private final Deque<String> evidence = new ArrayDeque<>();

         private void push(String detail, int keep) {
            if (detail != null && !detail.isBlank()) {
               this.evidence.addFirst(detail);
            }
            while (this.evidence.size() > keep) {
               this.evidence.removeLast();
            }
         }

         private String last() {
            return this.evidence.isEmpty() ? "" : this.evidence.peekFirst();
         }
      }

      private Violation of(String check) {
         return this.violations.computeIfAbsent(check, key -> new Violation());
      }

      /** Lets every level fall, so old traffic stops counting against a player. */
      private void decay() {
         for (Violation violation : this.violations.values()) {
            violation.level = Math.max(0.0, violation.level - AntiCheatPolicy.levelDecay());
         }
      }

      private void speedSample(double x, double z, boolean grounded, double slipperiness) {
         this.windowX[this.windowIndex] = x;
         this.windowZ[this.windowIndex] = z;
         this.windowGrounded[this.windowIndex] = grounded;
         this.windowSlip[this.windowIndex] = slipperiness;
         this.windowIndex = (this.windowIndex + 1) % SPEED_WINDOW;
         this.windowCount = Math.min(SPEED_WINDOW, this.windowCount + 1);
      }

      private void clearSpeedWindow() {
         this.windowCount = 0;
         this.windowIndex = 0;
      }

      /**
       * Moves every sample in the speed window by the same offset.
       *
       * <p>Used when <i>this module</i> moves the body. The window is a record of the
       * player's own path - it is read back as the distance between consecutive samples -
       * and a correction is not part of that path, so the samples are translated by exactly
       * the correction. The distances between them are unchanged, which is the point: the
       * check keeps the evidence that justified the correction instead of discarding it and
       * spending the next second re-earning it.
       */
      private void shiftSpeedWindow(double dx, double dz) {
         if (dx == 0.0 && dz == 0.0) {
            return;
         }
         for (int i = 0; i < SPEED_WINDOW; i++) {
            this.windowX[i] += dx;
            this.windowZ[i] += dz;
         }
      }

      private int speedSamples() {
         return this.windowCount;
      }

      /**
       * The slipperiest surface anywhere in the window, read back off the window.
       *
       * <p>A maximum rather than an average, and deliberately: a player who crossed
       * two blocks of ice inside the second keeps the ice allowance for the whole
       * window, which is the generous direction and the one that cannot false-positive.
       */
      private double speedSlipperiness() {
         double worst = MovementPhysics.GROUND_SLIPPERINESS;
         for (int i = 0; i < this.windowCount; i++) {
            int index = this.liveIndex(i);
            if (this.windowSlip[index] > worst) {
               worst = this.windowSlip[index];
            }
         }
         return worst;
      }

      /**
       * The slipperiest surface the window's <i>grounded</i> samples stood on.
       *
       * <p>Separate from {@link #speedSlipperiness}, which is a maximum over the whole window and
       * therefore reads the air out of every airborne sample. That maximum is right for the airborne
       * ceiling and is deliberately generous; it is the wrong surface to ask about a body's feet,
       * which is the only surface that can hand a grounded body the speed a jump holds.
       */
      private double speedGroundedSlipperiness() {
         double worst = MovementPhysics.GROUND_SLIPPERINESS;
         for (int i = 0; i < this.windowCount; i++) {
            int index = this.liveIndex(i);
            if (this.windowGrounded[index] && this.windowSlip[index] > worst) {
               worst = this.windowSlip[index];
            }
         }
         return worst;
      }

      /**
       * The physical index of the i-th oldest live sample.
       *
       * <p>When the ring is full the oldest sample is the one about to be overwritten,
       * so the window starts at the write cursor; before that it starts at slot zero.
       */
      private int liveIndex(int i) {
         int start = this.windowCount == SPEED_WINDOW ? this.windowIndex : 0;
         return (start + i) % SPEED_WINDOW;
      }

      /**
       * Whether most of the window was airborne.
       *
       * <p>A majority rather than any, which is the other half of the latch fix.
       * One airborne tick in twenty is a player stepping off a block mid-sprint, and
       * handing that window the jump ceiling is the same mistake as latching: it lets
       * a module hide inside a ceiling it did nothing to earn. Most of an airborne
       * window is a player who is actually jumping.
       */
      private boolean speedAirborne() {
         if (this.windowCount == 0) {
            return false;
         }
         int airborne = 0;
         for (int i = 0; i < this.windowCount; i++) {
            if (!this.windowGrounded[this.liveIndex(i)]) {
               airborne++;
            }
         }
         return airborne * 2 > this.windowCount;
      }

      /**
       * How many of the window's own steps ended in the air.
       *
       * <p>Counted over the steps the distance is summed over (every sample but the oldest,
       * which is the one that has no step into it), so the allowance and the distance are
       * measured over exactly the same ticks - see
       * {@link MovementPhysics#windowAllowance(double, double, int, int)}.
       */
      private int speedAirborneSamples() {
         if (this.windowCount < 2) {
            return 0;
         }
         int airborne = 0;
         for (int step = 1; step < this.windowCount; step++) {
            if (!this.windowGrounded[this.liveIndex(step)]) {
               airborne++;
            }
         }
         return airborne;
      }

      /** The distance walked inside the window, following the samples in order. */
      private double speedDistance() {
         if (this.windowCount < 2) {
            return 0.0;
         }
         double total = 0.0;
         int start = this.windowCount == SPEED_WINDOW ? this.windowIndex : 0;
         int previous = start;
         for (int step = 1; step < this.windowCount; step++) {
            int index = (start + step) % SPEED_WINDOW;
            double dx = this.windowX[index] - this.windowX[previous];
            double dz = this.windowZ[index] - this.windowZ[previous];
            total += Math.sqrt(dx * dx + dz * dz);
            previous = index;
         }
         return total;
      }

      private int total() {
         int sum = 0;
         for (Violation violation : this.violations.values()) {
            sum += violation.count;
         }
         return sum;
      }

   }
}
