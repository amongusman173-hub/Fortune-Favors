package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.menu.ClaimPermsMenu.AddPlayerMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Consumer;
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
import java.util.HashSet;
import net.minecraft.server.MinecraftServer;

public class ClaimPermsMenu extends ChestMenu {
   private static final int TOGGLE_CHESTS = 0;
   private static final int TOGGLE_DROP = 1;
   private static final int TOGGLE_BUILD = 2;
   private static final int TOGGLE_BORDERS = 3;
   private static final int TOGGLE_PVP = 4;
   private static final int TOGGLE_EXPLOSIONS = 5;
   private static final int TOGGLE_FIRE = 6;
   private static final int TOGGLE_MOBS = 7;
   private static final int INFO = 8;
   private static final int LIST_START = 18;
   private static final int LIST_END = 35;
   private static final int ABANDON = 45;
   private static final int ADD_PLAYER = 46;
   private static final int BACK = 52;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final Claim claim;
   private final boolean allClaims;

   public ClaimPermsMenu(int syncId, Inventory playerInventory, Claim claim) {
      this(syncId, playerInventory, claim, new SimpleContainer(54), false);
   }

   private ClaimPermsMenu(int syncId, Inventory playerInventory, Claim claim, SimpleContainer container, boolean allClaims) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.claim = claim;
      this.allClaims = allClaims;
      this.rebuild();
   }

   public static void open(ServerPlayer player, Claim claim) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ClaimPermsMenu(syncId, inv, claim), Component.literal("§2§lClaim Permissions")));
   }

   public static void openAll(ServerPlayer player, Claim claim) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new ClaimPermsMenu(syncId, inv, claim, new SimpleContainer(54), true), Component.literal("§2§lAll Claims - Permissions")
         )
      );
   }

   private void rebuild() {
      this.container.clearContent();
      this.container.setItem(0, this.toggle(Items.CHEST, "Open chests", "Allow everyone to open chests and containers in this claim", this.claim.allowChests));
      this.container.setItem(1, this.toggle(Items.ENDER_PEARL, "Drop items", "Allow everyone to drop items in this claim", this.claim.allowDrop));
      this.container
         .setItem(2, this.toggle(Items.IRON_PICKAXE, "Build / break", "Allow everyone to place and break blocks in this claim", this.claim.allowBuild));
      int bm = ClaimManager.borderMode(this.owner.getUUID());
      String bName = bm == 2 ? "Off" : (bm == 1 ? "Small" : "Full");
      ItemStack borderBtn = new ItemStack(Items.END_ROD);
      borderBtn.set(DataComponents.CUSTOM_NAME, Component.literal((bm == 2 ? "§8§l" : "§a§l") + "Claim border: " + bName));
      borderBtn.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Full · bright white sparkle outline around your claims"),
               Component.literal("§7Small · faint outline so you still see where claims are"),
               Component.literal("§7Off · no border at all"),
               Component.literal("§8Click to cycle")
            )
         )
      );
      this.container.setItem(3, borderBtn);
      this.container.setItem(4, this.toggle(Items.IRON_SWORD, "PvP", "Allow players to fight each other inside this claim", this.claim.allowPvp));
      this.container.setItem(5, this.toggle(Items.TNT, "Explosions", "Allow creepers and explosions to damage this claim", this.claim.allowExplosions));
      this.container
         .setItem(
            6, this.toggle(Items.FLINT_AND_STEEL, "Fire spread", "Allow fire to spread and ignite new blocks inside this claim", this.claim.allowFireSpread)
         );
      this.container
         .setItem(7, this.toggle(Items.ZOMBIE_HEAD, "Hostile spawns", "Allow hostile mobs to spawn naturally inside this claim", this.claim.allowMobSpawns));
      this.container
         .setItem(
            45,
            this.named(
               Items.REDSTONE_BLOCK, "§c§lABANDON CLAIM", "§7Removes this claim, refunding half what it cost", "§8Click: then type /claim abandon confirm"
            )
         );
      ItemStack info = new ItemStack(Items.PAPER);
      int count = ClaimManager.claimsOf(this.owner.getUUID()).size();
      if (this.allClaims) {
         info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + this.claim.ownerName + "'s claims"));
         info.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Every toggle here applies to ALL §f" + count + "§7 claims"),
                  Component.literal("§7you own - one screen, whole base policy."),
                  Component.literal("§7Admins bypass every permission.")
               )
            )
         );
      } else {
         info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + this.claim.ownerName + "'s claim"));
         info.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal(
                     "§7Area: §f"
                        + (this.claim.maxX - this.claim.minX + 1)
                        + "x"
                        + (this.claim.maxZ - this.claim.minZ + 1)
                        + "§7 blocks · §f"
                        + this.claim.blocks()
                        + "§7 blocks"
                  ),
                  Component.literal("§7Admins bypass every permission.")
               )
            )
         );
      }

      this.container.setItem(8, info);
      this.container.setItem(46, this.named(Items.OAK_SIGN, "§a§lADD PLAYER", "§7Adds an online player as an admin", "§8Click: type their name to add"));
      List<Entry<UUID, String>> admins = new ArrayList<>(this.claim.adminNames.entrySet());
      admins.sort(Entry.comparingByValue());

      for (int i = 0; i < admins.size() && 18 + i <= 35; i++) {
         Entry<UUID, String> e = admins.get(i);
         ItemStack row = new ItemStack(Items.EMERALD);
         row.set(DataComponents.CUSTOM_NAME, Component.literal("§e" + e.getValue()));
         row.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Admin · full access in this claim"), Component.literal("§8Click to remove"))));
         this.container.setItem(18 + i, row);
      }

      this.container.setItem(52, this.named(Items.ARROW, "§e§lBACK", "§7Return to Claims & Land overview"));
      this.container.setItem(53, this.named(Items.BARRIER, "§cClose", ""));
   }

   private void flip(Consumer<Claim> change) {
      if (this.allClaims) {
         for (Claim c : ClaimManager.claimsOf(this.owner.getUUID())) {
            change.accept(c);
         }
      } else {
         change.accept(this.claim);
      }
   }

   private ItemStack toggle(Item item, String name, String lore, boolean enabled) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal((enabled ? "§a§l" : "§8§l") + name + (enabled ? " ✓" : "")));
      stack.set(
         DataComponents.LORE, new ItemLore(List.of(Component.literal("§7" + lore), Component.literal(enabled ? "§8Click to revoke" : "§8Click to allow")))
      );
      return stack;
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
         } else {
            switch (slotId) {
               case 0:
                  this.flip(c -> c.allowChests = !c.allowChests);
                  break;
               case 1:
                  this.flip(c -> c.allowDrop = !c.allowDrop);
                  break;
               case 2:
                  this.flip(c -> c.allowBuild = !c.allowBuild);
                  break;
               case 3:
                  ClaimManager.toggleBorders(sp);
                  break;
               case 4:
                  this.flip(c -> c.allowPvp = !c.allowPvp);
                  break;
               case 5:
                  this.flip(c -> c.allowExplosions = !c.allowExplosions);
                  break;
               case 6:
                  this.flip(c -> c.allowFireSpread = !c.allowFireSpread);
                  break;
               case 7:
                  this.flip(c -> c.allowMobSpawns = !c.allowMobSpawns);
                  break;
               case 45:
                  this.returnCarried(sp);
                  if (ClaimManager.abandon(sp)) {
                     sp.closeContainer();
                  } else {
                     this.rebuild();
                     this.broadcastChanges();
                  }

                  return;
               case 46:
                  this.returnCarried(sp);
                  this.openAddPlayer(sp);
                  return;
               case 52:
                  this.returnCarried(sp);
                  ClaimMenu.open(sp);
                  return;
               case 53:
                  this.returnCarried(sp);
                  sp.closeContainer();
                  return;
               default:
                  if (slotId >= 18 && slotId <= 35) {
                     this.removeAt(sp, slotId - 18);
                     this.rebuild();
                     this.broadcastChanges();
                     this.returnCarried(sp);
                     return;
                  }

                  this.returnCarried(sp);
                  return;
            }

            ClaimManager.save(sp.level().getServer());
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void removeAt(ServerPlayer player, int index) {
      List<Entry<UUID, String>> admins = new ArrayList<>(this.claim.adminNames.entrySet());
      admins.sort(Entry.comparingByValue());
      if (index >= 0 && index < admins.size()) {
         UUID id = admins.get(index).getKey();
         this.claim.admins.remove(id);
         this.claim.adminNames.remove(id);
         Chat.msg(player, "&aRemoved &f" + admins.get(index).getValue() + "&a from the claim.");
      }
   }

   private void openAddPlayer(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new AddPlayerMenu(syncId, inv, this.claim), Component.literal("§e§lAdd an admin")));
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }


    static class AddPlayerMenu extends AnvilPromptMenu {
       private final Claim claim;
    
       AddPlayerMenu(int syncId, Inventory playerInventory, Claim claim) {
          super(
             syncId,
             playerInventory,
             new ItemStack(Items.PAPER),
             helper("§eType a player name", new String[]{"§7Type the name of an online player", "§7to make them an admin of this claim."})
          );
          this.claim = claim;
          this.returnInputOnCancel = false;
       }
    
       protected boolean canAccept(String text) {
          return !text.isEmpty() && text.length() <= 16;
       }
    
       protected void renderResult(ItemStack result, String text) {
          result.set(DataComponents.CUSTOM_NAME, Component.literal(text.isEmpty() ? "§8Type a player name" : "§a§lAdd " + text + " as admin"));
       }
    
       protected void accept(ServerPlayer player, String text) {
          MinecraftServer server = player.level().getServer();
          ServerPlayer target = server.getPlayerList().getPlayerByName(text);
          if (target == null) {
             Chat.msg(player, "&cPlayer &f" + text + "&c is not online. Admins must be added while online.");
          } else {
             UUID id = target.getUUID();
             if (id.equals(this.claim.owner)) {
                Chat.msg(player, "&cYou already own this claim!");
             } else if (this.claim.adminNames.containsKey(id)) {
                Chat.msg(player, "&c" + target.getName().getString() + " is already an admin here.");
             } else {
                this.claim.admins.add(id);
                this.claim.adminNames.put(id, target.getName().getString());
                ClaimManager.save(server);
                Chat.raw(player, "&aAdded &f" + target.getName().getString() + "&a as an admin of your claim.");
                Chat.raw(target, "&eYou are now an admin of &f" + this.claim.ownerName + "&e's claim - you can build, open chests, and help out!");
             }
          }
       }
    
       protected void reopen(ServerPlayer player) {
          ClaimPermsMenu.open(player, this.claim);
       }
    }
}
