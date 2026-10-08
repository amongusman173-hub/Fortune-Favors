package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.PotionBelt;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Potion Belt's window: four flasks on the top row and a brewing stand's bay under them.
 *
 * <p>Click a flask and the draught goes down on the spot. Empty a flask and the bay is what fills it
 * again - a bottle, an ingredient and blaze powder - and the belt brews exactly the way the block it
 * copies brews, through the game's own {@link PotionBrewing}: the recipe book answers what mixes with
 * what, so water and nether wart become an awkward potion and awkward and sugar become Swiftness,
 * and this class never has to know a single recipe.
 *
 * <p>The last step is the belt's own trick: the moment a finished potion of one of the four kinds is
 * sitting in the bottle slot, the belt drinks it and that flask fills itself. So the loop is brew a
 * potion, watch the flask fill, drink it in a fight - and the bay is left holding the glass bottle
 * the brew came out of.
 *
 * <p>The bay's three slots are real slots, and they belong to the player rather than to the belt:
 * anything left in them when the window closes is handed back, because a bottle that disappears
 * into a window is a bug report, not a feature. The window is otherwise a panel - nothing moves in
 * or out of the flask row by shift-click, and the close button is where a carried stack goes.
 */
public class PotionBeltMenu extends ChestMenu {
   /** The flask row: one slot per draught, in {@link PotionBelt#NAMES} order. */
   private static final int INFO = 4;
   /** The bay: what is being brewed. */
   private static final int BOTTLE = 5;
   /** The bay: what it is brewed with. */
   private static final int INGREDIENT = 6;
   /** The bay: the blaze powder the brew burns. */
   private static final int FUEL = 7;
   private static final int CLOSE = 8;
   /** Cells of the progress bar drawn into the info pane's lore. */
   private static final int BAR_CELLS = 12;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final ItemStack belt;
   /** Ticks left of the brew in the bay; 0 when the bay is idle. */
   private int brewTicks;

   public PotionBeltMenu(int syncId, Inventory playerInventory, ItemStack belt) {
      this(syncId, playerInventory, belt, new SimpleContainer(9));
   }

