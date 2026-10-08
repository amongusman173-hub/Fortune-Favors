package com.fortuneandfavors.util;

/**
 * Cue kinds for the effect templates added on top of {@code net.FfVfx}'s own.
 *
 * <p>Kept outside {@code FfVfx} so a template can be added without touching the transport: the
 * server sends one of these through {@code FfVfx.shape} like any other kind, and
 * {@code client.FfVfxClient} draws it. They start at 100: well clear of the transport's own
 * numbering, and still inside a byte in case the wire format packs the kind into one. And
 * {@code economy.Fx} lists kinds of both families in one switch, so a clash would be a duplicate
 * case label - a compile error rather than one effect drawn as another.
 *
 * <p>What each cue's fields mean (a cue has a position, a second vector, {@code a}, {@code b} and
 * a colour):
 * <ul>
 *   <li>{@link #SPIRAL} - a double helix rising from the position; a = height, b = ticks.</li>
 *   <li>{@link #SHOCKWAVE} - a ground wave with debris rolling out; a = radius.</li>
 *   <li>{@link #LIGHTNING} - a forked bolt from the position to the second vector.</li>
 *   <li>{@link #DOME} - a shimmering hemisphere; a = radius, b = ticks.</li>
 *   <li>{@link #VORTEX} - a funnel spiralling in and up; a = radius, b = ticks.</li>
 *   <li>{@link #CRESCENT} - a moon-shaped blade sweeping across the facing (second vector); a = reach.</li>
 *   <li>{@link #STARBURST} - rays thrown out in every direction; a = ray length.</li>
 *   <li>{@link #RUNE_CIRCLE} - a turning ring of runes with a star inside; a = radius, b = ticks.</li>
 *   <li>{@link #EMBER_RAIN} - embers falling over a circle; a = radius, b = ticks.</li>
 *   <li>{@link #CHAINS} - sagging chain links from the position to the second vector.</li>
 *   <li>{@link #HEARTBEAT} - a double pulse of rings, repeating; a = radius, b = ticks.</li>
 *   <li>{@link #PETALS} - motes circling the position; a = radius, b = ticks.</li>
 *   <li>{@link #SHATTER} - shards flying apart; a = size.</li>
 *   <li>{@link #COMET} - a bright head with a trail, travelling to the second vector; b = ticks.</li>
 *   <li>{@link #AURA} - a flickering column of flame and wisps; a = height, b = ticks.</li>
 *   <li>{@link #FLARE} - a blinding bloom with a ring; a = size.</li>
 * </ul>
 *
 * <p>The themed set (116-127), drawn with the themed sprites rather than the ice ones:
 * <ul>
 *   <li>{@link #SCULK_BLOOM} - sculk veins creeping out over the ground from the position, spores
 *       swelling and popping along them; a = radius, b = ticks.</li>
 *   <li>{@link #SOUL_STREAM} - soul wisps flowing from the position to the second vector (a point)
 *       on a weaving path; a = how far the stream sways (blocks), b = ticks.</li>
 *   <li>{@link #TIDE_WAVE} - a rolling wall of water travelling from the position along the second
 *       vector (a direction), throwing droplets and leaving foam; a = reach, b = ticks.</li>
 *   <li>{@link #GUST} - a swirl of wind: feathers and streaks corkscrewing along the second vector
 *       (a direction); a = reach. Lives 16 ticks.</li>
 *   <li>{@link #GEAR_SPIN} - three interlocking cogs turning; they stand upright facing the second
 *       vector (a direction), or lie flat on the ground when it is zero; a = size, b = ticks.</li>
 *   <li>{@link #STARFALL} - twinkling stars raining over a circle, flashing where they land;
 *       a = radius, b = ticks.</li>
 *   <li>{@link #GEM_SHARDS} - faceted gems bursting out of a flash, glinting as they tumble;
 *       a = size. Lives 20 ticks.</li>
 *   <li>{@link #BLOOD_SPLASH} - droplets arcing out (biased along the second vector, a direction,
 *       when it is not zero), then dripping; a = size. Lives 22 ticks.</li>
 *   <li>{@link #THREADS} - puppet strings dropping from above onto the position and hanging taut,
 *       swaying, before they snap; a = how high above the strings start, b = ticks.</li>
 *   <li>{@link #VOID_COLLAPSE} - dark motes imploding into the position, then a ring thrown out;
 *       a = radius, b = ticks of implosion (the ring follows).</li>
 *   <li>{@link #RIFT_PORTAL} - an upright portal rift that tears open over 10 ticks, holds for b
 *       ticks and seals over 10; it stands at the position (its foot) facing the second vector (a
 *       direction - whatever steps out comes that way); a = height.</li>
 *   <li>{@link #SONIC_RING} - sculk shockwave crescents racing from the position along the second
 *       vector (a direction); a = reach, b = ticks.</li>
 * </ul>
 */
public final class FxKinds {
   public static final int SPIRAL = 100;
   public static final int SHOCKWAVE = 101;
   public static final int LIGHTNING = 102;
   public static final int DOME = 103;
   public static final int VORTEX = 104;
   public static final int CRESCENT = 105;
   public static final int STARBURST = 106;
   public static final int RUNE_CIRCLE = 107;
   public static final int EMBER_RAIN = 108;
   public static final int CHAINS = 109;
   public static final int HEARTBEAT = 110;
   public static final int PETALS = 111;
   public static final int SHATTER = 112;
   public static final int COMET = 113;
   public static final int AURA = 114;
   public static final int FLARE = 115;
   public static final int SCULK_BLOOM = 116;
   public static final int SOUL_STREAM = 117;
   public static final int TIDE_WAVE = 118;
   public static final int GUST = 119;
   public static final int GEAR_SPIN = 120;
   public static final int STARFALL = 121;
   public static final int GEM_SHARDS = 122;
   public static final int BLOOD_SPLASH = 123;
   public static final int THREADS = 124;
   public static final int VOID_COLLAPSE = 125;
   public static final int RIFT_PORTAL = 126;
   /** The last id that fits in a signed byte: the template set is full at 127. */
   public static final int SONIC_RING = 127;
   // Kinds are a VarInt on the wire, so there is no ceiling at 127.
   /** A crimson halo overhead dripping blood: (x,y,z) under it, a = radius, b = ticks, color. */
   public static final int BLOOD_MOON = 128;
   /** A turning ring of runes on the ground: a = radius, b = ticks, color. */
   public static final int CRIMSON_SIGIL = 129;
   /** Water spiralling in and down: a = radius, b = ticks, color. */
   public static final int WHIRLPOOL = 130;
   /** A curling arm rising out of the ground and slapping down: a = height, b = ticks, color. */
   public static final int TENTACLE = 131;
   /** A dark cloud over a spot with bolts striking down: a = radius, b = ticks, color. */
   public static final int STORM_CELL = 132;
   /** Feathers whirled up in a widening spiral: a = radius, b = ticks, color. */
   public static final int FEATHER_STORM = 133;
   /** Cogs flung out of a point: a = size. One-shot. */
   public static final int COG_BURST = 134;
   /** Gems falling over a circle: a = radius, b = ticks, color. */
   public static final int GEM_RAIN = 135;
   /** A constellation drawn star by star over a spot: a = radius, b = ticks, color. */
   public static final int STAR_TRAIL = 136;
   /** Souls climbing a column: a = height, b = ticks, color. */
   public static final int SOUL_PILLAR = 137;

   private FxKinds() {
   }
}
