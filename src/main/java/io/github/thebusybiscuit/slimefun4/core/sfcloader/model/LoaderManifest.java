package io.github.thebusybiscuit.slimefun4.core.sfcloader.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LoaderManifest {
    private int formatVersion = 1;
    private String name;
    private String createdAt;
    private String createdBy;
    private ServerInfo sourceServer;
    private Map<String, Object> capturedSettingsPreview;
    private List<CapturedFile> files;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record ServerInfo(String name, String serverUuid, String minecraftVersion, String platform, String slimefunVersion, boolean onlineMode) {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    @Nullable
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@Nullable String createdAt) {
        this.createdAt = createdAt;
    }

    @Nullable
    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(@Nullable String createdBy) {
        this.createdBy = createdBy;
    }

    @Nullable
    public ServerInfo getSourceServer() {
        return sourceServer;
    }

    public void setSourceServer(@Nullable ServerInfo sourceServer) {
        this.sourceServer = sourceServer;
    }

    @Nullable
    public Map<String, Object> getCapturedSettingsPreview() {
        return capturedSettingsPreview;
    }

    public void setCapturedSettingsPreview(@Nullable Map<String, Object> capturedSettingsPreview) {
        this.capturedSettingsPreview = capturedSettingsPreview;
    }

    @Nullable
    public List<CapturedFile> getFiles() {
        return files;
    }

    public void setFiles(@Nullable List<CapturedFile> files) {
        this.files = files;
    }

    @Nonnull
    public String toJson() {
        return GSON.toJson(this);
    }

    @Nonnull
    public static LoaderManifest fromJson(@Nonnull String json) {
        return GSON.fromJson(json, LoaderManifest.class);
    }
}
