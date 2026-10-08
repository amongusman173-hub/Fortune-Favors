package com.fortuneandfavors;

import com.fortuneandfavors.NiceKeepInventoryManager.GraveData;
import com.fortuneandfavors.NiceKeepInventoryManager.LastSpot;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.Advancements;
import com.fortuneandfavors.economy.AdvancedEnchantments;
import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.WormholeManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.NkiSnapshotHolder;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.math.Transformation;
import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Display.BillboardConstraints;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class NiceKeepInventoryManager {
   public static final int MAX_GRACES = 50;
   public static final long GRAVE_EXPIRY_TICKS = 72000L;
   private static final Map<UUID, LastSpot> lastTouched = new HashMap<>();
   private static final Map<BlockPos, GraveData> graves = new HashMap<>();
   private static final Map<UUID, Long> lastTouchTick = new HashMap<>();
   private static final Map<UUID, List<ItemStack>> pendingKept = new HashMap<>();
   private static final Map<UUID, double[]> deathLocations = new HashMap<>();
   private static final Map<UUID, UUID> deliverySnowballs = new HashMap<>();
   private static final Random RANDOM = new Random();

   private NiceKeepInventoryManager() {
   }

   public static boolean isImportantItem(ItemStack stack) {
      if (stack.isEmpty()) {
         return false;
      } else if (stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR)) {
         return true;
      } else if (stack.is(ItemTags.SWORDS)
         || stack.is(ItemTags.AXES)
         || stack.is(ItemTags.PICKAXES)
         || stack.is(ItemTags.SHOVELS)
         || stack.is(ItemTags.HOES)
         || stack.is(Items.MACE)
         || stack.is(Items.TRIDENT)) {
         return true;
      } else if (stack.is(Items.BOW)
         || stack.is(Items.CROSSBOW)
         || stack.is(Items.TRIDENT)
         || stack.is(Items.SHIELD)
         || stack.is(Items.SHEARS)
         || stack.is(Items.FLINT_AND_STEEL)
         || stack.is(Items.FISHING_ROD)) {
         return true;
      } else if (stack.is(Items.TOTEM_OF_UNDYING) || AdvancedEnchantments.isSoulbound(stack)) {
         return true;
      } else {
         return ModItems.isWitherStaff(stack)
               || ModItems.isWitherBlade(stack)
               || ModItems.isWitherCrown(stack)
               || ModItems.isSlimeLauncher(stack)
               || ModItems.isSlimeShield(stack)
               || ModItems.isSlimeBoots(stack)
               || ModItems.isStoneStaff(stack)
               || ModItems.isGolemFist(stack)
               || ModItems.isStoneheart(stack)
            ? true
            : ModItems.isSpawnerItem(stack)
               || ModItems.isBackpack(stack)
               || ModItems.isBundle(stack)
               || ModItems.isForgeLegendary(stack)
               || ModItems.isKingBone(stack)
               || ModItems.isSlimeCore(stack)
               || ModItems.isGolemCore(stack)
               || ModItems.isSlimeTrophy(stack)
               || ModItems.isGolemTrophy(stack)
               || CustomEnchantments.isTome(stack)
               || CCEnchantments.isCCTome(stack);
      }
   }

   public static boolean isArmorItem(ItemStack stack) {
      return stack.isEmpty()
         ? false
         : stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR);
   }

   public static void setLastTouched(ServerPlayer player, BlockPos pos) {
      UUID id = player.getUUID();
      lastTouched.put(id, new LastSpot(player.level().dimension(), pos.immutable()));
      lastTouchTick.put(id, player.level().getGameTime());
   }

   public static BlockPos getLastTouched(ServerPlayer player) {
      LastSpot spot = lastTouched.get(player.getUUID());
      return spot != null && spot.dim().equals(player.level().dimension()) ? spot.pos() : player.blockPosition();
   }

   public static boolean onPlayerDeath(ServerPlayer player, MinecraftServer server) {
      if (!isEnabled()) {
         return false;
      }

      if (!DuelManager.isInDuel(player.getUUID()) && !DuelManager.isDuelRealm(player.level())) {
         // Death by the King's blood magic or the Scarlet Devil used to skip NKI
         // here, because a Blood Revenant confiscated the whole inventory and the
         // servant was the only thing that could hand it back. The servant now
         // wears copies and takes nothing, so a claimed death is just a death:
         // NKI keeps whatever it is configured to keep.
         List<ItemStack> allItems = NkiSnapshotHolder.take(player.getUUID());
         if (allItems != null && !allItems.isEmpty()) {
            ServerLevel level = player.level();
            UUID ownerUUID = player.getUUID();
            String ownerName = player.getName().getString();
            deathLocations.put(ownerUUID, new double[]{player.getX(), player.getY(), player.getZ()});
            int invSize = player.getInventory().getContainerSize();
            List<ItemStack> kept = new ArrayList<>();

            for (int i = 0; i < invSize; i++) {
               kept.add(ItemStack.EMPTY);
            }

            List<ItemStack> graveItems = new ArrayList<>();
            boolean hasAny = false;

            for (int i = 0; i < allItems.size() && i < invSize; i++) {
               ItemStack stack = allItems.get(i);
               if (stack != null && !stack.isEmpty()) {
                  hasAny = true;
                  if (isImportantItem(stack)) {
                     kept.set(i, stack.copy());
                  } else {
                     graveItems.add(stack.copy());
                  }
               }
            }

            if (!hasAny) {
               return false;
            }

            if (graveItems.isEmpty()) {
               pendingKept.put(player.getUUID(), kept);
               return true;
            }

            boolean voidDeath = player.getY() < level.getMinY();
            // No graves in the Snow Queen's realm: it is wiped when the fight ends, grave and all.
            boolean realm = com.fortuneandfavors.economy.BossManager.isInSnowRealm(player);
            BlockPos gravePos = voidDeath || realm ? null : findGravePosition(level, getLastTouched(player));
            if (gravePos == null) {
               for (ItemStack it : graveItems) {
                  EconomyManager.giveItem(ownerUUID, it);
               }

               if (!kept.isEmpty()) {
                  pendingKept.put(player.getUUID(), kept);
               }

               Chat.raw(
                  player,
                  Chat.colorize(
                     voidDeath
                        ? "&6&lYou fell into the void!&r &7Your items are safe - claim them with &e/claim loot&7."
                        : "&6&lNo safe place for a grave!&r &7Your items were sent to &e/claim loot&7."
                  )
               );
               return true;
            } else if (placeGraveHead(level, gravePos, player)) {
               GraveData data = new GraveData(ownerUUID, ownerName, graveItems, level.getGameTime(), level.dimension());
               graves.put(gravePos, data);
               player.getInventory().clearContent();
               if (!kept.isEmpty()) {
                  pendingKept.put(player.getUUID(), kept);
               }

               Chat.raw(
                  player,
                  Chat.colorize(
                     "&6&lYour grave has been placed!&r &7You kept your armor and tools.\n&r&7Grave at &e"
                        + gravePos.getX()
                        + ", "
                        + gravePos.getY()
                        + ", "
                        + gravePos.getZ()
                        + "&7. Right-click it to claim your items."
                  )
               );
               Advancements.grant(player, "nki_first_grave");
               return true;
            } else {
               for (ItemStack it : graveItems) {
                  EconomyManager.giveItem(ownerUUID, it);
               }

               if (!kept.isEmpty()) {
                  pendingKept.put(player.getUUID(), kept);
               }

               Chat.raw(player, Chat.colorize("&6&lCouldn't place a grave here!&r &7Your items were sent to &e/claim loot&7."));
               return true;
            }
         } else {
            NkiSnapshotHolder.clear();
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean isSafeGraveSpot(ServerLevel level, BlockPos check) {
      if (check.getY() <= level.getMinY()) {
         return false;
      }

      BlockState st = level.getBlockState(check);
      BlockState below = level.getBlockState(check.below());
      BlockState above = level.getBlockState(check.above());
      return st.isAir() && st.getFluidState().isEmpty() && !below.isAir() && below.getFluidState().isEmpty()
         ? !above.is(Blocks.LAVA) && !above.is(Blocks.FIRE)
         : false;
   }

   private static BlockPos findGravePosition(ServerLevel level, BlockPos base) {
      for (int dy = 0; dy <= 5; dy++) {
         BlockPos check = base.above(dy);
         if (isSafeGraveSpot(level, check)) {
            return check;
         }
      }

      for (int r = 1; r <= 8; r++) {
         for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
               if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                  BlockPos col = base.offset(dx, 0, dz);

                  for (int dy = 0; dy <= 4; dy++) {
                     BlockPos check = col.above(dy);
                     if (isSafeGraveSpot(level, check)) {
                        return check;
                     }
                  }
               }
            }
         }
      }

      return null;
   }

   private static boolean placeGraveHead(ServerLevel level, BlockPos pos, ServerPlayer player) {
      BlockState headState = Blocks.PLAYER_HEAD.defaultBlockState();
      level.setBlockAndUpdate(pos, headState);
      if (!level.getBlockState(pos).is(Blocks.PLAYER_HEAD)) {
         return false;
      }

      double gx = pos.getX() + 0.5;
      double gy = pos.getY() + 0.5;
      double gz = pos.getZ() + 0.5;

      for (int i = 0; i < 18; i++) {
         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, gx, gy + i * 0.22, gz, 2, 0.18, 0.05, 0.18, 0.02);
      }

      for (int i = 0; i < 16; i++) {
         double a = i / 16.0 * Math.PI * 2.0;
         int color = Color.HSBtoRGB(0.12F, 0.85F, 1.0F) & 16777215;
         level.sendParticles(new DustParticleOptions(color, 1.0F), gx + Math.cos(a) * 1.1, gy + 0.3, gz + Math.sin(a) * 1.1, 1, 0.0, 0.0, 0.0, 0.0);
      }

      level.sendParticles(ParticleTypes.END_ROD, gx, gy + 0.4, gz, 12, 0.3, 0.5, 0.3, 0.04);
      level.sendParticles(ParticleTypes.ENCHANT, gx, gy + 0.6, gz, 20, 0.4, 0.6, 0.4, 0.12);
      level.playSound(null, gx, gy, gz, (SoundEvent)SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.6F, 0.7F);

      try {
         if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            ResolvableProfile profile = ResolvableProfile.createResolved(player.getGameProfile());
            DataComponentMap add = DataComponentMap.builder().set(DataComponents.PROFILE, profile).build();
            skull.setComponents(DataComponentMap.composite(skull.collectComponents(), add));
            skull.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
         }
      } catch (Exception var15) {
      }

      BlockPos textPos = pos.above();
      TextDisplay textDisplay = (TextDisplay)EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (textDisplay != null) {
         textDisplay.setPos(textPos.getX() + 0.5, textPos.getY() + 0.5, textPos.getZ() + 0.5);
         textDisplay.setNoGravity(true);
         textDisplay.setInvulnerable(true);
         // Tagged so the orphan sweep can find and remove it if the grave block
         // ever disappears without a proper claim (worldedit, explosion, bug).
         textDisplay.addTag(ModItems.DISPLAY_TAG);
         textDisplay.setBillboardConstraints(BillboardConstraints.CENTER);
         textDisplay.setText(Component.literal(player.getName().getString()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(16755200))));
         textDisplay.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F), new Quaternionf(), new Vector3f(1.5F, 1.5F, 1.5F), new Quaternionf()));
         level.addFreshEntity(textDisplay);
      }

      return true;
   }

   public static boolean isGraveHead(ServerLevel level, BlockPos pos) {
      GraveData data = graves.get(pos);
      return data != null && data.dim.equals(level.dimension());
   }

   /**
    * One unclaimed grave, as the dynamic board needs to see it.
    *
    * <p>{@code key} is the grave's identity on the job board, because a job about a grave has to be
    * able to say *which* grave and go stale when that grave is claimed - a coordinate pair that
    * outlives the block it names is how a board ends up advertising a drop that is not there.
    */
   public record GraveRecord(UUID ownerUUID, String ownerName, String dimension, BlockPos pos, long ageTicks) {
      public String key() {
         return graveKey(this.dimension, this.pos);
      }
   }

   /** A grave's identity on the job board: dimension plus the block it sits on. */
   public static String graveKey(String dimension, BlockPos pos) {
      return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   /** Every grave still sitting in the world, with how long it has been there. */
   public static List<GraveRecord> unclaimedGraves(MinecraftServer server) {
      List<GraveRecord> out = new ArrayList<>();
      if (server == null) {
         return out;
      }
      for (Entry<BlockPos, GraveData> e : graves.entrySet()) {
         GraveData data = e.getValue();
         if (data == null) {
            continue;
         }
         ServerLevel level = server.getLevel(data.dim);
         long now = level == null ? 0L : level.getGameTime();
         out.add(
            new GraveRecord(
               data.ownerUUID,
               data.ownerName,
               data.dim.identifier().toString(),
               e.getKey().immutable(),
               Math.max(0L, now - data.createdTick)
            )
         );
      }
      return out;
   }

   /** True when the grave has outlived its owner's absence and is anyone's to bring home. */
   public static boolean isGraveAbandoned(MinecraftServer server, GraveData data) {
      if (server == null || data == null) {
         return false;
      }
      if (server.getPlayerList().getPlayer(data.ownerUUID) != null) {
         return false;
      }
      ServerLevel level = server.getLevel(data.dim);
      long now = level == null ? 0L : level.getGameTime();
      return (now - data.createdTick) * 50L >= com.fortuneandfavors.economy.DynamicContractsManager.GRAVE_ABANDONED_MS;
   }

   /**
    * Sends a grave's contents to the player they belong to rather than to whoever found it.
    *
    * <p>This is the part that makes a recovery job a job instead of a robbery: the finder is paid
    * for the trip and the gear is handed over - on the spot if the owner is back, in the post if
    * they are not, through the same queued-keeps channel a death already uses.
    */
   private static void deliverGraveToOwner(UUID ownerUUID, List<ItemStack> items) {
      List<ItemStack> post = pendingKept.computeIfAbsent(ownerUUID, k -> new ArrayList<>());
      for (ItemStack stack : items) {
         if (stack != null && !stack.isEmpty()) {
            post.add(stack.copy());
         }
      }
   }

   public static boolean tryClaimGrave(ServerPlayer player, BlockPos pos, MinecraftServer server) {
      if (!isEnabled()) {
         return false;
      }

      if (DuelManager.isInDuel(player.getUUID())) {
         return false;
      }

      GraveData data = graves.get(pos);
      if (data == null) {
         return false;
      }

      boolean isAdmin = player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
      boolean owner = player.getUUID().equals(data.ownerUUID);
      // A grave whose owner has not come back for it is the one drop on the server that can be
      // neither defended nor recovered by anyone else. Long enough away and it becomes a public
      // errand - but the gear still goes to the player it belongs to, never to the finder.
      boolean rescue = !owner && isGraveAbandoned(server, data);
      if (owner || isAdmin && canAdminClaim() || rescue) {
         int given = 0;

         if (rescue) {
            for (ItemStack stack : data.items) {
               if (stack != null && !stack.isEmpty()) {
                  given += stack.getCount();
               }
            }
            deliverGraveToOwner(data.ownerUUID, data.items);
         } else {
            for (ItemStack stack : data.items) {
               int remaining = stack.getCount();
               ItemStack toGive = stack.copy();

               while (remaining > 0) {
                  int batchSize = Math.min(remaining, toGive.getMaxStackSize());
                  ItemStack batch = toGive.copyWithCount(batchSize);
                  if (!player.getInventory().add(batch)) {
                     player.drop(batch, false);
                  }

                  given += batchSize;
                  remaining -= batchSize;
               }
            }
         }

         ServerLevel level = player.level();
         level.removeBlock(pos, false);
         double gx = pos.getX() + 0.5;
         double gy = pos.getY() + 0.5;
         double gz = pos.getZ() + 0.5;

         for (int i = 0; i < 12; i++) {
            level.sendParticles(
               ParticleTypes.HEART,
               gx + (RANDOM.nextDouble() - 0.5) * 1.2,
               gy + RANDOM.nextDouble() * 1.5,
               gz + (RANDOM.nextDouble() - 0.5) * 1.2,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
         }

         level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, gx, gy + 0.4, gz, 40, 0.7, 1.0, 0.7, 0.18);
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, gx, gy + 0.3, gz, 14, 0.5, 0.6, 0.5, 0.1);
         level.sendParticles(ParticleTypes.END_ROD, gx, gy + 0.5, gz, 16, 0.4, 0.7, 0.4, 0.05);
         com.fortuneandfavors.VfxManager.fireworkBurst(level, gx, gy + 0.4, gz, 26);
         level.playSound(null, gx, gy, gz, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5F, 1.4F);
         BlockPos textPos = pos.above();

         for (TextDisplay display : level.getEntitiesOfClass(TextDisplay.class, new AABB(textPos).inflate(0.5), td -> td.blockPosition().equals(textPos))) {
            display.remove(RemovalReason.DISCARDED);
         }

         graves.remove(pos);
         // The Death Compass quiets down once the grave is retrieved.
         WormholeManager.clearDeath(player);
         if (rescue) {
            Chat.raw(
               player,
               Chat.colorize(
                  "&eYou recovered &f" + data.ownerName + "&e's grave! &7" + given + " item(s) are on their way back to them."
               )
            );
            com.fortuneandfavors.economy.DynamicContractsManager.onGraveRecovered(
               server, player, graveKey(data.dim.identifier().toString(), pos)
            );
         } else {
            Chat.raw(player, Chat.colorize("&aYou claimed your grave! &7" + given + " item(s) returned."));
         }
         return true;
      } else {
         Chat.msg(player, "&cThis grave belongs to &f" + data.ownerName + "&c. Only they can claim it.");
         return true;
      }
   }

   /** Returns every item from all of the target's unclaimed graves straight to their inventory. */
   /** Non-destructive preview: total item count sitting in this player's
    *  unclaimed graves (0 if none, without touching or removing them). */
   public static int previewGraveCount(ServerPlayer target) {
      int total = 0;
      UUID owner = target.getUUID();
      for (GraveData data : graves.values()) {
         if (data != null && data.ownerUUID.equals(owner)) {
            for (ItemStack stack : data.items) {
               if (stack != null && !stack.isEmpty()) {
                  total += stack.getCount();
               }
            }
         }
      }
      return total;
   }

   /** Flat copies of every item still sitting in this player's unclaimed
    *  graves, in no particular order. Used by the /ff restore preview GUI. */
   public static List<ItemStack> previewGraveItems(ServerPlayer target) {
      List<ItemStack> out = new ArrayList<>();
      UUID owner = target.getUUID();
      for (GraveData data : graves.values()) {
         if (data == null || !data.ownerUUID.equals(owner)) {
            continue;
         }
         for (ItemStack stack : data.items) {
            if (stack != null && !stack.isEmpty()) {
               out.add(stack.copy());
            }
         }
      }
      return out;
   }

   public static int restorePlayerInventory(ServerPlayer target, MinecraftServer server) {
      int given = 0;
      UUID owner = target.getUUID();
      Iterator<Entry<BlockPos, GraveData>> it = graves.entrySet().iterator();

      while (it.hasNext()) {
         Entry<BlockPos, GraveData> e = it.next();
         GraveData data = e.getValue();
         if (data == null || !data.ownerUUID.equals(owner)) {
            continue;
         }

         for (ItemStack stack : data.items) {
            int remaining = stack.getCount();
            ItemStack toGive = stack.copy();

            while (remaining > 0) {
               int batchSize = Math.min(remaining, toGive.getMaxStackSize());
               ItemStack batch = toGive.copyWithCount(batchSize);
               if (!target.getInventory().add(batch)) {
                  target.drop(batch, false);
               }

               given += batchSize;
               remaining -= batchSize;
            }
         }

         ServerLevel level = server.getLevel(data.dim);
         if (level != null && level.isLoaded(e.getKey())) {
            level.removeBlock(e.getKey(), false);
            BlockPos textPos = e.getKey().above();

            for (TextDisplay display : level.getEntitiesOfClass(
               TextDisplay.class, new AABB(textPos).inflate(0.5), td -> td.blockPosition().equals(textPos)
            )) {
               display.remove(RemovalReason.DISCARDED);
            }
         }

         it.remove();
      }

      if (given > 0) {
         Chat.raw(target, Chat.colorize("&aYour lost items were restored - &f" + given + "&a item(s) returned."));
         SoundUtil.play(target, ModSounds.TRANSFER);
      } else {
         Chat.raw(target, "&7No unclaimed graves found - nothing to restore.");
      }
      return given;
   }

   public static boolean isEnabled() {
      return ModConfig.is("nice_keep_inventory");
   }

   public static void setEnabled(boolean on) {
      ModConfig.setFeature("nice_keep_inventory", on);
   }

   private static boolean canAdminClaim() {
      return ModConfig.niceKeepInventoryAllowOthersClaim();
   }

   public static void tick(MinecraftServer server) {
      if (!deliverySnowballs.isEmpty()) {
         Iterator<Entry<UUID, UUID>> sit = deliverySnowballs.entrySet().iterator();

         while (sit.hasNext()) {
            Entry<UUID, UUID> se = sit.next();
            UUID snowballId = se.getKey();
            UUID ownerId = se.getValue();
            boolean found = false;

            for (ServerLevel sl : server.getAllLevels()) {
               Entity e = sl.getEntity(snowballId);
               if (e != null && e.isAlive()) {
                  found = true;
                  ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                  if (owner != null && owner.isAlive() && owner.level() instanceof ServerLevel ol) {
                     double dx = owner.getX() - e.getX();
                     double dy = owner.getY() + 1.0 - e.getY();
                     double dz = owner.getZ() - e.getZ();
                     double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                     if (dist < 2.0) {
                        ol.sendParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getY(), e.getZ(), 14, 0.4, 0.3, 0.4, 0.04);
                        e.discard();
                     } else {
                        e.setDeltaMovement(dx / dist * 1.2, dy / dist * 1.2, dz / dist * 1.2);
                        e.hurtMarked = true;
                        if (sl.getGameTime() % 3L == 0L) {
                           sl.sendParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getY(), e.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
                        }
                     }
                     break;
                  }

                  e.discard();
                  break;
               }
            }

            if (!found) {
               sit.remove();
            }
         }
      }

      if (NkiSnapshotHolder.peek() != null) {
         boolean stale = true;

         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (NkiSnapshotHolder.isFor(p.getUUID()) && !p.isAlive()) {
               stale = false;
               break;
            }
         }

         if (stale) {
            NkiSnapshotHolder.clear();
         }
      }

      if (!pendingKept.isEmpty()) {
         Iterator<Entry<UUID, List<ItemStack>>> pit = pendingKept.entrySet().iterator();

         while (pit.hasNext()) {
            Entry<UUID, List<ItemStack>> entry = pit.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.isAlive()) {
               List<ItemStack> saved = entry.getValue();

               for (int i = 0; i < saved.size() && i < player.getInventory().getContainerSize(); i++) {
                  ItemStack stack = saved.get(i);
                  if (stack != null && !stack.isEmpty()) {
                     ItemStack cur = player.getInventory().getItem(i);
                     if (cur.isEmpty()) {
                        player.getInventory().setItem(i, stack.copy());
                     } else if (!player.getInventory().add(stack.copy())) {
                        player.drop(stack.copy(), false);
                     }
                  }
               }

               if (player.level() instanceof ServerLevel sl) {
                  double px = player.getX();
                  double py = player.getY() + 1.0;
                  double pz = player.getZ();
                  sl.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, px, py, pz, 28, 0.5, 0.8, 0.5, 0.15);
                  sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, px, py + 0.5, pz, 14, 0.4, 0.5, 0.4, 0.08);
                  sl.sendParticles(ParticleTypes.ENCHANT, px, py + 0.3, pz, 20, 0.5, 0.6, 0.5, 0.1);
                  sl.sendParticles(ParticleTypes.END_ROD, px, py + 0.5, pz, 16, 0.4, 0.7, 0.4, 0.05);

                  for (int i = 0; i < 14; i++) {
                     double a = i / 14.0 * Math.PI * 2.0;
                     sl.sendParticles(
                        new DustParticleOptions(Color.HSBtoRGB(0.12F, 0.9F, 1.0F) & 16777215, 1.0F),
                        px + Math.cos(a) * 1.2,
                        py + 0.2 + i % 4 * 0.3,
                        pz + Math.sin(a) * 1.2,
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                     );
                  }

                  sl.sendParticles(ParticleTypes.GLOW, px, py + 0.8, pz, 12, 0.6, 0.6, 0.6, 0.04);
                  sl.playSound(null, px, py, pz, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8F, 1.2F);
                  sl.playSound(null, px, py, pz, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 1.5F);
                  sl.playSound(null, px, py, pz, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.2F);
               }

               double[] death = deathLocations.remove(entry.getKey());
               if (death != null && player.level() instanceof ServerLevel dl) {
                  try {
                     Snowball snowball = (Snowball)EntityTypes.SNOWBALL.create(dl, EntitySpawnReason.COMMAND);
                     if (snowball != null) {
                        snowball.setPos(death[0], death[1] + 1.0, death[2]);
                        snowball.setOwner(player);
                        double dx = player.getX() - death[0];
                        double dy = player.getY() - death[1];
                        double dz = player.getZ() - death[2];
                        double dist = Math.max(0.1, Math.sqrt(dx * dx + dy * dy + dz * dz));
                        snowball.setDeltaMovement(dx / dist * 1.5, dy / dist * 1.5 + 0.3, dz / dist * 1.5);
                        dl.addFreshEntity(snowball);
                        deliverySnowballs.put(snowball.getUUID(), entry.getKey());
                        dl.sendParticles(ParticleTypes.SNOWFLAKE, death[0], death[1] + 1.0, death[2], 20, 1.0, 0.5, 1.0, 0.06);
                        dl.playSound(null, death[0], death[1], death[2], SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 1.0F, 1.2F);
                     }
                  } catch (Exception var19) {
                  }
               }

               pit.remove();
            }
         }
      }

      if (!graves.isEmpty()) {
         long now = server.overworld().getGameTime();
         Iterator<Entry<BlockPos, GraveData>> it = graves.entrySet().iterator();

         while (it.hasNext()) {
            Entry<BlockPos, GraveData> entry = it.next();
            GraveData data = entry.getValue();
            ServerLevel level = server.getLevel(data.dim);
            if (level == null) {
               level = server.overworld();
            }

            BlockPos pos = entry.getKey();
            if (now % 40L == 0L && level.isLoaded(pos)) {
               double gx = pos.getX() + 0.5;
               double gy = pos.getY() + 0.6;
               double gz = pos.getZ() + 0.5;
               level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, gx, gy, gz, 1, 0.15, 0.1, 0.15, 0.01);
               level.sendParticles(ParticleTypes.END_ROD, gx, gy + 0.3, gz, 1, 0.1, 0.2, 0.1, 0.01);
            }

            if (now - data.createdTick > 72000L) {
               if (!level.isLoaded(pos)) {
                  for (ItemStack stack : data.items) {
                     EconomyManager.giveItem(data.ownerUUID, stack);
                  }
               } else {
                  for (ItemStack stack : data.items) {
                     ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
                     level.addFreshEntity(drop);
                  }

                  level.removeBlock(pos, false);
                  BlockPos textPos = pos.above();

                  for (TextDisplay display : level.getEntitiesOfClass(
                     TextDisplay.class, new AABB(textPos).inflate(0.5), td -> td.blockPosition().equals(textPos)
                  )) {
                     display.remove(RemovalReason.DISCARDED);
                  }
               }

               it.remove();
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      try {
         JsonObject root = new JsonObject();
         JsonArray gravesArr = new JsonArray();
         Provider access = server.registryAccess();

         for (Entry<BlockPos, GraveData> entry : graves.entrySet()) {
            JsonObject graveJson = new JsonObject();
            BlockPos pos = entry.getKey();
            GraveData data = entry.getValue();
            graveJson.addProperty("x", pos.getX());
            graveJson.addProperty("y", pos.getY());
            graveJson.addProperty("z", pos.getZ());
            graveJson.addProperty("owner", data.ownerUUID.toString());
            graveJson.addProperty("ownerName", data.ownerName);
            graveJson.addProperty("created", data.createdTick);
            graveJson.addProperty("dim", data.dim.identifier().toString());
            JsonArray itemsArr = new JsonArray();

            for (ItemStack stack : data.items) {
               JsonElement json = JsonUtil.itemToJson(stack, access);
               if (json != null) {
                  itemsArr.add(json);
               }
            }

            graveJson.add("items", itemsArr);
            gravesArr.add(graveJson);
         }

         root.add("graves", gravesArr);
         JsonArray pendingArr = new JsonArray();

         for (Entry<UUID, List<ItemStack>> e : pendingKept.entrySet()) {
            JsonObject pend = new JsonObject();
            pend.addProperty("uuid", e.getKey().toString());
            JsonArray slots = new JsonArray();
            List<ItemStack> list = e.getValue();

            for (int i = 0; i < list.size(); i++) {
               ItemStack st = list.get(i);
               if (st != null && !st.isEmpty()) {
                  JsonObject slotJson = new JsonObject();
                  slotJson.addProperty("slot", i);
                  JsonElement itemJson = JsonUtil.itemToJson(st, access);
                  if (itemJson != null) {
                     slotJson.add("item", itemJson);
                     slots.add(slotJson);
                  }
               }
            }

            if (!slots.isEmpty()) {
               pend.add("slots", slots);
               pendingArr.add(pend);
            }
         }

         root.add("pending", pendingArr);
         EconomyManager.getDataDir(server).resolve("nice_keep_inventory.json").toFile().mkdirs();
         JsonUtil.write(EconomyManager.getDataDir(server).resolve("nice_keep_inventory.json"), root);
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.error("Failed to save Nice Keep Inventory data", e);
      }
   }

   public static void load(MinecraftServer server) {
      try {
         Path file = EconomyManager.getDataDir(server).resolve("nice_keep_inventory.json");
         JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());
         Provider access = server.registryAccess();
         graves.clear();
         if (root.has("graves")) {
            for (JsonElement elem : root.getAsJsonArray("graves")) {
               JsonObject graveJson = elem.getAsJsonObject();
               BlockPos pos = new BlockPos(graveJson.get("x").getAsInt(), graveJson.get("y").getAsInt(), graveJson.get("z").getAsInt());
               UUID owner = UUID.fromString(graveJson.get("owner").getAsString());
               String ownerName = graveJson.has("ownerName") ? graveJson.get("ownerName").getAsString() : "";
               long created = graveJson.has("created") ? graveJson.get("created").getAsLong() : 0L;
               List<ItemStack> items = new ArrayList<>();
               if (graveJson.has("items")) {
                  for (JsonElement itemElem : graveJson.getAsJsonArray("items")) {
                     ItemStack stack = JsonUtil.jsonToItem(itemElem, access);
                     if (!stack.isEmpty()) {
                        items.add(stack);
                     }
                  }
               }

               ResourceKey<Level> dim = ResourceKey.create(
                  Registries.DIMENSION, Identifier.parse(graveJson.has("dim") ? graveJson.get("dim").getAsString() : "minecraft:overworld")
               );
               if (!items.isEmpty()) {
                  graves.put(pos, new GraveData(owner, ownerName, items, created, dim));
               }
            }
         }

         pendingKept.clear();
         if (root.has("pending")) {
            for (JsonElement elem : root.getAsJsonArray("pending")) {
               try {
                  JsonObject pend = elem.getAsJsonObject();
                  UUID uuid = UUID.fromString(pend.get("uuid").getAsString());
                  List<ItemStack> list = new ArrayList<>();
                  int size = 41;

                  for (int i = 0; i < size; i++) {
                     list.add(ItemStack.EMPTY);
                  }

                  if (pend.has("slots")) {
                     for (JsonElement slotElem : pend.getAsJsonArray("slots")) {
                        JsonObject slotJson = slotElem.getAsJsonObject();
                        int slot = slotJson.get("slot").getAsInt();
                        if (slot >= 0 && slot < size && slotJson.has("item")) {
                           ItemStack st = JsonUtil.jsonToItem(slotJson.get("item"), access);
                           if (!st.isEmpty()) {
                              list.set(slot, st);
                           }
                        }
                     }
                  }

                  boolean hasAny = false;
                  Iterator var27 = list.iterator();

                  while (true) {
                     if (var27.hasNext()) {
                        ItemStack st = (ItemStack)var27.next();
                        if (st.isEmpty()) {
                           continue;
                        }

                        hasAny = true;
                     }

                     if (hasAny) {
                        pendingKept.put(uuid, list);
                     }
                     break;
                  }
               } catch (Exception var16) {
               }
            }
         }
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.error("Failed to load Nice Keep Inventory data", e);
      }
   }


    static public class GraveData {
       public final UUID ownerUUID;
       public final String ownerName;
       public final List<ItemStack> items;
       public final long createdTick;
       public final ResourceKey<Level> dim;
    
       public GraveData(UUID ownerUUID, String ownerName, List<ItemStack> items, long createdTick, ResourceKey<Level> dim) {
          this.ownerUUID = ownerUUID;
          this.ownerName = ownerName;
          this.items = items;
          this.createdTick = createdTick;
          this.dim = dim;
       }
    }

    record LastSpot(ResourceKey<Level> dim, BlockPos pos) {
    }
}
