package net.kdt.pojavlaunch;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.InstanceSetter;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
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
        private static final String TOUCH_CONTROLLER_PROJECT = "touchcontroller";
    private static final String MOD_MENU_PROJECT = "modmenu";

    private AutoSetupManager() {}

    public interface Callback {
        void onStage(String stage);
        void onSuccess(String version, String loaderVersion, int modCount);
        void onError(Throwable error);
    }

    public static void setup(Context context, String minecraftVersion, String profileName, Callback callback) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                if (net.kdt.pojavlaunch.authenticator.accounts.Accounts.getCurrent() == null) {
                    throw new IOException("Please add and select a Microsoft or Offline account before Auto Setup.");
                }
                if (ProgressKeeper.hasOngoingTasks()) {
                    throw new IOException("Another download is already running. Please wait for it to finish.");
                }

                notifyStage(callback, "Installing Fabric Loader…");
                String fabricVersion = installFabric(minecraftVersion);

                String safeProfileName = profileName == null ? "" : profileName.trim();
                if (safeProfileName.isEmpty()) safeProfileName = "EYAD-Touch-" + minecraftVersion;

                final String finalProfileName = safeProfileName;
                Instance instance = Instances.createInstance(new InstanceSetter() {
                    @Override
                    public void setInstanceProperties(Instance target) {
                        target.sharedData = false;
                        target.versionId = fabricVersion;
                    }
                }, finalProfileName);

                // Always use our known-good default Bedrock-style control layout.
                instance.controlLayout = null;
                instance.maybeWrite();
                Instances.setSelectedInstance(instance);

                // Verify the selection is immediately readable before starting downloads.
                Instance selectedNow = Instances.loadSelectedInstance();
                if (selectedNow == null ||
                        !instance.mInstanceRoot.equals(selectedNow.mInstanceRoot)) {
                    throw new IOException("The new Minecraft instance could not be selected. Please try Auto Setup again.");
                }

                notifyStage(callback, "Downloading Minecraft files…");
                downloadGame(context, fabricVersion);

                notifyStage(callback, "Installing TouchController + Mod Menu…");
                int installed = installTouchControllerAndModMenu(instance, minecraftVersion);

                final String installedProfileName = finalProfileName;
                Tools.runOnUiThread(() -> callback.onSuccess(
                        installedProfileName, minecraftVersion, installed
                ));
            } catch (Throwable error) {
                Tools.runOnUiThread(() -> callback.onError(error));
            }
        });
    }

    private static void notifyStage(Callback callback, String stage) {
        Tools.runOnUiThread(() -> callback.onStage(stage));
    }

    private static String installFabric(String minecraftVersion) throws IOException {
        FabricVersion[] versions = FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(minecraftVersion);
        if (versions == null || versions.length == 0) {
            throw new IOException("Fabric is not available for Minecraft " + minecraftVersion);
        }

        String selected = null;
        for (FabricVersion version : versions) {
            if (version.stable) {
                selected = version.version;
                break;
            }
        }
        if (selected == null) selected = versions[0].version;

        String installedId = FabriclikeUtils.FABRIC_UTILS.install(minecraftVersion, selected);
        if (installedId == null) {
            throw new IOException("Failed to install Fabric " + selected);
        }
        return installedId;
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

    private static int installTouchControllerAndModMenu(Instance instance, String minecraftVersion)
            throws IOException, InterruptedException {
        File modsDir = new File(instance.getGameDirectory(), "mods");
        FileUtils.ensureDirectory(modsDir);

        List<JsonObject> roots = new ArrayList<>();

        JsonObject controller = getBestProjectVersion(
                TOUCH_CONTROLLER_PROJECT, minecraftVersion, "fabric");
        if (controller == null) {
            throw new IOException("TouchController does not support Minecraft " + minecraftVersion
                    + " on Fabric.");
        }
        roots.add(controller);

        JsonObject modMenu = getBestProjectVersion(MOD_MENU_PROJECT, minecraftVersion, "fabric");
        if (modMenu != null) roots.add(modMenu);

        List<JsonObject> resolved = resolveRequiredDependencies(roots, minecraftVersion, "fabric");
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
            throw new IOException("No compatible TouchController/Mod Menu files were found.");
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
