package net.kdt.pojavlaunch.modloaders.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public boolean isModpack;
    public String name;
    @Nullable public String mcVersion;
    /** Restrict results to the loader used by the selected instance. */
    @Nullable public String loader;

}
