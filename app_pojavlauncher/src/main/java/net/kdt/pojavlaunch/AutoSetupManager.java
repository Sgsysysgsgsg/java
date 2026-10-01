package net.kdt.pojavlaunch;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.InstanceInstaller;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgelikeUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.MoJsonDownloader;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AutoSetupManager {
        private static final String MOD_MENU_PROJECT = "modmenu";
    private static final String TOUCH_CONTROLLER_PROJECT = "touchcontroller";

    private AutoSetupManager() {}

    public interface Callback {
        void onStage(String stage);
        void onSuccess(String version, String loaderVersion, int modCount);
        void onError(Throwable error);
    }

    public static void setup(Context context, String minecraftVersion, String profileName,
                              String setupType, String loader, Callback callback) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                if (net.kdt.pojavlaunch.authenticator.accounts.Accounts.getCurrent() == null) {
                    throw new IOException("Please add and select a Microsoft or Offline account before Auto Setup.");
                }
                if (ProgressKeeper.hasOngoingTasks()) {
                    throw new IOException("Another download is already running. Please wait for it to finish.");
                }

                String safeProfileName = profileName == null ? "" : profileName.trim();
                if (safeProfileName.isEmpty()) safeProfileName = "EYAD-" + minecraftVersion;
                final String finalProfileName = safeProfileName;
                final String selectedLoader = loader == null ? "vanilla" : loader.toLowerCase();
                final boolean modded = "modded".equalsIgnoreCase(setupType);

                if (!modded) {
                    notifyStage(callback, "Preparing Vanilla Minecraft…");
                    Instance instance = createInstance(minecraftVersion, minecraftVersion, "vanilla", finalProfileName, null);
                    notifyStage(callback, "Downloading Minecraft files…");
                    downloadGame(context, minecraftVersion);
                    verifySelectedInstance(instance);
                    Tools.runOnUiThread(() -> callback.onSuccess(finalProfileName, minecraftVersion, 0));
                    return;
                }

                if ("forge".equals(selectedLoader) || "neoforge".equals(selectedLoader)) {
                    notifyStage(callback, "Preparing " + selectedLoader + " installer…");
                    Instance instance = installForgeLike(minecraftVersion, selectedLoader, finalProfileName);
                    verifySelectedInstance(instance);
                    // Forge/NeoForge installers perform their own game/library installation.
                    // Start the existing launcher installer flow rather than duplicating it here.
                    if (instance.installer != null) instance.installer.start();
                    notifyStage(callback, selectedLoader + " installer started…");
                    Tools.runOnUiThread(() -> callback.onSuccess(finalProfileName, minecraftVersion, 0));
                    return;
                }

                if (!"fabric".equals(selectedLoader) && !"quilt".equals(selectedLoader)) {
                    throw new IOException("Unsupported mod loader: " + selectedLoader);
                }

                notifyStage(callback, "Installing " + selectedLoader + " Loader…");
                String loaderVersion = installFabricLike(minecraftVersion, selectedLoader);
                Instance instance = createInstance(loaderVersion, minecraftVersion, selectedLoader, finalProfileName, null);
                verifySelectedInstance(instance);

                notifyStage(callback, "Downloading Minecraft files…");
                downloadGame(context, loaderVersion);

                notifyStage(callback, "Checking TouchController compatibility…");
                int installed = installTouchControllerAndModMenu(instance, minecraftVersion, selectedLoader);

                boolean touchControllerInstalled = hasTouchControllerJar(instance);
                if (touchControllerInstalled) {
                    notifyStage(callback, "TouchController is available for " + minecraftVersion + " — enabling it…");
                } else {
                    notifyStage(callback, "TouchController is not available for " + minecraftVersion
                            + " — using your GoLauncher Custom Controls instead.");
                }

                Tools.runOnUiThread(() -> callback.onSuccess(finalProfileName, minecraftVersion, installed));
            } catch (Throwable error) {
                Tools.runOnUiThread(() -> callback.onError(error));
            }
        });
    }

    /** Backward-compatible entry point: creates the normal Fabric modded profile. */
    public static void setup(Context context, String minecraftVersion, String profileName, Callback callback) {
        setup(context, minecraftVersion, profileName, "modded", "fabric", callback);
    }

    private static Instance createInstance(String versionId, String minecraftVersion, String loader,
                                           String profileName, InstanceInstaller installer) throws IOException {
        Instance instance = Instances.createInstance(target -> {
            target.sharedData = false;
            target.versionId = versionId;
            target.minecraftVersion = minecraftVersion;
            target.modLoader = loader;
            target.installer = installer;
        }, profileName);
        instance.controlLayout = null;
        instance.maybeWrite();
        Instances.setSelectedInstance(instance);
        return instance;
    }

    private static void verifySelectedInstance(Instance instance) throws IOException {
        Instance selectedNow = Instances.loadSelectedInstance();
        if (selectedNow == null ||
                !instance.getGameDirectory().getAbsolutePath().equals(selectedNow.getGameDirectory().getAbsolutePath())) {
            throw new IOException("The new Minecraft instance could not be selected. Please try Auto Setup again.");
        }
    }

    private static String installFabricLike(String minecraftVersion, String loader) throws IOException {
        FabriclikeUtils utils = "quilt".equalsIgnoreCase(loader)
                ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
        FabricVersion[] versions = utils.downloadLoaderVersions(minecraftVersion);
        if (versions == null || versions.length == 0) {
            throw new IOException(utils.getName() + " is not available for Minecraft " + minecraftVersion);
        }
        String selected = null;
        for (FabricVersion version : versions) {
            if (version.stable) {
                selected = version.version;
                break;
            }
        }
        if (selected == null) selected = versions[0].version;
        String installedId = utils.install(minecraftVersion, selected);
        if (installedId == null) throw new IOException("Failed to install " + utils.getName() + " " + selected);
        return installedId;
    }

    private static Instance installForgeLike(String minecraftVersion, String loader, String profileName)
            throws IOException {
        ForgelikeUtils utils = "neoforge".equalsIgnoreCase(loader)
                ? ForgelikeUtils.NEOFORGE_UTILS : ForgelikeUtils.FORGE_UTILS;
        List<String> versions = utils.downloadVersions();
        if (versions == null || versions.isEmpty()) {
            throw new IOException(utils.getName() + " versions are unavailable right now.");
        }
        String selected = null;
        for (String version : versions) {
            if (!utils.shouldSkipVersion(version) && minecraftVersion.equals(utils.processVersionString(version))) {
                selected = version;
                break;
            }
        }
        if (selected == null) {
            throw new IOException("No compatible " + utils.getName() + " version was found for Minecraft " + minecraftVersion);
        }
        InstanceInstaller installer = utils.createInstaller(selected);
        if (installer == null) throw new IOException("Failed to prepare " + utils.getName() + " installer.");
        return createInstance(selected, minecraftVersion, loader, profileName, installer);
    }

    private static void notifyStage(Callback callback, String stage) {
        Tools.runOnUiThread(() -> callback.onStage(stage));
    }

    private static void downloadGame(Context context, String fabricVersion)
            throws IOException, InterruptedException {
        MoJsonDownloader.prepareSubstitutionMap(context.getAssets());

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];

        new MoJsonDownloader().start(context.getAssets(), null, fabricVersion,
                new net.kdt.pojavlaunch.tasks.MoJsonExtras.DoneListener() {
                    @Override
                    public void onDownloadDone(File[] classpath) {
                        latch.countDown();
                    }

                    @Override
                    public void onDownloadFailed(Throwable throwable) {
                        failure[0] = throwable;
                        latch.countDown();
                    }
                });

        if (!latch.await(45, TimeUnit.MINUTES)) {
            throw new IOException("Minecraft files are taking too long to download. Check your connection and try again.");
        }
        if (failure[0] != null) {
            if (failure[0] instanceof IOException) throw (IOException) failure[0];
            throw new IOException("Minecraft download failed", failure[0]);
        }
    }

    private static boolean hasTouchControllerJar(Instance instance) {
        File modsDir = new File(instance.getGameDirectory(), "mods");
        File[] files = modsDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".jar")
                    && (lower.contains("touchcontroller") || lower.contains("touch-controller"));
        });
        return files != null && files.length > 0;
    }

    private static int installTouchControllerAndModMenu(Instance instance, String minecraftVersion, String loader)
            throws IOException, InterruptedException {
        File modsDir = new File(instance.getGameDirectory(), "mods");
        FileUtils.ensureDirectory(modsDir);

        List<JsonObject> roots = new ArrayList<>();

        // TouchController and Mod Menu are optional integrations.
        // GoLauncher has a native fallback control layer, so an unavailable
        // mod must never make Auto Setup fail after Minecraft was downloaded.
        JsonObject touchController = getBestProjectVersion(TOUCH_CONTROLLER_PROJECT, minecraftVersion, loader);
        if (touchController != null) {
            roots.add(touchController);
        }

        JsonObject modMenu = getBestProjectVersion(MOD_MENU_PROJECT, minecraftVersion, loader);
        if (modMenu != null) {
            roots.add(modMenu);
        }

        if (roots.isEmpty()) {
            return 0;
        }

        List<JsonObject> resolved = resolveRequiredDependencies(roots, minecraftVersion, loader);
        return downloadModFiles(resolved, modsDir);
    }

    private static List<JsonObject> resolveRequiredDependencies(List<JsonObject> roots,
                                                                  String minecraftVersion,
                                                                  String loader)
            throws IOException {
        List<JsonObject> result = new ArrayList<>();
        Queue<JsonObject> queue = new ArrayDeque<>(roots);
        Set<String> visitedVersions = new HashSet<>();

        while (!queue.isEmpty()) {
            JsonObject current = queue.remove();
            if (current == null || !current.has("id")) continue;

            String versionId = current.get("id").getAsString();
            if (!visitedVersions.add(versionId)) continue;
            result.add(current);

            JsonArray dependencies = current.getAsJsonArray("dependencies");
            if (dependencies == null) continue;

            for (int i = 0; i < dependencies.size(); i++) {
                JsonObject dependency = dependencies.get(i).getAsJsonObject();
                String type = dependency.has("dependency_type")
                        ? dependency.get("dependency_type").getAsString()
                        : "required";
                if (!"required".equals(type)) continue;

                JsonObject resolved = null;
                String fixedVersionId = getNullableString(dependency, "version_id");
                String projectId = getNullableString(dependency, "project_id");

                if (fixedVersionId != null) {
                    resolved = getVersion(fixedVersionId);
                    if (!supportsExact(resolved, minecraftVersion, loader)) {
                        throw new IOException("Dependency " + fixedVersionId
                                + " is not compatible with Minecraft " + minecraftVersion);
                    }
                } else if (projectId != null) {
                    resolved = getBestProjectVersion(projectId, minecraftVersion, loader);
                }

                if (resolved != null) queue.add(resolved);
            }
        }
        return result;
    }

    private static int downloadModFiles(List<JsonObject> modVersions, File modsDir)
            throws IOException, InterruptedException {
        ArrayList<TaskMetadata> tasks = new ArrayList<>();
        Set<String> installedNames = new HashSet<>();

        for (JsonObject modVersion : modVersions) {
            JsonArray files = modVersion.getAsJsonArray("files");
            if (files == null || files.size() == 0) continue;

            JsonObject selectedFile = null;
            for (int i = 0; i < files.size(); i++) {
                JsonObject candidate = files.get(i).getAsJsonObject();
                if (candidate.has("primary") && candidate.get("primary").getAsBoolean()) {
                    selectedFile = candidate;
                    break;
                }
                if (selectedFile == null) selectedFile = candidate;
            }
            if (selectedFile == null || !selectedFile.has("url")) continue;

            String url = selectedFile.get("url").getAsString();
            String fileName = selectedFile.has("filename")
                    ? selectedFile.get("filename").getAsString()
                    : "mod-" + modVersion.get("id").getAsString() + ".jar";
            if (!fileName.endsWith(".jar") || !installedNames.add(fileName)) continue;

            JsonObject hashes = selectedFile.getAsJsonObject("hashes");
            String sha1 = hashes != null && hashes.has("sha1")
                    ? hashes.get("sha1").getAsString()
                    : null;
            long size = selectedFile.has("size") ? selectedFile.get("size").getAsLong() : 0;

            tasks.add(new TaskMetadata(
                    new File(modsDir, fileName),
                    new URL(url),
                    size,
                    sha1,
                    DownloadMirror.DOWNLOAD_CLASS_NONE
            ));
        }

        if (tasks.isEmpty()) {
            throw new IOException("No compatible mod files were found.");
        }

        new AutoDownloader().download(tasks);
        return tasks.size();
    }

    private static JsonObject getBestProjectVersion(String project, String minecraftVersion, String loader)
            throws IOException {
        JsonArray versions = getProjectVersions(project, minecraftVersion, loader);
        if (versions == null || versions.size() == 0) return null;

        JsonObject fallback = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject version = versions.get(i).getAsJsonObject();
            if (!supportsExact(version, minecraftVersion, loader)) continue;
            if (fallback == null) fallback = version;
            String type = getNullableString(version, "version_type");
            if ("release".equals(type)) return version;
        }
        return fallback;
    }

    private static boolean supportsExact(JsonObject version, String minecraftVersion, String loader) {
        if (version == null) return false;

        JsonArray gameVersions = version.getAsJsonArray("game_versions");
        boolean gameMatch = false;
        if (gameVersions != null) {
            for (int i = 0; i < gameVersions.size(); i++) {
                if (minecraftVersion.equals(gameVersions.get(i).getAsString())) {
                    gameMatch = true;
                    break;
                }
            }
        }

        JsonArray loaders = version.getAsJsonArray("loaders");
        boolean loaderMatch = false;
        if (loaders != null) {
            for (int i = 0; i < loaders.size(); i++) {
                if (loader.equalsIgnoreCase(loaders.get(i).getAsString())) {
                    loaderMatch = true;
                    break;
                }
            }
        }
        return gameMatch && loaderMatch;
    }

    private static String getNullableString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        String value = object.get(key).getAsString();
        return value == null || value.isEmpty() ? null : value;
    }

    private static JsonArray getProjectVersions(String project, String minecraftVersion, String loader)
            throws IOException {
        String gameVersions = URLEncoder.encode("[\\\"" + minecraftVersion + "\\\"]", "UTF-8");
        String loaders = URLEncoder.encode("[\\\"" + loader + "\\\"]", "UTF-8");
        String url = "https://api.modrinth.com/v2/project/" + project
                + "/version?game_versions=" + gameVersions
                + "&loaders=" + loaders
                + "&include_changelog=false";

        String raw = ApiHandler.getRaw(url);
        if (raw == null || raw.isEmpty()) {
            throw new IOException("Modrinth returned no versions for " + project);
        }
        return Tools.GLOBAL_GSON.fromJson(raw, JsonArray.class);
    }

    private static JsonObject getVersion(String versionId) throws IOException {
        String raw = ApiHandler.getRaw("https://api.modrinth.com/v2/version/" + versionId);
        if (raw == null || raw.isEmpty()) {
            throw new IOException("Unable to resolve dependency version " + versionId);
        }
        return Tools.GLOBAL_GSON.fromJson(raw, JsonObject.class);
    }

    private static final class AutoDownloader extends Downloader {
        AutoDownloader() {
            super(com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK);
        }

        void download(ArrayList<TaskMetadata> tasks) throws IOException, InterruptedException {
            try {
                runDownloads(tasks);
            } finally {
                // Downloader reports progress but does not close this progress key itself.
                // Always end it so the launcher cannot remain stuck on "Downloading files".
                ProgressKeeper.submitProgress(
                        com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK,
                        -1,
                        -1
                );
            }
        }
    }
}
