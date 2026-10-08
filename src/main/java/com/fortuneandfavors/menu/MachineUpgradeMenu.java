package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.MachineTuning;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.component.ItemLore;

/**
 * The window a Super Hopper and a Super Smelter are tuned from: five tiers, what each one costs, and
 * what it buys.
 *
 * <p>A tier is bought on the block rather than in the shop on purpose. The machine already stands
 * where it is useful - over the farm, beside the chest - and moving it to upgrade it would mean
 * breaking the one thing a player wants to keep. So the window lists the five tiers as themselves,
 * with the price of the <i>step</i> rather than the whole machine: a player who is on tier two and
 * clicks five pays for two-to-five only, and one who clicks back down is refunded nothing, which is
 * the rule stated in the header rather than a surprise.
 *
 * <p>Like the sorter's window this is a vanilla chest menu, so its clicks arrive at the server
 * already interpreted by the client. Only the top row is buttons; every slot below the controls is
 * inert, and anything left on the cursor is handed back before the window closes.
 */
public class MachineUpgradeMenu extends ChestMenu {
   private static final int INFO = 4;
   /** The five tiers, left to right, on the middle row. */
   private static final int TIER_FIRST = 10;
   private static final int TIER_LAST = 14;
   /** Takes the machine off the ground and back into the hand. */
   private static final int PICKUP = 22;
   private static final int CLOSE = 26;
   /** Where the player's own pack starts in a three-row chest window. */
   private static final int PACK_FIRST = 27;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;

