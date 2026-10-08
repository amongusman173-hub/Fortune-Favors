package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.PrisonManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The board at the mouth of the Pit: the five fights, one tile each.
 *
 * <p>The old arena had one door and one fight, so a prisoner never chose anything - the tile that
 * took you there said "Combat Zone" and that was the whole decision. The Pit has five shapes of
 * fight with different lengths, different payouts and different rewards, and a menu that lists them
 * is what lets a player decide whether they have time for a ten-round Gauntlet or only one Warden.
 *
 * <p>Each tile is described by the same three numbers the fight itself is built from - rounds,
 * payout per round, and the tokens the whole run pays - so a sixth mode appears here without a new
 * card being drawn by hand.
 */
public class PitMenu extends ChestMenu {
   private static final int INFO = 4;
   /** One tile per mode, left to right, in the order the enum declares them. */
   private static final int MODES = 10;
   private static final int BACK = 22;
   private static final int CLOSE = 16;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public PitMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private PitMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new PitMenu(syncId, inv), Component.literal("§c§lThe Prison Pit")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.red());

      PrisonManager.PitMode live = PrisonManager.pitModeOf(this.owner.getUUID());
      this.container.setItem(INFO, this.infoCard(live));

      PrisonManager.PitMode[] modes = PrisonManager.PitMode.values();
      for (int i = 0; i < modes.length; i++) {
         this.container.setItem(MODES + i, this.modeCard(modes[i], live));
      }

      this.container.setItem(BACK, this.named(new ItemStack(Items.ARROW), "§7← Back to the Cell Block"));
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack infoCard(PrisonManager.PitMode live) {
      ItemStack info = new ItemStack(Items.IRON_SWORD);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lThe Prison Pit"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Five fights, one floor. Each pays §ecash§7,"));
      lore.add(Component.literal("§7§dtokens§7, and the whole run pays a bonus."));
      if (live != null) {
         lore.add(Component.literal(""));
         lore.add(Component.literal(
            "§cYou are in a fight: §f" + live.label + " §7round §f" + PrisonManager.pitRoundOf(this.owner.getUUID())
               + "§7/§f" + live.rounds + "§7."
         ));
         lore.add(Component.literal("§8Finish it, or walk out of the Pit to abandon it."));
      } else {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§7Leaving the Pit mid-run abandons it - no payout."));
      }
      lore.add(Component.literal(""));
      // The Pit has a night of its own, and the board at its mouth had better say so.
      // The block's clock, not the level's game time - see PrisonMenu: the Pit pays off the event
      // that is actually running, and this line has to name that one.
      lore.add(Component.literal(com.fortuneandfavors.economy.PrisonEvents.statusLine(
         com.fortuneandfavors.economy.PrisonCellblock.clock(this.owner.level())
      )));
      lore.add(Component.literal("§8Fight tickets pay at the Processing pad's rate;"));
      lore.add(Component.literal("§8the Champion's stone still has to be carried out."));
      info.set(DataComponents.LORE, new ItemLore(lore));
      return info;
   }

   private ItemStack modeCard(PrisonManager.PitMode mode, PrisonManager.PitMode live) {
      ItemStack card = new ItemStack(icon(mode));
      boolean running = live == mode;
      card.set(DataComponents.CUSTOM_NAME, Component.literal(
         (running ? "§a§l▶ " : colour(mode) + "§l") + mode.label.toUpperCase()
      ));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7" + mode.blurb + "."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Rounds: §f" + mode.rounds + "§7 · each pays §e$" + String.format("%,d", mode.cashFor(1))
         + "§7-" + "§e$" + String.format("%,d", mode.cashFor(mode.rounds))));
      lore.add(Component.literal("§7Tokens: §d" + mode.tokensPerRound + "§7 per round · §d" + mode.clearBonus() + "§7 for the run"));
      if (mode == PrisonManager.PitMode.BOUNTY) {
         int top = PrisonManager.topHeat(this.owner.level().getServer());
         double scale = PrisonManager.bountyScale(top);
         lore.add(Component.literal(String.format("§7Board scale today: §f%.2f×§7 (hottest record §c%d§7).", scale, top)));
      }
      if (mode == PrisonManager.PitMode.CHAMPION) {
         lore.add(Component.literal("§6This week: §f" + PrisonManager.championName() + "§6, and they keep the name."));
         lore.add(Component.literal("§7Beating them grants the §6" + PrisonManager.CHAMPION_TITLE + "§7 title."));
      }
      if (mode == PrisonManager.PitMode.WARDEN) {
         lore.add(Component.literal("§7The block's own Warden - the manhunt's man."));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal(running ? "§aRunning now." : "§7Click to walk in and start the fight."));
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   private static String colour(PrisonManager.PitMode mode) {
      return switch (mode) {
         case DUEL -> "§e";
         case GAUNTLET -> "§c";
         case BOUNTY -> "§6";
         case WARDEN -> "§4";
         case CHAMPION -> "§d";
      };
   }

   private static net.minecraft.world.item.Item icon(PrisonManager.PitMode mode) {
      return switch (mode) {
         case DUEL -> Items.IRON_SWORD;
         case GAUNTLET -> Items.SHIELD;
         case BOUNTY -> Items.NAME_TAG;
         case WARDEN -> Items.NETHERITE_AXE;
         case CHAMPION -> Items.NETHER_STAR;
      };
   }

   /**
    * Where the tiles are, for the self-test.
    *
    * <p>The five modes share one run of slots starting at {@link #MODES}, so a sixth mode would
    * silently land on the Back arrow if the row were ever too short. Pinning the start and the count
    * is what keeps that a fact about two numbers rather than a bug found in play.
    *
    * @return {first mode slot, number of mode slots, back, close}
    */
   public static int[] layoutForTest() {
      return new int[]{MODES, PrisonManager.PitMode.values().length, BACK, CLOSE};
   }

   private ItemStack named(ItemStack stack, String name) {
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
      if (player instanceof ServerPlayer sp) {
         PrisonManager.PitMode[] modes = PrisonManager.PitMode.values();
         if (slotId >= MODES && slotId < MODES + modes.length) {
            PrisonManager.PitMode mode = modes[slotId - MODES];
            sp.closeContainer();
            String err = PrisonManager.startPit(sp, mode);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               SoundUtil.play(sp, ModSounds.TRANSFER);
            }
            return;
         }
         if (slotId == BACK) {
            this.returnCarried(sp);
            PrisonMenu.open(sp);
            return;
         }
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         this.returnCarried(sp);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
