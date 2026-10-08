package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.FarmMachines;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
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
 * One window for the three farm machines.
 *
 * <p>They are the same machine in three costumes - a block beside a field, a radius and one thing it
 * does every second - so they get one window, and the window says the thing a player actually wants
 * to know: how far it reaches, whether it can find the chest it is supposed to work out of, and
 * which of those two is why nothing is happening. The range button draws the area it works, because
 * a radius printed as a number has never once told anybody whether their field is inside it.
 */
public class FarmMachineMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int SHOW = 20;
   private static final int RUN = 22;
   private static final int CLOSE = 26;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;
   private final String type;

   public FarmMachineMenu(int syncId, Inventory playerInventory, BlockPos pos, String type) {
      this(syncId, playerInventory, pos, type, new SimpleContainer(27));
   }

   private FarmMachineMenu(int syncId, Inventory playerInventory, BlockPos pos, String type, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.type = type;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos, String type) {
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new FarmMachineMenu(syncId, inv, pos, type),
         Component.literal("§a§l" + label(type))
      ));
   }

   private static String label(String type) {
      return switch (type) {
         case MachineManager.TYPE_AUTO_PLANTER -> "Auto Planter";
         case MachineManager.TYPE_AUTO_HARVESTER -> "Auto Harvester";
         default -> "Irrigation Sprinkler";
      };
   }

   private static int radius(String type) {
      return switch (type) {
         case MachineManager.TYPE_AUTO_PLANTER -> FarmMachines.PLANT_RADIUS;
         case MachineManager.TYPE_AUTO_HARVESTER -> FarmMachines.HARVEST_RADIUS;
         default -> FarmMachines.SPRINKLER_RADIUS;
      };
   }

   private ItemStack named(Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (!lore.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   private ServerLevel level() {
      return this.owner.level() instanceof ServerLevel server ? server : null;
   }

   private List<Container> chests() {
      ServerLevel level = this.level();
      UUID owner = MachineManager.ownerAt(this.owner.level(), this.pos);
      if (level == null) {
         return List.of();
      }
      return FarmMachines.containersFor(level, this.pos, owner == null ? this.owner.getUUID() : owner);
   }

   private void rebuild() {
      this.container.clearContent();
      int radius = radius(this.type);
      List<Container> chests = this.chests();
      int slots = 0;
      for (Container c : chests) {
         slots += c.getContainerSize();
      }
      List<Component> info = new ArrayList<>();
      info.add(Component.literal("§7Reach: §f" + radius + " blocks§7 in every direction."));
      switch (this.type) {
         case MachineManager.TYPE_AUTO_PLANTER -> {
            info.add(Component.literal("§7Sows seeds from the chests below"));
            info.add(Component.literal("§7onto bare farmland around it."));
         }
         case MachineManager.TYPE_AUTO_HARVESTER -> {
            info.add(Component.literal("§7Cuts every ripe crop in reach and"));
            info.add(Component.literal("§7sweeps the drops into the chests."));
         }
         default -> {
            info.add(Component.literal("§7Keeps farmland in reach at full moisture"));
            info.add(Component.literal("§7and pushes crops on a stage at a time."));
         }
      }
      info.add(Component.literal(""));
      info.add(Component.literal(chests.isEmpty()
         ? "§cNo container found§7 - build a chest within " + FarmMachines.CHEST_RADIUS + " blocks."
         : "§aWorking out of §f" + chests.size() + "§a container(s), §f" + slots + "§a slots."));
      this.container.setItem(INFO, this.named(icon(), "§a§l" + label(this.type), info));

      this.container.setItem(SHOW, this.named(
         Items.SPYGLASS, "§a§lShow the reach",
         List.of(Component.literal("§7Draws the square this machine works."))
      ));
      this.container.setItem(RUN, this.named(
         Items.CLOCK, "§a§lRun it now",
         List.of(Component.literal("§7One second of work, immediately."), Component.literal("§8It runs on its own anyway."))
      ));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose", List.of()));
   }

   private Item icon() {
      return switch (this.type) {
         case MachineManager.TYPE_AUTO_PLANTER -> Items.COMPOSTER;
         case MachineManager.TYPE_AUTO_HARVESTER -> Items.OBSERVER;
         default -> Items.CAULDRON;
      };
   }

   /** Draws the working square in the level, so the number becomes a place. */
   private void showReach(ServerPlayer sp) {
      ServerLevel level = this.level();
      if (level == null) {
         return;
      }
      int r = radius(this.type);
      double y = this.pos.getY() + 1.1;
      int sent = 0;
      for (int i = -r; i <= r; i++) {
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.pos.getX() + i + 0.5, y, this.pos.getZ() - r + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.pos.getX() + i + 0.5, y, this.pos.getZ() + r + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.pos.getX() - r + 0.5, y, this.pos.getZ() + i + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.pos.getX() + r + 0.5, y, this.pos.getZ() + i + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
         sent += 4;
      }
      Chat.msg(sp, "&aThe square this machine works is drawn - §f" + r + "&a blocks out, §f" + sent + "&a marks.");
   }

   /** Runs one second of the machine's own clock, so a player can watch it work. */
   private void runNow(ServerPlayer sp) {
      ServerLevel level = this.level();
      if (level == null) {
         return;
      }
      UUID owner = MachineManager.ownerAt(level, this.pos);
      UUID who = owner == null ? sp.getUUID() : owner;
      switch (this.type) {
         case MachineManager.TYPE_AUTO_PLANTER -> FarmMachines.tickPlanter(level, this.pos, who);
         case MachineManager.TYPE_AUTO_HARVESTER -> FarmMachines.tickHarvester(level, this.pos, who);
         default -> FarmMachines.tickSprinkler(level, this.pos, who);
      }
      Chat.msg(sp, "&aOne second of the " + label(this.type).toLowerCase() + " just ran.");
   }

   @Override
   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId >= 27) {
         return;
      }
      if (slotId == SHOW) {
         this.showReach(sp);
      } else if (slotId == RUN) {
         this.runNow(sp);
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
