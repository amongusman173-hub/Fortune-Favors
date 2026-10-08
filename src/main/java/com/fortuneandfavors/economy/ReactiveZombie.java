package com.fortuneandfavors.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;

/**
 * The world's answer to a death by zombie: the thing that did it calls up another one, and the new
 * body is wearing what the player was.
 *
 * <h2>The rule, exactly</h2>
 * If a player is beaten down by a zombie's <b>melee</b> attack, another zombie rises where they fell
 * - holding what they were holding, and wearing <b>one</b> piece of their armour, picked at random
 * from what they actually had on. It says nothing. There is no chat line, no death message of its
 * own, no announcement: the player looks up from the respawn screen and the world is already
 * different, which is the whole point of a reactive feature rather than an event.
 *
 * <h2>What it is not</h2>
 * Nothing about this is a second death-drop. The duplicates hold <b>copies</b> of the kit, taken at
 * the one moment the inventory still exists (see {@link #capture}), and every one of them is
 * marked so that it hands back nothing at all if it is killed - no equipment, no loot table, no
 * experience. Killing your own reflection is not a way to get a second copy of anything, and it is
 * not a reason to farm the world for gear either.
 *
 * <h2>One snapshot, taken at the only moment it is whole</h2>
 * Vanilla empties a player's slots one at a time as it drops them, so a copy read from the death
 * handler is always the looted version of the kit - which is why the capture is called from the
 * death-drop hook, alongside the blood servant's, on the first stack of the drop. The two are
 * separate records on purpose: a servant is a boss's ceremony and lasts a minute, while this is the
 * world's own reflex and lasts seconds, and only one of them may be spent per death.
 */
public final class ReactiveZombie {

   /**
    * The mark every duplicate carries.
    *
    * <p>An entity tag rather than a map of UUIDs, because it has to survive a save and a load: a
    * duplicate that was left standing when the server stopped is still a duplicate when it starts
    * again, and a set held in memory would have quietly turned it into an ordinary zombie that drops
    * the player's gear.
    */
   public static final String DUPLICATE_TAG = "ff_reactive_zombie";

   /** How long a captured kit waits for a death. Long enough to survive a slow death, and not more. */
   private static final long KIT_MILLIS = 30_000L;

   private static final Random RANDOM = new Random();

