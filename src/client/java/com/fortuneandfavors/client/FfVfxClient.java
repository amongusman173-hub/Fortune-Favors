package com.fortuneandfavors.client;

import com.fortuneandfavors.client.FfParticle.Tex;
import com.fortuneandfavors.net.FfVfx;
import com.fortuneandfavors.net.FfVfxPayload;
import com.fortuneandfavors.net.FfVfxPayload.Cue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the server's visual cues (see {@code net.FfVfx}) on a client that has the mod, entirely
 * with the mod's own particles ({@link FfParticle}) - no vanilla particle is spawned for a cue.
 *
 * <p>The server only says what happened - a ring here, a beam from A to B, the dragon's last
 * stand starting - and this decides how it looks. Everything is spent against one per-tick
 * budget, so a busy fight gets sparser rather than turning into a wall of particles.
 */
public final class FfVfxClient {
   static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-vfx");
   private static boolean announced;
   private static final java.util.Set<Integer> FAILED = new java.util.HashSet<>();
   private static final int TICK_BUDGET = 300;
   private static final int MAX_LIVE = 40;
   private static final double RAW_DENSITY = 0.35;
   private static final int WHITE = 0xFFFFFF;
   private static final int VOID = 0x1A0628;
   private static final List<Live> LIVE = new ArrayList<>();
   private static final List<Live> SPAWNED = new java.util.ArrayList<>();
   private static int room = TICK_BUDGET;

   private static final class Live {
      final Cue cue;
      final int life;
      int age;

      Live(Cue cue, int life) {
         this.cue = cue;
         this.life = life;
      }

      double t() {
         return (double)age / life;
      }
   }

   private FfVfxClient() {
   }

