package net.kdt.pojavlaunch.game;


import static net.kdt.pojavlaunch.Tools.dialogForceClose;
import static net.kdt.pojavlaunch.game.platform.Platform.PLATFORM;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SUSTAINED_PERFORMANCE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_VIRTUAL_MOUSE_START;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.os.IBinder;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.CallbackBridge;
import net.kdt.pojavlaunch.game.renderer.GameRenderer;
import net.kdt.pojavlaunch.utils.GpuUtils;
import net.kdt.pojavlaunch.utils.KeycodeUtils;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.TouchPresetManager;
import net.kdt.pojavlaunch.authenticator.accounts.Accounts;
import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.game.platform.Platform;
import net.kdt.pojavlaunch.game.platform.backend.DummyBackend;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.QuickSettingSideDialog;
import net.kdt.pojavlaunch.services.GameService;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.authenticator.accounts.Account;
import net.kdt.pojavlaunch.utils.jre.GameRunner;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

import git.artdeell.mojo.R;

public class GameActivity extends BaseActivity implements ControlButtonMenuListener, EditorExitable, ServiceConnection {
    public static final String INTENT_LAUNCH_VERSION = "intent_version";
    public static final String INTENT_LAUNCH_CLASSPATH = "intent_classpath";

    public static TouchCharInput touchCharInput;
    private GameView launcherGLView;
    private static WeakReference<GameCursorView> weakCursor;
    private LoggerView loggerView;
    private DrawerLayout drawerLayout;
    private ListView navDrawer;
    private View mDrawerPullButton;
    private GyroControl mGyroControl = null;
    private ControlLayout mControlLayout;
    private HotbarView mHotbarView;
    private View mLoadingScreen;
    private GameRenderer mGameRenderer;

    Instance instance;
    Account account;

    private ArrayAdapter<String> gameActionArrayAdapter;
    private AdapterView.OnItemClickListener gameActionClickListener;
    public ArrayAdapter<String> ingameControlsEditorArrayAdapter;
    public AdapterView.OnItemClickListener ingameControlsEditorListener;
    private GameService.LocalBinder mServiceBinder;

    private QuickSettingSideDialog mQuickSettingSideDialog;
    private boolean mInventoryUtilityOpen = false;
    private boolean mTouchControllerKeyboardVisible = false;
    TouchControllerBridge mTouchControllerBridge;
    public static int mForcedPanningHeight = 0;
    public static int mImeHeight = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = Instances.loadSelectedInstance();
        account = Accounts.getCurrent();
        if(instance == null) {
            Toast.makeText(this, R.string.instance_dir_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mGameRenderer = new GameRenderer(instance.getLaunchRenderer());

        if(GpuUtils.getGlInfo().isAdreno() && !PREF_ZINK_PREFER_SYSTEM_DRIVER) {
            mGameRenderer.overrideVulkanDriver();
        }

        AsyncAssetManager.extractDefaultSettings(this, instance.getGameDirectory());
        MCOptionUtils.load(instance.getGameDirectory().getAbsolutePath());

        Intent gameServiceIntent = new Intent(this, GameService.class);
        // Start the service a bit early
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout(R.layout.activity_basemain);

        Platform.initialize(this, launcherGLView);

        mGyroControl = new GyroControl(this);

        // Enabling this on TextureView results in a broken white result
        if(PREF_USE_ALTERNATE_SURFACE) getWindow().setBackgroundDrawable(null);
        else getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // Set the sustained performance mode for available APIs
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            getWindow().setSustainedPerformanceMode(PREF_SUSTAINED_PERFORMANCE);

        // This is required on Android 10 for the insets listener
        // https://issuetracker.google.com/issues/266331465
        boolean androidCompat = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q;
        if(androidCompat)
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Make keyboard pan the activity so the user sees what they're typing
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, insets) -> {
            if(launcherGLView.mSurface == null)
                return insets;
            ViewPropertyAnimator animSurface = launcherGLView.mSurface.animate()
                    .setDuration(100);
            ViewPropertyAnimator animCursor = launcherGLView.mCursorView.animate()
                    .setDuration(100);
            if(!insets.isVisible(WindowInsetsCompat.Type.ime())){
                animSurface.translationY(0).start();
                animCursor.translationY(0).start();
                mImeHeight = 0;
                if(androidCompat) {
                    // AndroidX keeps SystemUI visible for some reason after IME session
                    view.postDelayed(() -> {
                        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
                    }, 150);
                }
                return insets;
            }
            if(mForcedPanningHeight == 0 && !LauncherPreferences.PREF_KEYBOARD_AUTOPANNING)
                return insets;
            mImeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int translationY;
            // Autopanning (if keyboardPan wasn't clicked)
            if(mForcedPanningHeight == 0) {
                translationY = Tools.getTranslationFromCursorY(
                        (int)(Platform.cursorY * launcherGLView.getCursorRatioY() + 100),
                        launcherGLView.getHeight(),
                        mImeHeight,
                        0
                );
            } else
                translationY = mForcedPanningHeight == -1 ? mImeHeight : Math.clamp(mImeHeight - mForcedPanningHeight, 0, mImeHeight);
            animSurface.translationY(-translationY).start();
            animCursor.translationY(-translationY).start();
            return insets;
        });

        ingameControlsEditorArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, getResources().getStringArray(R.array.menu_customcontrol));
        ingameControlsEditorListener = (parent, view, position, id) -> {
            switch(position) {
                case 0: mControlLayout.addControlButton(new ControlData("New")); break;
                case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
                case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
                case 3: mControlLayout.openLoadDialog(); break;
                case 4: mControlLayout.openSaveDialog(this); break;
                case 5: mControlLayout.openSetDefaultDialog(); break;
                case 6: mControlLayout.openExitDialog(this);
            }
        };

        // Recompute the gui scale when options are changed
        MCOptionUtils.MCOptionListener optionListener = MCOptionUtils::getMcScale;
        MCOptionUtils.addMCOptionListener(optionListener);
        mControlLayout.setModifiable(false);

        // Set the activity for the executor. Must do this here, or else Tools.showErrorRemote() may not
        // execute the correct method
        ContextExecutor.setActivity(this);
        //Now, attach to the service. The game will only start when this happens, to make sure that we know the right state.
        bindService(gameServiceIntent, this, 0);
    }

    protected void initLayout(int resId) {
        setContentView(resId);
        bindValues();

        // TouchController needs a launcher-side IPC bridge on Android. Create it
        // before Minecraft starts so the mod can connect as soon as its JVM loads.
        if (hasTouchControllerInstalled()) {
            try {
                mTouchControllerBridge = new TouchControllerBridge(this);
                Os.setenv("TOUCH_CONTROLLER_PROXY_SOCKET",
                        TouchControllerBridge.SOCKET_NAME, true);
                mTouchControllerBridge.start();
                Log.i("TouchControllerBridge", "Android proxy started: "
                        + TouchControllerBridge.SOCKET_NAME);
            } catch (Throwable bridgeError) {
                Log.e("TouchControllerBridge", "Failed to start Android proxy", bridgeError);
                mTouchControllerBridge = null;
            }
        }

        mControlLayout.setMenuListener(this);

        mDrawerPullButton.setOnClickListener(v -> onClickedMenu());
        // Disable edge-swipe opening of the launcher settings drawer.\n        // Minecraft touch gestures should never accidentally pull the settings over the game.\n        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        launcherGLView.mCursorView.setCursorScale(LauncherPreferences.PREF_MOUSESCALE);
        // The on-screen virtual mouse is disabled for the minimal touch layout.
        launcherGLView.mCursorView.setVisibility(View.GONE);
        weakCursor = new WeakReference<>(launcherGLView.mCursorView);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if(!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());

            Intent activityIntent = getIntent();
            Bundle extras = Objects.requireNonNull(activityIntent.getExtras());
            String version = extras.getString(INTENT_LAUNCH_VERSION);
            File[] classpath = (File[]) extras.getSerializable(INTENT_LAUNCH_CLASSPATH);

            activityIntent.removeExtra(INTENT_LAUNCH_VERSION);
            activityIntent.removeExtra(INTENT_LAUNCH_CLASSPATH);

            setIntent(activityIntent);

            setTitle(getString(R.string.app_short_name) + " (" + version + ")");

            // Minimal in-game menu. Gameplay controls are intentionally TAB-only.
            String[] inGameMenuItems = {"⚙ Settings", "⌨ Keyboard", "✕ Exit"};
            gameActionArrayAdapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, inGameMenuItems) {
                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, convertView, parent);
                    row.setTextColor(Color.WHITE);
                    row.setTextSize(16);
                    row.setPadding(24, 18, 24, 18);
                    return row;
                }
            };
            gameActionClickListener = (parent, view, position, id) -> {
                switch(position) {
                    case 0:
                        openQuickSettings();
                        break;
                    case 1:
                        switchKeyboardState(false);
                        break;
                    case 2:
                        dialogForceClose(GameActivity.this);
                        break;
                }
                drawerLayout.closeDrawers();
            };
            navDrawer.setAdapter(gameActionArrayAdapter);
            navDrawer.setOnItemClickListener(gameActionClickListener);
            drawerLayout.closeDrawers();

            launcherGLView.setSurfaceReadyListener(() -> {
                try {
                    Tools.runOnUiThread(() -> { if(PREF_VIRTUAL_MOUSE_START) launcherGLView.mCursorView.setVisibility(View.VISIBLE); });
                    if(version == null || classpath == null) {
                        Tools.runOnUiThread(()->{
                            Toast.makeText(this, R.string.main_please_restart, Toast.LENGTH_LONG).show();
                            finish();
                        });
                        return;
                    }
                    runCraft(version, classpath);
                }catch (Throwable e){
                    Tools.showErrorRemote(e);
                }
            });
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private void loadControls() {
        // The user's Custom Controls layout is always the source of truth.
        // Built-in controls are only created when no control layout exists yet.
        // TouchController is an optional movement/aim integration; GoLauncher only
        // adds the small INV/BACK/Keyboard actions when that integration is present.
        try {
            boolean touchControllerInstalled = hasTouchControllerInstalled();
            File controlFile = new File(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            String savedPreset = LauncherPreferences.DEFAULT_PREF.getString("touch_control_preset", "");

            // TouchController owns movement/aim. If the current layout is one of
            // GoLauncher's built-in presets, migrate it to the minimal utility layer.
            // A user-created Custom Controls layout is never overwritten.
            if (!controlFile.isFile()) {
                TouchPresetManager.applyPreset(this,
                        touchControllerInstalled ? "utility" : "eyad_bedrock");
            } else if (touchControllerInstalled
                    && ("eyad_bedrock".equals(savedPreset) || "utility".equals(savedPreset))) {
                TouchPresetManager.applyPreset(this, "utility");
            }

            mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);

            if (touchControllerInstalled) {
                ensureTouchControllerUtilityControls();
            }

            mControlLayout.setControlVisible(true);
            updateUtilityControls();

            Log.i("TouchControls", "Control source: "
                    + (touchControllerInstalled
                    ? "Custom Controls + TouchController + GoLauncher actions"
                    : "Custom Controls + GoLauncher built-in fallback"));
        } catch (Exception error) {
            Log.e("TouchControls", "Failed to load controls", error);
            mControlLayout.setControlVisible(false);
        }
        mDrawerPullButton.setVisibility(View.GONE);
    }

    /**
     * Keep the three launcher actions inside the user's actual Custom Controls
     * layout. They can therefore be moved/resized/edited from the normal editor.
     * Existing controls are never replaced.
     */
    private void ensureTouchControllerUtilityControls() {
        CustomControls layout = mControlLayout.getLayout();
        if (layout == null || layout.mControlDataList == null) return;

        boolean changed = false;

        // Remove controls that are redundant when TouchController owns gameplay.
        // Only remove the known GoLauncher built-in gameplay/menu controls.
        String[] redundantControls = {"JUMP", "USE", "ATTACK", "SNEAK", "SPRINT", "F5",
                "Move", "AIM", "TAB", "Chat", "Command"};
        for (String name : redundantControls) {
            for (int i = layout.mControlDataList.size() - 1; i >= 0; i--) {
                if (name.equals(layout.mControlDataList.get(i).name)) {
                    layout.mControlDataList.remove(i);
                    changed = true;
                }
            }
        }

        if (!hasControlNamed(layout, "INV")) {
            mControlLayout.addControlButton(new ControlData(
                    "INV", new int[]{KeyEvent.KEYCODE_E}, "${right} - ${margin} * 2 - 116",
                    "${margin}", 58, 42, false));
            changed = true;
        }

        if (!hasControlNamed(layout, "BACK")) {
            mControlLayout.addControlButton(new ControlData(
                    "BACK", new int[]{KeyEvent.KEYCODE_ESCAPE}, "${right} - ${margin} * 2 - 58",
                    "${margin}", 58, 42, false));
            changed = true;
        }

        if (!hasControlNamed(layout, "Keyboard")) {
            mControlLayout.addControlButton(new ControlData(
                    "Keyboard", new int[]{ControlData.SPECIALBTN_KEYBOARD},
                    "${margin}", "${margin}", 82, 42, false));
            changed = true;
        }

        // Persist the additions so CustomControlsActivity can edit them later.
        if (changed) {
            try {
                mControlLayout.saveLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString("defaultCtrl", LauncherPreferences.PREF_DEFAULTCTRL_PATH)
                        .apply();
            } catch (Exception error) {
                Log.w("TouchControls", "Could not persist utility actions", error);
            }
        }
    }

    private boolean hasControlNamed(CustomControls layout, String name) {
        for (ControlData control : layout.mControlDataList) {
            if (name.equals(control.name)) return true;
        }
        return false;
    }

    /** Detect the optional TouchController integration without touching Minecraft APIs. */
    private boolean hasTouchControllerInstalled() {
        try {
            File modsDir = new File(instance.getGameDirectory(), "mods");
            File[] files = modsDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                return lower.endsWith(".jar")
                        && (lower.contains("touchcontroller") || lower.contains("touch-controller"));
            });
            return files != null && files.length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Update the small INV/BACK/Keyboard overlay according to the current Minecraft screen. */
    public void updateUtilityControls() {
        if (mControlLayout == null) return;
        boolean inGame = Platform.isGrabbing();

        // TouchController handles movement/aim. GoLauncher only exposes:
        // gameplay -> INV
        // GUI/menu -> BACK
        // text input -> Keyboard
        mControlLayout.setNamedControlVisible("INV", inGame);
        mControlLayout.setNamedControlVisible("BACK", !inGame);
        mControlLayout.setNamedControlVisible("Keyboard",
                !inGame && mTouchControllerKeyboardVisible);
    }

    /** Called by the utility controls/GameView when the inventory is opened or closed. */
    public void setInventoryUtilityOpen(boolean open) {
        mInventoryUtilityOpen = open;
        updateUtilityControls();
    }

    /** Called by the TouchController proxy when Minecraft requests text input. */
    public void updateTouchControllerKeyboard(boolean visible) {
        mTouchControllerKeyboardVisible = visible;
        updateUtilityControls();
    }

    @Override
    public void onAttachedToWindow() {
        // Post to get the correct display dimensions after layout.
        mControlLayout.post(()->{
            Tools.getDisplayMetrics(this);
            loadControls();
        });
    }

    /** Boilerplate binding */
    private void bindValues(){
        mControlLayout = findViewById(R.id.main_control_layout);
        launcherGLView = findViewById(R.id.main_game_render_view);
        drawerLayout = findViewById(R.id.main_drawer_options);
        navDrawer = findViewById(R.id.main_navigation_view);
        loggerView = findViewById(R.id.mainLoggerView);
        touchCharInput = findViewById(R.id.mainTouchCharInput);
        mDrawerPullButton = findViewById(R.id.drawer_button);
        mHotbarView = findViewById(R.id.hotbar_view);
        mLoadingScreen = findViewById(R.id.main_loading_screen);
    }

    @Override
    public void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        if(PREF_ENABLE_GYRO) mGyroControl.enable();
        PLATFORM.setHovered(true);
    }

    @Override
    protected void onPause() {
        ContextExecutor.clearActivity();
        mGyroControl.disable();
        // Avoid going through the JNI each time.
        if (Platform.isGrabbing()){
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
        }
        if(mQuickSettingSideDialog != null) {
            mQuickSettingSideDialog.cancel();
        }
        PLATFORM.setHovered(false);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PLATFORM.setVisible(true);
    }

    @Override
    protected void onStop() {
        PLATFORM.setVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mTouchControllerBridge != null) {
            try {
                mTouchControllerBridge.close();
            } catch (Throwable ignored) {
            }
            mTouchControllerBridge = null;
        }
        mTouchControllerKeyboardVisible = false;
        super.onDestroy();
        ContextExecutor.clearActivity();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if(mGyroControl != null) mGyroControl.updateOrientation();
        // Layout resize is practically guaranteed on a configuration change, and `onConfigurationChanged`
        // does not implicitly start a layout. So, request a layout and expect the screen dimensions to be valid after the]
        // post.
        if(mControlLayout == null) return;
        mControlLayout.requestLayout();
        mControlLayout.post(()->{
            // Child of mControlLayout, so refreshing size here is correct
            launcherGLView.refreshSize();
            mControlLayout.refreshControlButtonPositions();
        });
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if(mLoadingScreen != null && !(PLATFORM instanceof DummyBackend)) hideLoadingScreen();
        if(launcherGLView != null)  // Useful when backing out of the app
            Tools.MAIN_HANDLER.postDelayed(() -> launcherGLView.refreshSize(), 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            // Reload PREF_DEFAULTCTRL_PATH
            // If the storage root got unmounted/unreadable we won't be able to load the file anyway,
            // and MissingStorageActivity will be started.
            if(!Tools.checkStorageRoot(this)) return;
            LauncherPreferences.loadPreferences(getApplicationContext());
            try {
                mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void runCraft(String versionId, File[] classpath) throws Throwable {
        Logger.appendToLog("--------- Starting game with Launcher Debug!");
        Tools.printLauncherInfo(versionId, instance.getLaunchArgs(), mGameRenderer.getCurrentRenderer(), this);
        JREUtils.redirectAndPrintJRELog();
        GameRunner.launchGame(this, account, instance, versionId, classpath, mGameRenderer);
        //Note that we actually stall in the above function, even if the game crashes. But let's be safe.
        Tools.runOnUiThread(()-> mServiceBinder.isActive = false);
    }

    private void dialogSendCustomKey() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle(R.string.control_customkey);
        dialog.setItems(KeycodeUtils.generateKeyName(), (dInterface, position) -> KeycodeUtils.execKeyIndex(position));
        dialog.show();
    }

    boolean isInEditor;
    private void openCustomControls() {
        if(ingameControlsEditorListener == null || ingameControlsEditorArrayAdapter == null) return;

        mControlLayout.setModifiable(true);
        navDrawer.setAdapter(ingameControlsEditorArrayAdapter);
        navDrawer.setOnItemClickListener(ingameControlsEditorListener);
        mDrawerPullButton.setVisibility(View.VISIBLE);
        isInEditor = true;
    }

    private void openLogOutput() {
        loggerView.setVisibility(View.VISIBLE);
    }

    private void openQuickSettings() {
        if(mQuickSettingSideDialog == null) {
            mQuickSettingSideDialog = new QuickSettingSideDialog(this, mControlLayout) {
                @Override
                public void onResolutionChanged() {
                    launcherGLView.refreshSize();
                    mHotbarView.onResolutionChanged();
                }


            };
        }
        mQuickSettingSideDialog.appear(true);
    }

    public static void toggleMouse(Context ctx) {
        // Kept as a compatibility no-op for legacy imported control maps.
        GameCursorView cursorView = Tools.getWeakReference(weakCursor);
        if (cursorView != null) cursorView.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(navDrawer)) {
            drawerLayout.closeDrawer(navDrawer);
            return;
        }

        if (isInEditor) {
            if (mControlLayout != null) {
                mControlLayout.askToExit(this);
            }
            return;
        }

        // First back opens Minecraft's pause/menu via ESC. If the game is already
        // in a non-grabbing/menu state, back exits the game normally.
        if (!Platform.isGrabbing()) {
            dialogForceClose(this);
        } else {
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if(isInEditor) {
            if(event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if(event.getAction() == KeyEvent.ACTION_DOWN) mControlLayout.askToExit(this);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handleEvent;
        if(!(handleEvent = launcherGLView.processKeyEvent(event))) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !touchCharInput.isEnabled()) {
                if(event.getAction() != KeyEvent.ACTION_UP) return true; // We eat it anyway
                if (!Platform.isGrabbing()) dialogForceClose(this);
                else CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
                return true;
            }
        }
        return handleEvent;
    }

    public static void switchKeyboardState(boolean panning) {
        if(touchCharInput != null) {
            touchCharInput.switchKeyboardState();
            GameActivity.mForcedPanningHeight = panning ? -1 : 0;
        }
    }
    public static void toggleKeyboardState(boolean state, int panningHeight) {
        if(touchCharInput != null) {
            touchCharInput.setKeyboardState(state);
            GameActivity.mForcedPanningHeight = panningHeight;
        }
    }

    public void hideLoadingScreen(){
        if(mLoadingScreen == null) return;
        ((TextView) mLoadingScreen.findViewById(R.id.main_loading_screen_text)).setText(getString(R.string.loading_screen_booted, PLATFORM.backendName()));
        mLoadingScreen.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> {
                    ((ViewGroup) mLoadingScreen.getParent()).removeView(mLoadingScreen);
                    mLoadingScreen = null;
                })
                .start();
    }

    @Override
    public void onClickedMenu() {
        drawerLayout.openDrawer(navDrawer);
        navDrawer.requestLayout();
    }

    @Override
    public void exitEditor() {
        try {
            mControlLayout.loadLayout((CustomControls)null);
            mControlLayout.setModifiable(false);
            System.gc();
            mControlLayout.loadLayout(instance.getLaunchControls());
            mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        } catch (Exception e) {
            Tools.showError(this,e);
        }

        navDrawer.setAdapter(gameActionArrayAdapter);
        navDrawer.setOnItemClickListener(gameActionClickListener);
        isInEditor = false;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        GameService.LocalBinder localBinder = (GameService.LocalBinder) service;
        mServiceBinder = localBinder;
        launcherGLView.start(localBinder.isActive);
        localBinder.isActive = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    /*
     * Android 14 (or some devices, at least) seems to dispatch the the captured mouse events as trackball events
     * due to a bug(?) somewhere(????)
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean checkCaptureDispatchConditions(MotionEvent event) {
        int eventSource = event.getSource();
        // On my device, the mouse sends events as a relative mouse device.
        // Not comparing with == here because apparently `eventSource` is a mask that can
        // sometimes indicate multiple sources, like in the case of InputDevice.SOURCE_TOUCHPAD
        // (which is *also* an InputDevice.SOURCE_MOUSE when controlling a cursor)
        return (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) != 0 ||
                (eventSource & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if(Tools.isAndroid8OrHigher() && checkCaptureDispatchConditions(ev))
            return launcherGLView.dispatchCapturedPointerEvent(ev);
        else return super.dispatchTrackballEvent(ev);
    }
}
