package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.ExpeditionManager.Type;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.economy.ExpeditionProgression;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
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

public class ExpeditionMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int[] TYPE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 21};
   private static final int LEAVE = 22;
   private static final int CODEX = 23;
   /** The Expedition Compass, sold here because this is where the wait is announced. */
   private static final int COMPASS = 25;
   /** The party desk: who you are going in with, and whose bag you will be carrying. */
   private static final int PARTY = 34;
   /** The permanent shop: the desk a player is already at when they decide whether to go back in. */
   private static final int SHOP = 24;
   /**
    * The guide to the shop's survival tree, on the desk where a player decides whether to buy first.
    *
    * <p>A shop card can offer to spend EXP; it cannot explain why one of its lines is locked behind
    * another without becoming a wall of text. That question is what the guide answers, so it sits
    * beside the shop here rather than only inside the shop. See {@link ExpeditionGuideMenu}.
    */
   private static final int GUIDE = 19;
   private static final int CLOSE = 35;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ExpeditionMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private ExpeditionMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ExpeditionMenu(syncId, inv), Component.literal("§b§lExpeditions")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.blue());
      ItemStack info = new ItemStack(Items.COMPASS);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lExpeditions"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Descend into a dungeon of giant chambers."),
               Component.literal("§7Enter a room and its pack wakes - clear it"),
               Component.literal("§7and §a3 doors §7open onto the unknown. Choose one."),
               Component.literal("§7Escape alive to cash out what you secured."),
               Component.literal("§cDie or run out of time and you lose everything!"),
               Component.literal("§8The deeper you go, the more every chamber pays.")
            )
         )
      );
      this.container.setItem(INFO, info);
      Type[] types = Type.values();
      for (int i = 0; i < types.length; i++) {
         this.container.setItem(TYPE_SLOTS[i], this.typeCard(types[i]));
      }
      boolean inExpedition = ExpeditionManager.isInExpedition(this.owner.getUUID());
      ItemStack leave = new ItemStack(inExpedition ? Items.OAK_DOOR : Items.GLASS_PANE);
      leave.set(DataComponents.CUSTOM_NAME, Component.literal(inExpedition ? "§a§lLeave now (keep loot)" : "§8Not on an expedition"));
      leave.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(inExpedition ? "§7Walk out of the zone or click here to" : "§7Start an expedition above first."),
               Component.literal(inExpedition ? "§7cash out what you've secured." : "§7You keep loot only if you escape alive.")
            )
         )
      );
      this.container.setItem(LEAVE, leave);
      ItemStack codex = this.named(new ItemStack(Items.WRITABLE_BOOK), "§d§lExpedition Codex");
      codex.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Every dungeon and chamber this world"),
               Component.literal("§7has ever seen - and what they paid."),
               Component.literal("§8Click to read the codex.")
            )
         )
      );
      this.container.setItem(CODEX, codex);
      this.container.setItem(COMPASS, this.compassCard());
      this.container.setItem(SHOP, this.shopCard());
      this.container.setItem(GUIDE, this.guideCard());
      this.container.setItem(PARTY, this.partyCard());
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   /**
    * The Expedition Compass, at the desk where the wait is read out.
    *
    * <p>The card says whether there is a wait to erase right now, because that is the only question
    * a buyer has: a compass bought with nothing to use it on is a compass that sits in a chest until
    * the next fatal blow, and a player who has just lost a run should not have to remember that the
    * thing which answers it is for sale somewhere else.
    */
   private ItemStack compassCard() {
      ItemStack card = new ItemStack(Items.COMPASS);
      card.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lExpedition Compass"));
      long now = com.fortuneandfavors.economy.ServerClock.clock(this.owner.level());
      String wait = ExpeditionManager.fatalWaitText(this.owner.getUUID(), now);
      java.util.List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Erases the rest of a fatal-blow wait, so the"));
      lore.add(Component.literal("§7next descent does not have to wait on the last."));
      lore.add(Component.literal(wait == null
         ? "§8No wait on you right now - " + ExpeditionManager.FATAL_WAIT_MINUTES + "m after a fatal blow."
         : "§cYour wait: §f" + wait + "§c left."));
      lore.add(Component.literal("§7Price: §a" + Chat.moneyStr(ExpeditionManager.COMPASS_PRICE)));
      lore.add(Component.literal("§eClick to buy"));
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   /**
    * The permanent Expedition Shop, on the desk the player already visits.
    *
    * <p>The shop has its own command and a broker carries it into a run, but this is the window a
    * player is looking at while the question "should I go back in" is on the table - and the answer
    * is sometimes "buy a level first". The card reads their own ledger rather than pitching
    * generically, because the only number that decides that question is what is banked.
    */
   private ItemStack shopCard() {
      long banked = com.fortuneandfavors.economy.ExpeditionProgression.available(this.owner.getUUID());
      long earned = com.fortuneandfavors.economy.ExpeditionProgression.earned(this.owner.getUUID());
      ItemStack card = new ItemStack(Items.EXPERIENCE_BOTTLE);
      card.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lExpedition Shop"));
      card.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Spend Expedition EXP on permanent"),
         Component.literal("§7upgrades: speed, strength, one revive"),
         Component.literal("§7a run, more time, more pack room,"),
         Component.literal("§7and meeting a broker in a chamber."),
         Component.literal("§aBanked: §f" + banked + " EXP§8 · §7all-time §f" + earned),
         Component.literal("§eClick to open §8(/expedition shop)")
      )));
      return card;
   }

   /**
    * The guide card: the tree, explained in words, one click from the shop that draws it.
    *
    * <p>The shop already draws the branch under its root; what a picture cannot say is <i>why</i> the
    * branch is gated. A player who has just been told a line is locked is at the exact moment the
    * question forms, and this is the card that answers it without leaving the window.
    */
   private ItemStack guideCard() {
      ExpeditionProgression.Upgrade root = ExpeditionGuideMenu.guideRoot();
      ExpeditionProgression.Upgrade branch = ExpeditionGuideMenu.guideBranch();
      ItemStack book = new ItemStack(Items.KNOWLEDGE_BOOK);
      book.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lThe Survival Tree"));
      book.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7The shop's lines, and the one branch"),
         Component.literal("§7that hangs off another: §f" + root.name + "§7 opens"),
         Component.literal("§f" + branch.name + "§7 - and §fwhy§7 one is locked"),
         Component.literal("§7until the other is bought."),
         Component.literal("§eClick to read")
      )));
      return book;
   }

   /**
    * The party desk.
    *
    * <p>It reads the room rather than pitching the feature: a player who is already in a party is
    * told how many are in it and who is holding the door, and a player who is not is told what the
    * door is for. Either way the card names the count, because the number that decides whether this
    * is worth opening is how many other people have turned up.
    */
   private ItemStack partyCard() {
      com.fortuneandfavors.economy.PartyManager.Party party =
         com.fortuneandfavors.economy.PartyManager.partyOf(this.owner.getUUID());
      ItemStack card = new ItemStack(party != null && party.size() > 1 ? Items.GOLDEN_HELMET : Items.LEATHER_HELMET);
      card.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lExpedition Party"));
      java.util.List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Go in together: up to §f" + com.fortuneandfavors.economy.PartyManager.MAX_MEMBERS + "§7 of you"));
      lore.add(Component.literal("§7in one site, carrying §fone§7 bag between you."));
      lore.add(Component.literal("§7The pack is nine pieces for each name."));
      if (party == null) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§8You are not in a party."));
      } else {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§7Your party: §f" + party.size() + "§7/§f"
            + com.fortuneandfavors.economy.PartyManager.MAX_MEMBERS + "§7 · bag §f" + party.capacity()));
         lore.add(Component.literal(this.owner.getUUID().equals(party.host())
            ? "§6You hold the door."
            : "§eThe host decides when you go in."));
      }
      lore.add(Component.literal("§eClick to open the party window."));
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   private ItemStack typeCard(Type t) {
      ItemStack stack = new ItemStack(t.icon);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(t.color + "§l" + t.name));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + t.desc),
               Component.literal("§7Duration: §f" + (t.durationTicks / 20L / 60L) + " min"),
               Component.literal("§7Cash multiplier: §a" + t.multiplier + "x"),
               Component.literal("§7Danger: " + (t.danger.equals("Extreme") ? "§c" : t.danger.equals("High") ? "§6" : "§a") + t.danger),
               Component.literal(ExpeditionProgression.entryCost(t) <= 0L
                  ? "§7Unlock: §afree"
                  : "§7Unlock: §f" + ExpeditionProgression.entryCost(t) + " EXP §8(you have "
                     + ExpeditionProgression.available(this.owner.getUUID()) + ")"),
               Component.literal("§8Click to start")
            )
         )
      );
      return stack;
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
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         Type[] types = Type.values();
         for (int i = 0; i < types.length; i++) {
            if (slotId == TYPE_SLOTS[i]) {
               this.returnCarried(sp);
               String err = ExpeditionManager.start(sp, types[i]);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  SoundUtil.play(sp, ModSounds.DENY);
               } else {
                  sp.closeContainer();
               }
               return;
            }
         }
         if (slotId == LEAVE) {
            this.returnCarried(sp);
            if (ExpeditionManager.isInExpedition(sp.getUUID())) {
               ExpeditionManager.leave(sp);
               sp.closeContainer();
            }
            return;
         }
         if (slotId == CODEX) {
            this.returnCarried(sp);
            ExpeditionCodexMenu.open(sp);
            return;
         }
         if (slotId == SHOP) {
            this.returnCarried(sp);
            com.fortuneandfavors.menu.ExpeditionShopMenu.open(sp);
            return;
         }
         if (slotId == GUIDE) {
            this.returnCarried(sp);
            ExpeditionGuideMenu.open(sp);
            return;
         }
         if (slotId == PARTY) {
            this.returnCarried(sp);
            com.fortuneandfavors.menu.PartyMenu.open(sp);
            return;
         }
         if (slotId == COMPASS) {
            this.returnCarried(sp);
            String err = ExpeditionManager.buyExpeditionCompass(sp);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            }
            this.rebuild();
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
