package net.kdt.pojavlaunch;

import android.content.Context;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Built-in touch preset helper. Kept for compatibility with existing profiles. */
public final class TouchPresetManager {
    private TouchPresetManager() {}

    public static void applyPreset(Context context, String preset) throws Exception {
        String asset;
        switch (preset) {
            case "tab_only": asset = "tab_only.json"; break;
            case "dpad_tap": asset = "bedrock_dpad_tap.json"; break;
            case "joystick_aim": asset = "bedrock_joystick_aim.json"; break;
            case "joystick_tap":
            default: asset = "bedrock_joystick_tap.json"; break;
        }

        File target = new File(Tools.CTRLDEF_FILE);

        // Older builds accidentally created default.json as a directory.
        // Remove that broken directory before writing the real file.
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) {
                        deleteTree(child);
                    } else {
                        child.delete();
                    }
                }
            }
            target.delete();
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new java.io.IOException("Unable to create controlmap directory");
        }

        try (InputStream input = context.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }

        LauncherPreferences.DEFAULT_PREF.edit()
                .putString("defaultCtrl", Tools.CTRLDEF_FILE)
                .putString("touch_control_preset", preset)
                .apply();
        LauncherPreferences.PREF_DEFAULTCTRL_PATH = Tools.CTRLDEF_FILE;
    }

    public static void ensureDefault(Context context) {
        try {
            File target = new File(Tools.CTRLDEF_FILE);
            if (target.isDirectory() || !target.exists()) {
                applyPreset(context, "tab_only");
            }
        } catch (Exception ignored) { }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteTree(child);
                else child.delete();
            }
        }
        file.delete();
    }
}
