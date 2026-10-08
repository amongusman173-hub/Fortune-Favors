package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.ExpeditionManager.Type;
import com.fortuneandfavors.economy.LootBackpack;
import com.fortuneandfavors.economy.PartyManager;
import com.fortuneandfavors.economy.PartyManager.Party;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
 * The party window: four rooms, a shared pack, and the host's hand on the door.
 *
 * <p>The top row is the party itself - one room per member, filled in the order people joined, with
 * the host in the first one. The host clicks a name to send that person out of the lobby; everybody
 * else reads the same row and can do nothing with it. The middle rows are the descents: the host
 * clicks one and the whole party goes in together, which is the only way a party run can start,
 * because the bag that a party carries is handed out when the group arrives.
 *
 * <p>With no party the window is a directory instead: form one, or click somebody's name to join
 * theirs. It is the same window either way, because the question - "who am I going in with" - is the
 * same question before and after the answer.
 */
public class PartyMenu extends ChestMenu {
   private static final int INFO = 4;
   /** The four rooms. */
   private static final int[] ROOMS = {10, 11, 12, 13};
   private static final int CREATE = 15;
   private static final int LEAVE = 16;
   /** The descents, in the same order the expedition window lists them. */
   private static final int[] TYPES = {19, 20, 21, 22, 23, 24, 25, 26};
   private static final int BAG = 31;
   /** Somebody else's party, while you are not in one. */
   private static final int[] JOIN = {28, 29, 30, 32, 33, 34};
   private static final int CLOSE = 40;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public PartyMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45));
   }

   private PartyMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new PartyMenu(syncId, inv), Component.literal("§6§lExpedition Party")));
   }

   private MinecraftServer server() {
      return this.owner.level().getServer();
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 5, Items.STAINED_GLASS_PANE.orange());
      Party party = PartyManager.partyOf(this.owner.getUUID());
      this.container.setItem(INFO, this.infoCard(party));
      if (party == null) {
         this.setNoParty();
      } else {
         this.setParty(party);
      }
      this.container.setItem(BAG, this.bagCard(party));
      this.container.setItem(CLOSE, named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   /** The card in the middle of the top row: what this window is, and who is asking. */
   private ItemStack infoCard(Party party) {
      ItemStack card = new ItemStack(Items.PLAYER_HEAD);
      card.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lExpedition Party"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Up to §f" + PartyManager.MAX_MEMBERS + "§7 go into one site"));
      lore.add(Component.literal("§7together and carry §fone§7 bag between you."));
      lore.add(Component.literal(""));
      if (party == null) {
         lore.add(Component.literal("§7You are not in a party."));
         lore.add(Component.literal("§8Form one below, or join somebody's."));
      } else {
         lore.add(Component.literal("§7Your party: §f" + party.size() + "§7/§f" + PartyManager.MAX_MEMBERS + "§7 explorers."));
         lore.add(Component.literal(this.owner.getUUID().equals(party.host())
            ? "§6You are the host§7 - the door is yours."
            : "§e" + nameOf(party.host()) + " §7is the host."));
         lore.add(Component.literal("§8The bag is sized by the party: nine a name."));
      }
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   /** The four rooms, and the two buttons that work whether or not anybody is in them. */
   private void setNoParty() {
      for (int i = 0; i < ROOMS.length; i++) {
         this.container.setItem(ROOMS[i], emptyRoom(i));
      }
      this.container.setItem(CREATE, named(new ItemStack(Items.EMERALD), "§a§lCreate a party"));
      ItemStack create = this.container.getItem(CREATE);
      create.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7You become the host: you choose who"),
         Component.literal("§7stays, and when the group goes in."),
         Component.literal("§eClick to form the party.")
      )));
      this.container.setItem(LEAVE, named(new ItemStack(Items.GLASS_PANE), "§8Not in a party"));
      this.setTypeSlots(false, false);
      this.setJoinSlots();
   }

   private void setParty(Party party) {
      boolean host = this.owner.getUUID().equals(party.host());
      List<UUID> members = party.members();
      for (int i = 0; i < ROOMS.length; i++) {
         this.container.setItem(ROOMS[i], i < members.size()
            ? memberCard(party, members.get(i), host)
            : emptyRoom(i));
      }
      this.container.setItem(CREATE, named(new ItemStack(Items.GLASS_PANE), "§7Party formed"));
      ItemStack leave = named(new ItemStack(Items.OAK_DOOR), "§c§lLeave the party");
      leave.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Step out of the lobby. The bag stays"),
         Component.literal("§7with the party you left."),
         Component.literal(host && party.size() > 1
            ? "§8The crown passes to the next name."
            : "§eClick to leave.")
      )));
      this.container.setItem(LEAVE, leave);
      this.setTypeSlots(host, true);
      for (int i = 0; i < JOIN.length; i++) {
         this.container.setItem(JOIN[i], named(new ItemStack(Items.GLASS_PANE), " "));
      }
   }

   private ItemStack emptyRoom(int index) {
      ItemStack room = named(new ItemStack(Items.STAINED_GLASS_PANE.gray()), "§8Party room " + (index + 1));
      room.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Empty. Somebody has to be willing"),
         Component.literal("§7to walk into a site with you."),
         Component.literal("§8Open the party window of a friend who"),
         Component.literal("§8has made one, and click their name.")
      )));
      return room;
   }

   private ItemStack memberCard(Party party, UUID member, boolean hostViewing) {
      boolean crown = member.equals(party.host());
      boolean self = member.equals(this.owner.getUUID());
      ItemStack card = named(
         new ItemStack(Items.NAME_TAG),
         (crown ? "§6§l" : "§f§l") + nameOf(member) + (self ? " §8(you)" : "")
      );
      if (crown) {
         card.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(crown ? "§6Host§7 - chooses the door and the roster." : "§7Member."));
      if (hostViewing && !self) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§cClick to remove them from the party."));
      } else if (!hostViewing) {
         lore.add(Component.literal("§8Only the host can remove a member."));
      }
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   /** The descents, live only in the host's hands - they are also the party's start button. */
   private void setTypeSlots(boolean host, boolean inParty) {
      Type[] types = Type.values();
      Party mine = PartyManager.partyOf(this.owner.getUUID());
      boolean inRun = mine != null && mine.onRun();
      for (int i = 0; i < TYPES.length; i++) {
         if (!host) {
            // A blank card explains itself: with no party the descent list is the pitch for making
            // one, and with one it is the host's hand on the door rather than the viewer's.
            this.container.setItem(
               TYPES[i],
               named(
                  new ItemStack(Items.STAINED_GLASS_PANE.gray()),
                  inParty ? "§8Only the host starts the run" : "§8Form a party to go in together"
               )
            );
            continue;
         }
         if (i >= types.length) {
            this.container.setItem(TYPES[i], named(new ItemStack(Items.STAINED_GLASS_PANE.gray()), " "));
            continue;
         }
         Type t = types[i];
         ItemStack card = named(new ItemStack(t.icon), t.color + "§l" + t.name);
         card.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7" + t.desc),
            Component.literal("§7Duration: §f" + (t.durationTicks / 20L / 60L) + " min §8· §7cash §a" + t.multiplier + "x"),
            Component.literal("§7Danger: " + (t.danger.equals("Extreme") ? "§c" : t.danger.equals("High") ? "§6" : "§a") + t.danger),
            Component.literal(""),
            Component.literal(inRun
               ? "§cA run is already live - somebody is still inside."
               : "§eClick to send the whole party in.")
         )));
         this.container.setItem(TYPES[i], card);
      }
   }

   /** Somebody else's lobby, for a player who is not in one. */
   private void setJoinSlots() {
      List<Party> open = PartyManager.openParties(this.server());
      for (int i = 0; i < JOIN.length; i++) {
         if (i >= open.size()) {
            this.container.setItem(JOIN[i], named(new ItemStack(Items.STAINED_GLASS_PANE.gray()), " "));
            continue;
         }
         Party p = open.get(i);
         ItemStack card = named(new ItemStack(Items.PLAYER_HEAD), "§e§lJoin " + nameOf(p.host()) + "'s party");
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal("§7" + p.size() + "§7/§f" + PartyManager.MAX_MEMBERS + "§7 explorers:"));
         for (UUID m : p.members()) {
            lore.add(Component.literal("§8· §f" + nameOf(m)));
         }
         lore.add(Component.literal("§7Their bag holds §f" + p.capacity() + "§7 pieces."));
         lore.add(Component.literal("§eClick to join."));
         card.set(DataComponents.LORE, new ItemLore(lore));
         this.container.setItem(JOIN[i], card);
      }
   }

   /** The party's pack, drawn from the party rather than from whoever is looking at it. */
   private ItemStack bagCard(Party party) {
      ItemStack card = new ItemStack(Items.BUNDLE);
      if (party == null) {
         return namedCard(card, "§6§lParty Pack", List.of(
            Component.literal("§7A party carries one bag between"),
            Component.literal("§7everybody: nine pieces for each name."),
            Component.literal("§8Form a party to size it up.")
         ));
      }
      boolean live = party.onRun();
      List<ItemStack> pieces = live ? party.bag() : List.of();
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Holds §f" + pieces.size() + "§7/§f" + party.capacity() + "§7 pieces"));
      lore.add(Component.literal("§8Nine a name: " + party.size() + " x " + LootBackpack.BASE_CAPACITY));
      if (!live) {
         lore.add(Component.literal("§8The bag fills when the party goes in."));
      } else if (pieces.isEmpty()) {
         lore.add(Component.literal("§8Nothing in it yet - loot the chests."));
      } else {
         long worth = 0L;
         for (ItemStack piece : pieces) {
            worth += LootBackpack.valueOf(piece);
            lore.add(Component.literal(
               "§8· §f" + piece.getCount() + "x " + piece.getHoverName().getString()
                  + " §8(§a" + Chat.moneyStr(LootBackpack.valueOf(piece)) + "§8)"
            ));
         }
         lore.add(Component.literal("§7Carried together: §a" + Chat.moneyStr(worth)));
         lore.add(Component.literal("§8Every member carries a copy of this list."));
      }
      return namedCard(card, "§6§lParty Pack", lore);
   }

   private ItemStack namedCard(ItemStack stack, String name, List<Component> lore) {
      named(stack, name);
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private static ItemStack named(ItemStack stack, String name) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   private String nameOf(UUID uuid) {
      MinecraftServer server = this.server();
      ServerPlayer sp = server == null ? null : server.getPlayerList().getPlayer(uuid);
      return sp == null ? "Someone" : sp.getName().getString();
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         com.fortuneandfavors.util.InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId == CLOSE) {
         this.returnCarried(sp);
         sp.closeContainer();
         return;
      }
      Party party = PartyManager.partyOf(sp.getUUID());
      if (slotId == CREATE) {
         this.returnCarried(sp);
         if (party == null) {
            this.say(sp, PartyManager.create(sp));
         }
         this.rebuild();
         return;
      }
      if (slotId == LEAVE) {
         this.returnCarried(sp);
         if (party != null) {
            this.say(sp, PartyManager.leave(sp));
         }
         this.rebuild();
         return;
      }
      for (int i = 0; i < ROOMS.length; i++) {
         if (slotId != ROOMS[i]) {
            continue;
         }
         this.returnCarried(sp);
         List<UUID> members = party == null ? List.of() : party.members();
         if (i < members.size()) {
            this.say(sp, PartyManager.kick(sp, members.get(i)));
         }
         this.rebuild();
         return;
      }
      Type[] types = Type.values();
      for (int i = 0; i < TYPES.length; i++) {
         if (slotId != TYPES[i]) {
            continue;
         }
         this.returnCarried(sp);
         boolean host = party != null && sp.getUUID().equals(party.host());
         if (host && i < types.length) {
            String problem = PartyManager.startRun(sp, types[i]);
            this.say(sp, problem);
            if (problem == null) {
               sp.closeContainer();
               return;
            }
         }
         this.rebuild();
         return;
      }
      for (int slot : JOIN) {
         if (slotId != slot) {
            continue;
         }
         this.returnCarried(sp);
         if (party == null) {
            List<Party> open = PartyManager.openParties(this.server());
            int index = indexOf(JOIN, slotId);
            if (index >= 0 && index < open.size()) {
               this.say(sp, PartyManager.join(sp, open.get(index).host()));
            }
         }
         this.rebuild();
         return;
      }
      this.returnCarried(sp);
      this.broadcastChanges();
   }

   private static int indexOf(int[] slots, int slot) {
      for (int i = 0; i < slots.length; i++) {
         if (slots[i] == slot) {
            return i;
         }
      }
      return -1;
   }

   private void say(ServerPlayer sp, String problem) {
      if (problem != null) {
         Chat.msg(sp, "&c" + problem);
         SoundUtil.play(sp, ModSounds.DENY);
      } else {
         SoundUtil.play(sp, ModSounds.TRANSFER);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
