package com.fortuneandfavors.util;

import com.fortuneandfavors.FortuneFavorsMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

public final class JsonUtil {
   public static final int CURRENT_VERSION = 1;
   public static final int BACKUP_KEEP = 5;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   /** The exact JSON this process last wrote for a file, so a save that would
    *  rewrite identical bytes can be skipped. See {@link #write}. */
   private static final java.util.Map<Path, String> lastWritten = new java.util.HashMap<>();

   private JsonUtil() {
   }

   public static Gson gson() {
      return GSON;
   }

   public static JsonObject readOrCreate(Path file, JsonObject defaults) {
      if (Files.exists(file)) {
         try {
            String content = Files.readString(file);
            if (!content.isBlank()) {
               JsonObject obj = (JsonObject)GSON.fromJson(content, JsonObject.class);
               if (obj != null) {
                  return obj;
               }
            }

            FortuneFavorsMod.LOGGER.warn("{} is blank or holds no JSON object - leaving it untouched, trying backups", file);
            JsonObject fromBackup = readBestBackup(file);
            return fromBackup != null ? fromBackup : defaults;
         } catch (IOException e) {
            e.printStackTrace();
            FortuneFavorsMod.LOGGER.warn("Could not read {} - leaving it untouched, trying backups", file);
            JsonObject fromBackup = readBestBackup(file);
            return fromBackup != null ? fromBackup : defaults;
         } catch (RuntimeException e) {
            FortuneFavorsMod.LOGGER.warn("Could not parse {} - leaving it untouched, trying backups", file, e);
            JsonObject fromBackup = readBestBackup(file);
            return fromBackup != null ? fromBackup : defaults;
         }
      } else {
         write(file, defaults);
         return defaults;
      }
   }

   private static JsonObject readBestBackup(Path file) {
      for (Path backup : backups(file)) {
         try {
            if (Files.exists(backup)) {
               String content = Files.readString(backup);
               if (!content.isBlank()) {
                  JsonObject obj = (JsonObject)GSON.fromJson(content, JsonObject.class);
                  if (obj != null) {
                     FortuneFavorsMod.LOGGER.warn("Recovered {} from backup {}", file, backup.getFileName());
                     return obj;
                  }
               }
            }
         } catch (Exception var5) {
         }
      }

      return null;
   }

   public static List<Path> backups(Path file) {
      List<Path> out = new ArrayList<>();
      Path base = file.resolveSibling(file.getFileName() + ".bak");
      out.add(base);

      for (int i = 1; i <= 5; i++) {
         out.add(Path.of(base.toString() + "." + i));
      }

      return out;
   }

   /** Writes {@code obj} to {@code file} and reports whether it actually
    *  landed on disk. Callers that charge the player money (claims, slots,
    *  shops) MUST check the result - a full disk, a read-only folder or an
    *  evicted iCloud/OneDrive path used to fail silently here, which looked
    *  exactly like "my purchase didn't stick". */
   public static boolean write(Path file, JsonObject obj) {
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

      try {
         Files.createDirectories(file.getParent());
         if (obj != null && !obj.has("version")) {
            obj.addProperty("version", 1);
         }

         // Perf and iCloud: writing one of these files also rotates six names
         // (a .bak copy plus four renames), and the mod saves every file on a
         // two-minute timer whether or not anything about it changed. In a folder
         // that lives inside iCloud Drive that churn is what grew thousands of
         // conflict copies - "<name> 2", "<name> 3" ... up to "<name> 39" - and
         // every one of them made the next walk of the folder slower and the next
         // backup bigger. If we are about to write exactly the bytes this process
         // wrote last time, the file on disk already holds them: skip the dance.
         String json = GSON.toJson(obj);
         if (Files.exists(file) && json.equals(lastWritten.get(file))) {
            return true;
         }

         if (Files.exists(file)) {
            rotateBackups(file);

            try {
               Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException var5) {
            }
         }

         Files.writeString(tmp, json);

         try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
         }

         lastWritten.put(file, json);
         return true;
      } catch (IOException e) {
         FortuneFavorsMod.LOGGER.error("Could not write {} - the change was NOT saved to disk", file, e);

         try {
            Files.deleteIfExists(tmp);
         } catch (IOException var6) {
         }

         return false;
      }
   }

   private static void rotateBackups(Path file) {
      Path base = file.resolveSibling(file.getFileName() + ".bak");

      for (int i = 4; i >= 1; i--) {
         Path from = Path.of(base.toString() + "." + i);
         Path to = Path.of(base.toString() + "." + (i + 1));

         try {
            if (Files.exists(from)) {
               Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (IOException var7) {
         }
      }

      try {
         if (Files.exists(base)) {
            Files.move(base, Path.of(base.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException var6) {
      }
   }

   public static JsonElement itemToJson(ItemStack stack, Provider access) {
      return (JsonElement)ItemStack.OPTIONAL_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, access), stack).result().orElse(null);
   }

   public static ItemStack jsonToItem(JsonElement element, Provider access) {
      return ItemStack.OPTIONAL_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, access), element).result().orElse(ItemStack.EMPTY);
   }

   public static long jsonLong(JsonObject obj, String key, long def) {
      return obj.has(key) ? obj.get(key).getAsLong() : def;
   }

   public static int jsonInt(JsonObject obj, String key, int def) {
      return obj.has(key) ? obj.get(key).getAsInt() : def;
   }

   public static float jsonFloat(JsonObject obj, String key, float def) {
      return obj.has(key) ? obj.get(key).getAsFloat() : def;
   }

   public static double jsonDouble(JsonObject obj, String key, double def) {
      return obj.has(key) ? obj.get(key).getAsDouble() : def;
   }

   public static String jsonString(JsonObject obj, String key, String def) {
      return obj.has(key) ? obj.get(key).getAsString() : def;
   }

   public static boolean jsonBool(JsonObject obj, String key, boolean def) {
      return obj.has(key) ? obj.get(key).getAsBoolean() : def;
   }

   public static ItemStack withName(ItemStack stack, Component name) {
      stack = stack.copy();
      stack.set(DataComponents.CUSTOM_NAME, name);
      return stack;
   }

   public static ItemStack withPatch(ItemStack stack, DataComponentPatch patch) {
      stack = stack.copy();
      stack.applyComponents(patch);
      return stack;
   }
}
