package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.anticheat.AntiCheat;
import com.fortuneandfavors.anticheat.AntiCheatStore;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.SoundUtil;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import com.mojang.authlib.GameProfile;

/**
 * The moderation screen behind {@code /ff anticheat banned} and the
 * {@code MODERATION} button on an alert.
 *
 * <p>Two views in one menu. The list is everybody with a punishment or a flag on
 * the record, worst first, with their live state; clicking a row opens the case
 * file for that player. The file is where the two decisions the spec insists on
 * live - <b>confirm cheating</b> and <b>mark false positive</b> - plus the one
 * action that actually changes somebody's game: lifting the punishment.
 *
 * <p>Every action is re-checked against {@link AntiCheat#isStaff} here rather
 * than trusted from whoever opened the screen, because the screen is only a
 * convenience and the server is the authority. A false positive never erases the
 * evidence: the verdict is filed next to it, which is the signal that tells the
 * server owner a check needs tuning.
 */
public class AntiCheatMenu extends ChestMenu {
   /** Rows 0-4 list records; the bottom row is buttons. */
   private static final int PAGE_SLOTS = 45;
   private static final int BACK = 45;
   private static final int SPECTATE = 46;
   private static final int CONFIRM = 48;
   private static final int SUMMARY = 49;
   private static final int FALSE_POSITIVE = 50;

   private static final int UNBAN = 53;
   private static final int PREV = 45;
   private static final int NEXT = 53;

   private final SimpleContainer container;
   private final ServerPlayer viewer;
   private final boolean bannedOnly;
   private final List<AntiCheatStore.Record> records;
   private int page;
   /** null while the list is showing; the open case file otherwise. */
   private AntiCheatStore.Record open;
   /** Which of the open player's checks a verdict would apply to. */
   private int selectedCheck;

