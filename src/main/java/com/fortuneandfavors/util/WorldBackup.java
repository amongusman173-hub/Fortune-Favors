package com.fortuneandfavors.util;

import com.fortuneandfavors.economy.ModConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class WorldBackup {
   private static final String ROOT = "fortuneandfavors_backups";
   private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

   private WorldBackup() {
   }

   public static void backupWorld(MinecraftServer server) {
      try {
         Path world = server.getWorldPath(LevelResource.ROOT);
         if (world == null || !Files.isDirectory(world)) {
            return;
         }

         Path dest = backupRoot(server).resolve(TS.format(LocalDateTime.now()) + "_world");
         copyWorld(world, dest);
         prune(backupRoot(server), ModConfig.backupKeep());
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   public static void backupData(MinecraftServer server) {
      try {
         Path data = server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors");
         if (!Files.isDirectory(data)) {
            return;
         }

         Path dest = backupRoot(server).resolve(TS.format(LocalDateTime.now()) + "_data");
         copyTree(data, dest);
         prune(backupRoot(server), ModConfig.backupKeep());
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   private static Path backupRoot(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).getParent().resolve("fortuneandfavors_backups");
   }

   private static void copyWorld(Path world, Path dest) throws IOException {
      Files.createDirectories(dest);
      copyFile(world.resolve("level.dat"), dest.resolve("level.dat"));
      copyFile(world.resolve("level.dat_old"), dest.resolve("level.dat_old"));
      copyDir(world.resolve("playerdata"), dest.resolve("playerdata"));
      copyDir(world.resolve("advancements"), dest.resolve("advancements"));
      copyDir(world.resolve("stats"), dest.resolve("stats"));
      copyDir(world.resolve("region"), dest.resolve("region"));
      copyDir(world.resolve("poi"), dest.resolve("poi"));
      copyDir(world.resolve("entities"), dest.resolve("entities"));

      for (String dim : new String[]{"DIM-1", "DIM1"}) {
         Path d = world.resolve(dim);
         if (Files.isDirectory(d)) {
            copyDir(d.resolve("region"), dest.resolve(dim).resolve("region"));
            copyDir(d.resolve("poi"), dest.resolve(dim).resolve("poi"));
            copyDir(d.resolve("entities"), dest.resolve(dim).resolve("entities"));
         }
      }

      Path modData = world.resolve("fortuneandfavors");
      if (Files.isDirectory(modData)) {
         copyTree(modData, dest.resolve("fortuneandfavors"));
      }
   }

   private static void copyTree(Path src, Path dst) throws IOException {
      Files.createDirectories(dst);

      try (Stream<Path> stream = Files.walk(src)) {
         java.util.Iterator<Path> it = stream.iterator();
         while (it.hasNext()) {
            Path p = it.next();
            if (!p.equals(src)) {
               Path target = dst.resolve(src.relativize(p).toString());
               if (isICloudConflict(p.getFileName().toString())) {
                  continue;
               }
               if (Files.isDirectory(p)) {
                  Files.createDirectories(target);
               } else {
                  copyFile(p, target);
               }
            }
         }
      }
   }

   private static void copyDir(Path src, Path dst) {
      if (Files.isDirectory(src)) {
         try {
            Files.createDirectories(dst);

            try (Stream<Path> stream = Files.list(src)) {
               stream.forEach(p -> copyFile(p, dst.resolve(p.getFileName().toString())));
            }
         } catch (IOException var7) {
         }
      }
   }

   private static void copyFile(Path src, Path dst) {
      if (src != null && dst != null && Files.isRegularFile(src)) {
         String name = src.getFileName().toString();
         if (isICloudConflict(name)) {
            return;
         }
         if (!name.equals("session.lock") && !name.endsWith(".tmp") && !name.endsWith(".new") && !name.endsWith(".lock")) {
            try {
               Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException var4) {
            }
         }
      }
   }

   private static void prune(Path root, int keep) {
      if (Files.isDirectory(root)) {
         List<Path> dirs = new ArrayList<>();

         try (Stream<Path> stream = Files.list(root)) {
            stream.filter(x$0 -> Files.isDirectory(x$0)).forEach(dirs::add);
         } catch (IOException ignored) {
            return;
         }

         if (dirs.size() > keep) {
            dirs.sort(Comparator.comparing(Path::getFileName));

            for (int i = 0; i < dirs.size() - keep; i++) {
               deleteTree(dirs.get(i));
            }
         }
      }
   }

   /**
    * True for iCloud's conflict copies - a {@code "<name> <n>.<ext>"} file or a
    * {@code "<name> <n>"} directory. This project lives inside iCloud Drive, which
    * keeps resurrecting them (the {@code scrubICloudConflicts} build task fights
    * the same thing in {@code src/} and {@code build/}).
    *
    * <p>A backup that carries one is junk, and worse than junk in the prune: iCloud
    * removes the copy underneath a running {@code Files.walk}, which is how a prune
    * used to die. See {@link #deleteTree}.
    */
   /**
    * The document name an iCloud conflict counter was inserted into, or
    * {@code null} when the name carries no counter at all.
    *
    * <p>iCloud inserts {@code " <n>"} before the extension
    * ({@code "contracts.json 2.bak"}) or at the end
    * ({@code "contracts.json.bak.5 39"}), and then it copies its own copy, so the
    * copies chain: {@code "auctions.json.bak.5 39.bak"} is junk with no original
    * of its own sitting next to it to compare against. Stripping the counter -
    * and a trailing {@code .bak}, which the mod only ever writes after a counter
    * has been taken off - repeatedly collapses a chain of any length back to the
    * document it came from.
    *
    * <p>A name with no counter in it returns null even when it is a
    * {@code .bak}, because those are the generations the recovery path reads.
    */
   public static String conflictOriginal(String name) {
      if (name == null || name.isEmpty()) {
         return null;
      }
      String current = name;
      boolean sawCounter = false;
      while (true) {
         String next = current;
         if (next.endsWith(".bak")) {
            next = next.substring(0, next.length() - 4);
         } else {
            int space = next.lastIndexOf(' ');
            if (space > 0 && space < next.length() - 1) {
               int dot = next.indexOf('.', space + 1);
               String digits = dot < 0 ? next.substring(space + 1) : next.substring(space + 1, dot);
               boolean numeric = !digits.isEmpty();
               for (int i = 0; i < digits.length() && numeric; i++) {
                  numeric = Character.isDigit(digits.charAt(i));
               }
               if (numeric) {
                  next = dot < 0 ? next.substring(0, space) : next.substring(0, space) + next.substring(dot);
                  sawCounter = true;
               }
            }
         }
         if (next.equals(current)) {
            break;
         }
         current = next;
      }
      return sawCounter ? current : null;
   }

   public static boolean isICloudConflict(String name) {
      // Test hook: public so the name rule can be asserted without a filesystem,
      // the same way the boss numbers are exposed for their own assertions.
      if (name == null || name.isEmpty()) {
         return false;
      }
      int space = name.lastIndexOf(' ');
      if (space <= 0 || space == name.length() - 1) {
         return false;
      }
      String tail = name.substring(space + 1);
      int dot = tail.indexOf('.');
      String digits = dot < 0 ? tail : tail.substring(0, dot);
      if (digits.isEmpty()) {
         return false;
      }
      for (int i = 0; i < digits.length(); i++) {
         if (!Character.isDigit(digits.charAt(i))) {
            return false;
         }
      }
      return true;
   }

   /**
    * Deletes iCloud conflict copies and abandoned {@code .tmp} files from the
    * mod's own data directory, and returns how many were removed.
    *
    * <p>That folder holds about forty documents. It was found holding 3447
    * entries, 1577 of them conflict copies, the worst of which had climbed to
    * {@code challenges.json.bak.5 39}: iCloud mints one whenever it sees the same
    * file change twice, and the mod rewrites and rotates every one of these files
    * on a two-minute timer, so it mints them continuously. Nothing ever removed
    * them, so the folder only grew - every data backup walked all of them, every
    * conflict copy the backup carried made it bigger, and on a synced volume just
    * reading one can stall the calling thread on a download.
    *
    * <p>Only a copy whose original is sitting right next to it is removed. A
    * canonical file, and a {@code .bak} generation the recovery path depends on,
    * are never touched.
    */
   public static int scrubConflictCopies(Path dir) {
      if (dir == null || !Files.isDirectory(dir)) {
         return 0;
      }
      List<Path> junk = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(dir)) {
         stream.forEach(p -> {
            if (Files.isDirectory(p)) {
               return;
            }
            String name = p.getFileName().toString();
            if (name.endsWith(".tmp")) {
               junk.add(p);
               return;
            }
            // Only a copy whose original is still sitting next to it is junk.
            // Requiring that is what makes it impossible for this to delete a
            // file that was the only copy of anything.
            String original = conflictOriginal(name);
            if (original != null && Files.isRegularFile(p.resolveSibling(original))) {
               junk.add(p);
            }
         });
      } catch (IOException | UncheckedIOException e) {
         // A partial listing is still worth cleaning.
      }
      int removed = 0;
      for (Path p : junk) {
         try {
            if (Files.deleteIfExists(p)) {
               removed++;
            }
         } catch (IOException | RuntimeException e) {
            // Held open by the sync daemon - the next boot will take it.
         }
      }
      return removed;
   }

   /**
    * Deletes a backup directory tree, collecting the paths before deleting any of
    * them.
    *
    * <p>The lazy version - {@code Files.walk(root).sorted(...).forEach(delete)} -
    * threw once per prune and kept every backup forever. {@code Files.walk} is a
    * <i>lazy</i> stream whose iterator reads attributes as it goes, and when a file
    * listed by the walk is gone by the time it is read (an iCloud conflict copy the
    * daemon just reaped, or anything else touching the folder), the failure is an
    * {@link UncheckedIOException} - which is a {@link RuntimeException}, so the
    * {@code catch (IOException)} around it never saw it. Sorting first and deleting
    * second means the walk is finished before anything on disk changes, and the
    * remainder is one try/catch per path.
    */
   private static void deleteTree(Path root) {
      List<Path> victims = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(root)) {
         stream.forEach(victims::add);
      } catch (IOException | UncheckedIOException e) {
         // The walk died partway: delete what it did hand over.
      }
      victims.sort(Comparator.reverseOrder());
      for (Path path : victims) {
         try {
            Files.deleteIfExists(path);
         } catch (IOException | RuntimeException e) {
            // Held open by another process - the next prune will take it.
         }
      }
   }
}
