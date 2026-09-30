package net.kdt.pojavlaunch.prefs;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SCALE_FACTOR;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import com.kdt.CustomSeekbar;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.interfaces.SimpleSeekBarListener;

/**
 * Small in-game settings panel.
 *
 * The launcher intentionally keeps the on-screen controls minimal: TAB is the
 * only built-in touch button. Gameplay input is handled by Minecraft itself.
 */
public abstract class QuickSettingSideDialog extends com.kdt.SideDialogView {

    private SharedPreferences.Editor mEditor;
    private CustomSeekbar mResolutionBar;
    private TextView mResolutionText;
    private float mOriginalResolution;

    public QuickSettingSideDialog(Context context, android.view.ViewGroup parent) {
        super(context, parent, R.layout.dialog_quick_setting);
        setTitle(R.string.quick_setting_title);
        setupCancelButton();
    }

    @Override
    protected void onInflate() {
        mResolutionBar = mDialogContent.findViewById(R.id.editResolution_seekbar);
        mResolutionText = mDialogContent.findViewById(R.id.editResolution_textView_percent);
        Tools.runOnUiThread(this::setupListeners);
    }

    private void setupListeners() {
        mEditor = LauncherPreferences.DEFAULT_PREF.edit();
        mOriginalResolution = PREF_SCALE_FACTOR;

        mResolutionBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_SCALE_FACTOR = progress / 100f;
            mEditor.putInt("resolutionRatio", progress);
            setSeekTextPercent(mResolutionText, progress);
            onResolutionChanged();
        });

        mResolutionBar.setProgress((int) (mOriginalResolution * 100f));
        setSeekTextPercent(mResolutionText, mResolutionBar.getProgress());
    }

    private static void setSeekTextPercent(TextView target, int value) {
        target.setText(target.getContext().getString(R.string.percent_format, value));
    }

    private void setupCancelButton() {
        setStartButtonListener(android.R.string.cancel, v -> cancel());
        setEndButtonListener(android.R.string.ok, v -> {
            if (mEditor != null) mEditor.apply();
            disappear(true);
        });
    }

    public void cancel() {
        if (isDisplaying()) {
            PREF_SCALE_FACTOR = mOriginalResolution;
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putInt("resolutionRatio", (int) (mOriginalResolution * 100f))
                    .apply();
            onResolutionChanged();
        }
        disappear(true);
    }

    @Override
    protected void onDestroy() {
        if (mResolutionBar != null) {
            mResolutionBar.setOnSeekBarChangeListener(null);
        }
    }

    /** Called when the resolution is changed. */
    public abstract void onResolutionChanged();
}
