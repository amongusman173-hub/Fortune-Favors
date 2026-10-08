package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.client.ScreenFx;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
   @Unique
   private static final Identifier DEVOUR_OVERLAY = Identifier.fromNamespaceAndPath("fortuneandfavors", "textures/misc/devour_overlay");
   @Unique
   private static final Identifier CORRUPTION_OVERLAY = Identifier.fromNamespaceAndPath("fortuneandfavors", "textures/misc/corruption_overlay");
   @Unique
   private static final Identifier LINKED_OVERLAY = Identifier.fromNamespaceAndPath("fortuneandfavors", "textures/misc/linked_overlay");

   @Inject(method = "extractRenderState", at = @At("TAIL"))
   private void fortuneandfavors$screenOverlays(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
      try {
         if (ScreenFx.devourActive()) {
            float pulse = 0.38F + 0.12F * (float)Math.sin(delta.getGameTimeDeltaTicks() * 0.4);
            ((HudInvoker)this).fortuneandfavors$extractTextureOverlay(extractor, DEVOUR_OVERLAY, pulse);
         }

         if (ScreenFx.corruptionActive()) {
            float pulse = 0.42F + 0.14F * (float)Math.sin(delta.getGameTimeDeltaTicks() * 0.6);
            ((HudInvoker)this).fortuneandfavors$extractTextureOverlay(extractor, CORRUPTION_OVERLAY, pulse);
         }

         if (ScreenFx.linkedActive()) {
            float pulse = 0.3F + 0.08F * (float)Math.sin(delta.getGameTimeDeltaTicks() * 0.3);
            ((HudInvoker)this).fortuneandfavors$extractTextureOverlay(extractor, LINKED_OVERLAY, pulse);
         }

         if (ScreenFx.goldenAppleActive()) {
            float progress = ScreenFx.goldenAppleProgress();
            int alpha = (int)((1.0F - progress) * 0.35F * 255.0F);
            float pulseAmt = 0.05F * (float)Math.sin(delta.getGameTimeDeltaTicks() * 1.2);
            alpha = Math.max(0, Math.min(102, alpha + (int)(pulseAmt * 255.0F)));
            int topColor = alpha << 24 | 0xFF0000 | 51200 | 0;
            int botColor = alpha << 24 | 13762560 | 25600 | 0;
            extractor.fillGradient(0, 0, extractor.guiWidth(), extractor.guiHeight(), topColor, botColor);
         }

         fortuneandfavors$timeStop(extractor);
         fortuneandfavors$beat(extractor);
         fortuneandfavors$fracture(extractor);
         fortuneandfavors$puppet(extractor);

         if (ScreenFx.deadeyeActive()) {
            // Subtle red flash when a Deadeye shot goes live - softer than the
            // golden apple, quick fade.
            float progress = ScreenFx.deadeyeProgress();
            int alpha = (int)((1.0F - progress) * 0.22F * 255.0F);
            alpha = Math.max(0, Math.min(64, alpha));
            int topColor = alpha << 24 | 0xA00000;
            int botColor = alpha << 24 | 0x500000;
            extractor.fillGradient(0, 0, extractor.guiWidth(), extractor.guiHeight(), topColor, botColor);
         }
      } catch (Exception var9) {
      }
   }

   /**
    * Time stopping, on screen: a white shutter that snaps into an inverted indigo/green wash, then
    * a cold drained tint with a dark vignette for as long as it holds - a faint pulse once a second,
    * like a held heartbeat - and a warm flash when time starts again.
    */
   @Unique
   private static void fortuneandfavors$timeStop(GuiGraphicsExtractor g) {
      int w = g.guiWidth();
      int h = g.guiHeight();
      long e = ScreenFx.timeStopElapsed();
      if (e >= 0L) {
         if (e < 120L) {
            g.fill(0, 0, w, h, fortuneandfavors$argb(0.85F - e / 120.0F * 0.35F, 0xFFFFFF));
            return;
         }
         if (e < 620L) {
            float k = 1.0F - (e - 120L) / 500.0F;
            g.fillGradient(0, 0, w, h, fortuneandfavors$argb(0.25F + 0.5F * k, 0x2B0F5E), fortuneandfavors$argb(0.25F + 0.5F * k, 0x0F5E3B));
            return;
         }
         float beat = Math.max(0.0F, 1.0F - (e % 1000L) / 250.0F) * 0.06F;
         g.fill(0, 0, w, h, fortuneandfavors$argb(0.30F + beat, 0x1A2238));
         int band = h / 4;
         g.fillGradient(0, 0, w, band, fortuneandfavors$argb(0.45F, 0x05060C), 0);
         g.fillGradient(0, h - band, w, h, 0, fortuneandfavors$argb(0.45F, 0x05060C));
         // A faint clock across the whole view: twelve gold marks, and a second hand that sweeps
         // once every two seconds while time is held.
         int cx = w / 2;
         int cy = h / 2;
         float radius = Math.min(w, h) * 0.38F;
         int mark = fortuneandfavors$argb(0.22F, 0xE2B042);
         for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2.0 * i / 12.0;
            int mx = cx + (int)(Math.cos(a) * radius);
            int my = cy + (int)(Math.sin(a) * radius);
            int s2 = i % 3 == 0 ? 3 : 2;
            g.fill(mx - s2, my - s2, mx + s2, my + s2, mark);
         }
         double hand = Math.PI * 2.0 * ((e % 2000L) / 2000.0) - Math.PI / 2.0;
         int handColor = fortuneandfavors$argb(0.18F, 0xFFFFFF);
         for (int i = 6; i < (int)(radius * 0.9F); i += 4) {
            int hx = cx + (int)(Math.cos(hand) * i);
            int hy = cy + (int)(Math.sin(hand) * i);
            g.fill(hx - 1, hy - 1, hx + 1, hy + 1, handColor);
         }
         return;
      }
      long r = ScreenFx.timeResumeElapsed();
      if (r >= 0L && r < 350L) {
         g.fill(0, 0, w, h, fortuneandfavors$argb(0.6F * (1.0F - r / 350.0F), 0xFFE8B0));
      }
   }

   /**
    * The Wither King's heartbeat: a frame of soul-dark light round the edge of the screen that
    * swells on every beat of his theme and fades before the next, harder and redder on each bar's
    * downbeat. Driven by the music's own start time, so it stays on the beat you hear.
    */
   @Unique
   private static void fortuneandfavors$beat(GuiGraphicsExtractor g) {
      if (!ScreenFx.beatActive()) {
         return;
      }
      double beats = com.fortuneandfavors.client.WitherMusic.beatsIntoLoop();
      if (beats < 0.0) {
         return;
      }
      double phase = beats - Math.floor(beats);
      boolean downbeat = ((long)Math.floor(beats)) % 4L == 0L;
      float pulse = (float)Math.pow(1.0 - phase, 3.0);
      int rgb = downbeat ? 0xA01030 : 0x6A1A9E;
      float peak = (downbeat ? 0.8F : 0.6F) * pulse;
      int w = g.guiWidth();
      int h = g.guiHeight();
      if (downbeat) {
         g.fill(0, 0, w, h, fortuneandfavors$argb(0.12F * pulse, 0x200008));
      }
      int steps = 8;
      int band = 3 + (int)(pulse * 5.0F);
      for (int i = 0; i < steps; i++) {
         int c = fortuneandfavors$argb(peak * (1.0F - (float)i / steps), rgb);
         int o = i * band;
         g.fill(o, o, w - o, o + band, c);
         g.fill(o, h - o - band, w - o, h - o, c);
         g.fill(o, o + band, o + band, h - o - band, c);
         g.fill(w - o - band, o + band, w - o, h - o - band, c);
      }
   }

   /**
    * The Mindbinder's FRACTURE: psychic static. Violet and cyan bars torn sideways across the
    * screen, re-cut every few frames, over a dark violet wash - strong at first, fading out.
    */
   @Unique
   private static void fortuneandfavors$fracture(GuiGraphicsExtractor g) {
      long age = ScreenFx.fractureAge();
      if (age < 0L) {
         return;
      }
      float k = 1.0F - age / 2500.0F;
      int w = g.guiWidth();
      int h = g.guiHeight();
      g.fill(0, 0, w, h, fortuneandfavors$argb(0.22F * k, 0x1A0630));
      java.util.Random r = new java.util.Random(age / 70L);
      for (int i = 0; i < 9; i++) {
         int y = r.nextInt(Math.max(1, h));
         int bh = 1 + r.nextInt(4);
         int shift = r.nextInt(40) - 20;
         g.fill(Math.max(0, shift), y, Math.min(w, w + shift), y + bh, fortuneandfavors$argb((0.15F + r.nextFloat() * 0.2F) * k, r.nextBoolean() ? 0xB04CFF : 0x4CE0FF));
      }
   }

   /** Working a seize: violet strings hanging from the top of the view toward its centre, and a tunnel closing in. */
   @Unique
   private static void fortuneandfavors$puppet(GuiGraphicsExtractor g) {
      float k = ScreenFx.puppetStrength();
      if (k <= 0.0F) {
         return;
      }
      int w = g.guiWidth();
      int h = g.guiHeight();
      int band = h / 5;
      g.fillGradient(0, 0, w, band, fortuneandfavors$argb(0.35F * k, 0x2A0A40), 0);
      g.fillGradient(0, h - band, w, h, 0, fortuneandfavors$argb(0.35F * k, 0x2A0A40));
      long t = System.currentTimeMillis();
      for (int s = 0; s < 3; s++) {
         int top = w / 2 + (s - 1) * w / 5;
         double sway = Math.sin(t / 300.0 + s) * 6.0;
         for (int y = 0; y < h / 2 - 10; y += 3) {
            int x = (int)(top + (w / 2 - top) * (y / (h / 2.0)) + sway * (1.0 - y / (h / 2.0)));
            g.fill(x, y, x + 1, y + 2, fortuneandfavors$argb(0.45F * k, 0xC07CFF));
         }
      }
   }

   @Unique
   private static int fortuneandfavors$argb(float alpha, int rgb) {
      return ((int)(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24) | (rgb & 0xFFFFFF);
   }
}
