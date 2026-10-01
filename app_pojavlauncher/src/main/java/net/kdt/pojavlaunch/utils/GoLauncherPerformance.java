package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Build;

import net.kdt.pojavlaunch.Tools;

/**
 * Centralized, one-shot device performance classification for GoLauncher.
 *
 * The result is intentionally cheap to calculate and contains no background
 * polling. Launcher and game code can use the same tier so performance
 * decisions stay consistent across the app.
 */
public final class GoLauncherPerformance {
    public enum Tier {
        LOW,
        BALANCED,
        HIGH
    }

    private GoLauncherPerformance() {}

    public static Tier getTier(Context context) {
        int ramMb = Tools.getTotalDeviceMemory(context);
        int cores = Runtime.getRuntime().availableProcessors();
        int gles = 2;

        try {
            gles = GpuUtils.getGlInfo().glesMajorVersion;
        } catch (Throwable ignored) {
            // Keep the conservative GLES 2 baseline.
        }

        if (ramMb <= 4096 || cores <= 4 || gles < 3) {
            return Tier.LOW;
        }

        if (ramMb >= 8192 && cores >= 8 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return Tier.HIGH;
        }

        return Tier.BALANCED;
    }

    public static boolean isHigh(Context context) {
        return getTier(context) == Tier.HIGH;
    }

    public static boolean isLow(Context context) {
        return getTier(context) == Tier.LOW;
    }

    public static int defaultRenderDistance(Context context) {
        switch (getTier(context)) {
            case HIGH: return 10;
            case BALANCED: return 7;
            default: return 5;
        }
    }

    public static int defaultSimulationDistance(Context context) {
        switch (getTier(context)) {
            case HIGH: return 8;
            case BALANCED: return 6;
            default: return 4;
        }
    }

    public static int defaultMaxFps(Context context) {
        switch (getTier(context)) {
            case HIGH: return 60;
            case BALANCED: return 45;
            default: return 30;
        }
    }

    public static int defaultMipmapLevels(Context context) {
        return getTier(context) == Tier.LOW ? 1 : 2;
    }

    public static int defaultResolutionSide(Context context) {
        return getTier(context) == Tier.HIGH ? 1080 : 720;
    }
}
