package joshie.copy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static joshie.copy.CopyPaste.MODID;

@SuppressWarnings("WeakerAccess, unused")
@Mod(value = MODID)
@Mod.EventBusSubscriber(modid = MODID)
public class CopyPaste {
    static final String MODID = "copypaste";
    private static final Logger LOGGER = LogManager.getLogger(MODID);
    private static File root = new File(FMLPaths.CONFIGDIR.get().toFile(), "copy");

    public CopyPaste() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.spec);
    }

    @SubscribeEvent
    public static void onServerStarting(FMLServerAboutToStartEvent event) {
        if (!root.exists()) {
            root.mkdir();
        }
        MinecraftServer server = event.getServer();
        SaveHandler saveHandler = server.getActiveAnvilConverter().getSaveLoader(server.getFolderName(), server);
        File directory = saveHandler.getWorldDirectory();
        File file = new File(directory, "copied.log");
        if (!file.exists()) {
            try {
                LOGGER.log(Level.INFO, "Copying files to the world...");
                FileUtils.writeLines(file, getMD5FromFiles(getFilesInDirectory(root)));
                FileUtils.copyDirectory(root, saveHandler.getWorldDirectory());
            } catch (IOException ex) {
                ex.printStackTrace();
                LOGGER.log(Level.ERROR, "There was an error while trying to copy ");
            }
        } else if (Config.GENERAL.copyExisting.get()) {
            try {
                LOGGER.log(Level.INFO, "Validating and updating files in the world...");
                List<String> hashes = getMD5FromFiles(getFilesInDirectory(root));
                List<String> existing = FileUtils.readLines(file);
                boolean changed = false;
                //Check in the existing hashes, if the file isn't supposed to existing anymore get rid of it
                for (String hash : existing) {
                    if (!hashes.contains(hash)) changed = deleteFileWithHash(directory, hash);
                }

                //Remove all the non existing empty directories
                if (changed) {
                    FileUtils.listFilesAndDirs(directory, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE)
                            .stream().filter(File::isDirectory).forEach(File::delete);
                }

                //Check in the root hashes, if it doesn't exist already then copy the file
                for (String hash : hashes) {
                    if (!existing.contains(hash)) changed = copyFileWithHash(directory, hash);
                }

                //Update the log file
                if (changed) FileUtils.writeLines(file, getMD5FromFiles(getFilesInDirectory(root)));
            } catch (IOException ex) {
                LOGGER.log(Level.ERROR, "Failed to update an existing world with updated files");
            }
        }
    }

    private static List<String> getMD5FromFiles(Collection<File> files) {
        List<String> hashes = new ArrayList<>();
        files.stream().filter(File::isFile).forEach(file -> {
            try (FileInputStream fis = new FileInputStream(file)) {
                hashes.add(DigestUtils.md5Hex(fis));
            } catch (IOException ex) {
                LOGGER.log(Level.ERROR, "Failed to fetch the hash for the file:" + file.toString());
            }
        });

        return hashes;
    }

    private static boolean copyFileWithHash(File worldDirectory, String string) {
        for (File file : getFilesInDirectory(root)) {
            try {
                if (md5Matches(string, file)) {
                    FileUtils.copyFileToDirectory(file, new File(worldDirectory, file.getParentFile().toString().replace(root.toString(), "")));
                    return true; //Copied so returning true
                }
            } catch (IOException ex) {
                LOGGER.log(Level.ERROR, "Failed to copy the file " + file.toString() + " from root to the world");
            }
        }

        return false;
    }

    private static boolean md5Matches(String string, File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return string.equals(DigestUtils.md5Hex(fis));
        } catch (IOException ex) {
            LOGGER.log(Level.ERROR, "Failed to fetch the hash for the file:" + file.toString());
            return false;
        }
    }

    private static Collection<File> getFilesInDirectory(File directory) {
        return FileUtils.listFiles(directory, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
    }

    private static boolean deleteFileWithHash(File directory, String string) {
        for (File file : getFilesInDirectory(directory)) {
            try {
                if (file.isFile() && md5Matches(string, file)) {
                    FileUtils.forceDelete(file.getCanonicalFile());
                    return true;
                }
            } catch (IOException ex) {
                LOGGER.log(Level.ERROR, "Failed to delete the file " + file.toString() + " from the world");
            }
        }
        return false;
    }

    static class Config {
        private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
        public static final General GENERAL = new General(BUILDER);

        static class General {
            public ForgeConfigSpec.BooleanValue copyExisting;

            General(ForgeConfigSpec.Builder builder) {
                builder.push("general");
                copyExisting = builder
                        .comment("Keep world data updated with the contents of the copy folder")
                        .define("copyExisting", true);
                builder.pop();
            }
        }

        public static final ForgeConfigSpec spec = BUILDER.build();

    }
}