package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.net.FfVfx;
import com.fortuneandfavors.util.FxKinds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Emerald Sovereign's three legendaries.
 *
 * <ul>
 *   <li><b>Royal Contract</b> - right-click signs it and a Royal Guard answers.
 *       The guard is a real, killable vindicator that fights for you; the paper is
 *       spent, exactly like a contract.</li>
 *   <li><b>Sovereign's Bell</b> - right-click rings it: every hostile mob around
 *       is staggered and slowed, and every villager in range is empowered.</li>
 *   <li><b>Emerald Seal</b> - carried, it mints an emerald from hostile kills and
 *       marks you as a hero of the village (vanilla's own cheaper-trade mechanism).
 *       Right-click spends five emeralds on a Royal Tribute buff.</li>
 * </ul>
 */
public final class SovereignGear {

   private static final long BELL_COOLDOWN_TICKS = 220L;
   private static final long SEAL_COOLDOWN_TICKS = 400L;
   private static final int SEAL_EMERALD_COST = 5;
   private static final double BELL_RADIUS = 16.0;
   /** The most creatures one ring of the Bell marks with a custom cue (the vanilla puff is per mob). */
   private static final int BELL_CUE_CAP = 16;
   private static final double SEAL_EMERALD_CHANCE = 0.25;
   /** The Royal Guard's lifetime, and how many may answer one contract. */
   private static final int GUARD_TICKS = 60 * 20;
   private static final int GUARD_CAP = 3;
   private static final String GUARD_TAG = "ff_contract_guard";
   private static final String GUARD_OWNER_KEY = "ff_contract_owner";

   /** Emerald: the colour every Sovereign effect is drawn in. */
   private static final int EMERALD = 0x3FD86A;
   /** The crown's gold, for the moments the village answers. */
   private static final int CROWN = 0xF2C445;
   /** How far a Royal Guard looks for something to fight. */
   private static final double GUARD_RANGE = 16.0;

   private static final Map<UUID, Long> BELL_UNTIL = new HashMap<>();
   private static final Map<UUID, Long> SEAL_UNTIL = new HashMap<>();

   private static final Random RANDOM = new Random();

   private static final class Guard {
      final UUID id;
      int ticks;

      Guard(UUID id, int ticks) {
         this.id = id;
         this.ticks = ticks;
      }
   }

   private static final Map<UUID, List<Guard>> GUARDS = new HashMap<>();

   private SovereignGear() {
   }

   // ------------------------------------------------------------- royal contract

   /** Right-click with a Royal Contract: a guard answers and the paper is spent. */
   public static String useContract(ServerPlayer player, ItemStack held) {
      String err = summonGuard(player);
      if (err != null) {
         return err;
      }
      if (!player.getAbilities().instabuild) {
         held.shrink(1);
      }
      return null;
   }

   private static String summonGuard(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return "The contract cannot be signed here.";
      }
      long live = GUARDS.getOrDefault(player.getUUID(), List.of()).stream().filter(g -> g.ticks > 0).count();
      if (live >= GUARD_CAP) {
         return "You already have " + GUARD_CAP + " Royal Guards under contract.";
      }
      Mob guard = (Mob) EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
      if (guard == null) {
         return "Nobody answered the contract.";
      }
      double angle = RANDOM.nextDouble() * Math.PI * 2.0;
      guard.setPos(player.getX() + Math.cos(angle) * 2.0, player.getY(), player.getZ() + Math.sin(angle) * 2.0);
      guard.setPersistenceRequired();
      guard.setCustomName(Component.literal("\u00a7a\u00a7l\u2694 Royal Guard of " + player.getName().getString()));
      guard.setCustomNameVisible(true);
      guard.addTag(GUARD_TAG);
      guard.addTag(GUARD_OWNER_KEY + ":" + player.getUUID());
      // Armed: a mob made with create() skips the spawn that hands a vindicator its axe, so the
      // guard used to answer the contract empty-handed.
      guard.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
      // The guard is your sword arm, not your loot: what it carries is its own.
      guard.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
      guard.setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.0F);
      // Sworn to its owner through the same tag every other friendly summon uses, which is what
      // stops it turning on them: a vindicator is hostile, and nothing told this one otherwise.
      tagFriendly(guard, player.getUUID());
      level.addFreshEntity(guard);

      var list = new java.util.ArrayList<>(GUARDS.getOrDefault(player.getUUID(), List.of()));
      list.add(new Guard(guard.getUUID(), GUARD_TICKS));
      GUARDS.put(player.getUUID(), list);

      // The contract's seal is pressed into the ground and the guard steps up out of it: a gold
      // rune ring for the wax, a green circle inside it, a shaft of light, and the paper going up
      // as a puff of cut emeralds - the price of the signature, paid in front of you.
      Fx.shape(level, FfVfx.SUMMON_CIRCLE, ParticleTypes.HAPPY_VILLAGER, guard.position().add(0.0, 0.05, 0.0), Vec3.ZERO, 1.6, 30.0, EMERALD);
      Fx.runeCircle(level, ParticleTypes.END_ROD, guard.position().add(0.0, 0.08, 0.0), 2.2, 30, CROWN);
      Fx.shape(level, FfVfx.PILLAR, ParticleTypes.HAPPY_VILLAGER, guard.position(), Vec3.ZERO, 4.0, 0.0, CROWN);
      Fx.gemShards(level, ParticleTypes.HAPPY_VILLAGER, player.position().add(0.0, 1.2, 0.0).add(player.getLookAngle().scale(0.6)), 0.7, EMERALD);
      Fx.vanilla(level, ParticleTypes.HAPPY_VILLAGER, guard.getX(), guard.getY() + 1.0, guard.getZ(), 20, 0.5, 0.8, 0.5, 0.08);
      level.playSound(null, guard.getX(), guard.getY(), guard.getZ(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.PLAYERS, 1.2F, 1.0F);
      bar(player, "&aRoyal Guard &7- his contract runs for &f60s");
      return null;
   }

   // -------------------------------------------------------------- sovereign bell

   /** Right-click with the Bell: stagger enemies, empower the village. */
   public static String useBell(ServerPlayer player, ItemStack held) {
      if (!(player.level() instanceof ServerLevel level)) {
         return null;
      }
      long now = ServerClock.clock(level);
      Long until = BELL_UNTIL.get(player.getUUID());
      if (until != null && now < until) {
         bar(player, "&7The Bell is still ringing &8(" + ((until - now + 19) / 20) + "s)");
         return null;
      }
      BELL_UNTIL.put(player.getUUID(), now + BELL_COOLDOWN_TICKS);

      double x = player.getX();
      double y = player.getY() + 1.0;
      double z = player.getZ();
      level.playSound(null, x, y, z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 2.0F, 0.8F);
      level.playSound(null, x, y, z, SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.4F, 0.7F);
      // The toll rolls outward in three rings to the edge of its reach. Three shape cues where the
      // old version sent ninety separate particle packets.
      Vec3 bell = new Vec3(x, y, z);
      Fx.shape(level, FfVfx.NOVA, ParticleTypes.NOTE, bell.add(0.0, -0.8, 0.0), Vec3.ZERO, BELL_RADIUS, 0.0, EMERALD);
      Fx.flare(level, ParticleTypes.NOTE, bell.add(0.0, 1.2, 0.0), 1.6, CROWN);
      for (int ring = 1; ring <= 3; ring++) {
         Fx.shape(level, FfVfx.RING, ParticleTypes.NOTE, bell, Vec3.ZERO, ring * 5.0, 0.0, ring == 2 ? CROWN : EMERALD);
      }
      // The bell keeps humming after the strike, and the ground under the ringer shows the reach
      // as a turning gold seal for the same beat.
      Fx.resonance(level, ParticleTypes.NOTE, bell.add(0.0, 1.0, 0.0), 30.0, CROWN);
      Fx.runeCircle(level, ParticleTypes.END_ROD, player.position().add(0.0, 0.06, 0.0), 2.4, 30, CROWN);

      int staggered = 0;
      int rung = 0;
      // Custom cues for the creatures the bell touched are capped: a mob farm in range would
      // otherwise be a packet per mob, every ring.
      int cues = 0;
      for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(BELL_RADIUS))) {
         // The ringer's own sworn mobs (Royal Guards, friendly skeletons) are not staggered by
         // their own side's bell, and the village does not take up arms against them.
         if (e instanceof LivingEntity sworn && BossManager.isFriendlySkeleton(sworn)) {
            continue;
         }
         if (e instanceof Monster hostile && hostile.isAlive()) {
            hostile.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 120, 2, false, true, true));
            hostile.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 1, false, true, true));
            hostile.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, false, true, true));
            // Dazed: notes circling its head for the first two seconds of the stagger.
            if (cues++ < BELL_CUE_CAP) {
               FfVfx.shape(level, FxKinds.PETALS, ParticleTypes.NOTE, hostile.position().add(0.0, hostile.getBbHeight() + 0.3, 0.0),
                  Vec3.ZERO, 0.6, 40.0, CROWN);
            }
            Fx.vanilla(level, ParticleTypes.NOTE, hostile.getX(), hostile.getY() + 2.0, hostile.getZ(), 6, 0.4, 0.3, 0.4, 0.08);
            staggered++;
         } else if (e instanceof Villager villager && villager.isAlive()) {
            // Royal subjects: the village answers its own bell - and it answers
            // with pitchforks, not just with buffs (see tickSubjects).
            villager.addEffect(new MobEffectInstance(MobEffects.SPEED, 400, 0, false, true, true));
            villager.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 400, 0, false, true, true));
            villager.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 400, 0, false, true, true));
            SUBJECTS.put(villager.getUUID(), now + SUBJECT_TICKS);
            // Called to arms: a gold column goes up through the villager and a green glint of
            // emeralds breaks over its head.
            if (cues++ < BELL_CUE_CAP) {
               FfVfx.shape(level, FfVfx.PILLAR, ParticleTypes.HAPPY_VILLAGER, villager.position(), Vec3.ZERO, 3.0, 0.0, CROWN);
               FfVfx.shape(level, FxKinds.GEM_SHARDS, ParticleTypes.HAPPY_VILLAGER, villager.position().add(0.0, villager.getBbHeight() + 0.2, 0.0),
                  Vec3.ZERO, 0.6, 0.0, EMERALD);
            }
            Fx.vanilla(level, ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 2.0, villager.getZ(), 14, 0.5, 0.5, 0.5, 0.06);
            rung++;
         }
      }
      bar(
         player,
         "&aBell rung &7- &f" + staggered + " &7staggered, &f" + rung + " &7villager(s) took up arms"
      );
      return null;
   }

   // -------------------------------------------------------------- royal subjects

   /**
    * Villagers empowered by the Bell, and when the crown wears off.
    *
    * <p>Villagers have no attack goal in vanilla - giving them Strength and
    * Resistance looks good on paper but leaves them standing there being hit. So a
    * Royal Subject is driven directly: it closes on the nearest hostile, and lands
    * a real pitchfork strike on a short cooldown. The damage is credited to the
    * villager, so kills read as the village's doing.
    */
   private static final Map<UUID, Long> SUBJECTS = new HashMap<>();
   private static final Map<UUID, Long> SUBJECT_STRIKE = new HashMap<>();
   private static final long SUBJECT_TICKS = 60L * 30L;
   private static final double SUBJECT_RANGE = 14.0;
   private static final float SUBJECT_DAMAGE = 4.5F;

   private static void tickSubjects(MinecraftServer server, long now) {
      if (SUBJECTS.isEmpty()) {
         return;
      }
      for (Map.Entry<UUID, Long> e : new java.util.ArrayList<>(SUBJECTS.entrySet())) {
         Entity raw = findEntity(server, e.getKey());
         if (!(raw instanceof Villager villager) || !villager.isAlive() || now > e.getValue()) {
            SUBJECTS.remove(e.getKey());
            SUBJECT_STRIKE.remove(e.getKey());
            continue;
         }
         if (!(villager.level() instanceof ServerLevel level)) {
            continue;
         }
         Monster foe = nearestFoe(level, villager);
         if (foe == null) {
            continue;
         }
         // Walk at it. Villagers do have pathfinding, they just have nothing to
         // use it on in vanilla.
         villager.getNavigation().moveTo(foe, 0.8);
         if (villager.distanceToSqr(foe) > 6.25) {
            continue;
         }
         if (now < SUBJECT_STRIKE.getOrDefault(villager.getUUID(), 0L)) {
            continue;
         }
         SUBJECT_STRIKE.put(villager.getUUID(), now + 22L);
         foe.invulnerableTime = 0;
         foe.hurtServer(level, level.damageSources().mobAttack(villager), SUBJECT_DAMAGE);
         Vec3 away = foe.position().subtract(villager.position());
         if (away.lengthSqr() > 0.01) {
            away = new Vec3(away.x, 0.0, away.z).normalize();
            foe.push(away.x * 0.7, 0.3, away.z * 0.7);
            foe.hurtMarked = true;
         }
         // The pitchfork swing: a gold crescent from the villager across the foe for modded
         // clients, the vanilla sweep for everyone else.
         Vec3 swing = foe.position().subtract(villager.position());
         FfVfx.shape(level, FxKinds.CRESCENT, ParticleTypes.CRIT, villager.position().add(0.0, villager.getBbHeight() * 0.6, 0.0),
            new Vec3(swing.x, 0.0, swing.z), 2.6, 0.0, CROWN);
         Fx.vanillaOnly(() -> {
            Fx.vanilla(level, ParticleTypes.SWEEP_ATTACK, foe.getX(), foe.getY() + 0.8, foe.getZ(), 3, 0.3, 0.3, 0.3, 0.04);
            Fx.vanilla(level, ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.6, villager.getZ(), 4, 0.3, 0.3, 0.3, 0.02);
         });
         level.playSound(null, villager.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.8F, 1.3F);
      }
   }

   private static Monster nearestFoe(ServerLevel level, Villager villager) {
      Monster best = null;
      double bestDist = SUBJECT_RANGE * SUBJECT_RANGE;
      for (Monster m : level.getEntitiesOfClass(Monster.class, villager.getBoundingBox().inflate(SUBJECT_RANGE))) {
         // A sworn mob - the ringer's own Royal Guard, a friendly skeleton - is on the village's side.
         if (!m.isAlive() || m.isSpectator() || BossManager.isFriendlySkeleton(m)) {
            continue;
         }
         double d = villager.distanceToSqr(m);
         if (d < bestDist) {
            bestDist = d;
            best = m;
         }
      }
      return best;
   }

   // ---------------------------------------------------------------- emerald seal

   /** Right-click with the Seal: spend five emeralds on a Royal Tribute buff. */
   public static String useSeal(ServerPlayer player, ItemStack held) {
      if (!(player.level() instanceof ServerLevel level)) {
         return null;
      }
      long now = ServerClock.clock(level);
      Long until = SEAL_UNTIL.get(player.getUUID());
      if (until != null && now < until) {
         bar(player, "&7Royal Tribute already sworn &8(" + ((until - now + 19) / 20) + "s)");
         return null;
      }
      if (!consumeEmeralds(player, SEAL_EMERALD_COST)) {
         return "The Seal needs " + SEAL_EMERALD_COST + " emeralds in your inventory.";
      }
      player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 200, 1, false, true, true));
      player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 1, false, true, true));
      SEAL_UNTIL.put(player.getUUID(), now + SEAL_COOLDOWN_TICKS);
      // The seal is pressed: the five emeralds break into shards in front of you, a gold helix of
      // tribute winds up around you, and the royal seal turns on the ground under your feet.
      Vec3 chest = player.position().add(0.0, 1.0, 0.0);
      Fx.gemShards(level, ParticleTypes.HAPPY_VILLAGER, chest.add(player.getLookAngle().scale(0.8)), 1.2, EMERALD);
      Fx.spiral(level, ParticleTypes.TOTEM_OF_UNDYING, player.position(), 2.4, 24, CROWN);
      Fx.runeCircle(level, ParticleTypes.END_ROD, player.position().add(0.0, 0.06, 0.0), 1.6, 30, CROWN);
      Fx.shape(level, FfVfx.CLOCK_BURST, ParticleTypes.HAPPY_VILLAGER, chest, Vec3.ZERO, 2.2, 0.0, CROWN);
      Fx.shape(level, FfVfx.RING, ParticleTypes.HAPPY_VILLAGER, player.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 1.4, 0.0, EMERALD);
      Fx.vanilla(level, ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.2, player.getZ(), 24, 0.6, 0.8, 0.6, 0.12);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.4F, 1.3F);
      bar(player, "&a&lROYAL TRIBUTE &7- Strength + Resistance for 10s");
      return null;
   }

   private static boolean consumeEmeralds(ServerPlayer player, int amount) {
      int have = 0;
      var inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).is(Items.EMERALD)) {
            have += inv.getItem(i).getCount();
         }
      }
      if (have < amount) {
         return false;
      }
      int left = amount;
      for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
         ItemStack stack = inv.getItem(i);
         if (!stack.is(Items.EMERALD)) {
            continue;
         }
         int take = Math.min(left, stack.getCount());
         stack.shrink(take);
         left -= take;
      }
      return true;
   }

   // ------------------------------------------------------------------ tick loop

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Safe.run("sovereign gear tick", () -> tickSeal(p));
      }
      tickGuards(server);
      long now = ServerClock.clock(server.overworld());
      Safe.run("sovereign royal subjects", () -> tickSubjects(server, now));
   }

   /** Carried: the Seal marks you as a hero of the village, which is how vanilla
    *  makes trades cheaper - and it mints the occasional emerald. */
   private static void tickSeal(ServerPlayer player) {
      if (!player.isAlive() || !carriesSeal(player)) {
         return;
      }
      // Hero of the Village is the vanilla trade-discount mechanism; using it
      // instead of rewriting trade costs means the discount behaves exactly like
      // the game's own and stacks with nothing it should not.
      // Topped up rather than re-applied every tick, which sent an effect packet every tick.
      MobEffectInstance hero = player.getEffect(MobEffects.HERO_OF_THE_VILLAGE);
      if (hero == null || hero.getDuration() < 30) {
         player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 80, 0, false, false, true));
      }
   }

   private static boolean carriesSeal(ServerPlayer player) {
      var inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (ModItems.isEmeraldSeal(inv.getItem(i))) {
            return true;
         }
      }
      return false;
   }

   /** Called from the kill hook: a hostile kill can mint an emerald. */
   public static void onHostileKill(ServerPlayer killer, LivingEntity victim) {
      if (killer == null || !(victim instanceof Monster)) {
         return;
      }
      if (!carriesSeal(killer)) {
         return;
      }
      if (RANDOM.nextDouble() > SEAL_EMERALD_CHANCE) {
         return;
      }
      killer.getInventory().placeItemBackInInventory(new ItemStack(Items.EMERALD, 1));
      if (killer.level() instanceof ServerLevel level) {
         // The minted emerald flies out of the kill into your pocket, and glints as it lands.
         Vec3 purse = killer.position().add(0.0, 1.1, 0.0);
         if (victim.level() == level) {
            FfVfx.shape(level, FxKinds.COMET, ParticleTypes.HAPPY_VILLAGER, victim.position().add(0.0, victim.getBbHeight() * 0.6, 0.0),
               purse, 0.0, 8.0, EMERALD);
         }
         Fx.gemShards(level, ParticleTypes.HAPPY_VILLAGER, purse, 0.5, EMERALD);
      }
   }

   /** Keeps every contracted guard alive for its term, then dismisses it. */
   private static void tickGuards(MinecraftServer server) {
      if (GUARDS.isEmpty()) {
         return;
      }
      for (Map.Entry<UUID, List<Guard>> entry : GUARDS.entrySet()) {
         ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
         for (Guard guard : entry.getValue()) {
            if (guard.ticks <= 0) {
               continue;
            }
            guard.ticks--;
            Entity raw = findEntity(server, guard.id);
            if (!(raw instanceof Mob mob) || !mob.isAlive()) {
               guard.ticks = 0;
               continue;
            }
            if (mob.level() instanceof ServerLevel level) {
               if (guard.ticks % 10 == 0) {
                  steerGuard(level, mob, owner);
               }
               if (guard.ticks % 20 == 0) {
                  // "Under contract": a small emerald glint over the guard's head once a second
                  // (a one-shot, so it never trails behind a guard on the move).
                  FfVfx.shape(level, FxKinds.FLARE, ParticleTypes.HAPPY_VILLAGER, mob.position().add(0.0, mob.getBbHeight() + 0.4, 0.0),
                     Vec3.ZERO, 0.35, 0.0, EMERALD);
                  Fx.vanilla(level, ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + 1.4, mob.getZ(), 3, 0.3, 0.3, 0.3, 0.0);
               }
               if (guard.ticks == 0) {
                  // The contract runs out: the guard's seal breaks and he is gone in a puff.
                  Fx.gemShards(level, ParticleTypes.HAPPY_VILLAGER, mob.position().add(0.0, 1.0, 0.0), 0.8, EMERALD);
                  Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 0.8, mob.getZ(), 12, 0.4, 0.4, 0.4, 0.05);
                  Fx.shape(level, FfVfx.RING, ParticleTypes.HAPPY_VILLAGER, mob.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 1.2, 0.0, EMERALD);
                  level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.PLAYERS, 1.0F, 0.7F);
                  if (owner != null) {
                     bar(owner, "&7A Royal Guard's contract has expired.");
                  }
                  mob.discard();
               }
            }
         }
         List<Guard> kept = new java.util.ArrayList<>();
         for (Guard guard : entry.getValue()) {
            if (guard.ticks > 0) {
               kept.add(guard);
            }
         }
         entry.setValue(kept);
      }
   }

   public static void onServerStopping(MinecraftServer server) {
      // The bell's call to arms is a server-lifetime ledger keyed by villager; on an integrated
      // server the statics outlive the world, so it is emptied with the guards.
      SUBJECTS.clear();
      SUBJECT_STRIKE.clear();
      for (List<Guard> guards : GUARDS.values()) {
         for (Guard guard : guards) {
            Entity raw = findEntity(server, guard.id);
            if (raw != null) {
               raw.discard();
            }
         }
      }
      GUARDS.clear();
   }

   public static void onPlayerDisconnect(UUID id) {
      SUBJECTS.entrySet().removeIf(e -> e.getKey().equals(id));
      SUBJECT_STRIKE.entrySet().removeIf(e -> e.getKey().equals(id));
      BELL_UNTIL.remove(id);
      SEAL_UNTIL.remove(id);
      // Their guards are left on the list with their time intact, so tickGuards still owns them
      // and dismisses each when its contract runs out. Removing the list here - which this used
      // to do - meant nothing tracked the guards any more and they stood in the world for good.
   }

   /**
    * Keeps a Royal Guard fighting for its owner. A vindicator's own targeting goes after
    * villagers, golems and players; the friendly tag stops the players, and this points it at
    * monsters instead - the owner's attacker first - and walks it back to the owner when idle.
    */
   private static void steerGuard(ServerLevel level, Mob guard, ServerPlayer owner) {
      LivingEntity current = guard.getTarget();
      boolean wrong = current != null && (!current.isAlive() || !(current instanceof net.minecraft.world.entity.monster.Enemy)
         || current instanceof Mob m && BossManager.isFriendlySkeleton(m));
      if (wrong) {
         guard.setTarget(null);
         current = null;
      }
      if (current != null) {
         return;
      }
      LivingEntity foe = null;
      if (owner != null && owner.getLastHurtByMob() instanceof net.minecraft.world.entity.monster.Enemy attacker
         && attacker instanceof LivingEntity a && a.isAlive() && a.distanceToSqr(guard) < GUARD_RANGE * GUARD_RANGE * 2.0) {
         foe = a;
      }
      if (foe == null) {
         double best = GUARD_RANGE * GUARD_RANGE;
         for (Mob m : level.getEntitiesOfClass(Mob.class, guard.getBoundingBox().inflate(GUARD_RANGE),
            m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && !BossManager.isFriendlySkeleton(m))) {
            double d = m.distanceToSqr(guard);
            if (d < best) {
               best = d;
               foe = m;
            }
         }
      }
      if (foe != null) {
         guard.setTarget(foe);
      } else if (owner != null && owner.level() == level && guard.distanceToSqr(owner) > 64.0) {
         guard.getNavigation().moveTo(owner, 1.1);
      }
   }

   private static void tagFriendly(Mob mob, UUID owner) {
      net.minecraft.world.item.component.CustomData data = mob.getOrDefault(
         net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY
      );
      net.minecraft.nbt.CompoundTag tag = data.copyTag();
      tag.putString("ff_friendly_owner", owner.toString());
      mob.setComponent(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
   }

   public static void clear() {
      BELL_UNTIL.clear();
      SEAL_UNTIL.clear();
      GUARDS.clear();
      SUBJECTS.clear();
      SUBJECT_STRIKE.clear();
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      if (server == null || id == null) {
         return null;
      }
      for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
         Entity e = level.getEntity(id);
         if (e != null) {
            return e;
         }
      }
      return null;
   }

   private static void bar(ServerPlayer player, String text) {
      player.sendOverlayMessage(Component.literal(Chat.colorize(text)));
   }

   /** Test hook: the Seal's emerald price, so the lore cannot drift from it. */
   public static int sealEmeraldCost() {
      return SEAL_EMERALD_COST;
   }
}
