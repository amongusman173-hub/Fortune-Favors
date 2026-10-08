package com.fortuneandfavors.util;

import com.fortuneandfavors.util.ChatCoalescer.LastBuy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class ChatCoalescer {
   private static final long WINDOW_TICKS = 100L;
   private static final Map<UUID, LastBuy> last = new HashMap<>();

   private ChatCoalescer() {
   }

   public static void buyMessage(ServerPlayer player, ItemStack stack, int given, long price) {
      String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      long now = player.level().getGameTime();
      LastBuy prev = last.get(player.getUUID());
      int count = given;
      long total = price;
      if (prev != null && now - prev.time <= 100L && prev.key.equals(key)) {
         count = prev.count + given;
         total = prev.totalPrice + price;
      }

      last.put(player.getUUID(), new LastBuy(key, count, total, now));
      player.connection
         .send(new ClientboundSystemChatPacket(Component.literal("§aBought §f" + Chat.itemName(stack) + "§a x" + count + " for " + Chat.moneyStr(total)), true));
   }


    record LastBuy(String key, int count, long totalPrice, long time) {
    }
}
