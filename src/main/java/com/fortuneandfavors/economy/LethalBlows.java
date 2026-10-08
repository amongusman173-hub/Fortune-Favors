package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One ordered decision for the one question every "you would die here" rule asks.
 *
 * <p>Five systems in this mod each answer the same question - <i>this blow kills you, what
 * happens instead?</i> - and each of them used to answer it by itself: three copies of the same
 * two lines of arithmetic (health plus absorption, and a Totem of Undying in either hand),
 * written in three different files, with a fourth, more correct copy in the duelling gate, and
 * a fifth hand-rolled copy of vanilla's own totem ritual in {@code DuelManager}. That is how
 * "totems do nothing in the Gladiator" happened: the duel's own elimination gate absorbs a blow
 * by cancelling it, cancelling it is what stops vanilla from ever reaching its own totem check,
 * and nothing in the code said who was allowed to take a blow a totem would have taken.
 *
 * <p>So the arithmetic lives here, once, and the *order* is the thing this file exists to
 * publish. A blow is read as fatal after armor and toughness (the figure vanilla's own
 * protection uses), the vanilla totem is a rule in the list rather than an implicit fact, and a
 * rule that wants to take a blow the player's totem would have saved has to say so out loud with
 * {@link Rule#canOverruleTotem()} - which is exactly the declaration the audit and the self-test
 * check, because a rule that outranks a totem without meaning to is a player watching their
 * totem burn for nothing.
 *
 * <p>Nothing here is authored per player: a rule gets a {@link Blow} and returns one of three
 * {@link Answer}s, and the walk stops at the first rule that has an opinion.
 */
public final class LethalBlows {
   private LethalBlows() {
   }

   /** What a rule thinks of a blow. */
   public enum Answer {
      /** No opinion: this blow is not mine, ask the next rule. */
      INNOCENT,
      /** The blow belongs to someone else - normally the player's own totem. Let it land. */
      DEFER,
      /** I take the blow: the rule has already done its thing, and the death is refused. */
      CLAIM
   }

   /**
    * A blow that would kill a player, with the arithmetic already done.
    *
    * @param amount   the raw damage as the damage event reports it, before armor
    * @param effective that damage after armor and toughness - what vanilla's own save-or-die
    *                  check compares a body against, and therefore what "fatal" has to mean
    */
   public record Blow(
      ServerPlayer player,
      DamageSource source,
      float amount,
      float effective,
      boolean totemInHand,
      boolean cutsThroughTotem
   ) {
      /** True when this blow really would kill: the body has nothing left to soak it with. */
      public boolean fatal() {
         return player.getHealth() + player.getAbsorptionAmount() <= effective;
      }
   }

   /**
    * One answer to "what happens instead".
    *
    * <p>{@code precedence} is the whole ordering; {@code canOverruleTotem} is the one claim a
    * rule has to make explicitly.
    */
   public interface Rule {
      String id();

      int precedence();

      /**
       * True when this rule may take a blow that a Totem of Undying in the player's hand would
       * have saved. Only a system that has its own reason to beat a totem - a duel gate that
       * already owns every blow in its match - should ever answer yes.
       */
      boolean canOverruleTotem();

      Answer answer(Blow blow);
   }

   /** A rule whose whole body is a lambda. */
   private record Simple(String id, int precedence, boolean canOverruleTotem, Handler handler) implements Rule {
      @Override
      public Answer answer(Blow blow) {
         // Asked about no blow at all, a rule has no opinion - never a claim. This is the shape
         // the harness asks every rule in, and the shape the walk uses when a body has gone.
         if (blow == null || blow.player() == null) {
            return Answer.INNOCENT;
         }

         Answer a = this.handler.answer(blow);
         return a == null ? Answer.INNOCENT : a;
      }
   }

   private interface Handler {
      Answer answer(Blow blow);
   }

   /**
    * The player's own answer, and the reason the ordering exists: a totem in hand is a save
    * vanilla performs itself, inside the damage application - so the honest thing for this layer
    * to do about a blow a totem would take is to hand it straight back.
    *
    * <p>It sits at {@link #TOTEM_PRECEDENCE}, and everything that comes after it loses to it by
    * construction rather than by remembering to check a hand.
    */
   public static final Rule VANILLA_TOTEM = new Simple("vanilla-totem", 50, false, blow -> blow.totemInHand() ? Answer.DEFER : Answer.INNOCENT);

   /** Where the player's own answer sits in the walk; the audit reads this. */
   public static final int TOTEM_PRECEDENCE = 50;

   /** A fatal blow inside a standing Mirage Castle takes you home instead. */
   public static final Rule MIRAGE_CASTLE = new Simple(
      "mirage-castle", 60, false, blow -> MirageCastleManager.answerFatalBlow(blow)
   );

   /** A fatal blow on an expedition ends the run and costs the secured loot, never the pack. */
   public static final Rule EXPEDITION = new Simple(
      "expedition", 70, false, blow -> ExpeditionManager.answerFatalBlow(blow)
   );

   /** A fatal blow in the prison is a trip to Solitary, not a death. */
   public static final Rule PRISON_SOLITARY = new Simple(
      "prison-solitary", 80, false, blow -> PrisonCellblock.answerFatalBlow(blow)
   );

   /**
    * The walk, in order. Every rule that answers a fatal player blow is listed here, in one
    * screen, which is the point of this file: a reader asking "who may save me from this, and in
    * what order" gets an answer without opening five other classes.
    */
   private static final List<Rule> RULES = new ArrayList<>(
      List.of(VANILLA_TOTEM, MIRAGE_CASTLE, EXPEDITION, PRISON_SOLITARY)
   );

   static {
      RULES.sort(Comparator.comparingInt(Rule::precedence));
   }

   /** The ordered rules, for a test or a command to read back. */
   public static List<Rule> rules() {
      return List.of(RULES.toArray(new Rule[0]));
   }

   /** The walk as text: {@code precedence id} and, for an overrider, that it beats a totem. */
   public static List<String> order() {
      List<String> out = new ArrayList<>();

      for (Rule r : RULES) {
         out.add(r.precedence() + " " + r.id() + (r.canOverruleTotem() ? " (overrules a totem)" : ""));
      }

      return List.copyOf(out);
   }

   // ------------------------------------------------------------------ the arithmetic

   /** The damage this blow would really do, after armor and toughness. */
   public static float effectiveDamage(ServerPlayer player, DamageSource source, float amount) {
      float armor = 0.0F;
      float toughness = 0.0F;

      try {
         AttributeInstance a = player.getAttribute(Attributes.ARMOR);
         AttributeInstance t = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
         if (a != null) {
            armor = (float)a.getValue();
         }
         if (t != null) {
            toughness = (float)t.getValue();
         }
      } catch (Throwable ignored) {
      }

      try {
         return CombatRules.getDamageAfterAbsorb(player, amount, source, armor, toughness);
      } catch (Throwable ignored) {
         // A source with no attacker, a body with no attributes: the raw figure is the honest
         // fallback, and it errs towards calling the blow fatal, which is the safe direction.
         return amount;
      }
   }

   /**
    * True when this blow would kill.
    *
    * <p>After armor, and with absorption counted - the two corrections that matter, and the two
    * this mod has got wrong before: a hit that empties the health bar while golden apples are
    * still soaking it was read as fatal (and threw players out of events they were winning), and
    * so was a hit the player's own armor was about to eat.
    */
   public static boolean wouldKill(ServerPlayer player, DamageSource source, float amount) {
      if (player == null) {
         return false;
      }

      return player.getHealth() + player.getAbsorptionAmount() <= effectiveDamage(player, source, amount);
   }

   /** A Totem of Undying in either hand. Vanilla only checks the hands, so neither does this. */
   public static boolean carriesTotem(ServerPlayer player) {
      if (player == null) {
         return false;
      }

      try {
         return player.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * True when this blow is delivered by an Excalibur - the one blade in the mod that has an
    * opinion about totems, and the one reason a rule is allowed to take a saved blow.
    */
   public static boolean cutsThroughTotem(DamageSource source) {
      if (source == null || source.getEntity() == null || !(source.getEntity() instanceof ServerPlayer attacker)) {
         return false;
      }

      try {
         ItemStack held = attacker.getMainHandItem();
         return held.has(DataComponents.CUSTOM_NAME)
            && ((Component)held.get(DataComponents.CUSTOM_NAME)).getString().contains("Excalibur");
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Spends a held Totem of Undying the way vanilla spends one.
    *
    * <p>This exists because a gate that cancels a blow never reaches vanilla's check, and the
    * only correct thing to do then is the ritual by hand: the totem is consumed, the body is put
    * on one heart, the effects are granted, and two seconds of grace are handed out so the save
    * is a save rather than a formality the next swing undoes.
    *
    * <p>An Excalibur beats it: the blade is refused here with the line that says so, and the
    * totem stays in the hand - a fighter who reads the message keeps the item that failed.
    *
    * @return true when the totem was really spent and the blow should be refused
    */
   public static boolean spendTotem(ServerPlayer player, DamageSource source) {
      if (player == null || !player.isAlive()) {
         return false;
      }

      InteractionHand hand;
      if (player.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.TOTEM_OF_UNDYING)) {
         hand = InteractionHand.MAIN_HAND;
      } else if (player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING)) {
         hand = InteractionHand.OFF_HAND;
      } else {
         return false;
      }

      if (cutsThroughTotem(source)) {
         Chat.msg(player, "&4Excalibur cuts through your totem - &cnothing saves you from that blade.");
         return false;
      }

      try {
         player.getItemInHand(hand).shrink(1);
         player.setHealth(1.0F);
         player.removeAllEffects();
         player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
         player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
         player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
         player.invulnerableTime = 40;

         if (player.level() instanceof ServerLevel level) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 70, 0.6, 0.9, 0.6, 0.5);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 24, 0.5, 0.8, 0.5, 0.12);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.4F, 1.0F);
         }

         return true;
      } catch (Throwable t) {
         // A broken save must not become an eaten totem: the totem stays and the blow lands the
         // way it would have without any of this.
         return false;
      }
   }

   // ------------------------------------------------------------------ the walk

   /** Reads a blow the way every rule will see it. */
   public static Blow blowOf(ServerPlayer player, DamageSource source, float amount) {
      return new Blow(
         player, source, amount, player == null ? amount : effectiveDamage(player, source, amount),
         carriesTotem(player), cutsThroughTotem(source)
      );
   }

   /**
    * The one call the damage layer makes: true = let the blow land, false = refuse it.
    *
    * <p>A blow that is not fatal is nobody's business and lands: that is the first line, and it
    * is why a scratch does not throw an expedition away or send a castle's guest home.
    */
   public static boolean resolve(ServerPlayer player, DamageSource source, float amount) {
      if (player == null || player.level().isClientSide()) {
         return true;
      }

      Blow blow = blowOf(player, source, amount);
      if (!blow.fatal()) {
         return true;
      }

      for (Rule rule : RULES) {
         Answer answer = safeAnswer(rule, blow);
         if (answer == Answer.CLAIM) {
            return false;
         }
         if (answer == Answer.DEFER) {
            return true;
         }
      }

      return true;
   }

   /**
    * One rule, asked the way the walk would ask it - the entry point a system keeps for its own
    * callers (and for the harness), so "what does this rule say about this blow" has one answer
    * whether it is asked alone or as part of the order.
    */
   public static boolean verdict(Rule rule, ServerPlayer player, DamageSource source, float amount) {
      if (player == null || player.level().isClientSide()) {
         return true;
      }

      Blow blow = blowOf(player, source, amount);
      if (!blow.fatal()) {
         return true;
      }

      return safeAnswer(rule, blow) != Answer.CLAIM;
   }

   private static Answer safeAnswer(Rule rule, Blow blow) {
      try {
         Answer a = rule.answer(blow);
         return a == null ? Answer.INNOCENT : a;
      } catch (Throwable t) {
         // A rule that throws has no opinion, and never a claim: a rule that silently claimed
         // would refuse a death nobody arranged.
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error(
            "Fortune & Favors: lethal-blow rule '" + rule.id() + "' failed - treating it as having no opinion", t
         );
         return Answer.INNOCENT;
      }
   }
}
