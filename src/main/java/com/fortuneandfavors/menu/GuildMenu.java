package com.fortuneandfavors.menu;

import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import com.fortuneandfavors.util.PriceUtil;

public class GuildMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CREATE = 11;
   private static final int JOIN_START = 13;
   private static final int JOIN_END = 17;
   private static final int CLOSE = 22;
   private static final int NAME = 2;
   private static final int OWNER = 3;
   private static final int MOTD = 4;
   private static final int PVP = 9;
   private static final int WEALTH = 10;
   private static final int PVE = 11;
   private static final int INVITE = 13;
   private static final int FRIENDLY = 15;
   private static final int DISBAND = 16;
   private static final int LEAVE = 17;
   private static final int SKILLS = 21;
   private static final int MAIL = 14;
   private static final int ROSTER_START = 27;
   private static final int ROSTER_END = 44;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final boolean viewOnly;
   private final UUID viewGuildId;

   public GuildMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45), false, null);
   }

   private GuildMenu(int syncId, Inventory playerInventory, SimpleContainer container, boolean viewOnly, UUID viewGuildId) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.viewOnly = viewOnly;
      this.viewGuildId = viewGuildId;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new GuildMenu(syncId, inv), Component.literal("§6§lGuild")));
   }

   public static void openView(ServerPlayer viewer, Guild g) {
      viewer.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new GuildMenu(syncId, inv, new SimpleContainer(45), true, g.id), Component.literal("§6§lGuild · " + g.name))
      );
   }

   private ItemStack button(Item item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lines).<Component>map(s -> Component.literal(s)).toList()));
      return stack;
   }

   private ItemStack headButton(ServerPlayer member, String name, String... lines) {
      ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
      stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(member.getGameProfile()));
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
      if (this.viewOnly) {
         Guild g = GuildManager.byId(this.viewGuildId);
         if (g != null) {
            this.rebuildView(g);
         }
      } else {
         Guild g = GuildManager.getGuild(this.owner.getUUID());
         if (g == null) {
            this.rebuildNoGuild();
         } else {
            this.rebuildInGuild(g);
         }
      }

      this.container.setItem(22, this.button(Items.BARRIER, "§cClose", "§7Leave this window"));
      this.container.setItem(0, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(8, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(18, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(26, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(36, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(44, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.broadcastChanges();
   }

   private void rebuildNoGuild() {
      this.container
         .setItem(
            4,
            this.button(
               Items.BOOK,
               "§6§lYou're not in a guild",
               "§7Guilds are invite-only crews with shared",
               "§7scores - §cPvP§7 (kills), §aWealth§7 (money earned)",
               "§7and §bPvE§7 (bosses slain).",
               "§7Guildmates deal §a25% less§7 damage to each",
               "§7other unless Friendly Fire is turned on."
            )
         );
      this.container
         .setItem(
            11,
            this.button(
               Items.EMERALD,
               "§a§lCREATE GUILD",
               "§7Found your own guild.",
               "§7Type §f/guild create <name>§7 to name it.",
               "§8Names are 3-16 letters, numbers or spaces."
            )
         );
      List<Guild> inv = GuildManager.invitesFor(this.owner.getUUID());
      if (inv.isEmpty()) {
         this.container
            .setItem(
               13,
               this.button(
                  (Item)Items.STAINED_GLASS_PANE.gray(), "§8No invites yet", "§7When a guild invites you, their", "§7guild shows up here - click to join."
               )
            );
      } else {
         int i = 0;

         for (Guild gi : inv) {
            if (i > 4) {
               break;
            }

            this.container
               .setItem(
                  13 + i,
                  this.button(
                     Items.SHIELD,
                     "§e§lJOIN §f\"" + gi.name + "\"",
                     "§7Click to accept the invite and join",
                     "§7the guild founded by " + (gi.owner.equals(this.owner.getUUID()) ? "you" : "its founder") + "."
                  )
               );
            i++;
         }
      }
   }

   private void rebuildInGuild(Guild g) {
      int level = GuildManager.levelOf(g);
      long prog = GuildManager.progressToNext(g);
      long score = GuildManager.totalScore(g);
      this.container
         .setItem(
            2,
            this.button(
               Items.NAME_TAG,
               "§6§l" + g.name,
               "§7" + g.members.size() + " member" + (g.members.size() == 1 ? "" : "s"),
               "§eLevel " + level + " §8(§7" + score + " total score§8)",
               "§8" + prog + "/100 to level " + (level + 1)
            )
         );
      MinecraftServer server = this.owner.level().getServer();
      String ownerName = server == null
         ? "?"
         : (server.getPlayerList().getPlayer(g.owner) != null ? server.getPlayerList().getPlayer(g.owner).getName().getString() : "Offline founder");
      this.container
         .setItem(
            3, this.button(Items.PLAYER_HEAD, "§eFounder", "§7" + ownerName, "§8You: " + (g.owner.equals(this.owner.getUUID()) ? "the founder" : "a member"))
         );
      this.container
         .setItem(
            4,
            this.button(
               Items.PAPER,
               "§6§lMessage of the Day",
               g.motd.isEmpty() ? "§8No MOTD set" : "§e" + g.motd,
               g.owner.equals(this.owner.getUUID()) ? "§8Set it: §f/guild motd <text>" : "§8Set by the founder"
            )
         );
      this.container
         .setItem(
            9, this.button(Items.DIAMOND_SWORD, "§c§lPvP Score", "§7" + g.pvp + " player kill" + (g.pvp == 1L ? "" : "s"), "§8Earned by killing other players")
         );
      this.container.setItem(10, this.button(Items.GOLD_INGOT, "§a§lWealth Score", "§7" + Chat.moneyStr(g.wealth), "§8Earned from every dollar brought in"));
      this.container
         .setItem(
            11,
            this.button(
               Items.NETHER_STAR, "§b§lPvE Score", "§7" + g.pve + " boss" + (g.pve == 1L ? "" : "es") + " slain", "§8Raid bosses and vanilla bosses count"
            )
         );
      this.container
         .setItem(12, this.button(Items.DIAMOND_PICKAXE, "§6§lGuild Mine", "§7Automated cash production.", "§7Build, upgrade, and deposit via §f/mine§7.", "§7Level " + g.mineLevel + (g.mineLevel > 0 ? " · stock " + Chat.moneyStr(g.mineStock) : " · not built")));
      this.container
         .setItem(13, this.button(Items.FEATHER, "§e§lINVITE", "§7Type §f/guild invite <player>§7 to invite", "§7anyone online. They accept from this menu.", "§8Officers and the founder can invite."));
      boolean canManage = GuildManager.canManage(g, this.owner.getUUID());
      int unclaimed = 0;
      for (GuildManager.Mail m : GuildManager.mailboxOf(this.owner.getUUID())) {
         if (!m.claimed) {
            unclaimed++;
         }
      }

      this.container
         .setItem(
            14,
            this.button(
               Items.CHEST_MINECART,
               unclaimed > 0 ? "§d§lGUILD MAIL §8(" + unclaimed + " new)" : "§d§lGuild Mail",
               "§7Messages with item attachments from your",
               "§7guild's officers and founder.",
               unclaimed > 0 ? "§aYou have §e" + unclaimed + "§a unclaimed attachment" + (unclaimed == 1 ? "" : "s") + "!" : "§8Nothing new right now.",
               "§8Click to open your mailbox."
            )
         );
      this.container
         .setItem(
            15,
            this.button(
               g.friendlyFire ? Items.REDSTONE_TORCH : Items.SHIELD,
               g.friendlyFire ? "§c§lFriendly Fire: ON" : "§a§lFriendly Fire: OFF",
               "§7Guildmates deal §a25% less§7 damage to each",
               "§7other while this is OFF.",
               "§7Click to toggle (founder only)."
            )
         );
      if (g.owner.equals(this.owner.getUUID())) {
         this.container.setItem(16, this.button(Items.TNT, "§c§lDISBAND GUILD", "§7Permanently dissolve the guild.", "§7Everyone is removed."));
      }

      this.container.setItem(17, this.button(Items.OAK_DOOR, "§cLeave Guild", "§7Leave voluntarily. The founder's", "§7departure disbands the guild."));
      this.container
         .setItem(
            21,
            this.button(
               Items.EXPERIENCE_BOTTLE,
               "§b§lGuild Skills",
               "§7Spend perk points on member buffs",
               "§8Wisdom: +skill XP · Fortune: +cash",
               g.owner.equals(this.owner.getUUID()) ? "§8Click to spend perk points (founder)." : "§8Founder-only spending · everyone benefits."
            )
         );
      List<UUID> members = new ArrayList<>(g.members);

      for (int i = 0; i < members.size() && 27 + i <= 44; i++) {
         UUID mid = members.get(i);
         ServerPlayer m = server == null ? null : server.getPlayerList().getPlayer(mid);
         String name = m != null ? m.getName().getString() : GuildManager.displayName(server, mid, "(offline)");
         boolean founder = mid.equals(g.owner);
         boolean online = m != null;
         String[] lore = new String[]{"§8" + rankTag(g, mid, founder) + " · " + (online ? "§aonline" : "§7offline")};
         if (m != null) {
            this.container.setItem(27 + i, this.headButton(m, rankColor(g, mid, founder) + name, lore));
         } else {
            this.container.setItem(27 + i, this.button(Items.PLAYER_HEAD, rankColor(g, mid, founder) + name, lore));
         }
      }
   }

   private static String rankTag(Guild g, UUID mid, boolean founder) {
      return founder ? "Founder" : GuildManager.rankOf(g, mid) == GuildManager.Rank.OFFICER ? "Officer" : "Member";
   }

   private static String rankColor(Guild g, UUID mid, boolean founder) {
      return founder ? "§e" : GuildManager.rankOf(g, mid) == GuildManager.Rank.OFFICER ? "§6" : "§f";
   }

   private void rebuildView(Guild g) {
      MinecraftServer server = this.owner.level().getServer();
      String founderName = GuildManager.displayName(server, g.owner, "Offline founder");
      int level = GuildManager.levelOf(g);
      long prog = GuildManager.progressToNext(g);
      this.container
         .setItem(
            2,
            this.button(
               Items.NAME_TAG,
               "§6§l" + g.name,
               "§7" + g.members.size() + " member" + (g.members.size() == 1 ? "" : "s"),
               "§eLevel " + level + " §8(§7" + GuildManager.totalScore(g) + " total score§8)",
               "§8" + prog + "/100 to level " + (level + 1),
               "§8Viewing via /guild info"
            )
         );
      this.container.setItem(3, this.button(Items.PLAYER_HEAD, "§eFounder", "§7" + founderName));
      this.container.setItem(4, this.button(Items.PAPER, "§6§lMessage of the Day", g.motd.isEmpty() ? "§8No MOTD set" : "§e" + g.motd));
      this.container.setItem(9, this.button(Items.DIAMOND_SWORD, "§c§lPvP Score", "§7" + g.pvp + " player kill" + (g.pvp == 1L ? "" : "s")));
      this.container.setItem(10, this.button(Items.GOLD_INGOT, "§a§lWealth Score", "§7" + Chat.moneyStr(g.wealth)));
      this.container.setItem(11, this.button(Items.NETHER_STAR, "§b§lPvE Score", "§7" + g.pve + " boss" + (g.pve == 1L ? "" : "es") + " slain"));
      this.container.setItem(13, this.button(Items.BOOK, "§6§lRoster", "§7" + g.members.size() + " member" + (g.members.size() == 1 ? "" : "s") + ":"));
      this.container
         .setItem(
            15,
            this.button(
               g.friendlyFire ? Items.REDSTONE_TORCH : Items.SHIELD,
               g.friendlyFire ? "§c§lFriendly Fire: ON" : "§a§lFriendly Fire: OFF",
               "§7Guildmates deal §a25% less§7 damage to each",
               "§7other while this is OFF."
            )
         );
      List<UUID> members = new ArrayList<>(g.members);

      for (int i = 0; i < members.size() && 27 + i <= 44; i++) {
         UUID mid = members.get(i);
         String name = GuildManager.displayName(server, mid, "(offline)");
         boolean founder = mid.equals(g.owner);
         ServerPlayer m = server == null ? null : server.getPlayerList().getPlayer(mid);
         String[] lore = new String[]{"§8" + (founder ? "Founder" : "Member") + " · " + (m != null ? "§aonline" : "§7offline")};
         if (m != null) {
            this.container.setItem(27 + i, this.headButton(m, (founder ? "§e" : "§f") + name, lore));
         } else {
            this.container.setItem(27 + i, this.button(Items.PLAYER_HEAD, (founder ? "§e" : "§f") + name, lore));
         }
      }
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
         if (this.viewOnly) {
            if (slotId == 22) {
               this.returnCarried(sp);
               sp.closeContainer();
            } else {
               this.returnCarried(sp);
            }
         } else if (slotId == 22) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId >= 0 && slotId < 45) {
            Guild g = GuildManager.getGuild(sp.getUUID());
            if (g == null) {
               if (slotId == 11) {
                  Chat.raw(sp, "§eType §f/guild create <name>§e to found your guild - §7names are 3-16 letters, numbers or spaces.");
                  this.returnCarried(sp);
                  return;
               }

               if (slotId >= 13 && slotId <= 17) {
                  List<Guild> inv = GuildManager.invitesFor(sp.getUUID());
                  int idx = slotId - 13;
                  if (idx < inv.size()) {
                     String err = GuildManager.join(sp, inv.get(idx).id);
                     if (err == null) {
                        sp.closeContainer();
                     } else {
                        Chat.msg(sp, "&c" + err);
                     }
                  }

                  this.returnCarried(sp);
                  return;
               }
            } else {
               switch (slotId) {
                  case 4:
                     if (g.owner.equals(sp.getUUID())) {
                        Chat.raw(sp, "§eSet your guild's message of the day with §f/guild motd <text>§7.");
                     } else {
                        Chat.raw(sp, "§7Only the founder can set the message of the day.");
                     }

                     this.returnCarried(sp);
                     return;
                  case 5:
                  case 6:
                  case 7:
                  case 8:
                  case 9:
                  case 10:
                  case 11:
                  case 18:
                  case 19:
                  case 20:
                  default:
                     break;
                  case 12:
                     GuildMineMenu.open(sp);
                     return;
                  case 13:
                     Chat.raw(sp, "§eType §f/guild invite <player>§7 to invite someone online.");
                     this.returnCarried(sp);
                     return;
                  case 14:
                     GuildMailMenu.open(sp);
                     return;
                  case 15:
                     String errMsg2 = GuildManager.toggleFriendlyFire(sp);
                     if (errMsg2 != null) {
                        Chat.msg(sp, "&c" + errMsg2);
                     } else {
                        this.rebuild();
                        this.broadcastChanges();
                     }

                     this.returnCarried(sp);
                     return;
                  case 16:
                     String errMsg3 = GuildManager.disband(sp);
                     if (errMsg3 == null) {
                        sp.closeContainer();
                     } else {
                        Chat.msg(sp, "&c" + errMsg3);
                     }

                     this.returnCarried(sp);
                     return;
                  case 17:
                     String errMsg4 = GuildManager.leave(sp);
                     if (errMsg4 != null) {
                        Chat.msg(sp, "&c" + errMsg4);
                     } else {
                        // Leaving is two-step now: this call only armed the
                        // confirmation, so keep the window open and tell the
                        // player the words that actually commit it.
                        Chat.msg(sp, "&7Confirm with &f/guild leave confirm&7 (&f/guild leave cancel&7 to stay).");
                     }

                     this.returnCarried(sp);
                     return;
                  case 21:
                     GuildSkillsMenu.open(sp);
                     return;
               }
            }

            this.returnCarried(sp);
         } else {
            super.clicked(slotId, button, input, player);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < 45) {
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
