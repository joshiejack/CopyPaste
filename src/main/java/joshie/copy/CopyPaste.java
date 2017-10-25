package joshie.copy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
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
import static joshie.copy.CopyPaste.VERSION;

@SuppressWarnings("ConstantConditions, WeakerAccess, unused")
@Mod(modid = MODID, version = VERSION, acceptableRemoteVersions = "*", acceptedMinecraftVersions = "*")
@Config(modid = MODID)
public class CopyPaste {
    static final String MODID = "copypaste";
    static final String VERSION = "1.1";
    @Config.Comment("Keep world data updated with the contents of the copy folder")
    public static boolean copyExisting = true;
    private Logger logger = LogManager.getLogger(MODID);
    private File root;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        root = new File(event.getModConfigurationDirectory(), "copy");
    }

    @EventHandler
    public void onServerStarting(FMLServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        ISaveHandler isavehandler = server.getActiveAnvilConverter().getSaveLoader(server.getFolderName(), true);
        File directory = isavehandler.getWorldDirectory();
        File file = new File(directory, "copied.log");
        if (!file.exists()) {
            try {
                logger.log(Level.INFO, "Copying files to the world...");
                FileUtils.writeLines(file, getMD5FromFiles(getFilesInDirectory(root)));
                FileUtils.copyDirectory(root, isavehandler.getWorldDirectory());
            } catch (IOException ex) {
                ex.printStackTrace();
                logger.log(Level.ERROR, "There was an error while trying to copy ");
            }
        } else if (copyExisting) {
            try {
                logger.log(Level.INFO, "Validating and updating files in the world...");
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
                logger.log(Level.ERROR, "Failed to update an existing world with updated files");
            }
        }
    }

    private List<String> getMD5FromFiles(Collection<File> files) {
        List<String> hashes = new ArrayList<>();
        files.stream().filter(File::isFile).forEach(file -> {
            try (FileInputStream fis = new FileInputStream(file)) {
                hashes.add(DigestUtils.md5Hex(fis));
            } catch (IOException ex) {
                logger.log(Level.ERROR, "Failed to fetch the hash for the file:" + file.toString());
            }
        });

        return hashes;
    }

    private boolean copyFileWithHash(File worldDirectory, String string) {
        for (File file : getFilesInDirectory(root)) {
            try {
                if (md5Matches(string, file)) {
                    FileUtils.copyFileToDirectory(file, new File(worldDirectory, file.getParentFile().toString().replace(root.toString(), "")));
                    return true; //Copied so returning true
                }
            } catch (IOException ex) {
                logger.log(Level.ERROR, "Failed to copy the file " + file.toString() + " from root to the world");
            }
        }

        return false;
    }

    private boolean md5Matches(String string, File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return string.equals(DigestUtils.md5Hex(fis));
        } catch (IOException ex) {
            logger.log(Level.ERROR, "Failed to fetch the hash for the file:" + file.toString());
            return false;
        }
    }

    private Collection<File> getFilesInDirectory(File directory) {
        return FileUtils.listFiles(directory, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private boolean deleteFileWithHash(File directory, String string) {
        for (File file : getFilesInDirectory(directory)) {
            try {
                if (file.isFile() && md5Matches(string, file)) {
                    FileUtils.forceDelete(file.getCanonicalFile());
                    return true;
                }
            } catch (IOException ex) {
                logger.log(Level.ERROR, "Failed to delete the file " + file.toString() + " from the world");
            }
        }

        return false;
    }
}
