package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.UseEffects;

/**
 * Sword blocking = hold right-click with a sword → the vanilla blocks_attacks
 * "using item" state starts (block pose + isBlocking() true) and the
 * LegacyBlockMixin halves incoming melee damage.
 *
 * The blocks_attacks component is re-applied on tick so it survives syncs,
 * freshly-picked-up swords, and any component resets. It carries NO vanilla
 * damage reductions — the mixin owns the 50% reduction — so blocking never
 * double-reduces, never plays shield sounds, and costs no durability.
 *
 * The Distant Memory sword also gets use_effects (full movement speed, can
 * sprint) so blocking never slows it down.
 */
public final class SwordBlockManager {
   private SwordBlockManager() {
   }

   private static final BlocksAttacks SWORD_BLOCK = new BlocksAttacks(
      0.0F,                                     // blockDelaySeconds — instant block
      1.0F,                                     // disableCooldownScale — never disabled by axe hits
      List.of(),                                // no vanilla damage reduction (LegacyBlockMixin owns it)
      BlocksAttacks.ItemDamageFunction.DEFAULT, // no durability cost per blocked hit
      Optional.empty(),                         // bypassedBy
      Optional.empty(),                         // blockSound — silent block
      Optional.empty()                          // disableSound
   );

   private static final UseEffects FULL_SPEED_USE = new UseEffects(true, false, 1.0F);

   /** The blocks_attacks component used for sword blocking (client + server). */
   public static BlocksAttacks blockComponent() {
      return SWORD_BLOCK;
   }

   public static void tick(MinecraftServer server) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         boolean canBlock = com.fortuneandfavors.duel.DuelManager.isLegacyFight(p)
            || ModItems.isDistantMemorySword(p.getMainHandItem())
            || com.fortuneandfavors.economy.AdvancedEnchantments.has(p.getMainHandItem(), com.fortuneandfavors.economy.AdvancedEnchantments.GUARD);
         applyToHand(p.getMainHandItem(), canBlock);
         applyToHand(p.getOffhandItem(), canBlock);
      }
   }

   private static void applyToHand(ItemStack stack, boolean canBlock) {
      if (stack == null || stack.isEmpty() || !stack.is(ItemTags.SWORDS)) {
         return;
      }
      // Only grant blocks_attacks to swords that should block: Distant Memory
      // sword (always) or any sword during a legacy 1.8 duel. Outside of duels,
      // normal swords cannot block.
      if (canBlock && !stack.has(DataComponents.BLOCKS_ATTACKS)) {
         stack.set(DataComponents.BLOCKS_ATTACKS, SWORD_BLOCK);
      } else if (!canBlock && !com.fortuneandfavors.economy.AdvancedEnchantments.has(stack, com.fortuneandfavors.economy.AdvancedEnchantments.GUARD) && stack.has(DataComponents.BLOCKS_ATTACKS)) {
         stack.remove(DataComponents.BLOCKS_ATTACKS);
      }
      if (ModItems.isDistantMemorySword(stack) && !stack.has(DataComponents.USE_EFFECTS)) {
         stack.set(DataComponents.USE_EFFECTS, FULL_SPEED_USE);
      }
   }
}
