package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.ModPlatform;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;

/**
 * The mod's music, as a Bedrock client can actually hear it.
 *
 * <p>A sound is named twice in this mod, and only one of those names crosses the proxy. The modded
 * client is handed a payload and plays the cut itself; everybody else is handed the cut as a plain
 * sound packet, and that packet carries a <b>sound id</b>. For a Java client the id is the mod's own
 * - {@code fortuneandfavors:withered_loop} resolves off the pack and plays. For a Bedrock client it
 * is translated by Geyser, and Geyser translates <i>vanilla</i> sound events: an id it has never
 * heard of is an id it cannot send, so a Bedrock player watching the Wither fight gets a silent
 * soundtrack and no error anywhere.
 *
 * <p>The way through is to hand the Bedrock client a sound id Geyser <b>does</b> know, and then have
 * the Bedrock half of the pack point that vanilla sound at our file. Each cut is named after a music
 * disc, because a disc is the one vanilla sound a pack may replace that a fight is not already using,
 * and {@code record.*} is the name Geyser sends.
 *
 * <p>The cost is real and worth stating: a Bedrock client running the pack hears our music instead of
 * those four discs. They are discs a server using this pack should not be handing out - the aliases
 * are room this mod has taken, not one it has been given - and moving a cut to a different disc is
 * one line here and one in {@code tools/build_resourcepack.py}, which the build refuses to let
 * disagree.
 *
 * <p>The failure mode is also deliberately the quiet one: an alias that Geyser does not translate
 * leaves the player in exactly the silence they were in before, so nothing here can make a Bedrock
 * client's soundtrack worse than it already is.
 */
public final class BedrockMusic {
   /** One cut's stand-in: what the mod calls it, what Bedrock is handed, and the name it arrives under. */
   public record StandIn(SoundEvent wanted, SoundEvent standIn, String bedrockName) {}

   /**
    * Every cut that needs a stand-in, and the disc that carries it.
    *
    * <p>The {@code bedrockName} is the identifier the Bedrock pack redefines and therefore the only
    * thing that couples these two halves; {@code ffAuditSources} reads it out of this table and out of
    * the pack builder and fails the build if the two ever disagree.
    */
   private static final List<StandIn> STAND_INS = List.of(
      new StandIn(ModSounds.WITHERED_INTRO, disc("music_disc.13"), "record.13"),
      new StandIn(ModSounds.WITHERED_LOOP, disc("music_disc.cat"), "record.cat"),
      new StandIn(ModSounds.WITHERED_DEATH, disc("music_disc.blocks"), "record.blocks"),
      new StandIn(ModSounds.ENDER_DRAGON_THEME, disc("music_disc.chirp"), "record.chirp"),
      // The End's opening theme. It is the one cut in this table that is not a boss fight's: the
      // one-shot the island plays for a body that has never been here, sent as a plain sound on the
      // native fallback. Without a stand-in a Bedrock client hears the silence the track exists to
      // fill, which is why it takes a fifth disc rather than riding on the four the fights use.
      new StandIn(ModSounds.ARIA_MATH_EPIC, disc("music_disc.relic"), "record.relic")
   );

   /**
    * A vanilla sound event, built by id rather than looked up in the registry.
    *
    * <p>The same shape as the mod's own music events, and for the same reason: what is actually sent
    * is a <i>direct</i> holder on a sound packet, so the client only ever needs the id - and an id is
    * all the proxy reads. A registry lookup would also have to have happened before the registry was
    * frozen, which a static table in a mod cannot promise.
    */
   private static SoundEvent disc(String path) {
      return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("minecraft", path));
   }

   private BedrockMusic() {
   }

   /**
    * The sound to send this listener: the cut itself for a Java client, its stand-in for Bedrock.
    *
    * <p>Asked per hand-out rather than cached, because a player can arrive through either door and
    * Geyser's own answer is already cached underneath this call.
    */
   public static SoundEvent forListener(ServerPlayer p, SoundEvent wanted) {
      return ModPlatform.isBedrock(p) ? standInFor(wanted) : wanted;
   }

   /** The stand-in for a cut, or the cut itself when it needs none. */
   public static SoundEvent standInFor(SoundEvent wanted) {
      for (StandIn s : STAND_INS) {
         if (s.wanted() == wanted) {
            return s.standIn();
         }
      }
      return wanted;
   }

   /** The table, for the checks that pin it. */
   public static List<StandIn> standIns() {
      return STAND_INS;
   }
}
