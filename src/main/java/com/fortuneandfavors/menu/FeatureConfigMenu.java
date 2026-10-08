package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.ShopData;
import com.fortuneandfavors.menu.FeatureConfigMenu.AuctionMinutesMenu;
import com.fortuneandfavors.menu.FeatureConfigMenu.PricePromptMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
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

/**
 * The server config screen behind {@code /ff config}.
 *
 * <p>Laid out on the nine-by-six grid rather than by slot arithmetic. The feature
 * toggles used to be written straight into slots 0..16, which put half of them on
 * the frame and left the rest in reading order that had nothing to do with the
 * categories - so the screen read as a wall of identically-shaped dyes. Features
 * now sit on the interior of the top three rows, the server settings on the row
 * below, and the actions on the bottom row, with the frame left alone.
 *
 * <p>The settings outgrew their row when the anticheat arrived, so the last two -
 * the anticheat and the switch that decides whether it looks at operators - sit at
 * the left of the bottom row, immediately before the actions, rather than being
 * squeezed into the feature grid where they would read as features.
 */
public class FeatureConfigMenu extends ChestMenu {
   private static final int INFO = 4;

   /**
    * The interior of rows 1-3 in reading order: 17 slots, one per
    * {@link ModConfig#FEATURES} entry. Interior means columns 1-7 of each row, so
    * nothing lands on the frame.
    */
   private static final int[] FEATURE_SLOTS = {
      10, 11, 12, 13, 14, 15, 16,
      19, 20, 21, 22, 23, 24, 25,
      28, 29, 30
   };

   // --- server settings ---------------------------------------------------------
   private static final int MULTIPLIER = 31;
   private static final int AUCTION_TIME = 32;
   private static final int BUYNOW = 33;
   private static final int DEATH_DROP = 34;
   private static final int EXPLOSION_REBUILD = 37;
   private static final int RAID_COOLDOWN = 38;
   private static final int WITHER_REWORK = 39;
   private static final int BOSS_DESPAWN = 40;
   private static final int BOSS_MINIONS = 41;
   private static final int BOSS_DIALOGUE = 42;
   private static final int GRAVE_CLAIM_OTHERS = 43;
   /** The machine particles: not a feature, just the sparkle the machines give off. */
   private static final int MACHINE_PARTICLES = 44;

   // --- settings that moved to the bottom row ------------------------------------
   private static final int ANTICHEAT = 46;
   private static final int ANTICHEAT_OPS = 47;

   // --- actions -----------------------------------------------------------------
   private static final int RESET = 48;
   private static final int CLOSE = 50;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private boolean resetArmed = false;

