package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.duel.DuelManager.BotDifficulty;
import com.fortuneandfavors.duel.DuelManager.BotProfile;
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
import net.minecraft.world.item.component.ItemLore;

/**
 * The bot builder.
 *
 * <p>Four dials and a fight button. Left-click a dial to turn it up, right-click to
 * turn it down, and the numbers are shown the way a player thinks about them -
 * blocks a second, clicks a second - rather than as the raw fractions the record
 * stores.
 *
 * <p>Every value is clamped by {@link BotProfile} itself rather than here, so the
 * menu cannot reach past what a person can do even if a step is added carelessly
 * later. That ceiling is the whole point of the screen: a slider that goes past a
 * player is not a difficulty setting, it is a hack with a nicer label.
 */
public class CustomBotMenu extends ChestMenu {
   private static final int SLOTS = 9;
   private static final int SPEED = 0;
   private static final int STRAFE = 1;
   private static final int CPS = 2;
   private static final int SMART = 3;
   private static final int RESET = 4;
   private static final int RANDOM = 5;
   private static final int FIGHT = 8;

   /** One notch of each dial. Speed is per-tick, so 0.01 is a fifth of a block a second. */
   private static final double SPEED_STEP = 0.01;
   private static final double STRAFE_STEP = 0.05;
   private static final double CPS_STEP = 0.5;
   private static final double SMART_STEP = 0.05;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private BotProfile profile;

   private CustomBotMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.profile = DuelManager.customProfileOf(this.owner);
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new CustomBotMenu(syncId, inv, new SimpleContainer(SLOTS)), Component.literal("§6§lCustom Bot"))
      );
   }

   private void apply(BotProfile next) {
      this.profile = next;
      DuelManager.setCustomProfile(this.owner, next);
      this.rebuild();
   }

   /** Turns one dial by a single step, in the direction the mouse button asked for. */
   private void nudge(int slot, boolean up) {
      double d = up ? 1.0 : -1.0;
      BotProfile p = this.profile;
      switch (slot) {
         case SPEED -> this.apply(new BotProfile(p.speed() + d * SPEED_STEP, p.strafe(), p.cps(), p.smartness()));
         case STRAFE -> this.apply(new BotProfile(p.speed(), p.strafe() + d * STRAFE_STEP, p.cps(), p.smartness()));
         case CPS -> this.apply(new BotProfile(p.speed(), p.strafe(), p.cps() + d * CPS_STEP, p.smartness()));
         case SMART -> this.apply(new BotProfile(p.speed(), p.strafe(), p.cps(), p.smartness() + d * SMART_STEP));
         default -> {
         }
      }
   }

   private void rebuild() {
      this.dial(
         SPEED,
         Items.FEATHER,
         "§b§lSpeed",
         "§f" + this.blocksPerSecond(this.profile.speed()) + " §7blocks/s",
         "§7How fast its feet move. A sprinting player is about §f5.6§7."
      );
      this.dial(
         STRAFE,
         Items.SHIELD,
         "§a§lStrafing",
         "§f" + pct(this.profile.strafe()),
         "§7How much it circles you and steps out of your reach."
      );
      this.dial(
         CPS,
         Items.DIAMOND_SWORD,
         "§c§lClicks per second",
         "§f" + trim(this.profile.cps()) + " §7cps",
         "§7How often it swings. 1.9's cooldown still gates a real hit."
      );
      this.dial(
         SMART,
         Items.ENCHANTED_BOOK,
         "§d§lSmartness",
         "§f" + pct(this.profile.smartness()),
         "§7Reaction time, aim and arrow lead, head-turn speed,",
         "§7and how often it reaches for the clever answer."
      );
      this.container.setItem(RESET, this.button(Items.BARRIER, "§7§lReset to Normal", "§7Back to the Normal preset's dials."));
      this.container.setItem(RANDOM, this.button(Items.ENDER_EYE, "§e§lRandomise", "§7Roll a bot you would not have picked."));
      this.container.setItem(
         FIGHT,
         this.button(
            Items.NETHERITE_SWORD,
            "§6§lFight this bot",
            "§7" + this.profile.describe(),
            "§7Pick a mode next, like any other difficulty."
         )
      );
      this.broadcastChanges();
   }

   private void dial(int slot, Item item, String name, String value, String... lore) {
      String[] lines = new String[lore.length + 2];
      lines[0] = value;
      System.arraycopy(lore, 0, lines, 1, lore.length);
      lines[lines.length - 1] = "§8Left-click §7+ §8· Right-click §7-";
      this.container.setItem(slot, this.button(item, name, lines));
   }

   private ItemStack button(Item item, String name, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<Component> body = new java.util.ArrayList<>(lore.length);
      for (String line : lore) {
         body.add(Component.literal(line));
      }
      stack.set(DataComponents.LORE, new ItemLore(body));
      return stack;
   }

   /** A speed in ticks reads as blocks a second once it is multiplied by the tick rate. */
   private String blocksPerSecond(double speed) {
      return trim(speed * 20.0);
   }

   private static String pct(double v) {
      return Math.round(v * 100.0) + "%";
   }

   private static String trim(double v) {
      return String.format(java.util.Locale.ROOT, "%.1f", v);
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }

      switch (slotId) {
         case SPEED, STRAFE, CPS, SMART -> this.nudge(slotId, button == 0);
         case RESET -> this.apply(BotProfile.of(BotDifficulty.NORMAL));
         case RANDOM -> this.apply(randomProfile());
         case FIGHT -> {
            String err = DuelManager.challengeCustomBot(sp);
            sp.closeContainer();
            if (err != null) {
               com.fortuneandfavors.util.Chat.msg(sp, "&c" + err);
            } else {
               DuelModeMenu.open(sp);
            }
         }
         default -> {
            if (input == ContainerInput.QUICK_MOVE || input == ContainerInput.CLONE) {
               this.returnCarried(sp);
            } else {
               super.clicked(slotId, button, input, player);
            }
         }
      }
   }

   /**
    * A profile nobody would dial in by hand.
    *
    * <p>Randomised inside the same clamped range as the sliders, so the roll can
    * produce an odd bot but never an illegal one - and the speed and strafe draws
    * are deliberately allowed to disagree with the smartness draw, because "quick
    * but dumb" is one of the flavours worth finding.
    */
   private static BotProfile randomProfile() {
      double speed = BotProfile.MIN_SPEED + Math.random() * (BotProfile.MAX_SPEED - BotProfile.MIN_SPEED);
      double cps = BotProfile.MIN_CPS + Math.random() * (BotProfile.MAX_CPS - BotProfile.MIN_CPS);
      return new BotProfile(speed, Math.random(), cps, Math.random());
   }

   private void returnCarried(ServerPlayer sp) {
      if (!sp.containerMenu.getCarried().isEmpty()) {
         sp.drop(sp.containerMenu.getCarried(), false);
         sp.containerMenu.setCarried(ItemStack.EMPTY);
      }
   }
}
