package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.WormholeManager;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.Arrays;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

public class WormholeMenu extends ChestMenu {
   private static final int PLAYER_START = 0;
   private static final int PLAYER_END = 17;
   private static final int LAST_DEATH = 18;
   private static final int RTP = 19;
   private static final int RESPAWN = 22;
   private static final int CLOSE = 26;
   private static final int WAYPOINT_1 = 27;
   private static final int WAYPOINT_2 = 28;
   private static final int WAYPOINT_3 = 29;
   private static final int CLEAR_WAYPOINTS = 30;
   private final SimpleContainer container = (SimpleContainer)this.getContainer();
   private final ServerPlayer player;

   public WormholeMenu(int syncId, Inventory playerInventory) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, new SimpleContainer(36), 4);
      this.player = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new WormholeMenu(syncId, inv), Component.literal("§5§l✧ Wormhole Portal ✧")));
   }

   private void rebuild() {
      this.container.clearContent();

      for (int slot : new int[]{20, 21, 23, 24, 25}) {
         this.container.setItem(slot, this.frame(slot % 2 == 0 ? (Item)Items.STAINED_GLASS_PANE.magenta() : (Item)Items.STAINED_GLASS_PANE.purple()));
      }

      List<ServerPlayer> others = this.player
         .level()
         .getServer()
         .getPlayerList()
         .getPlayers()
         .stream()
         .filter(p -> !p.getUUID().equals(this.player.getUUID()))
         .limit(18L)
         .toList();
      int i = 0;

      for (ServerPlayer other : others) {
         ItemStack head = new ItemStack(Items.PLAYER_HEAD);
         head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(other.getGameProfile()));
         head.set(DataComponents.CUSTOM_NAME, Component.literal("§d§l" + other.getName().getString()));
         head.set(
            DataComponents.LORE,
            new ItemLore(List.of(Component.literal("§7Tear a rift to this player."), Component.literal("§dClick to send a teleport request")))
         );
         head.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
         this.container.setItem(0 + i, head);
         i++;
      }

      if (others.isEmpty()) {
         this.container.setItem(4, this.named(Items.ENDER_EYE, "§8The rift is empty", "§7No other players are online to", "§7open a portal to."));
      }

      ItemStack death = this.named(Items.SKELETON_SKULL, "§c§l☠ LAST DEATH", "§7Warp to your last death location", "§8Costs 50% HP · consumes the potion");
      death.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      this.container.setItem(18, death);
      ItemStack rtp = this.named(Items.ENDER_PEARL, "§d§l⚡ RANDOM TELEPORT", "§7Warp to a random surface spot", "§8Within 500 blocks · consumes the potion");
      rtp.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      this.container.setItem(19, rtp);
      ItemStack respawn = this.named(Items.COMPASS, "§a§l✈ RESPAWN POINT", "§7Consume the potion and warp", "§8to your bed (or world spawn)");
      respawn.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      this.container.setItem(22, respawn);
      this.container.setItem(26, this.named(Items.BARRIER, "§cClose", "§7Keep the potion"));
      List<com.fortuneandfavors.economy.WormholeManager.Waypoint> wps = WormholeManager.waypointsOf(this.player);

      for (int w = 0; w < 3; w++) {
         int slot = 27 + w;
         if (w < wps.size()) {
            com.fortuneandfavors.economy.WormholeManager.Waypoint wp = wps.get(w);
            String dim = wp.dimension().identifier().toString().replace("minecraft:", "").replace("fortuneandfavors:", "");
            ItemStack item = this.named(
               Items.LODESTONE,
               "§d§l✦ WAYPOINT #" + (w + 1),
               "§7" + dim + " · " + wp.pos().getX() + ", " + wp.pos().getY() + ", " + wp.pos().getZ(),
               "§eLeft-click§7 to warp here (consumes the potion)",
               "§eRight-click§7 to remove this waypoint"
            );
            item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            this.container.setItem(slot, item);
         } else {
            this.container.setItem(slot, this.named(Items.BOOK, "§7Waypoint #" + (w + 1) + " §8(empty)", "§eLeft-click§7 to set a waypoint at", "§7your current location (free)"));
         }
      }

      this.container.setItem(30, this.named(Items.BARRIER, "§c✕ CLEAR ALL WAYPOINTS", "§7Removes every waypoint you've set"));

      for (int slot : new int[]{31, 32, 33, 34, 35}) {
         this.container.setItem(slot, this.frame(slot % 2 == 0 ? (Item)Items.STAINED_GLASS_PANE.magenta() : (Item)Items.STAINED_GLASS_PANE.purple()));
      }
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private ItemStack named(Item item, String name, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (lore.length > 0) {
         stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).<Component>map(s -> Component.literal(s)).toList()));
      }

      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp && slotId < 36) {
         this.returnCarried(sp);
         if (slotId >= 0 && slotId <= 17) {
            ItemStack stack = this.container.getItem(slotId);
            if (!stack.isEmpty()
               && stack.is(Items.PLAYER_HEAD)
               && stack.get(DataComponents.PROFILE) != null
               && ((ResolvableProfile)stack.get(DataComponents.PROFILE)).name().isPresent()) {
               ServerPlayer target = sp.level()
                  .getServer()
                  .getPlayerList()
                  .getPlayerByName((String)((ResolvableProfile)stack.get(DataComponents.PROFILE)).name().get());
               if (target != null) {
                  WormholeManager.request(sp, target);
                  sp.closeContainer();
               }
            }
         } else {
            switch (slotId) {
               case 18:
                  WormholeManager.toLastDeath(sp);
                  sp.closeContainer();
                  break;
               case 19:
                  WormholeManager.toRandomSurface(sp);
                  sp.closeContainer();
               case 20:
               case 21:
               case 23:
               case 24:
               case 25:
               default:
                  break;
               case 22:
                  WormholeManager.toRespawn(sp);
                  sp.closeContainer();
                  break;
               case 26:
                  sp.closeContainer();
                  break;
               case 27:
               case 28:
               case 29:
                  this.handleWaypointClick(sp, slotId - 27, button);
                  break;
               case 30:
                  WormholeManager.clearWaypoints(sp);
                  this.rebuild();
                  this.broadcastChanges();
                  break;
            }
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void handleWaypointClick(ServerPlayer sp, int index, int button) {
      boolean filled = WormholeManager.waypointAt(sp, index) != null;
      if (button == 1) {
         if (filled) {
            WormholeManager.removeWaypoint(sp, index);
            this.rebuild();
            this.broadcastChanges();
         }
      } else if (filled) {
         WormholeManager.toWaypoint(sp, index);
         sp.closeContainer();
      } else {
         WormholeManager.setWaypoint(sp, index);
         this.rebuild();
         this.broadcastChanges();
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }
}
