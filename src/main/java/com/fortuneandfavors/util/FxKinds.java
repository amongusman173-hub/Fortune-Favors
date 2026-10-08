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

   private FxKinds() {
   }
}
