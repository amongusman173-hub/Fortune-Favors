package com.fortuneandfavors.util;

import com.fortuneandfavors.mixin.ConnectionAccessor;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The address a player actually connected to.
 *
 * <h2>Why this is read rather than stored</h2>
 * An address was the one line a board had to be *told*: it is not about the player, and it is not
 * something the server can look up either, because the address only exists on the client's side of
 * the wire. Which left a hardcoded string in the config, and a board that printed the same address
 * to everybody - including to players who had joined through a different hostname, and including on
 * a server that string had nothing to do with.
 *
 * <p>It exists in exactly one place: the handshake. Before a client has a name, a uuid or a player
 * object, it sends the host and port it was told to dial, and vanilla keeps neither. So the
 * handshake is recorded as it arrives - keyed by the {@link Connection}, the only thing that exists
 * at that point - and read back per player once they have a body to ask about.
 *
 * <h2>What that buys</h2>
 * Each player sees the address they used, which is the only address guaranteed to work for them:
 * two players can arrive through two hostnames and both be right. And it is the address of the
 * server they are on *now*, so nothing carries over from the last one.
 *
 * <p>The map is weak-keyed, so a connection that closes and is collected takes its entry with it -
 * this is a lookup, not a log, and nothing here outlives the session it describes.
 */
public final class ServerAddress {
   /** The port a bare address means, and therefore the one an address need not carry. */
   private static final int DEFAULT_PORT = 25565;

   /** Longest address kept. Short enough to stay a sidebar, long enough for every real hostname. */
   private static final int MAX_ADDRESS = 64;

   private static final Map<Connection, String> BY_CONNECTION =
      Collections.synchronizedMap(new WeakHashMap<>());

   private ServerAddress() {
   }

   /**
    * Records the host a handshake carried, against the connection that carried it.
    *
    * <p>Called from {@code HandshakeAddressMixin} at the head of vanilla's handshake handler, which
    * is the only moment this information exists at all - it is gone by the time anybody could log
    * in. Keyed by connection rather than by address or name because there is no name yet, and
    * deliberately not by the remote socket address: two players behind one router share that, and
    * handing one player a board naming the other's server is exactly the class of mistake the
    * sidebar was rebuilt to stop.
    */
   public static void remember(Connection connection, String host, int port) {
      if (connection == null) {
         return;
      }
      String address = format(host, port);
      if (!address.isEmpty()) {
         BY_CONNECTION.put(connection, address);
      }
   }

   /** The address this player connected through, or {@code null} if it was never seen. */
   public static String of(ServerPlayer player) {
      if (player == null || player.connection == null) {
         return null;
      }
      try {
         Connection connection = ((ConnectionAccessor)player.connection).fortuneandfavors$connection();
         return connection == null ? null : BY_CONNECTION.get(connection);
      } catch (Throwable t) {
         // A board is decoration; a board that takes a tick with it is a broken server.
         return null;
      }
   }

   /**
    * The address the server itself was configured to bind, when that is an address anybody could
    * dial.
    *
    * <p>Last in line and usually absent: a dedicated server's {@code server-ip} is blank (bind
    * everything) or a wildcard, and a bound interface is not a hostname. It is here for the case
    * where server-ip *is* the real thing - a machine with its own name - because printing nothing at
    * all is not obviously better than printing what the server knows.
    */
   public static String local(MinecraftServer server) {
      if (server == null) {
         return null;
      }
      String ip = server.getLocalIp();
      if (ip == null) {
         return null;
      }
      ip = ip.trim();
      if (ip.isEmpty() || "0.0.0.0".equals(ip) || "::".equals(ip) || "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip)) {
         return null;
      }
      return format(ip, server.getPort());
   }

   /**
    * The address a handshake carries, as a board should print it.
    *
    * <p>Three things happen here, and each of them is a real handshake rather than a hypothetical:
    * a modded client appends its own data to the host after a {@code NUL} (Fabric clients do, and so
    * does every BungeeCord-style forwarder), so anything from the first {@code NUL} on is dropped;
    * the port is appended only when it is not the one a bare address already means, so the common
    * case stays as short as the player typed it; and the whole thing is capped, because this string
    * arrives from a client and ends up on a sidebar.
    */
   public static String format(String host, int port) {
      if (host == null) {
         return "";
      }
      String address = host;
      int nul = address.indexOf('\0');
      if (nul >= 0) {
         address = address.substring(0, nul);
      }
      address = address.trim();
      if (address.length() > MAX_ADDRESS) {
         address = address.substring(0, MAX_ADDRESS);
      }
      if (!address.isEmpty() && port != DEFAULT_PORT && address.indexOf(':') < 0) {
         address = address + ":" + port;
      }
      return address;
   }
}
