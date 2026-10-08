package com.fortuneandfavors;

import com.fortuneandfavors.economy.WitherReworkManager;
import java.io.File;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * TEMP dev-only: scripted smoke test for the Ascended Wither rework.
 * Arms itself at server start when a file named {@code wtest.flag} exists in
 * the working directory, then walks the whole fight - spawn charge-up, phase 2,
 * abilities, parry, death sequence, and a King fight - logging every step and
 * observation to the server log so the entire fight can be verified headlessly.
 */
public final class WitherTestDriver {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-wtest");
   private static boolean armed = false;
   private static long nextStepAt = 0L;
   private static int step = 0;

   private WitherTestDriver() {
   }

   public static void onServerStarted(MinecraftServer server) {
      if (armed) {
         return;
      }

      // Opt-in only, and deliberately NOT driven by a file in the project tree.
      // This repo lives in iCloud Drive, which resurrects deleted files - so the
      // old "touch wtest.flag" trigger kept coming back and re-armed the whole
      // scripted fight (a normal wither AND a King wither, slam dummies, minions,
      // nukes and their VFX) on EVERY world load, with overworld chunk 0,0
      // force-loaded the entire time. That reads as "insane lag" in a real world.
      // Ask for it explicitly instead: -Dff.wtest=true, or FF_WTEST=1.
      boolean requested = Boolean.getBoolean("ff.wtest")
         || "1".equals(System.getenv("FF_WTEST"))
         || "true".equalsIgnoreCase(String.valueOf(System.getenv("FF_WTEST")));

      if (!requested) {
         // Belt and braces: release the chunk a crashed previous run force-loaded,
         // so a stale ticket from the smoke test can never pin a chunk forever.
         try {
            server.overworld().setChunkForced(0, 0, false);
         } catch (Throwable ignored) {
         }
         return;
      }

      armed = true;
      step = 0;
      nextStepAt = server.overworld().getGameTime() + 60L;
      LOGGER.info("WTEST armed via -Dff.wtest / FF_WTEST - scripted smoke test scheduled");
   }

   public static void tick(MinecraftServer server) {
      if (!armed) {
         return;
      }
      long now = server.overworld().getGameTime();
      if (now < nextStepAt) {
         return;
      }
      runStep(server, now);
   }

