package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.DailyLoginStreakManager;
import com.fortuneandfavors.economy.DisplayPrefsManager;
import com.fortuneandfavors.economy.FirstEverRecordManager;
import com.fortuneandfavors.economy.StreakTrackerManager;
import com.fortuneandfavors.economy.TagManager;
import com.fortuneandfavors.economy.TagManager.Tag;
import com.fortuneandfavors.economy.TitleManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

public class TagsMenu extends ChestMenu {
   // Row 1 - the preview, so the two catalogues below have something to be about
   private static final int PREVIEW = 4;
   // Row 2 - what you are wearing now
   private static final int TAG_INFO = 10;
   private static final int TITLE_INFO = 12;
   private static final int POSITION_SETTING = 14;
   private static final int SHOW_TAGS = 15;
   private static final int RECORDS = 16;
   // Rows 3-4 - title catalog (owned cards are clickable)
   private static final int TITLE_HEADER = 18;
   private static final int TITLE_START = 19;
   private static final int TITLE_UNEQUIP = 26;
   private static final int TITLE_END = 34;
   // Row 5 - tag catalog and the way out
   private static final int TAG_HEADER = 36;
   private static final int TAG_START = 37;
   private static final int TAG_END = 42;
   private static final int TAG_UNEQUIP = 43;
   private static final int CLOSE = 44;