   public FeatureConfigMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private FeatureConfigMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new FeatureConfigMenu(syncId, inv), Component.literal("§6§lFortune & Favors config")));
   }

   // --------------------------------------------------------------- rendering

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.orange());

      this.container.setItem(INFO, this.header());

      String[] features = ModConfig.FEATURES;
      for (int i = 0; i < features.length && i < FEATURE_SLOTS.length; i++) {
         this.feature(FEATURE_SLOTS[i], features[i]);
      }

      this.setting(
         MULTIPLIER, Items.EMERALD, "§e§lShop price multiplier",
         "§7Currently §f" + ShopData.priceMultiplier() + "x§7 on every buy price.",
         "§8Click: type a number (0.5, 1.0, 2.0...)"
      );
      this.setting(
         AUCTION_TIME, Items.CLOCK, "§e§lAuction length",
         "§7New auctions last §f" + ModConfig.auctionMinutes() + "§7 minutes.",
         "§8Click: type minutes (1 - 10080)"
      );

      boolean buyNow = ModConfig.buyNow();
      this.toggle(
         BUYNOW, "§d§lAuction buy-now", buyNow,
         "§7Instant §fBUY NOW§7 on fixed-price auctions.",
         buyNow ? "§8Click to disable" : "§8Click to enable"
      );
      boolean dropOnDeath = ModConfig.dropSpawnersOnDeath();
      this.toggle(
         DEATH_DROP, "§7§lSpawners drop on death", dropOnDeath,
         "§7Dying unbinds any spawner you are carrying.",
         dropOnDeath ? "§8Click to keep spawners bound" : "§8Click to make them drop"
      );
      boolean er = ModConfig.explosionRebuild();
      this.toggle(
         EXPLOSION_REBUILD, "§c§lExplosion rebuild", er,
         "§7Blasts re-place what they broke after a moment.",
         er ? "§8Click to disable" : "§8Click to enable"
      );
      boolean raidCd = ModConfig.raidCooldown();
      this.toggle(
         RAID_COOLDOWN, "§6§lRaid cooldown (30m)", raidCd,
         "§7Player raids wait 30 minutes between triggers.",
         raidCd ? "§8Click to disable" : "§8Click to enable"
      );

      boolean witherRework = ModConfig.witherRework();
      ItemStack witherStack = new ItemStack(witherRework ? Items.WITHER_SKELETON_SKULL : Items.SKELETON_SKULL);
      witherStack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal((witherRework ? "§5§l" : "§8§l") + "Reworked Wither" + (witherRework ? " ✓" : " ✗"))
      );
      witherStack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Summoned withers become the Ascended Wither:"),
               Component.literal("§7phases, charges, slams, knights, a domain."),
               Component.literal(witherRework ? "§8Click to revert to vanilla withers" : "§8Click to fight the Ascended Wither")
            )
         )
      );
      this.container.setItem(WITHER_REWORK, witherStack);

      String despawn = ModConfig.bossDespawn();
      ItemStack despawnStack = new ItemStack(switch (despawn) {
         case "never" -> Items.DRAGON_HEAD;
         case "summoner" -> Items.SKELETON_SKULL;
         default -> Items.IRON_SWORD;
      });
      despawnStack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lBoss despawn"));
      despawnStack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7When bosses leave because fighters died:"),
               Component.literal(switch (despawn) {
                  case "never" -> "§fNEVER§7 - bosses never despawn from deaths";
                  case "summoner" -> "§fSUMMONER§7 - only when the summoner dies";
                  default -> "§fFIGHTERS§7 - when every player who fought is dead";
               }),
               Component.literal("§8Click to cycle the mode")
            )
         )
      );
      this.container.setItem(BOSS_DESPAWN, despawnStack);

      this.setting(
         BOSS_MINIONS, Items.WITHER_SKELETON_SKULL, "§c§lBoss minions: §f" + ModConfig.bossMinions(),
         "§7Max minions a raid boss can have out at once.",
         "§8Click to cycle (1 to 10)"
      );

      // Boss dialogue: the switch players actually ask for.
      boolean dialogue = ModConfig.bossDialogue();
      this.toggle(
         BOSS_DIALOGUE, "§e§lBoss dialogue", dialogue,
         "§7Every boss taunt, telegraph and ceremony line.",
         dialogue ? "§8Click to silence boss lines" : "§8Click to let bosses speak again"
      );

      boolean claimOthers = ModConfig.niceKeepInventoryAllowOthersClaim();
      this.toggle(
         GRAVE_CLAIM_OTHERS, "§b§lAdmins can claim graves", claimOthers,
         "§7Lets admins recover another player's grave (NKI).",
         claimOthers ? "§8Click to restrict to owners only" : "§8Click to let admins claim any grave"
      );

      // The machine particles. It sits with the settings rather than in the feature grid because it
      // turns nothing off but a sparkle - and the numbers are in the lore so an owner can see the
      // cost they are choosing between before they click.
      boolean particles = ModConfig.machineParticles();
      this.toggle(
         MACHINE_PARTICLES, "§b§lMachine particles", particles,
         "§7The little coloured pulse a working machine gives off.",
         "§7At most §f5§7 appear each tick across the whole server,",
         "§7and each machine waits between its own pulses.",
         "§8Vanilla particles and boss effects are untouched.",
         particles ? "§8Click to switch the sparkles off" : "§8Click to bring the sparkles back"
      );

      // Anticheat: the two switches that decide whether the server watches.
      boolean antiCheat = ModConfig.anticheat();
      this.toggle(
         ANTICHEAT, "§c§lAnticheat", antiCheat,
         "§7Speed, flight, reach, killaura, scaffold,",
         "§7fast-mine, knockback delay and ore vision.",
         "§7Refuses the impossible, sets movement back,",
         "§7and reports to staff. &cOff by default.",
         antiCheat ? "§8Click to stop watching" : "§8Click to turn the checks on"
      );
      boolean antiCheatOps = ModConfig.anticheatOps();
      this.toggle(
         ANTICHEAT_OPS, "§c§lAnticheat: operators", antiCheatOps,
         "§7Whether the checks also look at operators.",
         antiCheatOps ? "§7Operators are §cchecked§7 - use this to test" : "§7Operators are §aexempt§7 from every check.",
         antiCheatOps ? "§8Click to exempt operators again" : "§8Click to check operators too"
      );

      ItemStack reset = new ItemStack(this.resetArmed ? Items.TNT : Items.REDSTONE_BLOCK);
      reset.set(DataComponents.CUSTOM_NAME, Component.literal(this.resetArmed ? "§c§lCLICK AGAIN TO CONFIRM" : "§c§lRESET ALL"));
      reset.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Re-enables every feature toggle."),
               Component.literal(this.resetArmed ? "§cClick once more to confirm." : "§8Click, then confirm")
            )
         )
      );
      this.container.setItem(RESET, reset);

      ItemStack close = this.named(Items.BARRIER, "§c§lClose");
      close.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Leave the config screen."))));
      this.container.setItem(CLOSE, close);
   }

   private ItemStack header() {
      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lServer config"));
      int on = 0;
      for (String f : ModConfig.FEATURES) {
         if (ModConfig.is(f)) {
            on++;
         }
      }
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Green = running · grey = switched off."),
               Component.literal("§7Click any icon to flip it for the whole server."),
               Component.literal("§8Features enabled: §f" + on + "§8/§f" + ModConfig.FEATURES.length),
               Component.literal("§8Saved to config.json in the world's data folder.")
            )
         )
      );
      return info;
   }

   /** A plain on/off switch (- lime/grey dye, + a tick or a cross). */
   private void toggle(int slot, String name, boolean on, String... loreLines) {
      ItemStack stack = new ItemStack(on ? Items.DYE.lime() : Items.DYE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal((on ? "§a§l" : "§8§l") + name + (on ? " §a✓" : " §8✗")));
      this.container.setItem(slot, this.lored(stack, loreLines));
   }

   /** A numeric/cyclic setting (- its own icon, not a dye). */
   private void setting(int slot, Item icon, String name, String... loreLines) {
      ItemStack stack = new ItemStack(icon);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      this.container.setItem(slot, this.lored(stack, loreLines));
   }

   private ItemStack lored(ItemStack stack, String... loreLines) {
      List<Component> lines = new ArrayList<>();
      for (String line : loreLines) {
         lines.add(Component.literal(line));
      }
      stack.set(DataComponents.LORE, new ItemLore(lines));
      return stack;
   }

   private void feature(int slot, String feature) {
      boolean on = ModConfig.is(feature);
      ItemStack stack = new ItemStack(on ? Items.DYE.lime() : Items.DYE.gray());
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal((on ? "§a§l" : "§8§l") + ModConfig.displayName(feature) + (on ? " §a✓" : " §8✗"))
      );
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(on ? "§7Running on this server." : "§7Switched off on this server."),
               Component.literal(on ? "§8Click to disable" : "§8Click to enable")
            )
         )
      );
      this.container.setItem(slot, stack);
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
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

   private void refresh(ServerPlayer sp) {
      this.rebuild();
      this.broadcastChanges();
      this.returnCarried(sp);
   }

   /** Saves, plays the right sound, tells the admin, and redraws. */
   private void ack(ServerPlayer sp, boolean now, String message) {
      ModConfig.save(sp.level().getServer());
      SoundUtil.play(sp, now ? ModSounds.BUY : ModSounds.DENY);
      Chat.msg(sp, message);
      this.refresh(sp);
   }

   // ------------------------------------------------------------------ clicks

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }

      // Read *before* clearing: clicking RESET once arms it, and the second click
      // within the same screen is the one that commits. The old screen armed its
      // confirm and then cleared the flag on the next click, so the reset could
      // never actually fire.
      boolean wasArmed = this.resetArmed;
      this.resetArmed = false;

      // Feature toggles first. The slot table is the single source of truth for
      // which slot holds which feature, so the two can never drift apart.
      for (int i = 0; i < ModConfig.FEATURES.length && i < FEATURE_SLOTS.length; i++) {
         if (slotId == FEATURE_SLOTS[i]) {
            String feature = ModConfig.FEATURES[i];
            boolean now = ModConfig.toggle(feature);
            ModConfig.save(sp.level().getServer());
            SoundUtil.play(sp, now ? ModSounds.BUY : ModSounds.DENY);
            Chat.msg(sp, "&a" + ModConfig.displayName(feature) + (now ? " &aenabled" : " &cdisabled") + "&a.");
            if (feature.equals("tags")) {
               com.fortuneandfavors.economy.TagManager.refreshTabList(sp.level().getServer());
            }
            this.refresh(sp);
            return;
         }
      }

      switch (slotId) {
         case MULTIPLIER -> {
            this.returnCarried(sp);
            this.openMultiplier(sp);
         }
         case AUCTION_TIME -> {
            this.returnCarried(sp);
            this.openAuctionTime(sp);
         }
         case BUYNOW -> {
            boolean now = !ModConfig.buyNow();
            ModConfig.setBuyNow(now);
            this.ack(sp, now, now ? "&aAuction buy-now enabled." : "&cAuction buy-now disabled.");
         }
         case DEATH_DROP -> {
            boolean now = !ModConfig.dropSpawnersOnDeath();
            ModConfig.setDropSpawnersOnDeath(now);
            this.ack(
               sp, now,
               now ? "&aSpawners now drop (unbound) when their owner dies - killers can loot them." : "&aSpawners now stay bound to their owner through death."
            );
         }
         case EXPLOSION_REBUILD -> {
            boolean now = !ModConfig.explosionRebuild();
            ModConfig.setExplosionRebuild(now);
            this.ack(
               sp, now,
               now ? "&aExplosion rebuild enabled - TNT craters now repair themselves." : "&cExplosion rebuild disabled - craters stay cratered."
            );
         }
         case RAID_COOLDOWN -> {
            boolean now = !ModConfig.raidCooldown();
            ModConfig.setRaidCooldown(now);
            this.ack(
               sp, now,
               now ? "&aRaid cooldown enabled - 30 minutes between raids." : "&cRaid cooldown disabled - raids can be triggered freely."
            );
         }
         case WITHER_REWORK -> {
            boolean now = !ModConfig.witherRework();
            ModConfig.setWitherRework(now);
            this.ack(
               sp, now,
               now
                  ? "&5Reworked Wither enabled - the next wither summoned is the &lAscended Wither&5."
                  : "&cReworked Wither disabled - withers spawn vanilla again."
            );
         }
         case BOSS_DIALOGUE -> {
            boolean now = !ModConfig.bossDialogue();
            ModConfig.setBossDialogue(now);
            this.ack(
               sp, now,
               now
                  ? "&aBoss dialogue enabled - bosses speak again."
                  : "&eBoss dialogue muted. &7Mechanic telegraphs, sounds and boss bars still run, so nothing you need to dodge is hidden."
            );
         }
         case BOSS_DESPAWN -> {
            String next = switch (ModConfig.bossDespawn()) {
               case "never" -> "summoner";
               case "summoner" -> "fighters";
               default -> "never";
            };
            ModConfig.setBossDespawn(next);
            ModConfig.save(sp.level().getServer());
            SoundUtil.play(sp, ModSounds.BUY);
            Chat.msg(sp, switch (next) {
               case "never" -> "&aBosses will now NEVER despawn from fighter deaths.";
               case "summoner" -> "&aBosses will now despawn only when the summoner dies.";
               default -> "&aBosses will now despawn only when every fighter is dead.";
            });
            this.refresh(sp);
         }
         case BOSS_MINIONS -> {
            int nextMinions = ModConfig.bossMinions() >= 10 ? 1 : ModConfig.bossMinions() + 1;
            ModConfig.setBossMinions(nextMinions);
            ModConfig.save(sp.level().getServer());
            SoundUtil.play(sp, ModSounds.BUY);
            Chat.msg(sp, "&aRaid bosses can now have up to &f" + nextMinions + "&a minions at once.");
            this.refresh(sp);
         }
         case GRAVE_CLAIM_OTHERS -> {
            boolean now = !ModConfig.niceKeepInventoryAllowOthersClaim();
            ModConfig.setNiceKeepInventoryAllowOthersClaim(now);
            this.ack(
               sp, now,
               now ? "&aAdmins can now claim any player's grave." : "&aGraves can now only be claimed by their owner."
            );
         }
         case MACHINE_PARTICLES -> {
            boolean now = !ModConfig.machineParticles();
            ModConfig.setMachineParticles(now);
            this.ack(
               sp, now,
               now
                  ? "&aMachine particles on - working sorters, hoppers and smelters sparkle again (5 a tick at most, server-wide)."
                  : "&eMachine particles off. &7Nothing else changed - vanilla particles, boss telegraphs and every other effect still run."
            );
         }
         case ANTICHEAT -> {
            boolean now = !ModConfig.anticheat();
            ModConfig.setAnticheat(now);
            if (!now) {
               com.fortuneandfavors.anticheat.AntiCheat.clear();
            }
            this.ack(
               sp, now,
               now
                  ? "&cAnticheat enabled. &7Alerts go to staff; impossible hits, breaks and placements are refused."
                  : "&cAnticheat disabled. &7Nothing is checked and the ledger is cleared."
            );
         }
         case ANTICHEAT_OPS -> {
            boolean now = !ModConfig.anticheatOps();
            ModConfig.setAnticheatOps(now);
            this.ack(
               sp, now,
               now
                  ? "&aOperators are now checked as well. &7Turn this off again after testing."
                  : "&aOperators are exempt again - every check skips them."
            );
         }
         case RESET -> {
            if (!wasArmed) {
               this.resetArmed = true;
               SoundUtil.play(sp, ModSounds.DENY);
               this.refresh(sp);
               return;
            }
            ModConfig.enableAll();
            ModConfig.save(sp.level().getServer());
            SoundUtil.play(sp, ModSounds.CLAIM);
            Chat.msg(sp, "&aAll features re-enabled.");
            this.refresh(sp);
         }
         case CLOSE -> {
            this.returnCarried(sp);
            sp.closeContainer();
         }
         default -> this.returnCarried(sp);
      }
   }

   private void openMultiplier(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new PricePromptMenu(syncId, inv), Component.literal("§e§lShop price multiplier")));
   }

   private void openAuctionTime(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new AuctionMinutesMenu(syncId, inv), Component.literal("§e§lAuction length (minutes)")));
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   static class AuctionMinutesMenu extends AnvilPromptMenu {
      AuctionMinutesMenu(int syncId, Inventory playerInventory) {
         super(
            syncId,
            playerInventory,
            new ItemStack(Items.CLOCK),
            helper(
               "§eType minutes",
               new String[]{"§7How long new auctions last by default.", "§7Current: §f" + ModConfig.auctionMinutes() + "m", "§7Anything from 1 to 10080 (a week)"}
            )
         );
         this.returnInputOnCancel = false;
      }

      protected boolean canAccept(String text) {
         try {
            long v = Long.parseLong(text.trim());
            return v >= 1L && v <= 10080L;
         } catch (Exception e) {
            return false;
         }
      }

      protected void renderResult(ItemStack result, String text) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal(text.isEmpty() ? "§8Type minutes" : "§a§lAuctions last " + text + " minutes"));
      }

      protected void accept(ServerPlayer player, String text) {
         long v = Math.max(1L, Math.min(10080L, Long.parseLong(text.trim())));
         ModConfig.setAuctionMinutes(v);
         ModConfig.save(player.level().getServer());
         SoundUtil.play(player, ModSounds.BUY);
         Chat.raw(player, "&aNew auctions will last &f" + v + "&a minutes.");
      }

      protected void reopen(ServerPlayer player) {
         FeatureConfigMenu.open(player);
      }
   }

   static class PricePromptMenu extends AnvilPromptMenu {
      PricePromptMenu(int syncId, Inventory playerInventory) {
         super(
            syncId,
            playerInventory,
            new ItemStack(Items.GOLD_INGOT),
            helper(
               "§eType a multiplier",
               new String[]{"§7Applies to every shop buy price.", "§71.0 = normal · 0.5 = half · 2.0 = double", "§7Anything from 0.1 to 100"}
            )
         );
         this.returnInputOnCancel = false;
      }

      protected boolean canAccept(String text) {
         try {
            double v = Double.parseDouble(text);
            return v >= 0.1 && v <= 100.0;
         } catch (Exception e) {
            return false;
         }
      }

      protected void renderResult(ItemStack result, String text) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal(text.isEmpty() ? "§8Type a multiplier" : "§a§lSet prices to " + text + "x"));
      }

      protected void accept(ServerPlayer player, String text) {
         double v = Math.max(0.1, Math.min(100.0, Double.parseDouble(text)));
         ShopData.setPriceMultiplier(v);
         ShopData.save(player.level().getServer());
         SoundUtil.play(player, ModSounds.BUY);
         Chat.raw(player, "&aShop price multiplier set to &f" + v + "x&a.");
      }

      protected void reopen(ServerPlayer player) {
         FeatureConfigMenu.open(player);
      }
   }
}
