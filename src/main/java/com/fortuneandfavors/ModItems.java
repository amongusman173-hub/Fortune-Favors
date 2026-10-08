package com.fortuneandfavors;

import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.TokenManager;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.component.ItemAttributeModifiers.Builder;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

public final class ModItems {
   /** Scoreboard tag put on every floating text display the mod spawns, so stale
    *  ones left over from a crash or a missed cleanup can be identified and
    *  discarded anywhere in the world. */
   public static final String DISPLAY_TAG = "ff_display";
   /** Extra tag for displays that are purely ephemeral (proc popups, mask-sense
    *  labels) and must never outlive their short lifetime. */
   public static final String DISPLAY_TMP_TAG = "ff_display_tmp";
   public static final String TYPE_TAG = "ff";
   /** Entity tag marking wither-rework-owned minions (knights) so they can be
    *  swept if a crash orphans them. */
   public static final String WITHER_KNIGHT_TAG = "ff_wither_knight";
   public static final String SPAWNER_LOOT_LEVEL_TAG = "ffl";
   public static final String TYPE_CHUNK_CLAIMER = "chunk_claimer";
   public static final String TYPE_BUY_SIGN = "buy_sign";
   public static final String TYPE_SELL_SIGN = "sell_sign";
   public static final String TYPE_AUTO_SELL = "auto_sell_hopper";
   public static final String TYPE_ELEVATOR = "elevator";
   public static final String TYPE_REDEEMER = "token_redeemer";
   public static final String TYPE_INFUSER = "spawner_infuser";
   public static final String TYPE_ITEM_FORGE = "item_forge";
   // The eight late-game machines and utilities, in the order the shop lists them: a site of your
   // own that never unloads, a cheaper way to mend gear than the anvil, a block that decides what
   // goes where, two kitchens you can carry, a belt, and the two halves of a farm that runs itself.
   public static final String TYPE_CHUNK_ANCHOR = "chunk_anchor";
   public static final String TYPE_REPAIR_STATION = "repair_station";
   public static final String TYPE_ITEM_SORTER = "item_sorter";
   public static final String TYPE_PORTABLE_FURNACE = "portable_furnace";
   public static final String TYPE_PORTABLE_CAMPFIRE = "portable_campfire";
   public static final String TYPE_POTION_BELT = "potion_belt";
   /** The Expedition Compass: one fatal-blow wait, erased. See ExpeditionManager. */
   public static final String TYPE_EXPEDITION_COMPASS = "expedition_compass";
   public static final String TYPE_AUTO_PLANTER = "auto_planter";
   public static final String TYPE_AUTO_HARVESTER = "auto_harvester";
   public static final String TYPE_IRRIGATION_SPRINKLER = "irrigation_sprinkler";
   public static final String TYPE_WORMHOLE = "wormhole_potion";
   public static final String TYPE_DEATH_COMPASS = "death_compass";
   public static final String TYPE_BUNDLE = "bundle";
   public static final String TYPE_ENDER_POUCH = "ender_pouch";
   public static final String TYPE_MYSTERY = "mystery_box";
   public static final String TYPE_RAID_TOKEN = "raid_boss_token";
   public static final String TYPE_RAID_BANNER = "raid_banner";
   public static final String TYPE_KING_LOOT = "king_loot_box";
   public static final String TYPE_WITHER_LOOT = "wither_loot_box";
   public static final String TYPE_RAID_LOOT = "raid_loot_box";
   public static final String TYPE_SLIME_LOOT = "slime_loot_box";
   public static final String TYPE_GOLEM_LOOT = "golem_loot_box";
   public static final String TYPE_WITHER_STAFF = "wither_staff";
   public static final String TYPE_WITHER_BLADE = "wither_blade";
   public static final String TYPE_WITHER_CROWN = "wither_crown";
   public static final String TYPE_WITHER_CLOAK_SWORD = "wither_cloak_sword";
   public static final String TYPE_SLIME_TOKEN = "slime_boss_token";
   public static final String TYPE_SLIME_LAUNCHER = "slime_launcher";
   public static final String TYPE_SLIME_SHIELD = "slime_shield";
   public static final String TYPE_SLIME_BOOTS = "slime_boots";
   public static final String TYPE_KING_BONE = "king_bone";
   public static final String TYPE_SLIME_CORE = "slime_core";
   public static final String TYPE_SLIME_TROPHY = "slime_trophy";
   public static final String TYPE_SPAWNER_LOOT = "spawner_loot";
   public static final String TYPE_STONE_GOLEM_TOKEN = "stone_golem_token";
   public static final String TYPE_GOLEM_CORE = "golem_core";
   public static final String TYPE_GOLEM_TROPHY = "golem_trophy";
   public static final String TYPE_STONE_STAFF = "stone_staff";
   public static final String TYPE_GOLEM_FIST = "golem_fist";
   public static final String TYPE_STONEHEART = "stoneheart";
   public static final String TYPE_MINDBINDER_EYE = "mindbinder_eye";
   public static final String TYPE_SHATTERED_MIND = "shattered_mind";
   public static final String TYPE_MIND_STAFF = "mind_staff";
   public static final String TYPE_POSSESSED_MASK = "possessed_mask";
   public static final String TYPE_MIND_SHROUD = "mind_shroud";
   public static final String TYPE_MIND_LOOT = "mind_loot_box";
   public static final String TYPE_SNOW_QUEEN_TOKEN = "snow_queen_token";
   public static final String TYPE_SNOW_LOOT = "snow_loot_box";
   public static final String TYPE_ICE_STAFF = "ice_staff";
   public static final String TYPE_FROZEN_HEART = "frozen_heart";
   public static final String TYPE_FROSTBOUND_CROWN = "frostbound_crown";
   public static final String TYPE_GLACIER_CLOAK = "glacier_cloak";
   public static final String TYPE_BACKPACK = "backpack";
   public static final String TYPE_SCULK_MEDALLION = "sculk_medallion";
   public static final String TYPE_SCULK_LOOT = "sculk_loot_box";
   public static final String TYPE_SCULK_STAFF = "sculk_mage_staff";
   public static final String TYPE_SCULK_LEGGINGS = "sculk_sensor_leggings";
   public static final String TYPE_WARDENS_CALL = "wardens_call";
   public static final String TYPE_SCULK_ORB = "sculk_orb";
   public static final String TYPE_RUNE_HASTE = "rune_haste";
   public static final String TYPE_RUNE_FLAME = "rune_flame";
   public static final String TYPE_RUNE_FORTUNE = "rune_fortune";
   public static final String TYPE_RUNE_SWIFTNESS = "rune_swiftness";
   public static final String TYPE_RUNE_FROST = "rune_frost";
   public static final String TYPE_RUNE_REACH = "rune_reach";
   public static final String TYPE_RUNE_LIFESTEAL = "rune_lifesteal";
   public static final String TYPE_RUNE_WARDING = "rune_warding";
   public static final String TYPE_RUNE_FORTITUDE = "rune_fortitude";
   public static final String TYPE_SCULK_ESSENCE = "sculk_essence";
   public static final String TYPE_DISTANT_MEMORY_SHARD = "distant_memory_shard";
   public static final String TYPE_DISTANT_MEMORY_SWORD = "distant_memory_sword";
   public static final String TYPE_LAST_REMEMBRANCE = "last_remembrance";
   /**
    * The NBT key that marks a stack as one of the monarch's own keepings.
    *
    * <p>A flag rather than a type, because one of the two keepings is a custom-enchantment
    * tome and already carries the tome's own keys ({@code ff}, {@code ff_tome}, {@code level}):
    * {@link #setType} replaces the whole component, which would wipe the tome and leave a book
    * that renders right and does nothing. So the marker is merged in beside whatever is there.
    */
   public static final String MONARCH_KEEPING_KEY = "ff_monarch_keeping";
   public static final int ICE_STAFF_MAX_DURABILITY = 100;
   public static final int DISTANT_MEMORY_SWORD_DURABILITY = 3122;
   public static final String WCS_BLOCKS_KEY = "ff_wcs_blocks";
   private static final String BACKPACK_SKIN_VALUE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM1MWU1MDU5ODk4MzhlMjcyODdlN2FmYmM3Zjk3ZTc5NmNhYjVmMzU5OGE3NjE2MGMxMzFjOTQwZDBjNSJ9fX0=";
   private static final UUID BACKPACK_SKIN_ID = UUID.fromString("87400000-0000-0000-0000-000000000087");
   public static final int MAX_BACKPACK_TIER = 5;
   public static final String SHIELD_CHARGES_KEY = "ff_slime_shield_charges";
   public static final String SHIELD_RECHARGE_KEY = "ff_slime_shield_recharge";
   public static final String SLIME_BOOTS_JUMP_KEY = "ff_jump_boost";
   public static final String SLIME_BOOTS_BOUNCE_KEY = "ff_bounce";
   public static final String TIER_KEY = "ff_tier";
   public static final int MAX_TIER = 3;
   public static final String MIND_ASCENDED_KEY = "ff_mind_ascended";
   public static final String SPAWNER_OWNER_KEY = "ff_spawner_owner";
   public static final String SPAWNER_OWNER_NAME_KEY = "ff_spawner_owner_name";
   public static final String SPAWNER_TYPE_KEY = "ff_spawner_type";
   public static final String SPAWNER_MODE_KEY = "ff_spawner_mode";
   public static final String SPAWNER_LEVEL_KEY = "ff_spawner_level";
   public static final String OWNER_KEY = "ff_owner";
   public static final String OWNER_NAME_KEY = "ff_owner_name";
   public static final String CASH_KEY = "ff_cash";
   public static final String PAYOUTS_KEY = "ff_payouts";

   private ModItems() {
   }

