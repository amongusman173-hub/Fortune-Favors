package com.fortuneandfavors.client.config;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.client.ScreenFx;
import com.fortuneandfavors.net.FfConfigPayload;
import com.fortuneandfavors.net.FfNet;
import com.google.gson.JsonObject;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The single config manager for Fortune & Favors.
 *
 *  {@link FfConfigState} (plain Java, no Cloth imports) owns the values and the
 *  config file. Cloth Config is strictly OPTIONAL: every Cloth class touch is
 *  quarantined in {@link ClothBridge}, which is only class-loaded when
 *  cloth-config is actually installed. That keeps installs without Cloth (like
 *  a minimal test instance) from crashing with NoClassDefFoundError at startup.
 *
 *  Behavior is unchanged when Cloth IS present: ModMenu gets the Cloth screen,
 *  saving pushes server settings to the modded server (ops only), and joining
 *  with OP auto-pushes your locally saved server settings once. */
public final class FfConfigManager {
   private static final String CLOTH_ID = "cloth-config";
   private static Boolean clothLoaded = null;

   /** True only while connected to a server running this mod that has told us
    *  we may push config changes (we have OP there). Local worlds and non-mod
    *  servers both leave this false, so saving the config screen never tries to
    *  reach a server that isn't listening. */
   private static boolean serverCanEdit = false;

   private FfConfigManager() {
   }

   /** True when cloth-config is installed (checked once, lazily). */
   public static boolean clothAvailable() {
      if (clothLoaded == null) {
         clothLoaded = FabricLoader.getInstance().isModLoaded(CLOTH_ID);
      }
      return clothLoaded;
   }

   /** Loads state from config/fortuneandfavors.json, then hands control to the
    *  Cloth bridge when Cloth is installed. Never touches Cloth classes
    *  otherwise, so minimal installs load fine. */
   public static void init() {
      FfConfigState.load();
      applyClientFx();
      if (clothAvailable()) {
         safely("Cloth Config init", ClothBridge::init);
      } else {
         FortuneFavorsMod.LOGGER
            .info("Fortune & Favors: cloth-config not installed - the config screen is disabled, client FX toggles still work");
      }
   }

   /** Applies the client FX toggles from the state (works with or without
    *  Cloth). */
   public static void applyClientFx() {
      FfConfigState.ClientCategory c = FfConfigState.get().client;
      ScreenFx.setEffectEnabled(1, c.devourOverlay);
      ScreenFx.setEffectEnabled(2, c.corruptionOverlay);
      ScreenFx.setEffectEnabled(3, c.linkedOverlay);
      ScreenFx.setEffectEnabled(4, c.swordBlockPose);
      ScreenFx.setEffectEnabled(5, c.goldenAppleFlash);
      ScreenFx.setEffectEnabled(6, c.deadeyeFlash);
   }

   /** The ModMenu config screen, or null when Cloth Config is missing (ModMenu
    *  then shows no config button instead of crashing). */
   public static Screen configScreen(Screen parent) {
      if (!clothAvailable()) {
         return new FfSettingsScreen(parent);
      }
      return safely("Cloth Config screen", () -> ClothBridge.screen(parent), null);
   }

   /** Called when the player saves the config screen: persist locally, apply
    *  FX, and push server settings only when the server granted edit rights. */
   public static void onSaved() {
      applyClientFx();
      FfConfigState.save();
      sendApply();
   }

   public static void sendApply() {
      // Only push server settings when we're on a modded server that has granted
      // us edit rights (OP). Otherwise the change is purely local - the server
      // will correct our mirror on the next join/sync anyway.
      if (serverCanEdit && ClientPlayNetworking.canSend(FfConfigPayload.TYPE)) {
         ClientPlayNetworking.send(new FfConfigPayload(1, FfConfigState.get().toServerJson().toString()));
      }
   }

   public static void applySnapshot(String json) {
      JsonObject root = (JsonObject)com.fortuneandfavors.util.JsonUtil.gson().fromJson(json, JsonObject.class);
      if (root != null) {
         // "__can_edit" is a per-recipient transport flag, not a config value:
         // the server says whether WE may push changes. Strip it before the
         // snapshot is fed into the config screen so it never renders as an
         // unknown option.
         if (root.has("__can_edit")) {
            serverCanEdit = com.fortuneandfavors.util.JsonUtil.jsonBool(root, "__can_edit", false);
            root.remove("__can_edit");
         }

         reportVersionMismatch(root);
         reportRegistryMismatch(root);

         // The snapshot is authoritative, and joining must NEVER write server
         // settings. This used to auto-push the client's locally saved copy on
         // connect for ops, which is how a server's Explosion Rebuild silently
         // switched itself off: every op leave-and-rejoin stamped their stale
         // local value over the live server config, with no warning anywhere.
         // The one thing that pushes now is an op saving the config screen.
         FfConfigState.get().applyServerSnapshot(root);
         applyClientFx();
         // Keep the Cloth holder's in-memory values in step with the server's
         // snapshot so the config screen opens showing the server's values.
         if (clothAvailable()) {
            safely("Cloth Config mirror", ClothBridge::mirrorStateToCloth);
         }

      }
   }

