package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ChunkAnchor;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * The Chunk Anchor's window.
 *
 * <p>The machine is invisible by definition - it does something where the player is not - so the
 * window is the feature: which nine chunks are being held, whether they are loaded right now, what
 * the next anchor costs, and a button to buy one. Nothing in here is a real container: every click
 * is a command.
 *
 * <p>The three-by-three of chunk names in the middle is the map. It is laid out the way the chunks
 * are, because a player standing at the anchor is looking at a block and the boundary around it
 * needs to become a grid in their head before anything else in this window means much.
 */
public class ChunkAnchorMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int BUY = 22;
   private static final int PING = 24;
   private static final int CLOSE = 26;
   /** The nine slots the chunk map is drawn in, left to right, top to bottom. */
   private static final int[] MAP = {10, 11, 12, 19, 20, 21, 28, 29, 30};
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;

   public ChunkAnchorMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      this(syncId, playerInventory, pos, new SimpleContainer(27));
   }

   private ChunkAnchorMenu(int syncId, Inventory playerInventory, BlockPos pos, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos) {
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new ChunkAnchorMenu(syncId, inv, pos), Component.literal("§b§lChunk Anchor")
      ));
   }

   private ItemStack named(Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (!lore.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   /** The level this anchor stands in, or null if the window outlived the world. */
   private ServerLevel level() {
      return this.owner.level() instanceof ServerLevel server ? server : null;
   }

   private void rebuild() {
      this.container.clearContent();
      ServerLevel level = this.level();
      int held = ChunkAnchor.ownedCount(this.owner.getUUID());
      List<int[]> chunks = ChunkAnchor.heldChunkPositions(this.pos);
      int loaded = level == null ? 0 : ChunkAnchor.loadedNow(level, this.pos);
      int cx = this.pos.getX() >> 4;
      int cz = this.pos.getZ() >> 4;

      this.container.setItem(INFO, this.named(
         Items.LODESTONE,
         "§b§lChunk Anchor",
         List.of(
            Component.literal("§7Holding §f" + loaded + "§7/§f" + chunks.size() + "§7 chunks loaded."),
            Component.literal("§7This anchor is §f" + (loaded == chunks.size() ? "fully loaded" : "catching up") + "§7."),
            Component.literal(""),
            Component.literal("§7Anchors you hold: §f" + held + "§7/§f" + ChunkAnchor.PER_PLAYER),
            Component.literal("§8" + ChunkAnchor.priceNote(this.owner.getUUID()))
         )
      ));

      // The map: one square per chunk, named for its own coordinates, brighter for the anchor's own.
      for (int i = 0; i < MAP.length && i < chunks.size(); i++) {
         int[] c = chunks.get(i);
         boolean home = c[0] == cx && c[1] == cz;
         this.container.setItem(MAP[i], this.named(
            home ? Items.RESPAWN_ANCHOR : Items.PAPER,
            (home ? "§a" : "§7") + "Chunk " + c[0] + ", " + c[1],
            List.of(Component.literal(home ? "§7The anchor's own chunk." : "§8Held loaded by this anchor."))
         ));
      }

      boolean canBuy = held < ChunkAnchor.PER_PLAYER;
      long price = ChunkAnchor.priceFor(this.owner.getUUID());
      this.container.setItem(BUY, this.named(
         canBuy ? Items.LODESTONE : Items.DYE.gray(),
         canBuy ? "§a§lBuy another - §f" + Chat.moneyStr(price) : "§8Anchor limit reached",
         canBuy
            ? List.of(
               Component.literal("§7Puts anchor §f" + (held + 1) + " §7of §f" + ChunkAnchor.PER_PLAYER + " §7in your pack."),
               Component.literal("§8Each anchor you already hold raises the price."),
               Component.literal("§8Balance: §f" + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID())))
            )
            : List.of(Component.literal("§7Pick one of yours up to buy another."))
      ));

      this.container.setItem(PING, this.named(
         Items.ENDER_EYE,
         "§b§lShow the boundary",
         List.of(Component.literal("§7Draws the outline of the nine chunks"), Component.literal("§7this anchor is holding."))
      ));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose", List.of()));
   }

   @Override
   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId >= 27) {
         // The player's own inventory is not reachable from here - nothing in this window is a real
         // slot, and a click there is a misclick.
         return;
      }
      if (slotId == BUY) {
         String error = ChunkAnchor.buyAnother(sp);
         if (error != null) {
            Chat.msg(sp, "&c" + error);
         }
      } else if (slotId == PING) {
         ServerLevel level = this.level();
         if (level != null) {
            ChunkAnchor.vfx(level, this.pos);
            Chat.msg(sp, "&bThe boundary is drawn where the anchor is standing.");
         }
      } else if (slotId == CLOSE) {
         sp.closeContainer();
         return;
      }
      this.rebuild();
      this.broadcastChanges();
   }

   @Override
   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