   public static void init() {
      ClientPlayNetworking.registerGlobalReceiver(
         FfVfxPayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> accept(payload.cues()))
      );
      ClientTickEvents.END_CLIENT_TICK.register(FfVfxClient::tick);
      // /ffvfx: plays a few effects in front of you with no server involved, so "the effects do
      // not draw" and "the server is not sending them" can be told apart in five seconds.
      net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
         dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("ffvfx").executes(c -> {
            selfTest();
            c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(
               "\u00a7dF&F VFX test played. \u00a77If you see nothing, send logs/latest.log (look for 'fortuneandfavors-vfx')."));
            return 1;
         }))
      );
   }

   private static void selfTest() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         return;
      }
      Vec3 look = mc.player.getLookAngle();
      Vec3 at = mc.player.position().add(look.x * 6.0, 0.0, look.z * 6.0);
      Vec3 side = new Vec3(-look.z, 0.0, look.x);
      net.minecraft.core.particles.ParticleOptions p = ParticleTypes.END_ROD;
      accept(List.of(
         new Cue(FfVfx.CLOCK_BURST, p, (float)at.x, (float)at.y + 1.0F, (float)at.z, 0, 0, 0, 6.0F, 0, 0, 0xE2B042),
         new Cue(FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, (float)(at.x + side.x * 4), (float)at.y + 1.5F, (float)(at.z + side.z * 4),
            (float)side.x, 0, (float)side.z, 1.6F, 40, 0, 0xB06BFF),
         new Cue(FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, (float)(at.x - side.x * 4), (float)at.y, (float)(at.z - side.z * 4), 0, 0, 0, 4.0F, 0, 0, 0x3FE0FF),
         new Cue(FfVfx.GOO_SPLASH, ParticleTypes.ITEM_SLIME, (float)at.x, (float)at.y, (float)at.z, 0, 0, 0, 2.5F, 0, 0, 0x6FE36A)
      ));
   }

   private static void tick(Minecraft mc) {
      room = TICK_BUDGET;
      ClientLevel level = mc.level;
      if (level == null) {
         LIVE.clear();
         FfParticle.service(false);
         return;
      }
      if (mc.isPaused()) {
         return;
      }
      FfParticle.service(!level.tickRateManager().runsNormally());
      try {
         realmSnow(mc, level);
      } catch (Throwable t) {
         failed(-1, t);
      }
      Iterator<Live> it = LIVE.iterator();
      while (it.hasNext()) {
         Live l = it.next();
         if (++l.age >= l.life) {
            it.remove();
            continue;
         }
         try {
            animate(level, l);
         } catch (Throwable t) {
            it.remove();
            failed(l.cue.kind(), t);
         }
      }
      // Effects a set piece starts mid-animation join here, after the walk over LIVE is done.
      LIVE.addAll(SPAWNED);
      SPAWNED.clear();
   }

   private static void accept(List<Cue> cues) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level == null) {
         return;
      }
      if (!announced) {
         announced = true;
         LOG.info("Fortune & Favors VFX: receiving effect cues from the server ({} in the first batch)", cues.size());
      }
      for (Cue c : cues) {
         try {
            switch (c.kind()) {
               case FfVfx.RAW -> raw(level, c);
               case FfVfx.RING -> ring(level, c);
               case FfVfx.BEAM -> beam(level, c);
               case FfVfx.CLOCK -> clock(level, c.x(), c.y(), c.z(), c.a(), c.b(), c.color());
               case FfVfx.RIFT -> start(level, c, 24, false);
               case FfVfx.NOVA -> start(level, c, 14, false);
               case FfVfx.PILLAR -> start(level, c, 16, false);
               case FfVfx.TEAR -> start(level, c, Math.max(6, Math.min(80, (int)c.b())), false);
               case FfVfx.CLOCK_BURST -> start(level, c, 30, true);
               case FfVfx.ULTIMATE, FfVfx.DEATH, FfVfx.GATEWAY, FfVfx.RIFT_DEATH -> start(level, c, Math.max(1, Math.min(600, (int)c.a())), true);
               case FfVfx.METEOR -> start(level, c, Math.max(2, Math.min(60, (int)c.b())), true);
               case FfVfx.GOO_SPLASH -> start(level, c, 10, false);
               case FfVfx.GEYSER -> start(level, c, 12, false);
               case FfVfx.ROCKBURST -> start(level, c, 12, false);
               case FfVfx.STOP -> LIVE.removeIf(l -> l.cue.kind() == FfVfx.ULTIMATE
                  && Math.abs(l.cue.x() - c.x()) < 12.0F && Math.abs(l.cue.y() - c.y()) < 12.0F && Math.abs(l.cue.z() - c.z()) < 12.0F);
               case FfVfx.CLASH -> start(level, c, 8, true);
               case FfVfx.SLASH -> start(level, c, Math.max(2, Math.min(40, (int)c.a())) + 16, true);
               case FfVfx.MUZZLE -> muzzle(level, c);
               case FfVfx.FROST_NOVA -> start(level, c, 16, true);
               case FfVfx.ICE_ERUPT -> start(level, c, 13, false);
               case FfVfx.ICICLE -> start(level, c, Math.max(2, Math.min(60, (int)c.a())) + 2, true);
               case FfVfx.FROST_LANCE -> frostLance(level, c);
               case FfVfx.WHITEOUT -> start(level, c, Math.max(2, Math.min(100, (int)c.a())) + 2, true);
               case FfVfx.SNOW_SPAWN, FfVfx.SNOW_DEATH -> start(level, c, Math.max(4, Math.min(200, (int)c.a())), true);
               case FfVfx.FROST_SPRAY -> frostSpray(level, c);
               case FfVfx.ICE_TRAIL -> start(level, c, Math.max(2, Math.min(80, (int)c.b())), false);
               case FfVfx.ICE_BURST -> iceShatter(level, c.x(), c.y(), c.z(), Math.max(0.3, c.a()), c.color());
               case FfVfx.SONIC -> sonicLance(level, c);
               case FfVfx.RESONANCE -> start(level, c, Math.max(10, Math.min(200, (int)c.a())), true);
               case FfVfx.ROD_ORBIT, FfVfx.MIND_AURA, FfVfx.SNOW_AURA, FfVfx.SCULK_AURA -> {
                  // One orbit per body: a renewal replaces the old one rather than doubling the rods.
                  LIVE.removeIf(l -> l.cue.kind() == c.kind() && l.cue.count() == c.count());
                  start(level, c, Math.max(2, Math.min(200, (int)c.b())), true);
               }
               case FfVfx.WORMHOLE -> start(level, c, c.b() > 0.5F ? 16 : 18, true);
               case FfVfx.SUMMON_CIRCLE -> start(level, c, Math.max(4, Math.min(400, (int)c.b())), true);
               default -> {
               }
            }
         } catch (Throwable t) {
            // A cue this build cannot draw is skipped, never allowed to take the client down mid-fight -
            // but it is reported once, so a broken effect is never silent.
            failed(c.kind(), t);
         }
      }
   }

   /** Set pieces always get a slot; ordinary effects are dropped when the screen is already busy. */
   private static void start(ClientLevel level, Cue c, int life, boolean setPiece) {
      if (!setPiece && LIVE.size() >= MAX_LIVE) {
         return;
      }
      Live l = new Live(c, life);
      LIVE.add(l);
      animate(level, l);
   }

   private static void animate(ClientLevel level, Live l) {
      switch (l.cue.kind()) {
         case FfVfx.RIFT -> rift(level, l);
         case FfVfx.NOVA -> nova(level, l);
         case FfVfx.PILLAR -> pillar(level, l);
         case FfVfx.TEAR -> tear(level, l);
         case FfVfx.CLOCK_BURST -> clockBurst(level, l);
         case FfVfx.ULTIMATE -> ultimate(level, l);
         case FfVfx.DEATH -> death(level, l);
         case FfVfx.GATEWAY -> gateway(level, l);
         case FfVfx.METEOR -> meteor(level, l);
         case FfVfx.GOO_SPLASH -> gooSplash(level, l);
         case FfVfx.GEYSER -> geyser(level, l);
         case FfVfx.ROCKBURST -> rockBurst(level, l);
         case FfVfx.RIFT_DEATH -> riftDeath(level, l);
         case FfVfx.SUMMON_CIRCLE -> summonCircle(level, l);
         case FfVfx.WORMHOLE -> wormhole(level, l);
         case FfVfx.CLASH -> clash(level, l);
         case FfVfx.SLASH -> slash(level, l);
         case FfVfx.MIND_AURA -> mindAura(level, l);
         case FfVfx.SNOW_AURA -> snowAura(level, l);
         case FfVfx.FROST_NOVA -> frostNova(level, l);
         case FfVfx.ICE_ERUPT -> iceErupt(level, l);
         case FfVfx.ICICLE -> icicle(level, l);
         case FfVfx.WHITEOUT -> whiteout(level, l);
         case FfVfx.SNOW_SPAWN -> snowSpawn(level, l);
         case FfVfx.SNOW_DEATH -> snowDeath(level, l);
         case FfVfx.ICE_TRAIL -> iceTrail(level, l);
         case FfVfx.RESONANCE -> resonance(level, l);
         case FfVfx.SCULK_AURA -> sculkAura(level, l);
         case FfVfx.ROD_ORBIT -> rodOrbit(level, l);
         default -> {
         }
      }
   }

   // ------------------------------------------------------------------ one-shot cues

   /** Ad-hoc particles: vanilla's spread rules at half density, drawn with our sprites in the type's colour. */
   private static void raw(ClientLevel level, Cue c) {
      RandomSource r = level.getRandom();
      ParticleOptions o = c.particle();
      ParticleType<?> type = o.getType();
      int rgb = tintOf(o);
      boolean boom = type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER
         || type == ParticleTypes.GUST_EMITTER_LARGE || type == ParticleTypes.FLASH;
      boolean gel = type == ParticleTypes.ITEM_SLIME;
      Tex tex = boom ? Tex.GLOW
         : gel ? Tex.BLOB
         : type == ParticleTypes.BLOCK || type == ParticleTypes.FALLING_DUST ? Tex.ROCK
         : type == ParticleTypes.ELECTRIC_SPARK ? Tex.BOLT
         : type == ParticleTypes.END_ROD || type == ParticleTypes.FIREWORK ? Tex.SPARK
         : type == ParticleTypes.ENCHANT ? Tex.HEXRUNE
         : type == ParticleTypes.SNOWFLAKE ? Tex.FLAKE
         : type == ParticleTypes.FLAME || type == ParticleTypes.SOUL_FIRE_FLAME || type == ParticleTypes.SOUL || type == ParticleTypes.SCULK_SOUL ? Tex.FLAME
         : type == ParticleTypes.PORTAL || type == ParticleTypes.REVERSE_PORTAL ? Tex.MOTE
         : type == ParticleTypes.CLOUD || type == ParticleTypes.GUST || type == ParticleTypes.WHITE_ASH ? Tex.SMOKE
         : Tex.GLOW;
      double want = c.count() * RAW_DENSITY;
      int n = (int)want + (r.nextDouble() < want - (int)want ? 1 : 0);
      for (int i = 0; i < n; i++) {
         double s = c.a() * 0.5;
         FfParticle made = p(level, tex, rgb,
            c.x() + r.nextGaussian() * c.ax(), c.y() + r.nextGaussian() * c.ay(), c.z() + r.nextGaussian() * c.az(),
            r.nextGaussian() * s, r.nextGaussian() * s + (gel ? 0.12 : 0.0), r.nextGaussian() * s,
            boom ? 2.5F : tex == Tex.SMOKE ? 0.5F : gel ? 0.2F + r.nextFloat() * 0.12F : 0.22F, boom ? 8 : 16 + r.nextInt(12),
            boom ? 2.0F : 0.6F, 0.05F, 0.9F);
         if ((gel || tex == Tex.ROCK) && made != null) {
            made.fall(tex == Tex.ROCK ? 1.0F : 0.7F);
         }
      }
   }

   /**
    * A ring. Fast rings are shockwaves: evenly spaced glow, every point moving straight out (or in),
    * sparks on every third. Slow rings are markers redrawn every tick, so they are drawn as a few
    * short-lived turning runes - a spinning collar, never a smear.
    */
   private static void ring(ClientLevel level, Cue c) {
      double radius = c.a();
      int rgb = tintOf(c.particle());
      if (Math.abs(c.b()) < 0.3) {
         if ((level.getGameTime() & 1L) == 1L) {
            return;
         }
         int pts = clamp((int)(Math.PI * 2.0 * radius / 2.6), 6, 28);
         double phase = level.getGameTime() * 0.05;
         for (int i = 0; i < pts; i++) {
            double a = phase + Math.PI * 2.0 * i / pts;
            p(level, c.particle().getType() == ParticleTypes.ITEM_SLIME ? Tex.BLOB : Tex.RUNE, rgb, c.x() + Math.cos(a) * radius, c.y(), c.z() + Math.sin(a) * radius,
               0.0, 0.0, 0.0, 0.38F, 5, 1.0F, 0.1F, 1.0F);
         }
         return;
      }
      int pts = clamp((int)(Math.PI * 2.0 * radius / 1.2), 12, 56);
      double phase = level.getRandom().nextDouble() * Math.PI * 2.0;
      double out = c.b() * 0.14;
      for (int i = 0; i < pts; i++) {
         double a = phase + Math.PI * 2.0 * i / pts;
         double cos = Math.cos(a);
         double sin = Math.sin(a);
         p(level, Tex.GLOW, rgb, c.x() + cos * radius, c.y(), c.z() + sin * radius, cos * out, 0.01, sin * out, 0.7F, 14, 0.3F, 0.0F, 0.9F);
         if (i % 3 == 0) {
            p(level, Tex.SPARK, WHITE, c.x() + cos * radius, c.y(), c.z() + sin * radius, cos * out * 1.3, 0.03, sin * out * 1.3, 0.25F, 12, 0.5F, 0.2F, 0.9F);
         }
      }
   }

   /** A beam: a white-hot core, a coloured sheath, and a two-strand helix turning with the clock. */
   private static void beam(ClientLevel level, Cue c) {
      Vec3 from = new Vec3(c.x(), c.y(), c.z());
      Vec3 to = new Vec3(c.ax(), c.ay(), c.az());
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.01) {
         return;
      }
      Vec3 dir = delta.scale(1.0 / length);
      Vec3 u = dir.cross(Math.abs(dir.y) > 0.95 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0)).normalize();
      Vec3 v = dir.cross(u);
      int rgb = c.color();
      int steps = clamp((int)(length / 1.0), 2, 40);
      double phase = level.getGameTime() * 0.5;
      for (int i = 0; i <= steps; i++) {
         double t = (double)i / steps;
         Vec3 at = from.add(delta.scale(t));
         p(level, Tex.GLOW, WHITE, at.x, at.y, at.z, 0.0, 0.0, 0.0, 0.5F, 3, 1.0F, 0.0F, 1.0F);
         if ((i & 1) == 0) {
            p(level, Tex.GLOW, rgb, at.x, at.y, at.z, 0.0, 0.0, 0.0, 1.1F, 4, 1.3F, 0.0F, 0.55F);
         }
         if (length > 40.0 || (i & 1) == 1) {
            continue;
         }
         for (int strand = 0; strand < 2; strand++) {
            double ang = phase + t * length * 1.3 + strand * Math.PI;
            Vec3 h = at.add(u.scale(Math.cos(ang) * 0.45)).add(v.scale(Math.sin(ang) * 0.45));
            p(level, Tex.MOTE, rgb, h.x, h.y, h.z, 0.0, 0.0, 0.0, 0.18F, 5, 0.5F, 0.0F, 1.0F);
         }
      }
   }

   /** One frame of a clock face: twelve runes, a white minute hand on the sweep, a gold hour hand. */
   private static void clock(ClientLevel level, double x, double y, double z, double radius, double sweep, int rgb) {
      if ((level.getGameTime() & 1L) == 1L) {
         return; // redrawn every tick by the server; every other tick with longer life looks the same at half the cost
      }
      for (int i = 0; i < 12; i++) {
         double a = Math.PI * 2.0 * i / 12.0 - Math.PI / 2.0;
         p(level, i % 3 == 0 ? Tex.RUNE : Tex.MOTE, rgb, x + Math.cos(a) * radius, y, z + Math.sin(a) * radius,
            0.0, 0.0, 0.0, i % 3 == 0 ? 0.45F : 0.22F, 5, 1.0F, 0.0F, 1.0F);
      }
      double minute = Math.PI * 2.0 * sweep - Math.PI / 2.0;
      double hour = Math.PI * 2.0 * sweep / 12.0 - Math.PI / 2.0;
      for (int i = 1; i <= 7; i++) {
         double f = i / 7.0;
         p(level, Tex.GLOW, WHITE, x + Math.cos(minute) * radius * 0.88 * f, y, z + Math.sin(minute) * radius * 0.88 * f,
            0.0, 0.0, 0.0, 0.22F, 3, 1.0F, 0.0F, 1.0F);
         if (i <= 4) {
            p(level, Tex.GLOW, rgb, x + Math.cos(hour) * radius * 0.55 * f / (4.0 / 7.0), y, z + Math.sin(hour) * radius * 0.55 * f / (4.0 / 7.0),
               0.0, 0.0, 0.0, 0.26F, 3, 1.0F, 0.0F, 1.0F);
         }
      }
      p(level, Tex.GLOW, WHITE, x, y, z, 0.0, 0.0, 0.0, 0.45F, 3, 1.0F, 0.0F, 1.0F);
   }

   // ------------------------------------------------------------------ cues that play out over time

   /** Three arms of the void spiralling into a point, a dark halo rolling out at the start. */
   private static void rift(ClientLevel level, Live l) {
      Cue c = l.cue;
      double size = c.a();
      double cy = c.y() + 1.0;
      if (l.age == 0) {
         p(level, Tex.GLOW, c.color(), c.x(), cy, c.z(), 0.0, 0.0, 0.0, (float)(2.5 * size), 8, 1.8F, 0.0F, 0.9F);
         p(level, Tex.RING, c.color(), c.x(), cy, c.z(), 0.0, 0.0, 0.0, (float)size, 14, 6.0F, 0.0F, 0.9F);
      }
      double reach = size * 1.8 * (1.0 - l.t() * 0.6);
      for (int arm = 0; arm < 3; arm++) {
         double ang = l.age * 0.45 + arm * Math.PI * 2.0 / 3.0;
         double ox = Math.cos(ang) * reach;
         double oz = Math.sin(ang) * reach;
         p(level, Tex.MOTE, c.color(), c.x() + ox, cy, c.z() + oz, -ox / 10.0, 0.0, -oz / 10.0, 0.3F, 10, 0.3F, 0.0F, 1.0F);
         p(level, Tex.SMOKE, VOID, c.x() + ox * 0.7, cy, c.z() + oz * 0.7, -ox / 14.0, 0.0, -oz / 14.0, 0.8F, 12, 0.4F, 0.1F, 0.85F);
      }
      if (l.age % 2 == 0) {
         p(level, Tex.SHARD, WHITE, c.x(), cy, c.z(), 0.0, 0.18, 0.0, 0.4F, 12, 0.6F, 0.0F, 0.95F);
      }
   }

   /** A flash and a halo, a burst of sparks on a sphere, then a ground wave rolling to the full radius. */
   private static void nova(ClientLevel level, Live l) {
      Cue c = l.cue;
      double radius = c.a();
      int rgb = c.color() == 0 ? tintOf(c.particle()) : c.color();
      if (l.age == 0) {
         flash(level, c.x(), c.y() + 0.5, c.z(), rgb, (float)Math.min(8.0, 1.5 + radius * 0.3));
         p(level, Tex.RING, rgb, c.x(), c.y() + 0.5, c.z(), 0.0, 0.0, 0.0, 1.0F, 12, (float)Math.min(40.0, radius * 1.6), 0.0F, 0.85F);
         int n = 24;
         double speed = Math.min(0.7, radius * 0.05);
         double golden = Math.PI * (3.0 - Math.sqrt(5.0));
         for (int i = 0; i < n; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / n;
            double rad = Math.sqrt(1.0 - y * y);
            double ang = golden * i;
            p(level, Tex.SPARK, i % 2 == 0 ? WHITE : rgb, c.x(), c.y() + 0.5, c.z(),
               Math.cos(ang) * rad * speed, y * speed, Math.sin(ang) * rad * speed, 0.3F, 18, 0.4F, 0.2F, 0.9F);
         }
      }
      double t = (l.age + 1.0) / l.life;
      double at = Math.max(0.5, radius * (1.0 - Math.pow(1.0 - t, 3.0)));
      int pts = clamp((int)(Math.PI * 2.0 * at / 1.6), 10, 40);
      double phase = l.age * 0.2;
      for (int i = 0; i < pts; i++) {
         double ang = phase + Math.PI * 2.0 * i / pts;
         double x = c.x() + Math.cos(ang) * at;
         double z = c.z() + Math.sin(ang) * at;
         p(level, Tex.GLOW, rgb, x, c.y() + 0.2, z, 0.0, 0.03, 0.0, 1.1F, 10, 0.4F, 0.0F, 0.8F);
         if (l.age % 4 == 0 && i % 5 == 0) {
            p(level, Tex.SMOKE, VOID, x, c.y() + 0.3, z, 0.0, 0.04, 0.0, 0.9F, 20, 1.6F, 0.05F, 0.6F);
         }
      }
   }

   /** A column that climbs from the floor: light shards riding up a turning core. */
   private static void pillar(ClientLevel level, Live l) {
      Cue c = l.cue;
      double y = c.y() + c.a() * l.t();
      for (int k = 0; k < 4; k++) {
         double ang = l.age * 0.7 + k * Math.PI / 2.0;
         p(level, Tex.SHARD, c.color(), c.x() + Math.cos(ang) * 0.45, y, c.z() + Math.sin(ang) * 0.45, 0.0, 0.05, 0.0, 0.5F, 14, 0.4F, 0.0F, 1.0F);
      }
      p(level, Tex.GLOW, WHITE, c.x(), y + 0.3, c.z(), 0.0, 0.0, 0.0, 0.9F, 6, 0.5F, 0.0F, 1.0F);
   }

   /**
    * A rift tearing open. Its outline is jagged but fixed for the whole life of the rift (seeded
    * from where it is), so it reads as a crack in the air and never flickers. Overlapping glows draw
    * a continuous rim - white-hot on the inside, the rift's colour around it - over a dark core of
    * void smoke, with the odd crack of light along the seam. Edges open over the first third, hold,
    * and seal at the end. Redrawn every other tick with particles that outlive the gap.
    */
   private static void tear(ClientLevel level, Live l) {
      Cue c = l.cue;
      double halfH = c.a();
      double t = l.t();
      double open = t < 0.35 ? 1.0 - Math.pow(1.0 - t / 0.35, 3.0) : t > 0.8 ? Math.max(0.0, (1.0 - t) / 0.2) : 1.0;
      double halfW = halfH * 0.3 * open;
      Vec3 side = new Vec3(c.ax(), 0.0, c.az());
      side = side.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : side.normalize();
      RandomSource r = level.getRandom();
      if (l.age == 0) {
         flash(level, c.x(), c.y(), c.z(), c.color(), (float)halfH);
      }
      if ((l.age & 1) == 1) {
         return;
      }
      long seed = Double.doubleToLongBits(c.x() * 31.0 + c.z() * 17.0 + c.y());
      int n = clamp((int)(halfH * 4.0), 5, 12);
      for (int k = -n; k <= n; k++) {
         double s = (double)k / n;
         double y = c.y() + s * halfH;
         double lens = Math.sqrt(Math.max(0.0, 1.0 - s * s));
         for (int sign = -1; sign <= 1; sign += 2) {
            // A fixed zigzag: each segment of each edge has its own stable kink.
            double kink = (((seed ^ (k * 0x9E3779B97F4A7C15L) ^ sign) >>> 11) % 1000) / 1000.0 - 0.5;
            double w = halfW * lens * (1.0 + kink * 0.5) + halfH * 0.04 * kink * open;
            double ex = c.x() + side.x * w * sign;
            double ez = c.z() + side.z * w * sign;
            p(level, Tex.GLOW, c.color(), ex, y, ez, 0.0, 0.0, 0.0, 0.42F, 5, 1.0F, 0.0F, 1.0F, 0.75F);
            p(level, Tex.GLOW, WHITE, c.x() + side.x * w * sign * 0.8, y, c.z() + side.z * w * sign * 0.8,
               0.0, 0.0, 0.0, 0.2F, 5, 1.0F, 0.0F, 1.0F, 0.9F);
         }
      }
      if (halfW > 0.05) {
         p(level, Tex.SMOKE, VOID, c.x(), c.y() + (r.nextDouble() - 0.5) * halfH, c.z(),
            0.0, 0.0, 0.0, (float)(halfW * 1.8), 8, 0.9F, 0.03F, 1.0F, 0.95F);
         // Motes pulled across the opening into the seam.
         double reach = halfW * 3.0;
         double sign = r.nextBoolean() ? 1.0 : -1.0;
         p(level, Tex.MOTE, c.color(), c.x() + side.x * reach * sign, c.y() + (r.nextDouble() - 0.5) * halfH * 1.4, c.z() + side.z * reach * sign,
            -side.x * reach * sign / 8.0, 0.0, -side.z * reach * sign / 8.0, 0.2F, 8, 0.2F, 0.0F, 1.0F);
      }
      // Now and then, a crack of light runs the length of the seam.
      if (open > 0.5 && r.nextInt(6) == 0) {
         double y0 = c.y() - halfH * 0.9;
         for (int i = 0; i <= 10; i++) {
            double jag = (r.nextDouble() - 0.5) * halfW * 0.6;
            p(level, Tex.SPARK, WHITE, c.x() + side.x * jag, y0 + halfH * 1.8 * i / 10.0, c.z() + side.z * jag,
               0.0, 0.0, 0.0, 0.25F, 4, 0.6F, 0.0F, 1.0F);
         }
      }
   }

   /** Time stopping: a white shutter, a halo, the face snapping in, and runes racing outward. */
   private static void clockBurst(ClientLevel level, Live l) {
      Cue c = l.cue;
      if (l.age == 0) {
         flash(level, c.x(), c.y(), c.z(), WHITE, (float)Math.min(6.0, c.a() * 0.4));
         p(level, Tex.RING, c.color(), c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 1.0F, 16, (float)Math.min(40.0, c.a() * 2.0), 0.0F, 0.9F);
         clock(level, c.x(), c.y(), c.z(), Math.min(4.0, c.a() * 0.25), 0.0, c.color());
         int n = 24;
         for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0 * i / n;
            double sp = Math.min(1.2, c.a() * 0.07);
            p(level, Tex.RUNE, c.color(), c.x(), c.y(), c.z(), Math.cos(a) * sp, 0.0, Math.sin(a) * sp, 0.5F, 16, 0.6F, 0.3F, 0.88F);
         }
      }
      // The dome: a shell that races out to the edge of the stop, hangs, then collapses back in.
      if ((l.age & 1) == 0) {
         double tt = (double)l.age / l.life;
         double shell = tt < 0.4 ? 1.0 - Math.pow(1.0 - tt / 0.4, 3.0) : tt < 0.6 ? 1.0 : 1.0 - 0.75 * Math.pow((tt - 0.6) / 0.4, 2.0);
         double sr = Math.max(0.5, c.a() * shell);
         int n = 64;
         double golden = Math.PI * (3.0 - Math.sqrt(5.0));
         double spin = l.age * 0.05;
         for (int i = 0; i < n; i++) {
            double yy = 1.0 - 2.0 * (i + 0.5) / n;
            double rr = Math.sqrt(1.0 - yy * yy);
            double ang = golden * i + spin;
            p(level, i % 5 == 0 ? Tex.SPARK : Tex.GLOW, i % 5 == 0 ? WHITE : c.color(),
               c.x() + Math.cos(ang) * rr * sr, c.y() + yy * sr, c.z() + Math.sin(ang) * rr * sr,
               0.0, 0.0, 0.0, i % 5 == 0 ? 0.35F : 0.6F, 3, 1.0F, 0.0F, 1.0F, 0.7F);
         }
      }
      if (l.age >= 16) {
         return;
      }
      double t = (l.age + 1.0) / 16.0;
      double rad = c.a() * (1.0 - Math.pow(1.0 - t, 3.0));
      int pts = clamp((int)(Math.PI * 2.0 * rad / 1.8), 12, 48);
      for (int i = 0; i < pts; i++) {
         double a = Math.PI * 2.0 * i / pts;
         p(level, (i & 1) == 0 ? Tex.SPARK : Tex.GLOW, (i & 1) == 0 ? WHITE : c.color(),
            c.x() + Math.cos(a) * rad, c.y(), c.z() + Math.sin(a) * rad, 0.0, 0.0, 0.0, 0.4F, 5, 0.6F, 0.0F, 0.9F);
      }
   }

   /**
    * The dragon's last stand, as an ultimate: the island's light is drawn into the body, a crimson
    * pillar rises from the middle of the arena under a sigil that widens with every second, embers
    * climb the whole bowl, an eclipse halo hangs over the dragon, and it ends in a white-out.
    */
   private static void ultimate(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double t = l.t();
      double px = c.x(), py = c.y(), pz = c.z();
      double cx = c.ax(), cy = c.ay(), cz = c.az();
      int red = c.color();

      if (l.age == 0) {
         flash(level, px, py, pz, red, 10.0F);
         p(level, Tex.RING, red, px, py, pz, 0.0, 0.0, 0.0, 2.0F, 20, 30.0F, 0.0F, 0.9F);
      }
      // The pull: motes from a wide sphere converging on the body.
      for (int i = 0; i < 4; i++) {
         double th = r.nextDouble() * Math.PI * 2.0;
         double ph = Math.acos(r.nextDouble() * 2.0 - 1.0);
         double rr = 16.0 + r.nextDouble() * 6.0;
         double ox = Math.sin(ph) * Math.cos(th) * rr, oy = Math.cos(ph) * rr, oz = Math.sin(ph) * Math.sin(th) * rr;
         p(level, i % 3 == 0 ? Tex.SPARK : Tex.MOTE, i % 2 == 0 ? red : WHITE, px + ox, py + oy, pz + oz,
            -ox / 20.0, -oy / 20.0, -oz / 20.0, 0.3F, 20, 0.3F, 0.1F, 1.0F);
      }
      // The pillar at the heart of the island.
      for (int i = 0; i < 2; i++) {
         p(level, Tex.SHARD, i == 0 ? WHITE : red, cx + r.nextGaussian() * 0.5, cy, cz + r.nextGaussian() * 0.5,
            0.0, 0.9 + r.nextDouble() * 0.4, 0.0, 1.4F, 34, 0.6F, 0.0F, 1.0F);
      }
      p(level, Tex.GLOW, red, cx, cy + 1.0, cz, 0.0, 0.0, 0.0, 3.0F, 4, 1.2F, 0.0F, 0.6F);
      // The sigil: two counter-rotating rune circles that widen as the stand goes on.
      if (l.age % 4 == 0) {
         double radius = 8.0 + 22.0 * t;
         for (int ring = 0; ring < 2; ring++) {
            double rad = ring == 0 ? radius : radius * 0.62;
            int pts = ring == 0 ? 28 : 18;
            double phase = (ring == 0 ? 1 : -1) * l.age * 0.015;
            for (int i = 0; i < pts; i++) {
               double a = phase + Math.PI * 2.0 * i / pts;
               p(level, Tex.RUNE, ring == 0 ? red : 0xFFB0C0, cx + Math.cos(a) * rad, cy + 0.25, cz + Math.sin(a) * rad,
                  0.0, 0.0, 0.0, 0.9F, 5, 1.0F, 0.05F, 0.95F);
            }
         }
      }
      // Embers climbing the whole bowl.
      for (int i = 0; i < 3; i++) {
         double a = r.nextDouble() * Math.PI * 2.0;
         double d = r.nextDouble() * 34.0;
         p(level, Tex.SPARK, i % 2 == 0 ? 0xFF6A3C : red, cx + Math.cos(a) * d, cy + r.nextDouble() * 4.0, cz + Math.sin(a) * d,
            r.nextGaussian() * 0.02, 0.08 + r.nextDouble() * 0.08, r.nextGaussian() * 0.02, 0.25F, 40, 0.3F, 0.15F, 1.0F);
      }
      // The eclipse: a dark-cored halo that hangs over the dragon.
      if (l.age % 8 == 0) {
         p(level, Tex.RING, red, px, py + 6.0, pz, 0.0, 0.0, 0.0, 9.0F, 10, 1.1F, 0.02F, 0.85F);
         p(level, Tex.SMOKE, VOID, px, py + 6.0, pz, 0.0, 0.0, 0.0, 7.5F, 10, 1.05F, 0.01F, 0.9F);
      }
      // Star-shards: crimson streaks falling out of the sky across the whole arena.
      for (int i = 0; i < 2; i++) {
         double a = r.nextDouble() * Math.PI * 2.0;
         double d = r.nextDouble() * 40.0;
         FfParticle shard = p(level, Tex.SHARD, i == 0 ? WHITE : red, cx + Math.cos(a) * d, cy + 28.0 + r.nextDouble() * 6.0, cz + Math.sin(a) * d,
            0.0, -1.4, 0.0, 0.8F, 24, 0.6F, 0.0F, 1.0F);
         if (shard != null) {
            shard.fall(0.5F);
         }
      }
      // Three crimson blades sweeping the island, faster and longer as the stand goes on.
      if ((l.age & 1) == 0) {
         double sweep = l.age * (0.04 + 0.08 * t);
         double reach = 10.0 + 22.0 * t;
         for (int blade = 0; blade < 3; blade++) {
            double a = sweep + blade * Math.PI * 2.0 / 3.0;
            for (double d = 3.0; d <= reach; d += 2.2) {
               p(level, Tex.GLOW, d > reach - 3.0 ? WHITE : red, cx + Math.cos(a) * d, cy + 0.6, cz + Math.sin(a) * d,
                  0.0, 0.0, 0.0, 0.7F, 4, 0.6F, 0.0F, 1.0F, 0.85F);
            }
         }
      }
      // Fractures: a jagged crack of light tearing outward along the ground.
      if (l.age % 9 == 0) {
         double a = r.nextDouble() * Math.PI * 2.0;
         double x = cx, z = cz;
         for (int k = 0; k < 14; k++) {
            a += (r.nextDouble() - 0.5) * 0.9;
            x += Math.cos(a) * 1.8;
            z += Math.sin(a) * 1.8;
            p(level, Tex.SPARK, k % 3 == 0 ? WHITE : red, x, cy + 0.15, z, 0.0, 0.02, 0.0, 0.45F, 12, 0.4F, 0.0F, 1.0F);
         }
      }
      // A heartbeat from the body, quickening: every 20 ticks at the start, every 6 at the end.
      int beat = Math.max(6, (int)(20 - 14 * t));
      if (l.age % beat == 0) {
         p(level, Tex.RING, red, px, py, pz, 0.0, 0.0, 0.0, 1.5F, 10, 7.0F, 0.0F, 1.0F, 0.9F);
         p(level, Tex.GLOW, red, px, py, pz, 0.0, 0.0, 0.0, 3.0F, 6, 1.6F, 0.0F, 1.0F, 0.7F);
      }
      // A dark vortex pulling smoke into him.
      for (int i = 0; i < 2; i++) {
         double a = r.nextDouble() * Math.PI * 2.0;
         double d = 20.0 + r.nextDouble() * 6.0;
         double ox = Math.cos(a) * d, oz = Math.sin(a) * d, oy = (r.nextDouble() - 0.3) * 8.0;
         p(level, Tex.SMOKE, VOID, px + ox, py + oy, pz + oz, (-ox - oz * 0.8) / 22.0, -oy / 22.0, (-oz + ox * 0.8) / 22.0,
            1.4F, 22, 0.4F, 0.08F, 1.0F, 0.8F);
      }
      // The crescendo, then the white-out.
      if (l.life - l.age <= 20) {
         double k = 1.0 - (l.life - l.age) / 20.0;
         p(level, Tex.GLOW, WHITE, px, py, pz, 0.0, 0.0, 0.0, (float)(2.0 + 10.0 * k), 3, 1.0F, 0.0F, 0.8F);
      }
      if (l.age == l.life - 1) {
         flash(level, px, py, pz, WHITE, 30.0F);
         p(level, Tex.RING, WHITE, cx, cy + 1.0, cz, 0.0, 0.0, 0.0, 4.0F, 24, 25.0F, 0.0F, 1.0F);
         sparkBurst(level, px, py, pz, 60, 1.4, red);
      }
   }

   /**
    * The dragon's death: rays of light breaking out of the body and lengthening, the body coming
    * apart into rising motes, flashes cracking across it - then everything rushes back into one
    * point and goes off.
    */
   private static void death(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double t = l.t();
      double x = c.x(), y = c.y(), z = c.z();
      int violet = c.color();
      if (l.age == 0) {
         flash(level, x, y, z, violet, 12.0F);
         p(level, Tex.RING, violet, x, y, z, 0.0, 0.0, 0.0, 2.0F, 18, 20.0F, 0.0F, 0.9F);
      }
      if (t < 0.85) {
         int rays = 2 + (int)(4.0 * t);
         double length = 4.0 + 14.0 * t;
         for (int i = 0; i < rays; i++) {
            double th = r.nextDouble() * Math.PI * 2.0;
            double dy = r.nextDouble() * 1.2 - 0.6;
            Vec3 dir = new Vec3(Math.cos(th), dy, Math.sin(th)).normalize();
            int steps = (int)(length / 1.2);
            for (int s = 1; s <= steps; s++) {
               double f = s * 1.2;
               p(level, Tex.GLOW, s * 2 < steps ? WHITE : violet, x + dir.x * f, y + dir.y * f, z + dir.z * f,
                  0.0, 0.0, 0.0, (float)(0.75 * (1.0 - (double)s / steps) + 0.2), 3, 1.0F, 0.0F, 0.95F);
            }
         }
         for (int i = 0; i < 4; i++) {
            p(level, i % 3 == 0 ? Tex.SPARK : Tex.MOTE, i % 2 == 0 ? violet : WHITE,
               x + r.nextGaussian() * 3.0, y + r.nextGaussian() * 2.0, z + r.nextGaussian() * 3.0,
               r.nextGaussian() * 0.02, 0.06 + r.nextDouble() * 0.08, r.nextGaussian() * 0.02, 0.3F, 40, 0.2F, 0.1F, 1.0F);
         }
         if (l.age % 8 == 0) {
            double fx = x + r.nextGaussian() * 4.0, fy = y + r.nextGaussian() * 2.0, fz = z + r.nextGaussian() * 4.0;
            flash(level, fx, fy, fz, violet, 3.0F);
            p(level, Tex.RING, WHITE, fx, fy, fz, 0.0, 0.0, 0.0, 0.5F, 10, 8.0F, 0.0F, 0.8F);
         }
      } else {
         // The implosion.
         for (int i = 0; i < 8; i++) {
            double th = r.nextDouble() * Math.PI * 2.0;
            double ph = Math.acos(r.nextDouble() * 2.0 - 1.0);
            double rr = 10.0 + r.nextDouble() * 4.0;
            double ox = Math.sin(ph) * Math.cos(th) * rr, oy = Math.cos(ph) * rr, oz = Math.sin(ph) * Math.sin(th) * rr;
            p(level, Tex.SPARK, i % 2 == 0 ? WHITE : violet, x + ox, y + oy, z + oz, -ox / 8.0, -oy / 8.0, -oz / 8.0, 0.35F, 8, 0.2F, 0.2F, 1.0F);
         }
         p(level, Tex.GLOW, WHITE, x, y, z, 0.0, 0.0, 0.0, (float)(1.0 + 8.0 * (t - 0.85) / 0.15), 3, 1.0F, 0.0F, 0.9F);
      }
      if (l.age == l.life - 1) {
         flash(level, x, y, z, WHITE, 26.0F);
         p(level, Tex.RING, violet, x, y, z, 0.0, 0.0, 0.0, 3.0F, 26, 30.0F, 0.0F, 1.0F);
         p(level, Tex.RING, WHITE, x, y, z, 0.0, 0.0, 0.0, 2.0F, 18, 22.0F, 0.0F, 1.0F);
         sparkBurst(level, x, y, z, 80, 1.6, violet);
      }
   }

   /** The exit gateway opening: a beam of light growing out of the floor inside a turning rune ring. */
   private static void gateway(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double height = 2.0 + 26.0 * l.t();
      for (int i = 0; i < 3; i++) {
         p(level, Tex.SHARD, i == 0 ? WHITE : c.color(), c.x() + r.nextGaussian() * 0.3, c.y() + r.nextDouble() * height, c.z() + r.nextGaussian() * 0.3,
            0.0, 0.25, 0.0, 0.9F, 10, 0.6F, 0.0F, 1.0F);
      }
      if (l.age % 3 == 0) {
         for (int i = 0; i < 12; i++) {
            double a = l.age * 0.04 + Math.PI * 2.0 * i / 12;
            p(level, Tex.RUNE, c.color(), c.x() + Math.cos(a) * 3.2, c.y() + 0.3, c.z() + Math.sin(a) * 3.2, 0.0, 0.0, 0.0, 0.6F, 4, 1.0F, 0.05F, 1.0F);
         }
      }
      if (l.age == l.life - 1) {
         flash(level, c.x(), c.y() + 2.0, c.z(), c.color(), 8.0F);
         p(level, Tex.RING, WHITE, c.x(), c.y() + 1.0, c.z(), 0.0, 0.0, 0.0, 2.0F, 16, 10.0F, 0.0F, 1.0F);
      }
   }

   // ------------------------------------------------------------------ the Slime King

   /** A gel meteor coming down, accelerating like the server's (t squared), shedding drops as it falls. */
   private static void meteor(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double t = l.t();
      double k = t;   // linear, exactly as the server moves it - an eased path landed away from the hit
      double x = c.x() + (c.ax() - c.x()) * k;
      double y = c.y() + (c.ay() - c.y()) * k;
      double z = c.z() + (c.az() - c.z()) * k;
      boolean star = c.particle().getType() != ParticleTypes.ITEM_SLIME;
      if (star) {
         p(level, Tex.SPARK, WHITE, x, y, z, 0.0, 0.0, 0.0, 0.8F, 3, 1.0F, 0.3F, 1.0F);
         p(level, Tex.STREAK, c.color(), x, y + 0.6, z, 0.0, 0.2, 0.0, 0.5F, 6, 0.4F, 0.0F, 0.9F);
         p(level, Tex.GLOW, c.color(), x, y, z, 0.0, 0.0, 0.0, 1.4F, 3, 1.0F, 0.0F, 1.0F, 0.5F);
         return;
      }
      p(level, Tex.BLOB, c.color(), x, y, z, 0.0, 0.0, 0.0, 1.0F, 3, 1.0F, 0.0F, 1.0F);
      p(level, Tex.GLOW, c.color(), x, y, z, 0.0, 0.0, 0.0, 1.8F, 3, 1.0F, 0.0F, 1.0F, 0.45F);
      FfParticle drop = p(level, Tex.BLOB, c.color(), x + r.nextGaussian() * 0.3, y + 0.6, z + r.nextGaussian() * 0.3,
         r.nextGaussian() * 0.03, 0.05, r.nextGaussian() * 0.03, 0.25F, 14, 0.5F, 0.0F, 0.95F);
      if (drop != null) {
         drop.fall(0.4F);
      }
      if ((l.age & 1) == 0) {
         p(level, Tex.SPARK, WHITE, x, y + 0.4, z, 0.0, 0.02, 0.0, 0.25F, 6, 0.4F, 0.2F, 1.0F);
      }
   }

   /** Gel landing: a flash, a halo, a crown of drops thrown up and out, and a puddle spreading. */
   private static void gooSplash(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double radius = c.a();
      if (l.age == 0) {
         flash(level, c.x(), c.y() + 0.3, c.z(), c.color(), (float)(radius * 0.8));
         p(level, Tex.RING, c.color(), c.x(), c.y() + 0.3, c.z(), 0.0, 0.0, 0.0, 0.6F, 10, (float)(radius * 4.0), 0.0F, 1.0F, 0.8F);
         int n = clamp((int)(8 + radius * 5), 8, 24);
         for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0 * i / n + r.nextDouble() * 0.3;
            double out = 0.08 + radius * 0.05 * r.nextDouble();
            FfParticle drop = p(level, Tex.BLOB, i % 4 == 0 ? 0xC8FFB0 : c.color(), c.x(), c.y() + 0.3, c.z(),
               Math.cos(a) * out, 0.3 + r.nextDouble() * 0.3, Math.sin(a) * out, 0.22F + r.nextFloat() * 0.2F, 24, 0.6F, 0.0F, 0.98F);
            if (drop != null) {
               drop.fall(1.0F);
            }
         }
      }
      if ((l.age & 1) == 0) {
         double rad = radius * (1.0 - Math.pow(1.0 - (l.age + 1.0) / l.life, 2.0));
         int pts = clamp((int)(Math.PI * 2.0 * rad / 0.9), 6, 24);
         for (int i = 0; i < pts; i++) {
            double a = Math.PI * 2.0 * i / pts;
            p(level, Tex.BLOB, c.color(), c.x() + Math.cos(a) * rad, c.y() + 0.08, c.z() + Math.sin(a) * rad,
               0.0, 0.0, 0.0, 0.35F, 16, 1.2F, 0.0F, 1.0F, 0.85F);
         }
      }
   }

   /** A geyser: a column of gel thrown straight up that rains back down around the vent. */
   private static void geyser(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      if (l.age == 0) {
         p(level, Tex.RING, c.color(), c.x(), c.y() + 0.2, c.z(), 0.0, 0.0, 0.0, 0.5F, 8, 4.0F, 0.0F, 1.0F, 0.8F);
         p(level, Tex.GLOW, WHITE, c.x(), c.y() + 0.5, c.z(), 0.0, 0.0, 0.0, 1.2F, 5, 1.5F, 0.0F, 1.0F);
      }
      if (l.age < 7) {
         double push = Math.sqrt(c.a()) * 0.42;
         for (int i = 0; i < 3; i++) {
            FfParticle drop = p(level, Tex.BLOB, i == 0 ? 0xC8FFB0 : c.color(), c.x() + r.nextGaussian() * 0.15, c.y() + 0.2, c.z() + r.nextGaussian() * 0.15,
               r.nextGaussian() * 0.04, push * (0.8 + r.nextDouble() * 0.4), r.nextGaussian() * 0.04, 0.3F + r.nextFloat() * 0.2F, 26, 0.6F, 0.0F, 0.98F);
            if (drop != null) {
               drop.fall(1.0F);
            }
         }
         p(level, Tex.SPARK, WHITE, c.x(), c.y() + 0.4 + l.age * 0.5, c.z(), 0.0, 0.2, 0.0, 0.3F, 6, 0.4F, 0.2F, 1.0F);
      }
   }

   /**
    * The Time Lord blown back into his own rift: a tear opens behind him and holds, light and
    * clock-runes are dragged off him into the seam, and on the last tick it detonates - a
    * white-out, runes and shards blown outward, two halos - and is gone.
    */
   /**
    * The Time Lord's end, in three acts: light cracks out of him as the rift opens behind; he
    * bursts; then every piece of him is dragged spiralling into the seam, which swallows, seals
    * to a point and detonates.
    */
   private static void riftDeath(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      Vec3 side = new Vec3(c.ax(), 0.0, c.az());
      side = side.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : side.normalize();
      Vec3 behind = new Vec3(side.z, 0.0, -side.x);
      double rx = c.x() + behind.x * 1.6;
      double ry = c.y() + 0.6;
      double rz = c.z() + behind.z * 1.6;
      double t = l.t();
      int burst = (int)(l.life * 0.4);
      double size = t < 0.4 ? 1.2 + 1.8 * t / 0.4 : t < 0.85 ? 3.0 + 1.4 * (t - 0.4) / 0.45 : 0.2 + 4.2 * (1.0 - (t - 0.85) / 0.15);
      if (l.age < l.life - 1) {
         Live tear = new Live(new Cue(FfVfx.TEAR, c.particle(), (float)rx, (float)ry, (float)rz, (float)side.x, 0.0F, (float)side.z,
            (float)size, 0.0F, 0, c.color()), l.life * 3);
         tear.age = Math.min(l.age, l.life);
         tear(level, tear);
         p(level, Tex.GLOW, 0x2A0A40, rx, ry, rz, 0.0, 0.0, 0.0, (float)size * 0.9F, 3, 1.0F, 0.0F, 1.0F, 0.8F);
      }
      if (l.age < burst) {
         // Act one: he cracks. Rays of light break out of his body, more and longer each tick.
         double q = l.age / (double)burst;
         p(level, Tex.GLOW, c.color(), c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, (float)(0.6 + 1.6 * q), 3, 1.0F, 0.0F, 1.0F, 0.7F);
         if ((l.age & 1) == 0) {
            crackOut(level, c.color(), c.x(), c.y() + r.nextGaussian() * 0.4, c.z(), 1 + (int)(q * 4.0), 0.8 + 1.4 * q);
         }
         for (int i = 0; i < 3; i++) {
            double ox = c.x() + r.nextGaussian() * 0.5, oy = c.y() + r.nextGaussian() * 0.8, oz = c.z() + r.nextGaussian() * 0.5;
            p(level, i == 0 ? Tex.SHARD : Tex.MOTE, i == 1 ? 0xE2B042 : c.color(), ox, oy, oz,
               (rx - ox) / 18.0, (ry - oy) / 18.0, (rz - oz) / 18.0, 0.22F, 18, 0.3F, 0.3F, 1.0F);
         }
         return;
      }
      if (l.age == burst) {
         // He bursts.
         flash(level, c.x(), c.y(), c.z(), WHITE, 18.0F);
         flash(level, c.x(), c.y(), c.z(), c.color(), 12.0F);
         p(level, Tex.FLARE, WHITE, c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 3.5F, 12, 1.6F, 0.05F, 1.0F);
         p(level, Tex.RING, c.color(), c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 1.5F, 16, 16.0F, 0.0F, 1.0F);
         p(level, Tex.RING, 0xE2B042, c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 1.0F, 12, 11.0F, 0.0F, 1.0F);
         sparkBurst(level, c.x(), c.y(), c.z(), 70, 1.5, c.color());
         crackOut(level, WHITE, c.x(), c.y(), c.z(), 10, 2.6);
         for (int i = 0; i < 24; i++) {
            p(level, i % 3 == 0 ? Tex.ROCK : Tex.SHARD, i % 2 == 0 ? c.color() : 0xE2B042, c.x(), c.y(), c.z(),
               r.nextGaussian() * 0.6, r.nextGaussian() * 0.45, r.nextGaussian() * 0.6, 0.4F, 26, 0.6F, 0.4F, 0.82F);
         }
         return;
      }
      if (l.age < l.life - 1) {
         // Act two: everything he was is dragged into the seam, spiralling, faster as it closes.
         double u = (l.age - burst) / (double)(l.life - 1 - burst);
         double shell = 3.2 - 2.0 * u;
         for (int i = 0; i < 5; i++) {
            Vec3 d = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.6, r.nextGaussian()).normalize().scale(shell);
            double ox = c.x() + d.x, oy = c.y() + d.y, oz = c.z() + d.z;
            double pull = 12.0 - 6.0 * u;
            p(level, i % 3 == 0 ? Tex.SHARD : i % 3 == 1 ? Tex.WISP : Tex.MOTE, i % 2 == 0 ? c.color() : i == 1 ? 0xE2B042 : WHITE, ox, oy, oz,
               (rx - ox) / pull + (rz - oz) * 0.04, (ry - oy) / pull, (rz - oz) / pull - (rx - ox) * 0.04, 0.3F, (int)pull, 0.2F, 0.4F, 1.0F);
         }
         vortex(level, (l.age & 1) == 0 ? Tex.WISP : Tex.MOTE, (l.age & 1) == 0 ? c.color() : WHITE, rx, ry, rz, size * 1.1, 4, 0.25F, 0.8);
         if (l.age % 4 == 0) {
            p(level, Tex.RING, c.color(), rx, ry, rz, 0.0, 0.0, 0.0, (float)size * 1.6F, 8, 0.1F, 0.0F, 1.0F, 0.7F);
         }
         if (u > 0.75) {
            for (int i = 0; i < 2; i++) {
               double a = r.nextDouble() * Math.PI * 2.0;
               Vec3 from = new Vec3(rx + Math.cos(a) * 4.0, ry + r.nextGaussian(), rz + Math.sin(a) * 4.0);
               line(level, Tex.GLOW, i == 0 ? WHITE : c.color(), from, new Vec3(rx, ry, rz), 8, 0.12F, 2, 0.9F);
            }
         }
         return;
      }
      // Sealed to a point - then it detonates.
      flash(level, rx, ry, rz, WHITE, 20.0F);
      flash(level, rx, ry, rz, c.color(), 12.0F);
      p(level, Tex.FLARE, WHITE, rx, ry, rz, 0.0, 0.0, 0.0, 5.0F, 16, 1.4F, 0.03F, 1.0F);
      p(level, Tex.RING, c.color(), rx, ry, rz, 0.0, 0.0, 0.0, 2.0F, 22, 24.0F, 0.0F, 1.0F);
      p(level, Tex.RING, 0xE2B042, rx, ry, rz, 0.0, 0.0, 0.0, 1.0F, 16, 15.0F, 0.0F, 1.0F);
      sparkBurst(level, rx, ry, rz, 60, 1.4, c.color());
      for (int i = 0; i < 20; i++) {
         double a = Math.PI * 2.0 * i / 20;
         p(level, Tex.RUNE, 0xE2B042, rx, ry, rz, Math.cos(a) * 0.7, (r.nextDouble() - 0.5) * 0.4, Math.sin(a) * 0.7, 0.45F, 24, 0.5F, 0.3F, 0.9F);
      }
   }

   /**
    * Excalibur's cut: the blade's arc is drawn through the target and hangs there, humming, until
    * the cut lands - then the whole line bursts into stars and a cross-cut flashes through it.
    */
   private static void slash(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      int land = Math.max(2, Math.min(40, (int)c.a()));
      Vec3 look = new Vec3(c.ax(), c.ay(), c.az());
      Vec3 f = look.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
      Vec3 side = f.cross(new Vec3(0.0, 1.0, 0.0));
      side = side.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : side.normalize();
      Vec3 up = side.cross(f).normalize();
      double tilt = Math.toRadians(28.0);
      Vec3 across = side.scale(Math.cos(tilt)).add(up.scale(Math.sin(tilt)));
      Vec3 cross = side.scale(-Math.sin(tilt)).add(up.scale(Math.cos(tilt)));
      Vec3 normal = across.cross(f).normalize();
      Vec3 centre = new Vec3(c.x(), c.y(), c.z());
      double radius = 2.6;
      Vec3 pivot = centre.subtract(f.scale(radius * 0.9));
      double span = Math.toRadians(75.0);
      java.util.function.DoubleFunction<Vec3> at = th -> pivot.add(across.scale(Math.sin(th) * radius).add(f.scale(Math.cos(th) * radius)));
      int gold = c.color();
      if (l.age < 3) {
         // The swing: the edge sweeps across, leaving its line behind.
         double from = -span + 2.0 * span * l.age / 3.0, to = -span + 2.0 * span * (l.age + 1) / 3.0;
         int hold = land - l.age + 2;
         for (int i = 0; i <= 12; i++) {
            Vec3 q = at.apply(from + (to - from) * i / 12.0);
            p(level, Tex.GLOW, WHITE, q.x, q.y, q.z, 0.0, 0.0, 0.0, 0.16F, hold, 1.0F, 0.0F, 1.0F);
            p(level, Tex.GLOW, gold, q.x, q.y, q.z, 0.0, 0.0, 0.0, 0.42F, hold, 0.8F, 0.0F, 1.0F, 0.55F);
         }
         Vec3 head = at.apply(to);
         p(level, Tex.ARC, WHITE, head.x, head.y, head.z, 0.0, 0.0, 0.0, 1.6F, 3, 1.3F, 0.0F, 1.0F);
         for (int i = 0; i < 4; i++) {
            p(level, Tex.SPARK, i % 2 == 0 ? WHITE : gold, head.x, head.y, head.z,
               across.x * 0.25 + r.nextGaussian() * 0.08, across.y * 0.25 + r.nextGaussian() * 0.08, across.z * 0.25 + r.nextGaussian() * 0.08, 0.3F, 10, 0.3F, 0.2F, 0.86F);
         }
         return;
      }
      if (l.age < land) {
         // The cut hangs in the air, humming: sparks shed off it, stars wink along it.
         for (int i = 0; i < 3; i++) {
            Vec3 q = at.apply(-span + 2.0 * span * r.nextDouble());
            double s = r.nextBoolean() ? 0.06 : -0.06;
            p(level, Tex.SPARK, gold, q.x, q.y, q.z, normal.x * s, normal.y * s + 0.01, normal.z * s, 0.18F, 8, 0.3F, 0.2F, 0.9F);
         }
         Vec3 q = at.apply(-span + 2.0 * span * r.nextDouble());
         p(level, Tex.FLARE, WHITE, q.x, q.y, q.z, 0.0, 0.0, 0.0, 0.5F, 4, 1.2F, 0.1F, 1.0F);
         return;
      }
      if (l.age == land) {
         // It lands: the line bursts into stars, a cross-cut flashes through, the air rings.
         for (int i = 0; i <= 10; i++) {
            Vec3 q = at.apply(-span + 2.0 * span * i / 10.0);
            p(level, Tex.FLARE, i % 2 == 0 ? WHITE : gold, q.x, q.y, q.z, 0.0, 0.0, 0.0, 1.1F, 8, 1.4F, 0.1F, 1.0F);
            for (int k = -1; k <= 1; k += 2) {
               p(level, Tex.SPARK, gold, q.x, q.y, q.z, normal.x * 0.5 * k + r.nextGaussian() * 0.1, normal.y * 0.5 * k + 0.1, normal.z * 0.5 * k + r.nextGaussian() * 0.1,
                  0.3F, 14, 0.3F, 0.2F, 0.84F);
            }
         }
         line(level, Tex.GLOW, WHITE, centre.subtract(cross.scale(2.4)), centre.add(cross.scale(2.4)), 16, 0.2F, 6, 1.0F);
         line(level, Tex.GLOW, gold, centre.subtract(cross.scale(2.6)), centre.add(cross.scale(2.6)), 12, 0.5F, 6, 0.5F);
         flash(level, centre.x, centre.y, centre.z, gold, 7.0F);
         p(level, Tex.FLARE, WHITE, centre.x, centre.y, centre.z, 0.0, 0.0, 0.0, 3.0F, 10, 1.5F, 0.05F, 1.0F);
         p(level, Tex.RING, gold, centre.x, centre.y, centre.z, 0.0, 0.0, 0.0, 1.0F, 12, 12.0F, 0.0F, 1.0F);
         crackOut(level, gold, centre.x, centre.y, centre.z, 6, 1.8);
         return;
      }
      // Gold dust settling where it cut.
      for (int i = 0; i < 2; i++) {
         Vec3 q = at.apply(-span + 2.0 * span * r.nextDouble());
         FfParticle e = p(level, Tex.EMBER, gold, q.x, q.y, q.z, r.nextGaussian() * 0.02, 0.02, r.nextGaussian() * 0.02, 0.16F, 18, 0.5F, 0.2F, 0.95F);
         if (e != null) {
            e.fall(0.006F);
         }
      }
   }

   /**
    * The King's burning rods: eight short columns of fire-light bound to his body, orbiting in two
    * rings and bobbing, an ember at each tip. White-hot in his last phase.
    */
   private static void rodOrbit(ClientLevel level, Live l) {
      Cue c = l.cue;
      net.minecraft.world.entity.Entity body = level.getEntity(c.count());
      if (body == null || !body.isAlive()) {
         return;
      }
      int phase = (int)c.a();
      int core = phase >= 3 ? 0xFFF2D8 : 0xFF9A3C;
      double radius = phase >= 3 ? 1.7 : 1.45;
      double now = level.getGameTime();
      for (int idx = 0; idx < 8; idx++) {
         boolean upper = idx >= 4;
         double a = now * 0.055 + (idx % 4) * (Math.PI / 2) + (upper ? 0.45 : 0.0);
         double x = body.getX() + Math.cos(a) * radius;
         double z = body.getZ() + Math.sin(a) * radius;
         double y = body.getY() + (upper ? body.getBbHeight() * 0.72 : body.getBbHeight() * 0.42) + Math.sin(now * 0.12 + idx) * 0.15;
         for (int k = -1; k <= 1; k++) {
            p(level, Tex.GLOW, core, x, y + k * 0.22, z, 0.0, 0.0, 0.0, 0.16F, 2, 1.0F, 0.0F, 1.0F);
         }
         if ((l.age & 1) == 0) {
            p(level, Tex.FLAME, phase >= 2 ? 0xFF5A2A : core, x, y + 0.35, z, 0.0, 0.03, 0.0, 0.14F, 6, 0.5F, 0.0F, 1.0F);
         }
      }
   }

   /**
    * The Mindbinder's presence: runes circling its waist, a halo of rings over its head, thought-wisps
    * rising off it and an eye that flares open now and then. Hot pink and faster in its second phase.
    */
   private static void mindAura(ClientLevel level, Live l) {
      Cue c = l.cue;
      net.minecraft.world.entity.Entity body = level.getEntity(c.count());
      if (body == null || !body.isAlive()) {
         return;
      }
      RandomSource r = level.getRandom();
      boolean two = c.a() >= 2.0F;
      int col = two ? 0xFF4CB0 : 0xB04CFF;
      double now = level.getGameTime() * (two ? 0.14 : 0.08);
      double x = body.getX(), y = body.getY(), z = body.getZ(), h = body.getBbHeight();
      for (int i = 0; i < 3; i++) {
         double a = now + i * Math.PI * 2.0 / 3.0;
         p(level, Tex.HEXRUNE, i == 0 ? 0x78E6FF : col, x + Math.cos(a) * 1.3, y + h * 0.45 + Math.sin(now * 2.0 + i) * 0.15, z + Math.sin(a) * 1.3,
            0.0, 0.0, 0.0, 0.28F, 2, 1.0F, 0.0F, 1.0F);
      }
      if ((l.age & 1) == 0) {
         p(level, Tex.RING, col, x, y + h + 0.45, z, 0.0, 0.0, 0.0, 0.7F, 3, 1.1F, 0.0F, 1.0F, 0.5F);
      }
      p(level, Tex.WISP, r.nextBoolean() ? col : 0x78E6FF, x + r.nextGaussian() * 0.4, y + 0.1, z + r.nextGaussian() * 0.4,
         0.0, 0.05 + r.nextDouble() * 0.03, 0.0, 0.22F, 16, 0.4F, 0.3F, 0.97F, 0.8F);
      if (l.age % (two ? 6 : 12) == 0) {
         p(level, Tex.FLARE, 0x78E6FF, x, y + h * 0.85, z, 0.0, 0.0, 0.0, 0.7F, 5, 1.3F, 0.05F, 1.0F);
      }
      if (two && (l.age & 3) == 0) {
         crackOut(level, col, x, y + h * 0.6, z, 1, 1.1);
      }
   }

   /** A small muzzle puff: one glow, a few bubbles, three speed lines - light on purpose. */
   private static void muzzle(ClientLevel level, Cue c) {
      RandomSource r = level.getRandom();
      Vec3 face = new Vec3(c.ax(), c.ay(), c.az());
      face = face.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : face.normalize();
      p(level, Tex.GLOW, c.color(), c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 0.5F, 4, 1.4F, 0.0F, 1.0F, 0.7F);
      for (int i = 0; i < 4; i++) {
         p(level, Tex.BUBBLE, c.color(), c.x(), c.y(), c.z(),
            face.x * 0.12 + r.nextGaussian() * 0.04, face.y * 0.12 + 0.03, face.z * 0.12 + r.nextGaussian() * 0.04,
            0.12F + r.nextFloat() * 0.08F, 14, 1.4F, 0.0F, 0.9F);
      }
      for (int i = 0; i < 3; i++) {
         p(level, Tex.STREAK, WHITE, c.x(), c.y(), c.z(), face.x * 0.4, face.y * 0.4, face.z * 0.4, 0.18F, 4, 0.4F, 0.0F, 0.85F);
      }
   }

   /**
    * A golden clash - a parry, a perfect block: a white-gold flash, a quick gold halo, a cone of
    * gold streaks thrown out where the blow met, cut-gold shards tumbling down, embers rising.
    */
   private static void clash(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      int gold = c.color();
      Vec3 face = new Vec3(c.ax(), c.ay(), c.az());
      face = face.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : face.normalize();
      if (l.age == 0) {
         p(level, Tex.GLOW, WHITE, c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 0.9F, 4, 1.8F, 0.0F, 1.0F);
         p(level, Tex.GLOW, gold, c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 1.6F, 7, 1.6F, 0.0F, 1.0F, 0.8F);
         p(level, Tex.RING, gold, c.x(), c.y(), c.z(), 0.0, 0.0, 0.0, 0.3F, 7, 7.0F, 0.0F, 1.0F);
         for (int i = 0; i < 12; i++) {
            Vec3 v = face.add(r.nextGaussian() * 0.6, r.nextGaussian() * 0.6, r.nextGaussian() * 0.6).normalize().scale(0.35 + r.nextDouble() * 0.3);
            p(level, i % 3 == 0 ? Tex.SPARK : Tex.STREAK, i % 4 == 0 ? WHITE : gold, c.x(), c.y(), c.z(), v.x, v.y, v.z, 0.22F, 10, 0.3F, 0.3F, 0.82F);
         }
         for (int i = 0; i < 5; i++) {
            FfParticle shard = p(level, Tex.CRYSTAL, gold, c.x(), c.y(), c.z(),
               face.x * 0.15 + r.nextGaussian() * 0.12, 0.2 + r.nextDouble() * 0.15, face.z * 0.15 + r.nextGaussian() * 0.12,
               0.14F, 22, 0.8F, (r.nextFloat() - 0.5F) * 0.6F, 0.97F);
            if (shard != null) {
               shard.fall(0.9F);
            }
         }
      }
      if (l.age < 5) {
         p(level, Tex.EMBER, gold, c.x() + r.nextGaussian() * 0.3, c.y() + r.nextGaussian() * 0.2, c.z() + r.nextGaussian() * 0.3,
            0.0, 0.05, 0.0, 0.15F, 14, 0.5F, 0.0F, 0.95F);
      }
   }

   /**
    * A wormhole. Leaving: a spiral funnel of light pulls in round you and tightens until it snaps
    * shut with a flash. Arriving: it bursts open - flash, halo - and light spirals out.
    */
   private static void wormhole(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      boolean arriving = c.b() > 0.5F;
      double t = l.t();
      int rgb = c.color();
      if (arriving && l.age == 0) {
         flash(level, c.x(), c.y() + 1.0, c.z(), rgb, 4.0F);
         p(level, Tex.RING, rgb, c.x(), c.y() + 1.0, c.z(), 0.0, 0.0, 0.0, 0.5F, 14, 10.0F, 0.0F, 1.0F);
         sparkBurst(level, c.x(), c.y() + 1.0, c.z(), 24, 0.5, 0x6FE6FF);
      }
      double radius = arriving ? 0.4 + 2.2 * t : 2.4 * (1.0 - t) + 0.15;
      for (int arm = 0; arm < 3; arm++) {
         for (int k = 0; k < 2; k++) {
            double a = l.age * 0.55 + arm * Math.PI * 2.0 / 3.0 + k * 0.25;
            double y = c.y() + (arriving ? 0.2 + t * 2.0 : 2.2 - t * 1.8) + k * 0.3;
            double x = c.x() + Math.cos(a) * radius;
            double z = c.z() + Math.sin(a) * radius;
            p(level, k == 0 ? Tex.GLOW : Tex.MOTE, arm == 0 ? 0x6FE6FF : rgb, x, y, z,
               -Math.sin(a) * 0.05, 0.0, Math.cos(a) * 0.05, k == 0 ? 0.35F : 0.2F, 8, 0.4F, 0.0F, 1.0F);
         }
      }
      if (!arriving && (l.age & 1) == 0) {
         p(level, Tex.SHARD, WHITE, c.x() + r.nextGaussian() * 0.4, c.y() + 0.2, c.z() + r.nextGaussian() * 0.4, 0.0, 0.25, 0.0, 0.35F, 10, 0.5F, 0.0F, 1.0F);
      }
      if (!arriving && l.age == l.life - 1) {
         flash(level, c.x(), c.y() + 1.0, c.z(), WHITE, 3.0F);
         p(level, Tex.RING, rgb, c.x(), c.y() + 1.0, c.z(), 0.0, 0.0, 0.0, 3.0F, 8, 0.1F, 0.0F, 1.0F);
      }
   }

   /**
    * A summoning circle: a rune ring writes itself onto the ground, an inner ring turns the other
    * way, pillars of soul-fire climb at its points, souls spiral in to the middle, and (for a big
    * summoning) dark smoke closes in before it all goes off at once.
    */
   private static void summonCircle(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double radius = c.a();
      double t = l.t();
      boolean big = radius >= 2.0;
      int rgb = c.color();
      if (l.age % 3 == 0) {
         double drawn = Math.min(1.0, t / 0.2);   // the ring is written in, then held
         int pts = clamp((int)(Math.PI * 2.0 * radius / 0.9), 10, 36);
         for (int i = 0; i < pts * drawn; i++) {
            double a = l.age * 0.02 + Math.PI * 2.0 * i / pts;
            p(level, Tex.RUNE, rgb, c.x() + Math.cos(a) * radius, c.y() + 0.12, c.z() + Math.sin(a) * radius, 0.0, 0.0, 0.0, big ? 0.55F : 0.3F, 4, 1.0F, 0.05F, 1.0F);
         }
         int inner = pts / 2;
         for (int i = 0; i < inner * drawn; i++) {
            double a = -l.age * 0.035 + Math.PI * 2.0 * i / inner;
            p(level, Tex.GLOW, WHITE, c.x() + Math.cos(a) * radius * 0.55, c.y() + 0.12, c.z() + Math.sin(a) * radius * 0.55, 0.0, 0.0, 0.0, 0.25F, 4, 1.0F, 0.0F, 1.0F, 0.7F);
         }
      }
      int pillars = big ? 8 : 4;
      for (int i = 0; i < pillars; i++) {
         double a = Math.PI * 2.0 * i / pillars + Math.PI / pillars;
         p(level, Tex.SHARD, i % 2 == 0 ? rgb : WHITE, c.x() + Math.cos(a) * radius, c.y() + 0.2, c.z() + Math.sin(a) * radius,
            0.0, 0.12 + 0.2 * t, 0.0, big ? 0.45F : 0.25F, big ? 14 : 8, 0.5F, 0.0F, 1.0F);
      }
      for (int i = 0; i < 2; i++) {
         double a = r.nextDouble() * Math.PI * 2.0;
         double from = radius * 1.6;
         double ox = Math.cos(a) * from, oz = Math.sin(a) * from;
         p(level, Tex.MOTE, rgb, c.x() + ox, c.y() + 0.4 + r.nextDouble() * 1.5, c.z() + oz,
            (-ox - oz * 0.6) / 14.0, 0.02, (-oz + ox * 0.6) / 14.0, 0.22F, 14, 0.3F, 0.1F, 1.0F);
      }
      if (big && t > 0.7) {
         double shell = radius * (1.0 - (t - 0.7) / 0.3) + 0.5;
         for (int i = 0; i < 6; i++) {
            double th = r.nextDouble() * Math.PI * 2.0;
            double ph = Math.acos(r.nextDouble() * 2.0 - 1.0);
            p(level, Tex.SMOKE, VOID, c.x() + Math.sin(ph) * Math.cos(th) * shell, c.y() + 1.5 + Math.cos(ph) * shell, c.z() + Math.sin(ph) * Math.sin(th) * shell,
               0.0, 0.0, 0.0, 1.0F, 6, 0.8F, 0.05F, 1.0F, 0.85F);
         }
      }
      if (l.age == l.life - 1) {
         flash(level, c.x(), c.y() + (big ? 1.5 : 0.8), c.z(), rgb, big ? 8.0F : 2.5F);
         p(level, Tex.RING, rgb, c.x(), c.y() + 0.3, c.z(), 0.0, 0.0, 0.0, 1.0F, 14, (float)(radius * 4.0), 0.0F, 1.0F);
         sparkBurst(level, c.x(), c.y() + 1.0, c.z(), big ? 40 : 14, big ? 1.0 : 0.4, rgb);
      }
   }

   /**
    * Stone breaking: a dusty flash, faceted chunks thrown up and out that tumble and fall, and a
    * low ring of dust rolling outward. Big bursts also throw a halo.
    */
   private static void rockBurst(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double radius = c.a();
      if (l.age == 0) {
         p(level, Tex.GLOW, c.color(), c.x(), c.y() + 0.4, c.z(), 0.0, 0.0, 0.0, (float)(0.8 + radius * 0.5), 6, 1.4F, 0.0F, 1.0F, 0.6F);
         if (radius >= 2.0) {
            p(level, Tex.RING, c.color(), c.x(), c.y() + 0.3, c.z(), 0.0, 0.0, 0.0, 0.8F, 10, (float)(radius * 3.0), 0.0F, 1.0F, 0.7F);
         }
         int n = clamp((int)(5 + radius * 5), 5, 30);
         for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            double out = 0.05 + r.nextDouble() * (0.06 + radius * 0.035);
            int shade = 0.3 > r.nextDouble() ? 0x6E6860 : r.nextBoolean() ? 0xA8A094 : 0x8A8378;
            FfParticle chunk = p(level, Tex.ROCK, shade, c.x() + Math.cos(a) * radius * 0.3, c.y() + 0.3, c.z() + Math.sin(a) * radius * 0.3,
               Math.cos(a) * out, 0.25 + r.nextDouble() * (0.2 + radius * 0.04), Math.sin(a) * out,
               0.12F + r.nextFloat() * (radius >= 2.0 ? 0.3F : 0.15F), 30 + r.nextInt(10), 1.0F, (r.nextFloat() - 0.5F) * 0.5F, 0.98F);
            if (chunk != null) {
               chunk.fall(1.0F);
            }
         }
      }
      if ((l.age & 1) == 0 && radius >= 1.0) {
         double rad = radius * (1.0 - Math.pow(1.0 - (l.age + 1.0) / l.life, 2.0));
         int pts = clamp((int)(Math.PI * 2.0 * rad / 1.4), 6, 28);
         for (int i = 0; i < pts; i++) {
            double a = Math.PI * 2.0 * i / pts + r.nextDouble() * 0.2;
            p(level, Tex.SMOKE, 0x8E877C, c.x() + Math.cos(a) * rad, c.y() + 0.2, c.z() + Math.sin(a) * rad,
               Math.cos(a) * 0.02, 0.02, Math.sin(a) * 0.02, 0.7F, 24, 1.8F, 0.03F, 0.95F, 0.7F);
         }
      }
   }

   // ------------------------------------------------------------------ the Snow Queen

   private static final int ICE = 0xBFEFFF;
   private static final int DEEP_ICE = 0x5FB8FF;

   /** Template: ice bursting apart - a flash, a ring, crystals up, shards down, flakes everywhere. */
   private static void iceShatter(ClientLevel level, double x, double y, double z, double size) {
      iceShatter(level, x, y, z, size, ICE);
   }

   private static void iceShatter(ClientLevel level, double x, double y, double z, double size, int ICE) {
      RandomSource r = level.getRandom();
      flash(level, x, y, z, ICE, (float)(2.5 * size));
      p(level, Tex.RING, ICE, x, y, z, 0.0, 0.0, 0.0, 0.5F, 10, (float)(8.0 * size), 0.0F, 1.0F);
      for (int i = 0; i < (int)(10 * size); i++) {
         FfParticle s = p(level, i % 2 == 0 ? Tex.SHARD : Tex.CRYSTAL, i % 3 == 0 ? WHITE : ICE, x, y, z,
            r.nextGaussian() * 0.25 * size, 0.2 + r.nextDouble() * 0.25, r.nextGaussian() * 0.25 * size, 0.22F, 24, 0.7F, (r.nextFloat() - 0.5F) * 0.6F, 0.96F);
         if (s != null) {
            s.fall(0.04F);
         }
      }
      for (int i = 0; i < (int)(8 * size); i++) {
         p(level, Tex.FLAKE, WHITE, x, y, z, r.nextGaussian() * 0.2 * size, r.nextGaussian() * 0.15, r.nextGaussian() * 0.2 * size, 0.25F, 26, 0.5F, 0.15F, 0.9F);
      }
   }

   /** A frost nova: a ring of ice racing outward along the ground, crystals thrown up in its wake. */
   private static void frostNova(ClientLevel level, Live l) {
      Cue c = l.cue;
      int ICE = c.color();
      RandomSource r = level.getRandom();
      double rad = c.a() * (1.0 - Math.pow(1.0 - l.t(), 3.0));
      if (l.age == 0) {
         flash(level, c.x(), c.y() + 1.0, c.z(), ICE, 8.0F);
         p(level, Tex.FLARE, WHITE, c.x(), c.y() + 1.0, c.z(), 0.0, 0.0, 0.0, 3.0F, 10, 1.4F, 0.05F, 1.0F);
      }
      int n = 20 + (int)(c.a() * 2.0);
      for (int i = 0; i < n; i++) {
         double a = Math.PI * 2.0 * i / n + l.age * 0.05;
         double x = c.x() + Math.cos(a) * rad, z = c.z() + Math.sin(a) * rad;
         p(level, i % 3 == 0 ? Tex.CRYSTAL : Tex.FLAKE, i % 2 == 0 ? WHITE : ICE, x, c.y() + 0.2, z, 0.0, 0.05 + r.nextDouble() * 0.05, 0.0,
            i % 3 == 0 ? 0.35F : 0.25F, 10, 0.5F, 0.1F, 0.95F);
      }
      if ((l.age & 1) == 0) {
         p(level, Tex.SMOKE, ICE, c.x() + r.nextGaussian() * rad * 0.5, c.y() + 0.3, c.z() + r.nextGaussian() * rad * 0.5, 0.0, 0.01, 0.0, 1.2F, 20, 1.6F, 0.05F, 0.95F, 0.3F);
      }
   }

   /** Ice erupting from the ground: a crystal spire shoots up, then breaks. */
   private static void iceErupt(ClientLevel level, Live l) {
      Cue c = l.cue;
      int ICE = c.color();
      double h = Math.max(1.0, c.a());
      if (l.age == 0) {
         for (int k = 0; k < (int)(h * 2.5); k++) {
            double y = c.y() + k * 0.4;
            float size = (float)(0.7 - 0.5 * k / (h * 2.5));
            p(level, Tex.CRYSTAL, k % 2 == 0 ? ICE : WHITE, c.x(), y, c.z(), 0.0, 0.0, 0.0, size, 12, 0.9F, 0.0F, 1.0F);
         }
         p(level, Tex.RING, ICE, c.x(), c.y() + 0.1, c.z(), 0.0, 0.0, 0.0, 0.4F, 8, 5.0F, 0.0F, 1.0F);
         sparkBurst(level, c.x(), c.y() + 0.4, c.z(), 10, 0.35, ICE);
      }
      if (l.age == 11) {
         iceShatter(level, c.x(), c.y() + h * 0.5, c.z(), 0.8, ICE);
      }
   }

   /** A great icicle dropping out of the sky, shattering where it lands. */
   private static void icicle(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      int fall = Math.max(2, (int)c.a());
      if (l.age < fall) {
         double k = l.age / (double)fall;
         double y = c.y() + 16.0 * (1.0 - k * k);
         p(level, Tex.ICICLE, WHITE, c.x(), y + 0.8, c.z(), 0.0, 0.0, 0.0, 1.6F, 2, 1.0F, 0.0F, 1.0F);
         p(level, Tex.GLOW, ICE, c.x(), y + 0.8, c.z(), 0.0, 0.0, 0.0, 1.8F, 2, 1.0F, 0.0F, 1.0F, 0.4F);
         p(level, Tex.FLAKE, ICE, c.x() + r.nextGaussian() * 0.3, y + 2.0, c.z() + r.nextGaussian() * 0.3, 0.0, 0.05, 0.0, 0.2F, 12, 0.4F, 0.2F, 0.95F);
         return;
      }
      if (l.age == fall) {
         iceShatter(level, c.x(), c.y() + 0.4, c.z(), 1.3);
         for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2.0 * i / 6;
            p(level, Tex.CRYSTAL, ICE, c.x() + Math.cos(a) * 0.8, c.y() + 0.3, c.z() + Math.sin(a) * 0.8, 0.0, 0.0, 0.0, 0.5F, 30, 1.0F, 0.0F, 1.0F);
         }
      }
   }

   /** A frost lance: a white-hot line of ice, crystals hanging along it, a burst at the far end. */
   private static void frostLance(ClientLevel level, Cue c) {
      RandomSource r = level.getRandom();
      Vec3 a = new Vec3(c.x(), c.y(), c.z()), b = new Vec3(c.ax(), c.ay(), c.az());
      int n = Math.max(6, (int)(a.distanceTo(b) * 3.0));
      line(level, Tex.GLOW, WHITE, a, b, n, 0.18F, 5, 1.0F);
      line(level, Tex.GLOW, DEEP_ICE, a, b, n / 2, 0.55F, 6, 0.5F);
      for (int i = 0; i < n / 3; i++) {
         Vec3 q = a.lerp(b, r.nextDouble());
         p(level, Tex.CRYSTAL, ICE, q.x, q.y, q.z, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, 0.2F, 14, 0.5F, 0.4F, 0.9F);
      }
      p(level, Tex.FLARE, WHITE, a.x, a.y, a.z, 0.0, 0.0, 0.0, 1.0F, 5, 1.2F, 0.0F, 1.0F);
      iceShatter(level, b.x, b.y, b.z, 0.7);
   }

   /** Her presence: a crown of crystals turning over her head, snow drifting round her, frost at her feet. */
   private static void snowAura(ClientLevel level, Live l) {
      Cue c = l.cue;
      net.minecraft.world.entity.Entity body = level.getEntity(c.count());
      if (body == null || !body.isAlive()) {
         return;
      }
      RandomSource r = level.getRandom();
      int phase = (int)c.a();
      double x = body.getX(), y = body.getY(), z = body.getZ(), h = body.getBbHeight();
      double now = level.getGameTime() * (0.06 + 0.03 * phase);
      for (int i = 0; i < 5; i++) {
         double a = now + i * Math.PI * 2.0 / 5.0;
         p(level, Tex.CRYSTAL, i == 0 ? WHITE : ICE, x + Math.cos(a) * 0.6, y + h + 0.35, z + Math.sin(a) * 0.6, 0.0, 0.0, 0.0, 0.22F, 2, 1.0F, 0.0F, 1.0F);
      }
      for (int i = 0; i < 2 + phase; i++) {
         double a = r.nextDouble() * Math.PI * 2.0, d = 0.6 + r.nextDouble() * 2.2;
         p(level, Tex.FLAKE, WHITE, x + Math.cos(a) * d, y + h + 0.5, z + Math.sin(a) * d, Math.sin(a) * 0.02, -0.04, -Math.cos(a) * 0.02, 0.16F, 30, 0.8F, 0.1F, 1.0F, 0.9F);
      }
      if (l.age % 3 == 0) {
         p(level, Tex.SMOKE, ICE, x + r.nextGaussian() * 0.6, y + 0.15, z + r.nextGaussian() * 0.6, 0.0, 0.005, 0.0, 1.0F, 26, 1.8F, 0.05F, 0.97F, 0.3F);
      }
      if (phase >= 3 && (l.age & 3) == 0) {
         crackOut(level, ICE, x, y + h * 0.55, z, 1, 1.4);
      }
   }

   /** A whiteout closing on one body: snow spirals in tighter and faster, then the ice takes it. */
   private static void whiteout(ClientLevel level, Live l) {
      Cue c = l.cue;
      net.minecraft.world.entity.Entity body = level.getEntity(c.count());
      if (body == null) {
         return;
      }
      int burst = Math.max(2, (int)c.a());
      double x = body.getX(), y = body.getY(), z = body.getZ();
      if (l.age < burst) {
         double k = l.age / (double)burst;
         double rad = 2.4 - 1.6 * k;
         for (int i = 0; i < 6; i++) {
            double a = l.age * (0.25 + 0.35 * k) + i * Math.PI / 3.0;
            p(level, i % 2 == 0 ? Tex.FLAKE : Tex.STREAK, WHITE, x + Math.cos(a) * rad, y + 0.2 + i * 0.35, z + Math.sin(a) * rad,
               -Math.sin(a) * 0.15, 0.0, Math.cos(a) * 0.15, 0.22F, 4, 0.6F, 0.2F, 0.9F, 0.8F);
         }
         p(level, Tex.SMOKE, ICE, x, y + 1.0, z, 0.0, 0.0, 0.0, (float)(1.5 + k), 3, 1.0F, 0.0F, 1.0F, (float)(0.15 + 0.3 * k));
         return;
      }
      if (l.age == burst) {
         iceShatter(level, x, y + 1.0, z, 1.5);
         p(level, Tex.CRYSTAL, WHITE, x, y + 1.0, z, 0.0, 0.0, 0.0, 2.2F, 14, 0.6F, 0.0F, 1.0F, 0.8F);
      }
   }

   /**
    * Her arrival: a blizzard funnel climbs out of the ground while icicles crash in a ring round
    * it, then the funnel bursts and she stands in a frost nova with a crown of light.
    */
   private static void snowSpawn(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double t = l.t();
      int burst = (int)(l.life * 0.75);
      if (l.age < burst) {
         double k = l.age / (double)burst;
         double rad = 3.2 - 2.2 * k;
         for (int i = 0; i < 8; i++) {
            double a = l.age * 0.35 + i * Math.PI / 4.0;
            double y = c.y() + (i * 0.5 + l.age * 0.12) % 5.0;
            p(level, i % 2 == 0 ? Tex.FLAKE : Tex.STREAK, i % 3 == 0 ? ICE : WHITE, c.x() + Math.cos(a) * rad, y, c.z() + Math.sin(a) * rad,
               -Math.sin(a) * 0.2, 0.08, Math.cos(a) * 0.2, 0.24F, 6, 0.6F, 0.2F, 0.9F, 0.85F);
         }
         p(level, Tex.GLOW, ICE, c.x(), c.y() + 1.5, c.z(), 0.0, 0.0, 0.0, (float)(1.0 + 2.5 * k), 3, 1.0F, 0.0F, 1.0F, 0.5F);
         if (l.age % 7 == 0) {
            double a = r.nextDouble() * Math.PI * 2.0;
            Live drop = new Live(new Cue(FfVfx.ICICLE, c.particle(), (float)(c.x() + Math.cos(a) * 5.0), c.y(), (float)(c.z() + Math.sin(a) * 5.0),
               0.0F, 0.0F, 0.0F, 10.0F, 0.0F, 0, ICE), 12);
            SPAWNED.add(drop);
         }
         if (l.age % 3 == 0) {
            p(level, Tex.SMOKE, ICE, c.x() + r.nextGaussian() * 2.0, c.y() + 0.2, c.z() + r.nextGaussian() * 2.0, 0.0, 0.01, 0.0, 2.0F, 30, 1.5F, 0.03F, 0.97F, 0.3F);
         }
         return;
      }
      if (l.age == burst) {
         iceShatter(level, c.x(), c.y() + 1.5, c.z(), 2.0);
         p(level, Tex.FLARE, WHITE, c.x(), c.y() + 2.2, c.z(), 0.0, 0.0, 0.0, 5.0F, 16, 1.3F, 0.03F, 1.0F);
         SPAWNED.add(new Live(new Cue(FfVfx.FROST_NOVA, c.particle(), c.x(), c.y(), c.z(), 0.0F, 0.0F, 0.0F, 12.0F, 0.0F, 0, ICE), 16));
         return;
      }
      for (int i = 0; i < 3; i++) {
         p(level, Tex.FLAKE, WHITE, c.x() + r.nextGaussian() * 3.0, c.y() + 5.0, c.z() + r.nextGaussian() * 3.0, 0.0, -0.08, 0.0, 0.18F, 40, 1.0F, 0.1F, 1.0F, (float)(1.0 - t));
      }
   }

   /**
    * Her end: ice creeps over her body crystal by crystal while the cold drains into her, cracks
    * of light split it - then she shatters, and the snow she was falls softly after.
    */
   private static void snowDeath(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      int shatter = (int)(l.life * 0.7);
      if (l.age < shatter) {
         double k = l.age / (double)shatter;
         for (int i = 0; i < 2 + (int)(k * 4.0); i++) {
            double a = r.nextDouble() * Math.PI * 2.0, h = r.nextDouble() * 2.0;
            p(level, Tex.CRYSTAL, r.nextInt(3) == 0 ? WHITE : ICE, c.x() + Math.cos(a) * 0.35, c.y() + h, c.z() + Math.sin(a) * 0.35, 0.0, 0.0, 0.0,
               0.18F + (float)r.nextDouble() * 0.15F, shatter - l.age + 2, 1.0F, 0.0F, 1.0F);
         }
         vortex(level, Tex.FLAKE, WHITE, c.x(), c.y() + 1.0, c.z(), 3.5 - 2.0 * k, 3, 0.2F, 0.7);
         if (l.age % 4 == 0 && k > 0.35) {
            crackOut(level, ICE, c.x(), c.y() + 1.0 + r.nextGaussian() * 0.4, c.z(), 1 + (int)(k * 3.0), 0.6 + k);
         }
         p(level, Tex.GLOW, ICE, c.x(), c.y() + 1.0, c.z(), 0.0, 0.0, 0.0, (float)(0.8 + 2.0 * k), 3, 1.0F, 0.0F, 1.0F, 0.5F);
         return;
      }
      if (l.age == shatter) {
         flash(level, c.x(), c.y() + 1.0, c.z(), WHITE, 18.0F);
         iceShatter(level, c.x(), c.y() + 1.0, c.z(), 3.0);
         p(level, Tex.FLARE, WHITE, c.x(), c.y() + 1.0, c.z(), 0.0, 0.0, 0.0, 6.0F, 18, 1.3F, 0.03F, 1.0F);
         sparkBurst(level, c.x(), c.y() + 1.0, c.z(), 50, 1.2, ICE);
         SPAWNED.add(new Live(new Cue(FfVfx.FROST_NOVA, c.particle(), c.x(), c.y(), c.z(), 0.0F, 0.0F, 0.0F, 16.0F, 0.0F, 0, ICE), 16));
         for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            SPAWNED.add(new Live(new Cue(FfVfx.ICE_ERUPT, c.particle(), (float)(c.x() + Math.cos(a) * 3.5), c.y(), (float)(c.z() + Math.sin(a) * 3.5),
               0.0F, 0.0F, 0.0F, 4.0F, 0.0F, 0, ICE), 13));
         }
         return;
      }
      for (int i = 0; i < 4; i++) {
         p(level, Tex.FLAKE, WHITE, c.x() + r.nextGaussian() * 4.0, c.y() + 6.0, c.z() + r.nextGaussian() * 4.0, 0.0, -0.06, 0.0, 0.2F, 60, 1.0F, 0.1F, 1.0F);
      }
      p(level, Tex.MOTE, ICE, c.x() + r.nextGaussian(), c.y() + 0.5, c.z() + r.nextGaussian(), 0.0, 0.05, 0.0, 0.15F, 30, 0.5F, 0.0F, 1.0F);
   }

   /** The ice staff's breath: a cone of flakes, streaks and crystals blown down the sight line. */
   private static void frostSpray(ClientLevel level, Cue c) {
      RandomSource r = level.getRandom();
      Vec3 from = new Vec3(c.x(), c.y(), c.z()), to = new Vec3(c.ax(), c.ay(), c.az());
      Vec3 dir = to.subtract(from);
      double len = dir.length();
      if (len < 1.0E-3) {
         return;
      }
      dir = dir.scale(1.0 / len);
      double spread = Math.max(0.5, c.a());
      for (int i = 0; i < 14; i++) {
         double f = 0.15 + r.nextDouble() * 0.3;
         Vec3 v = dir.scale(len / 12.0).add(r.nextGaussian() * spread * 0.05, r.nextGaussian() * spread * 0.03, r.nextGaussian() * spread * 0.05);
         p(level, i % 4 == 0 ? Tex.CRYSTAL : i % 2 == 0 ? Tex.FLAKE : Tex.STREAK, i % 3 == 0 ? WHITE : ICE, from.x + dir.x * f, from.y + dir.y * f - 0.2, from.z + dir.z * f,
            v.x, v.y, v.z, i % 4 == 0 ? 0.18F : 0.22F, 12, 1.6F, 0.3F, 0.96F, 0.85F);
      }
      p(level, Tex.SMOKE, ICE, from.x + dir.x, from.y + dir.y - 0.2, from.z + dir.z, dir.x * len / 16.0, dir.y * len / 16.0, dir.z * len / 16.0, 0.6F, 16, 4.0F, 0.05F, 0.96F, 0.3F);
   }

   /** A shard of ice in flight: a glinting crystal head and a short frost tail, bound to its entity. */
   private static void iceTrail(ClientLevel level, Live l) {
      net.minecraft.world.entity.Entity e = level.getEntity(l.cue.count());
      if (e == null || !e.isAlive()) {
         return;
      }
      p(level, Tex.CRYSTAL, WHITE, e.getX(), e.getY(), e.getZ(), 0.0, 0.0, 0.0, 0.3F, 2, 1.0F, 0.0F, 1.0F);
      p(level, Tex.GLOW, ICE, e.getX(), e.getY(), e.getZ(), 0.0, 0.0, 0.0, 0.6F, 4, 0.5F, 0.0F, 1.0F, 0.6F);
      if ((l.age & 1) == 0) {
         p(level, Tex.FLAKE, ICE, e.getX(), e.getY(), e.getZ(), 0.0, -0.01, 0.0, 0.14F, 10, 0.5F, 0.3F, 0.9F);
      }
   }

   // ------------------------------------------------------------------ the Elder Warden

   /** A sonic lance: rings of sound racing down the line, a soul-white core, a burst where it ends. */
   private static void sonicLance(ClientLevel level, Cue c) {
      Vec3 a = new Vec3(c.x(), c.y(), c.z()), b = new Vec3(c.ax(), c.ay(), c.az());
      double len = a.distanceTo(b);
      if (len < 0.5) {
         return;
      }
      Vec3 dir = b.subtract(a).scale(1.0 / len);
      line(level, Tex.GLOW, WHITE, a, b, (int)(len * 2.0), 0.16F, 5, 1.0F);
      line(level, Tex.GLOW, c.color(), a, b, (int)len, 0.5F, 7, 0.5F);
      for (double d = 1.0; d < len; d += 1.6) {
         Vec3 q = a.add(dir.scale(d));
         p(level, Tex.RING, d % 3.2 < 1.6 ? c.color() : WHITE, q.x, q.y, q.z, dir.x * 0.05, dir.y * 0.05, dir.z * 0.05, 0.4F, 6 + (int)(d / 4.0), 3.0F, 0.0F, 1.0F, 0.8F);
      }
      p(level, Tex.FLARE, WHITE, a.x, a.y, a.z, 0.0, 0.0, 0.0, 1.6F, 6, 1.3F, 0.0F, 1.0F);
      flash(level, b.x, b.y, b.z, c.color(), 4.0F);
      sparkBurst(level, b.x, b.y, b.z, 16, 0.5, c.color());
   }

   /**
    * The Elder Warden's ultimate, RESONANCE: sound folds into his chest as rings that collapse
    * inward, his heart flares, then the deep dark answers in three great shockwaves.
    */
   private static void resonance(ClientLevel level, Live l) {
      Cue c = l.cue;
      RandomSource r = level.getRandom();
      double x = c.x(), y = c.y(), z = c.z();
      int charge = (int)(l.life * 0.42);
      if (l.age < charge) {
         double k = l.age / (double)charge;
         if (l.age % 3 == 0) {
            p(level, Tex.RING, c.color(), x, y + 1.6, z, 0.0, 0.0, 0.0, (float)(9.0 - 4.0 * k), 6, 0.05F, 0.0F, 1.0F, 0.7F);
         }
         vortex(level, Tex.MOTE, c.color(), x, y + 1.6, z, 7.0 - 4.0 * k, 4, 0.22F, 0.5);
         p(level, Tex.GLOW, c.color(), x, y + 1.7, z, 0.0, 0.0, 0.0, (float)(0.6 + 2.4 * k), 3, 1.0F, 0.0F, 1.0F, 0.7F);
         if (l.age % 2 == 0) {
            p(level, Tex.SMOKE, 0x061418, x + r.nextGaussian() * 6.0, y + 0.3, z + r.nextGaussian() * 6.0, 0.0, 0.01, 0.0, 3.0F, 30, 1.4F, 0.02F, 0.97F, 0.5F);
         }
         return;
      }
      int beat = l.age - charge;
      if (beat % 12 == 0 && beat <= 24) {
         // One shockwave: a flash in his chest, a ground ring, a wall of rings, cracks of soul light.
         flash(level, x, y + 1.7, z, c.color(), 10.0F);
         p(level, Tex.FLARE, WHITE, x, y + 1.7, z, 0.0, 0.0, 0.0, 4.0F, 10, 1.4F, 0.04F, 1.0F);
         p(level, Tex.RING, c.color(), x, y + 0.3, z, 0.0, 0.0, 0.0, 1.0F, 14, 36.0F, 0.0F, 1.0F);
         p(level, Tex.RING, WHITE, x, y + 1.6, z, 0.0, 0.0, 0.0, 1.0F, 12, 30.0F, 0.0F, 1.0F, 0.6F);
         crackOut(level, c.color(), x, y + 1.6, z, 6, 2.4);
         for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2.0 * i / 24;
            p(level, i % 2 == 0 ? Tex.STREAK : Tex.MOTE, i % 3 == 0 ? WHITE : c.color(), x, y + 0.6, z, Math.cos(a) * 1.2, 0.02, Math.sin(a) * 1.2, 0.35F, 14, 0.6F, 0.0F, 0.9F);
         }
      }
      if (beat > 24) {
         p(level, Tex.MOTE, c.color(), x + r.nextGaussian() * 5.0, y + 0.2, z + r.nextGaussian() * 5.0, 0.0, 0.06, 0.0, 0.2F, 30, 0.6F, 0.0F, 1.0F);
      }
   }

   /** The Elder Warden's presence: a soul heart beating in his chest, tendrils of light off his head, the dark pooling at his feet. */
   private static void sculkAura(ClientLevel level, Live l) {
      Cue c = l.cue;
      net.minecraft.world.entity.Entity body = level.getEntity(c.count());
      if (body == null || !body.isAlive()) {
         return;
      }
      RandomSource r = level.getRandom();
      boolean two = c.a() >= 2.0F;
      double x = body.getX(), y = body.getY(), z = body.getZ(), h = body.getBbHeight();
      int beat = (int)(level.getGameTime() % (two ? 14 : 20));
      if (beat == 0 || beat == 4) {
         p(level, Tex.GLOW, c.color(), x, y + h * 0.62, z, 0.0, 0.0, 0.0, beat == 0 ? 1.6F : 1.1F, 5, 1.4F, 0.0F, 1.0F, 0.8F);
         p(level, Tex.RING, c.color(), x, y + h * 0.62, z, 0.0, 0.0, 0.0, 0.5F, 8, beat == 0 ? 7.0F : 4.0F, 0.0F, 1.0F, 0.5F);
      }
      if ((l.age & 1) == 0) {
         p(level, Tex.FLAME, r.nextBoolean() ? c.color() : WHITE, x + r.nextGaussian() * 0.6, y + h * 0.9, z + r.nextGaussian() * 0.6,
            r.nextGaussian() * 0.02, 0.05, r.nextGaussian() * 0.02, 0.22F, 18, 0.4F, 0.3F, 0.97F, 0.8F);
      }
      if (l.age % 3 == 0) {
         p(level, Tex.SMOKE, 0x041014, x + r.nextGaussian() * 1.2, y + 0.2, z + r.nextGaussian() * 1.2, 0.0, 0.0, 0.0, 2.0F, 30, 1.4F, 0.02F, 1.0F, 0.55F);
      }
      if (l.age % (two ? 5 : 9) == 0) {
         crackOut(level, c.color(), x, y + h + 0.1, z, 1, 0.9);
      }
   }

   /** Heavy snow round the camera whenever this client is standing in her realm. No server traffic. */
   private static void realmSnow(Minecraft mc, ClientLevel level) {
      if (mc.player == null || !level.dimension().identifier().getPath().equals("snow_queen_realm")) {
         return;
      }
      RandomSource r = level.getRandom();
      Vec3 cam = mc.player.getEyePosition();
      double wind = 0.12 + 0.06 * Math.sin(level.getGameTime() * 0.01);
      for (int i = 0; i < 16; i++) {
         double x = cam.x + (r.nextDouble() - 0.5) * 28.0 - wind * 20.0, y = cam.y + 4.0 + r.nextDouble() * 10.0, z = cam.z + (r.nextDouble() - 0.5) * 28.0;
         p(level, i % 4 == 0 ? Tex.FLAKE : Tex.MOTE, WHITE, x, y, z, wind + r.nextGaussian() * 0.02, -0.16 - r.nextDouble() * 0.12, r.nextGaussian() * 0.02,
            i % 4 == 0 ? 0.14F : 0.08F, 70, 1.0F, 0.1F, 1.0F, 0.9F);
      }
      if (r.nextInt(3) == 0) {   // a gust
         p(level, Tex.STREAK, WHITE, cam.x + (r.nextDouble() - 0.5) * 16.0, cam.y + (r.nextDouble() - 0.3) * 4.0, cam.z + (r.nextDouble() - 0.5) * 16.0,
            0.6, -0.02, 0.0, 0.6F, 10, 1.0F, 0.0F, 1.0F, 0.35F);
      }
      if (r.nextInt(2) == 0) {   // drifting ground fog
         p(level, Tex.SMOKE, 0xE8F6FF, cam.x + (r.nextDouble() - 0.5) * 20.0, mc.player.getY() + 0.3, cam.z + (r.nextDouble() - 0.5) * 20.0,
            wind * 0.5, 0.0, 0.0, 2.5F, 60, 1.6F, 0.02F, 1.0F, 0.18F);
      }
   }

   // ------------------------------------------------------------------ helpers

   private static void flash(ClientLevel level, double x, double y, double z, int rgb, float size) {
      p(level, Tex.GLOW, WHITE, x, y, z, 0.0, 0.0, 0.0, size * 0.6F, 5, 1.6F, 0.0F, 1.0F);
      p(level, Tex.GLOW, rgb, x, y, z, 0.0, 0.0, 0.0, size, 9, 1.5F, 0.0F, 0.8F);
   }

   /** Template: a lit line from a to b, n points, each holding for life ticks. */
   private static void line(ClientLevel level, Tex tex, int rgb, Vec3 a, Vec3 b, int n, float size, int life, float alpha) {
      for (int i = 0; i <= n; i++) {
         Vec3 q = a.lerp(b, i / (double)n);
         p(level, tex, rgb, q.x, q.y, q.z, 0.0, 0.0, 0.0, size, life, 1.0F, 0.0F, 1.0F, alpha);
      }
   }

   /** Template: n motes on a ring of radius r, swirling in toward (x,y,z) - a drain, a vortex, a swallow. */
   private static void vortex(ClientLevel level, Tex tex, int rgb, double x, double y, double z, double r, int n, float size, double spin) {
      RandomSource rand = level.getRandom();
      for (int i = 0; i < n; i++) {
         double a = rand.nextDouble() * Math.PI * 2.0;
         double h = (rand.nextDouble() - 0.5) * r * 0.6;
         double ox = Math.cos(a) * r, oz = Math.sin(a) * r;
         p(level, tex, rgb, x + ox, y + h, z + oz, (-ox - oz * spin) / 10.0, -h / 10.0, (-oz + ox * spin) / 10.0, size, 10, 0.2F, 0.3F, 1.0F);
      }
   }

   /** Template: n cracks of light bursting out of a body - short lit rays in random directions. */
   private static void crackOut(ClientLevel level, int rgb, double x, double y, double z, int n, double len) {
      RandomSource r = level.getRandom();
      for (int i = 0; i < n; i++) {
         Vec3 d = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.8, r.nextGaussian()).normalize();
         Vec3 from = new Vec3(x, y, z).add(d.scale(0.3));
         line(level, Tex.GLOW, i % 2 == 0 ? WHITE : rgb, from, from.add(d.scale(len)), 5, 0.14F, 3, 1.0F);
         p(level, Tex.CRACK, rgb, from.x + d.x * 0.4, from.y + d.y * 0.4, from.z + d.z * 0.4, 0.0, 0.0, 0.0, 0.5F, 4, 1.2F, 0.0F, 1.0F);
      }
   }

   private static void sparkBurst(ClientLevel level, double x, double y, double z, int n, double speed, int rgb) {
      double golden = Math.PI * (3.0 - Math.sqrt(5.0));
      for (int i = 0; i < n; i++) {
         double yy = 1.0 - 2.0 * (i + 0.5) / n;
         double rad = Math.sqrt(1.0 - yy * yy);
         double ang = golden * i;
         p(level, Tex.SPARK, i % 2 == 0 ? WHITE : rgb, x, y, z, Math.cos(ang) * rad * speed, yy * speed, Math.sin(ang) * rad * speed,
            0.45F, 30, 0.3F, 0.2F, 0.9F);
      }
   }

   /** The colour a vanilla particle type stands for, so a raw cue keeps its meaning in our art. */
   private static int tintOf(ParticleOptions o) {
      if (o instanceof DustParticleOptions dust) {
         org.joml.Vector3f v = dust.getColor();
         return ((int)(v.x() * 255) << 16) | ((int)(v.y() * 255) << 8) | (int)(v.z() * 255);
      }
      ParticleType<?> t = o.getType();
      if (t == ParticleTypes.PORTAL || t == ParticleTypes.REVERSE_PORTAL) {
         return 0x9B4DFF;
      }
      if (t == ParticleTypes.DRAGON_BREATH) {
         return 0xD04CFF;
      }
      if (t == ParticleTypes.SOUL_FIRE_FLAME || t == ParticleTypes.SCULK_SOUL || t == ParticleTypes.SOUL) {
         return 0x3FE0FF;
      }
      if (t == ParticleTypes.FLAME || t == ParticleTypes.LAVA || t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER) {
         return 0xFFB060;
      }
      if (t == ParticleTypes.ENCHANT) {
         return 0xB8A0FF;
      }
      if (t == ParticleTypes.BLOCK || t == ParticleTypes.FALLING_DUST) {
         return 0x9C9488;
      }
      if (t == ParticleTypes.ITEM_SLIME) {
         return 0x6FE36A;
      }
      if (t == ParticleTypes.TOTEM_OF_UNDYING) {
         return 0xF2E070;
      }
      if (t == ParticleTypes.SNOWFLAKE) {
         return 0xD8F4FF;
      }
      return 0xF0E6FF;
   }

   private static FfParticle p(
      ClientLevel level, Tex tex, int rgb, double x, double y, double z, double vx, double vy, double vz,
      float size, int life, float grow, float spin, float drag
   ) {
      return p(level, tex, rgb, x, y, z, vx, vy, vz, size, life, grow, spin, drag, 1.0F);
   }

   private static FfParticle p(
      ClientLevel level, Tex tex, int rgb, double x, double y, double z, double vx, double vy, double vz,
      float size, int life, float grow, float spin, float drag, float alpha
   ) {
      if (room <= 0) {
         return null;
      }
      room--;
      return FfParticle.spawn(level, tex, rgb, x, y, z, vx, vy, vz, size, life, grow, spin, drag, alpha);
   }

   private static void failed(int kind, Throwable t) {
      if (FAILED.add(kind)) {
         LOG.error("Fortune & Favors VFX: effect kind {} failed to draw (reported once)", kind, t);
      }
   }

   private static int clamp(int v, int lo, int hi) {
      return Math.max(lo, Math.min(hi, v));
   }
}
