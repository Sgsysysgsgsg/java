package net.kdt.pojavlaunch;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.util.Locale;

/** Lightweight device/version compatibility check used before launching Minecraft. */
public final class DeviceCompatibility {
    public enum Tier { DEAD, LOW, BALANCED, HIGH }

    public static final class Result {
        public final Tier tier;
        public final long ramMb;
        public final int cores;
        public final int javaMajor;
        public final boolean arm64;
        public final boolean likelyPlayable;
        public final String reason;

        Result(Tier tier, long ramMb, int cores, int javaMajor, boolean arm64,
               boolean likelyPlayable, String reason) {
            this.tier = tier;
            this.ramMb = ramMb;
            this.cores = cores;
            this.javaMajor = javaMajor;
            this.arm64 = arm64;
            this.likelyPlayable = likelyPlayable;
            this.reason = reason;
        }
    }

    private DeviceCompatibility() {}

    public static Result check(Context context, String versionId, int requiredJava) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(info);

        long ramMb = Math.max(512L, info.totalMem / (1024L * 1024L));
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equalsIgnoreCase(abi)) {
                arm64 = true;
                break;
            }
        }

        int javaMajor = requiredJava > 0 ? requiredJava : inferJavaMajor(versionId);
        String v = versionId == null ? "" : versionId.toLowerCase(Locale.ROOT);

        int score = 0;
        if (ramMb >= 6000) score += 3;
        else if (ramMb >= 3500) score += 2;
        else if (ramMb >= 2200) score += 1;

        if (cores >= 8) score += 3;
        else if (cores >= 6) score += 2;
        else if (cores >= 4) score += 1;

        if (arm64) score += 1;
        if (javaMajor >= 21 && ramMb < 3000) score -= 2;
        if (javaMajor >= 17 && ramMb < 2200) score -= 2;
        if (isVeryModern(v) && ramMb < 3500) score -= 1;

        Tier tier;
        if (ramMb < 1800 || cores <= 2 || score <= -1) tier = Tier.DEAD;
        else if (ramMb < 2600 || cores <= 4 || score <= 2) tier = Tier.LOW;
        else if (score <= 5) tier = Tier.BALANCED;
        else tier = Tier.HIGH;

        boolean playable = tier != Tier.DEAD;
        String reason;
        switch (tier) {
            case DEAD:
                reason = "Very limited hardware: use an older Minecraft version, low render distance and lightweight mods.";
                break;
            case LOW:
                reason = "Low-end hardware: prefer older versions, Fabric and performance mods.";
                break;
            case BALANCED:
                reason = "Balanced profile: modern versions should work with sensible settings.";
                break;
            default:
                reason = "High-performance profile: modern versions and modpacks are more suitable.";
                break;
        }

        return new Result(tier, ramMb, cores, javaMajor, arm64, playable, reason);
    }

    private static boolean isVeryModern(String version) {
        try {
            String[] p = version.split("\\.");
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return major >= 1 && minor >= 20;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static int inferJavaMajor(String version) {
        try {
            String[] p = version.split("\\.");
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (major == 1 && minor >= 20) return minor >= 5 ? 21 : 17;
            if (major == 1 && minor >= 17) return 16;
        } catch (Exception ignored) {}
        return 8;
    }

    public static String summary(Result r) {
        return String.format(Locale.ROOT, "%s • %d MB RAM • %d cores • Java %d",
                r.tier.name(), r.ramMb, r.cores, r.javaMajor);
    }
}
