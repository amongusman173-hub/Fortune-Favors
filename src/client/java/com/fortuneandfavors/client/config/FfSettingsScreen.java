package com.fortuneandfavors.client.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The mod's own client settings screen: a grid of cards, one per setting. Saves on close. */
public class FfSettingsScreen extends Screen {
   private static final String[] DENSITY = {"Low", "Medium", "High"};
   private static final int CARD_W = 170;
   private static final int CARD_H = 40;
   private static final int GAP = 6;

   private record Card(String title, String desc, int accent, Supplier<String> value, Consumer<Button> press) {
   }

   private final Screen parent;
   private final List<Card> cards = new ArrayList<>();
   private int left;
   private int top;

   public FfSettingsScreen(Screen parent) {
      super(Component.literal("Fortune & Favors"));
      this.parent = parent;
      FfConfigState.ClientCategory c = FfConfigState.get().client;
      toggle("Custom VFX", "Particle amount for effects", 0x7FD8FF, () -> DENSITY[clamp(c.vfxDensity)], () -> c.vfxDensity = (clamp(c.vfxDensity) + 1) % 3);
      bool("Realm Snow", "Heavy snow in the frozen realm", 0xBDE8FF, () -> c.realmSnow, v -> c.realmSnow = v);
      bool("Corruption", "Mindbinder corruption overlay", 0xB06BFF, () -> c.corruptionOverlay, v -> c.corruptionOverlay = v);
      bool("Devour", "Devour screen overlay", 0xFF6B6B, () -> c.devourOverlay, v -> c.devourOverlay = v);
      bool("Linked", "Linked-soul screen overlay", 0x6BFFB0, () -> c.linkedOverlay, v -> c.linkedOverlay = v);
      bool("Sword Block", "Sword blocking pose", 0xE0E0E0, () -> c.swordBlockPose, v -> c.swordBlockPose = v);
      bool("Golden Apple", "Flash on eating", 0xFFD24A, () -> c.goldenAppleFlash, v -> c.goldenAppleFlash = v);
      bool("Deadeye", "Deadeye hit flash", 0xFF9A3C, () -> c.deadeyeFlash, v -> c.deadeyeFlash = v);
   }

   private static int clamp(int d) {
      return Math.max(0, Math.min(2, d));
   }

   private void bool(String t, String d, int accent, Supplier<Boolean> get, Consumer<Boolean> set) {
      toggle(t, d, accent, () -> get.get() ? "ON" : "OFF", () -> set.accept(!get.get()));
   }

   private void toggle(String t, String d, int accent, Supplier<String> value, Runnable flip) {
      cards.add(new Card(t, d, accent, value, b -> {
         flip.run();
         b.setMessage(Component.literal(value.get()));
         FfConfigManager.applyClientFx();
      }));
   }

   @Override
   protected void init() {
      int cols = 2;
      int rows = (cards.size() + 1) / 2;
      left = (width - (CARD_W * cols + GAP)) / 2;
      top = Math.max(44, (height - rows * (CARD_H + GAP)) / 2 + 10);
      for (int i = 0; i < cards.size(); i++) {
         Card card = cards.get(i);
         int x = left + (i % cols) * (CARD_W + GAP);
         int y = top + (i / cols) * (CARD_H + GAP);
         addRenderableWidget(Button.builder(Component.literal(card.value.get()), card.press::accept).bounds(x + CARD_W - 48, y + 4, 42, 20).build());
      }
      addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 50, top + rows * (CARD_H + GAP) + 6, 100, 20).build());
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
      int rows = (cards.size() + 1) / 2;
      int pw = CARD_W * 2 + GAP + 20;
      int px = left - 10;
      int py = top - 34;
      int ph = rows * (CARD_H + GAP) + 74;
      // Panel with corner brackets.
      g.fill(px, py, px + pw, py + ph, 0xC0101826);
      g.fill(px, py, px + pw, py + 1, 0xFF3A5A7A);
      g.fill(px, py + ph - 1, px + pw, py + ph, 0xFF3A5A7A);
      for (int[] k : new int[][]{{px, py}, {px + pw - 8, py}, {px, py + ph - 2}, {px + pw - 8, py + ph - 2}}) {
         g.fill(k[0], k[1], k[0] + 8, k[1] + 2, 0xFFBFE6FF);
      }
      g.text(font, "§6§lFORTUNE §e& §6§lFAVORS", width / 2 - font.width("FORTUNE & FAVORS") / 2 - 3, py + 8, 0xFFFFFFFF);
      g.text(font, "§7Client settings", px + 8, py + 22, 0xFFFFFFFF);
      for (int i = 0; i < cards.size(); i++) {
         Card card = cards.get(i);
         int x = left + (i % 2) * (CARD_W + GAP);
         int y = top + (i / 2) * (CARD_H + GAP);
         boolean hover = mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H;
         g.fill(x, y, x + CARD_W, y + CARD_H, hover ? 0xE0404A5E : 0xE02C3344);
         g.fill(x, y, x + 3, y + CARD_H, 0xFF000000 | card.accent);
         g.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, 0xFF1A1F2A);
         g.text(font, card.title, x + 9, y + 10, 0xFFFFFFFF);
         g.text(font, card.desc, x + 9, y + 28, 0xFFA0A8B8);
      }
      super.extractRenderState(g, mx, my, pt);
   }

   @Override
   public void onClose() {
      FfConfigManager.applyClientFx();
      FfConfigState.save();
      minecraft.gui.setScreen(parent);
   }
}
