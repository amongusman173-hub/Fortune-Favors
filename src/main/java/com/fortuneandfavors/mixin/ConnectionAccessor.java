package com.fortuneandfavors.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The raw connection behind a player's packet listener, reached from the outside.
 *
 * <p>A connection is the only thing that exists both when a handshake arrives (no player yet) and
 * when a board is drawn (a player, and no handshake in living memory), so it is the key that ties
 * an address to the person who used it. Vanilla keeps that field protected, and re-deriving a key
 * from the remote socket address instead would merge every player behind one router.
 *
 * <p>Registered as {@code ConnectionAccessor} in {@code fortuneandfavors.mixins.json}; an accessor
 * that is not listed loads as nothing, and {@code ServerAddress.of} answers null for everybody.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ConnectionAccessor {
   @Accessor("connection")
   Connection fortuneandfavors$connection();
}
