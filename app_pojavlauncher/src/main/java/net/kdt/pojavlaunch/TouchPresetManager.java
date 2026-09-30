package net.kdt.pojavlaunch;

import android.content.Context;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

/** Built-in touch preset helper. Kept for compatibility with existing profiles. */
public final class TouchPresetManager {
    private TouchPresetManager() {}

    public static void applyPreset(Context context, String preset) throws Exception {
        String asset;
        switch (preset) {
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

        // This overload expects an output DIRECTORY, not the final file path.
        Tools.copyAssetFile(context, asset, parent, true);

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
                applyPreset(context, "joystick_tap");
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
