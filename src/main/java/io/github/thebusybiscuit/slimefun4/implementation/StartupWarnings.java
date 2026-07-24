package io.github.thebusybiscuit.slimefun4.implementation;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.ParametersAreNonnullByDefault;

import io.github.thebusybiscuit.slimefun4.utils.NumberUtils;

/**
 * This class stores some startup warnings we occasionally need to print.
 * If you setup your server the recommended way, you are never going to see
 * any of these messages.
 * 
 * @author TheBusyBiscuit
 *
 */
final class StartupWarnings {

    private static final String BORDER = "\u2550".repeat(56);
    private static final String PREFIX = "\u2551 ";
    private static final String TOP    = "\u2554" + BORDER + "\u2557";
    private static final String MID    = "\u2560" + BORDER + "\u2563";
    private static final String BOTTOM = "\u255a" + BORDER + "\u255d";

    private StartupWarnings() {}

    @ParametersAreNonnullByDefault
    static void discourageCSCoreLib(Logger logger) {
        logger.log(Level.SEVERE, TOP);
        logger.log(Level.SEVERE, PREFIX + "\u26d4 CS-CoreLib Detected");
        logger.log(Level.SEVERE, MID);
        logger.log(Level.SEVERE, PREFIX + "Slimefun no longer requires CS-CoreLib.");
        logger.log(Level.SEVERE, PREFIX + "It was removed as of January 30th 2021.");
        logger.log(Level.SEVERE, PREFIX + "Please remove CS-CoreLib to continue.");
        logger.log(Level.SEVERE, BOTTOM);
    }

    @ParametersAreNonnullByDefault
    static void invalidMinecraftVersion(Logger logger, int majorVersion, String slimefunVersion) {
        logger.log(Level.SEVERE, TOP);
        logger.log(Level.SEVERE, PREFIX + "\u26d4 Incompatible Minecraft Version");
        logger.log(Level.SEVERE, MID);
        logger.log(Level.SEVERE, PREFIX + "You are running: Minecraft 1.{0}.x", majorVersion);
        logger.log(Level.SEVERE, PREFIX + "Slimefun {0} requires: Minecraft {1}", new Object[] { slimefunVersion, String.join(" / ", Slimefun.getSupportedVersions()) });
        logger.log(Level.SEVERE, PREFIX + "Please update your server to a supported version.");
        logger.log(Level.SEVERE, BOTTOM);
    }

    @ParametersAreNonnullByDefault
    static void invalidServerSoftware(Logger logger) {
        logger.log(Level.SEVERE, TOP);
        logger.log(Level.SEVERE, PREFIX + "\u26d4 Unsupported Server Software");
        logger.log(Level.SEVERE, MID);
        logger.log(Level.SEVERE, PREFIX + "CraftBukkit is no longer supported.");
        logger.log(Level.SEVERE, PREFIX + "Please use Paper, Purpur, or a compatible fork.");
        logger.log(Level.SEVERE, PREFIX + "(We recommend Paper for best performance)");
        logger.log(Level.SEVERE, BOTTOM);
    }

    @ParametersAreNonnullByDefault
    static void oldJavaVersion(Logger logger, int recommendedJavaVersion) {
        int javaVersion = NumberUtils.getJavaVersion();

        logger.log(Level.WARNING, TOP);
        logger.log(Level.WARNING, PREFIX + "\u26a0 Outdated Java Version");
        logger.log(Level.WARNING, MID);
        logger.log(Level.WARNING, PREFIX + "Current: Java {0}", javaVersion);
        logger.log(Level.WARNING, PREFIX + "Recommended: Java {0}", recommendedJavaVersion);
        logger.log(Level.WARNING, PREFIX + "Please update Java before reporting issues.");
        logger.log(Level.WARNING, BOTTOM);
    }

}
