package com.fortuneandfavors.block;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.menu.ChairMenu;
import com.fortuneandfavors.util.Chat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The mod's chair: a tracked vanilla stair that you can actually sit on.
 *  Right-click to sit (an invisible, invulnerable small armor stand becomes
 *  the seat), sneak-right-click to open the chair menu (pick up, check the
 *  owner, or restyle it into another wood), and the block can't be mined -
 *  the pickup is the only way to move it, so the server-side item can never
 *  be lost to a bad break. Seats are transient: the stand is discarded the
 *  moment its rider leaves, and relogging while seated re-seats you. */
public final class ChairBlock {
   /** Stand spawn offset relative to the block. A small armor stand carries its
    *  rider ~0.74 blocks above its own y, so a NEGATIVE offset here is what
    *  lands the player inside the stair's seat (~half a block up) instead of
    *  floating on top of the chair. */
   private static final double SEAT_Y = -0.2;
   private static final long RESIT_COOLDOWN = 15L;
   private static final Map<UUID, ArmorStand> seats = new HashMap<>();
   private static final Map<UUID, Long> lastSit = new HashMap<>();

   private ChairBlock() {
   }

   public static boolean isChair(Level level, BlockPos pos) {
      return MachineManager.isChair(level, pos);
   }

   /** Right-click on a tracked chair: sit down (or open the manage menu when
    *  sneaking). Returns PASS when the caller should keep handling the click. */
   public static InteractionResult onUse(ServerPlayer player, Level level, BlockPos pos) {
      if (level.isClientSide() || !isChair(level, pos)) {
         return InteractionResult.PASS;
      }
      if (player.isShiftKeyDown()) {
         // Sneak-use opens the manage GUI (pickup / owner / wood variants).
         // Returning PASS (not SUCCESS) keeps the use flow honest without
         // blocking the menu from opening.
         ChairMenu.open(player, (ServerLevel)level, pos);
         return InteractionResult.SUCCESS;
      }
      return sitDown(player, (ServerLevel)level, pos) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
   }

