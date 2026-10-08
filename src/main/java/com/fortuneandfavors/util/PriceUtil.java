package com.fortuneandfavors.util;

public final class PriceUtil {
   private PriceUtil() {
   }

   public static long parse(String text) {
      if (text == null) {
         return 0L;
      }

      String s = text.trim().toLowerCase();
      if (s.isEmpty()) {
         return 0L;
      }

      long multiplier = 1L;
      char last = s.charAt(s.length() - 1);
      if (last == 'k') {
         multiplier = 1000L;
         s = s.substring(0, s.length() - 1);
      } else if (last == 'm') {
         multiplier = 1000000L;
         s = s.substring(0, s.length() - 1);
      } else if (last == 'b') {
         multiplier = 1000000000L;
         s = s.substring(0, s.length() - 1);
      }

      s = s.trim();
      if (s.isEmpty()) {
         return 0L;
      }

      try {
         double value = Double.parseDouble(s);
         if (Double.isFinite(value) && !(value <= 0.0)) {
            double total = value * multiplier;
            return total > 9.223372E18F ? 0L : Math.round(total);
         } else {
            return 0L;
         }
      } catch (NumberFormatException e) {
         return 0L;
      }
   }

   public static String shortStr(long amount) {
      if (amount >= 1000000000L && amount % 1000000000L == 0L) {
         return amount / 1000000000L + "b";
      } else if (amount >= 1000000L && amount % 1000000L == 0L) {
         return amount / 1000000L + "m";
      } else {
         return amount >= 1000L && amount % 1000L == 0L ? amount / 1000L + "k" : Long.toString(amount);
      }
   }
}
