package com.fortuneandfavors.util;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class Chat {
   private static final String PREFIX = "§8[§2⛏ Fortune & Favors§8]§7 ";

   private Chat() {
   }

   public static String colorize(String text) {
      return text.replace('&', '§');
   }

   public static void msg(ServerPlayer player, String text) {
      player.sendSystemMessage(Component.literal("§8[§2⛏ Fortune & Favors§8]§7 " + colorize(text)));
   }

   public static void raw(ServerPlayer player, String text) {
      player.sendSystemMessage(Component.literal(colorize(text)));
   }

   public static void raw(ServerPlayer player, Component component) {
      player.sendSystemMessage(component);
   }

   public static MutableComponent money(long amount) {
      return Component.literal("§a$" + amount);
   }

   public static String moneyStr(long amount) {
      return "§a$" + amount + "§7";
   }

   public static MutableComponent join(Component... parts) {
      MutableComponent out = Component.empty();

      for (Component part : parts) {
         out.append(part);
      }

      return out;
   }

   public static String itemName(ItemStack stack) {
      return stack.getHoverName().getString();
   }

   public static String formatLines(List<Component> lines) {
      StringBuilder sb = new StringBuilder();

      for (Component line : lines) {
         sb.append(line.getString()).append('\n');
      }

      return sb.toString();
   }
}
