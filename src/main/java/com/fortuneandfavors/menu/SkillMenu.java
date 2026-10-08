package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.SkillManager;
import com.fortuneandfavors.economy.SkillManager.Upgrade;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The skill screen.
 *
 * <p>It is three rows taller than it used to be, and that is the whole point of the rework: the
 * old board put six trees, the point total, the respec and the close button into twenty-seven
 * slots and then said everything it had to say in a lore list, because there was nowhere else to
 * put it. A card could not say how far into a level a player was and what they had to spend at
 * the same time - the two facts a player opens this screen to read. Six rows buys the room to
 * state both on the card itself, and to give a tree's upgrade row a real header instead of a
 * tooltip.
 */
public class SkillMenu extends ChestMenu {
   private static final int INFO = 4;
   /**
    * The trees, centred on the overview's third row.
    *
    * <p>Seven now, not six: Fishing was added as a whole tree, and the row has the slot for it. The
    * row is written out rather than derived from {@code SKILLS.length} because the cards have to
    * stay centred whichever number of trees the file happens to hold - a derived row would silently
    * left-shift the whole board the moment a tree was added.
    */
   private static final int[] CARDS = new int[]{19, 20, 21, 22, 23, 24, 25};
   private static final int POINTS = 13;
   private static final int RESPEC = 31;
   /** A tree's upgrades: two rows, because Enchanting has nine of them and the rest have six or seven. */
   private static final int[] UPGRADES = new int[]{
      10, 11, 12, 13, 14, 15, 16,
      19, 20, 21, 22, 23, 24, 25
   };
   private static final int REFUND_TREE = 40;
   private static final int BACK = 45;
   private static final int CLOSE = 49;
   private static final int ROWS = 6;
   private static final int SIZE = ROWS * 9;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private String view;
   private final Map<Integer, String> upgradeSlots = new LinkedHashMap<>();

