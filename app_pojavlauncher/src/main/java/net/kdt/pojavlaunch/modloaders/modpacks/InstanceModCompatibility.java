package net.kdt.pojavlaunch.modloaders.modpacks;

import net.kdt.pojavlaunch.instances.Instance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstanceModCompatibility {
    private static final Pattern MC_VERSION_SUFFIX =
            Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)$");

    private InstanceModCompatibility() {}

    public static String getMinecraftVersion(Instance instance) {
        if (instance == null) return null;
        if (instance.minecraftVersion != null && !instance.minecraftVersion.trim().isEmpty()) {
            return instance.minecraftVersion.trim();
        }
        String id = instance.versionId;
        if (id == null || id.isEmpty()) return null;
        Matcher matcher = MC_VERSION_SUFFIX.matcher(id);
        if (matcher.find()) return matcher.group(1);
        return id;
    }

    public static String getLoader(Instance instance) {
        if (instance == null) return null;
        if (instance.modLoader != null && !instance.modLoader.trim().isEmpty()) {
            return instance.modLoader.trim().toLowerCase();
        }
        String id = instance.versionId == null ? "" : instance.versionId.toLowerCase();
        if (id.startsWith("fabric-loader-")) return "fabric";
        if (id.startsWith("quilt-loader-")) return "quilt";
        if (id.startsWith("neoforge-")) return "neoforge";
        if (id.startsWith("forge-")) return "forge";
        return null;
    }

    public static boolean matches(String minecraftVersion, String loader, String candidateVersion,
                                  String candidateLoader) {
        if (minecraftVersion == null || candidateVersion == null
                || !minecraftVersion.equals(candidateVersion)) return false;
        if (loader == null || loader.isEmpty() || candidateLoader == null || candidateLoader.isEmpty()) {
            return true;
        }
        return loader.equalsIgnoreCase(candidateLoader);
    }

    public static int curseForgeLoaderType(String loader) {
        if (loader == null) return 0;
        switch (loader.toLowerCase()) {
            case "forge": return 1;
            case "fabric": return 4;
            case "quilt": return 5;
            case "neoforge": return 6;
            default: return 0;
        }
    }
}
