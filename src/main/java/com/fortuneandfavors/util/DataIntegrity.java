package com.fortuneandfavors.util;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.EconomyManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;

/**
 * Proves at startup that the data we are about to play on is actually readable,
 * and that anything unreadable still has a usable backup to rebuild from.
 *
 * <p>Every manager in this mod persists its state as one JSON file next to the
 * world. When one of those files is quietly emptied or corrupted, nothing fails
 * loudly: claims just stop protecting land, spawners stop being owned, the Item
 * Forge stops recognising crafted machines, achievements come back blank. By the
 * time anyone notices, every backup generation has usually rotated past the good
 * copy - which is exactly how a wipe becomes permanent.
 *
 * <p>So this runs once per boot, right after every manager has loaded: it parses
 * each file, reports how many are readable and how many are empty shells, and
 * for each broken one names the file AND whether a backup can still save it. The
 * matching {@code /claim restore}, {@code /spawners recover} or {@code /ff
 * restore} command then does the actual rebuild.
 *
 * <p>Read-only: it never repairs, deletes or rewrites anything.
 */
public final class DataIntegrity {
   /** A named file that needs attention, plus whether it can be rebuilt. */
   public record Problem(String file, String reason, Path usableBackup) {
      public boolean recoverable() {
         return usableBackup != null;
      }
   }

   private DataIntegrity() {
   }

   public static List<Problem> audit(MinecraftServer server) {
      List<Problem> problems = new ArrayList<>();
      Path dir;

      try {
         dir = EconomyManager.getDataDir(server);
      } catch (Throwable t) {
         return problems;
      }

      if (dir == null || !Files.isDirectory(dir)) {
         return problems;
      }

      int total = 0;
      int empty = 0;

      try (Stream<Path> listing = Files.list(dir)) {
         for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
            total++;
            String reason = inspect(file);
            if (reason == null) {
               if (isShell(file)) {
                  empty++;
               }
               continue;
            }

            Problem problem = new Problem(file.getFileName().toString(), reason, usableBackup(file));
            problems.add(problem);
            FortuneFavorsMod.LOGGER.error(
               "Data integrity: {} is unreadable ({}) - {}. Do NOT delete it; the matching /claim restore, /spawners recover or /ff restore command can rebuild it.",
               new Object[]{
                  file.getFileName(),
                  reason,
                  problem.recoverable()
                     ? "a usable backup was found at " + problem.usableBackup().getFileName()
                     : "no usable backup was found, so the data may need restoring from a world backup"
               }
            );
         }
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Data integrity: could not list {}", dir, e);
         return problems;
      }

      if (problems.isEmpty()) {
         FortuneFavorsMod.LOGGER.info(
            "Data integrity: all {} data file(s) readable{}",
            total,
            empty > 0 ? " (" + empty + " empty - normal for a fresh world, alarming if this world has been played)" : ""
         );
      } else {
         int recoverable = 0;
         for (Problem p : problems) {
            if (p.recoverable()) {
               recoverable++;
            }
         }

         FortuneFavorsMod.LOGGER.error(
            "Data integrity: {} of {} data file(s) UNREADABLE ({} rebuildable from a backup). See the lines above.",
            new Object[]{problems.size(), total, recoverable}
         );
      }

      return problems;
   }

   /** Null when the file parses as a JSON object, otherwise why it does not. */
   private static String inspect(Path file) {
      try {
         String content = Files.readString(file);
         if (content.isBlank()) {
            return "blank";
         }

         JsonElement parsed = JsonUtil.gson().fromJson(content, JsonElement.class);
         if (parsed == null || !parsed.isJsonObject()) {
            return "not a JSON object";
         }

         return null;
      } catch (Exception e) {
         return e.getClass().getSimpleName();
      }
   }

   /** True for a valid file that simply holds nothing yet - every value is either
    *  the version stamp or an empty object/array. Distinguishes "fresh world"
    *  from "something ate the contents". */
   private static boolean isShell(Path file) {
      try {
         JsonElement parsed = JsonUtil.gson().fromJson(Files.readString(file), JsonElement.class);
         if (parsed == null || !parsed.isJsonObject()) {
            return false;
         }

         for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
            if ("version".equals(e.getKey())) {
               continue;
            }

            JsonElement value = e.getValue();
            boolean emptyValue = value.isJsonArray() && value.getAsJsonArray().isEmpty()
               || value.isJsonObject() && value.getAsJsonObject().isEmpty();
            if (!emptyValue) {
               return false;
            }
         }

         return true;
      } catch (Exception ignored) {
         return false;
      }
   }

   /** The first backup generation that parses, or null when none does. */
   private static Path usableBackup(Path file) {
      for (Path backup : JsonUtil.backups(file)) {
         try {
            if (Files.exists(backup) && inspect(backup) == null) {
               return backup;
            }
         } catch (Exception ignored) {
         }
      }

      return null;
   }
}