   /** The four slots that count as "your armour". */
   private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
   };

   private static final Map<UUID, Kit> KITS = new HashMap<>();

   private ReactiveZombie() {
   }

   /** What a player was holding, one piece of what they were wearing, and when to forget it. */
   private record Kit(ItemStack hand, ItemStack armor, long expiresAt) {
   }

   /**
    * Copies the player's kit, the moment their death begins.
    *
    * <p>Called from the death-drop hook, which runs while the inventory is still whole, and
    * idempotent because that hook fires once per carried stack: only the first call sees the
    * complete kit, and the rest keep what the first one took. Expired entries are swept here, which
    * keeps the map bounded without a tick handler of its own.
    */
   public static void capture(ServerPlayer player) {
      if (player == null) {
         return;
      }
      try {
         long now = System.currentTimeMillis();
         KITS.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
         if (KITS.containsKey(player.getUUID())) {
            return;
         }
         ItemStack hand = player.getMainHandItem().copy();
         List<ItemStack> worn = new ArrayList<>();
         for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = player.getItemBySlot(slot);
            if (!piece.isEmpty()) {
               worn.add(piece.copy());
            }
         }
         // One piece, and it is whatever the player was actually wearing - a duplicate of somebody
         // in a bare chest comes back bare-chested rather than dressed in nothing at all.
         ItemStack armor = worn.isEmpty() ? ItemStack.EMPTY : worn.get(RANDOM.nextInt(worn.size())).copy();
         KITS.put(player.getUUID(), new Kit(hand, armor, now + KIT_MILLIS));
      } catch (Throwable ignored) {
      }
   }

   /**
    * The reaction itself: a melee kill by a zombie, answered on the spot where the player fell.
    *
    * <p>Melee is asked for by hand - the direct body that dealt the blow has to be the zombie
    * itself, not something it threw - because a duplicate is meant to read as "the one that got you
    * came back with your gear on", and a death by a trident a drowned threw across a lake is not
    * that.
    */
   public static void onPlayerDeath(ServerPlayer dead, DamageSource source) {
      if (dead == null || source == null) {
         return;
      }
      try {
         if (!(source.getDirectEntity() instanceof Zombie killer) || source.getEntity() != killer) {
            return;
         }
         // A duplicate is not allowed to make duplicates: a player dying to one must not spawn
         // another wearing whatever that one was carrying, which is how a death can snowball.
         if (isDuplicate(killer)) {
            return;
         }
         if (!(dead.level() instanceof ServerLevel level)) {
            return;
         }
         Kit kit = KITS.remove(dead.getUUID());
         raise(level, dead, kit);
      } catch (Throwable ignored) {
      }
   }

   /** Builds and places the duplicate. A body always rises, even for a player who died with nothing. */
   private static void raise(ServerLevel level, ServerPlayer dead, Kit kit) {
      Zombie duplicate = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT);
      if (duplicate == null) {
         return;
      }
      duplicate.snapTo(dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), 0.0F);
      duplicate.addTag(DUPLICATE_TAG);
      // It stays standing where it was left. A duplicate that despawned after a minute would take
      // the last trace of the kill with it, and the player who came back for it would find nothing
      // to be unsettled by.
      duplicate.setPersistenceRequired();
      duplicate.setCanPickUpLoot(false);
      duplicate.skipDropExperience();
      // Every slot, before anything is put in any of them: a duplicate hands back nothing at all.
      for (EquipmentSlot slot : EquipmentSlot.values()) {
         duplicate.setDropChance(slot, 0.0F);
      }
      if (kit != null) {
         if (!kit.hand.isEmpty()) {
            duplicate.setItemSlot(EquipmentSlot.MAINHAND, kit.hand);
         }
         if (!kit.armor.isEmpty()) {
            duplicate.setItemSlot(armorSlotOf(duplicate, kit.armor), kit.armor);
         }
      }
      level.addFreshEntity(duplicate);
      // A sound and nothing else: it is allowed to be heard and never to be announced.
      level.playSound(null, dead.getX(), dead.getY(), dead.getZ(), SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 0.55F, 0.7F);
   }

   /**
    * Which slot a piece of armour belongs in, by asking the item where it can be worn.
    *
    * <p>Read off the item rather than remembered at capture time, so the copy lands in the slot the
    * original was in even when the piece was in an unusual one.
    */
   private static EquipmentSlot armorSlotOf(Zombie duplicate, ItemStack piece) {
      EquipmentSlot byItem = duplicate.getEquipmentSlotForItem(piece);
      return byItem.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? byItem : EquipmentSlot.CHEST;
   }

   /** Whether this body is one of the duplicates. The one question every drop rule asks. */
   public static boolean isDuplicate(net.minecraft.world.entity.Entity entity) {
      return entity != null && entity.entityTags().contains(DUPLICATE_TAG);
   }

   /** Forgets one player's captured kit. Used by the test seam and on a clean teardown. */
   public static void forget(UUID id) {
      KITS.remove(id);
   }

   /** Drops every captured kit. Called when the server stops. */
   public static void clear() {
      KITS.clear();
   }

   /** How many kits are waiting. A list that only ever grows is a server that gets slower. */
   public static int pending() {
      long now = System.currentTimeMillis();
      KITS.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
      return KITS.size();
   }

   /** Test seam: the kit a player would rise with, as (hand, armour), or null when there is none. */
   public static ItemStack[] peek(UUID id) {
      Kit kit = KITS.get(id);
      if (kit == null) {
         return null;
      }
      return new ItemStack[]{kit.hand, kit.armor};
   }
}
