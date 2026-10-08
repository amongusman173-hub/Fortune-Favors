package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.util.PerfMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
   @Accessor("players")
   protected abstract List<ServerPlayer> fortuneandfavors$players();

   /**
    * Counts every particle the server is asked to send, for {@code /ff perf report}.
    *
    * <p>This is the one door all of them go through - the mod's effects and vanilla's spawners and
    * explosions alike - so the number is complete without asking forty call sites to report
    * themselves, and without a single one of them being able to forget to. The count is the
    * <i>requested</i> count rather than the method's return value: the return is the number of
    * viewers a packet went to, which is a different quantity and a much less useful one, while
    * the argument is the number that actually costs anything.
    *
    * <p>A HEAD injection with a callback it never reads, deliberately: the call is counted whether
    * or not the server then decides there is nobody in range, because "the world asked for 200,000
    * particles this tick" is the finding, and the alternative reports a healthy zero on a server
    * whose players are simply spread out.
    */
   @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("HEAD"))
   private void fortuneandfavors$countParticles(
      ParticleOptions type,
      double x,
      double y,
      double z,
      int count,
      double xOffset,
      double yOffset,
      double zOffset,
      double speed,
      CallbackInfoReturnable<Integer> cir
   ) {
      PerfMonitor.countParticles(count);
   }

   @Inject(method = "getPlayers(Ljava/util/function/Predicate;I)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$hidePuppets(Predicate<? super ServerPlayer> predicate, int limit, CallbackInfoReturnable<List<ServerPlayer>> cir) {
      List<ServerPlayer> out = new ArrayList<>();

      for (ServerPlayer pl : this.fortuneandfavors$players()) {
         if (pl != null && !BossManager.isMindPuppet(pl) && predicate.test(pl)) {
            out.add(pl);
            if (out.size() >= limit) {
               break;
            }
         }
      }

      cir.setReturnValue(out);
   }
}