   private PotionBeltMenu(int syncId, Inventory playerInventory, ItemStack belt, SimpleContainer container) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.belt = belt;
      this.rebuild();
   }

   public static void open(ServerPlayer player, ItemStack belt) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new PotionBeltMenu(syncId, inv, belt), Component.literal("§d§lPotion Belt")));
   }

   /**
    * Ticks the brewing bay of whoever is looking at one.
    *
    * <p>Called from the mod's player tick rather than from a block entity, because there is no block:
    * the belt brews where it is carried, which is the whole point of a belt. A throw here is caught
    * by the caller's own guard, and a window that fails to tick is a window rather than a server.
    */
   public static void tickOpen(ServerPlayer player) {
      if (player.containerMenu instanceof PotionBeltMenu menu) {
         menu.tick();
      }
   }

   /** True while the belt this window belongs to is still the thing in the player's hand. */
   private boolean beltHeld() {
      return this.owner.getMainHandItem() == this.belt || this.owner.getOffhandItem() == this.belt;
   }

   private static ItemStack named(Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (!lore.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   /** One line of the flask row: what it does, what is left in it, and when it comes back. */
   private void rebuild() {
      long now = this.owner.level().getGameTime();
      for (int i = 0; i < PotionBelt.FLASKS; i++) {
         int charges = PotionBelt.charges(this.belt, i);
         long cooling = PotionBelt.secondsLeft(this.belt, i, now);
         long shared = PotionBelt.sharedSecondsLeft(this.belt, now);
         List<Component> lore = new java.util.ArrayList<>();
         lore.add(Component.literal(PotionBelt.effectLine(i)));
         lore.add(Component.literal("§7Draughts: §f" + charges + "§7/§f" + PotionBelt.CHARGES));
         if (cooling > 0L) {
            lore.add(Component.literal("§8Settling: " + cooling + "s"));
         }
         if (shared > 0L) {
            lore.add(Component.literal("§8The belt is settling: " + shared + "s"));
         }
         lore.add(Component.literal(charges > 0 ? "§eClick to drink" : "§8Empty - brew one in the bay"));
         lore.add(Component.literal(PotionBelt.recipeLine(i)));
         this.container.setItem(i, named(charges > 0 ? Items.POTION : Items.GLASS_BOTTLE, "§d§l" + PotionBelt.NAMES[i], lore));
      }
      this.container.setItem(INFO, named(Items.BREWING_STAND, "§d§lPotion Belt", this.bayLore()));
      this.container.setItem(CLOSE, named(Items.BARRIER, "§cClose", List.of()));
   }

   /** What the bay is doing right now, said in the middle of the window. */
   private List<Component> bayLore() {
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Four draughts, drunk instantly from the belt."));
      lore.add(Component.literal("§7Each flask has its own cooldown, and the belt"));
      lore.add(Component.literal("§7itself settles between draughts."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§d§lThe bay: §r§7brew a potion and the belt drinks it."));
      if (this.brewTicks > 0) {
         lore.add(Component.literal("§7Brewing §8[§f" + this.bar() + "§8]"));
      } else if (this.container.getItem(BOTTLE).isEmpty()) {
         lore.add(Component.literal("§8Drop a §7Water Bottle §8in the middle slot"));
      } else if (this.container.getItem(INGREDIENT).isEmpty()) {
         lore.add(Component.literal("§8Drop an §7ingredient §8beside it"));
      } else if (this.container.getItem(FUEL).isEmpty()) {
         lore.add(Component.literal("§8The brew needs §7Blaze Powder §8to burn"));
      } else {
         lore.add(Component.literal("§8Those two do not mix."));
      }
      lore.add(Component.literal(PotionBelt.BASE_STEP));
      lore.add(Component.literal("§8\u2193 then finish it with a second ingredient"));
      lore.add(Component.literal("§7Click each flask for its recipe."));
      return lore;
   }

   /** The brew, as a bar of block characters. */
   private String bar() {
      int filled = BAR_CELLS - (int)Math.ceil(this.brewTicks / (double)PotionBelt.BREW_TICKS * BAR_CELLS);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < BAR_CELLS; i++) {
         sb.append(i < filled ? '\u2588' : '\u2591');
      }
      return sb.toString();
   }

   /**
    * One tick of the belt's own clock.
    *
    * <p>Three things can happen, in this order: a finished potion in the bay fills the flask it
    * belongs to; a brew in progress advances and lands; or a bottle and an ingredient that mix, with
    * fuel to burn, start a brew. The order matters - the pour is checked first, so a potion that has
    * just finished is drunk on the same tick it finished rather than sitting in the bay looking
    * inert.
    */
   public void tick() {
      if (this.owner.isRemoved()) {
         return;
      }
      if (!this.beltHeld()) {
         Chat.msg(this.owner, "&cHold the belt to use it.");
         this.owner.closeContainer();
         return;
      }
      boolean changed = false;
      int pours = PotionBelt.flaskForPotion(this.container.getItem(BOTTLE));
      if (pours >= 0) {
         if (PotionBelt.charges(this.belt, pours) < PotionBelt.CHARGES) {
            this.pour(pours);
            changed = true;
         }
      } else {
         changed = this.brewTick();
      }
      if (changed || this.owner.level().getGameTime() % 5L == 0L) {
         this.rebuild();
         this.broadcastChanges();
      }
   }

   /** The belt drinks the brew in the bottle slot, and the flask it belongs to comes back full. */
   private void pour(int flask) {
      String error = PotionBelt.pour(this.owner, this.belt, flask);
      if (error != null) {
         Chat.msg(this.owner, "&c" + error);
         return;
      }
      ItemStack bottle = this.container.getItem(BOTTLE);
      bottle.shrink(1);
      if (bottle.isEmpty()) {
         this.container.setItem(BOTTLE, new ItemStack(Items.GLASS_BOTTLE));
      }
      Chat.raw(
         this.owner,
         "&d&lTHE BELT DRINKS THE BREW. &r&7" + PotionBelt.NAMES[flask] + " flask full: &f"
            + PotionBelt.CHARGES + " draughts&7."
      );
   }

   /** One tick of the bay. Returns whether anything happened. */
   private boolean brewTick() {
      ItemStack bottle = this.container.getItem(BOTTLE);
      ItemStack ingredient = this.container.getItem(INGREDIENT);
      ItemStack fuel = this.container.getItem(FUEL);
      PotionBrewing brewing = this.owner.level().potionBrewing();
      if (this.brewTicks > 0) {
         // Taking the bottle or the ingredient out mid-brew ends it: the bay is the player's, and a
         // brew that survived its own ingredients being removed would be a brew nobody could stop.
         if (!brewing.hasMix(bottle, ingredient)) {
            this.brewTicks = 0;
            return true;
         }
         this.brewTicks--;
         if (this.brewTicks > 0) {
            return true;
         }
         ItemStack product = brewing.mix(ingredient, bottle);
         product.setCount(1);
         ingredient.shrink(1);
         fuel.shrink(1);
         bottle.shrink(1);
         if (bottle.isEmpty()) {
            this.container.setItem(BOTTLE, product);
         } else {
            // A stack of water bottles is brewed one at a time; this one's product is handed over
            // rather than dropped on the floor of a window that has no floor.
            InventoryHelper.giveOrDrop(this.owner, product);
         }
         Chat.msg(this.owner, "&aThe belt finishes its brew.");
         return true;
      }
      if (!bottle.isEmpty() && !ingredient.isEmpty() && !fuel.isEmpty() && brewing.hasMix(bottle, ingredient)) {
         this.brewTicks = PotionBelt.BREW_TICKS;
         Chat.msg(this.owner, "&7The belt starts brewing - it will drink the potion when it is done.");
         return true;
      }
      return false;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      // The player's own inventory, and the clicks that are not on a slot at all (a number-key
      // swap, a drop key, a click outside the window): all vanilla's business.
      if (slotId < 0 || slotId >= 9) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (!this.beltHeld()) {
         Chat.msg(sp, "&cHold the belt to use it.");
         sp.closeContainer();
         return;
      }
      if (slotId == CLOSE) {
         ItemStack carried = this.getCarried();
         if (!carried.isEmpty()) {
            this.setCarried(ItemStack.EMPTY);
            InventoryHelper.giveOrDrop(sp, carried);
         }
         sp.closeContainer();
         return;
      }
      if (slotId < PotionBelt.FLASKS) {
         String error = PotionBelt.drink(sp, this.belt, slotId);
         if (error != null) {
            Chat.msg(sp, "&c" + error);
         }
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      if (slotId == BOTTLE || slotId == INGREDIENT || slotId == FUEL) {
         // The bay's slots are real slots - a brewing stand's slots, with the player's own items in
         // them - so vanilla places and takes exactly as it would in a chest.
         super.clicked(slotId, button, input, player);
         this.tick();
         return;
      }
      // The info pane: a panel, so a carried stack is put back in the pack rather than eaten by it.
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(sp, carried);
      }
   }

   /**
    * The bay goes back to the player with the window.
    *
    * <p>Three real slots and a handful of real ingredients in them: closing the belt mid-brew has to
    * hand the bottle, the ingredient and the blaze powder back, or the belt is a hole in the pack.
    * The brew's own progress is not carried anywhere - a brewing stand keeps brewing because it is a
    * block with a clock; a belt that kept a half-finished brew inside an item's data would be a belt
    * that brews while it is in a chest.
    */
   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         for (int slot : new int[]{BOTTLE, INGREDIENT, FUEL}) {
            int count = this.container.getItem(slot).getCount();
            if (count <= 0) {
               continue;
            }
            ItemStack left = this.container.removeItem(slot, count);
            if (!left.isEmpty()) {
               InventoryHelper.giveOrDrop(sp, left);
            }
         }
         ItemStack carried = this.getCarried();
         if (!carried.isEmpty()) {
            this.setCarried(ItemStack.EMPTY);
            InventoryHelper.giveOrDrop(sp, carried);
         }
      }
      this.brewTicks = 0;
      super.removed(player);
   }

   /** The window is a panel, not a container: nothing moves in or out of it by shift-click. */
   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