   public SkillMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(SIZE));
   }

   private SkillMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, ROWS);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.view = null;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new SkillMenu(syncId, inv), Component.literal("§6§lSkill Tree")));
   }

   private void rebuild() {
      this.container.clearContent();
      this.upgradeSlots.clear();
      UUID uuid = this.owner.getUUID();
      if (this.view == null) {
         for (int i = 0; i < SkillManager.SKILLS.length && i < CARDS.length; i++) {
            this.container.setItem(CARDS[i], this.card(SkillManager.SKILLS[i], uuid));
         }

         this.container.setItem(INFO, this.infoBook(uuid));
         this.container.setItem(POINTS, this.pointsItem(uuid));
         this.container.setItem(RESPEC, this.respecItem(uuid));
         this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
      } else {
         Upgrade[] ups = SkillManager.upgradesOf(this.view);

         for (int i = 0; i < ups.length && i < UPGRADES.length; i++) {
            int slot = UPGRADES[i];
            this.container.setItem(slot, this.upgradeItem(ups[i], uuid));
            this.upgradeSlots.put(slot, ups[i].id());
         }

         this.container.setItem(INFO, this.treeHeader(uuid, this.view));
         this.container.setItem(REFUND_TREE, this.refundSkillItem(uuid, this.view));
         this.container.setItem(BACK, this.named(Items.ARROW, "§7Back to the six skills"));
         this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
      }
   }

   /** The header of a tree page: level, the real XP numbers, and what is left to spend here. */
   private ItemStack treeHeader(UUID uuid, String skill) {
      int level = SkillManager.level(uuid, skill);
      int unspent = SkillManager.unspentPoints(uuid, skill);
      boolean maxed = SkillManager.isMaxed(uuid, skill);
      ItemStack stack = new ItemStack(SkillManager.cardItem(skill));
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(
            "§b§l" + SkillManager.displayName(skill) + " §7- Level §f" + level + "§7/§f" + SkillManager.MAX_LEVEL + (maxed ? " §6✔" : "")
         )
      );
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(SkillManager.bar(uuid, skill) + " §f" + SkillManager.xpPercent(uuid, skill) + "%"));
      if (maxed) {
         lore.add(Component.literal("§6Tree complete - the capstone is yours."));
         lore.add(Component.literal("§7This skill no longer takes XP; its clock is done."));
      } else {
         lore.add(Component.literal("§7XP: §f" + SkillManager.xpIntoLevel(uuid, skill) + "§8/§f" + SkillManager.xpNeeded(uuid, skill)));
      }

      lore.add(Component.literal("§7Invested here: §f" + SkillManager.spentPoints(uuid, skill) + "§7 point(s)"));
      lore.add(Component.literal(unspent > 0 ? "§b" + unspent + " point(s) to spend" : "§8No points to spend in this tree"));
      String bonuses = SkillManager.activeBonuses(uuid, skill);
      lore.add(Component.literal(bonuses.isEmpty() ? "§8No upgrades bought yet" : "§8" + bonuses));
      lore.add(Component.literal("§7Click an upgrade below to buy the next level."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   /** The one page that explains the system, now that the system has a curve and a capstone. */
   private ItemStack infoBook(UUID uuid) {
      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lHow skills work"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Skills level up from ordinary play:"),
               Component.literal("§7  §fMining§7 - mine blocks with a pickaxe"),
               Component.literal("§7  §fCombat§7 - kill mobs with a weapon"),
               Component.literal("§7  §fFarming§7 - harvest crops"),
               Component.literal("§7  §fTrading§7 - complete trades"),
               Component.literal("§7  §fForaging§7 - chop wood with an axe"),
               Component.literal("§7  §fEnchanting§7 - forge & enchant gear"),
               Component.literal("§7  §fFishing§7 - reel in a catch"),
               Component.literal(""),
               Component.literal("§6Every level-up earns 1 skill point!"),
               Component.literal("§6Level " + SkillManager.MAX_LEVEL + " pays a capstone point, and ends that tree."),
               Component.literal("§7Each tree caps at level " + SkillManager.MAX_LEVEL + " on its own -"),
               Component.literal("§7a finished skill stops taking XP and stops paying."),
               Component.literal("§7Later levels ask for more: the last one costs " + SkillManager.xpForLevel(SkillManager.MAX_LEVEL) + " XP."),
               Component.literal("§7Finish every tree for §6Master of All§7."),
               Component.literal(""),
               Component.literal("§8Completed trees: " + SkillManager.maxedTrees(uuid) + "/" + SkillManager.SKILLS.length
                  + "  §8|  Total level: " + SkillManager.totalLevel(uuid) + "/" + (SkillManager.SKILLS.length * SkillManager.MAX_LEVEL)),
               Component.literal("§7Click a card to spend points on upgrades.")
            )
         )
      );
      return info;
   }

   private ItemStack pointsItem(UUID uuid) {
      int pts = SkillManager.unspentPoints(uuid);
      ItemStack stack = new ItemStack(Items.EXPERIENCE_BOTTLE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§l" + pts + " §7unspent skill point" + (pts == 1 ? "" : "s")));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Each tree keeps its own pool - points only spend"));
      lore.add(Component.literal("§7inside the skill that earned them."));
      lore.add(Component.literal(""));
      for (String s : SkillManager.SKILLS) {
         int left = SkillManager.unspentPoints(uuid, s);
         lore.add(
            Component.literal(
               "§7" + SkillManager.displayName(s) + ": §f" + left + (SkillManager.isMaxed(uuid, s) ? " §6✔ complete" : "")
            )
         );
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Total level: " + SkillManager.totalLevel(uuid) + "/" + (SkillManager.SKILLS.length * SkillManager.MAX_LEVEL)));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack respecItem(UUID uuid) {
      long cost = SkillManager.refundCost(uuid);
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(cost <= 0L ? "§8Respec - nothing spent" : "§c§lRespec §8(" + Chat.moneyStr(cost) + ")"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Refunds every upgrade in every tree"),
               Component.literal("§7back into each tree's own pool."),
               Component.literal("§7Your level and XP are not touched."),
               Component.literal("§8Costs $1,000 per point invested.")
            )
         )
      );
      return stack;
   }

   private ItemStack refundSkillItem(UUID uuid, String skill) {
      int spent = SkillManager.spentPoints(uuid, skill);
      ItemStack stack = new ItemStack(Items.GRINDSTONE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(spent > 0 ? "§e§lRefund " + SkillManager.displayName(skill) : "§8No points to refund"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Refund all points invested in this tree."));
      lore.add(Component.literal("§7They return to this tree's pool, whole."));
      if (spent > 0) {
         lore.add(Component.literal("§8Costs " + Chat.moneyStr(spent * 500L) + " for " + spent + " point(s)."));
      }
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack upgradeItem(Upgrade upgrade, UUID uuid) {
      int level = SkillManager.upgradeLevel(uuid, this.view, upgrade.id());
      boolean maxed = level >= upgrade.maxLevel();
      int unspent = SkillManager.unspentPoints(uuid, this.view);
      boolean affordable = unspent >= upgrade.cost();
      ItemStack stack = new ItemStack(this.iconFor(this.view, upgrade.id()));
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(
            (maxed ? "§7§l" : (affordable ? "§a§l" : "§c§l")) + upgrade.name() + " §8(" + upgrade.cost() + " pt" + (upgrade.cost() == 1 ? "" : "s") + ")"
         )
      );
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(upgrade.desc()));
      lore.add(Component.literal("§7Level: §f" + level + "§7/§f" + upgrade.maxLevel() + "  " + SkillManager.pips(level, upgrade.maxLevel())));
      if (maxed) {
         lore.add(Component.literal("§aMaxed!"));
      } else if (affordable) {
         lore.add(Component.literal("§aClick to upgrade §7(-" + upgrade.cost() + ", §f" + (unspent - upgrade.cost()) + "§7 left)"));
      } else {
         lore.add(Component.literal("§cNeed " + (upgrade.cost() - unspent) + " more " + SkillManager.displayName(this.view) + " point(s)"));
      }

      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private Item iconFor(String skill, String upgradeId) {
      return switch (skill) {
         case "mining" -> {
            switch (upgradeId) {
               case "haste":
                  yield Items.SUGAR;
               case "durability":
                  yield Items.ANVIL;
               case "fortune":
                  yield Items.RAW_GOLD;
               case "autosmelt":
                  yield Items.FURNACE;
               case "prospector":
                  yield Items.EXPERIENCE_BOTTLE;
               case "excavator":
                  yield Items.DIAMOND_PICKAXE;
               case "cavesight":
                  yield Items.LANTERN;
               case "gemsmith":
                  yield Items.EMERALD;
               default:
                  yield Items.BOOK;
            }
         }
         case "combat" -> {
            switch (upgradeId) {
               case "damage":
                  yield Items.DIAMOND_SWORD;
               case "lifesteal":
                  yield Items.APPLE;
               case "tank":
                  yield Items.SHIELD;
               case "execute":
                  yield Items.IRON_AXE;
               case "bloodthirst":
                  yield Items.REDSTONE;
               case "absorb":
                  yield Items.GOLDEN_APPLE;
               case "bloodiron":
                  yield Items.IRON_CHESTPLATE;
               case "warcry":
                  yield Items.GOAT_HORN;
               default:
                  yield Items.BOOK;
            }
         }
         case "farming" -> {
            switch (upgradeId) {
               case "growth":
                  yield Items.WHEAT;
               case "replant":
                  yield Items.WHEAT_SEEDS;
               case "harvest":
                  yield Items.GOLDEN_HOE;
               case "reap":
                  yield Items.EXPERIENCE_BOTTLE;
               case "seedbank":
                  yield Items.BEETROOT_SEEDS;
               case "vigor":
                  yield Items.SUGAR;
               case "compost":
                  yield Items.BONE_MEAL;
               default:
                  yield Items.BOOK;
            }
         }
         case "trading" -> {
            switch (upgradeId) {
               case "commission":
                  yield Items.GOLD_INGOT;
               case "barter":
                  yield Items.EMERALD;
               case "silver":
                  yield Items.VILLAGER_SPAWN_EGG;
               case "salesman":
                  yield Items.PAPER;
               case "tip":
                  yield Items.GOLD_NUGGET;
               case "schmoozer":
                  yield Items.EXPERIENCE_BOTTLE;
               case "reputation":
                  yield Items.NAME_TAG;
               default:
                  yield Items.BOOK;
            }
         }
         case "foraging" -> {
            switch (upgradeId) {
               case "lumberjack":
                  yield Items.OAK_LOG;
               case "timber":
                  yield Items.EXPERIENCE_BOTTLE;
               case "bark":
                  yield Items.SUGAR;
               case "leafy":
                  yield Items.OAK_LEAVES;
               case "arboreal":
                  yield Items.LANTERN;
               case "grove":
                  yield Items.OAK_SAPLING;
               case "charcoal":
                  yield Items.CHARCOAL;
               default:
                  yield Items.BOOK;
            }
         }
         case "fishing" -> {
            switch (upgradeId) {
               case "angler":
                  yield Items.EXPERIENCE_BOTTLE;
               case "big_catch":
                  yield Items.COD;
               case "treasure":
                  yield Items.NAUTILUS_SHELL;
               case "bait_saver":
                  yield Items.STRING;
               case "deep_sea":
                  yield Items.PRISMARINE_CRYSTALS;
               default:
                  yield Items.FISHING_ROD;
            }
         }
         case "enchanting" -> {
            switch (upgradeId) {
               case "arcane":
                  yield Items.EXPERIENCE_BOTTLE;
               case "insight":
                  yield Items.ENCHANTED_BOOK;
               case "soulbind":
                  yield Items.HEART_OF_THE_SEA;
               case "runed":
                  yield Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
               case "reclaim":
                  yield Items.GRINDSTONE;
               case "owl":
                  yield Items.ALLAY_SPAWN_EGG;
               case "lucky_enchant":
                  yield Items.LAPIS_LAZULI;
               case "tome_saver":
                  yield Items.BOOK;
               case "enchanter_aura":
                  yield Items.CRYING_OBSIDIAN;
               case "mender":
                  yield Items.GRINDSTONE;
               default:
                  yield Items.BOOK;
            }
         }
         default -> Items.BOOK;
      };
   }

   private ItemStack card(String skill, UUID uuid) {
      int level = SkillManager.level(uuid, skill);
      boolean maxed = SkillManager.isMaxed(uuid, skill);
      int unspent = SkillManager.unspentPoints(uuid, skill);
      ItemStack stack = new ItemStack(SkillManager.cardItem(skill));
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(
            "§b§l" + SkillManager.displayName(skill) + " §7- Level §f" + level + "§7/§f" + SkillManager.MAX_LEVEL + (maxed ? " §6✔" : "")
         )
      );
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(SkillManager.bar(uuid, skill) + " §f" + SkillManager.xpPercent(uuid, skill) + "%"));
      if (maxed) {
         lore.add(Component.literal("§6Tree complete!"));
         lore.add(Component.literal("§7This tree takes no more XP and pays no more points."));
      } else {
         lore.add(Component.literal("§7XP: §f" + SkillManager.xpIntoLevel(uuid, skill) + "§8/§f" + SkillManager.xpNeeded(uuid, skill) + " §7to level §f" + (level + 1)));
      }

      lore.add(Component.literal(unspent > 0 ? "§b" + unspent + " point(s) to spend here" : "§8No unspent points in this tree"));
      String bonuses = SkillManager.activeBonuses(uuid, skill);
      if (!bonuses.isEmpty()) {
         lore.add(Component.literal("§8" + bonuses));
      }

      lore.add(Component.literal("§7Click to open the " + SkillManager.displayName(skill) + " tree"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
      } else if (slotId == CLOSE) {
         this.returnCarried(sp);
         sp.closeContainer();
      } else if (this.view == null && slotId == RESPEC) {
         String err = SkillManager.refundAll(sp);
         if (err != null) {
            SoundUtil.play(sp, ModSounds.DENY);
            Chat.msg(sp, "&c" + err);
         } else {
            SoundUtil.play(sp, ModSounds.MYSTERY);
         }

         this.rebuild();
         this.broadcastChanges();
         this.returnCarried(sp);
      } else {
         if (this.view == null) {
            for (int i = 0; i < CARDS.length && i < SkillManager.SKILLS.length; i++) {
               if (slotId == CARDS[i]) {
                  this.view = SkillManager.SKILLS[i];
                  SoundUtil.play(sp, ModSounds.PAGE_FLIP);
                  this.rebuild();
                  this.broadcastChanges();
                  this.returnCarried(sp);
                  return;
               }
            }
         } else {
            if (slotId == BACK) {
               this.view = null;
               SoundUtil.play(sp, ModSounds.PAGE_FLIP);
               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(sp);
               return;
            }

            if (slotId == REFUND_TREE) {
               String err = SkillManager.refundSkill(sp, this.view);
               if (err != null) {
                  SoundUtil.play(sp, ModSounds.DENY);
                  Chat.msg(sp, "&c" + err);
               } else {
                  SoundUtil.play(sp, ModSounds.MYSTERY);
               }

               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(sp);
               return;
            }

            String upgradeId = this.upgradeSlots.get(slotId);
            if (upgradeId != null) {
               String err = SkillManager.spend(sp, this.view, upgradeId);
               if (err != null) {
                  SoundUtil.play(sp, ModSounds.DENY);
                  Chat.msg(sp, "&c" + err);
               }

               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(sp);
               return;
            }
         }

         this.returnCarried(sp);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