   private static void runStep(MinecraftServer server, long now) {
      try {
         ServerLevel level = server.overworld();

         switch (step) {
            case 0 -> {
               spawnWither(level, false);
               // Dummy target so phase-2 ascension slams actually slam onto
               // something (no real players online). High HP + resistance + noAI
               // keeps it alive as a punching bag.
               net.minecraft.world.entity.monster.Ravager z = (net.minecraft.world.entity.monster.Ravager)EntityTypes.RAVAGER
                  .create(level, EntitySpawnReason.COMMAND);
               if (z != null) {
                  z.setPos(8.5, 90.0, 8.5);
                  z.setNoAi(true);
                  z.setPersistenceRequired();
                  z.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, 100000, 5, false, false));
                  z.setCustomName(net.minecraft.network.chat.Component.literal("Slam Dummy"));
                  level.addFreshEntity(z);
                  WitherBoss w = findWither(level);
                  if (w != null) {
                     w.setTarget(z);
                  }
               }
               LOGGER.info("WTEST step0: spawned wither at (8,96,8) + slam dummy -> animated claim + 13s charge-up");
               nextStepAt = now + 340L;
            }
            case 1 -> {
               logState(level, "step1: charge-up should be complete, boss bar retitled");
               nextStepAt = now + 60L;
            }
            case 2 -> {
               // Single big hit (armor-reduced) -> ~40% HP, phase 2 unlocked.
               hurtWither(level, 400.0F);
               logState(level, "step2: hit for 400 - phase 2 unlocked");
               nextStepAt = now + 240L;
            }
            case 3, 4, 5, 6, 7 -> {
               logState(level, "step" + step + ": phase-2 observation - slam/domain/knights/anti-burrow should be logging");
               nextStepAt = now + 240L;
            }
            case 8 -> {
               // Second hit -> ~20% HP, crosses 30% without dying -> parry fires once.
               hurtWither(level, 80.0F);
               logState(level, "step8: hit for 80 - PARRY should have fired");
               nextStepAt = now + 240L;
            }
            case 9, 10 -> {
               logState(level, "step" + step + ": parry window + abilities (domain may still fire)");
               nextStepAt = now + 240L;
            }
            case 11 -> {
               killWither(level);
               logState(level, "step11: lethal damage sent - custom death sequence should be running");
               nextStepAt = now + 220L;
            }
            case 12 -> {
               logState(level, "step12: death sequence finished (wither removed, star dropped)");
               nextStepAt = now + 40L;
            }
            case 13 -> {
               spawnWither(level, true);
               LOGGER.info("WTEST step13: spawned KING wither (maxHP>300 pre-claim)");
               nextStepAt = now + 340L;
            }
            case 14 -> logState(level, "step14: king claimed + charge-up complete");
            case 15 -> {
               killWither(level);
               LOGGER.info("WTEST step15: king lethal damage - king death + extra drops (2 skulls + essence)");
               nextStepAt = now + 220L;
            }
            case 16 -> logState(level, "step16: king death finished - verify drops in manager logs");
            default -> {
               LOGGER.info("WTEST complete - disarming");
               armed = false;
               consumeFlags(level);
            }
         }
         step++;
      } catch (Throwable t) {
         LOGGER.error("WTEST error in step {}", step, t);
         armed = false;
         // Don't leave the smoke-test chunk forced when the script bails out.
         try {
            server.overworld().setChunkForced(0, 0, false);
         } catch (Throwable ignored) {
         }
      }
   }

   /** Releases the smoke test's force-loaded chunk and clears any leftover flag
    *  files from the old file-based trigger, so neither survives into a normal
    *  session. With the driver now armed only by -Dff.wtest, the flags are inert -
    *  this just tidies them away. */
   private static void consumeFlags(ServerLevel level) {
      try {
         level.setChunkForced(0, 0, false);
         boolean removed = new File("wtest.flag").delete() | new File("run/wtest.flag").delete();
         LOGGER.info(
            removed
               ? "WTEST complete - stale flag files removed (-Dff.wtest=true to run it again)"
               : "WTEST complete - chunk 0,0 released"
         );
      } catch (Throwable ignored) {
      }
   }

   private static void spawnWither(ServerLevel level, boolean king) {
      WitherBoss w = (WitherBoss)EntityTypes.WITHER.create(level, EntitySpawnReason.COMMAND);
      if (w == null) {
         LOGGER.error("WTEST: could not create wither");
         return;
      }
      // Force-load the chunk: with no players online the server otherwise
      // unloads it (and the wither with it) within seconds.
      level.setChunkForced(0, 0, true);
      w.setPos(8.5, 96.0, 8.5);
      if (king) {
         // Dummy target for the king too.
         net.minecraft.world.entity.monster.Ravager z = (net.minecraft.world.entity.monster.Ravager)EntityTypes.RAVAGER
            .create(level, EntitySpawnReason.COMMAND);
         if (z != null) {
            z.setPos(8.5, 90.0, 8.5);
            z.setNoAi(true);
            z.setPersistenceRequired();
            z.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, 100000, 5, false, false));
            z.setCustomName(net.minecraft.network.chat.Component.literal("Slam Dummy"));
            level.addFreshEntity(z);
            w.setTarget(z);
         }
         AttributeInstance hp = w.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(500.0);
         }
         w.setHealth(500.0F);
      }
      w.setInvulnerableTicks(220);
      level.addFreshEntity(w);
      WitherReworkManager.onWitherSpawn(w);
   }

   private static void hurtWither(ServerLevel level, float amount) {
      WitherBoss w = findWither(level);
      if (w == null) {
         LOGGER.error("WTEST: no wither found to damage");
         return;
      }
      w.hurt(level.damageSources().genericKill(), amount);
      LOGGER.info("WTEST: hurt {} -> hp {} / {}", (int)amount, (int)w.getHealth(), (int)w.getMaxHealth());
   }

   private static void killWither(ServerLevel level) {
      WitherBoss w = findWither(level);
      if (w == null) {
         LOGGER.error("WTEST: no wither found to kill");
         return;
      }
      w.hurt(level.damageSources().genericKill(), 99999.0F);
   }

   private static WitherBoss findWither(ServerLevel level) {
      for (Entity e : level.getAllEntities()) {
         if (e instanceof WitherBoss w) {
            return w;
         }
      }
      return null;
   }

   private static void logState(ServerLevel level, String label) {
      WitherBoss w = findWither(level);
      if (w == null) {
         LOGGER.info("WTEST {}: no wither loaded", label);
         return;
      }
      LOGGER.info("WTEST {}: hp {} / {} invuln={} live={} name={}",
         label, (int)w.getHealth(), (int)w.getMaxHealth(), w.getInvulnerableTicks(),
         WitherReworkManager.isFightLive(w.getUUID()),
         w.getCustomName() == null ? "none" : w.getCustomName().getString());
   }
}