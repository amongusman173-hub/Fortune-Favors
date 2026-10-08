package com.fortuneandfavors.util;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which client platform a player is on.
 *
 * <p>Answered by asking Geyser, if Geyser is installed. Nothing is added to the build for it -
 * the class is looked up by name and the answer is "no" when it is not there - because a mod
 * that hard-depends on a proxy plugin cannot be installed without it, and every server that
 * has Bedrock players has this one.
 */
public final class ModPlatform {
   /** Resolved once. Null means "Geyser is not here" or "its API moved", and both are false. */
   private static Object api;
   private static Method isBedrockPlayer;
   private static boolean resolved;

   private ModPlatform() {
   }

   /**
    * True when this uuid is connected through Geyser - a Bedrock client, not a Java one.
    *
    * <p>The reflection is cached deliberately. The anticheat asks this on every finding, and
    * resolving a class by name and looking a method up on it, per call, is the kind of thing
    * that shows up in a tick profile on a server that is already struggling.
    */
   public static boolean isBedrock(UUID id) {
      if (id == null) {
         return false;
      }
      resolve();
      if (isBedrockPlayer == null || api == null) {
         return false;
      }
      try {
         return Boolean.TRUE.equals(isBedrockPlayer.invoke(api, id));
      } catch (Throwable t) {
         return false;
      }
   }

   public static boolean isBedrock(ServerPlayer player) {
      return player != null && isBedrock(player.getUUID());
   }

   private static synchronized void resolve() {
      if (resolved) {
         return;
      }
      resolved = true;
      try {
         Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
         api = apiClass.getMethod("api").invoke(null);
         isBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID.class);
      } catch (Throwable t) {
         // No Geyser, or an API that does not look like this one. Either way the answer to
         // every question below is false, and the reason is worth one line in the log.
         api = null;
         isBedrockPlayer = null;
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.debug(
            "Fortune & Favors: no Geyser API on this server - Bedrock players will be judged like Java ones", t
         );
      }
   }

   /** Whether the platform question can be asked at all, for the diagnostics report. */
   public static boolean hasBedrockApi() {
      resolve();
      return isBedrockPlayer != null;
   }
}
