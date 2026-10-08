package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/**
 * What a player's equipment entitles them to, against what their attributes are showing.
 *
 * <p>Some of what this mod hands out is not an item attribute at all - it is a modifier
 * written onto the <i>player</i> by a tick hook: the Crab Claw's interaction range, the
 * Colossus Plate's knockback resistance. That is a mirror, and a mirror can be a tick
 * behind the arm that uses it. A player picks the claw up and swings inside the same
 * tick, and the packet that says so is handled on the connection's thread <i>before</i>
 * the player's own tick has run the mirror: the server is still holding the vanilla
 * three blocks while the client is already reaching with the weapon it can see.
 *
 * <p>Nothing about that is a cheat, and a check that refuses the hit is refusing the
 * mod's own grant. So the authority for "how far can this body reach" is the equipment
 * - which the server can see for itself, without trusting a word from the client - and
 * this class answers with the part of that entitlement the live attribute has not
 * caught up with yet.
 *
 * <p>The modifier ids are named here rather than at the call sites for the usual
 * reason: a check that reads the entitlement has to be reading the <i>same</i>
 * modifier the mirror writes, and two string literals in two files is how those drift.
 */
public final class EquipmentAttributes {

   /** The modifier {@link AdvancedEnchantments} writes for the Crab Claw's entity reach. */
   public static final String ENTITY_RANGE_PATH = "crab_claw_entity";
   /** The modifier {@link AdvancedEnchantments} writes for the Crab Claw's block reach. */
   public static final String BLOCK_RANGE_PATH = "crab_claw_block";

   private EquipmentAttributes() {
   }

   /**
    * The interaction-range bonus this body's equipment asks for, in blocks.
    *
    * <p>Read the way the mirror reads it, from the same six slots, so the entitlement
    * and the grant can never disagree about which item is being worn.
    */
   public static double entitledInteractionRange(Player player) {
      if (player == null) {
         return 0.0;
      }
      int levels = 0;
      try {
         for (ItemStack stack : worn(player)) {
            levels = Math.max(levels, CustomEnchantments.levelOf(stack, CustomEnchantments.CRAB_CLAW));
         }
      } catch (Throwable t) {
         return 0.0;
      }
      return levels;
   }

   /**
    * How many blocks of interaction range this body is entitled to that its own
    * attribute is not showing yet.
    *
    * <p>Zero for everybody not wearing a Crab Claw, and zero for a body whose attribute
    * already carries the grant - which is the ordinary case, and the reason this can be
    * added to a range unconditionally.
    */
   public static double pendingInteractionRange(Player player, boolean entity) {
      if (player == null) {
         return 0.0;
      }
      double entitled = entitledInteractionRange(player);
      if (entitled <= 0.0) {
         return 0.0;
      }
      double applied = applied(
         player,
         entity ? Attributes.ENTITY_INTERACTION_RANGE : Attributes.BLOCK_INTERACTION_RANGE,
         entity ? ENTITY_RANGE_PATH : BLOCK_RANGE_PATH
      );
      return pendingRange(entitled, applied);
   }

   /** The entitlement the live attribute is missing, as its own function so it can be pinned. */
   public static double pendingRange(double entitled, double applied) {
      return Math.max(0.0, entitled - applied);
   }

   /** Whatever of ours is on an attribute right now, in the attribute's own units. */
   public static double applied(Player player, Holder<Attribute> attribute, String path) {
      try {
         AttributeInstance instance = player.getAttribute(attribute);
         if (instance == null) {
            return 0.0;
         }
         AttributeModifier modifier = instance.getModifier(FortuneFavorsMod.id(path));
         return modifier == null ? 0.0 : modifier.amount();
      } catch (Throwable t) {
         return 0.0;
      }
   }

   private static List<ItemStack> worn(Player player) {
      return List.of(
         player.getMainHandItem(), player.getOffhandItem(),
         player.getItemBySlot(EquipmentSlot.HEAD), player.getItemBySlot(EquipmentSlot.CHEST),
         player.getItemBySlot(EquipmentSlot.LEGS), player.getItemBySlot(EquipmentSlot.FEET)
      );
   }
}
