package com.fortuneandfavors.mixin;

import com.fortuneandfavors.util.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The address a player dialled, caught on the way in.
 *
 * <p>The host a client connects through is sent once, in the handshake - before it has a name, a
 * uuid, or anything the server could look it up by - and vanilla keeps none of it. So a board that
 * wanted to print the server's address had to be handed a hardcoded string, which is the same
 * address on every server and for every player, including the ones who joined through a different
 * hostname.
 *
 * <p>This is the only moment the real one exists, so it is recorded here and read back later, per
 * player, by {@link ServerAddress}. The head of the handler rather than the body: {@code
 * handleIntention} goes on to open a login, and a login can fail in ways that end the connection,
 * so the address is taken before any of that can run.
 *
 * <p>Server-list pings arrive by the same route - same handler, same packet, a different intention -
 * and they are skipped, because a ping is not somebody playing and the entry would outlive it.
 *
 * <p>Registered as {@code HandshakeAddressMixin} in {@code fortuneandfavors.mixins.json}. A mixin
 * that is not listed loads as nothing at all: the board would go on printing whatever address it
 * was given, and nothing would say so.
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class HandshakeAddressMixin {
   /** The connection this handshake arrived on, which is what the address is filed under. */
   @Shadow
   private Connection connection;

   @Inject(method = "handleIntention", at = @At("HEAD"))
   private void fortuneandfavors$rememberAddress(ClientIntentionPacket packet, CallbackInfo ci) {
      try {
         if (packet.intention() != ClientIntent.LOGIN) {
            return;
         }
         ServerAddress.remember(this.connection, packet.hostName(), packet.port());
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         // The address is decoration on a sidebar. Losing it costs a line; throwing here would cost
         // the login.
      }
   }
}
