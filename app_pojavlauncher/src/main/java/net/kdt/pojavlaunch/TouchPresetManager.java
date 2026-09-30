package net.kdt.pojavlaunch;

import android.content.Context;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

/** Lightweight built-in Bedrock-style touch presets. */
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
        Tools.copyAssetFile(
                context,
                asset,
                new File(Tools.CTRLMAP_PATH, "default.json").getAbsolutePath(),
                true
        );
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString("defaultCtrl", Tools.CTRLDEF_FILE)
                .putString("touch_control_preset", preset)
                .apply();
        LauncherPreferences.PREF_DEFAULTCTRL_PATH = Tools.CTRLDEF_FILE;
    }

    public static void ensureDefault(Context context) {
        try {
            File target = new File(Tools.CTRLDEF_FILE);
            if (!target.exists()) applyPreset(context, "joystick_tap");
        } catch (Exception ignored) { }
    }
}
