package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.PermissionManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * Claims overview GUI: list every claim you own with one-click management
 * (opens ClaimPermsMenu), plus quick actions for the claimer item, claim
 * loot vault, claim slots, border mode and backup recovery.
 */
public class ClaimMenu extends ChestMenu {
   private static final int CLAIM_LOOT = 9;
   private static final int GET_CLAIMER = 10;
   private static final int CLAIM_SLOTS = 11;
   private static final int BORDERS = 12;
   private static final int ALL_CLAIMS = 13;
   private static final int RECOVER = 14;
   private static final int INFO = 4;
   private static final int LIST_START = 18;
   private static final int LIST_END = 44;
   private static final int CURRENT_CLAIM = 46;
   private static final int BACK_TO_MENU = 48;
   private static final int CLOSE = 49;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ClaimMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private ClaimMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ClaimMenu(syncId, inv), Component.literal("§6§lClaims & Land")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.yellow());
      UUID id = this.owner.getUUID();

      ItemStack title = new ItemStack(Items.GOLDEN_SHOVEL);
      title.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lClaims & Land"));
      title.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Owned claims: §f" + ClaimManager.usedClaims(id) + "§7 / §f" + ClaimManager.maxClaims(id)),
               Component.literal("§7Click a claim below to manage its permissions.")
            )
         )
      );
      this.container.setItem(INFO, title);

      // ---- Quick actions -------------------------------------------------
      this.container.setItem(
         CLAIM_LOOT,
         this.named(
            Items.CHEST,
            "§b§lClaim Loot",
            "§7Collect pending items - auction winnings,",
            "§7chest-shop payouts and boss loot all land here.",
            "§8Click to collect"
         )
      );
      ItemStack claimer = new ItemStack(Items.GOLDEN_SHOVEL);
      claimer.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lChunk Claimer"));
      claimer.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click once to claim the chunk you're in"),
               Component.literal("§7(§f" + Chat.moneyStr(ClaimManager.CHUNK_CLAIMER_PRICE) + "§7/chunk)."),
               Component.literal(ModItems.hasChunkClaimer(this.owner) ? "§8You already carry one" : "§8Click to receive one")
            )
         )
      );
      this.container.setItem(GET_CLAIMER, claimer);

      long slotCost = ClaimManager.slotUpgradeCost(id);
      ItemStack slots = new ItemStack(Items.NETHER_STAR);
      boolean maxed = ClaimManager.maxClaims(id) >= 24;
      slots.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lClaim Slots §7(" + ClaimManager.usedClaims(id) + "/" + ClaimManager.maxClaims(id) + ")"));
      slots.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(maxed ? "§7You own the maximum 24 claims." : "§7Buy one more slot for §f" + Chat.moneyStr(slotCost) + "§7."),
               Component.literal("§7Each slot costs more than the last."),
               Component.literal(maxed ? "§8Maxed out" : "§8Click to buy a slot")
            )
         )
      );
      this.container.setItem(CLAIM_SLOTS, slots);

      int bm = ClaimManager.borderMode(id);
      String bName = bm == 2 ? "Off" : (bm == 1 ? "Small" : "Full");
      ItemStack borders = new ItemStack(Items.END_ROD);
      borders.set(DataComponents.CUSTOM_NAME, Component.literal((bm == 2 ? "§8§l" : "§a§l") + "Claim border: " + bName));
      borders.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Full · bright outline · Small · faint outline"),
               Component.literal("§7Off · no border at all"),
               Component.literal("§8Click to cycle")
            )
         )
      );
      this.container.setItem(BORDERS, borders);

      this.container.setItem(
         ALL_CLAIMS,
         this.named(
            Items.WRITABLE_BOOK,
            "§e§lManage ALL claims",
            "§7One screen for your whole base policy:",
            "§7every permission toggle applies to all",
            "§7of your claims at once.",
            "§8Click to open"
         )
      );

      boolean canRecover = ClaimManager.isAdminOp(this.owner) || PermissionManager.isEconomyAdmin(id);
      ItemStack recover = new ItemStack(canRecover ? Items.COMPASS : Items.BARRIER);
      recover.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(canRecover ? "§c§lRecover claims" : "§8§lRecover claims §7(OP only)")
      );
      recover.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Pull claims back from the automatic"),
               Component.literal("§7backups if the save ever gets wiped."),
               Component.literal(canRecover ? "§8Click to recover" : "§8Requires operator permissions")
            )
         )
      );
      this.container.setItem(RECOVER, recover);

      // ---- Claim list ----------------------------------------------------
      List<Claim> mine = new ArrayList<>(ClaimManager.claimsOf(id));
      mine.sort(Comparator.comparing((Claim c) -> c.dimension).thenComparingInt(c -> c.minX).thenComparingInt(c -> c.minZ));
      int slot = LIST_START;
      for (Claim c : mine) {
         if (slot > LIST_END) {
            break;
         }
         ItemStack row = new ItemStack(Items.PAPER);
         String dim = c.dimension.replace("minecraft:", "").replace("fortuneandfavors:", "");
         row.set(DataComponents.CUSTOM_NAME, Component.literal("§eClaim @ §f" + c.minX + ", " + c.minZ + " §7(" + dim + ")"));
         row.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Area: §f" + (c.maxX - c.minX + 1) + "×" + (c.maxZ - c.minZ + 1) + "§7 · §f" + c.blocks() + "§7 blocks"),
                  Component.literal("§7Admins: §f" + c.adminNames.size()),
                  Component.literal("§8Click to manage permissions")
               )
            )
         );
         this.container.setItem(slot, row);
         slot++;
      }
      if (mine.isEmpty()) {
         ItemStack empty = new ItemStack(Items.DYE.gray());
         empty.set(DataComponents.CUSTOM_NAME, Component.literal("§7No claims yet"));
         empty.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Get a Chunk Claimer (button above) and"),
                  Component.literal("§7right-click the ground to claim a chunk."),
                  Component.literal("§7You can own up to §f" + ClaimManager.maxClaims(id) + "§7 claims.")
               )
            )
         );
         this.container.setItem(LIST_START, empty);
      }

      // ---- Current claim -------------------------------------------------
      Claim here = ClaimManager.claimAt(this.owner);
      if (here != null) {
         ItemStack cur = new ItemStack(Items.STONE_BRICKS);
         boolean mine2 = here.owner.equals(id);
         cur.set(DataComponents.CUSTOM_NAME, Component.literal(mine2 ? "§a§lYou're standing in your claim" : "§c§lIn " + here.ownerName + "'s claim"));
         cur.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Chunk at §f" + here.minX + ", " + here.minZ),
                  Component.literal(mine2 ? "§8Click to manage this claim" : "§7You can't edit someone else's claim.")
               )
            )
         );
         this.container.setItem(CURRENT_CLAIM, cur);
      }

      this.container.setItem(BACK_TO_MENU, this.named(Items.ARROW, "§e§lBACK", "§7Return to the /menu hub"));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
   }

   private ItemStack named(Item item, String name, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (lore.length > 0) {
         stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(String.join("\n", lore)))));
      }
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
         if (slotId >= 54) {
            super.clicked(slotId, button, input, player);
            return;
         }
         this.returnCarried(sp);
         switch (slotId) {
            case CLAIM_LOOT -> {
               int claimed = EconomyManager.claimItems(sp);
               if (claimed > 0) {
                  Chat.raw(sp, "§aCollected §f" + claimed + "§a pending items.");
               } else {
                  Chat.msg(sp, "&7No pending items to collect (auction winnings, chest-shop payouts and boss loot land here).");
               }
            }
            case GET_CLAIMER -> {
               if (!ModItems.hasChunkClaimer(sp)) {
                  InventoryHelper.giveOrDrop(sp, ModItems.chunkClaimer());
                  Chat.raw(sp, "&aYou received a &fChunk Claimer&a! Right-click the ground to claim that chunk.");
               } else {
                  Chat.msg(sp, "&7You already have a Chunk Claimer. Right-click the ground to claim the chunk you're standing in.");
               }
            }
            case CLAIM_SLOTS -> ClaimManager.buySlot(sp);
            case BORDERS -> {
               int mode = ClaimManager.toggleBorders(sp);
               Chat.raw(sp, "&7Claim border: &f" + (mode == 2 ? "Off" : (mode == 1 ? "Small" : "Full")));
            }
            case ALL_CLAIMS -> {
               Claim first = null;
               List<Claim> mine = ClaimManager.claimsOf(sp.getUUID());
               if (!mine.isEmpty()) {
                  first = mine.get(0);
               }
               if (first != null) {
                  sp.closeContainer();
                  ClaimPermsMenu.openAll(sp, first);
                  return;
               }
               Chat.msg(sp, "&7You don't own any claims yet - get a Chunk Claimer first.");
            }
            case RECOVER -> {
               if (!(ClaimManager.isAdminOp(sp) || PermissionManager.isEconomyAdmin(sp.getUUID()))) {
                  Chat.msg(sp, "&cOnly operators can recover claims from backups.");
                  break;
               }
               // Preview-and-confirm screen, identical to /claim restore and
               // /ff restore claims - recovery is never a blind one-shot.
               sp.closeContainer();
               com.fortuneandfavors.menu.RecoveryMenu.open(sp, com.fortuneandfavors.menu.RecoveryMenu.Kind.CLAIMS);
            }
            case BACK_TO_MENU -> {
               com.fortuneandfavors.menu.MenuHubMenu.open(sp);
               return;
            }
            case CLOSE -> {
               sp.closeContainer();
               return;
            }
            default -> {
               if (slotId >= LIST_START && slotId <= LIST_END) {
                  List<Claim> mine = new ArrayList<>(ClaimManager.claimsOf(sp.getUUID()));
                  mine.sort(Comparator.comparing((Claim c) -> c.dimension).thenComparingInt(c -> c.minX).thenComparingInt(c -> c.minZ));
                  int index = slotId - LIST_START;
                  if (index >= 0 && index < mine.size()) {
                     sp.closeContainer();
                     ClaimPermsMenu.open(sp, mine.get(index));
                     return;
                  }
               }
            }
         }
         this.rebuild();
         this.broadcastChanges();
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