   /** Every milestone title, ordered as a chase ladder. */
   private static final List<String> TITLE_CATALOG =
      List.of("Duelist", "Swordsman", "Champion", "Boss Slayer", "Bounty Hunter", "Dedicated", "Immortal", "Untrustable");
   /** Every milestone tag, in unlock order. */
   private static final List<String> TAG_CATALOG = List.of("Veteran", "Centurion", "Untrustable", "Warlord's Defier");

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public TagsMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private TagsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new TagsMenu(syncId, inv), Component.literal("§b§lTags & Titles")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.blue());
      UUID uuid = this.owner.getUUID();

      // ---- The preview. The two sections below are a title catalogue and a tag catalogue, and the
      //      thing that made this screen confusing is that nothing on it said what either of them
      //      does to your name. So the top of the screen is your name, rendered the way the server
      //      will render it, with the two pieces labelled on it.
      ItemStack preview = new ItemStack(Items.PLAYER_HEAD);
      String activeTitle = TitleManager.activeTitle(uuid);
      Tag previewTag = TagManager.getTag(uuid);
      preview.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lHow you appear"));
      List<Component> previewLore = new ArrayList<>();
      previewLore.add(Component.literal(DisplayPrefsManager.showTags(uuid) ? "§8Preview of your name:" : "§8Hidden right now - §fShow Tags§8 is OFF:"));
      previewLore.add(Component.literal("  " + renderedName(uuid, activeTitle, previewTag)));
      previewLore.add(Component.literal(""));
      previewLore.add(Component.literal("§6[Titles]§7 show a name in gold beside yours."));
      previewLore.add(Component.literal("§b[Tags]§7 show a name in blue."));
      previewLore.add(Component.literal("§8You can wear one of each, in either order."));
      previewLore.add(Component.literal("§8A custom tag is set with §f/tag set <text>§8."));
      preview.set(DataComponents.LORE, new ItemLore(previewLore));
      this.container.setItem(PREVIEW, preview);

      Tag tag = TagManager.getTag(uuid);
      ItemStack tagStack = new ItemStack(Items.NAME_TAG);
      tagStack.set(DataComponents.CUSTOM_NAME, Component.literal(tag == null ? "§7Your tag: none" : "§b[" + tag.text() + "§b]"));
      List<Component> lore = new ArrayList<>();
      if (tag == null) {
         lore.add(Component.literal("§7Set a custom tag with §f/tag set <text>§7,"));
         lore.add(Component.literal("§7or click an owned tag below to equip it."));
      } else {
         lore.add(Component.literal("§7Change it with §f/tag set <text>§7,"));
         lore.add(Component.literal("§7recolor with §f/tag color <r> <g> <b>§7,"));
         lore.add(Component.literal("§7or remove with §f/tag clear§7."));
      }
      tagStack.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(TAG_INFO, tagStack);

      String title = TitleManager.activeTitle(uuid);
      ItemStack titleStack = new ItemStack(Items.GOLD_INGOT);
      titleStack.set(DataComponents.CUSTOM_NAME, Component.literal(title == null ? "§7Your title: none" : "§6[" + title + "§6]"));
      titleStack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Click a title below to equip it."),
               Component.literal("§7Titles come from streaks, milestones and records."),
               Component.literal("§8Shows in chat and the tab list next to your name.")
            )
         )
      );
      this.container.setItem(TITLE_INFO, titleStack);

      ItemStack posStack = new ItemStack(Items.COMPASS);
      posStack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lTitle & Tag Position"));
      String pos = DisplayPrefsManager.positionOf(uuid);
      List<Component> posLore = new ArrayList<>();
      posLore.add(Component.literal("§7Choose where your title and tag render"));
      posLore.add(Component.literal("§7relative to your name in chat and the tab list."));
      if (DisplayPrefsManager.POS_BEFORE.equals(pos)) {
         posLore.add(Component.literal("§fMode: §aBefore name §7- §6[Title§6] §7[Tag§7] §fName"));
      } else if (DisplayPrefsManager.POS_AFTER.equals(pos)) {
         posLore.add(Component.literal("§fMode: §aAfter name §7- §fName §6[Title§6] §7[Tag§7]"));
      } else {
         posLore.add(Component.literal("§fMode: §aDefault §7- §6[Title§6] §fName §7[Tag§7]"));
      }
      posLore.add(Component.literal("§8Click to cycle the position."));
      posStack.set(DataComponents.LORE, new ItemLore(posLore));
      this.container.setItem(POSITION_SETTING, posStack);

      boolean showTags = DisplayPrefsManager.showTags(uuid);
      ItemStack showStack = new ItemStack(showTags ? Items.ENDER_EYE : Items.ENDER_PEARL);
      showStack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lShow Tags & Titles"));
      List<Component> showLore = new ArrayList<>();
      showLore.add(
         Component.literal(
            showTags
               ? "§fState: §aON §7- your §b[tag]§7 and §6[title]§7 show next to your name"
               : "§fState: §cOFF §7- your §b[tag]§7 and §6[title]§7 are hidden"
         )
      );
      showLore.add(Component.literal("§7Everyone stops seeing your tag and title,"));
      showLore.add(Component.literal("§7in chat and the tab list. Click to toggle."));
      showStack.set(DataComponents.LORE, new ItemLore(showLore));
      this.container.setItem(SHOW_TAGS, showStack);

      ItemStack records = new ItemStack(Items.WRITABLE_BOOK);
      records.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lFirst Ever Records"));
      List<Component> recLore = new ArrayList<>();
      Map<String, String> all = FirstEverRecordManager.all();
      if (all.isEmpty()) {
         recLore.add(Component.literal("§7No records yet - go make history!"));
      } else {
         int n = 0;
         for (Map.Entry<String, String> e : all.entrySet()) {
            if (n >= 3) {
               break;
            }
            recLore.add(Component.literal(" §5★ §f" + FirstEverRecordManager.displayForNews(e.getKey()) + "§7 - " + e.getValue()));
            n++;
         }
         recLore.add(Component.literal("§8Full list in /menu → Records."));
      }
      records.set(DataComponents.LORE, new ItemLore(recLore));
      this.container.setItem(RECORDS, records);

      // === TITLES - owned are clickable, locked are grayed out with progress ===
      this.container.setItem(
         TITLE_HEADER,
         this.named(
            new ItemStack(Items.GOLDEN_HELMET),
            "§6§lTitles §7(worn in gold)",
            "§7One at a time, drawn as §6[Title]§7 beside your name.",
            "§7Click an owned one to wear it, or the worn one to",
            "§7take it off. Locked ones show what unlocks them."
         )
      );
      List<String> titles = allTitles();
      int slot = TITLE_START;
      for (String t : titles) {
         if (slot > TITLE_END) {
            break;
         }
         boolean unlocked = TitleManager.has(uuid, t);
         boolean equipped = t.equals(title);
         boolean creator = TitleManager.CREATOR_TITLE.equals(t);
         ItemStack card;
         if (!unlocked) {
            card = new ItemStack(Items.DYE.gray());
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§8[" + t + "§8]"));
         } else if (equipped) {
            card = new ItemStack(Items.DYE.lime());
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l[" + t + "§a§l]"));
         } else if (creator) {
            card = new ItemStack(Items.NETHER_STAR);
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§e§l[" + t + "§e§l]"));
         } else {
            card = new ItemStack(Items.NAME_TAG);
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§b[" + t + "§b]"));
         }
         List<Component> cardLore = new ArrayList<>();
         if (!unlocked) {
            cardLore.add(Component.literal("§8Locked"));
         } else if (equipped) {
            cardLore.add(Component.literal("§aEquipped - click to take it off."));
         } else if (creator) {
            cardLore.add(Component.literal("§7Exclusive to the creator of the mod."));
            cardLore.add(Component.literal("§7Once equipped it §estays on§7 until you unequip it."));
            cardLore.add(Component.literal("§7Click to equip."));
         } else {
            cardLore.add(Component.literal("§7Click to equip this title."));
         }
         String cond = titleCondition(t, uuid);
         if (cond != null) {
            cardLore.add(Component.literal("§8" + cond));
         }
         card.set(DataComponents.LORE, new ItemLore(cardLore));
         this.container.setItem(slot, card);
         slot++;
         if (slot == TITLE_UNEQUIP) {
            slot++;
         }
      }
      this.container.setItem(
         TITLE_UNEQUIP,
         this.named(
            new ItemStack(Items.BARRIER),
            title == null ? "§7Unequip title" : "§cUnequip title",
            title == null ? "§7No title equipped." : "§7Click to remove your current title.",
            "§7The only way to take off a sticky title like",
            "§7§eCreator of Mod§7."
         )
      );

      // === TAGS - owned are clickable, locked show live progress ===
      this.container.setItem(
         TAG_HEADER,
         this.named(
            new ItemStack(Items.CHEST),
            "§b§lTags §7(worn in blue)",
            "§7One at a time, drawn as §b[Tag]§7 beside your name.",
            "§7Click an owned one to wear it, or the worn one to",
            "§7take it off. A custom tag is §f/tag set <text>§7."
         )
      );
      this.container.setItem(
         TAG_UNEQUIP,
         this.named(
            new ItemStack(Items.BARRIER),
            tag == null ? "§7Unequip tag" : "§cUnequip tag",
            tag == null ? "§7No custom tag equipped." : "§7Click to remove your current tag.",
            "§7Your §b[tag]§7 and §6[title]§7 sit next to your name",
            "§7- where is your choice on this screen."
         )
      );
      slot = TAG_START;
      for (String t : TAG_CATALOG) {
         if (slot > TAG_END) {
            break;
         }
         boolean owned = tagOwned(t, uuid);
         boolean equipped = tag != null && strip(tag.text()).equals(t);
         ItemStack card;
         if (!owned) {
            card = new ItemStack(Items.DYE.gray());
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§8[" + t + "§8]"));
         } else if (equipped) {
            card = new ItemStack(Items.DYE.lime());
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l[" + t + "§a§l]"));
         } else {
            card = new ItemStack(Items.NAME_TAG);
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§b[" + t + "§b]"));
         }
         List<Component> cardLore = new ArrayList<>();
         if (owned) {
            cardLore.add(Component.literal(equipped ? "§aEquipped - click to unequip." : "§aUnlocked - click to equip."));
         } else {
            cardLore.add(Component.literal("§8Locked"));
         }
         String progress = tagProgress(t, uuid);
         if (progress != null) {
            cardLore.add(Component.literal(owned ? "§8Progress: " + progress + " ✓" : "§8Progress: " + progress));
         } else {
            cardLore.add(Component.literal(owned ? "§8Earned in a raid." : "§8" + tagUnlockHint(t)));
         }
         card.set(DataComponents.LORE, new ItemLore(cardLore));
         this.container.setItem(slot, card);
         slot++;
      }
      // Nothing is silently dropped. The title catalogue has outgrown its grid before, and a
      // catalogue that quietly stops listing things reads as "this title does not exist" rather
      // than as "this screen is full".
      int capacity = (TITLE_UNEQUIP - TITLE_START) + (TITLE_END - TITLE_UNEQUIP);
      if (titles.size() > capacity) {
         this.container.setItem(
            TITLE_END + 1,
            this.named(
               new ItemStack(Items.PAPER),
               "§7+" + (titles.size() - capacity) + " more title(s)",
               "§8This screen is full - the rest are listed in",
               "§8§f/ff title list§8."
            )
         );
      }
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   /**
    * Your name exactly as the server will draw it, for the preview card.
    *
    * <p>The whole screen is two catalogues, and the question a player arrives with is "what does
    * picking one of these actually do to me". A card that answers it by drawing the answer is worth
    * more than three paragraphs of lore that describe it - and it is honest, because it reads the
    * same position setting and the same one-badge rule the renderer does. The only thing it cannot
    * draw is the tag colour of a custom tag, which is the player's own.
    */
   private String renderedName(UUID uuid, String title, Tag tag) {
      String name = "§f" + this.owner.getName().getString();
      String titlePart = title == null ? null : "§6[" + title + "§6]";
      String tagPart = tag == null ? null : "§b[" + tag.text() + "§b]";
      if (titlePart != null && tagPart != null && strip(tag.text()).equalsIgnoreCase(strip(title))) {
         // The same words never render twice - the same rule the renderer applies.
         tagPart = null;
      }
      String pos = DisplayPrefsManager.positionOf(uuid);
      if (DisplayPrefsManager.POS_BEFORE.equals(pos)) {
         return joinParts(titlePart, tagPart, name);
      }
      if (DisplayPrefsManager.POS_AFTER.equals(pos)) {
         return joinParts(name, titlePart, tagPart);
      }
      return joinParts(titlePart, name, tagPart);
   }

   private static String joinParts(String... parts) {
      StringBuilder sb = new StringBuilder();
      for (String part : parts) {
         if (part == null) {
            continue;
         }
         if (sb.length() > 0) {
            sb.append(' ');
         }
         sb.append(part);
      }
      return sb.toString();
   }

   /** All titles shown in the menu: the creator's exclusive one first if applicable. */
   private List<String> allTitles() {
      List<String> out = new ArrayList<>();
      if (TitleManager.isCreator(this.owner)) {
         out.add(TitleManager.CREATOR_TITLE);
      }
      out.addAll(TITLE_CATALOG);
      return out;
   }

   /** Removes all legacy color codes from a display string. */
   private static String strip(String s) {
      return s == null ? "" : s.replaceAll("§[0-9a-fk-or]", "");
   }

   /** True if the player owns this milestone tag (owned tags may store color codes). */
   private static boolean tagOwned(String t, UUID uuid) {
      for (String owned : TagManager.ownedTags(uuid)) {
         if (strip(owned).equals(t)) {
            return true;
         }
      }
      return false;
   }

   /** The unlock condition for a title card, with live progress where known. */
   private static String titleCondition(String t, UUID uuid) {
      int day = DailyLoginStreakManager.dayOf(uuid);
      int duels = StreakTrackerManager.duelWins(uuid);
      return switch (t) {
         case "Dedicated" -> "Unlock: reach a 30-day login streak (currently day " + day + ").";
         case "Immortal" -> "Unlock: reach a 100-day login streak (currently day " + day + ").";
         case "Boss Slayer" -> "Unlock: slay 4 bosses in a row (current streak " + StreakTrackerManager.bossStreak(uuid) + ").";
         case "Bounty Hunter" -> "Unlock: reach Bounty Hunter level 20 (currently level " + StreakTrackerManager.bountyLevel(uuid) + ").";
         case "Duelist" -> "Unlock: win 10 duels (currently " + duels + ").";
         case "Swordsman" -> "Unlock: win 25 duels (currently " + duels + ").";
         case "Champion" -> "Unlock: win 50 duels (currently " + duels + ").";
         case "Untrustable" -> "Unlock: kneel to the Warlord during a raid.";
         default -> null;
      };
   }

   /** Live progress toward a milestone tag, e.g. "day 9/14". Null when the tag has no numeric progress. */
   private static String tagProgress(String t, UUID uuid) {
      int day = DailyLoginStreakManager.dayOf(uuid);
      return switch (t) {
         case "Veteran" -> "day " + day + "/14";
         case "Centurion" -> "day " + day + "/60";
         default -> null;
      };
   }

   /** How to unlock a raid tag, shown on locked cards. */
   private static String tagUnlockHint(String t) {
      return switch (t) {
         case "Untrustable" -> "Unlock: kneel to the Warlord in a raid.";
         case "Warlord's Defier" -> "Unlock: refuse the Warlord's offer in a raid.";
         default -> "";
      };
   }

   private ItemStack named(ItemStack stack, String name, String... loreLines) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (loreLines.length > 0) {
         List<Component> lore = new ArrayList<>();
         for (String l : loreLines) {
            lore.add(Component.literal(l));
         }
         stack.set(DataComponents.LORE, new ItemLore(lore));
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
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId == POSITION_SETTING) {
            DisplayPrefsManager.cyclePosition(sp.getUUID());
            DisplayPrefsManager.save(sp.level().getServer());
            TagManager.refreshTabList(sp.level().getServer());
            SoundUtil.play(sp, ModSounds.TRANSFER);
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId == SHOW_TAGS) {
            boolean now = !DisplayPrefsManager.showTags(sp.getUUID());
            DisplayPrefsManager.setShowTags(sp.getUUID(), now);
            DisplayPrefsManager.save(sp.level().getServer());
            TagManager.refreshTabList(sp.level().getServer());
            Chat.raw(
               sp,
               now
                  ? "§7Your §b[tag]§7 and §6[title]§7 are now §chidden§7 from chat and the tab list."
                  : "§7Your §b[tag]§7 and §6[title]§7 are now §ashown§7 again."
            );
            SoundUtil.play(sp, ModSounds.TRANSFER);
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId == TITLE_UNEQUIP) {
            TitleManager.clearActive(sp);
            TagManager.refreshTabList(sp.level().getServer());
            SoundUtil.play(sp, ModSounds.DENY);
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId >= TITLE_START && slotId <= TITLE_END) {
            int idx = slotId < TITLE_UNEQUIP ? slotId - TITLE_START : slotId - TITLE_START - 1;
            List<String> titles = allTitles();
            if (idx < titles.size()) {
               String t = titles.get(idx);
               if (TitleManager.has(sp.getUUID(), t)) {
                  // Clicking the title you are wearing takes it off, which is the same gesture the
                  // tag row below has always used. Two catalogues a screen apart that answered the
                  // same click differently was half of why this screen read as broken.
                  if (t.equals(TitleManager.activeTitle(sp.getUUID()))) {
                     TitleManager.clearActive(sp);
                     TagManager.refreshTabList(sp.level().getServer());
                     SoundUtil.play(sp, ModSounds.DENY);
                  } else if (TitleManager.setActive(sp, t)) {
                     TagManager.refreshTabList(sp.level().getServer());
                     SoundUtil.play(sp, ModSounds.TRANSFER);
                  } else {
                     SoundUtil.play(sp, ModSounds.DENY);
                  }
               } else {
                  Chat.msg(sp, "&cYou haven't unlocked the &f" + t + " &ctitle yet.");
                  SoundUtil.play(sp, ModSounds.DENY);
               }
               this.rebuild();
               this.broadcastChanges();
            }
            this.returnCarried(sp);
         } else if (slotId == TAG_UNEQUIP) {
            Tag cur = TagManager.getTag(sp.getUUID());
            if (cur == null) {
               Chat.msg(sp, "&7You have no custom tag equipped.");
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               TagManager.clearTag(sp.getUUID());
               TagManager.refreshTabList(sp.level().getServer());
               Chat.raw(sp, "§7Tag §b[" + strip(cur.text()) + "§b]§7 unequipped - it stays owned, equip it anytime.");
               SoundUtil.play(sp, ModSounds.DENY);
            }
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId >= TAG_START && slotId <= TAG_END) {
            int idx = slotId - TAG_START;
            if (idx < TAG_CATALOG.size()) {
               String t = TAG_CATALOG.get(idx);
               String stored = null;
               for (String owned : TagManager.ownedTags(sp.getUUID())) {
                  if (strip(owned).equals(t)) {
                     stored = owned;
                     break;
                  }
               }
               if (stored == null) {
                  Chat.msg(sp, "&cYou haven't unlocked the &f" + t + " &ctag yet.");
                  SoundUtil.play(sp, ModSounds.DENY);
               } else {
                  Tag cur = TagManager.getTag(sp.getUUID());
                  if (cur != null && strip(cur.text()).equals(t)) {
                     // Clicking the equipped tag unequips it.
                     TagManager.clearTag(sp.getUUID());
                     TagManager.refreshTabList(sp.level().getServer());
                     Chat.raw(sp, "§7Tag §b[" + strip(t) + "§b]§7 unequipped.");
                     SoundUtil.play(sp, ModSounds.DENY);
                  } else {
                     String err = TagManager.equipOwned(sp, stored);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                        SoundUtil.play(sp, ModSounds.DENY);
                     } else {
                        TagManager.refreshTabList(sp.level().getServer());
                        Chat.raw(sp, "§aEquipped tag §b[" + strip(t) + "§b]§a!");
                        SoundUtil.play(sp, ModSounds.TRANSFER);
                     }
                  }
               }
               this.rebuild();
               this.broadcastChanges();
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
      return ItemStack.EMPTY;
   }
}