   private static boolean sitDown(ServerPlayer player, ServerLevel level, BlockPos pos) {
      // Never let the seat spawn at/above the build limit - that is what
      // produced the bogus height-limit popup when sitting near the top.
      // Also clamp above the world floor so the stand itself stays in-bounds.
      double seatY = Math.max(level.getMinY(), pos.getY() + SEAT_Y);
      if (seatY >= level.getHeight()) {
         return false;
      }
      long now = level.getGameTime();
      UUID id = player.getUUID();
      Long last = lastSit.get(id);
      if (last != null && now - last < RESIT_COOLDOWN) {
         return false;
      }
      lastSit.put(id, now);

      discardSeat(id);
      if (player.getVehicle() != null) {
         player.stopRiding();
      }

      float yaw = facingYaw(level, pos);
      // NOT a marker: zero-size marker stands glitch the passenger position
      // (the "teleport to height limit" bug). A small stand sits perfectly.
      ArmorStand stand = new ArmorStand(level, pos.getX() + 0.5, seatY, pos.getZ() + 0.5);
      stand.setYRot(yaw);
      stand.setYHeadRot(yaw);
      stand.setYBodyRot(yaw);
      stand.setNoGravity(true);
      stand.setInvulnerable(true);
      stand.setInvisible(true);
      stand.setNoBasePlate(true);
      stand.setSilent(true);
      setStandFlags(stand);
      level.addFreshEntity(stand);

      if (!player.startRiding(stand)) {
         stand.discard();
         return false;
      }

      seats.put(id, stand);
      level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.5F, 1.4F);
      level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 3, 0.15, 0.1, 0.15, 0.01);
      com.fortuneandfavors.economy.Advancements.grant(player, "chair_sitter");
      return true;
   }

   private static float facingYaw(Level level, BlockPos pos) {
      try {
         var dir = level.getBlockState(pos).getValue(BlockStateProperties.HORIZONTAL_FACING);
         return dir.toYRot() + 180.0F; // sit looking away from the chair's back
      } catch (Exception ignored) {
         return 0.0F;
      }
   }

   /** small + no-base-plate flags on the stand's synced data (no marker). */
   private static void setStandFlags(ArmorStand stand) {
      byte flags = stand.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
      flags |= (byte)(ArmorStand.CLIENT_FLAG_SMALL | ArmorStand.CLIENT_FLAG_NO_BASEPLATE);
      stand.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, flags);
   }

   /** Per-player tick: tidy up seats that ended (dismount, death, dimension
    *  change). The auto-resit after a relog happens on connect instead, so a
    *  player who deliberately stands up isn't glued to the chair. */
   public static void tickPlayer(ServerPlayer player) {
      UUID id = player.getUUID();
      ArmorStand seat = seats.get(id);
      if (seat != null && (player.getVehicle() != seat || !seat.isAlive())) {
         discardSeat(id);
      }
   }

   /** Dismount/cleanup for one player's seat, if any. */
   public static void discardSeat(UUID playerId) {
      ArmorStand seat = seats.remove(playerId);
      if (seat != null && seat.isAlive()) {
         seat.ejectPassengers();
         seat.discard();
      }
   }

   /** Join hook: re-seat a player who logged out while sitting, and sweep any
    *  orphaned seat stands near them (server restart mid-sit leaves none). */
   public static void onConnect(ServerPlayer player) {
      discardSeat(player.getUUID());
      ServerLevel level = player.level();
      BlockPos below = player.blockPosition().below();
      if (level.isLoaded(below) && isChair(level, below) && player.getVehicle() == null) {
         sitDown(player, level, below);
      }
   }

   /** Disconnect hook: no seat should survive its rider logging out. */
   public static void onDisconnect(ServerPlayer player) {
      discardSeat(player.getUUID());
      lastSit.remove(player.getUUID());
   }

   /** The stair block this chair is currently made of, e.g. Blocks.OAK_STAIRS. */
   public static Block blockOf(Level level, BlockPos pos) {
      return level.getBlockState(pos).getBlock();
   }

   /** The matching Chair item for whatever stair this chair currently is. */
   public static ItemStack itemFor(Level level, BlockPos pos) {
      Block b = blockOf(level, pos);
      Item like = b.asItem();
      return like instanceof BlockItem ? ModItems.chair(like) : ModItems.chair();
   }

   /** Restyles the chair into another stair block, keeping facing/half/shape. */
   public static boolean restyle(ServerLevel level, BlockPos pos, Block newStair) {
      if (!(newStair instanceof StairBlock) || !isChair(level, pos)) {
         return false;
      }
      BlockState old = level.getBlockState(pos);
      BlockState next = newStair.defaultBlockState();
      for (var prop : old.getProperties()) {
         next = copyProp(next, old, prop);
      }
      level.setBlock(pos, next, 3);
      level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.9F, 1.1F);
      level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 8, 0.25, 0.15, 0.25, 0.02);
      return true;
   }

   /** Copies one state property across stair types (wildcard-capture safe). */
   @SuppressWarnings({"unchecked", "rawtypes"})
   private static BlockState copyProp(BlockState next, BlockState old, net.minecraft.world.level.block.state.properties.Property prop) {
      if (next.hasProperty(prop)) {
         next = next.setValue(prop, (Comparable)old.getValue(prop));
      }
      return next;
   }

   /** Reclaims the chair item from its block position (menu / sneak-use path).
    *  Returns false when it wasn't a chair or the caller isn't the owner. */
   public static boolean pickUp(ServerPlayer player, Level level, BlockPos pos) {
      if (!isChair(level, pos)) {
         return false;
      }
      // Eject anyone sitting on this exact chair first.
      Iterator<Map.Entry<UUID, ArmorStand>> it = seats.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, ArmorStand> e = it.next();
         ArmorStand s = e.getValue();
         if (s.isAlive() && BlockPos.containing(s.getX(), s.getY() - SEAT_Y, s.getZ()).equals(pos)) {
            ServerPlayer rider = s.level().getServer().getPlayerList().getPlayer(e.getKey());
            if (rider != null) {
               rider.stopRiding();
            }
            discardSeat(e.getKey());
            it.remove();
         }
      }

      MachineManager.Machine m = MachineManager.get(level, pos);
      if (m == null) {
         return false;
      }
      if (m.owner() != null && !m.owner().equals(player.getUUID())) {
         Chat.msg(player, "&cThis chair belongs to " + (m.ownerName().isEmpty() ? "another player" : m.ownerName()) + "!");
         return false;
      }

      ItemStack chair = itemFor(level, pos);
      MachineManager.removeChair(level, pos);
      level.destroyBlock(pos, false);
      com.fortuneandfavors.util.InventoryHelper.giveOrDrop(player, chair);
      level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
      Chat.msg(player, "&7Picked up your &fChair&7.");
      return true;
   }
}
