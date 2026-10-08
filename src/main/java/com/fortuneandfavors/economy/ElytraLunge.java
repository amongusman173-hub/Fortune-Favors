package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** The Lunge enchant's signature move: you LUNGE with the SPEAR, not with
 *  your jump. Only right-clicking while holding a vanilla spear carrying the
 *  vanilla minecraft:lunge enchantment and wearing an elytra fires the dash.
 *  Spear sound + spear VFX included. */
public final class ElytraLunge {
   private static final Map<UUID, Long> cooldownUntil = new HashMap<>();
   private static final long COOLDOWN_TICKS = 50L; // 2.5s

   private ElytraLunge() {
   }

   /** True when the held item is a vanilla spear carrying the vanilla
    *  minecraft:lunge enchantment - any [material]_spear with lunge works. */
   public static boolean holdsSpear(ServerPlayer p) {
      return com.fortuneandfavors.ModItems.isLungeSpear(p.getMainHandItem());
   }

   /** Spear-only lunge. Called from the use-item hook (right-click with the
    *  spear) and nothing else. Returns true when a lunge fired. */
   public static boolean tryLunge(ServerPlayer p) {
      try {
         if (!holdsSpear(p) || !p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return false;
         }
         long now = p.level().getGameTime();
         long until = cooldownUntil.getOrDefault(p.getUUID(), 0L);
         if (now < until) {
            int left = (int)Math.ceil((until - now) / 20.0);
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7Lunge is recharging - §f" + left + "s§7."), true);
            return true; // consumed the click; no chat spam beyond the bar
         }
         cooldownUntil.put(p.getUUID(), now + COOLDOWN_TICKS);
         Vec3 look = p.getLookAngle();
         Vec3 motion = p.getDeltaMovement();
         double speed = Math.max(2.2, motion.length() + 1.4);
         Vec3 boost = look.scale(speed * 0.75).add(0.0, 0.18, 0.0);
         Vec3 blended = new Vec3(
            boost.x * 0.65 + motion.x * 0.35, boost.y * 0.65 + motion.y * 0.35, boost.z * 0.65 + motion.z * 0.35
         );
         p.setDeltaMovement(blended);
         p.hurtMarked = true;
         Advancements.grant(p, "spear_lunge");
         if (look.y > 0.75) {
            Advancements.grant(p, "spear_rampage");
         }

         // Spear sounds: a riptide-throw whoosh layered with a trident hit.
         p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_RIPTIDE_3, SoundSource.PLAYERS, 1.1F, 1.3F);
         p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 0.9F);

         // Spear VFX: a shockwave ring at the feet, a wind trail behind the
         // dash, spark points along the look vector, and a sonic-boom tip.
         var level = p.level();
         double px = p.getX();
         double py = p.getY();
         double pz = p.getZ();
         for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2.0 * i / 24.0;
            double r = 0.9;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ELECTRIC_SPARK, px + Math.cos(a) * r, py + 0.3, pz + Math.sin(a) * r, 1, 0.0, 0.05, 0.0, 0.0);
         }
         for (int i = 1; i <= 6; i++) {
            Vec3 trail = p.position().subtract(look.scale(i * 0.55)).add(0.0, 0.9, 0.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CLOUD, trail.x, trail.y, trail.z, 2, 0.08, 0.08, 0.08, 0.01);
         }
         for (int i = 0; i < 8; i++) {
            Vec3 tip = p.position().add(look.scale(0.8 + i * 0.35)).add(0.0, 1.2, 0.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 1, 0.02, 0.02, 0.02, 0.0);
         }
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SONIC_BOOM, px + look.x, py + 1.1 + look.y, pz + look.z, 1, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.GUST, px, py + 0.8, pz, 6, 0.4, 0.3, 0.4, 0.02);
         return true;
      } catch (Exception ignored) {
         return false;
      }
   }

   public static void tick(ServerPlayer p) {
      if (cooldownUntil.size() > 64 && p.level().getGameTime() % 200L == 0L) {
         long now = p.level().getGameTime();
         cooldownUntil.entrySet().removeIf(e -> now > e.getValue());
      }
   }
}
