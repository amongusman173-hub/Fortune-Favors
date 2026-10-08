package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.PlayerRaidManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
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
 * The Warlord's private offer - shown to ONE player during the raid's second
 * phase (multiplayer only). Choose to betray your friends and join the Warlord,
 * or deny and keep fighting. The offer only stands for {@link #DECISION_TICKS};
 * letting it expire (or closing the window without choosing) counts as a deny,
 * so the Warlord is never left waiting and the mechanic can't be dodged.
 */
public class BetrayalMenu extends ChestMenu {
   private static final int DECISION_TICKS = 240; // 12 seconds
   private static final int TITLE = 0;
   private static final int ACCEPT = 1;
   private static final int TIMER = 4;
   private static final int DENY = 7;
   private static final int INFO = 8;
   private static final Set<BetrayalMenu> OPEN = ConcurrentHashMap.newKeySet();
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private boolean decided = false;
   private int ticksLeft = DECISION_TICKS;
   private int lastShownSecond = -1;

   private BetrayalMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      OPEN.add(this);
      this.rebuild();
   }

   /** Active red flash bars, faded out a few ticks after the offer opens. */
   private static final Set<ServerBossEvent> FLASH_BARS = ConcurrentHashMap.newKeySet();
   private static int flashTicks = 0;

   private static void warnBossBarFlash(ServerPlayer player) {
      ServerBossEvent flash = new ServerBossEvent(UUID.randomUUID(),
         Component.literal("§c§l⚔ THE WARLORD SPEAKS TO YOU ⚔"),
         BossBarColor.RED, BossBarOverlay.PROGRESS);
      flash.setProgress(1.0F);
      flash.addPlayer(player);
      FLASH_BARS.add(flash);
   }

   public static void open(ServerPlayer player) {
      // Loud, close audio sting - impossible to miss.
      SoundUtil.play(player, SoundEvents.RAID_HORN.value(), 0.5F, 4.0F);
      SoundUtil.play(player, SoundEvents.WITHER_SPAWN, 1.2F, 2.0F);
      player.level().playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.HOSTILE, 1.5F, 0.5F);
      warnBossBarFlash(player);
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new BetrayalMenu(syncId, inv, new SimpleContainer(9)), Component.literal("§c§l⚔ THE WARLORD SPEAKS"))
      );
   }

   /** Advances the countdown on any open betrayal offer; auto-denies on expiry,
    *  death, logout, or when the offered player leaves the raid area. */
   public static void tickAll(MinecraftServer server) {
      // Fade the red "WARLORD SPEAKS" flash bars: 30 ticks full, then gone.
      flashTicks++;
      if (flashTicks >= 30) {
         flashTicks = 0;
         for (ServerBossEvent bar : FLASH_BARS) {
            bar.removeAllPlayers();
         }
         FLASH_BARS.clear();
      }
      for (BetrayalMenu m : OPEN) {
         if (m.decided) {
            continue;
         }
         // The offer lapses if the player dies, disconnects, or wanders out of
         // the raid - the Warlord won't be kept waiting by a fleeing target.
         if (!m.owner.isAlive()) {
            m.resolve(false);
            continue;
         }
         net.minecraft.core.BlockPos center = PlayerRaidManager.pendingBetrayalCenter(m.owner);
         if (center == null || m.owner.distanceToSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5) > 128.0 * 128.0) {
            m.resolve(false);
            continue;
         }
         m.ticksLeft--;
         int second = (m.ticksLeft + 19) / 20;
         if (second != m.lastShownSecond) {
            m.lastShownSecond = second;
            m.container.setItem(TIMER, m.timerStack(Math.max(0, second)));
            m.broadcastChanges();
         }
         if (m.ticksLeft <= 0) {
            m.resolve(false);
         }
      }
   }

   private static ItemStack acceptStack() {
      ItemStack s = new ItemStack((Item)Items.DYE.red());
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lHELP THE WARLORD"));
      s.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Join the Warlord and betray your friends."),
         Component.literal("§7Your health joins the boss bar. The raid only"),
         Component.literal("§7ends when YOU die. The raid mobs won't touch"),
         Component.literal("§7you - but your friends can still strike you down."),
         Component.literal("§8You'll be branded §cUntrustable§8 forever.")
      )));
      return s;
   }

   private static ItemStack denyStack() {
      ItemStack s = new ItemStack((Item)Items.DYE.green());
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lDENY & KEEP FIGHTING"));
      s.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Refuse the Warlord's offer."),
         Component.literal("§7He grows furious - dealing a bit more damage."),
         Component.literal("§7The raid continues as normal."),
         Component.literal("§8You'll be honoured as a §aWarlord's Defier§8.")
      )));
      return s;
   }

   private ItemStack timerStack(int seconds) {
      ItemStack s = new ItemStack(Items.CLOCK);
      s.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(seconds <= 0 ? "§c§lTIME'S UP!" : "§eThe Warlord awaits... §f" + seconds + "s")
      );
      s.set(
         DataComponents.LORE,
         new ItemLore(List.of(
            Component.literal("§7Decide before time runs out."),
            Component.literal("§7Dallying, dying, or fleeing the raid"),
            Component.literal("§7all count as a §cdeny§7.")
         ))
      );
      return s;
   }

   private static ItemStack infoStack() {
      ItemStack s = new ItemStack(Items.BOOK);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§fWhat's happening?"));
      s.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7The Warlord has seized you for a"),
         Component.literal("§7private audience. Everyone else in the"),
         Component.literal("§7raid is watching - and waiting."),
         Component.literal(""),
         Component.literal("§7§o\"There is always a price for loyalty...\"")
      )));
      return s;
   }

   private void rebuild() {
      for (int i = 0; i < 9; i++) {
         ItemStack filler = new ItemStack(Items.STAINED_GLASS_PANE.gray());
         filler.set(DataComponents.CUSTOM_NAME, Component.literal("§8 "));
         this.container.setItem(i, filler);
      }
      ItemStack title = new ItemStack(Items.WITHER_SKELETON_SKULL);
      title.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l⚔ THE WARLORD SPEAKS"));
      title.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7He offers you power... for a price."))));
      this.container.setItem(TITLE, title);
      this.container.setItem(ACCEPT, acceptStack());
      this.container.setItem(DENY, denyStack());
      this.container.setItem(INFO, infoStack());
      this.container.setItem(TIMER, this.timerStack((this.ticksLeft + 19) / 20));
      this.broadcastChanges();
   }

   private void resolve(boolean accept) {
      if (this.decided) {
         return;
      }
      this.decided = true;
      String err = PlayerRaidManager.onBetrayalChoice(this.owner, accept);
      if (err != null) {
         Chat.msg(this.owner, "&c" + err);
      }
      this.owner.closeContainer();
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId == ACCEPT) {
            this.resolve(true);
         } else if (slotId == DENY) {
            this.resolve(false);
         } else {
            super.clicked(slotId, button, input, sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public void removed(Player player) {
      OPEN.remove(this);
      // Closing the window without choosing still counts as a deny - the
      // Warlord's offer can't be dodged by just walking away from it.
      if (!this.decided && player instanceof ServerPlayer sp) {
         this.decided = true;
         PlayerRaidManager.onBetrayalChoice(sp, false);
      }
      super.removed(player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