   /** Compares the server's block-registry fingerprint with this client's.
    *
    *  <p>Chunk packets carry block states as numeric ids assigned by
    *  registration order. If this client runs a different set of mods, those ids
    *  shift and chunk decoding fails with
    *  {@code MissingPaletteEntryException} - which a player sees as chunks that
    *  never load with everything inside them missing. Vanilla cannot detect this
    *  before it ships chunk data, so the server stamps its fingerprint on the
    *  config snapshot and we compare it here: the problem gets named at join
    *  time instead of looking like random corruption. */
   private static void reportRegistryMismatch(JsonObject root) {
      long serverStates = readFingerprint(root, FfNet.KEY_BLOCK_STATES);
      long serverHash = readFingerprint(root, FfNet.KEY_BLOCK_HASH);
      long serverCount = readFingerprint(root, FfNet.KEY_BLOCK_COUNT);
      root.remove(FfNet.KEY_BLOCK_STATES);
      root.remove(FfNet.KEY_BLOCK_HASH);
      root.remove(FfNet.KEY_BLOCK_COUNT);

      // An older server build does not stamp these - nothing to compare.
      if (serverStates < 0L && serverCount < 0L) {
         return;
      }

      long mineStates = com.fortuneandfavors.util.RegistryFingerprint.blockStates();
      long mineHash = com.fortuneandfavors.util.RegistryFingerprint.blockHash();
      long mineCount = com.fortuneandfavors.util.RegistryFingerprint.blockCount();

      boolean statesDiffer = serverStates >= 0L && mineStates >= 0L && serverStates != mineStates;
      boolean countDiffer = serverCount >= 0L && serverCount != mineCount;
      if (!statesDiffer && !countDiffer) {
         return;
      }

      String detail = "server \u00a7e" + serverCount + "\u00a7f blocks (\u00a7e" + serverStates + "\u00a7f states), you \u00a7e"
         + mineCount + "\u00a7f (\u00a7e" + mineStates + "\u00a7f)";
      FortuneFavorsMod.LOGGER.warn(
         "Block registry mismatch - {}; server hash {}, client hash {}. Chunk packets carry block states as numeric ids, so any chunk holding a block this client lacks will fail to decode. Install the same mod set on both sides.",
         new Object[]{detail, serverHash, mineHash}
      );
      Minecraft client = Minecraft.getInstance();
      if (client != null && client.player != null) {
         client.player.sendSystemMessage(
            Component.literal(
               "\u00a7c[Fortune & Favors] \u00a7fBlock registry mismatch - "
                  + detail
                  + ". \u00a77Chunks holding blocks your client does not have will not load. Install the same mod set as the server."
            )
         );
      }
   }

   /** Reads one fingerprint stamp, or -1 when the server did not send it. */
   private static long readFingerprint(JsonObject root, String key) {
      return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsLong() : -1L;
   }

   /** The server stamps every snapshot with its network protocol and build
    *  version. If they differ from ours, say so plainly: a mismatched build is
    *  the usual cause of a mid-session "Network Protocol Error" (or of items
    *  this client cannot read), and naming both versions turns that decoder
    *  failure into something the player can actually fix. */
   private static void reportVersionMismatch(JsonObject root) {
      int serverProtocol = root.has(FfNet.KEY_PROTOCOL) && root.get(FfNet.KEY_PROTOCOL).isJsonPrimitive()
         ? root.get(FfNet.KEY_PROTOCOL).getAsInt()
         : FfNet.PROTOCOL;
      String serverVersion = root.has(FfNet.KEY_VERSION) && root.get(FfNet.KEY_VERSION).isJsonPrimitive()
         ? root.get(FfNet.KEY_VERSION).getAsString()
         : "unknown";
      root.remove(FfNet.KEY_PROTOCOL);
      root.remove(FfNet.KEY_VERSION);
      if (serverProtocol == FfNet.PROTOCOL) {
         return;
      }

      String ours = FortuneFavorsMod.modVersion();
      FortuneFavorsMod.LOGGER.warn(
         "Fortune & Favors build mismatch: server {} (network protocol {}), client {} (network protocol {}). Install the same build on both sides.",
         new Object[]{serverVersion, serverProtocol, ours, FfNet.PROTOCOL});
      Minecraft client = Minecraft.getInstance();
      if (client != null && client.player != null) {
         client.player.sendSystemMessage(
            Component.literal(
               "\u00a7c[Fortune & Favors] \u00a7fVersion mismatch: server \u00a7e" + serverVersion + "\u00a7f, you \u00a7e" + ours
                  + "\u00a7f. Match the builds to fix network/item errors."
            )
         );
      }
   }

   /** Builds the server-payload JSON (snake_case protocol keys). */
   public static String serverJson() {
      return FfConfigState.get().toServerJson().toString();
   }

   /** Runs a Cloth-dependent task, swallowing NoClassDefFoundError/LinkageError
    *  as a final safety net in case Cloth is present but broken. */
   private static void safely(String what, Runnable task) {
      try {
         task.run();
      } catch (LinkageError e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: " + what + " failed - continuing without Cloth Config", e);
         clothLoaded = false;
      } catch (RuntimeException e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: " + what + " failed - continuing without Cloth Config", e);
      }
   }

   private static <T> T safely(String what, Supplier<T> task, T fallback) {
      try {
         return task.get();
      } catch (LinkageError | RuntimeException e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: " + what + " failed - continuing without Cloth Config", e);
         return fallback;
      }
   }
}
