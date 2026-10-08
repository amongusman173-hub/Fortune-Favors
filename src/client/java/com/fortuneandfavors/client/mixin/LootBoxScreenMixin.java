package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.client.ScreenFx;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The loot box reveal, drawn behind the prize slot (slot 13) of the open box: a soft pulse while
 * it spins, then a burst in the prize's rarity colour - sparkles for every band, turning rays of
 * light from Epic up, and a bigger gold double halo of rays for a Legendary. The server says when;
 * this only draws while one of those cues is fresh, so no other screen is ever touched for long.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class LootBoxScreenMixin {
   @Shadow protected int leftPos;
   @Shadow protected int topPos;
   @Shadow @Final protected AbstractContainerMenu menu;

   @Unique private static final Identifier FF_RAYS = Identifier.fromNamespaceAndPath("fortuneandfavors", "lootfx/rays");
   @Unique private static final Identifier FF_GLOW = Identifier.fromNamespaceAndPath("fortuneandfavors", "lootfx/glow");
   @Unique private static final Identifier FF_STAR = Identifier.fromNamespaceAndPath("fortuneandfavors", "lootfx/star");
   /** Common, Rare, Epic, Legendary - the bands' own colours. */
   @Unique private static final int[] FF_BAND = {0xC8C8C8, 0x3FE2FF, 0xB44DFF, 0xFFD24A};

   @Inject(method = "extractSlots", at = @At("HEAD"))
   private void fortuneandfavors$lootFx(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
      int fx = ScreenFx.lootFx();
      if (fx == 0 || this.menu.slots.size() <= 13) {
         return;
      }
      Slot slot = this.menu.slots.get(13);
      float cx = slot.x + 8;   // the pose is already at leftPos/topPos here; adding them again pushed the burst off-screen
      float cy = slot.y + 8;
      float s = ScreenFx.lootAgeMs() / 1000.0F;
      if (fx == 9) {
         float pulse = 0.5F + 0.5F * (float)Math.sin(s * 9.0);
         fortuneandfavors$sprite(g, FF_GLOW, cx, cy, 40.0F + 12.0F * pulse, 0.0F, 0.35F + 0.25F * pulse, 0xFFFFFF);
         for (int i = 0; i < 3; i++) {
            double a = s * 3.0 + i * Math.PI * 2.0 / 3.0;
            fortuneandfavors$sprite(g, FF_STAR, cx + (float)Math.cos(a) * 18.0F, cy + (float)Math.sin(a) * 18.0F, 9.0F, s * 4.0F, 0.8F, 0xFFFFFF);
         }
         return;
      }
      int band = Math.max(0, Math.min(3, fx - 10));
      int rgb = FF_BAND[band];
      float t = Math.min(1.0F, s / 2.6F);
      float fade = 1.0F - t;
      if (band >= 2) {
         float size = band == 3 ? 150.0F : 100.0F;
         fortuneandfavors$sprite(g, FF_RAYS, cx, cy, size * (0.6F + 0.4F * Math.min(1.0F, s * 3.0F)), s * 0.9F, 0.85F * fade, rgb);
         if (band == 3) {
            fortuneandfavors$sprite(g, FF_RAYS, cx, cy, size * 0.7F, -s * 1.4F, 0.7F * fade, 0xFFFFFF);
         }
      }
      fortuneandfavors$sprite(g, FF_GLOW, cx, cy, 16.0F + 70.0F * (float)Math.min(1.0, s * 2.5), 0.0F, 0.9F * fade, rgb);
      int stars = new int[]{4, 6, 10, 16}[band];
      for (int i = 0; i < stars; i++) {
         double a = i * Math.PI * 2.0 / stars + s * 0.8;
         float r = 10.0F + 38.0F * (1.0F - (float)Math.pow(1.0F - Math.min(1.0F, s * 1.5F), 3.0));
         fortuneandfavors$sprite(g, FF_STAR, cx + (float)Math.cos(a) * r, cy + (float)Math.sin(a) * r, i % 2 == 0 ? 10.0F : 7.0F, s * 5.0F, fade, i % 3 == 0 ? 0xFFFFFF : rgb);
      }
   }

   @Inject(method = "removed", at = @At("HEAD"))
   private void fortuneandfavors$clearLootFx(CallbackInfo ci) {
      ScreenFx.clearLoot();
   }

   @Unique
   private static void fortuneandfavors$sprite(GuiGraphicsExtractor g, Identifier id, float cx, float cy, float size, float angle, float alpha, int rgb) {
      if (alpha <= 0.01F) {
         return;
      }
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(cx, cy);
      pose.rotate(angle);
      int half = Math.round(size / 2.0F);
      int argb = ((int)(Math.min(1.0F, alpha) * 255.0F) << 24) | (rgb & 0xFFFFFF);
      g.blitSprite(RenderPipelines.GUI_TEXTURED, id, -half, -half, half * 2, half * 2, argb);
      pose.popMatrix();
   }
}
