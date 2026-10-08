package com.fortuneandfavors.client.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.HashedPatchMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hashed stack is only meaningful while the client and the server are hashing the same way,
 * so {@code HashedPatchMap.create} is handed a generator and dereferences it once per component.
 * Vanilla always passes {@code ClientPacketListener.decoratedHashOpsGenenerator()}, which is built
 * from the client's own registry access and is there by the time a world is loaded - but a mod that
 * assembles a packet by hand can pass null, and vanilla calls it on the client's own thread, inside
 * the very packet being built. autototem 1.1.0 does exactly that when it re-stocks a popped totem:
 * it hashes the carried item with a literal null, so the NullPointerException comes up through
 * {@code ClientPacketListener.handleEntityEvent} -> the packet processor, and the client is
 * disconnected with "Network Protocol Error" on any totem pop whose spare sits outside the hotbar.
 *
 * <p>A generator that is not there means there is nothing to hash against, and the honest answer to
 * that is a map with no components in it rather than an exception. The hash is the server's own
 * remote-slot bookkeeping: a map that does not line up makes the server resend that slot, which is
 * a redundant update, where the alternative is a lost connection. Nothing vanilla reaches this
 * branch, because nothing vanilla passes a null generator.
 */
@Mixin(HashedPatchMap.class)
public abstract class HashedPatchMapMixin {
   @Inject(method = "create", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors$tolerateMissingHasher(
      DataComponentPatch patch, HashedPatchMap.HashGenerator hasher, CallbackInfoReturnable<HashedPatchMap> cir
   ) {
      if (hasher == null) {
         cir.setReturnValue(new HashedPatchMap(Map.of(), Set.of()));
      }
   }
}