   private AntiCheatMenu(int syncId, Inventory playerInventory, boolean bannedOnly) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, new SimpleContainer(54), 6);
      this.container = (SimpleContainer)this.getContainer();
      this.viewer = (ServerPlayer)playerInventory.player;
      this.bannedOnly = bannedOnly;
      this.records = bannedOnly ? AntiCheatStore.punished() : AntiCheatStore.flagged();
      this.page = 0;
      this.rebuild();
      // A screen opened for one particular player - see openCase - lands on their case file
      // rather than on a list the moderator then has to search.
      if (pendingFocus != null) {
         AntiCheatStore.Record target = AntiCheatStore.get(pendingFocus);
         pendingFocus = null;
         if (target != null) {
            this.open = target;
            this.rebuild();
         }
      }
   }

   /**
    * The player a screen opened by {@link #openCase} should jump straight to.
    *
    * <p>A field rather than a constructor argument because the menu is built by
    * {@code openMenu}'s provider with the sync id the server assigns, so there is no moment in
    * between where the caller could hand it one. Single-threaded by construction: a menu is
    * built on the server thread inside the same call that asks for it.
    */
   private static java.util.UUID pendingFocus;

   /**
    * Opens the screen for a moderator. {@code bannedOnly} shows just the
    * punished, which is what {@code /ff anticheat banned} is for; otherwise every
    * player with anything on the record is listed.
    */
   public static void open(ServerPlayer viewer, boolean bannedOnly) {
      if (!AntiCheat.isStaff(viewer)) {
         Chat.msg(viewer, "&cThat screen is for staff only.");
         return;
      }
      viewer.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new AntiCheatMenu(syncId, inv, bannedOnly),
            Component.literal(bannedOnly ? "§c§lBanned players" : "§c§lAntiCheat records")
         )
      );
   }

   /**
    * Opens the records screen with one player's case file already showing.
    *
    * <p>What {@code /ff anticheat banned <player>} and the kit's record button both want: the
    * same screen as the list, in the same two modes, focused on the player being asked about -
    * so a moderator who already knows who they care about does not walk a paginated list to
    * find them.
    */
   public static void openCase(ServerPlayer viewer, java.util.UUID focus, boolean bannedOnly) {
      if (!AntiCheat.isStaff(viewer)) {
         Chat.msg(viewer, "&cThat screen is for staff only.");
         return;
      }
      pendingFocus = focus;
      viewer.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new AntiCheatMenu(syncId, inv, bannedOnly),
            Component.literal(bannedOnly ? "§c§lBanned players" : "§c§lAntiCheat records")
         )
      );
   }

   private int pages() {
      return Math.max(1, (this.records.size() + PAGE_SLOTS - 1) / PAGE_SLOTS);
   }

   private List<String> firedChecks(AntiCheatStore.Record record) {
      List<String> checks = new ArrayList<>();
      for (String check : AntiCheat.CHECKS) {
         if (record.counts.containsKey(check) || record.levels.containsKey(check)) {
            checks.add(check);
         }
      }
      checks.sort(Comparator.comparingDouble((String c) -> record.levels.getOrDefault(c, 0.0)).reversed());
      return checks;
   }

   private void rebuild() {
      for (int i = 0; i < 54; i++) {
         this.container.setItem(i, this.filler());
      }
      if (this.open == null) {
         this.rebuildList();
      } else {
         this.rebuildDetail();
      }
   }

   private void rebuildList() {
      int start = this.page * PAGE_SLOTS;
      for (int i = 0; i < PAGE_SLOTS; i++) {
         int index = start + i;
         if (index >= this.records.size()) {
            break;
         }
         this.container.setItem(i, this.listRow(this.records.get(index)));
      }
      this.container.setItem(
         SUMMARY,
         this.button(
            Items.BOOK,
            "§c§lAntiCheat",
            List.of(
               Component.literal("§7Records: §f" + this.records.size()),
               Component.literal("§7Page §f" + (this.page + 1) + "§7/§f" + this.pages()),
               Component.literal("§8Click a player for their case file."),
               Component.literal("§8Evidence is kept until the file is cleared.")
            )
         )
      );
      if (this.page > 0) {
         this.container.setItem(PREV, this.button(Items.PAPER, "§e§l◀ Previous page", List.of(Component.literal("§7Page " + this.page))));
      }
      if (this.page + 1 < this.pages()) {
         this.container.setItem(NEXT, this.button(Items.PAPER, "§e§lNext page ▶", List.of(Component.literal("§7Page " + (this.page + 2)))));
      }
   }

   private ItemStack listRow(AntiCheatStore.Record record) {
      long now = System.currentTimeMillis();
      AntiCheatStore.Punishment active = record.activePunishment(now);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7uuid: §f" + record.id));
      lore.add(
         Component.literal(
            record.timedOut(now)
               ? "§cTimed out§7: " + ((record.timeoutUntil - now) / 1000L) + "s left"
               : (active != null ? "§eActive " + active.type() + "§7: " + active.reason() : "§7No active punishment")
         )
      );
      lore.add(Component.literal("§7Failures on file: §f" + record.total() + " §7worst vl §f" + round(record.worstLevel())));
      lore.add(Component.literal("§7Today: §f" + record.dailyTriggers + " §7trigger(s), stage §f" + record.stage));
      lore.add(Component.literal("§7Last seen: §f" + stamp(record.lastSeen)));
      // Whether the module is quiet on a player right now, which "last seen" cannot say:
      // a record with a busy past and a silent present is a player who was dealt with, and
      // the reviewer's next question is always whether it is still happening.
      lore.add(Component.literal("§7Detection now: §f" + AntiCheat.cleanRecordText(record.id)));
      if (AntiCheat.isWatched(record.id)) {
         lore.add(Component.literal("§cBeing watched"));
      }
      lore.add(Component.literal("§8Click to open the case file."));

      ItemStack head = new ItemStack(Items.PLAYER_HEAD);
      head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(record.id, record.name)));
      head.set(DataComponents.CUSTOM_NAME, Component.literal((record.timedOut(now) ? "§c" : "§f") + record.name));
      head.set(DataComponents.LORE, new ItemLore(lore));
      return head;
   }

   private void rebuildDetail() {
      long now = System.currentTimeMillis();
      List<String> checks = this.firedChecks(this.open);
      this.selectedCheck = Math.max(0, Math.min(this.selectedCheck, Math.max(0, checks.size() - 1)));

      List<Component> head = new ArrayList<>();
      head.add(Component.literal("§7uuid: §f" + this.open.id));
      head.add(Component.literal("§7Failures: §f" + this.open.total() + " §7worst vl §f" + round(this.open.worstLevel())));
      head.add(Component.literal("§7Today: §f" + this.open.dailyTriggers + " §7trigger(s), stage §f" + this.open.stage));
      head.add(Component.literal("§7Timeout: §f" + (this.open.timedOut(now) ? ((this.open.timeoutUntil - now) / 1000L) + "s" : "none")));
      head.add(Component.literal("§7Punishments: §f" + this.open.punishments.size() + " §7reviews: §f" + this.open.reviews.size()));
      head.add(Component.literal("§7Last seen: §f" + stamp(this.open.lastSeen)));
      head.add(Component.literal("§7Detection now: §f" + AntiCheat.cleanRecordText(this.open.id)));
      ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
      profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(this.open.id, this.open.name)));
      profile.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + this.open.name));
      profile.set(DataComponents.LORE, new ItemLore(head));
      this.container.setItem(4, profile);

      // One item per check that has ever fired, worst first.
      for (int i = 0; i < checks.size() && i < PAGE_SLOTS; i++) {
         String check = checks.get(i);
         int count = this.open.counts.getOrDefault(check, 0);
         double level = this.open.levels.getOrDefault(check, 0.0);
         String detail = this.open.lastDetail.getOrDefault(check, "");
         boolean chosen = i == this.selectedCheck;
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal("§7Failures: §f" + count));
         lore.add(Component.literal("§7Last vl: §f" + round(level)));
         if (!detail.isBlank()) {
            lore.add(Component.literal("§7Last evidence: §f" + detail));
         }
         lore.add(Component.literal(chosen ? "§8Selected for a verdict." : "§8Click to select this check."));
         ItemStack row = this.button(
            chosen ? Items.DIAMOND_SWORD : Items.IRON_SWORD,
            (chosen ? "§a§l▶ " : "§7") + check,
            lore
         );
         this.container.setItem(9 + i, row);
      }

      String selected = checks.isEmpty() ? null : checks.get(this.selectedCheck);
      List<Component> summaryLore = new ArrayList<>();
      summaryLore.add(Component.literal("§7Player: §f" + this.open.name));
      summaryLore.add(Component.literal("§7Verdict applies to: §f" + (selected == null ? "§cnothing - no check has fired" : selected)));
      summaryLore.add(Component.literal("§8Confirm keeps the evidence;"));
      summaryLore.add(Component.literal("§8false positive keeps it too, but lifts the check."));
      this.container.setItem(SUMMARY, this.button(Items.BOOK, "§e§lVerdict", summaryLore));

      this.container.setItem(
         CONFIRM,
         this.button(Items.REDSTONE_BLOCK, "§c§lCONFIRM CHEATING", List.of(
            Component.literal("§7Records a moderator verdict for §f" + (selected == null ? "no check" : selected) + "§7."),
            Component.literal("§8Evidence and history are never erased.")
         ))
      );
      this.container.setItem(
         FALSE_POSITIVE,
         this.button(Items.EMERALD_BLOCK, "§a§lFALSE POSITIVE", List.of(
            Component.literal("§7Clears §f" + (selected == null ? "no check" : selected) + "§7 from the record"),
            Component.literal("§8The verdict and its evidence are kept.")
         ))
      );
      this.container.setItem(
         UNBAN,
         this.button(Items.ANVIL, "§a§lLIFT PUNISHMENTS", List.of(
            Component.literal("§7Clears every active punishment"),
            Component.literal("§7and resets the automatic ladder."),
            Component.literal("§8The history stays on file.")
         ))
      );
      this.container.setItem(BACK, this.button(Items.ARROW, "§e§l◀ Back to the list", List.of(Component.literal("§7Return to the records."))));

      ServerPlayer online = this.viewer.level().getServer() == null
         ? null
         : this.viewer.level().getServer().getPlayerList().getPlayer(this.open.id);
      if (online != null) {
         this.container.setItem(
            SPECTATE,
            this.button(Items.ENDER_EYE, "§b§lSPECTATE", List.of(
               Component.literal("§7Watch §f" + this.open.name + "§7 live."),
               Component.literal("§8/ff anticheat unspectate puts you back.")
            ))
         );
      }
   }

   private ItemStack button(net.minecraft.world.item.Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private static String round(double value) {
      return String.format(java.util.Locale.ROOT, "%.2f", value);
   }

   private static String stamp(long millis) {
      if (millis <= 0L) {
         return "unknown";
      }
      return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
   }

   private String staff() {
      return this.viewer.getName().getString();
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (!AntiCheat.isStaff(sp)) {
         sp.closeContainer();
         return;
      }

      if (this.open == null) {
         if (slotId >= 0 && slotId < PAGE_SLOTS) {
            int index = this.page * PAGE_SLOTS + slotId;
            if (index < this.records.size()) {
               this.open = this.records.get(index);
               this.selectedCheck = 0;
               SoundUtil.play(sp, ModSounds.PAGE_FLIP);
               this.rebuild();
               this.broadcastChanges();
            }
            return;
         }
         if (slotId == PREV && this.page > 0) {
            this.page--;
         } else if (slotId == NEXT && this.page + 1 < this.pages()) {
            this.page++;
         } else if (slotId >= 0 && slotId < 54) {
            return;
         }
         SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         this.rebuild();
         this.broadcastChanges();
         return;
      }

      List<String> checks = this.firedChecks(this.open);
      if (slotId >= 9 && slotId < 9 + Math.min(checks.size(), PAGE_SLOTS)) {
         this.selectedCheck = slotId - 9;
         SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      if (slotId == BACK) {
         this.open = null;
         SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      if (slotId == CONFIRM) {
         this.verdict(sp, checks, false);
         return;
      }
      if (slotId == FALSE_POSITIVE) {
         this.verdict(sp, checks, true);
         return;
      }
      if (slotId == UNBAN) {
         boolean lifted = AntiCheatStore.unban(this.open.id, this.staff());
         AntiCheatStore.save(sp.level().getServer());
         SoundUtil.play(sp, lifted ? ModSounds.TRANSFER : ModSounds.DENY);
         Chat.msg(sp, lifted
            ? "&a&l[AC]&r &7Lifted every punishment on &f" + this.open.name + "&7."
            : "&c&l[AC]&r &7" + this.open.name + " had nothing active to lift.");
         this.refreshOpen();
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      if (slotId == SPECTATE) {
         ServerPlayer target = sp.level().getServer() == null
            ? null
            : sp.level().getServer().getPlayerList().getPlayer(this.open.id);
         if (target != null) {
            sp.closeContainer();
            AntiCheat.spectate(sp, target);
         }
         return;
      }
      if (slotId >= 0 && slotId < 54) {
         return;
      }
      super.clicked(slotId, button, input, player);
   }

   private void verdict(ServerPlayer staff, List<String> checks, boolean falsePositive) {
      if (checks.isEmpty()) {
         SoundUtil.play(staff, ModSounds.DENY);
         Chat.msg(staff, "&c&l[AC]&r &7There is no check to pass a verdict on.");
         return;
      }
      String check = checks.get(Math.max(0, Math.min(this.selectedCheck, checks.size() - 1)));
      AntiCheatStore.review(this.open.id, this.open.name, check, falsePositive, this.staff(), "moderator screen");
      AntiCheatStore.save(staff.level().getServer());
      SoundUtil.play(staff, ModSounds.TRANSFER);
      Chat.msg(staff, falsePositive
         ? "&a&l[AC]&r &7Marked &f" + check + "&7 a false positive for &f" + this.open.name + "&7. Evidence kept."
         : "&c&l[AC]&r &7Confirmed &f" + check + "&7 on &f" + this.open.name + "&7.");
      this.refreshOpen();
      this.rebuild();
      this.broadcastChanges();
   }

   /** Re-reads the open record, since a verdict mutates it in place. */
   private void refreshOpen() {
      if (this.open != null) {
         this.open = AntiCheatStore.get(this.open.id) == null ? this.open : AntiCheatStore.get(this.open.id);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