   public static ItemStack chunkClaimer() {
      ItemStack stack = new ItemStack(Items.GOLDEN_SHOVEL);
      setType(stack, "chunk_claimer");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lChunk Claimer"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click once to claim that whole chunk."),
               Component.literal("§7Sneak-right-click a chest in your claim to"),
               Component.literal("§7open its permission menu.")
            )
         )
      );
      return stack;
   }

   public static ItemStack buySign() {
      return sign("§2§lBuy Sign", "buy_sign", true, new String[]{
         "§7Right-click or §fsneak-right-click§7 a chest to",
         "§7place this sign on it - it becomes a",
         "§a§lBUY SHOP§7 where players pay to take your stock.",
         "§7Stock the chest, then set prices:",
         "§f/chestshop price all 500 §8or §f/chestshop price diamond 100",
         "§7Payments: §f/chestshop currency cash §8· §ftoken§8 · §f<item>",
         "§7", 
         "§8Sneak-right-click the placed shop chest for the",
         "§8full config menu: switch to sell (§f/chestshop toggle§8),",
         "§8close it, or delete the shop. Shop chests cannot",
         "§8be broken until the shop is deleted. Buy shops",
         "§8restock from your balance when a sale empties them."
      });
   }

   public static ItemStack sellSign() {
      return sign("§6§lSell Sign", "sell_sign", true, new String[]{
         "§7Right-click or §fsneak-right-click§7 a chest to",
         "§7place this sign on it - it becomes a",
         "§6§lSELL SHOP§7 where players sell their items to you.",
         "§7You pay them your cash (or currency) and keep",
         "§7the items in the chest. Set what you pay:",
         "§f/chestshop price all 500 §8or §f/chestshop price diamond 100",
         "§7", 
         "§8Sneak-right-click the placed shop chest for the",
         "§8config menu: switch to buy (§f/chestshop toggle§8),",
         "§8close it, or delete the shop. Shop chests cannot",
         "§8be broken until the shop is deleted. You keep 100% of",
         "§8everything sold to you."
      });
   }

   private static ItemStack sign(String name, String type, boolean glint, String... loreLines) {
      ItemStack stack = new ItemStack(Items.OAK_SIGN);
      setType(stack, type);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<Component> lore = new ArrayList<>();
      for (String line : loreLines) {
         lore.add(Component.literal(line));
      }

      stack.set(DataComponents.LORE, new ItemLore(lore));
      if (glint) {
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }

      return stack;
   }

   public static ItemStack autoSellHopper() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "auto_sell_hopper");
      setModel(stack, AUTO_SELL_HOPPER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lAuto-Sell Hopper"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Sucks up sellable items and sells them"),
               Component.literal("§7to you automatically. Drains containers above,"),
               Component.literal("§7and never touches shop chests or claimed land"),
               Component.literal("§7you can't access.")
            )
         )
      );
      return stack;
   }

   /**
    * Upwards Hopper: an exclusive shop-only machine block. Looks like a hopper,
    * works like one - but it pushes its contents UP into the container above,
    * while still passing items sideways like a vanilla hopper. No recipe exists;
    * it can only be bought in the Exclusive shop or /ff give.
    */
   public static ItemStack upwardsHopper() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "upwards_hopper");
      setModel(stack, UPWARDS_HOPPER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lUpwards Hopper"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A shop-exclusive hopper that pushes its items"),
               Component.literal("§7§dUP§7 into the container above - while still"),
               Component.literal("§7handing items sideways like a normal hopper."),
               Component.literal("§8No recipe - Exclusive shop only, $7,500")
            )
         )
      );
      return stack;
   }

   public static ItemStack elevator() {
      ItemStack stack = new ItemStack(Items.IRON_BLOCK);
      setType(stack, "elevator");
      setModel(stack, ELEVATOR_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lElevator"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Place it, jump to go up, sneak to go down."),
               Component.literal("§7It searches the whole column and teleports"),
               Component.literal("§7you straight through whatever is in between.")
            )
         )
      );
      return stack;
   }

   public static ItemStack chair() {
      return chair(Items.OAK_STAIRS);
   }

   /** A chair in a specific wood (variant switching keeps the placed stair). */
   public static ItemStack chair(net.minecraft.world.item.Item stairItem) {
      ItemStack stack = new ItemStack(stairItem);
      setType(stack, "chair");
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lChair"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A seat for your base. Place it like any stair."),
               Component.literal("§7Right-click to sit down, sneak to get up."),
               Component.literal("§7Sneak-right-click to open the chair menu:"),
               Component.literal("§7pick it up, check the owner, or restyle it"),
               Component.literal("§7into another wood."),
               Component.literal("§8Can't be broken - the pickup returns it safely")
            )
         )
      );
      return stack;
   }

   public static ItemStack spawnerInfuser() {
      ItemStack stack = new ItemStack(Items.CRAFTING_TABLE);
      setType(stack, "spawner_infuser");
      setModel(stack, SPAWNER_INFUSER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lSpawner Infuser"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Fuse spawners together to make them"),
               Component.literal("§7stronger (faster + more per spawn)."),
               Component.literal("§7Or convert a spawner's type: put it in the"),
               Component.literal("§7middle and surround it with iron blocks (golem),"),
               Component.literal("§7blaze rods, gunpowder, bones, rotten flesh,"),
               Component.literal("§7mutton, beef or porkchop.")
            )
         )
      );
      return stack;
   }

   public static ItemStack wormholePotion() {
      ItemStack stack = new ItemStack(Items.POTION);
      setType(stack, "wormhole_potion");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      // A potion bottle renders its liquid from potion_contents. Without that
      // component the client has no colour and no potion overlay to draw, so the
      // bottle came out as a flat black blob. It carries no effects on purpose -
      // the warp is the mod's own logic - so it is contents with a colour only.
      stack.set(
         DataComponents.POTION_CONTENTS,
         new net.minecraft.world.item.alchemy.PotionContents(
            java.util.Optional.of(net.minecraft.world.item.alchemy.Potions.WATER), java.util.Optional.of(0x8B2BE2), java.util.List.of(), java.util.Optional.empty()
         )
      );
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWormhole Potion"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Drink it to open a portal window:"),
               Component.literal("§7warp to a player (with their ok), your"),
               Component.literal("§7respawn, last death, a random spot - or"),
               Component.literal("§7one of up to §d3 personal waypoints§7 you set."),
               Component.literal("§8One-time use - consumed on teleport"),
               Component.literal("§8Setting waypoints is free · 30s cooldown")
            )
         )
      );
      return stack;
   }

   /** A compass that remembers where you last died. Hold it to track the
    *  grave (soul-flame thread + action-bar distance), right-click to fire a
    *  sonar ping that marks the spot. Goes quiet once you retrieve the grave. */
   public static ItemStack deathCompass() {
      ItemStack stack = new ItemStack(Items.COMPASS);
      setType(stack, "death_compass");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      // 4550042, not the 4550040 it used to share with the Sculk Medallion. A model
      // code only has to be unique per base item, so nothing ever complained about
      // the reuse - and because a code can only have one item's art drawn under it,
      // the one that lost was this one: it had no art anywhere and rendered as a
      // plain vanilla compass. Keep in step with the compass entry in
      // tools/make_item_definitions.py.
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550042.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lDeath Compass"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A compass bound to your last breath."),
               Component.literal("§7Hold it: a soul-flame thread reaches"),
               Component.literal("§7toward your grave and the distance shows"),
               Component.literal("§7on your action bar. Right-click: §csonar§7"),
               Component.literal("§7ping - a burst of soul fire marks the"),
               Component.literal("§7spot, even through walls."),
               Component.literal("§8It quiets once you retrieve your grave."),
               Component.literal("§8Craft: compass + soul sand, or /ff give deathcompass")
            )
         )
      );
      return stack;
   }

   public static boolean isDeathCompass(ItemStack stack) {
      return "death_compass".equals(typeOf(stack));
   }

   /** Always points at the player carrying the highest bounty on the server
    *  (within 20,000 blocks). Craft: compass + redstone, or /ff give bountycompass. */
   public static ItemStack bountyCompass() {
      ItemStack stack = new ItemStack(Items.COMPASS);
      setType(stack, "bounty_compass");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550041.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lBounty Compass"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A compass that hungers for heads."),
               Component.literal("§7Hold it: a flame-thread reaches toward"),
               Component.literal("§7the player with the §chighest bounty§7 in"),
               Component.literal("§7range (§f20,000 blocks§7) and the distance"),
               Component.literal("§7shows on your action bar. Right-click to"),
               Component.literal("§7announce their exact position."),
               Component.literal("§8Only tracks online players who aren't hidden."),
               Component.literal("§8Craft: compass + redstone, or /ff give bountycompass")
            )
         )
      );
      return stack;
   }

   public static boolean isBountyCompass(ItemStack stack) {
      return "bounty_compass".equals(typeOf(stack));
   }

   /** A sculk-infused berry from the Elder Warden. Eating restores hunger and
    *  grants sculk sight (Night Vision) + brief regeneration. */
   public static ItemStack sculkFood() {
      ItemStack stack = new ItemStack(Items.GLOW_BERRIES);
      setType(stack, "sculk_food");
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lSculk Fruit"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A berry that hums with the deep dark."),
               Component.literal("§7Right-click to eat: restores hunger,"),
               Component.literal("§710s of §3sculk sight§7 (Night Vision)"),
               Component.literal("§7and regeneration for 5 seconds."),
               Component.literal("§8Harvested from the Elder Warden.")
            )
         )
      );
      return stack;
   }

   public static boolean isSculkFood(ItemStack stack) {
      return "sculk_food".equals(typeOf(stack));
   }

   public static ItemStack mysteryBox() {
      ItemStack stack = new ItemStack(Items.BARREL);
      setType(stack, "mystery_box");
      setModel(stack, MYSTERY_BOX_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lMystery Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Dropped by server bosses."),
               Component.literal("§7Right-click to reveal a random prize -"),
               Component.literal("§7cash, gear, or a rare exclusive item.")
            )
         )
      );
      return stack;
   }

   public static ItemStack raidBossToken() {
      ItemStack stack = new ItemStack(Items.WITHER_SKELETON_SKULL);
      setType(stack, "raid_boss_token");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550004.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWithering Memory"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A fading shard of the King's dark"),
               Component.literal("§7recollection. Right-click to summon the"),
               Component.literal("§c§lKing Wither Skeleton§7!"),
               Component.literal("§cIf you die, the boss despawns."),
               Component.literal("§8Craft it: a wither skeleton skull surrounded"),
               Component.literal("§8by bone and coal")
            )
         )
      );
      return stack;
   }

   public static ItemStack raidBanner() {
      ItemStack stack = new ItemStack(Items.BANNER.red());
      setType(stack, "raid_banner");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550033.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lRaid Banner"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A blood-red banner that calls war."),
               Component.literal("§7Right-click it near a village to ignite a"),
               Component.literal("§c§lPlayer Raid§7 - defend the village and slay"),
               Component.literal("§7the Raid Warlord for the treasure."),
               Component.literal("§8Raids share a 30 minute cooldown per dimension."),
               Component.literal("§8Dropped by slain Raid Warlords.")
            )
         )
      );
      return stack;
   }

   public static ItemStack kingLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "king_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550005.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lKing Wither Skeleton Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the King Wither Skeleton.")
            )
         )
      );
      return stack;
   }

   /** Dropped by the reworked Wither ("The Ascended Wither"). Spins the same
    *  legendary pool as the King Wither Skeleton's box - the three wither
    *  legendaries (Blade, Crown, Staff) - so this is the wither's own box. */
   public static ItemStack witherLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "wither_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      // 4550025, not the King's 4550005: both boxes are chest *items*, so sharing
      // an id meant the resource pack could only ever resolve one of the two, and
      // the Wither Loot Box rendered as the King's.
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550025.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l☠ Wither Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Contains the three §5wither legendaries§7:"),
               Component.literal("§8the Wither Skeleton Blade, Crown and Staff."),
               Component.literal("§8Dropped by the Wither boss.")
            )
         )
      );
      return stack;
   }

   public static ItemStack raidLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "raid_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550019.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l⚔ Raid Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7The only way to obtain the raid legendaries!"),
               Component.literal("§8Dropped by the Raid Warlord and its lieutenants.")
            )
         )
      );
      return stack;
   }

   public static ItemStack golemLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "golem_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550018.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7§lStone Golem Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Stone Golem.")
            )
         )
      );
      return stack;
   }

   public static ItemStack slimeLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "slime_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550010.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSlime King Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Slime King.")
            )
         )
      );
      return stack;
   }

   public static ItemStack witherStaff() {
      ItemStack stack = new ItemStack(Items.BONE);
      setType(stack, "wither_staff");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(4550001.0F), List.of(), List.of(), List.of()));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWither Skeleton Staff"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to summon a friendly"),
               Component.literal("§fwither skeleton§7 to fight for you."),
               Component.literal("§8One per use - it follows you until it falls.")
            )
         )
      );
      return stack;
   }

   public static ItemStack witherBlade() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "wither_blade");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550002.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWither Skeleton Blade"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Hits like a stone sword, withers your enemy,"),
               Component.literal("§7and heals you on every kill."),
               Component.literal("§8Chance + heal scale with forge tier.")
            )
         )
      );
      AttributeModifier dmg = new AttributeModifier(FortuneFavorsMod.id("wither_blade_damage"), 4.0, Operation.ADD_VALUE);
      stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.ATTACK_DAMAGE, dmg, EquipmentSlotGroup.MAINHAND).build());
      return stack;
   }

   public static ItemStack witherCloakSword() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "wither_cloak_sword");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550032.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWither Cloak Sword"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to raise the §5Wither Shroud§7 for 12s:"),
               Component.literal("§7it negates the §fnext 3-5 hits§7 and locks you"),
               Component.literal("§7in place (Slowness I) while it holds."),
               Component.literal("§7Every hit it swallows is added to a§5 ledger§7."),
               Component.literal("§7When the shroud drops - or its last charge"),
               Component.literal("§7breaks - the ledger is paid back as a §5withering"),
               Component.literal("§5reprisal§7 that hits everything within §f5 blocks§7."),
               Component.literal("§7You pay §c2 hearts§7 and brief Wither to raise it."),
               Component.literal("§8Craft it: a Wither Blade over a Glacier Cloak.")
            )
         )
      );
      return stack;
   }

   public static ItemStack witherCrown() {
      ItemStack stack = new ItemStack(Items.NETHERITE_HELMET);
      setType(stack, "wither_crown");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550003.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWither Skeleton Crown"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Wither is nullified - it becomes"),
               Component.literal("§cRegeneration§7 instead (stronger per tier)."),
               Component.literal("§7Anyone who hits you gets Wither."),
               Component.literal("§8Upgrade at Tier III: §7slow passive regen outright."),
               Component.literal("§8Unbreakable.")
            )
         )
      );
      return stack;
   }

   public static ItemStack slimeBossToken() {
      ItemStack stack = new ItemStack(Items.SLIME_BALL);
      setType(stack, "slime_boss_token");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550006.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lGelatinous Crown"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The Slime King's own head, torn free"),
               Component.literal("§7and still faintly squirming. Right-click"),
               Component.literal("§7to summon the §a§lSlime King§r§7!"),
               Component.literal("§cIf you die, the boss despawns."),
               Component.literal("§8Craft it: slime blocks surrounding"),
               Component.literal("§8a golden helmet")
            )
         )
      );
      return stack;
   }

   public static ItemStack slimeLauncher() {
      ItemStack stack = new ItemStack(Items.SLIME_BALL);
      setType(stack, "slime_launcher");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550007.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSlime Launcher"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to fire a rack of slime balls at once:"),
               Component.literal("§72 at the bottom tier, 3 at tier II, 5 at tier III."),
               Component.literal("§7They bounce off walls and slow whatever they hit."),
               Component.literal("§8The left hand is left alone, so you can still swing.")
            )
         )
      );
      return stack;
   }

   public static ItemStack slimeShield() {
      ItemStack stack = new ItemStack(Items.SHIELD);
      setType(stack, "slime_shield");
      BlocksAttacks vanilla = (BlocksAttacks)new ItemStack(Items.SHIELD).get(DataComponents.BLOCKS_ATTACKS);
      if (vanilla != null) {
         stack.set(DataComponents.BLOCKS_ATTACKS, vanilla);
      }

      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550008.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSlime Shield"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Auto-blocks 3 hits from anywhere in your§r"),
               Component.literal("§7inventory (no need to raise it), then§r"),
               Component.literal("§7recharges (30s). Hold right-click to raise§r"),
               Component.literal("§7a wall of slime that blocks the vanilla way.§r"),
               Component.literal("§7Fall damage passes through - use the boots.§r"),
               Component.literal("§8Upgrade at Tier III: §76 auto-blocks, faster recharge.")
            )
         )
      );
      return stack;
   }

   public static ItemStack slimeBoots() {
      ItemStack stack = new ItemStack(Items.IRON_BOOTS);
      setType(stack, "slime_boots");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550009.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSlime Boots"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Iron-tier boots: no fall damage, you bounce."),
               Component.literal("§7Jump boost: §fLevel 1§7. Right-click in the air"),
               Component.literal("§7to cycle it (Off / I / II / III). Shift-right-click"),
               Component.literal("§7toggles the §fbounce§7 itself (handy when you just"),
               Component.literal("§7want to land flat). Hold §fShift§7 on the ground to"),
               Component.literal("§7charge a super jump, release to launch."),
               Component.literal("§8Unbreakable. Cannot be enchanted."),
               Component.literal("§8Forge it with Mythical Gelatin for Tier II/III")
            )
         )
      );
      return stack;
   }

   public static ItemStack kingBone() {
      ItemStack stack = new ItemStack(Items.BONE);
      setType(stack, "king_bone");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550011.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lWither Essence"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The King's condensed dark essence - its"),
               Component.literal("§7power made solid."),
               Component.literal("§8Upgrades wither legendaries to Tier II/III in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static ItemStack slimeCore() {
      ItemStack stack = new ItemStack(Items.SLIME_BALL);
      setType(stack, "slime_core");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550012.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lMythical Gelatin"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A shimmering lump of the Slime King's"),
               Component.literal("§7essence, distilled into pure gel."),
               Component.literal("§8Upgrades slime legendaries to Tier II/III in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static ItemStack stoneGolemToken() {
      ItemStack stack = new ItemStack(Items.CHISELED_STONE_BRICKS);
      setType(stack, "stone_golem_token");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550017.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§lBoulder Baby"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A grumpy little rock with a face."),
               Component.literal("§7Throw it - it shatters on impact, and"),
               Component.literal("§8§lStone Golem§r§7 rises from the rubble!"),
               Component.literal("§8It is nigh indestructible while armored -"),
               Component.literal("§8make it stagger itself to strike true.")
            )
         )
      );
      return stack;
   }

   public static ItemStack golemCore() {
      ItemStack stack = new ItemStack(Items.STONE);
      setType(stack, "golem_core");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550013.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lGolem Core"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The Stone Golem's rocky heart - still"),
               Component.literal("§7warm with earthen power."),
               Component.literal("§8Upgrades golem legendaries to Tier II/III in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static ItemStack stoneStaff() {
      ItemStack stack = new ItemStack(Items.STICK);
      setType(stack, "stone_staff");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550014.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§lStone Staff"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to call a §7§lgiant stone§r§7 down"),
               Component.literal("§7on the block you're looking at - it slams"),
               Component.literal("§7into the ground, dealing AOE damage and"),
               Component.literal("§7knockback to everything nearby."),
               Component.literal("§8Upgrade at Tier II/III: §7faster summon, heavier hit, wider blast")
            )
         )
      );
      return stack;
   }

   public static ItemStack golemFist() {
      ItemStack stack = new ItemStack(Items.STONE_AXE);
      setType(stack, "golem_fist");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550015.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7§lGolem's Fist"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A stone fist the size of a boulder - slow,"),
               Component.literal("§7heavy swings that send enemies flying."),
               Component.literal("§dGroundbreaker§7: right-click to slam the"),
               Component.literal("§7ground, shocking everything around you."),
               Component.literal("§8Upgrade at Tier II/III: §7heavier hits, bigger shockwave")
            )
         )
      );
      return stack;
   }

   public static ItemStack stoneHeart() {
      ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
      setType(stack, "stoneheart");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550016.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7§lStoneheart"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A chestplate carved from the Golem's core."),
               Component.literal("§7§lStand still§r§7 and you are immovable:"),
               Component.literal("§7no knockback and light damage resistance"),
               Component.literal("§7while you hold your ground."),
               Component.literal("§8Upgrade at Tier II/III: §7more armor")
            )
         )
      );
      return stack;
   }

   public static ItemStack tokenRedeemer() {
      ItemStack stack = new ItemStack(Items.GOLD_BLOCK);
      setType(stack, "token_redeemer");
      setModel(stack, TOKEN_REDEEMER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lToken Redeemer"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click it with a token in hand to"),
               Component.literal("§7redeem it for cash. Right-click it empty-"),
               Component.literal("§7handed to fund it and set payouts.")
            )
         )
      );
      return stack;
   }

   public static ItemStack itemForge() {
      ItemStack stack = new ItemStack(Items.SMITHING_TABLE);
      setType(stack, "item_forge");
      setModel(stack, ITEM_FORGE_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lItem Forge"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Place it and right-click it to open the"),
               Component.literal("§7forge - no need to hold boss drops."),
               Component.literal("§7Upgrade legendaries with Wither Essence /"),
               Component.literal("§7Mythical Gelatin, or fuse enchantment tomes on.")
            )
         )
      );
      return stack;
   }

   public static void setType(ItemStack stack, String type) {
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", type);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   private static CustomModelData cmd(float v) {
      return new CustomModelData(List.of(v), List.of(), List.of(), List.of());
   }

   /**
    * Custom-model ids for the items whose art lives outside this class (Excalibur
    * is minted by the command and the duel roll, the Mystery Keys by
    * {@code MysteryChestManager}). Kept together so the Java ids and the
    * thresholds in {@code tools/make_item_definitions.py} stay in step.
    */
   public static final float EXCALIBUR_MODEL = 4550280.0F;
   public static final float MYSTERY_BOX_MODEL = 4550281.0F;
   /** Four Mystery Key tiers, common to legendary. */
   public static final float[] MYSTERY_KEY_MODELS = {4550282.0F, 4550283.0F, 4550284.0F, 4550285.0F};

   /**
    * The Puppeteer's five items, and the ids they nearly shipped without.
    *
    * <p>They were written against 4550280-4550284 - the same numbers Excalibur, the
    * Mystery Box and the four Mystery Keys already own. A model id is only unique
    * <i>per base item</i>, so nothing crashed and nothing complained: it just meant
    * the five ids the Puppeteer's kit pointed at already belonged to somebody else,
    * which is why those items rendered as the plain vanilla armor stand, chest,
    * pumpkin, string and skull they are built on while every other boss's kit had
    * art. The ids below are theirs, and the pack's item definitions, models and
    * textures are written against them.
    */
   public static final float MARIONETTE_MODEL = 4550290.0F;
   public static final float PUPPETEER_BOX_MODEL = 4550291.0F;
   public static final float PUPPETEERS_MASK_MODEL = 4550292.0F;
   public static final float MARIONETTE_STRINGS_MODEL = 4550293.0F;
   public static final float EMPTY_MASK_MODEL = 4550294.0F;

   /**
    * The Sorter Tag - the one item that names a container for an Item Sorter to reach.
    *
    * <p>It is drawn on a sign, because a sign is the gesture people already make at a chest, but
    * it is a sign of its own: the tag system used to answer a click with *any* vanilla sign, which
    * meant a player could not label a chest without quietly turning it into a remote input for
    * every sorter that recognised its name. This item is the whole answer to "which sign is a
    * tag?" - only this one is, and an ordinary sign stays an ordinary sign.
    */
   public static final float SORTER_TAG_MODEL = 4550332.0F;

   // ------------------------------------------------------------------ machine held icons
   // A machine is a vanilla block with a name, and it used to wear the vanilla block's picture too:
   // the Super Hopper in a player's hand drew the same sprite as the hopper in a farm chest beside
   // it. These give each one a held icon of its own - see tools/make_machine_item_textures.py for
   // the art and the pack's assets/minecraft/items/*.json for the definitions the numbers land in.
   //
   // 4550340 upward is the first free block after the Sorter Tag, and each number is only required
   // to be unique on the base item it is minted on, which is why the seven hoppers sit in one file.
   public static final float AUTO_SELL_HOPPER_MODEL = 4550340.0F;
   public static final float UPWARDS_HOPPER_MODEL = 4550341.0F;
   public static final float ELEVATOR_MODEL = 4550342.0F;
   public static final float TOKEN_REDEEMER_MODEL = 4550343.0F;
   public static final float SPAWNER_INFUSER_MODEL = 4550344.0F;
   public static final float ITEM_FORGE_MODEL = 4550345.0F;
   public static final float CHUNK_ANCHOR_MODEL = 4550346.0F;
   public static final float REPAIR_STATION_MODEL = 4550347.0F;
   public static final float ITEM_SORTER_MODEL = 4550348.0F;
   public static final float SUPER_HOPPER_MODEL = 4550349.0F;
   public static final float TRANSFER_HOPPER_MODEL = 4550350.0F;
   public static final float CHECKER_HOPPER_MODEL = 4550351.0F;
   public static final float TWO_WAY_SPLITTER_MODEL = 4550352.0F;
   public static final float SUPER_SMELTER_MODEL = 4550353.0F;
   public static final float PORTABLE_FURNACE_MODEL = 4550354.0F;
   public static final float PORTABLE_CAMPFIRE_MODEL = 4550355.0F;
   public static final float AUTO_PLANTER_MODEL = 4550356.0F;
   public static final float AUTO_HARVESTER_MODEL = 4550357.0F;
   public static final float IRRIGATION_SPRINKLER_MODEL = 4550358.0F;
   public static final float OVERFLOW_HOPPER_MODEL = 4550359.0F;
   /**
    * The name a fresh Sorter Tag carries. A tag only claims the name a player *chose*, so the
    * binary-compare against this default is what keeps an unrenamed tag from claiming
    * {@code 3lSorter_Tag} as an id - see {@code ModEvents.heldTagName}.
    */
   public static final String SORTER_TAG_DEFAULT_NAME = "§3§lSorter Tag";

   /** Points an item at one of our model overrides. Never touches its type tag. */
   public static void setModel(ItemStack stack, float model) {
      if (stack != null && !stack.isEmpty()) {
         stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(model));
      }
   }

   /**
    * Excalibur - the duel roll's 1-in-1000 legendary. One factory so the command,
    * the duel pool and the item art can never drift apart: it is the same sword in
    * every place it is handed out.
    */
   public static ItemStack excalibur() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD, 1);
      setType(stack, "excalibur");
      setModel(stack, EXCALIBUR_MODEL);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l✦ Excalibur ✦"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§e§lThe Legendary Sword"),
               Component.literal("§7One hit. No mercy."),
               Component.literal("§81 use before it breaks.")
            )
         )
      );
      stack.set(DataComponents.DAMAGE, stack.getMaxDamage() - 1);
      return stack;
   }

   public static boolean isExcalibur(ItemStack stack) {
      return "excalibur".equals(typeOf(stack));
   }

   public static String typeOf(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         if (data == null) {
            return null;
         }

         String type = data.copyTag().getString("ff").orElse("");
         return type.isEmpty() ? null : type;
      } else {
         return null;
      }
   }

   public static boolean isChunkClaimer(ItemStack stack) {
      return "chunk_claimer".equals(typeOf(stack));
   }

   public static boolean isBuySign(ItemStack stack) {
      return "buy_sign".equals(typeOf(stack));
   }

   /**
    * The Potion Belt is carried rather than placed, so it has a right-click of its own.
    *
    * <p>"Rather than placed" is enforced rather than described: the belt is drawn on a pair of
    * leather leggings, which vanilla will not place, and it is named in the non-placeable list so a
    * future icon change cannot quietly turn the belt into a block that destroys its own charges.
    * The belt's whole function is a brewing stand, and the one thing a brewing stand in an
    * inventory must never do is become a brewing stand on the floor.
    */
   public static boolean isPotionBelt(ItemStack stack) {
      return TYPE_POTION_BELT.equals(typeOf(stack));
   }

   /** The Expedition Compass: right-click to erase a fatal-blow wait (see ExpeditionManager). */
   public static boolean isExpeditionCompass(ItemStack stack) {
      return TYPE_EXPEDITION_COMPASS.equals(typeOf(stack));
   }

   /**
    * Chunk Anchor: the one machine that is about where a player is NOT.
    *
    * <p>Everything else the mod sells needs somebody standing at it. This is the opposite - it
    * keeps the chunks around it loaded, so a farm keeps growing, a furnace keeps burning and a
    * machine keeps running while its owner is somewhere else entirely (or offline). Deliberately
    * the most expensive thing in the shop, and deliberately limited to one per player: a server
    * where everybody has four of these is a server that never unloads anything.
    */
   public static ItemStack chunkAnchor() {
      ItemStack stack = new ItemStack(Items.LODESTONE);
      setType(stack, "chunk_anchor");
      setModel(stack, CHUNK_ANCHOR_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lChunk Anchor"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Holds the §f3x3 chunks§7 around it loaded - whether"),
               Component.literal("§7or not anybody is standing there."),
               Component.literal("§7Crops grow, furnaces burn, machines run."),
               Component.literal("§8One per player. §7Sneak-right-click to pick it up.")
            )
         )
      );
      return stack;
   }

   /**
    * Repair Station: the anvil's job, priced instead of enchanted.
    *
    * <p>The anvil asks for XP and levels, which is a resource a farm can print. This asks for
    * money, which is the resource the whole server competes for - and it prices the repair off the
    * tool in front of it, so mending a netherite pickaxe is a decision and not a formality.
    */
   public static ItemStack repairStation() {
      ItemStack stack = new ItemStack(Items.GRINDSTONE);
      setType(stack, "repair_station");
      setModel(stack, REPAIR_STATION_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lRepair Station"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Put a damaged tool in and pay for it."),
               Component.literal("§7No levels, no XP - and the price rises with"),
               Component.literal("§7what the tool is worth and how far gone it is."),
               Component.literal("§8The more a player repairs, the dearer it gets.")
            )
         )
      );
      return stack;
   }

   /**
    * Item Sorter: a hopper that has opinions.
    *
    * <p>Right-click it with an item to add that item to its filter, empty-handed to cycle what it
    * is allowed to pull from (hoppers, any container, or both), and with a Backpack to take its
    * whole filter list off the pack. What it accepts it passes on; what it does not accept it
    * leaves exactly where it was.
    */
   public static ItemStack itemSorter() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "item_sorter");
      setModel(stack, ITEM_SORTER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lItem Sorter"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Pulls matching items out of the hoppers around"),
               Component.literal("§7it and passes them on - everything else it leaves"),
               Component.literal("§7alone. Configurable to read chests as well."),
               Component.literal("§8Right-click with an item: filter it in or out."),
               Component.literal("§8Empty hand: cycle hoppers / chests / both.")
            )
         )
      );
      return stack;
   }

   /**
    * Sorter Tag: names a container so a sorter can pull out of it from across the base.
    *
    * <p>It is a sign, and it is not placeable: the click on a container is the tag. A plain
    * vanilla sign is deliberately *not* one of these, so a player can keep putting ordinary signs
    * on their chests without every one of them becoming a remote input. The tag's name - the name
    * shown here - is the id a sorter names; rename it in an anvil to choose that id, or leave it
    * as it is and the mod hands out the next short {@code C1}, {@code C2}.
    */
   public static ItemStack sorterTag() {
      ItemStack stack = new ItemStack(Items.OAK_SIGN);
      setType(stack, "sorter_tag");
      setModel(stack, SORTER_TAG_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(SORTER_TAG_DEFAULT_NAME));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Names a chest, barrel or hopper for a sorter"),
               Component.literal("§7to reach - the tag is the address, not the place."),
               Component.literal("§8Right-click a container to tag it."),
               Component.literal("§8Sneak-right-click to remove a tag."),
               Component.literal("§8Rename it in an anvil to choose the tag's name.")
            )
         )
      );
      return stack;
   }

   public static boolean isSorterTag(ItemStack stack) {
      return "sorter_tag".equals(typeOf(stack));
   }

   /**
    * Super Hopper: a hopper that does what a hopper does, fast.
    *
    * <p>The vanilla hopper is the slowest mover in the game - one item every eight ticks - and a
    * farm that outruns it backs up at the hopper instead of at the chest. This one keeps a vanilla
    * hopper's shape, reach and one-item-per-tick ceiling-by-feel, but moves a whole stack a tick, so
    * it is the same machine with the throttle removed. It is deliberately unfiltered: it moves
    * exactly what a hopper would, just without the wait.
    */
   public static ItemStack superHopper() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "super_hopper");
      setModel(stack, SUPER_HOPPER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lSuper Hopper"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A hopper with the throttle removed - it moves"),
               Component.literal("§7a §ffull stack every tick§7 instead of one item"),
               Component.literal("§7every eight."),
               Component.literal("§8Above it pulls, the way it points it pushes."),
               Component.literal("§eRight-click it (empty hand) for its tiers -"),
               Component.literal("§eeach one moves more a tick than the last.")
            )
         )
      );
      return stack;
   }

   public static boolean isSuperHopper(ItemStack stack) {
      return "super_hopper".equals(typeOf(stack));
   }

   /**
    * Transfer Hopper: the sorter's transfer engine with no filter, plus tag links.
    *
    * <p>It pulls from the containers around it and from any tagged container it is linked to, and
    * hands everything on - the way it faces, around it, or to a tag of its own. It is the machine
    * for moving a whole chest elsewhere, and for feeding a line from across the base.
    *
    * <p>Its tags are an input list and one output, and the two never overlap: naming a container as
    * the output takes that tag off the input list, so a chest the hopper sends to is never also read
    * from - not even when it stands right beside the hopper. That is what makes the output tag a
    * one-way pipe, and it is also how two transfer hoppers are chained: tag the next one and point
    * this hopper's output at it. Its automatic route deliberately will not hand to another transfer
    * hopper, because two of them side by side would swap the same stack back and forth forever.
    */
   public static ItemStack transferHopper() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "transfer_hopper");
      setModel(stack, TRANSFER_HOPPER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lTransfer Hopper"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Moves §feverything§7 it can reach: the containers"),
               Component.literal("§7around it, plus any container it is §flinked§7 to."),
               Component.literal("§8Right-click: open its window and set links."),
               Component.literal("§8Click a tag to pull from it; §fshift-click§8 one to"),
               Component.literal("§8send everything there instead - an output tag."),
               Component.literal("§8A tagged chest is only ever sent to, never read"),
               Component.literal("§8from, so it is a one-way pipe."),
               Component.literal("§8Point the output at another Transfer Hopper to"),
               Component.literal("§8chain them down a line.")
            )
         )
      );
      return stack;
   }

   public static boolean isTransferHopper(ItemStack stack) {
      return "transfer_hopper".equals(typeOf(stack));
   }

   /**
    * Overflow Hopper: the Transfer Hopper that only sends to its tag when nothing else will take
    * the items.
    *
    * <p>The build it exists for is the one every farm ends up with: a chest fills, and the moment it
    * is full the farm stops. This is the same hopper with one rule changed - the container it points
    * at is filled first, exactly as an ordinary Transfer Hopper fills it, and the tag is the
    * <i>spillway</i> rather than the destination. While the first chest has room the tag is never
    * touched; the pulse the chest will not take another stack, that stack goes to the tag instead -
    * which can be a second chest, or another Transfer Hopper (or another Overflow Hopper) to carry
    * the surplus on down a line.
    *
    * <p>Everything else is the Transfer Hopper's: the same links in, the same one-way output tag, the
    * same refusal to read back out of the container it sends to, and the same window. See
    * {@link com.fortuneandfavors.economy.ItemSorter#tickOverflow}.
    */
   public static ItemStack overflowHopper() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "overflow_hopper");
      setModel(stack, OVERFLOW_HOPPER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lOverflow Hopper"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A Transfer Hopper that fills what it points at"),
               Component.literal("§7first - and only when that is §ffull§7 does it send"),
               Component.literal("§7the surplus to its §foutput tag§7."),
               Component.literal("§8Name a second chest as the output and the first"),
               Component.literal("§8chest stays full instead of the farm stalling."),
               Component.literal("§8Point it at another Transfer Hopper to carry the"),
               Component.literal("§8overflow on down the line."),
               Component.literal("§8Its window says whether the chest it is filling"),
               Component.literal("§8is full yet.")
            )
         )
      );
      return stack;
   }

   public static boolean isOverflowHopper(ItemStack stack) {
      return "overflow_hopper".equals(typeOf(stack));
   }

   /**
    * Item Checker Hopper: keeps only what none of your sorters want.
    *
    * <p>Point it at the output of a farm and it takes the leftovers - the items that no placed Item
    * Sorter is filtering - while leaving everything a sorter does want exactly where it is. Facing
    * another Checker Hopper hands the junk straight over (no travel), so a chain of them reaches a
    * chest across the base, or the void, without a belt of hoppers between.
    */
   public static ItemStack checkerHopper() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "checker_hopper");
      setModel(stack, CHECKER_HOPPER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lItem Checker Hopper"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Takes only what §fno sorter§7 wants - the junk"),
               Component.literal("§7a farm leaves behind - and passes it on."),
               Component.literal("§8Facing another Checker Hopper hands it over"),
               Component.literal("§8instantly: chain them to a far chest or a void."),
               Component.literal("§eRight-click it (empty hand) to switch it to §lvoid§e:"),
               Component.literal("§ethe junk is destroyed instead of travelling.")
            )
         )
      );
      return stack;
   }

   public static boolean isCheckerHopper(ItemStack stack) {
      return "checker_hopper".equals(typeOf(stack));
   }

   /**
    * 2-Way Splitter: one input, split evenly between the two sides beside it.
    */
   public static ItemStack twoWaySplitter() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      setType(stack, "two_way_splitter");
      setModel(stack, TWO_WAY_SPLITTER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l2-Way Splitter"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Splits what it holds §fequally§7 between the"),
               Component.literal("§7two containers beside it, one item each way."),
               Component.literal("§8Pulls from above like a hopper.")
            )
         )
      );
      return stack;
   }

   public static boolean isTwoWaySplitter(ItemStack stack) {
      return "two_way_splitter".equals(typeOf(stack));
   }

   /**
    * Super Smelter: a furnace faster than a blast furnace, that cooks anything - and eats fuel.
    *
    * <p>A blast furnace only knows ores and metals; this one runs every furnace recipe, so food,
    * glass, stone and clay smelt alongside the metal. At tier one it is a little over twice a plain
    * furnace's speed (a blast furnace is exactly twice) and spends its fuel faster to do it, and the
    * four tiers above it trade more of both - see {@link com.fortuneandfavors.economy.MachineTuning}
    * for the numbers and {@code MachineUpgradeMenu} for where they are bought.
    */
   public static ItemStack superSmelter() {
      ItemStack stack = new ItemStack(Items.FURNACE);
      setType(stack, "super_smelter");
      setModel(stack, SUPER_SMELTER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lSuper Smelter"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Smelts §feverything§7 - ores, food, glass, stone -"),
               Component.literal("§7faster than a blast furnace, and drinks fuel"),
               Component.literal("§7faster to do it."),
               Component.literal("§8Right-click to open it like a furnace."),
               Component.literal("§eSneak-right-click (empty hand) for its tiers -"),
               Component.literal("§eeach one cooks faster and burns fuel faster.")
            )
         )
      );
      return stack;
   }

   public static boolean isSuperSmelter(ItemStack stack) {
      return "super_smelter".equals(typeOf(stack));
   }

   /**
    * Portable Furnace: a furnace you can carry, and a fast one.
    *
    * <p>A blast furnace's own fire, in a block that folds up into your inventory - the reason to
    * own one is that a mining trip no longer needs a village with a furnace in it.
    */
   public static ItemStack portableFurnace() {
      ItemStack stack = new ItemStack(Items.BLAST_FURNACE);
      setType(stack, "portable_furnace");
      setModel(stack, PORTABLE_FURNACE_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lPortable Furnace"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Smelts ores and metals §ftwice as fast§7 as a"),
               Component.literal("§7plain furnace - and folds up into your pack"),
               Component.literal("§7when the trip is over."),
               Component.literal("§8Right-click it to cook. Sneak-right-click to pick it up.")
            )
         )
      );
      return stack;
   }

   /**
    * Portable Campfire: the same idea for food, and it wants nothing to burn.
    *
    * <p>A smoker's fire, carried: campfire recipes (which are the smoker's recipes) cook in half
    * the time a furnace takes, and the block is yours to pick up and put down wherever you are
    * cooking.
    */
   public static ItemStack portableCampfire() {
      ItemStack stack = new ItemStack(Items.SMOKER);
      setType(stack, "portable_campfire");
      setModel(stack, PORTABLE_CAMPFIRE_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lPortable Campfire"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Cooks food §ftwice as fast§7 as a furnace, using"),
               Component.literal("§7campfire recipes - and it packs away with you."),
               Component.literal("§8Right-click it to cook. Sneak-right-click to pick it up.")
            )
         )
      );
      return stack;
   }

   /**
    * Potion Belt: four draughts on your hip, a brewing stand bent around them, and a click to drink.
    *
    * <p>Carried rather than placed: right-click opens the belt, and clicking a flask drinks it on
    * the spot - no waiting for the bottle to be sipped, no second potion while the first is still
    * going down. Each flask has its own cooldown, because a belt with no cooldown is a bottle of
    * Speed with extra steps.
    *
    * <p>An empty flask is brewed full again, in the belt's own bay, with the game's own recipes: a
    * bottle, an ingredient and blaze powder, and a finished potion of one of the belt's four kinds
    * is drunk by the belt the moment it lands. See {@link com.fortuneandfavors.economy.PotionBelt}.
    */
   public static ItemStack potionBelt() {
      ItemStack stack = new ItemStack(Items.LEATHER_LEGGINGS);
      setType(stack, TYPE_POTION_BELT);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lPotion Belt"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Speed, Fire Resistance, Night Vision and"),
               Component.literal("§7Strength, brewed into one belt."),
               Component.literal("§8Right-click to open it, then click a flask to drink."),
               Component.literal("§8Brew a flask full in the bay: bottle, ingredient,"),
               Component.literal("§8blaze powder - the belt drinks the potion itself.")
            )
         )
      );
      return stack;
   }

   /**
    * Expedition Compass: the way back into a dungeon early.
    *
    * <p>A fatal blow inside an expedition ends the run, costs the secured loot and leaves a wait
    * behind - eight minutes in which the descent will not take you (see
    * {@link com.fortuneandfavors.economy.ExpeditionManager}). That wait is the only thing in the mod
    * that takes time away with nothing to do about it, so this is the something: right-click with a
    * compass and the rest of the wait is gone.
    *
    * <p>Built on a vanilla compass rather than on a block, because the whole item is one click and a
    * compass is what a player already reads as "this points at a place" - and it is spent only when
    * there is a wait to erase, so a compass in a pocket is never wasted by a curious click.
    */
   public static ItemStack expeditionCompass() {
      ItemStack stack = new ItemStack(Items.COMPASS);
      setType(stack, TYPE_EXPEDITION_COMPASS);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lExpedition Compass"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A fatal blow in a dungeon costs you the loot"),
               Component.literal("§7and then costs you time. Not with this."),
               Component.literal("§8Right-click: erase the rest of the fatal-blow wait."),
               Component.literal("§8Spent only when there is a wait to erase.")
            )
         )
      );
      return stack;
   }

   /**
    * Auto Planter: the farm's other half.
    *
    * <p>Stand it beside farmland and it keeps the ground sown - pulling seed out of the chest or
    * hopper next to it and planting whatever it finds, in the crop's own shape. Pair it with the
    * Auto Harvester and an Auto-Sell Hopper and the field runs itself.
    */
   public static ItemStack autoPlanter() {
      ItemStack stack = new ItemStack(Items.COMPOSTER);
      setType(stack, "auto_planter");
      setModel(stack, AUTO_PLANTER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§2§lAuto Planter"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Sows empty farmland around it from the container"),
               Component.literal("§7next to it: wheat, carrots, potatoes, beetroot"),
               Component.literal("§7and nether wart."),
               Component.literal("§8Place it beside farmland, fill a chest beside it.")
            )
         )
      );
      return stack;
   }

   /**
    * Auto Harvester: watches the field and takes it in when it is ready.
    *
    * <p>Mature crops in its own radius are cut and handed to the container beside it - which is
    * what makes it pair with the Auto-Sell Hopper rather than with a player's hands.
    */
   public static ItemStack autoHarvester() {
      ItemStack stack = new ItemStack(Items.OBSERVER);
      setType(stack, "auto_harvester");
      setModel(stack, AUTO_HARVESTER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lAuto Harvester"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Cuts every mature crop around it, the moment"),
               Component.literal("§7it is ripe, and hands the yield to the container"),
               Component.literal("§7beside it - hopper, chest, or Auto-Sell Hopper."),
               Component.literal("§8Pairs with the Auto Planter and the Auto-Sell Hopper.")
            )
         )
      );
      return stack;
   }

   /**
    * Irrigation Sprinkler: water that stays where it was put.
    *
    * <p>A vanilla farm is a grid of farmland and a bucket of water in the middle of it, and both
    * decay. This keeps the ground around it wet without a water channel anywhere, and the water it
    * throws is also a hard shove on whatever is growing: crops in its radius come in sooner.
    */
   public static ItemStack irrigationSprinkler() {
      ItemStack stack = new ItemStack(Items.CAULDRON);
      setType(stack, "irrigation_sprinkler");
      setModel(stack, IRRIGATION_SPRINKLER_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lIrrigation Sprinkler"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Keeps every farmland block around it watered"),
               Component.literal("§7with no channel to dig - and the crops in its"),
               Component.literal("§7radius grow §ffaster§7 than the sun alone would."),
               Component.literal("§8Place it on or beside the field.")
            )
         )
      );
      return stack;
   }

   public static boolean isSellSign(ItemStack stack) {
      return "sell_sign".equals(typeOf(stack));
   }

   public static boolean isMachineItem(ItemStack stack) {
      String type = typeOf(stack);
      return "auto_sell_hopper".equals(type)
         || "upwards_hopper".equals(type)
         || "elevator".equals(type)
         || "token_redeemer".equals(type)
         || "spawner_infuser".equals(type)
         || "item_forge".equals(type)
         || "chair".equals(type);
   }

   public static boolean isWormholePotion(ItemStack stack) {
      return "wormhole_potion".equals(typeOf(stack));
   }

   /** True when the stack is a vanilla spear (minecraft:[material]_spear)
    *  carrying the vanilla minecraft:lunge enchantment - the Lunge Spear. */
   public static boolean isLungeSpear(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(net.minecraft.tags.ItemTags.SPEARS)) {
         return false;
      }
      net.minecraft.world.item.enchantment.ItemEnchantments ench = (net.minecraft.world.item.enchantment.ItemEnchantments)stack.get(DataComponents.ENCHANTMENTS);
      if (ench == null) {
         return false;
      }
      for (var entry : ench.entrySet()) {
         if (entry.getKey().is(net.minecraft.world.item.enchantment.Enchantments.LUNGE) && entry.getIntValue() > 0) {
            return true;
         }
      }
      return false;
   }

   public static boolean isMysteryBox(ItemStack stack) {
      return "mystery_box".equals(typeOf(stack));
   }

   public static boolean isRaidBossToken(ItemStack stack) {
      return "raid_boss_token".equals(typeOf(stack));
   }

   public static boolean isRaidBanner(ItemStack stack) {
      return "raid_banner".equals(typeOf(stack));
   }

   public static boolean isKingLootBox(ItemStack stack) {
      return "king_loot_box".equals(typeOf(stack));
   }

   public static boolean isRaidLootBox(ItemStack stack) {
      return "raid_loot_box".equals(typeOf(stack));
   }

   public static boolean isWitherLootBox(ItemStack stack) {
      return "wither_loot_box".equals(typeOf(stack));
   }

   public static boolean isSlimeLootBox(ItemStack stack) {
      return "slime_loot_box".equals(typeOf(stack));
   }

   public static boolean isGolemLootBox(ItemStack stack) {
      return "golem_loot_box".equals(typeOf(stack));
   }

   public static boolean isWitherStaff(ItemStack stack) {
      return "wither_staff".equals(typeOf(stack));
   }

   public static boolean isWitherBlade(ItemStack stack) {
      return "wither_blade".equals(typeOf(stack));
   }

   public static boolean isWitherCrown(ItemStack stack) {
      return "wither_crown".equals(typeOf(stack));
   }

   public static boolean isWitherCloakSword(ItemStack stack) {
      return "wither_cloak_sword".equals(typeOf(stack));
   }

   public static int wcsBlocks(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0 : data.copyTag().getIntOr("ff_wcs_blocks", 0);
   }

   public static void setWcsBlocks(ItemStack stack, int blocks) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();
         if (blocks <= 0) {
            tag.remove("ff_wcs_blocks");
         } else {
            tag.putInt("ff_wcs_blocks", blocks);
         }

         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   /**
    * The tier a machine was bought up to, carried on the item.
    *
    * <p>The tier lives on the block, not the item - a player upgrades the machine where it stands.
    * But a machine is also a thing that gets moved, and a Super Smelter that lost six hundred
    * thousand of upgrades to a pickaxe would be a trap. So the number is copied on to the stack when
    * the machine comes up and read back off it when the machine goes down, which is the same way the
    * owner and the payout are carried. See {@link com.fortuneandfavors.economy.MachineTuning}.
    */
   public static int machineTier(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 1 : Math.max(1, data.copyTag().getIntOr("ff_tier", 1));
   }

   public static void setMachineTier(ItemStack stack, int tier) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();
      if (tier <= 1) {
         tag.remove("ff_tier");
      } else {
         tag.putInt("ff_tier", tier);
      }
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   /** Whether a Checker Hopper was set to destroy what nobody wants. Travels with the item. */
   public static boolean machineVoiding(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBooleanOr("ff_void", false);
   }

   public static void setMachineVoiding(ItemStack stack, boolean on) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();
      if (on) {
         tag.putBoolean("ff_void", true);
      } else {
         tag.remove("ff_void");
      }
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   public static boolean isSlimeBossToken(ItemStack stack) {
      return "slime_boss_token".equals(typeOf(stack));
   }

   public static boolean isSlimeLauncher(ItemStack stack) {
      return "slime_launcher".equals(typeOf(stack));
   }

   public static boolean isSlimeShield(ItemStack stack) {
      return "slime_shield".equals(typeOf(stack));
   }

   public static boolean isSlimeBoots(ItemStack stack) {
      return "slime_boots".equals(typeOf(stack));
   }

   public static boolean isKingBone(ItemStack stack) {
      return "king_bone".equals(typeOf(stack));
   }

   public static boolean isSlimeCore(ItemStack stack) {
      return "slime_core".equals(typeOf(stack));
   }

   public static boolean isSlimeTrophy(ItemStack stack) {
      return "slime_trophy".equals(typeOf(stack));
   }

   public static boolean isStoneGolemToken(ItemStack stack) {
      return "stone_golem_token".equals(typeOf(stack));
   }

   public static boolean isGolemCore(ItemStack stack) {
      return "golem_core".equals(typeOf(stack));
   }

   public static boolean isGolemTrophy(ItemStack stack) {
      return "golem_trophy".equals(typeOf(stack));
   }

   public static boolean isStoneStaff(ItemStack stack) {
      return "stone_staff".equals(typeOf(stack));
   }

   public static boolean isGolemFist(ItemStack stack) {
      return "golem_fist".equals(typeOf(stack));
   }

   public static boolean isStoneheart(ItemStack stack) {
      return "stoneheart".equals(typeOf(stack));
   }

   public static ItemStack mindbinderEye() {
      ItemStack stack = new ItemStack(Items.ENDER_EYE);
      setType(stack, "mindbinder_eye");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550019.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lOminous Eye"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A gold-ringed eye that stares back at you."),
               Component.literal("§7Throw it to call the §5Mindbinder§7..."),
               Component.literal("§7it will see you.")
            )
         )
      );
      return stack;
   }

   public static ItemStack shatteredMind() {
      ItemStack stack = new ItemStack(Items.PHANTOM_MEMBRANE);
      setType(stack, "shattered_mind");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550020.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lShattered Mind"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A fractured thought torn from the"),
               Component.literal("§5Mindbinder's§7 core."),
               Component.literal("§8Upgrades mindbinder legendaries to Tier II/III in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static ItemStack mindbinderStaff() {
      ItemStack stack = new ItemStack(Items.BLAZE_ROD);
      setType(stack, "mind_staff");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550021.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lStaff of the Mindbinder"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A staff that whispers with stolen minds."),
               Component.literal("§5Control§7 (default): right-click the creature"),
               Component.literal("§7you are looking at (up to 40 blocks) to seize"),
               Component.literal("§7its mind. Mash §fJUMP§7 to win the struggle -"),
               Component.literal("§7stronger minds resist harder. Seize a mob 3"),
               Component.literal("§7times and it becomes your §afriend for life§7."),
               Component.literal("§7Seizing a §cplayer§7 is brutally hard - a win"),
               Component.literal("§7holds them 30s (they can mash JUMP to break free)."),
               Component.literal("§bHeal§7 mode: right-click a creature to drink its"),
               Component.literal("§7life - its health (and a player's hunger) becomes"),
               Component.literal("§7yours. §8Sneak-right-click switches modes."),
               Component.literal("§8Bosses are too strong to seize.")
            )
         )
      );
      return stack;
   }

   public static ItemStack mindLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "mind_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550023.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lMindbinder Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Mindbinder.")
            )
         )
      );
      return stack;
   }

   public static ItemStack possessedMask() {
      ItemStack stack = new ItemStack(Items.IRON_HELMET);
      setType(stack, "possessed_mask");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550022.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lPossessed Mask"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A mask carved to look like the Mindbinder's"),
               Component.literal("§7own face. It remembers its master's tricks:"),
               Component.literal("§7mind-corruption drains away while worn."),
               Component.literal("§7Outside a Mindbinder fight it shields the mind:"),
               Component.literal("§7no nausea, weakness, blindness or daze - and"),
               Component.literal("§7nearby hostiles GLOW through walls (possession"),
               Component.literal("§7sense, stronger at higher tiers)."),
               Component.literal("§8Sneak-right-click: Self-Control Surge")
            )
         )
      );
      return stack;
   }

   public static boolean isMindbinderEye(ItemStack stack) {
      return "mindbinder_eye".equals(typeOf(stack));
   }

   public static boolean isShatteredMind(ItemStack stack) {
      return "shattered_mind".equals(typeOf(stack));
   }

   public static boolean isMindbinderStaff(ItemStack stack) {
      return "mind_staff".equals(typeOf(stack));
   }

   public static boolean isPossessedMask(ItemStack stack) {
      return "possessed_mask".equals(typeOf(stack));
   }

   public static ItemStack mindbinderShroud() {
      ItemStack stack = new ItemStack(Items.NETHERITE_CHESTPLATE);
      setType(stack, "mind_shroud");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550024.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lMindbinder's Shroud"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A robe torn from the Mindbinder's own form."),
               Component.literal("§7Lesser minds dull near it: nearby hostiles are"),
               Component.literal("§7slowed and weakened while you wear it."),
               Component.literal("§8Sneak-right-click: Mindshock - a psychic burst"),
               Component.literal("§8that stuns and scatters nearby mobs.")
            )
         )
      );
      return stack;
   }

   public static boolean isMindbinderShroud(ItemStack stack) {
      return "mind_shroud".equals(typeOf(stack));
   }

   public static boolean isMindLootBox(ItemStack stack) {
      return "mind_loot_box".equals(typeOf(stack));
   }

   public static ItemStack snowQueenToken() {
      ItemStack stack = new ItemStack(Items.SNOWBALL);
      setType(stack, "snow_queen_token");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550026.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lCryogenic Core"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The Snow Queen's heart, frozen mid-beat and"),
               Component.literal("§7still crackling with her storm. Right-click"),
               Component.literal("§7to summon the §b§lSnow Queen§7!"),
               Component.literal("§8Craft it: snow blocks surrounding a diamond")
            )
         )
      );
      return stack;
   }

   public static ItemStack snowLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "snow_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550027.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lSnow Queen Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Snow Queen.")
            )
         )
      );
      return stack;
   }

   public static ItemStack iceStaff() {
      ItemStack stack = new ItemStack(Items.BLAZE_ROD);
      setType(stack, "ice_staff");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550028.0F));
      stack.set(DataComponents.MAX_DAMAGE, 100);
      stack.set(DataComponents.DAMAGE, 0);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lIce Staff"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click hurls a ball of frost that"),
               Component.literal("§7blooms a freezing mist where it lands."),
               Component.literal("§7Hold to keep spraying: freezes water solid,"),
               Component.literal("§7turns rain to snow around you, builds freeze."),
               Component.literal("§7At zero charge it recharges - it never breaks."),
               Component.literal("§7Its glow dims as the charge runs low."),
               Component.literal("§8Dropped by the Snow Queen. Forge it with Frozen Hearts.")
            )
         )
      );
      return stack;
   }

   public static ItemStack frozenHeart() {
      ItemStack stack = new ItemStack(Items.PRISMARINE_CRYSTALS);
      setType(stack, "frozen_heart");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550029.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lFrozen Heart"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The Snow Queen's heart, torn from her chest"),
               Component.literal("§7while it was still beating."),
               Component.literal("§8Upgrades snow queen legendaries to Tier II/III in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static ItemStack frostboundCrown() {
      ItemStack stack = new ItemStack(Items.BOW);
      setType(stack, "frostbound_crown");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550030.0F));
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lFrostbound Bow"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Hold to draw - release to loose a volley"),
               Component.literal("§7of ice shards that chill and freeze."),
               Component.literal("§8A full draw looses an extra shard and"),
               Component.literal("§8flings them faster. Never needs arrows."),
               Component.literal("§8Dropped by the Snow Queen. Forge it with Frozen Hearts.")
            )
         )
      );
      return stack;
   }

   public static ItemStack glacierCloak() {
      ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
      setType(stack, "glacier_cloak");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550031.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lGlacier Cloak"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A cloak of packed glacier ice. While worn, a"),
               Component.literal("§7cold aura slows enemies who get too close."),
               Component.literal("§8Dropped by the Snow Queen.")
            )
         )
      );
      return stack;
   }

   public static boolean isSnowQueenToken(ItemStack stack) {
      return "snow_queen_token".equals(typeOf(stack));
   }

   public static boolean isSnowLootBox(ItemStack stack) {
      return "snow_loot_box".equals(typeOf(stack));
   }

   public static boolean isIceStaff(ItemStack stack) {
      return "ice_staff".equals(typeOf(stack));
   }

   public static boolean isFrozenHeart(ItemStack stack) {
      return "frozen_heart".equals(typeOf(stack));
   }

   public static boolean isFrostboundCrown(ItemStack stack) {
      return "frostbound_crown".equals(typeOf(stack));
   }

   public static boolean isGlacierCloak(ItemStack stack) {
      return "glacier_cloak".equals(typeOf(stack));
   }

   // ---------------------------------------------------------------- Time Lord

   /** Key for a per-stack cooldown, stored as an absolute game time. Keeping it
    *  on the item means a cooldown survives drops, trades and restarts - and can
    *  never be reset by re-logging. */
   public static final String COOLDOWN_KEY = "ff_item_cd";

   public static long cooldownUntil(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0L : data.copyTag().getLong(COOLDOWN_KEY).orElse(0L);
   }

   public static void setCooldownUntil(ItemStack stack, long until) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putLong(COOLDOWN_KEY, until);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   /** Whole seconds still to wait before this stack may be used again (0 = ready). */
   public static long cooldownSecondsLeft(ItemStack stack, long gameTime) {
      long left = cooldownUntil(stack) - gameTime;
      return left <= 0L ? 0L : (left + 19L) / 20L;
   }

   /** The Space-Time Rift: the clock that tears a hole in time and calls him through. */
   public static ItemStack spaceTimeRift() {
      ItemStack stack = new ItemStack(Items.CLOCK);
      setType(stack, "space_time_rift");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550210.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lSpace-Time Rift"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to tear a rift in time."),
               Component.literal("§7Something on the other side has been"),
               Component.literal("§7waiting a very long time to step through."),
               Component.literal("§8Summons §dThe Time Lord§8."),
               Component.literal("§8Not tradeable, not placeable.")
            )
         )
      );
      return stack;
   }

   public static boolean isSpaceTimeRift(ItemStack stack) {
      return "space_time_rift".equals(typeOf(stack));
   }

   public static ItemStack timeLordLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "time_lord_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550211.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lTime Lord Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by The Time Lord.")
            )
         )
      );
      return stack;
   }

   public static boolean isTimeLordLootBox(ItemStack stack) {
      return "time_lord_loot_box".equals(typeOf(stack));
   }

   /** True for the three Time Lord legendaries - the loot box family pool. */
   public static boolean isTimeLordLegendary(ItemStack stack) {
      return isPocketWatch(stack) || isChronoShard(stack) || isHourglass(stack);
   }

   /** Pocket-Watch. Version I freezes 5s on a 60s cooldown; the upgraded
    *  Version II freezes 10s and pays for the longer stop with an 80s cooldown. */
   public static ItemStack pocketWatch() {
      return pocketWatch(1);
   }

   public static ItemStack pocketWatch(int version) {
      int v = version >= 2 ? 2 : 1;
      ItemStack stack = new ItemStack(Items.CLOCK);
      setType(stack, "pocket_watch");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      // Version II has art of its own (the reforged, netherite watch).
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(v >= 2 ? 4550213.0F : 4550212.0F));
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(v >= 2 ? "§d§lPocket-Watch §7[§dII§7]" : "§d§lPocket-Watch §7[§dI§7]")
      );
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to stop time around you."),
               Component.literal("§7Everything but you freezes in place."),
               Component.literal("§dStop: " + pocketWatchSeconds(v) + "s §8· §7Cooldown: " + (v >= 2 ? "80s" : "60s")),
               Component.literal("§8Upgrade: hold a §7Netherite Ingot§8 in your"),
               Component.literal("§8off hand and sneak-right-click."),
               Component.literal("§8Obtained from the Time Lord Loot Box.")
            )
         )
      );
      setPocketWatchVersion(stack, v);
      return stack;
   }

   public static int pocketWatchSeconds(int version) {
      return version >= 2 ? 10 : 5;
   }

   public static int pocketWatchVersion(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0 : data.copyTag().getInt("ff_pw_version").orElse(0);
   }

   public static void setPocketWatchVersion(ItemStack stack, int version) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_pw_version", version >= 2 ? 2 : 1);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(version >= 2 ? 4550213.0F : 4550212.0F));
         stack.set(
            DataComponents.CUSTOM_NAME,
            Component.literal(version >= 2 ? "§d§lPocket-Watch §7[§dII§7]" : "§d§lPocket-Watch §7[§dI§7]")
         );
      }
   }

   public static boolean isPocketWatch(ItemStack stack) {
      return "pocket_watch".equals(typeOf(stack));
   }

   public static ItemStack chronoShard() {
      ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
      setType(stack, "chrono_shard");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550213.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lChrono Shard"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to rewind yourself 5 seconds:"),
               Component.literal("§7you return to where you stood and to the"),
               Component.literal("§7health you had back then."),
               Component.literal("§8Cooldown: 60s."),
               Component.literal("§8Obtained from the Time Lord Loot Box.")
            )
         )
      );
      return stack;
   }

   public static boolean isChronoShard(ItemStack stack) {
      return "chrono_shard".equals(typeOf(stack));
   }

   public static ItemStack hourglassOfHaste() {
      ItemStack stack = new ItemStack(Items.HONEYCOMB);
      setType(stack, "hourglass_of_haste");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550214.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lHourglass of Haste"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to accelerate yourself:"),
               Component.literal("§eSpeed II§7, §eHaste II§7 and §eJump Boost I§7 for 8s."),
               Component.literal("§cThen the bill comes due:"),
               Component.literal("§cSlowness II for 3s§7 when it ends."),
               Component.literal("§8Cooldown: 90s."),
               Component.literal("§8Obtained from the Time Lord Loot Box.")
            )
         )
      );
      return stack;
   }

   public static boolean isHourglass(ItemStack stack) {
      return "hourglass_of_haste".equals(typeOf(stack));
   }

   // ------------------------------------------------------------ Scarlet Devil

   /**
    * The Scarlet-Blood - a vial of her own blood, and the key to the fight.
    * Right-click to pour it out and call her through.
    */
   public static ItemStack scarletBlood() {
      ItemStack stack = new ItemStack(Items.GLASS_BOTTLE);
      setType(stack, "scarlet_blood");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550230.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lScarlet-Blood"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A vial of blood that never clots."),
               Component.literal("§7Right-click to pour it out - the Scarlet"),
               Component.literal("§7Devil answers her own blood."),
               Component.literal("§8Summons the §4Scarlet Devil§8."),
               Component.literal("§8Not tradeable, not placeable.")
            )
         )
      );
      return stack;
   }

   public static boolean isScarletBlood(ItemStack stack) {
      return "scarlet_blood".equals(typeOf(stack));
   }

   /** Scarlet Devil Loot Box - her own spin on the raid-boss box. */
   public static ItemStack scarletLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "scarlet_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550235.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lScarlet Devil Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Scarlet Devil.")
            )
         )
      );
      return stack;
   }

   public static boolean isScarletLootBox(ItemStack stack) {
      return "scarlet_loot_box".equals(typeOf(stack));
   }

   /** Scarlet Devil's Trophy: the bragging-rights drop. */
   public static ItemStack scarletTrophy() {
      ItemStack stack = new ItemStack(Items.GOLD_BLOCK);
      setType(stack, "scarlet_trophy");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550231.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§l☾ Scarlet Devil's Trophy"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Proof that you slew the Scarlet Devil."),
               Component.literal("§7It still beats, faintly, if you listen."),
               Component.literal("§8Dropped by the Scarlet Devil.")
            )
         )
      );
      return stack;
   }

   public static boolean isScarletTrophy(ItemStack stack) {
      return "scarlet_trophy".equals(typeOf(stack));
   }

   /**
    * Bloodsoaked Core - the Scarlet Devil's ONE forge material (her drop table
    * used to also spit out a Crimson Essence, which did the exact same job and
    * only ever confused the set). Feed one into the Item Forge alongside one of
    * her three legendary weapons to push it to the next tier, exactly like the
    * other bosses' cores.
    */
   public static ItemStack bloodsoakedCore() {
      ItemStack stack = new ItemStack(Items.PRISMARINE_CRYSTALS);
      setType(stack, "bloodsoaked_core");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550232.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lBloodsoaked Core"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A forge material - NOT a block."),
               Component.literal("§7Use it in the Item Forge to upgrade"),
               Component.literal("§7one of her three legendary weapons."),
               Component.literal("§8Dropped by the Scarlet Devil.")
            )
         )
      );
      return stack;
   }

   public static boolean isBloodsoakedCore(ItemStack stack) {
      return "bloodsoaked_core".equals(typeOf(stack));
   }

   /**
    * Scarlet Fang - her spear-turned-sword. Every hit it lands drinks a little
    * of the target's life and gives it to the wielder (see CombatGear.apply).
    */
   public static ItemStack scarletFang() {
      // A spear, not a sword: the Fang keeps the vanilla spear's thrust and its
      // long in-hand model (see tools/make_item_definitions.py), with her own
      // textures on top.
      ItemStack stack = new ItemStack(Items.DIAMOND_SPEAR);
      setType(stack, "scarlets_fang");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550234.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lScarlet Fang"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Her spear, its head carved into a fang."),
               Component.literal("§cBloodsuck:§7 each hit you land heals you"),
               Component.literal("§7for a share of the damage."),
               Component.literal("§8Dropped by the Scarlet Devil.")
            )
         )
      );
      return stack;
   }

   public static boolean isScarletFang(ItemStack stack) {
      return "scarlets_fang".equals(typeOf(stack));
   }

   /**
    * The Blood Grimoire - her move set, bound into a book. Right-click casts the
    * next of her attacks; sneak-right-click cycles which one. The point of the
    * item is that beating her hands you the toolkit she used on you.
    */
   public static ItemStack scarletGrimoire() {
      ItemStack stack = new ItemStack(Items.BOOK);
      setType(stack, "scarlet_grimoire");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550236.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lScarlet Grimoire"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Her own book of blood magic."),
               Component.literal("§7Right-click: cast the next spell."),
               Component.literal("§7Sneak-right-click: cycle spells."),
               Component.literal("§8Dropped by the Scarlet Devil.")
            )
         )
      );
      return stack;
   }

   public static boolean isScarletGrimoire(ItemStack stack) {
      return "scarlet_grimoire".equals(typeOf(stack));
   }

   /**
    * The Blood Prism - her ritual, in your hand. Right-click a living thing to
    * bleed it into a Blood Revenant that fights for you.
    *
    * <p>Deliberately different from dying to her: the ritual never touches a
    * player's inventory. A converted mob keeps its own gear (it has none); a
    * converted player simply fights on your side for a while. Nothing is taken
    * and nothing has to be reclaimed.
    */
   public static ItemStack bloodPrism() {
      ItemStack stack = new ItemStack(Items.PRISMARINE_SHARD);
      setType(stack, "blood_prism");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550237.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lBlood Prism"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Her blood ritual, cut into crystal."),
               Component.literal("§7Right-click a mob or player to bleed"),
               Component.literal("§7them into a Blood Revenant that fights"),
               Component.literal("§7for you for a while."),
               Component.literal("§8Never takes anyone's items."),
               Component.literal("§8Dropped by the Scarlet Devil.")
            )
         )
      );
      return stack;
   }

   public static boolean isBloodPrism(ItemStack stack) {
      return "blood_prism".equals(typeOf(stack));
   }

   /**
    * True for the Scarlet Devil's three legendary weapons - the loot box pool
    * and the set the Bloodsoaked Core upgrades in the Item Forge.
    *
    * <p>The Trophy is deliberately NOT in here: it is a trophy, and the Core is
    * the material, so listing either of them as a legendary is what made the set
    * read as "one legendary and two bits of junk".
    */
   public static boolean isScarletLegendary(ItemStack stack) {
      return isScarletFang(stack) || isScarletGrimoire(stack) || isBloodPrism(stack);
   }

   // ------------------------------------------------------------ Clockwork King

   /**
    * The Clockwork Core - the King's winding key, and the key to the fight.
    * Right-click to insert it and let him assemble.
    */
   public static ItemStack clockworkCore() {
      ItemStack stack = new ItemStack(Items.CLOCK);
      setType(stack, "clockwork_core");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550240.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Clockwork Core"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A mainspring that never unwinds."),
               Component.literal("§7Right-click to wind it - the Clockwork"),
               Component.literal("§7King answers his own key."),
               Component.literal("§8Summons the §6Clockwork King§8."),
               Component.literal("§8Not tradeable, not placeable.")
            )
         )
      );
      return stack;
   }

   public static boolean isClockworkCore(ItemStack stack) {
      return "clockwork_core".equals(typeOf(stack));
   }

   /** Clockwork King Loot Box - his own spin on the raid-boss box. */
   public static ItemStack clockworkLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "clockwork_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550245.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Clockwork Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Clockwork King.")
            )
         )
      );
      return stack;
   }

   public static boolean isClockworkLootBox(ItemStack stack) {
      return "clockwork_loot_box".equals(typeOf(stack));
   }

   /** The King's Trophy: the bragging-rights drop. */
   public static ItemStack clockworkTrophy() {
      ItemStack stack = new ItemStack(Items.GOLD_BLOCK);
      setType(stack, "clockwork_trophy");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550241.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Clockwork King's Trophy"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Proof that you scrapped the Clockwork King."),
               Component.literal("§7His mainspring is still ticking inside."),
               Component.literal("§8Dropped by the Clockwork King.")
            )
         )
      );
      return stack;
   }

   public static boolean isClockworkTrophy(ItemStack stack) {
      return "clockwork_trophy".equals(typeOf(stack));
   }

   /**
    * Mech-Scrap - the Clockwork King's forge material. Feed it into the Item Forge
    * alongside one of his three legendaries to push it to the next tier, exactly
    * like the other bosses' cores.
    */
   public static ItemStack mechScrap() {
      ItemStack stack = new ItemStack(Items.PRISMARINE_CRYSTALS);
      setType(stack, "mech_scrap");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550242.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Mech-Scrap"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A forge material - NOT a block."),
               Component.literal("§7Use it in the Item Forge to upgrade"),
               Component.literal("§7one of his three legendary items."),
               Component.literal("§8Dropped by the Clockwork King.")
            )
         )
      );
      return stack;
   }

   public static boolean isMechScrap(ItemStack stack) {
      return "mech_scrap".equals(typeOf(stack));
   }

   /**
    * The Clockwork Gauntlet - his legendary weapon. Every fourth landed hit winds
    * it up to Overdrive (see {@code ClockworkGear}), and the next one drives a
    * piston ram straight through whatever stands in front of you. The ram is an
    * on-hit ability, so a right-click on it does - and should do - nothing.
    */
   public static ItemStack clockworkGauntlet() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "clockwork_gauntlet");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550243.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Clockwork Gauntlet"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7His stamping arm, worn as a fist."),
               Component.literal("§7Every fourth hit winds the Overdrive;"),
               Component.literal("§7the next one drives a piston ram."),
               Component.literal("§8Dropped by the Clockwork King.")
            )
         )
      );
      return stack;
   }

   public static boolean isClockworkGauntlet(ItemStack stack) {
      return "clockwork_gauntlet".equals(typeOf(stack));
   }

   /**
    * The Mechanical Heart - his legendary trinket. Held anywhere in the inventory
    * it ticks: a slow self-repair that stops while you are being hit, so it rewards
    * breaking contact instead of out-tanking the boss.
    */
   public static ItemStack mechanicalHeart() {
      ItemStack stack = new ItemStack(Items.HEART_OF_THE_SEA);
      setType(stack, "mechanical_heart");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550244.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Mechanical Heart"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7It ticks whether or not you do."),
               Component.literal("§7Hold it to your ear and you can hear"),
               Component.literal("§7the shop floor it was built on."),
               Component.literal("§8Dropped by the Clockwork King.")
            )
         )
      );
      return stack;
   }

   public static boolean isMechanicalHeart(ItemStack stack) {
      return "mechanical_heart".equals(typeOf(stack));
   }

   /**
    * Automaton Armor - his legendary chestplate. Plated, fireproof and heavy: it
    * carries a permanent armour bonus and knockback resistance, and it refuses to
    * burn, which is the reason to wear it over netherite in a scrap fight.
    */
   public static ItemStack automatonArmor() {
      // Leggings, not a chestplate: the Clockwork King's own plating is a pair of
      // greaves. Every other boss legendary occupies its own slot (the Mantle is a
      // chest, the Plate is a chest, the Warlord's Cloak is a chest), so as a
      // chestplate this piece could never be worn alongside them - which is the
      // whole point of a set you collect one piece at a time.
      ItemStack stack = new ItemStack(Items.NETHERITE_LEGGINGS);
      setType(stack, "automaton_armor");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550246.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚙ Automaton Armor"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7His own plating, cut down to fit you."),
               Component.literal("§7Forged, not grown - it carries the weight"),
               Component.literal("§7of every gear he ever installed."),
               Component.literal("§8Dropped by the Clockwork King.")
            )
         )
      );
      return stack;
   }

   public static boolean isAutomatonArmor(ItemStack stack) {
      return "automaton_armor".equals(typeOf(stack));
   }

   /**
    * True for the Clockwork King's three legendaries - the loot box pool and the
    * set Mech-Scrap upgrades in the Item Forge. The Trophy is a trophy and the
    * Scrap is the material, so neither is listed here.
    */
   public static boolean isClockworkLegendary(ItemStack stack) {
      return isClockworkGauntlet(stack) || isMechanicalHeart(stack) || isAutomatonArmor(stack);
   }

   // -------------------------------------------------------- Starbound Magister

   /**
    * The Astral Compass - the Magister's summon. Unlike the other boss tokens it
    * is not a key to a door: she is a wandering fight, so the needle simply points
    * her at whoever winds it.
    */
   public static ItemStack astralCompass() {
      ItemStack stack = new ItemStack(Items.COMPASS);
      setType(stack, "astral_compass");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550250.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Astral Compass"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Its needle does not point north."),
               Component.literal("§7Right-click and the sky answers with"),
               Component.literal("§7the §bStarbound Magister§7."),
               Component.literal("§8She fights wherever you are.")
            )
         )
      );
      return stack;
   }

   public static boolean isAstralCompass(ItemStack stack) {
      return "astral_compass".equals(typeOf(stack));
   }

   public static ItemStack starboundLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "starbound_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550256.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Starbound Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Starbound Magister.")
            )
         )
      );
      return stack;
   }

   public static boolean isStarboundLootBox(ItemStack stack) {
      return "starbound_loot_box".equals(typeOf(stack));
   }

   public static ItemStack starboundTrophy() {
      ItemStack stack = new ItemStack(Items.NETHER_STAR);
      setType(stack, "starbound_trophy");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550251.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Starbound Trophy"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A star, still warm, in a glass case."),
               Component.literal("§7She kept it on a shelf and called it"),
               Component.literal("§7a spare."),
               Component.literal("§8Dropped by the Starbound Magister.")
            )
         )
      );
      return stack;
   }

   public static boolean isStarboundTrophy(ItemStack stack) {
      return "starbound_trophy".equals(typeOf(stack));
   }

   /** Magical Essence - the Magister's forge material, straight off her ceiling. */
   public static ItemStack magicalEssence() {
      ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
      setType(stack, "magical_essence");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550252.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Magical Essence"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A forge material - NOT a gem."),
               Component.literal("§7Use it in the Item Forge to upgrade"),
               Component.literal("§7one of her three legendary items."),
               Component.literal("§8Dropped by the Starbound Magister.")
            )
         )
      );
      return stack;
   }

   public static boolean isMagicalEssence(ItemStack stack) {
      return "magical_essence".equals(typeOf(stack));
   }

   /**
    * The Starpiercer - her legendary weapon. Every few hits it banks Astral
    * Charge (see {@code MagisterGear}); a full charge turns the next swing into a
    * piercing star that runs through everything in a line.
    */
   public static ItemStack starpiercer() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "starpiercer");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550253.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Starpiercer"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A shard of her ceiling, ground"),
               Component.literal("§7down to something with a hilt."),
               Component.literal("§7Every third hit fires a piercing star."),
               Component.literal("§8Dropped by the Starbound Magister.")
            )
         )
      );
      return stack;
   }

   public static boolean isStarpiercer(ItemStack stack) {
      return "starpiercer".equals(typeOf(stack));
   }

   /**
    * The Astral Mantle - her legendary chestplate. Worn it grants slow falling
    * and a chance to blink backward out of a heavy blow; sneak-right-click steps
    * you forward a short distance.
    */
   public static ItemStack astralMantle() {
      ItemStack stack = new ItemStack(Items.NETHERITE_CHESTPLATE);
      setType(stack, "astral_mantle");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550254.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Astral Mantle"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A cloak cut from the night sky."),
               Component.literal("§7Wear it for §bSlow Falling§7 and a"),
               Component.literal("§7chance to blink away from heavy hits."),
               Component.literal("§8Dropped by the Starbound Magister.")
            )
         )
      );
      return stack;
   }

   public static boolean isAstralMantle(ItemStack stack) {
      return "astral_mantle".equals(typeOf(stack));
   }

   /**
    * The Magister's Codex - her legendary spellbook. Right-click casts the chosen
    * spell, sneak-right-click cycles through Star Bolt, Gravity and Meteor.
    */
   public static ItemStack magistersCodex() {
      ItemStack stack = new ItemStack(Items.BOOK);
      setType(stack, "magisters_codex");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550255.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l✨ Magister's Codex"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Her own spellbook, with three pages"),
               Component.literal("§7left unburned."),
               Component.literal("§7Right-click to cast -"),
               Component.literal("§7sneak-right-click to turn the page."),
               Component.literal("§8Dropped by the Starbound Magister.")
            )
         )
      );
      return stack;
   }

   public static boolean isMagistersCodex(ItemStack stack) {
      return "magisters_codex".equals(typeOf(stack));
   }

   /** Her three legendaries: the loot box pool and the Essence-upgraded set. */
   public static boolean isMagisterLegendary(ItemStack stack) {
      return isStarpiercer(stack) || isAstralMantle(stack) || isMagistersCodex(stack);
   }

   // -------------------------------------------------------------- Void Shaper

   /**
    * The Void Anchor - the Void Shaper's summon. His name is his promise: he
    * fights with the ground itself, so the thing that calls him is a hook.
    */
   public static ItemStack voidAnchor() {
      ItemStack stack = new ItemStack(Items.ENDER_EYE);
      setType(stack, "void_anchor");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550260.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Void Anchor"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7It has been dragged through stone."),
               Component.literal("§7Right-click to throw it - the §5Void"),
               Component.literal("§5Shaper§7 follows it up."),
               Component.literal("§8Summons The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isVoidAnchor(ItemStack stack) {
      return "void_anchor".equals(typeOf(stack));
   }

   public static ItemStack voidshaperLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "voidshaper_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550266.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Void Shaper Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isVoidshaperLootBox(ItemStack stack) {
      return "voidshaper_loot_box".equals(typeOf(stack));
   }

   public static ItemStack colossusTrophy() {
      ItemStack stack = new ItemStack(Items.END_CRYSTAL);
      setType(stack, "colossus_trophy");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550261.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Void Shaper's Trophy"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A block he never got to throw."),
               Component.literal("§7It is still trying to move."),
               Component.literal("§8Dropped by The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isColossusTrophy(ItemStack stack) {
      return "colossus_trophy".equals(typeOf(stack));
   }

   /** Voidsteel Scrap - the Void Shaper's forge material. */
   public static ItemStack voidsteelScrap() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SCRAP);
      setType(stack, "voidsteel_scrap");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550262.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Voidsteel Scrap"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A forge material - NOT a metal."),
               Component.literal("§7Use it in the Item Forge to upgrade"),
               Component.literal("§7one of his three legendary items."),
               Component.literal("§8Dropped by The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isVoidsteelScrap(ItemStack stack) {
      return "voidsteel_scrap".equals(typeOf(stack));
   }

   /**
    * The Void Reaver - his legendary weapon. Every third landed hit rips a block
    * out of the world and hurls it (see {@code VoidShaperGear}).
    */
   public static ItemStack voidReaver() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "void_reaver");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550263.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Void Reaver"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A blade with a hunger for masonry."),
               Component.literal("§7Every third hit tears a block out"),
               Component.literal("§7of the world and throws it."),
               Component.literal("§8Dropped by The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isVoidReaver(ItemStack stack) {
      return "void_reaver".equals(typeOf(stack));
   }

   /** The Colossus Plate - his legendary chestplate, plated against knockback. */
   public static ItemStack colossusPlate() {
      ItemStack stack = new ItemStack(Items.NETHERITE_CHESTPLATE);
      setType(stack, "colossus_plate");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550264.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Colossus Plate"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Blocks, hammered flat and worn."),
               Component.literal("§7Heavy, and it does not care about"),
               Component.literal("§7being pushed."),
               Component.literal("§8Dropped by The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isColossusPlate(ItemStack stack) {
      return "colossus_plate".equals(typeOf(stack));
   }

   /**
    * The Shaping Sigil - his legendary trinket. Right-click marks the block you
    * are looking at; it is ripped out and hurled at whatever is in front of you.
    */
   public static ItemStack shapingSigil() {
      ItemStack stack = new ItemStack(Items.ECHO_SHARD);
      setType(stack, "shaping_sigil");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550265.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟯 Shaping Sigil"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7His grip, cast in miniature."),
               Component.literal("§7Right-click the ground to rip up"),
               Component.literal("§7that block and hurl it."),
               Component.literal("§8Dropped by The Void Shaper.")
            )
         )
      );
      return stack;
   }

   public static boolean isShapingSigil(ItemStack stack) {
      return "shaping_sigil".equals(typeOf(stack));
   }

   public static boolean isVoidshaperLegendary(ItemStack stack) {
      return isVoidReaver(stack) || isColossusPlate(stack) || isShapingSigil(stack);
   }

   // --------------------------------------------------------------- Puppeteer

   public static final String TYPE_MARIONETTE = "wooden_marionette";
   public static final String TYPE_PUPPET_LOOT = "puppeteer_loot_box";
   public static final String TYPE_PUPPETEERS_MASK = "puppeteers_mask";
   public static final String TYPE_MARIONETTE_STRINGS = "marionette_strings";
   public static final String TYPE_EMPTY_MASK = "empty_mask";

   /**
    * The wooden marionette - his summon.
    *
    * <p>A little wooden man on four strings, jointed at the knees. Hanging it up is
    * what calls him: the strings it is hanging from are already his, and it is not
    * pleased that somebody else is holding them.
    */
   public static ItemStack woodenMarionette() {
      ItemStack stack = new ItemStack(Items.ARMOR_STAND);
      setType(stack, TYPE_MARIONETTE);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      setModel(stack, MARIONETTE_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🎭 Wooden Marionette"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A little wooden man, jointed at the"),
               Component.literal("§7knees, hanging from four strings that"),
               Component.literal("§7go up further than the room does."),
               Component.literal("§7Right-click to hang it up."),
               Component.literal("§8Summons §5The Puppeteer§8."),
               Component.literal("§8Not tradeable, not placeable.")
            )
         )
      );
      return stack;
   }

   public static boolean isWoodenMarionette(ItemStack stack) {
      return TYPE_MARIONETTE.equals(typeOf(stack));
   }

   /**
    * Every item that calls a raid boss, by type - one list, in the order the bosses appear
    * in {@code /raidboss help}.
    *
    * <p>It exists to be audited: the self-test insists that each of these is craftable and
    * that the recipe which ships is the one its page draws. The Wooden Marionette is why.
    * Its page has always drawn a crafting grid - eight string around a carved pumpkin - and
    * no recipe for it shipped at all, so the page described a craft that did nothing, and a
    * boss nobody could summon without being handed the item. Nothing in the build noticed,
    * because a boss summon is not a thing any single file owns: the page is in one class,
    * the item in another and the recipe in a third, so "does this boss actually work" had
    * no place to be asked.
    */
   public static List<String> bossSummonTypes() {
      return List.of(
         TYPE_RAID_TOKEN,
         TYPE_SLIME_TOKEN,
         TYPE_STONE_GOLEM_TOKEN,
         TYPE_MINDBINDER_EYE,
         TYPE_SNOW_QUEEN_TOKEN,
         TYPE_SCULK_MEDALLION,
         "space_time_rift",
         "scarlet_blood",
         "clockwork_core",
         "astral_compass",
         "void_anchor",
         "sovereigns_crown",
         TYPE_MARIONETTE
      );
   }

   public static ItemStack puppeteerLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, TYPE_PUPPET_LOOT);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      setModel(stack, PUPPETEER_BOX_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🎭 Puppeteer Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by The Puppeteer.")
            )
         )
      );
      return stack;
   }

   public static boolean isPuppeteerLootBox(ItemStack stack) {
      return TYPE_PUPPET_LOOT.equals(typeOf(stack));
   }

   /**
    * The Puppeteer's Mask - his helmet legendary.
    *
    * <p>Porcelain over wood, with the smile painted on. Worn, it makes killing an
    * enemy occasionally leave a puppet of them standing for twenty seconds, fighting
    * whoever is left.
    */
   public static ItemStack puppeteersMask() {
      ItemStack stack = new ItemStack(Items.CARVED_PUMPKIN);
      setType(stack, TYPE_PUPPETEERS_MASK);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      setModel(stack, PUPPETEERS_MASK_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🎭 Puppeteer's Mask"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Painted porcelain over wood. The smile"),
               Component.literal("§7is the only part that is carved."),
               Component.literal("§7Worn: killing an enemy has a chance to"),
               Component.literal("§7raise a puppet of them for §f20s§7."),
               Component.literal("§8Dropped by The Puppeteer."),
               Component.literal("§8Part of the Puppeteer set.")
            )
         )
      );
      return stack;
   }

   public static boolean isPuppeteersMask(ItemStack stack) {
      return TYPE_PUPPETEERS_MASK.equals(typeOf(stack));
   }

   /**
    * Marionette Strings - his utility legendary.
    *
    * <p>A bundle of strings that is warm to the touch and never stops moving.
    * Right-click an entity to tie a string to it; right-click the air to yank it
    * toward you. A string snaps if its target gets far enough away, which is the
    * whole counterplay.
    */
   public static ItemStack marionetteStrings() {
      ItemStack stack = new ItemStack(Items.STRING);
      setType(stack, TYPE_MARIONETTE_STRINGS);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      setModel(stack, MARIONETTE_STRINGS_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🎭 Marionette Strings"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Warm, and they never stop moving."),
               Component.literal("§7Right-click an entity to tie a string"),
               Component.literal("§7to it. Right-click the air to §fpull§7."),
               Component.literal("§7It snaps if they get far enough away."),
               Component.literal("§8Dropped by The Puppeteer."),
               Component.literal("§8Part of the Puppeteer set.")
            )
         )
      );
      return stack;
   }

   public static boolean isMarionetteStrings(ItemStack stack) {
      return TYPE_MARIONETTE_STRINGS.equals(typeOf(stack));
   }

   /**
    * The Empty Mask - his other helmet, and the creepy one.
    *
    * <p>No face at all. Worn, your name is muddled in the player list, hostile mobs
    * are slower to notice you, and the swing that would have killed you leaves a
    * puppet standing where you were instead. The cooldown is long on purpose: it is
    * an escape, not a shield.
    */
   public static ItemStack emptyMask() {
      ItemStack stack = new ItemStack(Items.SKELETON_SKULL);
      setType(stack, TYPE_EMPTY_MASK);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      setModel(stack, EMPTY_MASK_MODEL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l🎭 The Empty Mask"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7There is no face on the inside of it"),
               Component.literal("§7either."),
               Component.literal("§7Worn: your name is harder to read, and"),
               Component.literal("§7mobs notice you later."),
               Component.literal("§7Lethal damage: a puppet takes your place"),
               Component.literal("§7and you get a window to leave."),
               Component.literal("§8Dropped by The Puppeteer."),
               Component.literal("§8Part of the Puppeteer set.")
            )
         )
      );
      return stack;
   }

   public static boolean isEmptyMask(ItemStack stack) {
      return TYPE_EMPTY_MASK.equals(typeOf(stack));
   }

   /** True for his three legendaries - the loot box family pool. */
   public static boolean isPuppeteerLegendary(ItemStack stack) {
      return isPuppeteersMask(stack) || isMarionetteStrings(stack) || isEmptyMask(stack);
   }

   // ---------------------------------------------------------- Emerald Sovereign

   /** The Sovereign's Crown - the summon. A crown is a claim on a place. */
   public static ItemStack sovereignsCrown() {
      ItemStack stack = new ItemStack(Items.GOLDEN_HELMET);
      setType(stack, "sovereigns_crown");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550270.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Sovereign's Crown"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Far too small for the head it"),
               Component.literal("§7was made for."),
               Component.literal("§7Right-click to put it on and he"),
               Component.literal("§7will come to take it back."),
               Component.literal("§8Summons The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isSovereignsCrown(ItemStack stack) {
      return "sovereigns_crown".equals(typeOf(stack));
   }

   public static ItemStack sovereignLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "sovereign_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550276.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Sovereign Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isSovereignLootBox(ItemStack stack) {
      return "sovereign_loot_box".equals(typeOf(stack));
   }

   public static ItemStack sovereignTrophy() {
      ItemStack stack = new ItemStack(Items.EMERALD_BLOCK);
      setType(stack, "sovereign_trophy");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550271.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Sovereign's Trophy"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7His throne, one block of it."),
               Component.literal("§7Nobody is going to sit in it."),
               Component.literal("§8Dropped by The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isSovereignTrophy(ItemStack stack) {
      return "sovereign_trophy".equals(typeOf(stack));
   }

   /** Royal Tribute - the Sovereign's forge material. */
   public static ItemStack royalTribute() {
      ItemStack stack = new ItemStack(Items.EMERALD);
      setType(stack, "royal_tribute");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550272.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Royal Tribute"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A forge material - NOT currency."),
               Component.literal("§7Use it in the Item Forge to upgrade"),
               Component.literal("§7one of his three legendary items."),
               Component.literal("§8Dropped by The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isRoyalTribute(ItemStack stack) {
      return "royal_tribute".equals(typeOf(stack));
   }

   /**
    * The Royal Contract - his legendary summoning paper. Right-click signs it and
    * a Royal Guard answers, fighting for you until the paper burns out.
    */
   public static ItemStack royalContract() {
      ItemStack stack = new ItemStack(Items.PAPER);
      setType(stack, "royal_contract");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550273.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Royal Contract"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Signed at the bottom in a hand"),
               Component.literal("§7that is not yours."),
               Component.literal("§7Right-click to summon a Royal Guard."),
               Component.literal("§8Dropped by The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isRoyalContract(ItemStack stack) {
      return "royal_contract".equals(typeOf(stack));
   }

   /**
    * The Sovereign's Bell - his legendary offhand. Ring it to stagger every
    * hostile mob nearby and stir the villagers into royal subjects.
    */
   public static ItemStack sovereignsBell() {
      ItemStack stack = new ItemStack(Items.BELL);
      setType(stack, "sovereigns_bell");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550274.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Sovereign's Bell"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7It rings for a kingdom that is"),
               Component.literal("§7one man wide."),
               Component.literal("§7Right-click to stun nearby enemies and"),
               Component.literal("§7empower nearby villagers."),
               Component.literal("§8Dropped by The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isSovereignsBell(ItemStack stack) {
      return "sovereigns_bell".equals(typeOf(stack));
   }

   /**
    * The Emerald Seal - his legendary trinket. Carried, trades get cheaper and
    * hostile kills occasionally mint an emerald; right-click spends five of them
    * on a short Royal Tribute buff.
    */
   public static ItemStack emeraldSeal() {
      ItemStack stack = new ItemStack(Items.FIREWORK_STAR);
      setType(stack, "emerald_seal");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550275.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l👑 Emerald Seal"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A stamp for a treasury nobody"),
               Component.literal("§7has audited in years."),
               Component.literal("§7Carried: cheaper trades, and emeralds"),
               Component.literal("§7from hostile kills."),
               Component.literal("§7Right-click: spend 5 emeralds for"),
               Component.literal("§7Royal Tribute (Strength + Resistance)."),
               Component.literal("§8Dropped by The Emerald Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isEmeraldSeal(ItemStack stack) {
      return "emerald_seal".equals(typeOf(stack));
   }

   public static boolean isSovereignLegendary(ItemStack stack) {
      return isRoyalContract(stack) || isSovereignsBell(stack) || isEmeraldSeal(stack);
   }

   public static ItemStack backpack(int tier) {
      tier = Math.max(1, Math.min(5, tier));
      ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
      setType(stack, "backpack");
      setBackpackTier(stack, tier);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lBackpack §7[" + backpackTierName(tier) + "]"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7A portable storage sack."));
      lore.add(Component.literal("§7Right-click to open §f" + tier * 9 + "§7 slots of storage."));
      if (tier < 5) {
         lore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
         lore.add(Component.literal("§7next material's ingots (" + backpackUpgradeIngotName(tier) + ")."));
      } else {
         lore.add(Component.literal("§7This backpack is maxed at Netherite."));
      }

      lore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      PropertyMap props = new PropertyMap(ImmutableMultimap.of()) {
      private final Multimap<String, Property> backing = LinkedHashMultimap.create();
      protected Multimap<String, Property> delegate() { return this.backing; }
   };
      props.put(
         "textures",
         new Property(
            "textures",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM1MWU1MDU5ODk4MzhlMjcyODdlN2FmYmM3Zjk3ZTc5NmNhYjVmMzU5OGE3NjE2MGMxMzFjOTQwZDBjNSJ9fX0="
         )
      );
      GameProfile gp = new GameProfile(BACKPACK_SKIN_ID, "Backpack", props);
      stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
      return stack;
   }

   public static boolean isBackpack(ItemStack stack) {
      return "backpack".equals(typeOf(stack));
   }

   public static boolean isEnderBackpack(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !isBackpack(stack)) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean("ff_ender").orElse(false);
   }

   public static void setEnderBackpack(ItemStack stack, boolean ender) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         if (ender) {
            tag.putBoolean("ff_ender", true);
         } else {
            tag.remove("ff_ender");
         }
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   /** Whether this backpack has a Crafting Table fused in (built-in Workbench
    *  button in the toolbar). Set by the Item Forge: backpack + crafting table. */
   public static boolean backpackHasWorkbench(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !isBackpack(stack)) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean("ff_workbench").orElse(false);
   }

   public static void setBackpackWorkbench(ItemStack stack, boolean workbench) {
      setBackpackFlag(stack, "ff_workbench", workbench);
   }

   /** Whether this backpack has a Jukebox fused in (Jukebox button in the
    *  toolbar). Set by the Item Forge: backpack + jukebox. */
   public static boolean backpackHasJukebox(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !isBackpack(stack)) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean("ff_jukebox").orElse(false);
   }

   public static void setBackpackJukebox(ItemStack stack, boolean jukebox) {
      setBackpackFlag(stack, "ff_jukebox", jukebox);
   }

   private static void setBackpackFlag(ItemStack stack, String key, boolean value) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         if (value) {
            tag.putBoolean(key, true);
         } else {
            tag.remove(key);
         }
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static ItemStack migrateBackpack(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !isBackpack(stack)) {
         return stack;
      }

      if (stack.is(Items.PLAYER_HEAD)) {
         return stack;
      }

      int count = stack.getCount();
      ItemStack head = new ItemStack(Items.PLAYER_HEAD);
      CustomData cd = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (cd != null) {
         head.set(DataComponents.CUSTOM_DATA, cd);
      }

      Component name = (Component)stack.get(DataComponents.CUSTOM_NAME);
      if (name != null) {
         head.set(DataComponents.CUSTOM_NAME, name);
      }

      ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
      if (lore != null) {
         head.set(DataComponents.LORE, lore);
      }

      head.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      head.set(DataComponents.MAX_STACK_SIZE, 1);
      head.setCount(count);
      PropertyMap props = new PropertyMap(ImmutableMultimap.of()) {
      private final Multimap<String, Property> backing = LinkedHashMultimap.create();
      protected Multimap<String, Property> delegate() { return this.backing; }
   };
      props.put(
         "textures",
         new Property(
            "textures",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM1MWU1MDU5ODk4MzhlMjcyODdlN2FmYmM3Zjk3ZTc5NmNhYjVmMzU5OGE3NjE2MGMxMzFjOTQwZDBjNSJ9fX0="
         )
      );
      GameProfile gp = new GameProfile(BACKPACK_SKIN_ID, "Backpack", props);
      head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
      return head;
   }

   public static boolean isSculkLootBox(ItemStack stack) {
      return "sculk_loot_box".equals(typeOf(stack));
   }

   public static boolean isCannonRod(ItemStack stack) {
      if (stack != null && !stack.isEmpty() && stack.is(Items.FISHING_ROD)) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         if (data != null && !data.isEmpty()) {
            CompoundTag tag = data.copyTag();
            return tag.contains("Orbital_Cannon") || tag.contains("wolf_cannon");
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static boolean isSculkMageStaff(ItemStack stack) {
      return "sculk_mage_staff".equals(typeOf(stack));
   }

   public static boolean isSculkSensorLeggings(ItemStack stack) {
      return "sculk_sensor_leggings".equals(typeOf(stack));
   }

   public static boolean isWardensCall(ItemStack stack) {
      return "wardens_call".equals(typeOf(stack));
   }

   public static boolean isSculkOrb(ItemStack stack) {
      return "sculk_orb".equals(typeOf(stack));
   }

   public static boolean isSculkEssence(ItemStack stack) {
      return "sculk_essence".equals(typeOf(stack));
   }

   public static boolean isSculkLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "sculk_mage_staff".equals(type) || "sculk_sensor_leggings".equals(type) || "wardens_call".equals(type);
   }

   public static int backpackTier(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 1 : Math.max(1, Math.min(5, data.copyTag().getIntOr("ff_bptier", 1)));
   }

   public static void setBackpackTier(ItemStack stack, int tier) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_bptier", Math.max(1, Math.min(5, tier)));
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static String backpackTierName(int tier) {
      return switch (tier) {
         case 1 -> "Copper";
         case 2 -> "Gold";
         case 3 -> "Iron";
         case 4 -> "Diamond";
         default -> "Netherite";
      };
   }

   public static Item backpackUpgradeIngot(int tier) {
      return switch (tier) {
         case 1 -> Items.GOLD_INGOT;
         case 2 -> Items.IRON_INGOT;
         case 3 -> Items.DIAMOND;
         case 4 -> Items.NETHERITE_INGOT;
         default -> null;
      };
   }

   public static String backpackUpgradeIngotName(int tier) {
      return switch (tier) {
         case 1 -> "Gold Ingots";
         case 2 -> "Iron Ingots";
         case 3 -> "Diamonds";
         case 4 -> "Netherite Ingots";
         default -> "- maxed -";
      };
   }

   public static ItemStack bundle(int tier) {
      tier = Math.max(1, Math.min(3, tier));
      ItemStack stack = new ItemStack(Items.BUNDLE);
      setType(stack, "bundle");
      setBundleTier(stack, tier);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lBundle §7[" + bundleTierName(tier) + "]"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7A stitched carry-sack - right-click to open §f" + bundleSlots(tier) + "§7 slots."));
      if (tier < 3) {
         lore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
         lore.add(Component.literal("§7next material's ingots (" + bundleUpgradeIngotName(tier) + ")."));
      } else {
         lore.add(Component.literal("§7This bundle is maxed at Netherite."));
      }

      lore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   public static boolean isBundle(ItemStack stack) {
      return "bundle".equals(typeOf(stack));
   }

   public static int bundleTier(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 1 : Math.max(1, Math.min(3, data.copyTag().getIntOr("ff_bdtier", 1)));
   }

   public static void setBundleTier(ItemStack stack, int tier) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_bdtier", Math.max(1, Math.min(3, tier)));
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static int bundleSlots(int tier) {
      return switch (tier) {
         case 1 -> 9;
         case 2 -> 27;
         default -> 54;
      };
   }

   public static String bundleTierName(int tier) {
      return switch (tier) {
         case 1 -> "Leather";
         case 2 -> "Iron";
         default -> "Netherite";
      };
   }

   public static Item bundleUpgradeIngot(int tier) {
      return switch (tier) {
         case 1 -> Items.IRON_INGOT;
         case 2 -> Items.NETHERITE_INGOT;
         default -> null;
      };
   }

   public static String bundleUpgradeIngotName(int tier) {
      return switch (tier) {
         case 1 -> "Iron Ingots";
         case 2 -> "Netherite Ingots";
         default -> "- maxed -";
      };
   }

   public static ItemStack enderPouch() {
      ItemStack stack = new ItemStack(Items.ENDER_CHEST);
      setType(stack, "ender_pouch");
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lEnder Pouch"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A little pocket that hums with the void."),
               Component.literal("§7Item Forge: put it in the material slot with"),
               Component.literal("§7a §6Backpack§7 to link the pack to your §5ender"),
               Component.literal("§7chest§7 - sneak-right-click the pack to open it."),
               Component.literal("§8Consumed by the forge")
            )
         )
      );
      return stack;
   }

   public static boolean isEnderPouch(ItemStack stack) {
      return "ender_pouch".equals(typeOf(stack));
   }

   public static ItemStack sculkLootBox() {
      ItemStack stack = new ItemStack(Items.ECHO_SHARD);
      setType(stack, "sculk_loot_box");
      stack.set(DataComponents.MAX_STACK_SIZE, 64);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550050.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lElder Warden Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(Component.literal("§7Right-click to spin for ancient-city loot."), Component.literal("§7Shift-right-click to see the prize list."))
         )
      );
      return stack;
   }

   public static ItemStack sculkMageStaff() {
      ItemStack stack = new ItemStack(Items.BLAZE_ROD);
      setType(stack, "sculk_mage_staff");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550051.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lSculk Mage Staff"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A staff grown from the Elder Warden's own sculk."),
               Component.literal("§bSonic Charge§7 - a warden sonic blast in a line."),
               Component.literal("§bSculk Bloom§7 - sculk wraps what it hits and drains"),
               Component.literal("§7their life to you. Sneak-right-click to swap modes.")
            )
         )
      );
      return stack;
   }

   public static ItemStack sculkSensorLeggings() {
      ItemStack stack = new ItemStack(Items.IRON_LEGGINGS);
      setType(stack, "sculk_sensor_leggings");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550052.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lSculk Sensor Leggings"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A living sensor woven into the leggings."),
               Component.literal("§bVibration Sense§7 - moving mobs in a huge radius"),
               Component.literal("§7glow through walls - for you alone."),
               Component.literal("§7Always on while worn.")
            )
         )
      );
      return stack;
   }

   public static ItemStack wardensCall() {
      ItemStack stack = new ItemStack(Items.GOAT_HORN);
      setType(stack, "wardens_call");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550053.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lWarden's Call"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A horn carved from a sculk shrieker's rib."),
               Component.literal("§bShriek§7 - darkness for enemies nearby, and mobs"),
               Component.literal("§7that hear it panic and flee. You are spared.")
            )
         )
      );
      return stack;
   }

   public static ItemStack sculkOrb(String ownerName, UUID ownerUuid, List<ItemStack> items) {
      ItemStack stack = new ItemStack(Items.ECHO_SHARD);
      setType(stack, "sculk_orb");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550054.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lSculk Orb"));
      List<Component> lines = new ArrayList<>();
      lines.add(Component.literal("§7The Elder Warden's stolen hoard, returned."));
      if (ownerName != null) {
         lines.add(Component.literal("§7Right-click to return §f" + ownerName + "§7's items."));
      }

      lines.add(Component.literal("§8If the owner is offline the loot moves to /claim loot."));
      stack.set(DataComponents.LORE, new ItemLore(lines));
      CompoundTag tag = new CompoundTag();
      if (ownerUuid != null) {
         tag.putString("ff_orb_owner", ownerUuid.toString());
      }

      if (ownerName != null) {
         tag.putString("ff_orb_owner_name", ownerName);
      }

      ListTag list = new ListTag();
      RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.EMPTY);

      for (ItemStack it : items) {
         if (it != null && !it.isEmpty()) {
            try {
               ItemStack.OPTIONAL_CODEC.encodeStart(ops, it).result().ifPresent(list::add);
            } catch (Exception var11) {
            }
         }
      }

      tag.put("ff_orb_items", list);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return stack;
   }

   public static UUID sculkOrbOwner(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }

      String s = data.copyTag().getString("ff_orb_owner").orElse("");
      if (s.isEmpty()) {
         return null;
      }

      try {
         return UUID.fromString(s);
      } catch (Exception ignored) {
         return null;
      }
   }

   public static List<ItemStack> sculkOrbItems(ItemStack stack) {
      List<ItemStack> out = new ArrayList<>();
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return out;
      }

      ListTag list = data.copyTag().getListOrEmpty("ff_orb_items");
      RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.EMPTY);

      for (int i = 0; i < list.size(); i++) {
         try {
            Tag el = list.get(i);
            if (el instanceof CompoundTag) {
               ItemStack it = ItemStack.OPTIONAL_CODEC.parse(ops, el).result().orElse(ItemStack.EMPTY);
               if (!it.isEmpty()) {
                  out.add(it);
               }
            }
         } catch (Exception var8) {
         }
      }

      return out;
   }

   public static ItemStack sculkEssence() {
      ItemStack stack = new ItemStack(Items.ECHO_SHARD);
      setType(stack, "sculk_essence");
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550055.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§lSculk Essence"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(Component.literal("§7A shard of the Elder Warden's heart."), Component.literal("§7Upgrades sculk legendaries in the Item Forge."))
         )
      );
      return stack;
   }

   public static boolean isRune(ItemStack stack) {
      String t = typeOf(stack);
      return TYPE_RUNE_HASTE.equals(t) || TYPE_RUNE_FLAME.equals(t) || TYPE_RUNE_FORTUNE.equals(t)
         || TYPE_RUNE_SWIFTNESS.equals(t) || TYPE_RUNE_FROST.equals(t)
         || TYPE_RUNE_REACH.equals(t) || TYPE_RUNE_LIFESTEAL.equals(t)
         || TYPE_RUNE_WARDING.equals(t) || TYPE_RUNE_FORTITUDE.equals(t);
   }

   public static boolean isRuneHaste(ItemStack stack) {
      return TYPE_RUNE_HASTE.equals(typeOf(stack));
   }

   public static boolean isRuneFlame(ItemStack stack) {
      return TYPE_RUNE_FLAME.equals(typeOf(stack));
   }

   public static boolean isRuneFortune(ItemStack stack) {
      return TYPE_RUNE_FORTUNE.equals(typeOf(stack));
   }

   public static boolean isRuneSwiftness(ItemStack stack) {
      return TYPE_RUNE_SWIFTNESS.equals(typeOf(stack));
   }

   public static boolean isRuneFrost(ItemStack stack) {
      return TYPE_RUNE_FROST.equals(typeOf(stack));
   }

   public static boolean isRuneReach(ItemStack stack) {
      return TYPE_RUNE_REACH.equals(typeOf(stack));
   }

   public static boolean isRuneLifesteal(ItemStack stack) {
      return TYPE_RUNE_LIFESTEAL.equals(typeOf(stack));
   }

   public static boolean isRuneWarding(ItemStack stack) {
      return TYPE_RUNE_WARDING.equals(typeOf(stack));
   }

   public static boolean isRuneFortitude(ItemStack stack) {
      return TYPE_RUNE_FORTITUDE.equals(typeOf(stack));
   }

   public static boolean hasRune(ItemStack weapon, String rune) {
      if (weapon == null || weapon.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)weapon.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean("ff_rune_" + rune).orElse(false);
   }

   public static void applyRune(ItemStack weapon, String rune) {
      if (weapon == null || weapon.isEmpty()) {
         return;
      }
      CustomData data = (CustomData)weapon.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag tag = data.copyTag();
      tag.putBoolean("ff_rune_" + rune, true);
      weapon.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      if ("haste".equals(rune) || "swiftness".equals(rune) || "reach".equals(rune)) {
         ItemAttributeModifiers existing = (ItemAttributeModifiers)weapon.get(DataComponents.ATTRIBUTE_MODIFIERS);
         Builder builder = ItemAttributeModifiers.builder();
         if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
               builder.add(e.attribute(), e.modifier(), e.slot());
            }
         }
         if ("haste".equals(rune)) {
            builder.add(
               Attributes.ATTACK_SPEED,
               new AttributeModifier(FortuneFavorsMod.id("rune_haste"), 0.08, Operation.ADD_VALUE),
               EquipmentSlotGroup.MAINHAND
            );
         } else if ("swiftness".equals(rune)) {
            builder.add(
               Attributes.MOVEMENT_SPEED,
               new AttributeModifier(FortuneFavorsMod.id("rune_swiftness"), 0.005, Operation.ADD_VALUE),
               EquipmentSlotGroup.MAINHAND
            );
         } else {
            builder.add(
               Attributes.ENTITY_INTERACTION_RANGE,
               new AttributeModifier(FortuneFavorsMod.id("rune_reach"), 1.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.MAINHAND
            );
         }
         weapon.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("warding".equals(rune) || "fortitude".equals(rune)) {
         // Armor runes: attributes keyed to the armor slot so they apply while worn.
         EquipmentSlotGroup slot = armorSlotGroup(weapon);
         ItemAttributeModifiers existing = (ItemAttributeModifiers)weapon.get(DataComponents.ATTRIBUTE_MODIFIERS);
         Builder builder = ItemAttributeModifiers.builder();
         if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
               builder.add(e.attribute(), e.modifier(), e.slot());
            }
         }
         if ("warding".equals(rune)) {
            builder.add(
               Attributes.ARMOR,
               new AttributeModifier(FortuneFavorsMod.id("rune_warding_armor"), 3.0, Operation.ADD_VALUE),
               slot
            );
            builder.add(
               Attributes.ARMOR_TOUGHNESS,
               new AttributeModifier(FortuneFavorsMod.id("rune_warding_tough"), 1.0, Operation.ADD_VALUE),
               slot
            );
         } else {
            builder.add(
               Attributes.MAX_HEALTH,
               new AttributeModifier(FortuneFavorsMod.id("rune_fortitude_health"), 4.0, Operation.ADD_VALUE),
               slot
            );
         }
         weapon.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      }
   }

   /** The equipment slot group a wearable armor piece occupies (for armor rune
    *  attribute modifiers), falling back to CHEST for anything unexpected. */
   public static EquipmentSlotGroup armorSlotGroup(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         net.minecraft.world.item.Item item = stack.getItem();
         if (item.builtInRegistryHolder().is(ItemTags.HEAD_ARMOR)) {
            return EquipmentSlotGroup.HEAD;
         }
         if (item.builtInRegistryHolder().is(ItemTags.CHEST_ARMOR)) {
            return EquipmentSlotGroup.CHEST;
         }
         if (item.builtInRegistryHolder().is(ItemTags.LEG_ARMOR)) {
            return EquipmentSlotGroup.LEGS;
         }
         if (item.builtInRegistryHolder().is(ItemTags.FOOT_ARMOR)) {
            return EquipmentSlotGroup.FEET;
         }
      }
      return EquipmentSlotGroup.CHEST;
   }

   /** Removes a socketed rune (undo path when consumption fails). Does not
    *  rebuild attribute modifiers - only used in the rare race where the rune
    *  stack disappeared between check and consume. */
   public static void removeRune(ItemStack weapon, String rune) {
      if (weapon == null || weapon.isEmpty()) {
         return;
      }
      CustomData data = (CustomData)weapon.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag tag = data.copyTag();
      tag.putBoolean("ff_rune_" + rune, false);
      weapon.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   public static ItemStack runeOfHaste() {
      ItemStack stack = new ItemStack(Items.FEATHER);
      setType(stack, TYPE_RUNE_HASTE);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§e§l⚡ Rune of Haste"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7+8% attack speed while held."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfFlame() {
      ItemStack stack = new ItemStack(Items.BLAZE_POWDER);
      setType(stack, TYPE_RUNE_FLAME);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l🔥 Rune of Flame"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§725% chance to set enemies on fire on hit."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfFortune() {
      ItemStack stack = new ItemStack(Items.EMERALD);
      setType(stack, TYPE_RUNE_FORTUNE);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l💎 Rune of Fortune"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§720% chance for $100-500 + XP per kill."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfSwiftness() {
      ItemStack stack = new ItemStack(Items.SUGAR);
      setType(stack, TYPE_RUNE_SWIFTNESS);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l💨 Rune of Swiftness"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7+5% movement speed while held."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfFrost() {
      ItemStack stack = new ItemStack(Items.SNOWBALL);
      setType(stack, TYPE_RUNE_FROST);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l❄ Rune of Frost"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§725% chance to slow enemies on hit."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfReach() {
      ItemStack stack = new ItemStack(Items.ENDER_PEARL);
      setType(stack, TYPE_RUNE_REACH);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§2§l➤ Rune of Reach"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7+1 block melee reach while held."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfLifesteal() {
      ItemStack stack = new ItemStack(Items.GHAST_TEAR);
      setType(stack, TYPE_RUNE_LIFESTEAL);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l♥ Rune of Lifesteal"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Heal 1 heart on every hit."),
               Component.literal("§7One of each rune fits per weapon -"),
               Component.literal("§7they all stack together."),
               Component.literal("§7Hold a sword or axe in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfWarding() {
      ItemStack stack = new ItemStack(Items.SHIELD);
      setType(stack, TYPE_RUNE_WARDING);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l🛡 Rune of Warding"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7+3 armor and +1 toughness while worn."),
               Component.literal("§7Armor runes fit armor pieces -"),
               Component.literal("§7one of each fits per piece."),
               Component.literal("§7Hold the armor piece in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack runeOfFortitude() {
      ItemStack stack = new ItemStack(Items.GOLDEN_APPLE);
      setType(stack, TYPE_RUNE_FORTITUDE);
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l❤ Rune of Fortitude"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7+2 max hearts while worn."),
               Component.literal("§7Armor runes fit armor pieces -"),
               Component.literal("§7one of each fits per piece."),
               Component.literal("§7Hold the armor piece in one hand and"),
               Component.literal("§7this rune in the other, then right-click.")
            )
         )
      );
      return stack;
   }

   public static ItemStack sculkMedallion() {
      ItemStack stack = new ItemStack(Items.ECHO_SHARD);
      setType(stack, "sculk_medallion");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550040.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§lSculk Infused Medallion"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A dark medallion pulsing with sculk energy."),
               Component.literal("§7It hums with the promise of something"),
               Component.literal("§7terrible lurking beneath the deep."),
               Component.literal("§8Craft it: 4 echo shards, 1 sculk sensor,"),
               Component.literal("§8and 4 golden blocks"),
               Component.literal("§c§lRight-click to summon the Elder Warden!")
            )
         )
      );
      return stack;
   }

   public static boolean isSculkMedallion(ItemStack stack) {
      return "sculk_medallion".equals(typeOf(stack));
   }

   public static boolean isSnowLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "ice_staff".equals(type) || "frostbound_crown".equals(type) || "glacier_cloak".equals(type);
   }

   public static ItemStack distantMemoryShard() {
      ItemStack stack = new ItemStack(Items.ECHO_SHARD);
      setType(stack, "distant_memory_shard");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550060.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lThe Echoing Shard Of A Distant Memory"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A crystallized fragment of a world that"),
               Component.literal("§7no longer exists. The air around it"),
               Component.literal("§7hums with forgotten echoes."),
               Component.literal("§7Forge it with a §bDiamond Sword§7 in the"),
               Component.literal("§7Item Forge to create a weapon of the past."),
               Component.literal("§8Craft it: 4 echo shards + 1 sculk catalyst")
            )
         )
      );
      return stack;
   }

   public static ItemStack distantMemorySword() {
      ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
      setType(stack, "distant_memory_sword");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550061.0F));
      stack.set(DataComponents.MAX_DAMAGE, 3122);
      stack.set(DataComponents.DAMAGE, 0);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lDistant Memory"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A blade forged from the echo of a world"),
               Component.literal("§7long gone. It remembers the old ways:"),
               Component.literal("§7no cooldown, §b1.8 directional block§7 (front only,"),
               Component.literal("§7no sound, no arrow reflect), normal damage,"),
               Component.literal("§7no sweep, and twice the durability of diamond."),
               Component.literal("§7Shields are forbidden in its presence."),
               Component.literal("§8Fully enchantable · Hold right-click to block"),
               Component.literal("§8Forged in the Item Forge")
            )
         )
      );
      stack.set(
         DataComponents.ATTRIBUTE_MODIFIERS,
         ItemAttributeModifiers.builder()
            .add(
               Attributes.ATTACK_DAMAGE,
               new AttributeModifier(FortuneFavorsMod.id("distant_memory_damage"), 4.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.MAINHAND
            )
            .add(
               Attributes.ATTACK_SPEED,
               new AttributeModifier(FortuneFavorsMod.id("distant_memory_attack_speed"), 1021.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.MAINHAND
            )
            // It does not shove what it hits. A blade that swings with no cooldown and also
            // knocks its target back is a blade that decides where a fight happens, and this one
            // was already the fastest weapon in the mod - the shove was the part nobody asked
            // for. The attribute is the game's own knockback term, so a negative value takes the
            // whole of it away: the base shove, the sprint bonus and the Knockback enchantment
            // on top, because all three arrive through this one number. Vanilla clamps a
            // non-positive shove strength, so a target hit by this is not pulled towards the
            // attacker - it simply stands where it was hit.
            .add(
               Attributes.ATTACK_KNOCKBACK,
               new AttributeModifier(FortuneFavorsMod.id("distant_memory_knockback"), -1.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.MAINHAND
            )
            .build()
      );
      // Blocking never slows this blade down - full move speed and sprinting while blocking.
      stack.set(DataComponents.USE_EFFECTS, new UseEffects(true, false, 1.0F));
      // 26.2's enchanting table only offers options when the stack has the
      // enchantable component AND an (empty) enchantments component. Stamp both
      // so the blade is always enchantable regardless of the base item's defaults.
      ensureDistantMemoryEnchantable(stack);
      return stack;
   }

   public static boolean isDistantMemoryShard(ItemStack stack) {
      return "distant_memory_shard".equals(typeOf(stack));
   }

   public static boolean isDistantMemorySword(ItemStack stack) {
      return "distant_memory_sword".equals(typeOf(stack));
   }

   /**
    * The greatsword the Distant Memory Of A Forgotten Monarch stands up with.
    *
    * <p>He is a mirage - the hall, the guards and the treasure are all somebody else's room,
    * held in the world for as long as the event lasts and handed back block for block when it
    * ends. This is the one thing in the castle that was ever really there, which is why it is
    * the boss's own drop rather than a line in a chest: the sword comes off the body that was
    * swinging it, and the mirage cannot take it back.
    *
    * <p>One definition, in one place, on purpose. The blade he is armed with at the fourth beat
    * of his announcement and the blade that lands on the flagstones when he falls are the same
    * stack, so "what he drops" cannot drift away from "what he was holding" - the two used to
    * be separate pieces of code in separate files, which is exactly how a boss ends up dropping
    * something it never carried.
    */
   public static ItemStack lastRemembrance(HolderLookup.Provider access) {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "last_remembrance");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550295.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7§lLast Remembrance"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The greatsword of a court that is gone - taken"),
               Component.literal("§7off the hands of the thing that was still holding it."),
               Component.literal("§7Sharpness V · Fire Aspect II · Unbreaking III"),
               Component.literal("§8The Distant Memory carries one, and drops it once.")
            )
         )
      );
      enchant(access, stack, Enchantments.SHARPNESS, 5);
      enchant(access, stack, Enchantments.FIRE_ASPECT, 2);
      enchant(access, stack, Enchantments.UNBREAKING, 3);
      return stack;
   }

   public static boolean isLastRemembrance(ItemStack stack) {
      return "last_remembrance".equals(typeOf(stack));
   }

   /**
    * The Efficiency VII book the Distant Memory keeps on him - his other rare drop.
    *
    * <p>Seven, which the enchanting table will never write and the anvil will not place on its
    * own: vanilla clamps a book to the enchantment's own maximum when it merges, so an
    * Efficiency VII book slid into an ordinary anvil used to come out the far side as
    * Efficiency V with nothing to say for itself. The level is written here, on the book, and
    * {@link #overMaxLevelBook} is what tells the anvil to hand it to the mod's own merge -
    * which writes the number it was handed.
    *
    * <p>Minted once, here, so the book the monarch drops and the book the checks read are
    * literally the same stack definition.
    */
   public static ItemStack monarchsEfficiencyBook(HolderLookup.Provider access) {
      ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lEfficiency VII"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A memory of a mine that never ran out."),
               Component.literal("§7Seven is past what any table will write,"),
               Component.literal("§7and the anvil writes what it is handed."),
               Component.literal("§8Rare. The Distant Memory carries one, sometimes.")
            )
         )
      );
      enchant(access, stack, Enchantments.EFFICIENCY, 7);
      markMonarchKeeping(stack);
      return stack;
   }

   /**
    * True for a book carrying a level the enchantment itself does not allow.
    *
    * <p>The one question the anvil has to ask before it merges a book. Vanilla caps a merged
    * enchantment at its own maximum - which is the right answer for two Efficiency V books and
    * the wrong one for a book this mod minted on purpose - so an over-max book is routed to the
    * merge the mod already owns for its legendaries, which takes the higher of the two levels
    * and writes it. Asked of the stack (not of a book somebody might mint later), because the
    * anvil sees only the stack.
    */
   public static boolean overMaxLevelBook(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(Items.ENCHANTED_BOOK)) {
         return false;
      }

      ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
      if (stored == null || stored.isEmpty()) {
         return false;
      }

      for (Holder<Enchantment> enchantment : stored.keySet()) {
         if (stored.getLevel(enchantment) > enchantment.value().getMaxLevel()) {
            return true;
         }
      }

      return false;
   }

   /**
    * Marks a stack as one of the monarch's own keepings, merging rather than replacing so a
    * tome keeps being a tome. See {@link #MONARCH_KEEPING_KEY}.
    */
   public static void markMonarchKeeping(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return;
      }

      CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
      tag.putBoolean(MONARCH_KEEPING_KEY, true);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   /** True for anything the Distant Memory's fall can leave behind that the castle must not take back. */
   public static boolean isMonarchKeeping(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean(MONARCH_KEEPING_KEY).orElse(false);
   }

   /**
    * Enchantments without an owner: the registry lookup for an item that is minted outside a
    * level. Silently does nothing when the registry is not there, because a keepsake without
    * its Sharpness is still the keepsake - and the caller is a boss's death, which has no
    * business throwing.
    */
   private static void enchant(HolderLookup.Provider access, ItemStack stack, ResourceKey<Enchantment> key, int level) {
      if (access == null) {
         return;
      }

      try {
         HolderLookup.RegistryLookup<Enchantment> reg = access.lookupOrThrow(Registries.ENCHANTMENT);
         stack.enchant(reg.getOrThrow(key), level);
      } catch (Throwable ignored) {
      }
   }

   /** Keeps the Distant Memory fully enchantable. 26.2 requires both the
    *  enchantable component and an (empty) enchantments component for the
    *  enchanting table to offer options; this stamps whichever is missing and
    *  never wipes enchantments already on the blade. */
   public static void ensureDistantMemoryEnchantable(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      if (!stack.has(DataComponents.ENCHANTABLE)) {
         stack.set(DataComponents.ENCHANTABLE, new Enchantable(10));
      }
      if (!stack.has(DataComponents.ENCHANTMENTS)) {
         stack.set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
      }
   }

   public static ItemStack repairMembrane() {
      ItemStack stack = new ItemStack(Items.PHANTOM_MEMBRANE);
      setType(stack, "repair_membrane");
      stack.set(DataComponents.MAX_STACK_SIZE, 64);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lRepairing Membrane"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A shimmering membrane from the End."),
               Component.literal("§7Put ANY item or armor in the Item Forge"),
               Component.literal("§7with this to repair it to full durability."),
               Component.literal("§8Bought with gems in /gemshop")
            )
         )
      );
      return stack;
   }

   public static boolean isRepairMembrane(ItemStack stack) {
      return "repair_membrane".equals(typeOf(stack));
   }

   public static void refreshDistantMemoryLore(ItemStack stack) {
      if (stack != null && !stack.isEmpty() && isDistantMemorySword(stack)) {
         ensureDistantMemoryEnchantable(stack);
         int current = stack.getMaxDamage() - stack.getDamageValue();
         int max = stack.getMaxDamage();
         stack.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7A blade forged from the echo of a world"),
                  Component.literal("§7long gone. It remembers the old ways:"),
                  Component.literal("§7no cooldown, §b1.8 directional block§7 (front only,"),
                  Component.literal("§7no sound, no arrow reflect), normal damage,"),
                  Component.literal("§7no sweep, and twice the durability of diamond."),
                  Component.literal("§7Shields are forbidden in its presence."),
                  Component.literal("§8§o" + current + " / " + max + " durability"),
                  Component.literal("§8Fully enchantable · Hold right-click to block"),
                  Component.literal("§8Forged in the Item Forge")
               )
            )
         );
      }
   }

   public static boolean isSpawnerLoot(ItemStack stack) {
      return "spawner_loot".equals(typeOf(stack));
   }

   public static void setSpawnerLoot(ItemStack stack, int level) {
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", "spawner_loot");
      tag.putInt("ffl", Math.max(1, level));
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   public static int spawnerLootLevel(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 1 : data.copyTag().getIntOr("ffl", 1);
   }

   public static boolean isForgeMaterial(ItemStack stack) {
      return isKingBone(stack)
         || isSlimeCore(stack)
         || isGolemCore(stack)
         || isShatteredMind(stack)
         || isFrozenHeart(stack)
         || isSculkEssence(stack)
         || isDistantMemoryShard(stack)
         || isAbyssalPearl(stack)
         || isGaleCore(stack)
         || isRaidersItemUpgrader(stack);
   }

   public static boolean isForgeLegendary(ItemStack stack) {
      return isWitherLegendary(stack)
         || isSlimeLegendary(stack)
         || isGolemLegendary(stack)
         || isMindLegendary(stack)
         || isSnowLegendary(stack)
         || isSculkLegendary(stack)
         || isRaidLegendary(stack)
         || isScarletLegendary(stack)
         || isSeaLegendary(stack)
         || isGaleLegendary(stack);
   }

   /** True for the raid legendary items that the Raiders Item Upgrader upgrades. */
   public static boolean isRaidLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "warlord_axe".equals(type)
         || "evoker_spellbook".equals(type)
         || "captain_horn".equals(type)
         || "warlord_cloak".equals(type)
         || "evoker_cloak".equals(type)
         || "illusioner_spellbook".equals(type)
         || "illusioner_cloak".equals(type);
   }

   public static boolean isMindLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "mind_staff".equals(type) || "possessed_mask".equals(type) || "mind_shroud".equals(type);
   }

   public static boolean isGolemLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "stone_staff".equals(type) || "golem_fist".equals(type) || "stoneheart".equals(type);
   }

   public static boolean isWitherLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "wither_staff".equals(type) || "wither_blade".equals(type) || "wither_crown".equals(type) || "wither_cloak_sword".equals(type);
   }

   public static boolean isSlimeLegendary(ItemStack stack) {
      String type = typeOf(stack);
      return "slime_launcher".equals(type) || "slime_shield".equals(type) || "slime_boots".equals(type);
   }

   public static boolean isEnchantableWeapon(ItemStack stack) {
      if (isForgeLegendary(stack)) {
         return true;
      } else {
         return isDistantMemorySword(stack)
            ? true
            : stack.is(ItemTags.SWORDS)
               || stack.is(ItemTags.AXES)
               || stack.getItem() instanceof MaceItem
               || stack.getItem() instanceof TridentItem
               || stack.is(ItemTags.BOW_ENCHANTABLE)
               || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)
               || stack.is(ItemTags.FISHING_ENCHANTABLE);
      }
   }

   public static int slimeShieldCharges(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 3 : data.copyTag().getIntOr("ff_slime_shield_charges", 3);
   }

   public static long slimeShieldRechargeAt(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0L : data.copyTag().getLongOr("ff_slime_shield_recharge", 0L);
   }

   public static void setSlimeShieldState(ItemStack stack, int charges, long rechargeAt) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_slime_shield_charges", charges);
         tag.putLong("ff_slime_shield_recharge", rechargeAt);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static ItemStack findSlimeShield(Player player) {
      if (player == null) {
         return null;
      }

      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack s = player.getInventory().getItem(i);
         if (isSlimeShield(s)) {
            return s;
         }
      }

      return null;
   }

   public static int slimeBootsJumpLevel(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 1 : Math.max(0, Math.min(3, data.copyTag().getInt("ff_jump_boost").orElse(1)));
   }

   public static void setSlimeBootsJumpLevel(ItemStack stack, int level) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_jump_boost", Math.max(0, Math.min(3, level)));
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static boolean slimeBootsBounce(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null || data.copyTag().getBoolean("ff_bounce").orElse(true);
   }

   public static void setSlimeBootsBounce(ItemStack stack, boolean bounce) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putBoolean("ff_bounce", bounce);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static boolean isMindAscended(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? false : data.copyTag().getBoolean("ff_mind_ascended").orElse(false);
   }

   public static void markMindAscended(ItemStack stack) {
      try {
         if (stack == null || stack.isEmpty() || !isWitherStaff(stack) || tierOf(stack) < 3) {
            return;
         }

         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putBoolean("ff_mind_ascended", true);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lMultidimensional Army"));
         stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550101.0F));
         List<Component> lines = new ArrayList<>();
         ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
         if (lore != null) {
            for (Component line : lore.lines()) {
               String s = line.getString();
               if (!s.startsWith("§8Ascended")) {
                  lines.add(line);
               }
            }
         }

         lines.add(Component.literal("§8Ascended by unmaking the Mindbinder · summons 3 · evoker/witch/tank specialists"));
         stack.set(DataComponents.LORE, new ItemLore(lines));
      } catch (Exception var8) {
      }
   }

   public static ItemStack multidimensionalArmy() {
      ItemStack stack = withTier(witherStaff(), 3);
      markMindAscended(stack);
      return stack;
   }

   public static int tierOf(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return 1;
      }

      int t = data.copyTag().getInt("ff_tier").orElse(0);
      return Math.max(1, Math.min(3, t == 0 ? 1 : t));
   }

   public static ItemStack withTier(ItemStack source, int tier) {
      ItemStack result = source.copy();
      if (!isForgeLegendary(result)) {
         return result;
      }

      int newTier = Math.max(1, Math.min(3, tier));
      CustomData data = (CustomData)result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag tag = data.copyTag();
      tag.putInt("ff_tier", newTier);
      result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      applyTierComponents(result, newTier);
      restyleTiered(result, newTier);
      CustomEnchantments.refresh(result);
      return result;
   }

   private static void applyTierComponents(ItemStack stack, int tier) {
      String type = typeOf(stack);
      if ("wither_blade".equals(type)) {
         double dmg = tier >= 3 ? 12.0 : 5.0 + (tier - 1) * 3.0;
         AttributeModifier mod = new AttributeModifier(FortuneFavorsMod.id("wither_blade_damage"), dmg, Operation.ADD_VALUE);
         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.ATTACK_DAMAGE, mod, EquipmentSlotGroup.MAINHAND).build());
      } else if ("wither_crown".equals(type)) {
         Builder builder = ItemAttributeModifiers.builder();
         builder.add(
            Attributes.ARMOR, new AttributeModifier(FortuneFavorsMod.id("wither_crown_armor"), 3.0 + (tier - 1), Operation.ADD_VALUE), EquipmentSlotGroup.HEAD
         );
         if (tier >= 3) {
            builder.add(
               Attributes.ARMOR_TOUGHNESS,
               new AttributeModifier(FortuneFavorsMod.id("wither_crown_toughness"), 1.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.HEAD
            );
         }

         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("slime_boots".equals(type)) {
         Builder builder = ItemAttributeModifiers.builder();
         builder.add(
            Attributes.ARMOR, new AttributeModifier(FortuneFavorsMod.id("slime_boots_armor"), 2.0 + (tier - 1), Operation.ADD_VALUE), EquipmentSlotGroup.FEET
         );
         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("golem_fist".equals(type)) {
         double dmg = 7.0 + (tier - 1) * 2.0;
         double kb = 1.2 + (tier - 1) * 0.4;
         stack.set(
            DataComponents.ATTRIBUTE_MODIFIERS,
            ItemAttributeModifiers.builder()
               .add(
                  Attributes.ATTACK_DAMAGE,
                  new AttributeModifier(FortuneFavorsMod.id("golem_fist_damage"), dmg, Operation.ADD_VALUE),
                  EquipmentSlotGroup.MAINHAND
               )
               .add(
                  Attributes.ATTACK_KNOCKBACK,
                  new AttributeModifier(FortuneFavorsMod.id("golem_fist_knockback"), kb, Operation.ADD_VALUE),
                  EquipmentSlotGroup.MAINHAND
               )
               .build()
         );
      } else if ("stoneheart".equals(type)) {
         Builder builder = ItemAttributeModifiers.builder();
         builder.add(
            Attributes.ARMOR, new AttributeModifier(FortuneFavorsMod.id("stoneheart_armor"), 6.0 + (tier - 1), Operation.ADD_VALUE), EquipmentSlotGroup.CHEST
         );
         if (tier >= 3) {
            builder.add(
               Attributes.ARMOR_TOUGHNESS,
               new AttributeModifier(FortuneFavorsMod.id("stoneheart_toughness"), 2.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.CHEST
            );
         }

         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("glacier_cloak".equals(type)) {
         Builder builder = ItemAttributeModifiers.builder();
         builder.add(
            Attributes.ARMOR,
            new AttributeModifier(FortuneFavorsMod.id("glacier_cloak_armor"), 6.0 + (tier - 1), Operation.ADD_VALUE),
            EquipmentSlotGroup.CHEST
         );
         if (tier >= 3) {
            builder.add(
               Attributes.ARMOR_TOUGHNESS,
               new AttributeModifier(FortuneFavorsMod.id("glacier_cloak_toughness"), 2.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.CHEST
            );
         }

         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("mind_shroud".equals(type)) {
         Builder builder = ItemAttributeModifiers.builder();
         builder.add(
            Attributes.ARMOR, new AttributeModifier(FortuneFavorsMod.id("mind_shroud_armor"), 8.0 + (tier - 1), Operation.ADD_VALUE), EquipmentSlotGroup.CHEST
         );
         builder.add(
            Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(FortuneFavorsMod.id("mind_shroud_toughness"), tier >= 3 ? 5.0 : 3.0, Operation.ADD_VALUE),
            EquipmentSlotGroup.CHEST
         );
         builder.add(
            Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(FortuneFavorsMod.id("mind_shroud_kbresist"), 0.1, Operation.ADD_VALUE),
            EquipmentSlotGroup.CHEST
         );
         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("sculk_sensor_leggings".equals(type)) {
         Builder builder = ItemAttributeModifiers.builder();
         builder.add(
            Attributes.ARMOR,
            new AttributeModifier(FortuneFavorsMod.id("sculk_leggings_armor"), 6.0 + (tier - 1), Operation.ADD_VALUE),
            EquipmentSlotGroup.LEGS
         );
         if (tier >= 3) {
            builder.add(
               Attributes.ARMOR_TOUGHNESS,
               new AttributeModifier(FortuneFavorsMod.id("sculk_leggings_toughness"), 2.0, Operation.ADD_VALUE),
               EquipmentSlotGroup.LEGS
            );
         }

         stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
      } else if ("sculk_mage_staff".equals(type)) {
         double dmg = 5.0 + (tier - 1) * 2.0;
         stack.set(
            DataComponents.ATTRIBUTE_MODIFIERS,
            ItemAttributeModifiers.builder()
               .add(
                  Attributes.ATTACK_DAMAGE,
                  new AttributeModifier(FortuneFavorsMod.id("sculk_staff_damage"), dmg, Operation.ADD_VALUE),
                  EquipmentSlotGroup.MAINHAND
               )
               .build()
         );
      }
   }

   private static void restyleTiered(ItemStack stack, int tier) {
      if (tier >= 2) {
         String name = stack.getHoverName().getString();
         int bracket = name.indexOf(" [");
         if (bracket >= 0) {
            name = name.substring(0, bracket);
         }

         stack.set(DataComponents.CUSTOM_NAME, Component.literal(name + " §7[§eTier " + romanTier(tier) + "§7]"));
         ItemLore existing = (ItemLore)stack.get(DataComponents.LORE);
         if (existing != null) {
            List<Component> lines = new ArrayList<>();

            for (Component line : existing.lines()) {
               String s = line.getString();
               if (!s.startsWith("§8Tier ")
                  && !s.startsWith("§8Current Tier ")
                  && !s.startsWith("§8Upgrades wither")
                  && !s.startsWith("§8Upgrades slime")) {
                  lines.add(line);
               }
            }

            lines.add(Component.literal("§8Current Tier " + romanTier(tier) + " · " + tierStatLine(typeOf(stack), tier)));
            stack.set(DataComponents.LORE, new ItemLore(lines));
         }
      }
   }

   private static String tierStatLine(String type, int tier) {
      return switch (type) {
         case "wither_blade" -> "+"
            + (tier >= 3 ? 12 : 5 + (tier - 1) * 3)
            + " damage · "
            + (30 + (tier - 1) * 10)
            + "% wither · heals "
            + 2 * tier
            + " HP on kill";
         case "wither_staff" -> "summon cooldown " + (30 - (tier - 1) * 10) + "s";
         case "wither_crown" -> tier >= 3
            ? "+2 armor, +1 toughness · Wither becomes Regen " + CustomEnchantments.roman(tier) + ", slow passive heal"
            : "+1 armor · Wither becomes Regen " + CustomEnchantments.roman(tier);
         case "ice_staff" -> "mist deals " + (6 + (tier - 1) * 3) + " dmg · freeze " + (25 + (tier - 1) * 15) + " · longer spray";
         case "frostbound_crown" -> "shards deal "
            + (5 + (tier - 1) * 3)
            + " dmg · freeze "
            + (10 + (tier - 1) * 6)
            + " · "
            + (2.5 - (tier - 1) * 0.5)
            + "s cooldown";
         case "glacier_cloak" -> "+" + (tier - 1) + " armor · cold aura slows enemies";
         case "slime_launcher" -> 16
            + (tier - 1) * 4
            + " damage per ball · "
            + (2.0 - (tier - 1) * 0.5)
            + "s reload"
            + (tier >= 3 ? " · instant 5-ball fire" : " · charge 1-5");
         case "slime_shield" -> slimeShieldMaxChargesForTier(tier)
            + " blocks · "
            + (30 - (tier - 1) * 5)
            + "s recharge"
            + (tier >= 3 ? " · raise to block manually" : "");
         case "slime_boots" -> 2 + (tier - 1) + " armor · faster charge · stronger jump";
         case "stone_staff" -> "summon every " + (12 - (tier - 1) * 3) + "s · " + (8 + (tier - 1) * 3) + " dmg · " + (2.5 + (tier - 1) * 0.5) + " blast";
         case "golem_fist" -> "+" + (7 + (tier - 1) * 2) + " damage · enormous knockback";
         case "stoneheart" -> 6 + (tier - 1) + " armor · immovable while still";
         case "mind_shroud" -> 8 + (tier - 1) + " armor · dulls + scatters nearby mobs";
         default -> "";
      };
   }

   private static int slimeShieldMaxChargesForTier(int tier) {
      return 3 + (tier - 1) + (tier >= 3 ? 1 : 0);
   }

   private static String romanTier(int n) {
      return switch (n) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         default -> String.valueOf(n);
      };
   }

   public static int slimeShieldMaxCharges(ItemStack stack) {
      return slimeShieldMaxChargesForTier(tierOf(stack));
   }

   public static long slimeShieldRechargeTicks(ItemStack stack) {
      return 600L - (tierOf(stack) - 1) * 100L;
   }

   public static boolean isSpecialItem(ItemStack stack) {
      return typeOf(stack) != null;
   }

   /**
    * The only custom items the mod deliberately ships as real, placeable blocks:
    * its machinery and signage, and the spawner, which is a block on purpose
    * ("Place it, then right-click it"). Everything else the mod tags with an
    * {@code ff} type is refused placement.
    */
   public static final java.util.Set<String> PLACEABLE_TYPES = java.util.Set.of(
      "buy_sign", "sell_sign", "auto_sell_hopper", "upwards_hopper", "spawner_infuser",
      "item_forge", "chair", "elevator", "spawner",
      // The eight late-game machines. Every one of them is a real block the player stands in the
      // world and configures, so every one of them has to be in here: the list is read fail-closed,
      // and a machine missing from it is a machine that is refused at the moment of placement -
      // the client shows the block for a tick, the server refuses it, and the item stays in the
      // hand, which reads in play as "the thing I just bought unpacks itself".
      "chunk_anchor", "repair_station", "item_sorter", "portable_furnace", "portable_campfire",
      "auto_planter", "auto_harvester", "irrigation_sprinkler",
      // The heavy hoppers and the fast furnace. All real blocks the player stands in the world, so
      // all of them belong here - the list is read fail-closed, and a machine missing from it is
      // refused at the moment of placement. The Sorter Tag is deliberately NOT here: it is a sign
      // that must never be placed, because its whole click is the tag.
      "super_hopper", "transfer_hopper", "checker_hopper", "two_way_splitter", "super_smelter"
   );

   /** True when this stack is one of the few custom items that is a block on
    *  purpose - see {@link #PLACEABLE_TYPES}. */
   public static boolean isPlaceableByDesign(ItemStack stack) {
      String type = typeOf(stack);
      return type != null && PLACEABLE_TYPES.contains(type);
   }

   /**
    * True when this stack must never be placed as a block.
    *
    * <p>Most of the mod's items are ordinary items, but a fair number are drawn
    * on a *vanilla block item* purely for their icon: every loot box is a chest,
    * the Mystery Box a barrel, a boss trophy a gold block, the Raid Banner a
    * banner, the Boulder Baby chiselled stone bricks. Vanilla will happily place
    * a block for any of them, and a placed block cannot carry the stack's data -
    * so the item is destroyed and a plain block takes its place. That is the
    * "ghost block" this method exists to prevent.
    *
    * <p>The question is deliberately asked **fail closed**: a custom item is
    * placeable only if it is one of {@link #PLACEABLE_TYPES}; anything else built
    * on a block is refused even if nobody remembered to add it to the list below.
    * Listing them by hand was the old rule, and it failed exactly the way a
    * hand-maintained list does - five loot boxes, then a bell, a trophy set, a
    * banner and a summoner were all left out of it at one time or another.
    */
   public static boolean isNonPlaceable(ItemStack stack) {
      String type = typeOf(stack);
      if (type == null) {
         return false;
      }
      if (PLACEABLE_TYPES.contains(type)) {
         return false;
      }
      if (stack.getItem() instanceof BlockItem) {
         return true;
      }
      // The named list stays for the items that are *not* block items: the block
      // click has to be consumed for them just the same, or right-clicking a
      // chest with a Pocket-Watch would be handed to vanilla instead of to the
      // watch. The block-based entries in it are already answered above, so this
      // list can only ever grow more permissive, never less safe.
      return "time_lord_loot_box".equals(type)
         // The Wooden Marionette is built on an armor stand, which is not a BlockItem -
         // so the block-item test above never sees it, and right-clicking the ground with
         // it used to spawn an ordinary armor stand: the item was consumed and the stack's
         // data went with it, leaving a vanilla prop instead of the boss. It is refused
         // here, and the block click is handled as the summon it is meant to be in
         // ModEvents.onUseBlock.
         || "wooden_marionette".equals(type)
         || "scarlet_loot_box".equals(type)
         || "clockwork_loot_box".equals(type)
         || "starbound_loot_box".equals(type)
         || "voidshaper_loot_box".equals(type)
         || "sovereign_loot_box".equals(type)
         || "space_time_rift".equals(type)
         || "pocket_watch".equals(type)
         || "chrono_shard".equals(type)
         || "hourglass_of_haste".equals(type)
         || "king_loot_box".equals(type)
         || "slime_loot_box".equals(type)
         || "golem_loot_box".equals(type)
         || "mind_loot_box".equals(type)
         || "snow_loot_box".equals(type)
         || "sculk_loot_box".equals(type)
         || "wither_loot_box".equals(type)
         || "raid_loot_box".equals(type)
         || "mystery_box".equals(type)
         || "raid_boss_token".equals(type)
         || "backpack".equals(type)
         || "bundle".equals(type)
         || "ender_pouch".equals(type)
         || "sculk_medallion".equals(type)
         || "distant_memory_shard".equals(type)
         // The Potion Belt is a brewing stand's function in an inventory, and the one thing a
         // brewing stand in an inventory must never do is become a brewing stand on the floor. It is
         // drawn on leather today, which vanilla will not place - the entry is here so that stays
         // true if the icon ever changes, and so the block click is consumed rather than handed on.
         || "potion_belt".equals(type);
   }

   public static boolean hasChunkClaimer(Player player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         if (isChunkClaimer(player.getInventory().getItem(i))) {
            return true;
         }
      }

      return false;
   }

   public static boolean isToken(ItemStack stack) {
      if (stack != null && !stack.isEmpty() && stack.is(Items.PAPER)) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         return data == null ? false : data.copyTag().getInt("version").isPresent();
      } else {
         return false;
      }
   }

   public static String resolveSpawnerType(String typeId) {
      if (typeId != null && !typeId.isEmpty()) {
         try {
            EntityType<?> t = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(typeId));
            if (t != null) {
               return typeId;
            }
         } catch (Exception var2) {
         }

         return "minecraft:zombie";
      } else {
         return "minecraft:zombie";
      }
   }

   public static ItemStack spawnerItem(String typeId, UUID owner) {
      ItemStack stack = new ItemStack(Items.SPAWNER);
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", "spawner");
      String type = resolveSpawnerType(typeId);
      tag.putString("ff_spawner_type", type);
      if (owner != null) {
         tag.putString("ff_spawner_owner", owner.toString());
      }

      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lSpawner"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Spawns: §f" + spawnerMobName(type)),
               Component.literal("§7Place it, then right-click it to switch"),
               Component.literal("§7between spawning the mob and generating its drops.")
            )
         )
      );
      return stack;
   }

   public static int spawnerLevel(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return 1;
      }

      int level = data.copyTag().getInt("ff_spawner_level").orElse(0);
      return Math.max(1, level);
   }

   public static void setSpawnerLevel(ItemStack stack, int level) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_spawner_level", Math.max(1, level));
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static boolean isSpawnerItem(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      if ("spawner".equals(typeOf(stack))) {
         return true;
      }

      if (!stack.is(Items.SPAWNER)) {
         return false;
      }

      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().contains("ff_spawner_type");
   }

   public static UUID spawnerOwner(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }

      try {
         String s = data.copyTag().getString("ff_spawner_owner").orElse("");
         return s.isEmpty() ? null : UUID.fromString(s);
      } catch (Exception e) {
         return null;
      }
   }

   /**
    * The owner name exactly as stored, or {@code ""} when none was recorded.
    *
    * <p>Use this one whenever the value is going to be <em>written</em> anywhere.
    * {@link #spawnerOwnerName} is the display form, and its {@code "unbound"}
    * placeholder must never be persisted: doing exactly that is how every spawner
    * on the server ended up genuinely recorded as owned by "unbound" - a spawner
    * item carrying an owner UUID but no name was re-bound with the display
    * placeholder, and the string became the real owner name.
    */
   public static String spawnerOwnerNameRaw(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return "";
      }

      return data.copyTag().getString("ff_spawner_owner_name").orElse("");
   }

   /** Display form of {@link #spawnerOwnerNameRaw}: never persist this. */
   public static String spawnerOwnerName(ItemStack stack) {
      String s = spawnerOwnerNameRaw(stack);
      return s.isEmpty() ? "unbound" : s;
   }

   public static String spawnerTypeId(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? "" : data.copyTag().getString("ff_spawner_type").orElse("");
   }

   public static int spawnerMode(ItemStack stack) {
      CustomData data = stack == null ? null : (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0 : data.copyTag().getInt("ff_spawner_mode").orElse(0);
   }

   public static void setSpawnerMode(ItemStack stack, int mode) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt("ff_spawner_mode", mode);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static void bindSpawner(ItemStack stack, UUID owner, String ownerName) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putString("ff_spawner_owner", owner.toString());
         tag.putString("ff_spawner_owner_name", ownerName);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static void unbindSpawner(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.remove("ff_spawner_owner");
         tag.remove("ff_spawner_owner_name");
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   private static String spawnerMobName(String typeId) {
      if (typeId != null && !typeId.isEmpty()) {
         try {
            EntityType<?> type = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(typeId));
            if (type != null) {
               return Component.translatable(type.getDescriptionId()).getString();
            }
         } catch (Exception var2) {
         }

         return typeId;
      } else {
         return "Unknown";
      }
   }

   public static void setMachineData(ItemStack stack, UUID owner, String ownerName, long cash, long[] payouts) {
      if (!stack.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         if (owner != null) {
            tag.putString("ff_owner", owner.toString());
         }

         if (ownerName != null && !ownerName.isEmpty()) {
            tag.putString("ff_owner_name", ownerName);
         }

         tag.putLong("ff_cash", cash);
         if (payouts != null && payouts.length > 0) {
            tag.putLongArray("ff_payouts", payouts);
         }

         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      }
   }

   public static UUID machineOwner(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }

      String s = data.copyTag().getStringOr("ff_owner", "");
      if (s.isEmpty()) {
         return null;
      }

      try {
         return UUID.fromString(s);
      } catch (Exception e) {
         return null;
      }
   }

   public static String machineOwnerName(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? "" : data.copyTag().getStringOr("ff_owner_name", "");
   }

   public static long machineCash(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0L : data.copyTag().getLongOr("ff_cash", 0L);
   }

   public static long[] machinePayouts(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? null : (long[])data.copyTag().getLongArray("ff_payouts").orElse(null);
   }

   public static void appendMachineDataLore(ItemStack stack) {
      String type = typeOf(stack);
      if (type != null) {
         ItemLore existing = (ItemLore)stack.get(DataComponents.LORE);
         List<Component> lore = existing != null ? new ArrayList<>(existing.lines()) : new ArrayList<>();
         UUID owner = machineOwner(stack);
         String ownerName = machineOwnerName(stack);
         lore.add(
            Component.literal(owner != null ? "§7Owned by §f" + (ownerName.isEmpty() ? owner.toString().substring(0, 8) : ownerName) : "§7Place it to claim it")
         );
         if ("token_redeemer".equals(type)) {
            long cash = machineCash(stack);
            lore.add(Component.literal("§7Pool: §a$" + cash));
            long[] payouts = machinePayouts(stack);
            if (payouts != null && payouts.length >= 4) {
               lore.add(Component.literal("§7Payouts: §a$" + payouts[0] + "§7 · §a$" + payouts[1] + "§7 · §a$" + payouts[2] + "§7 · §a$" + payouts[3]));
            }
         }

         // A machine that comes up off the ground says what dials it was carrying, so the thing in
         // a player's hand is the same machine they were looking at and not a fresh tier one.
         int tier = machineTier(stack);
         if (tier > 1) {
            lore.add(Component.literal("§7Tier: " + com.fortuneandfavors.economy.MachineTuning.tierName(tier)
               + "§7 - it keeps this when it goes back down"));
         }
         if (machineVoiding(stack)) {
            lore.add(Component.literal("§7Set to §lvoid§7 the junk it catches."));
         }

         lore.add(Component.literal("§8Sneak-right-click to pick it back up"));
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
   }

   public static ItemStack createToken(ServerPlayer player, int version) {
      ItemStack stack = new ItemStack(Items.PAPER);
      CompoundTag tag = new CompoundTag();
      tag.putString("creator_uuid", player.getUUID().toString());
      tag.putString("creator_name", player.getName().getString());
      tag.putInt("version", version);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + player.getName().getString() + "'s Token " + TokenManager.versionName(version)));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + player.getName().getString() + "'s favor token · special currency"),
               Component.literal("§8Give it to friends or spend it at chest shops"),
               Component.literal("§8Shift-right-click (as the creator) to give it a custom brand"),
               Component.literal("§8Can't be crafted or replicated - only /token makes these")
            )
         )
      );
      return stack;
   }

   // === RAID LEGENDARY ITEMS ===

   /** Warlord's Axe: unbreakable, Sharpness 6, Smite 6 */
   public static ItemStack warlordAxe() {
      ItemStack stack = new ItemStack(Items.NETHERITE_AXE);
      setType(stack, "warlord_axe");
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550200.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l⚔ Warlord's Axe"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Sharpness VI · Smite VI · Unbreakable"),
         Component.literal("§7Axes deal 15% more damage when held."),
         Component.literal("§8Dropped by the Raid Warlord")
      )));
      return stack;
   }

   /** Evoker's Spellbook: summons fangs or vexes, cycle with sneak-right-click */
   public static ItemStack evokerSpellbook() {
      ItemStack stack = new ItemStack(Items.BOOK);
      setType(stack, "evoker_spellbook");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550201.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l⚔ Evoker's Spellbook"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Right-click to summon evoker fangs."),
         Component.literal("§7Sneak-right-click to summon vexes."),
         Component.literal("§8Dropped by the Elder Evoker")
      )));
      return stack;
   }

   /** Raid Captain's Horn: summon 3 pillagers to fight for you */
   public static ItemStack captainHorn() {
      ItemStack stack = new ItemStack(Items.GOAT_HORN);
      setType(stack, "captain_horn");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550202.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚔ Raid Captain's Horn"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Right-click to summon 3 raiders to fight for you."),
         Component.literal("§7Most likely pillagers, sometimes vindicators,"),
         Component.literal("§7rarely an evoker."),
         Component.literal("§8Dropped by the Raid Captain")
      )));
      return stack;
   }

   /** Warlord's Cloak: all axes deal 15% more damage */
   public static ItemStack warlordCloak() {
      ItemStack stack = new ItemStack(Items.LEATHER_CHESTPLATE);
      setType(stack, "warlord_cloak");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550203.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l⚔ Warlord's Cloak"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7While worn: all axes deal §c+15% §7damage."),
         Component.literal("§7§oSneak+right-click with an empty hand: §fWarlord's Rage§7 -"),
         Component.literal("§7§f12s§7 of §cStrength II§7, §cResistance I§7 and +15% attack speed."),
         Component.literal("§8Cooldown: 1 minute"),
         Component.literal("§8Dropped by the Raid Warlord")
      )));
      return stack;
   }

   /** Evoker's Cloak: buffs spellbook damage, unlocks 2 extra spells */
   public static ItemStack evokerCloak() {
      ItemStack stack = new ItemStack(Items.LEATHER_CHESTPLATE);
      setType(stack, "evoker_cloak");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550204.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l⚔ Evoker's Cloak"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7While worn: sneak to cast §5§lTotem of Vitality§7 -"),
         Component.literal("§7§f120 HP§7 of instant healing, +§f2 hearts§7 max for 10s,"),
         Component.literal("§7and an extra vex ally."),
         Component.literal("§8Cooldown: 45 seconds"),
         Component.literal("§8Dropped by the Elder Evoker")
      )));
      return stack;
   }

   /** Illusioner's Spellbook: blinds nearby entities, creates 3 illusions */
   public static ItemStack illusionerSpellbook() {
      ItemStack stack = new ItemStack(Items.BOOK);
      setType(stack, "illusioner_spellbook");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550205.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§9§l⚔ Illusioner's Spellbook"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Right-click to blind all nearby entities for 5s."),
         Component.literal("§7Blinded mobs cannot attack for 5s."),
         Component.literal("§7Then 3 illusions appear mimicking your actions."),
         Component.literal("§8Dropped by the Illusioner")
      )));
      return stack;
   }

   /** Illusioner's Cloak: improves illusioner spellbook */
   public static ItemStack illusionerCloak() {
      ItemStack stack = new ItemStack(Items.LEATHER_CHESTPLATE);
      setType(stack, "illusioner_cloak");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550206.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§9§l⚔ Illusioner's Cloak"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7While worn, Illusioner's Spellbook blinds for §f8s§7"),
         Component.literal("§7and creates §f5 copies§7 of you instead of 3."),
         Component.literal("§7Sneak: dash where you face and vanish for 1.5s."),
         Component.literal("§8Cooldown: 20 seconds"),
         Component.literal("§8Dropped by the Illusioner")
      )));
      return stack;
   }

   /** Raiders Item Upgrader: a forge material that upgrades raid legendaries.
    *  Not placable, stacks to 64 - use it in the Item Forge. */
   public static ItemStack raidersItemUpgrader() {
      ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
      setType(stack, "raiders_item_upgrader");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550207.0F));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚒ Raiders Item Upgrader"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7A forge material - NOT a block."),
         Component.literal("§7Put a raid legendary in the Item Forge with"),
         Component.literal("§7this to upgrade it to Tier II/III."),
         Component.literal("§8Dropped by the Raid Warlord")
      )));
      return stack;
   }

   /** Warlord's Trophy: a bragging-rights trophy from the Raid Warlord. */
   public static ItemStack warlordTrophy() {
      ItemStack stack = new ItemStack(Items.GOLD_BLOCK);
      setType(stack, "warlord_trophy");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550208.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l⚔ Warlord's Trophy"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Proof that you slew the Raid Warlord."),
         Component.literal("§7Display it or trade it - a true prize."),
         Component.literal("§8Dropped by the Raid Warlord")
      )));
      return stack;
   }

   public static boolean isWarlordAxe(ItemStack stack) {
      return "warlord_axe".equals(typeOf(stack));
   }

   public static boolean isEvokerSpellbook(ItemStack stack) {
      return "evoker_spellbook".equals(typeOf(stack));
   }

   public static boolean isCaptainHorn(ItemStack stack) {
      return "captain_horn".equals(typeOf(stack));
   }

   public static boolean isWarlordCloak(ItemStack stack) {
      return "warlord_cloak".equals(typeOf(stack));
   }

   public static boolean isEvokerCloak(ItemStack stack) {
      return "evoker_cloak".equals(typeOf(stack));
   }

   public static boolean isIllusionerSpellbook(ItemStack stack) {
      return "illusioner_spellbook".equals(typeOf(stack));
   }

   public static boolean isIllusionerCloak(ItemStack stack) {
      return "illusioner_cloak".equals(typeOf(stack));
   }

   public static boolean isRaidersItemUpgrader(ItemStack stack) {
      return "raiders_item_upgrader".equals(typeOf(stack));
   }

   public static boolean isWarlordTrophy(ItemStack stack) {
      return "warlord_trophy".equals(typeOf(stack));
   }

   // ------------------------------------------------------------------ the End's own set
   //
   // Three legendary weapons and the two materials that make them, all of them dropped to
   // EVERYBODY still standing in the End when the reworked dragon falls (see
   // EnderDragonManager.grantFreeTheEnd). The materials cannot be named in a crafting-table
   // recipe - in this version a recipe ingredient is a set of item ids with no components, so a
   // recipe could only ask for "a nautilus shell", not "the Heart of the End" - so the three
   // weapons are forged from a base weapon plus the Heart plus five scales, in the Item Forge,
   // which is the one place in the mod that can match an item exactly.

   /** The Heart of the End - one per player, the one thing every recipe for the set needs. */
   public static ItemStack heartOfTheEnd() {
      ItemStack stack = new ItemStack(Items.NAUTILUS_SHELL);
      setType(stack, "heart_of_the_end");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550300.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 16);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lHeart of the End"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The island's last beat, held in"),
               Component.literal("§7the hand of everyone who ended it."),
               Component.literal(""),
               Component.literal("§7Forge it with a §fnetherite sword§7, a §fbow§7"),
               Component.literal("§7or a §fmace§7, five §5Dragon Scales§7, and"),
               Component.literal("§7one of the End's three weapons is yours."),
               Component.literal("§8Dropped by the Ender Dragon.")
            )
         )
      );
      return stack;
   }

   public static boolean isHeartOfTheEnd(ItemStack stack) {
      return "heart_of_the_end".equals(typeOf(stack));
   }

   /** The Dragon Scale - five of them, every time, for everybody. */
   public static ItemStack dragonScale() {
      ItemStack stack = new ItemStack(Items.TURTLE_SCUTE);
      setType(stack, "dragon_scale");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550301.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 64);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5Dragon Scale"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Shed off the wings the moment the"),
               Component.literal("§7fight ended - still warm."),
               Component.literal("§8Dropped by the Ender Dragon (5 each).")
            )
         )
      );
      return stack;
   }

   public static boolean isDragonScale(ItemStack stack) {
      return "dragon_scale".equals(typeOf(stack));
   }

   /**
    * Voidfang - the End's sword.
    *
    * <p>Right-click carves a short rift forward through whatever stands in the lane; every blow
    * leaves a Void Hunger mark, and the third mark on one body detonates. Both have cooldowns, so
    * the weapon is a rhythm rather than a damage hose.
    */
   public static ItemStack voidfang() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "voidfang");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550302.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l⚔ Voidfang"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7\"It doesn't cut flesh. It cuts the"),
               Component.literal("§7 space around it.\""),
               Component.literal(""),
               Component.literal("§5Rift Slash §8- §7right-click to carve a"),
               Component.literal("§7rift forward. Short cooldown."),
               Component.literal("§5Void Hunger §8- §7every hit marks the"),
               Component.literal("§7body; the third mark detonates."),
               Component.literal("§7Forged from the Heart of the End.")
            )
         )
      );
      return stack;
   }

   public static boolean isVoidfang(ItemStack stack) {
      return "voidfang".equals(typeOf(stack));
   }

   /**
    * Starfall - the End's bow.
    *
    * <p>Arrows apply Astral Mark; stacking the mark bursts for End energy. Sneak-right-click
    * charges a star into the sky, which comes back down as several smaller stars around the body
    * that was aimed at.
    */
   public static ItemStack starfall() {
      ItemStack stack = new ItemStack(Items.BOW);
      setType(stack, "starfall");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550303.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🏹 Starfall"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7\"The stars do not fall. They are"),
               Component.literal("§7 pulled.\""),
               Component.literal(""),
               Component.literal("§5Astral Mark §8- §7hits stack a mark;"),
               Component.literal("§7at full marks it bursts."),
               Component.literal("§5Starfall §8- §7sneak-right-click to charge,"),
               Component.literal("§7release to call stars down. Long cooldown."),
               Component.literal("§7Forged from the Heart of the End.")
            )
         )
      );
      return stack;
   }

   public static boolean isStarfall(ItemStack stack) {
      return "starfall".equals(typeOf(stack));
   }

   /**
    * Enderheart - the End's mace, and not a normal one.
    *
    * <p>Impact carries the fall into the blow and leaves an End shockwave behind it; Gravity
    * Break takes the room's weight away and then gives it back; Enderfall launches its holder up
    * and brings them down like the dragon. Slow on purpose: it is a weapon about one enormous
    * moment, not a fast one.
    */
   public static ItemStack enderheart() {
      ItemStack stack = new ItemStack(Items.MACE);
      setType(stack, "enderheart");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550304.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l🟣 Enderheart"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7\"The Dragon's heart still beats.\""),
               Component.literal(""),
               Component.literal("§5Impact §8- §7the fall is the damage, and"),
               Component.literal("§7every smash leaves a shockwave."),
               Component.literal("§5Gravity Break §8- §7right-click to strip the"),
               Component.literal("§7room's weight, then return it all at once."),
               Component.literal("§5Enderfall §8- §7sneak-right-click to be"),
               Component.literal("§7thrown up, then crash down. Long cooldown."),
               Component.literal("§7Forged from the Heart of the End.")
            )
         )
      );
      return stack;
   }

   public static boolean isEnderheart(ItemStack stack) {
      return "enderheart".equals(typeOf(stack));
   }

   /** The three End weapons, in one predicate - the set the Heart and the Scales make. */
   public static boolean isEnderLegendary(ItemStack stack) {
      return isVoidfang(stack) || isStarfall(stack) || isEnderheart(stack);
   }

   // ------------------------------------------------------------- the awakened tier
   //
   // Every one of the three can be taken a step further, and the step is deliberately not a
   // stat bump: what changes is the *rhythm* of the weapon. Voidfang's rift closes sooner, so
   // the blade becomes something you can lead with; Starfall's mark bursts a hit earlier, so
   // the bow becomes a pressure weapon instead of a finisher; Enderheart's shockwave and dive
   // reach further, so the mace stops being a single-target moment. The cost is two more
   // Hearts of the End and ten more Scales, which is exactly two more dragons - an upgrade you
   // fight for rather than one you buy.
   //
   // The awakened weapons are still the same *base* items (a netherite sword, a bow, a mace),
   // so the dispatch in ModEvents and the arrow-carried weapon lookup in EnderGear keep working
   // untouched: only the predicates widen.

   /**
    * Voidfang, Awakened - "the rift no longer closes, it blinks".
    *
    * <p>The tier-one blade is a rhythm weapon with a fifty-tick rift; this one carves it in
    * thirty-four and cuts deeper. The mark's ceiling shortens with it, so a fight led by this
    * sword is genuinely faster rather than merely bigger.
    */
   public static ItemStack voidfangAwakened() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "voidfang_awakened");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550312.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§l⚔ Voidfang, Awakened"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7\"It doesn't cut flesh. It cuts the"),
               Component.literal("§7 space around it.\""),
               Component.literal(""),
               Component.literal("§dRift Slash §8- §7the rift closes faster, and"),
               Component.literal("§7the cut runs deeper."),
               Component.literal("§dVoid Hunger §8- §7the third mark detonates, and"),
               Component.literal("§7the dead time between detonations is shorter."),
               Component.literal(""),
               Component.literal("§8Reforged with two Hearts of the End.")
            )
         )
      );
      return stack;
   }

   public static boolean isVoidfangAwakened(ItemStack stack) {
      return "voidfang_awakened".equals(typeOf(stack));
   }

   /**
    * Starfall, Awakened - "the sky answers sooner".
    *
    * <p>Astral Mark bursts a hit earlier (three arrows instead of four) and the charged shot
    * brings down eighteen stars instead of fourteen, each landing harder, so the bow that used to
    * set up a burst now lands one. Still a precision weapon: the marks live on one body at a time.
    */
   public static ItemStack starfallAwakened() {
      ItemStack stack = new ItemStack(Items.BOW);
      setType(stack, "starfall_awakened");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550313.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§l🏹 Starfall, Awakened"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7\"The stars do not fall. They are"),
               Component.literal("§7 pulled.\""),
               Component.literal(""),
               Component.literal("§dAstral Mark §8- §7bursts on the third arrow."),
               Component.literal("§dStarfall §8- §7eighteen stars come down instead"),
               Component.literal("§7of fourteen, and each one lands harder."),
               Component.literal(""),
               Component.literal("§8Reforged with two Hearts of the End.")
            )
         )
      );
      return stack;
   }

   public static boolean isStarfallAwakened(ItemStack stack) {
      return "starfall_awakened".equals(typeOf(stack));
   }

   /**
    * Enderheart, Awakened - "the heart beats louder".
    *
    * <p>The smash's shockwave reaches further, Gravity Break hurts more when the weight comes back,
    * the Enderfall crater is wider and everyone in it is hit harder, and both abilities come back in
    * less time than the base mace's. It is still slow; it is simply no longer small - and no longer
    * a tier whose whole difference is a bigger number on the same three moves.
    */
   public static ItemStack enderheartAwakened() {
      ItemStack stack = new ItemStack(Items.MACE);
      setType(stack, "enderheart_awakened");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(4550314.0F));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§l🟣 Enderheart, Awakened"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7\"The Dragon's heart still beats.\""),
               Component.literal(""),
               Component.literal("§dImpact §8- §7the shockwave reaches further"),
               Component.literal("§7and throws harder."),
               Component.literal("§dGravity Break §8- §7the weight lands on a wider"),
               Component.literal("§7room, lands harder, and comes back sooner."),
               Component.literal("§dEnderfall §8- §7a bigger crater, lands harder,"),
               Component.literal("§7and the wings recover sooner."),
               Component.literal(""),
               Component.literal("§8Reforged with two Hearts of the End.")
            )
         )
      );
      return stack;
   }

   public static boolean isEnderheartAwakened(ItemStack stack) {
      return "enderheart_awakened".equals(typeOf(stack));
   }

   /**
    * The three awakened weapons in one predicate.
    *
    * <p>Read by the forge (as the thing an upgrade *makes*), by the damage pipeline (so its
    * abilities route to the same handlers), and by the audit.
    */
   public static boolean isEnderAwakened(ItemStack stack) {
      return isVoidfangAwakened(stack) || isStarfallAwakened(stack) || isEnderheartAwakened(stack);
   }

   /**
    * Either tier of any of the three.
    *
    * <p>This is the predicate the abilities ask, because an awakened weapon must keep every
    * behaviour its base form had - an upgraded sword is a Voidfang, not a different weapon.
    */
   public static boolean isAnyVoidfang(ItemStack stack) {
      return isVoidfang(stack) || isVoidfangAwakened(stack);
   }

   public static boolean isAnyStarfall(ItemStack stack) {
      return isStarfall(stack) || isStarfallAwakened(stack);
   }

   public static boolean isAnyEnderheart(ItemStack stack) {
      return isEnderheart(stack) || isEnderheartAwakened(stack);
   }

   /** Either tier of the set, so the forge can recognise what it is being handed. */
   public static boolean isAnyEnderLegendary(ItemStack stack) {
      return isEnderLegendary(stack) || isEnderAwakened(stack);
   }

   /**
    * The awakened form of one of the three, or EMPTY when the stack is not an End weapon.
    *
    * <p>One place decides the mapping, so the forge, the recycle path and the audit cannot
    * disagree about what upgrades into what.
    */
   public static ItemStack awaken(ItemStack stack) {
      if (isVoidfang(stack)) {
         return voidfangAwakened();
      }
      if (isStarfall(stack)) {
         return starfallAwakened();
      }
      if (isEnderheart(stack)) {
         return enderheartAwakened();
      }
      return ItemStack.EMPTY;
   }

   // ================================================================= the sea and the sky
   //
   // The Drowned Sovereign and the Gale Warden, and the six legendaries between them. The ids are
   // kept in one block so the Java codes and the thresholds in `tools/make_item_definitions.py`
   // stay in step - see the note above MARIONETTE_MODEL for what happens when they do not.

   public static final float LEVIATHANS_GRASP_MODEL = 4550320.0F;
   public static final float TIDECALLER_MODEL = 4550321.0F;
   public static final float ABYSSAL_CHAIN_MODEL = 4550322.0F;
   public static final float SOVEREIGNS_HEART_MODEL = 4550323.0F;
   public static final float DROWNED_LOOT_BOX_MODEL = 4550324.0F;
   public static final float SKYBREAKER_MODEL = 4550325.0F;
   public static final float GALE_CHAKRAM_MODEL = 4550326.0F;
   public static final float WARDENS_MANTLE_MODEL = 4550327.0F;
   public static final float GALE_SIGIL_MODEL = 4550328.0F;
   public static final float GALE_LOOT_BOX_MODEL = 4550329.0F;
   public static final float ABYSSAL_PEARL_MODEL = 4550330.0F;
   public static final float GALE_CORE_MODEL = 4550331.0F;

   /**
    * The Abyssal Pearl - the Drowned Sovereign's forge material.
    *
    * <p>Every boss before these two dropped the same shared material, which is why both new sets
    * were forged with nothing of their own: a Leviathan's Grasp had no pearl to want, so the forge
    * fell through to the *last* set in its chain and upgraded it with a Mindbinder's Shattered
    * Mind. Each new boss now feeds exactly the set it belongs to, and the forge refuses anything
    * else for it - see ForgeOps#upgradeMaterialFor, which is the only place a set's material is
    * decided.
    */
   public static ItemStack abyssalPearl() {
      ItemStack stack = new ItemStack(Items.NAUTILUS_SHELL);
      setType(stack, "abyssal_pearl");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(ABYSSAL_PEARL_MODEL));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l\u25C6 Abyssal Pearl"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Pressure, cold and a very long time,"),
               Component.literal("§7pressed into one smooth pearl."),
               Component.literal("§8Upgrades the Drowned Sovereign's legendaries to Tier II/III"),
               Component.literal("§8in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static boolean isAbyssalPearl(ItemStack stack) {
      return "abyssal_pearl".equals(typeOf(stack));
   }

   /**
    * The Gale Core - the Gale Warden's forge material.
    *
    * <p>Momentum made solid, which is the only thing the Warden ever had to give. It upgrades the
    * sky set and nothing else, the same way the pearl upgrades the sea set and nothing else.
    */
   public static ItemStack galeCore() {
      ItemStack stack = new ItemStack(Items.BREEZE_ROD);
      setType(stack, "gale_core");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(GALE_CORE_MODEL));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l\u2744 Gale Core"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A knot of moving air, folded until it"),
               Component.literal("§7held still. It would rather not."),
               Component.literal("§8Upgrades the Gale Warden's legendaries to Tier II/III"),
               Component.literal("§8in the Item Forge")
            )
         )
      );
      return stack;
   }

   public static boolean isGaleCore(ItemStack stack) {
      return "gale_core".equals(typeOf(stack));
   }

   /**
    * The Heart of the Deep - the summon, and the sea boss's own material. Right-click it and the
    * tide comes in where you are standing.
    *
    * <p>Deliberately not a Compass: the two newest bosses are summoned by giving them something of
    * their own. A heart out of the deep is the one thing in this mod that the sea boss would answer.
    */
   public static ItemStack sovereignsHeart() {
      ItemStack stack = new ItemStack(Items.HEART_OF_THE_SEA);
      setType(stack, "sovereigns_heart");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(SOVEREIGNS_HEART_MODEL));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l\uD83C\uDF0A Heart of the Deep"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7It is still beating. That is the"),
               Component.literal("§7strange part."),
               Component.literal("§7Right-click and it answers with the"),
               Component.literal("§3Drowned Sovereign§7."),
               Component.literal("§8He fights wherever you are.")
            )
         )
      );
      return stack;
   }

   public static boolean isSovereignsHeart(ItemStack stack) {
      return "sovereigns_heart".equals(typeOf(stack));
   }

   /** His loot box. */
   public static ItemStack drownedLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "drowned_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(DROWNED_LOOT_BOX_MODEL));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l\uD83C\uDF0A Drowned Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Drowned Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isDrownedLootBox(ItemStack stack) {
      return "drowned_loot_box".equals(typeOf(stack));
   }

   /**
    * Leviathan's Grasp - a gauntlet built like a closing claw.
    *
    * <p>A bruiser's weapon: everything about it is about where the other body ends up. Grasp pulls
    * them in, Crush drives them into the floor, and the passive means the closer to death they are
    * the further the blow sends them - so a low-health target is a body you launch across a room.
    */
   public static ItemStack leviathansGrasp() {
      ItemStack stack = new ItemStack(Items.MACE);
      setType(stack, "leviathans_grasp");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(LEVIATHANS_GRASP_MODEL));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l\uD83E\uDD1E Leviathan's Grasp"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A claw that closed once and never"),
               Component.literal("§7opened again."),
               Component.literal(""),
               Component.literal("§3Grasp §8- §7right-click to haul the nearest"),
               Component.literal("§7enemy in and Crush it into the ground."),
               Component.literal("§3Undertow §8- §7sneak-right-click for a vortex"),
               Component.literal("§7at your feet that drags mobs inward."),
               Component.literal("§3Abyssal §8- §7jump and strike to come down"),
               Component.literal("§7in a water shockwave."),
               Component.literal("§3Passive §8- §7the weaker the target, the"),
               Component.literal("§7harder the knockback."),
               Component.literal("§8Dropped by the Drowned Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isLeviathansGrasp(ItemStack stack) {
      return "leviathans_grasp".equals(typeOf(stack));
   }

   /**
    * Tidecaller - his trident. Right-click throws a small wave you can steer by looking, which is the
    * weapon's identity: it is not damage, it is a launcher.
    */
   public static ItemStack tidecaller() {
      ItemStack stack = new ItemStack(Items.TRIDENT);
      setType(stack, "tidecaller");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(TIDECALLER_MODEL));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l\uD83D\uDD31 Tidecaller"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The sea answers this one. It answers"),
               Component.literal("§7nobody else."),
               Component.literal(""),
               Component.literal("§3Right-click §8- §7send a rolling wave along"),
               Component.literal("§7your sightline. Everything it passes"),
               Component.literal("§7is picked up and thrown where it was going."),
               Component.literal("§3Undertow §8- §7sneak-right-click to pull the"),
               Component.literal("§7nearby inward before the wave leaves."),
               Component.literal("§8Dropped by the Drowned Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isTidecaller(ItemStack stack) {
      return "tidecaller".equals(typeOf(stack));
   }

   /**
    * Abyssal Chain - a weapon that controls distance instead of health.
    *
    * <p>Pull an enemy to you, pull yourself to a wall, and mark what you hit so the rest of the
    * fight sends it further than it wanted to go.
    */
   public static ItemStack abyssalChain() {
      ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);
      setType(stack, "abyssal_chain");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(ABYSSAL_CHAIN_MODEL));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§3§l\u26D3 Abyssal Chain"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7It is not for hitting people. It is for"),
               Component.literal("§7deciding where they stand."),
               Component.literal(""),
               Component.literal("§3Chain Pull §8- §7right-click an enemy to yank"),
               Component.literal("§7them to you; §3Abyssal Hook §8- §7aim at a block"),
               Component.literal("§7to pull §7yourself§7 to it instead."),
               Component.literal("§3Undertow §8- §7sneak-right-click to drag"),
               Component.literal("§7everything nearby toward the impact."),
               Component.literal("§3Depth Mark §8- §7marked enemies take far more"),
               Component.literal("§7knockback from your blows."),
               Component.literal("§8Dropped by the Drowned Sovereign.")
            )
         )
      );
      return stack;
   }

   public static boolean isAbyssalChain(ItemStack stack) {
      return "abyssal_chain".equals(typeOf(stack));
   }

   /** The Gale Warden's summon. */
   public static ItemStack galeSigil() {
      ItemStack stack = new ItemStack(Items.NAUTILUS_SHELL);
      setType(stack, "gale_sigil");
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(GALE_SIGIL_MODEL));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l\uD83C\uDF2A Gale Sigil"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Hold it up and the air starts moving"),
               Component.literal("§7in a direction of its choosing."),
               Component.literal("§7Right-click and it answers with the"),
               Component.literal("§fGale Warden§7."),
               Component.literal("§8He fights wherever you are.")
            )
         )
      );
      return stack;
   }

   public static boolean isGaleSigil(ItemStack stack) {
      return "gale_sigil".equals(typeOf(stack));
   }

   /** His loot box. */
   public static ItemStack galeLootBox() {
      ItemStack stack = new ItemStack(Items.CHEST);
      setType(stack, "gale_loot_box");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(GALE_LOOT_BOX_MODEL));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l\uD83C\uDF2A Gale Loot Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to spin for a prize!"),
               Component.literal("§7Shift-right-click to see what you can get."),
               Component.literal("§7Stacks in your inventory."),
               Component.literal("§8Dropped by the Gale Warden.")
            )
         )
      );
      return stack;
   }

   public static boolean isGaleLootBox(ItemStack stack) {
      return "gale_loot_box".equals(typeOf(stack));
   }

   /**
    * Skybreaker - a greatsword that uses wind to make its swings reach further than the blade does.
    */
   public static ItemStack skybreaker() {
      ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
      setType(stack, "skybreaker");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(SKYBREAKER_MODEL));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l\uD83D\uDDE1 Skybreaker"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Heavy, and it knows it."),
               Component.literal(""),
               Component.literal("§3Wind Slash §8- §7right-click to throw a"),
               Component.literal("§7horizontal blade of wind down your sightline."),
               Component.literal("§3Updraft §8- §7hitting an enemy lifts you both"),
               Component.literal("§7a little; §3Downforce §8- §7striking an airborne"),
               Component.literal("§7enemy slams it into the ground."),
               Component.literal("§3Break the Sky §8- §7sneak-right-click to leap"),
               Component.literal("§7and come down in a radial shockwave."),
               Component.literal("§3Passive §8- §7the faster you are moving when you"),
               Component.literal("§7swing, the harder it lands."),
               Component.literal("§8Dropped by the Gale Warden.")
            )
         )
      );
      return stack;
   }

   public static boolean isSkybreaker(ItemStack stack) {
      return "skybreaker".equals(typeOf(stack));
   }

   /**
    * Gale Chakram - a ring of compressed wind that comes back, and can be made to come back
    * somewhere else.
    */
   public static ItemStack galeChakram() {
      ItemStack stack = new ItemStack(Items.TRIDENT);
      setType(stack, "gale_chakram");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(GALE_CHAKRAM_MODEL));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l\uD83E\uDE83 Gale Chakram"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Two chances per throw: out, and back."),
               Component.literal(""),
               Component.literal("§3Right-click §8- §7throw. It hits on the way out"),
               Component.literal("§7and again on the way home."),
               Component.literal("§3Windcurve §8- §7sneak-right-click and it"),
               Component.literal("§7curves instead of flying straight."),
               Component.literal("§3Razor Current §8- §7every consecutive hit on"),
               Component.literal("§7the same enemy knocks it further back."),
               Component.literal("§3Cyclone Return §8- §7sometimes it circles you"),
               Component.literal("§7on the way back. Twice the blade, twice"),
               Component.literal("§7the hits."),
               Component.literal("§8Dropped by the Gale Warden.")
            )
         )
      );
      return stack;
   }

   public static boolean isGaleChakram(ItemStack stack) {
      return "gale_chakram".equals(typeOf(stack));
   }

   /**
    * Warden's Mantle - his chestplate, and a movement item rather than a weapon.
    *
    * <p>It has no right-click on purpose: an armour piece's click is how you put it on, so every one
    * of its abilities is read off the tick loop instead - see {@code SeaAndSkyGear}.
    */
   public static ItemStack wardensMantle() {
      ItemStack stack = new ItemStack(Items.NETHERITE_CHESTPLATE);
      setType(stack, "wardens_mantle");
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_MODEL_DATA, cmd(WARDENS_MANTLE_MODEL));
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l\uD83E\uDEBD Warden's Mantle"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Woven from a wind that was already"),
               Component.literal("§7going somewhere."),
               Component.literal(""),
               Component.literal("§3Light as Air §8- §7you fall slowly, and far"),
               Component.literal("§7less hard."),
               Component.literal("§3Windstep §8- §7double-tap sneak to dash."),
               Component.literal("§3Updraft §8- §7sneak and jump for a burst of"),
               Component.literal("§7upward momentum."),
               Component.literal("§3Tailwind §8- §7sprint for a few seconds and"),
               Component.literal("§7the wind starts helping."),
               Component.literal("§3Second Wind §8- §7once per minute, a fall that"),
               Component.literal("§7would have killed you throws you back up."),
               Component.literal("§8Dropped by the Gale Warden.")
            )
         )
      );
      return stack;
   }

   public static boolean isWardensMantle(ItemStack stack) {
      return "wardens_mantle".equals(typeOf(stack));
   }

   /** His three, in one predicate - the set the Gale Loot Box rolls at. */
   public static boolean isGaleLegendary(ItemStack stack) {
      return isSkybreaker(stack) || isGaleChakram(stack) || isWardensMantle(stack);
   }

   /** His three, in one predicate. */
   public static boolean isSeaLegendary(ItemStack stack) {
      return isLeviathansGrasp(stack) || isTidecaller(stack) || isAbyssalChain(stack);
   }
}
