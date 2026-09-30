package net.kdt.pojavlaunch;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AutoSetupManager {
    private static final String TOUCH_CONTROLLER_PROJECT = "touchcontroller";

    private AutoSetupManager() {}

    public interface Callback {
        void onSuccess(String version, String loaderVersion, int modCount);
        void onError(Throwable error);
    }

    public static void setup(Context context, String minecraftVersion, Callback callback) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String fabricVersion = installFabric(minecraftVersion);
                installVanillaMetadata(minecraftVersion);

                Instance instance = Instances.createInstance(new InstanceSetter() {
                    @Override
                    public void setInstanceProperties(Instance target) {
                        target.sharedData = false;
                        target.versionId = fabricVersion;
                    }
                }, "EYAD-Touch-" + minecraftVersion);

                Instances.setSelectedInstance(instance);
                int installed = installTouchController(instance, minecraftVersion);

                Tools.runOnUiThread(() -> callback.onSuccess(
                        minecraftVersion, fabricVersion, installed
                ));
            } catch (Throwable error) {
                Tools.runOnUiThread(() -> callback.onError(error));
            }
        });
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

    private static void installVanillaMetadata(String minecraftVersion) throws IOException {
        JVersionList jVersionList = Tools.GLOBAL_GSON.fromJson(
                ApiHandler.getRaw("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"),
                JVersionList.class
        );
        if (jVersionList == null || jVersionList.versions == null) {
            throw new IOException("Unable to load Minecraft version metadata");
        }

        for (JVersionList.Version version : jVersionList.versions) {
            if (!minecraftVersion.equals(version.id) || version.url == null) continue;

            String json = ApiHandler.getRaw(version.url);
            if (json == null || json.isEmpty()) {
                throw new IOException("Unable to download Minecraft " + minecraftVersion + " metadata");
            }

            File versionDir = new File(Tools.DIR_HOME_VERSION, minecraftVersion);
            FileUtils.ensureDirectory(versionDir);
            Tools.write(new File(versionDir, minecraftVersion + ".json"), json);
            return;
        }

        throw new IOException("Minecraft version " + minecraftVersion + " was not found");
    }

    private static int installTouchController(Instance instance, String minecraftVersion) throws IOException {
        File modsDir = new File(instance.getGameDirectory(), "mods");
        FileUtils.ensureDirectory(modsDir);

        JsonArray controllerVersions = getProjectVersions(
                TOUCH_CONTROLLER_PROJECT, minecraftVersion, "fabric"
        );
        if (controllerVersions == null || controllerVersions.size() == 0) {
            throw new IOException("TouchController does not support Minecraft " + minecraftVersion);
        }

        JsonObject controller = controllerVersions.get(0).getAsJsonObject();
        List<JsonObject> required = new ArrayList<>();
        required.add(controller);

        JsonArray dependencies = controller.getAsJsonArray("dependencies");
        Set<String> visitedProjects = new HashSet<>();
        visitedProjects.add(TOUCH_CONTROLLER_PROJECT);

        if (dependencies != null) {
            for (int i = 0; i < dependencies.size(); i++) {
                JsonObject dependency = dependencies.get(i).getAsJsonObject();
                String type = dependency.has("dependency_type")
                        ? dependency.get("dependency_type").getAsString()
                        : "required";
                if (!"required".equals(type)) continue;

                String projectId = dependency.has("project_id") && !dependency.get("project_id").isJsonNull()
                        ? dependency.get("project_id").getAsString()
                        : null;
                String versionId = dependency.has("version_id") && !dependency.get("version_id").isJsonNull()
                        ? dependency.get("version_id").getAsString()
                        : null;

                JsonObject resolved = null;
                if (versionId != null && !versionId.isEmpty()) {
                    resolved = getVersion(versionId);
                } else if (projectId != null && visitedProjects.add(projectId)) {
                    JsonArray candidates = getProjectVersions(projectId, minecraftVersion, "fabric");
                    if (candidates != null && candidates.size() > 0) {
                        resolved = candidates.get(0).getAsJsonObject();
                    }
                }

                if (resolved != null) required.add(resolved);
            }
        }

        ArrayList<TaskMetadata> tasks = new ArrayList<>();
        Set<String> installedNames = new HashSet<>();

        for (JsonObject modVersion : required) {
            JsonArray files = modVersion.getAsJsonArray("files");
            if (files == null || files.size() == 0) continue;

            JsonObject selectedFile = files.get(0).getAsJsonObject();
            for (int i = 0; i < files.size(); i++) {
                JsonObject candidate = files.get(i).getAsJsonObject();
                if (candidate.has("primary") && candidate.get("primary").getAsBoolean()) {
                    selectedFile = candidate;
                    break;
                }
            }

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
            throw new IOException("TouchController download files were not found");
        }

        try {
            new Downloader(com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK) {
                @Override
                public void download() throws IOException, InterruptedException {
                    runDownloads(tasks);
                }
            }.download();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Auto Setup was interrupted", e);
        }

        return tasks.size();
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
}
