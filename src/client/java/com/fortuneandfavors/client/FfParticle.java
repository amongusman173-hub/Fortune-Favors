package com.fortuneandfavors.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/**
 * The mod's own particle: one of our sprites (textures/particle/, which vanilla's particle atlas
 * stitches for every namespace), tinted, fullbright, fading in and out, growing or shrinking over
 * its life. It is added straight to the particle engine, so it needs no registered particle type -
 * nothing about it exists on the server or on a vanilla client.
 */
final class FfParticle extends SingleQuadParticle {
   enum Tex {
      GLOW, SPARK, MOTE, RING, SHARD, RUNE, SMOKE, BLOB, ROCK, EMBER, CRYSTAL, STREAK, BUBBLE, ARC, FLARE, WISP, CRACK, FLAKE, ICICLE, FLAME, HEXRUNE, BOLT;

      final Identifier id = Identifier.fromNamespaceAndPath("fortuneandfavors", name().toLowerCase(java.util.Locale.ROOT));
   }

   /**
    * Every one of ours that is still alive. The particle engine stops ticking particles while the
    * level is frozen (/tick freeze, which the time stops use), and ours fade in on tick - so during
    * a stop they would sit invisible. {@link #service} keeps just ours moving.
    */
   private static final java.util.List<FfParticle> ALIVE = new java.util.ArrayList<>();

   private static boolean directAdd = true;

   private final float startSize;
   private final float endSize;
   private final float spin;
   private final float peakAlpha;

   private FfParticle(
      ClientLevel level, TextureAtlasSprite sprite, double x, double y, double z, double vx, double vy, double vz,
      int rgb, float size, int life, float grow, float spin, float drag, float alpha
   ) {
      super(level, x, y, z, sprite);
      this.xd = vx;
      this.yd = vy;
      this.zd = vz;
      this.hasPhysics = false;
      this.gravity = 0.0F;
      this.friction = drag;
      this.lifetime = Math.max(1, life);
      this.startSize = size;
      this.endSize = size * grow;
      this.quadSize = size;
      this.spin = spin;
      this.peakAlpha = alpha;
      this.roll = this.oRoll = random.nextFloat() * (float)(Math.PI * 2.0);
      setColor(((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
      setAlpha(0.0F);
   }

   /**
    * Spawns one. {@code grow} is the size multiplier reached at end of life, {@code drag} the
    * per-tick velocity keep (1 = no slowdown), {@code alpha} the peak opacity.
    */
   static FfParticle spawn(
      ClientLevel level, Tex tex, int rgb, double x, double y, double z, double vx, double vy, double vz,
      float size, int life, float grow, float spin, float drag, float alpha
   ) {
      Minecraft mc = Minecraft.getInstance();
      TextureAtlasSprite sprite = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES).getSprite(tex.id);
      FfParticle particle = new FfParticle(level, sprite, x, y, z, vx, vy, vz, rgb, size, life, grow, spin, drag, alpha);
      ALIVE.add(particle);
      // Straight into its render group rather than through engine.add(): that queue is only
      // drained by the engine's own tick, which does not run while the level is frozen. If that
      // ever fails at runtime (another mod reshaping the engine), fall back to the engine's add.
      net.minecraft.client.particle.ParticleEngine engine = mc.particleEngine;
      if (directAdd) {
         try {
            engine.particles.computeIfAbsent(particle.getGroup(), g -> engine.createParticleGroup((net.minecraft.client.particle.ParticleRenderType)g)).add(particle);
            return particle;
         } catch (Throwable t) {
            directAdd = false;
            FfVfxClient.LOG.warn("Fortune & Favors VFX: adding particles directly failed, using the engine queue instead", t);
         }
      }
      engine.add(particle);
      return particle;
   }

   /** Lets it fall: {@code g} is vanilla's gravity scale (1 = a dropped item). */
   FfParticle fall(float g) {
      this.gravity = g;
      return this;
   }

   /** Called every client tick: drops the dead, and ticks ours itself while the engine is frozen. */
   static void service(boolean frozen) {
      java.util.Iterator<FfParticle> it = ALIVE.iterator();
      while (it.hasNext()) {
         FfParticle p = it.next();
         if (!p.isAlive()) {
            it.remove();
         } else if (frozen) {
            p.tick();
         }
      }
   }

   @Override
   public void tick() {
      super.tick();
      oRoll = roll;
      roll += spin;
      float t = (float)age / (float)lifetime;
      quadSize = startSize + (endSize - startSize) * t;
      // Quick in, long out: light arrives at once and lingers as it goes.
      setAlpha(peakAlpha * (t < 0.12F ? t / 0.12F : 1.0F - (t - 0.12F) / 0.88F));
   }

   @Override
   protected Layer getLayer() {
      return Layer.TRANSLUCENT;
   }

   @Override
   protected int getLightCoords(float partialTick) {
      return 0xF000F0;
   }
}
