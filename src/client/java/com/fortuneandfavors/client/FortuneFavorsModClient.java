package com.fortuneandfavors.client;

import com.fortuneandfavors.client.config.FfConfigManager;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.net.FfArenaProbePayload;
import com.fortuneandfavors.net.FfConfigPayload;
import com.fortuneandfavors.net.FfScreenFxPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.fortuneandfavors.net.FfClientContextPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FortuneFavorsModClient implements ClientModInitializer {

   public void onInitializeClient() {
      registerCreativeTab();
      // O opens the client settings screen (rebindable in Controls).
      net.minecraft.client.KeyMapping settingsKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
         new net.minecraft.client.KeyMapping("key.fortuneandfavors.settings", org.lwjgl.glfw.GLFW.GLFW_KEY_O, net.minecraft.client.KeyMapping.Category.MISC));
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         while (settingsKey.consumeClick()) {
            client.gui.setScreen(new com.fortuneandfavors.client.config.FfSettingsScreen(client.gui.screen()));
         }
      });
      BackpackMusic.init();
      // Self-healing boombox: if the engine ever drops a song we still want
      // (sound reload, level change), it is started again instead of going
      // quietly dead.
      ClientTickEvents.END_CLIENT_TICK.register(client -> BackpackMusic.tick());
      // The reworked Wither's theme: the client's half is the fades, the crossfades and the loop,
      // so it needs a receiver and a tick of its own (see WitherMusic).
      WitherMusic.init();
      ClientTickEvents.END_CLIENT_TICK.register(client -> WitherMusic.tick());
      // The mod's one-shot cues and the player they share: the credits after a dragon dies and the
      // theme a body hears on its first walk into the End. Both are a track held for a while and
      // then faded out, which is the client's job because a sound packet can neither hold nor fade
      // (see OneShotTrack, CreditsMusic and EndIntroMusic). The one-shot player drives its own tick,
      // so neither cue has to remember to.
      OneShotTrack.init();
      CreditsMusic.init();
      EndIntroMusic.init();
      ClientPlayNetworking.registerGlobalReceiver(
         FfScreenFxPayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> ScreenFx.set(payload.fx(), payload.active()))
      );
      // Boss VFX: with this receiver registered the server stops sending this client vanilla
      // particles for the fight and sends cues it draws itself (see FfVfxClient, net.FfVfx).
      FfVfxClient.init();
      FfConfigManager.init();
      ClientPlayNetworking.registerGlobalReceiver(
         FfConfigPayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> FfConfigManager.applySnapshot(payload.json()))
      );
      // An arena probe asks what this client actually holds for a floor the
      // server built. Answering it is the whole point: the server cannot see
      // the difference between "never built" and "never received".
      ClientPlayNetworking.registerGlobalReceiver(
         FfArenaProbePayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> DuelArenaProbe.onDump(payload, ctx.client()))
      );
      ClientPlayConnectionEvents.JOIN.register((Join)(handler, sender, client) -> {
         // Ask for the server's config snapshot. Read-only: the reply is
         // mirrored locally and nothing is sent back, so a rejoin can never
         // rewrite the server's own settings.
         sender.sendPacket(new FfConfigPayload(0, ""));
         // And tell the server what this client is running - the list of loaded mod ids,
         // nothing else. It is a self-report from a machine nobody has verified, so the
         // server treats it as a note on a moderator's readout and can never act on it on
         // its own. Sent once per join and never again, because it cannot change while
         // connected. See com.fortuneandfavors.anticheat.ClientContext.
         try {
            if (ClientPlayNetworking.canSend(FfClientContextPayload.TYPE)) {
               sender.sendPacket(new FfClientContextPayload(1, selfReportJson()));
            }
         } catch (Throwable ignored) {
         }
      });
      // Elytra Lunge is now spear-only: right-click while holding the Lunge
      // Spear. No client-side trigger is registered anymore - the server's
      // use-item hook owns the move, so vanilla clients work identically.
   }

   /**
    * This client's own mod list, as the one JSON shape the server's reader accepts.
    *
    * <p>Ids only. No versions, no configs, no file paths, no player data - the server has no
    * use for any of it, and the less a client volunteers the less there is to leak when the
    * payload is logged. A partial or failed read sends an empty list rather than skipping the
    * send, so "this client is running something" and "this client could not say" stay
    * distinguishable on the readout.
    */
   private static String selfReportJson() {
      try {
         com.google.gson.JsonObject root = new com.google.gson.JsonObject();
         com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
         for (net.fabricmc.loader.api.ModContainer mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            mods.add(mod.getMetadata().getId());
         }
         root.add("mods", mods);
         return root.toString();
      } catch (Throwable t) {
         return "{\"mods\":[]}";
      }
   }

   /** The "Fortune & Favors" creative tab. Brand-new creative tabs are NOT part
    *  of the server's registry sync in 26.2, so this has to be registered
    *  client-side - vanilla clients won't see the tab (they still get the tomes
    *  in the Combat tab via the server-side addition). Every custom enchantment
    *  tome lives here as a grabbable item. */
   private void registerCreativeTab() {
      try {
         CreativeModeTab tab = FabricCreativeModeTab.builder()
            .icon(() -> new ItemStack(Items.GOLD_INGOT))
            .title(Component.literal("Fortune & Favors"))
            .displayItems((params, output) -> {
               CCEnchantments.acceptAllTomes(output::accept, params.holders());
            })
            .build();
         Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("fortuneandfavors", "creative"), tab
         );
      } catch (Throwable t) {
         // Never let a tab registration crash the client.
      }
   }
}
