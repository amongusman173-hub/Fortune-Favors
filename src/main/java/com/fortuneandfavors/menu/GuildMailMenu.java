package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.guild.GuildManager.Mail;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
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

/** Personal guild mailbox: officers/founder send items + notes; members claim here. */
public class GuildMailMenu extends ChestMenu {
   private static final int HELP = 4;
   private static final int CLOSE = 26;
   private static final int FIRST_MAIL = 9;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public GuildMailMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private GuildMailMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new GuildMailMenu(syncId, inv), Component.literal("§d§lGuild Mail")));
   }

   private ItemStack button(Item item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lines).<Component>map(s -> Component.literal(s)).toList()));
      return stack;
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private void rebuild() {
      this.container.clearContent();
      List<Mail> box = new ArrayList<>(GuildManager.mailboxOf(this.owner.getUUID()));
      box.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
      Guild g = GuildManager.getGuild(this.owner.getUUID());
      boolean canManage = g != null && GuildManager.canManage(g, this.owner.getUUID());

      this.container
         .setItem(
            4,
            this.button(
               Items.CHEST_MINECART,
               "§d§lGuild Mailbox",
               "§7Attachments wait here until you claim them.",
               "§8Left-click a message to claim its attachment.",
               "§8Shift-click a claimed message to delete it.",
               canManage ? "§8Send mail: §f/guild mail <member> <item hold>" : "§8Only officers and the founder send mail."
            )
         );
      this.container.setItem(26, this.button(Items.BARRIER, "§cClose", "§7Leave this window"));

      int shown = 0;
      for (Mail m : box) {
         if (shown >= 17) {
            break;
         }

         int slot = FIRST_MAIL + shown;
         String status = m.claimed ? "§a§lClaimed" : "§e§lUNCLAIMED";
         String from = m.senderName == null || m.senderName.isEmpty() ? "your guild" : m.senderName;
         List<String> lore = new ArrayList<>();
         lore.add("§7From: §f" + from);
         lore.add("§7Status: " + status);
         if (!m.items.isEmpty()) {
            ItemStack att = m.items.get(0);
            lore.add("§7Attachment: §f" + att.getCount() + "x " + att.getHoverName().getString());
         } else {
            lore.add("§7Attachment: §8none");
         }

         if (m.claimed) {
            lore.add("§8Shift-click this message to delete it.");
         } else {
            lore.add("§aClick to claim the attachment!");
         }

         ItemStack card = this.button(m.claimed ? Items.PAPER : Items.BUNDLE, "§f#" + m.id + " §d" + m.subject, lore.toArray(new String[0]));
         if (!m.claimed) {
            card.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
         }

         this.container.setItem(slot, card);
         shown++;
      }

      if (box.isEmpty()) {
         this.container
            .setItem(
               13,
               this.button(Items.STAINED_GLASS_PANE.gray(), "§8Mailbox empty", "§7When an officer or your founder", "§7sends you something, it lands here.")
            );
      }

      this.container.setItem(0, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(8, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(18, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.broadcastChanges();
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
         if (slotId == 26) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId >= FIRST_MAIL && slotId < FIRST_MAIL + 17) {
            List<Mail> box = new ArrayList<>(GuildManager.mailboxOf(this.owner.getUUID()));
            box.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            int idx = slotId - FIRST_MAIL;
            if (idx < box.size()) {
               Mail m = box.get(idx);
               if (!m.claimed && !m.items.isEmpty()) {
                  ItemStack item = GuildManager.claimMail(this.owner.getUUID(), m.id);
                  if (item != null) {
                     InventoryHelper.giveOrDrop(sp, item);
                     Chat.raw(sp, "§aClaimed §f" + item.getCount() + "x " + item.getHoverName().getString() + "§a from \"§d" + m.subject + "§a\".");
                     SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
                     this.rebuild();
                     this.broadcastChanges();
                  }
               } else if (m.claimed && input == ContainerInput.QUICK_MOVE) {
                  GuildManager.deleteMail(this.owner.getUUID(), m.id);
                  this.rebuild();
                  this.broadcastChanges();
               } else if (m.claimed) {
                  Chat.raw(sp, "§7Already claimed - shift-click to delete this message.");
               } else {
                  Chat.raw(sp, "§cThis message has no attachment.");
               }
            }

            this.returnCarried(sp);
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < 27) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }

   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         this.returnCarried(sp);
      }

      super.removed(player);
   }
}
