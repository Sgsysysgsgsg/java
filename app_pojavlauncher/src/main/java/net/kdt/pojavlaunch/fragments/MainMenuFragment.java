package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.AutoSetupManager;
import net.kdt.pojavlaunch.JVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.fragments.SearchModFragment;
import net.kdt.pojavlaunch.utils.FileUtils;

import git.artdeell.mojo.R;

import java.io.File;
import java.util.ArrayList;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mAutoSetupButton = view.findViewById(R.id.auto_setup_button);
        Button mModsButton = view.findViewById(R.id.mods_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);
        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mAutoSetupButton.setOnClickListener(v -> openAutoSetup(v.getContext()));

        mModsButton.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));

        mEditProfileButton.setOnClickListener(v ->
                mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v ->
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        mOpenDirectoryButton.setOnClickListener(v ->
                openGameDirectory(v.getContext()));
    }

    private void openAutoSetup(Context context) {
        final String[] typeValues = {"release", "snapshot", "old_beta", "old_alpha"};
        final int[] typeLabels = {
                R.string.mcl_setting_veroption_release,
                R.string.mcl_setting_veroption_snapshot,
                R.string.mcl_setting_veroption_oldbeta,
                R.string.mcl_setting_veroption_oldalpha
        };

        String[] labels = new String[typeLabels.length];
        for (int i = 0; i < typeLabels.length; i++) {
            labels[i] = getString(typeLabels[i]);
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.auto_setup_title)
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < typeValues.length) {
                        openAutoSetupVersions(context, typeValues[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openAutoSetupVersions(Context context, String type) {
        JVersionList versions = (JVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (versions == null || versions.versions == null || versions.versions.length == 0) {
            Toast.makeText(context, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();

        for (JVersionList.Version version : versions.versions) {
            if (version == null || version.id == null || !type.equals(version.type)) continue;

            ids.add(version.id);

            String javaText = "";
            if (version.javaVersion != null && version.javaVersion.majorVersion > 0) {
                javaText = " • Java " + version.javaVersion.majorVersion;
            }
            labels.add(version.id + javaText);
        }

        if (ids.isEmpty()) {
            Toast.makeText(
                    context,
                    "No " + type + " versions are available.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.auto_setup_choose_version)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < ids.size()) {
                        startAutoSetup(context, ids.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startAutoSetup(Context context, String minecraftVersion) {
        final EditText nameInput = new EditText(context);
        nameInput.setSingleLine(true);
        nameInput.setHint("Example: Survival Touch");
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        nameInput.setPadding(padding, 8, padding, 8);

        AlertDialog nameDialog = new AlertDialog.Builder(context)
                .setTitle("Name your profile")
                .setMessage("Choose a name for this Minecraft setup.")
                .setView(nameInput)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Install", null)
                .create();
        nameDialog.setOnShowListener(dialog -> {
            AlertDialog alert = (AlertDialog) dialog;
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String profileName = nameInput.getText().toString().trim();
                if (profileName.isEmpty()) {
                    nameInput.setError("Enter a profile name");
                    return;
                }
                alert.dismiss();
                runAutoSetup(context, minecraftVersion, profileName);
            });
        });
        nameDialog.show();
    }

    private void runAutoSetup(Context context, String minecraftVersion, String profileName) {
        ProgressDialog progress = new ProgressDialog(context);
        progress.setTitle("Auto Setup • " + minecraftVersion);
        progress.setMessage("Preparing…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        AutoSetupManager.setup(context, minecraftVersion, profileName, new AutoSetupManager.Callback() {
            @Override
            public void onStage(String stage) {
                if (progress.isShowing()) progress.setMessage(stage);
            }

            @Override
            public void onSuccess(String profile, String version, String loaderVersion, int modCount) {
                if (progress.isShowing()) progress.dismiss();
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                Toast.makeText(
                        context,
                        "Installed " + version + " • " + profile + " • " + modCount + " mods",
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(Throwable error) {
                if (progress.isShowing()) progress.dismiss();
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                new AlertDialog.Builder(context)
                        .setTitle(R.string.global_error)
                        .setMessage(getString(R.string.auto_setup_failed, message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    private void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if(instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if(FileUtils.ensureDirectorySilently(gameDirectory)) {
            openPath(context, gameDirectory, false);
        } else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
    }
}
