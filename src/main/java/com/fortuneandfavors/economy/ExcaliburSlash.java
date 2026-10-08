package com.fortuneandfavors.economy;

import com.fortuneandfavors.net.FfVfx;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Excalibur cuts first and lands later: the swing draws the slash, the damage follows {@link #LAND} ticks on. */
public final class ExcaliburSlash {
   private static final int LAND = 8;
   private static final int GOLD = 0xFFD86A;

   private record Cut(ServerLevel level, int target, ItemStack sword, long due) {
   }

   private static final Map<UUID, Cut> PENDING = new HashMap<>();

   private ExcaliburSlash() {
   }

   private static boolean isExcalibur(ItemStack held) {
      Component name = held.get(DataComponents.CUSTOM_NAME);
      return held.is(Items.NETHERITE_SWORD) && name != null && name.getString().contains("Excalibur");
   }

   /** AttackEntityCallback: an Excalibur swing is taken over here - the vanilla hit never happens. */
   public static InteractionResult onAttack(Player player, Entity target) {
      if (!(player instanceof ServerPlayer p) || !(target instanceof LivingEntity) || !isExcalibur(p.getMainHandItem())) {
         return InteractionResult.PASS;
      }
      ServerLevel level = (ServerLevel)p.level();
      if (!PENDING.containsKey(p.getUUID())) {   // one cut in the air at a time
         PENDING.put(p.getUUID(), new Cut(level, target.getId(), p.getMainHandItem(), level.getGameTime() + LAND));
         Vec3 at = target.position().add(0.0, target.getBbHeight() * 0.55, 0.0);
         FfVfx.shape(level, FfVfx.SLASH, ParticleTypes.SWEEP_ATTACK, at, p.getLookAngle(), LAND, 0.0, GOLD);
         FfVfx.enter();
         try {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 3, 0.6, 0.3, 0.6, 0.0);
         } finally {
            FfVfx.exit();
         }
         level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.5F, 0.5F);
      }
      return InteractionResult.FAIL;
   }

   public static void tick(MinecraftServer server) {
      for (Iterator<Map.Entry<UUID, Cut>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<UUID, Cut> e = it.next();
         Cut cut = e.getValue();
         if (cut.level.getGameTime() < cut.due) {
            continue;
         }
         it.remove();
         ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
         Entity target = cut.level.getEntity(cut.target);
         if (p == null || !(target instanceof LivingEntity victim) || !victim.isAlive()) {
            continue;
         }
         // ExcaliburMixin turns this into the one-hit kill for anything that is not a boss.
         float damage = (float)p.getAttributeValue(Attributes.ATTACK_DAMAGE) + 128.0F;
         victim.hurtServer(cut.level, cut.level.damageSources().playerAttack(p), damage);
         FfVfx.enter();
         try {
            com.fortuneandfavors.net.FfVfx.particles(cut.level, ParticleTypes.CRIT, victim.getX(), victim.getY() + victim.getBbHeight() * 0.55, victim.getZ(), 30, 0.5, 0.6, 0.5, 0.4);
            com.fortuneandfavors.net.FfVfx.particles(cut.level, ParticleTypes.EXPLOSION, victim.getX(), victim.getY() + victim.getBbHeight() * 0.55, victim.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
         } finally {
            FfVfx.exit();
         }
         cut.level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.4F, 0.6F);
         cut.level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.9F, 1.5F);
         // One use: the blade breaks once its cut lands, wherever it was moved to in the meantime.
         if (p.getMainHandItem() == cut.sword) {
            cut.sword.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
         } else {
            cut.sword.shrink(1);
         }
      }
   }
}
