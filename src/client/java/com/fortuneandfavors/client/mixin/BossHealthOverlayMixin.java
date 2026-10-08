package com.fortuneandfavors.client.mixin;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each of the mod's bosses gets a boss bar of its own art on a client with the mod. The server
 * still sends a plain coloured bar, so vanilla clients are unaffected; the bar is recognised here
 * by its name. Vanilla draws a bar in two calls (full-width background, then the progress fill);
 * both are swapped for the boss's sprite at the same width.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
   /** Name phrase (lower case) -> sprite key under textures/gui/sprites/boss_bar/. First match wins. */
   private static final String[][] FF_BARS = {
      {"time lord", "time_lord"},
      {"slime king", "slime_king"},
      {"scarlet devil", "scarlet_devil"},
      {"emerald sovereign", "emerald_sovereign"},
      {"puppeteer", "puppeteer"},
      {"clockwork king", "clockwork_king"},
      {"starbound magister", "starbound_magister"},
      {"drowned sovereign", "drowned_sovereign"},
      {"void shaper", "void_shaper"},
      {"gale warden", "gale_warden"},
      {"stone golem", "stone_golem"},
      {"snow queen", "snow_queen"},
      {"elder warden", "elder_warden"},
      {"king wither skeleton", "wither_king"},
      {"mindbinder", "mindbinder"},
      {"raid", "raid"},
      {"ender dragon", "ender_dragon"},
      {"wither", "wither"},
   };

   /**
    * The reworked bosses: each has a signature bar (patterned fill and an end-cap frame) and a
    * {@code _red} phase-two variant, shown when the server recolours the bar for phase two.
    */
   private static final java.util.Set<String> FF_PHASED = java.util.Set.of(
      "scarlet_devil", "emerald_sovereign", "puppeteer", "clockwork_king",
      "starbound_magister", "drowned_sovereign", "void_shaper", "gale_warden"
   );

   @Inject(
      method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void fortuneandfavors$customBar(
      GuiGraphicsExtractor graphics, int x, int y, BossEvent event, int width, Identifier[] bar, Identifier[] overlay, CallbackInfo ci
   ) {
      String key = fortuneandfavors$keyFor(event.getName().getString());
      if (key == null || bar.length == 0) {
         return;
      }
      boolean signature = key.equals("ender_dragon") || key.equals("time_lord") || key.equals("slime_king") || key.equals("wither")
         || key.equals("stone_golem") || key.equals("wither_king") || key.equals("mindbinder") || key.equals("snow_queen") || key.equals("elder_warden") || key.equals("raid")
         || FF_PHASED.contains(key);
      if (key.equals("wither") && event.getName().getString().toLowerCase(Locale.ROOT).contains("supercharged")) {
         key = "wither_supercharged";
      }
      // The dragon's and the Time Lord's bars change colour with their phase; so does their art.
      BossEvent.BossBarColor color = event.getColor();
      if (key.equals("ender_dragon") && event.getName().getString().toLowerCase(Locale.ROOT).contains("last stand")) {
         key = "ender_dragon_shattered";
      } else if (key.equals("ender_dragon") && color == BossEvent.BossBarColor.RED) {
         key = "ender_dragon_red";
      } else if (key.equals("ender_dragon") && color == BossEvent.BossBarColor.PURPLE) {
         key = "ender_dragon_purple";
      } else if (key.equals("time_lord") && color == BossEvent.BossBarColor.RED) {
         key = "time_lord_red";
      } else if (key.equals("snow_queen") && (color == BossEvent.BossBarColor.BLUE || color == BossEvent.BossBarColor.RED)) {
         key = color == BossEvent.BossBarColor.RED ? "snow_queen_red" : "snow_queen_blue";
      } else if (key.equals("elder_warden") && color == BossEvent.BossBarColor.RED) {
         key = "elder_warden_red";
      } else if (key.equals("mindbinder") && color == BossEvent.BossBarColor.RED) {
         key = "mindbinder_red";
      } else if (FF_PHASED.contains(key) && color == (key.equals("scarlet_devil") ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.RED)) {
         // Phase two. The Scarlet Devil's bar opens red and turns purple when she changes, so her
         // cue is purple; every other one turns red.
         key = key + "_red";
      }
      boolean progress = bar[0].getPath().endsWith("_progress");
      graphics.blitSprite(
         RenderPipelines.GUI_TEXTURED,
         Identifier.fromNamespaceAndPath("fortuneandfavors", "boss_bar/" + key + (progress ? "_progress" : "_background")),
         182, 5, 0, 0, x, y, width, 5
      );
      if (signature && !progress) {
         graphics.blitSprite(
            RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("fortuneandfavors", "boss_bar/" + key + "_frame"),
            198, 11, 0, 0, x - 8, y - 3, 198, 11
         );
      }
      ci.cancel();
   }

   private static String fortuneandfavors$keyFor(String name) {
      String lower = name.toLowerCase(Locale.ROOT);
      for (String[] entry : FF_BARS) {
         if (lower.contains(entry[0])) {
            return entry[1];
         }
      }
      return null;
   }
}
