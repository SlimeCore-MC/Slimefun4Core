package io.github.thebusybiscuit.slimefun4.core.sfcloader.export;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.CapturedFile;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.LoaderManifest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

public class ManifestBuilder {

    @Nonnull
    public LoaderManifest build(@Nonnull Plugin plugin, @Nonnull List<CapturedFile> files) {
        LoaderManifest manifest = new LoaderManifest();
        manifest.setCreatedAt(Instant.now().toString());
        manifest.setCreatedBy("SFCLoader 1.0.0 (SlimefunCore)");

        Server server = plugin.getServer();
        String sfVersion = plugin.getDescription().getVersion();
        
        LoaderManifest.ServerInfo serverInfo = new LoaderManifest.ServerInfo(
            server.getName(),
            "",
            server.getBukkitVersion(),
            server.getName(),
            sfVersion,
            server.getOnlineMode()
        );
        
        manifest.setSourceServer(serverInfo);
        manifest.setFiles(files);

        Map<String, Object> preview = new HashMap<>();
        preview.put("max_players", server.getMaxPlayers());
        manifest.setCapturedSettingsPreview(preview);

        return manifest;
    }
}
