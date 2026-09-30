package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
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
        JVersionList versions = (JVersionList) net.kdt.pojavlaunch.extra.ExtraCore.getValue(
                ExtraConstants.RELEASE_TABLE
        );

        if (versions == null || versions.versions == null || versions.versions.length == 0) {
            Toast.makeText(context, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        int limit = Math.min(80, versions.versions.length);

        for (int i = 0; i < limit; i++) {
            JVersionList.Version version = versions.versions[i];
            if (version == null || version.id == null) continue;
            ids.add(version.id);
            String type = version.type == null ? "Minecraft" : version.type;
            labels.add(version.id + " • " + type);
        }

        String[] labelArray = labels.toArray(new String[0]);
        new AlertDialog.Builder(context)
                .setTitle(R.string.auto_setup_choose_version)
                .setItems(labelArray, (dialog, which) -> {
                    if (which < 0 || which >= ids.size()) return;
                    startAutoSetup(context, ids.get(which));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startAutoSetup(Context context, String minecraftVersion) {
        ProgressDialog progress = new ProgressDialog(context);
        progress.setTitle(R.string.auto_setup_title);
        progress.setMessage(R.string.auto_setup_working);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        AutoSetupManager.setup(context, minecraftVersion, new AutoSetupManager.Callback() {
            @Override
            public void onSuccess(String version, String loaderVersion, int modCount) {
                progress.dismiss();
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                Toast.makeText(
                        context,
                        getString(R.string.auto_setup_success, version),
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(Throwable error) {
                progress.dismiss();
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