   public MachineUpgradeMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      this(syncId, playerInventory, pos, new SimpleContainer(27));
   }

   private MachineUpgradeMenu(int syncId, Inventory playerInventory, BlockPos pos, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos) {
      String title = MachineManager.isSuperSmelter(player.level(), pos)
         ? "\u00a76\u00a7lSuper Smelter Tiers"
         : "\u00a7b\u00a7lSuper Hopper Tiers";
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new MachineUpgradeMenu(syncId, inv, pos), Component.literal(title)
      ));
   }

   /**
    * True while the machine this window was opened on is still there.
    *
    * <p>The window holds a position, not the block. A player who breaks the machine from another
    * window - or picks it up with a second client - must not be able to buy a tier for a block that
    * is gone, so every action asks this first, exactly as the sorter's window does.
    */
   private boolean alive() {
      return MachineManager.isSuperHopper(this.owner.level(), this.pos)
         || MachineManager.isSuperSmelter(this.owner.level(), this.pos);
   }

   private boolean smelter() {
      return MachineManager.isSuperSmelter(this.owner.level(), this.pos);
   }

   private String key() {
      return MachineManager.keyFor(this.owner.level(), this.pos);
   }

   private ItemStack named(Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (!lore.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   private ItemStack glint(ItemStack stack) {
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      return stack;
   }

   private void rebuild() {
      this.container.clearContent();
      if (!this.alive()) {
         return;
      }
      int tier = MachineTuning.tier(this.key());
      boolean smelter = this.smelter();
      List<Component> info = new ArrayList<>();
      info.add(Component.literal(smelter ? "\u00a76\u00a7lSuper Smelter" : "\u00a7b\u00a7lSuper Hopper"));
      info.add(Component.literal("\u00a77Tier: " + MachineTuning.tierName(tier) + "\u00a77 of \u00a7f"
         + MachineTuning.tierName(MachineTuning.MAX_TIER)));
      info.add(Component.literal(smelter ? MachineTuning.smelterSpeedLine(tier) : MachineTuning.hopperSpeedLine(tier)));
      if (smelter) {
         info.add(Component.literal(MachineTuning.smelterFuelLine(tier)));
      }
      info.add(Component.literal(""));
      info.add(Component.literal("\u00a77Balance: \u00a7f" + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID()))));
      info.add(Component.literal("\u00a7eClick a tier to buy every step up to it."));
      info.add(Component.literal("\u00a78Going down costs nothing and refunds nothing."));
      this.container.setItem(INFO, this.named(smelter ? Items.FURNACE : Items.HOPPER,
         (smelter ? "\u00a76\u00a7lSuper Smelter" : "\u00a7b\u00a7lSuper Hopper") + " \u00a77- tier "
            + MachineTuning.tierName(tier), info));

      for (int t = 1; t <= MachineTuning.MAX_TIER; t++) {
         boolean current = t == tier;
         long cost = MachineTuning.costTo(tier, t);
         boolean affordable = cost == 0L || EconomyManager.hasCash(this.owner.getUUID(), cost);
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal(smelter ? MachineTuning.smelterSpeedLine(t) : MachineTuning.hopperSpeedLine(t)));
         if (smelter) {
            lore.add(Component.literal(MachineTuning.smelterFuelLine(t)));
         }
         lore.add(Component.literal(""));
         if (current) {
            lore.add(Component.literal("\u00a7aThis is the tier it is on now."));
         } else if (cost == 0L) {
            lore.add(Component.literal("\u00a7eClick to step back down to it."));
         } else {
            lore.add(Component.literal("\u00a77Cost to buy up to it: \u00a7f" + Chat.moneyStr(cost)));
            lore.add(Component.literal(affordable ? "\u00a7eClick to buy." : "\u00a7cNot enough money."));
         }
         ItemStack icon = this.named(Items.PAPER, "Tier " + MachineTuning.tierName(t), lore);
         this.container.setItem(TIER_FIRST + (t - 1), current ? this.glint(icon) : icon);
      }

      this.container.setItem(PICKUP, this.named(
         Items.STICK,
         "\u00a7c\u00a7lPick the machine up",
         List.of(
            Component.literal("\u00a77Puts it back in your hand, tiers and all."),
            Component.literal("\u00a78A sneak-click opens this window, not a pickup.")
         )
      ));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "\u00a7cClose", List.of()));
   }

   private void returnCarried(ServerPlayer sp) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(sp, carried);
      }
   }

   /** Buys every step from the current tier up to {@code wanted}, or down when it is lower. */
   private void buy(ServerPlayer sp, int wanted) {
      int current = MachineTuning.tier(this.key());
      if (wanted == current) {
         Chat.msg(sp, "&7It is already on tier " + MachineTuning.tierName(wanted) + "&7.");
         return;
      }
      if (wanted < current) {
         MachineTuning.setTier(this.key(), wanted);
         Chat.msg(sp, "&7Stepped down to tier " + MachineTuning.tierName(wanted) + "&7. No refund - the upgrades are not resold.");
         return;
      }
      long cost = MachineTuning.costTo(current, wanted);
      if (!EconomyManager.hasCash(sp.getUUID(), cost)) {
         Chat.msg(sp, "&cThat is " + Chat.moneyStr(cost) + "&c and you have "
            + Chat.moneyStr(EconomyManager.balance(sp.getUUID())) + "&c.");
         SoundUtil.play(sp, ModSounds.DENY);
         return;
      }
      long step = MachineTuning.nextStepCost(current);
      EconomyManager.takeCash(sp.getUUID(), cost);
      MachineTuning.setTier(this.key(), wanted);
      Chat.msg(sp, "&aUpgraded to tier " + MachineTuning.tierName(wanted) + "&a for &f" + Chat.moneyStr(cost)
         + "&a (next step was " + (step < 0L ? "maxed" : Chat.moneyStr(step)) + "&a).");
      SoundUtil.play(sp, ModSounds.BUY);
   }

   @Override
   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (!this.alive()) {
         this.returnCarried(sp);
         Chat.msg(sp, "&cThat machine is gone.");
         sp.closeContainer();
         return;
      }
      if (slotId >= PACK_FIRST) {
         // Nothing can be put into the top of this window, so the player's own pack is left to
         // vanilla - a player tidying their inventory in a window they opened should not have it
         // answer "no".
         super.clicked(slotId, button, input, player);
         this.returnCarried(sp);
         return;
      }
      if (slotId == CLOSE) {
         this.returnCarried(sp);
         sp.closeContainer();
         return;
      }
      if (slotId == PICKUP) {
         this.returnCarried(sp);
         if (MachineManager.pickUp(sp, sp.level(), this.pos)) {
            sp.closeContainer();
         }
         return;
      }
      if (slotId >= TIER_FIRST && slotId <= TIER_LAST) {
         this.buy(sp, slotId - TIER_FIRST + 1);
      } else if (slotId == INFO) {
         Chat.msg(sp, "&7Tier " + MachineTuning.tierName(MachineTuning.tier(this.key())) + "&7. "
            + (this.smelter() ? "The smelter's tier is its speed and its fuel together." : "The hopper's tier is how much it moves a tick."));
      }
      this.returnCarried(sp);
      this.rebuild();
      this.broadcastChanges();
   }

   /** Nothing in this window is a container - shift-clicking into or out of it is refused. */
   @Override
   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
