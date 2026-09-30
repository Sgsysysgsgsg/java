package net.kdt.pojavlaunch.modloaders.modpacks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgelikeUtils;
import net.kdt.pojavlaunch.modloaders.Lwjgl3ifyUtils;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modloaders.modpacks.InstanceModCompatibility;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.FabriclikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.ForgelikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.LoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.Lwjgl3ifyLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

public class ModrinthApi implements ModpackApi{
    private final ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        // Fixes an issue where the offset being equal or greater than total_hits is ignored
        if (modrinthSearchResult != null && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }


        // Build the facets filters
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facetString = new StringBuilder();
        facetString.append("[");
        facetString.append(String.format("[\"project_type:%s\"]", searchFilters.isModpack ? "modpack" : "mod"));
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            facetString.append(String.format(",[\"versions:%s\"]", searchFilters.mcVersion));
        if(searchFilters.loader != null && !searchFilters.loader.isEmpty())
            facetString.append(String.format(",[\"loaders:%s\"]", searchFilters.loader));
        facetString.append("]");
        params.put("facets", facetString.toString());
        params.put("query", searchFilters.name);
        params.put("limit", 50);
        params.put("index", "relevance");
        if(modrinthSearchResult != null)
            params.put("offset", modrinthSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray responseHits = response.getAsJsonArray("hits");
        if(responseHits == null) return null;

        ModItem[] items = new ModItem[responseHits.size()];
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            items[i] = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    hit.get("project_type").getAsString().equals("modpack"),
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    hit.get("icon_url").getAsString()
            );
        }
        if(modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.previousOffset += responseHits.size();
        modrinthSearchResult.results = items;
        modrinthSearchResult.totalResultCount = response.get("total_hits").getAsInt();
        return modrinthSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), JsonArray.class);
        if(response == null) return null;

        Instance selected = Instances.loadSelectedInstance();
        String minecraftVersion = InstanceModCompatibility.getMinecraftVersion(selected);
        String loader = InstanceModCompatibility.getLoader(selected);

        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> mcNames = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> hashes = new ArrayList<>();

        for (int i = 0; i < response.size(); ++i) {
            JsonObject version = response.get(i).getAsJsonObject();
            JsonArray gameVersions = version.getAsJsonArray("game_versions");
            boolean gameMatch = minecraftVersion == null;
            if (gameVersions != null && minecraftVersion != null) {
                for (int v = 0; v < gameVersions.size(); v++) {
                    if (minecraftVersion.equals(gameVersions.get(v).getAsString())) {
                        gameMatch = true;
                        break;
                    }
                }
            }
            if (!gameMatch) continue;

            boolean loaderMatch = loader == null || loader.isEmpty();
            JsonArray loaders = version.getAsJsonArray("loaders");
            if (loaders != null && loader != null && !loader.isEmpty()) {
                for (int l = 0; l < loaders.size(); l++) {
                    if (loader.equalsIgnoreCase(loaders.get(l).getAsString())) {
                        loaderMatch = true;
                        break;
                    }
                }
            }
            if (!loaderMatch) continue;

            JsonArray files = version.getAsJsonArray("files");
            if (files == null || files.size() == 0) continue;
            JsonObject file = files.get(0).getAsJsonObject();
            names.add(version.get("name").getAsString());
            mcNames.add(minecraftVersion != null ? minecraftVersion :
                    gameVersions != null && gameVersions.size() > 0 ? gameVersions.get(0).getAsString() : "");
            urls.add(file.get("url").getAsString());

            JsonObject hashesMap = file.getAsJsonObject("hashes");
            JsonElement sha1 = hashesMap == null ? null : hashesMap.get("sha1");
            hashes.add(sha1 == null || sha1.isJsonNull() ? null : sha1.getAsString());
        }

        return new ModDetail(
                item,
                names.toArray(new String[0]),
                mcNames.toArray(new String[0]),
                urls.toArray(new String[0]),
                hashes.toArray(new String[0])
        );
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        if (!modDetail.isModpack) {
            installSingleMod(modDetail, selectedVersion);
            return null;
        }
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installMrpack);
    }

    private void installSingleMod(ModDetail modDetail, int selectedVersion) throws IOException {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) throw new IOException("No Minecraft instance selected");
        File modsDir = new File(instance.getGameDirectory(), "mods");
        if (!modsDir.exists() && !modsDir.mkdirs()) throw new IOException("Unable to create mods directory");

        URL url = new URL(modDetail.versionUrls[selectedVersion]);
        String path = url.getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (fileName.isEmpty() || !fileName.toLowerCase().endsWith(".jar")) {
            fileName = modDetail.title.replaceAll("[^a-zA-Z0-9._-]", "_") + ".jar";
        }

        java.net.URLConnection connection = url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        long size = connection.getContentLengthLong();

        ArrayList<TaskMetadata> tasks = new ArrayList<>(1);
        tasks.add(new TaskMetadata(new File(modsDir, fileName), url, Math.max(size, 0), modDetail.versionHashes[selectedVersion], DownloadMirror.DOWNLOAD_CLASS_NONE));
        try {
            new Downloader(ProgressLayout.INSTALL_MODPACK) {
                public void download() throws IOException, InterruptedException {
                    runDownloads(tasks);
                }
            }.download();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Mod download interrupted", e);
        }
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installMrpack);
    }

    private static LoaderInstaller createInfo(ModrinthIndex modrinthIndex, File installDestination) throws IOException {
        if(modrinthIndex == null) return null;
        Map<String, String> dependencies = modrinthIndex.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if(mcVersion == null) return null;
        String modLoaderVersion;
        if((modLoaderVersion = dependencies.get("forge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("fabric-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("quilt-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.QUILT_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("neoforge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, mcVersion, modLoaderVersion);
        } else if(dependencies.size() == 1) {
            // "Vanilla" pack. Possibly GT:NH, let's try to detect lwjgl3ify
            File lwjgl3ifyJar = Lwjgl3ifyUtils.detectLwjgl3ifyJar(installDestination);
            if(lwjgl3ifyJar != null) return new Lwjgl3ifyLoaderInstaller(lwjgl3ifyJar);
        }

        return null;
    }

    private LoaderInstaller installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)){
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json")),
                    ModrinthIndex.class);
            try {
                new ModrinthDownloader().startDownloads(modrinthIndex.files, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createInfo(modrinthIndex, instanceDestination);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }

    static class ModrinthDownloader extends Downloader {
        public ModrinthDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        protected void startDownloads(ModrinthIndex.ModrinthIndexFile[] indexFiles, File instanceDestination) throws IOException, InterruptedException {
            String absoluteInstancePath = instanceDestination.getAbsolutePath();
            ArrayList<TaskMetadata> taskMetadatas = new ArrayList<>(indexFiles.length);
            for(ModrinthIndex.ModrinthIndexFile file : indexFiles) {
                File targetPath = new File(instanceDestination, file.path);
                if(!targetPath.getAbsolutePath().startsWith(absoluteInstancePath)) throw new IOException("Bad path!");
                FileUtils.ensureParentDirectory(targetPath);
                taskMetadatas.add(new TaskMetadata(
                        targetPath, new URL(file.downloads[0]), // TODO source selection
                        file.fileSize, file.hashes.sha1,
                        DownloadMirror.DOWNLOAD_CLASS_NONE
                ));
            }
            runDownloads(taskMetadatas);
        }
    }
}
