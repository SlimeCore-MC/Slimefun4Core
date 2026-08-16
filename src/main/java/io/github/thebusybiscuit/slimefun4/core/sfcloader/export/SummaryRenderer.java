package io.github.thebusybiscuit.slimefun4.core.sfcloader.export;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.CapturedFile;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.LoaderManifest;
import java.util.Map;
import javax.annotation.Nonnull;

public class SummaryRenderer {

    private SummaryRenderer() {}

    @Nonnull
    public static String render(@Nonnull LoaderManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  SFCLoader — Settings Export Summary\n");
        sb.append("═══════════════════════════════════════════════════\n\n");

        LoaderManifest.ServerInfo server = manifest.getSourceServer();
        if (server != null) {
            sb.append(String.format("  Server:    %s\n", server.name()));
            sb.append(String.format("  Platform:  %s\n", server.platform()));
            sb.append(String.format("  Minecraft: %s\n", server.minecraftVersion()));
            sb.append(String.format("  Slimefun:  %s\n", server.slimefunVersion()));
        }
        sb.append(String.format("  Exported:  %s\n\n", manifest.getCreatedAt()));

        sb.append("─── Captured Files ────────────────────────────────\n");
        if (manifest.getFiles() != null) {
            for (CapturedFile file : manifest.getFiles()) {
                if ("captured".equals(file.status())) {
                    sb.append(String.format("  ✓ %-30s (%d bytes)\n", file.path(), file.bytes()));
                } else {
                    sb.append(String.format("  ✗ %-30s (not present)\n", file.path()));
                }
            }
        }
        sb.append("\n");

        sb.append("─── Settings Preview ──────────────────────────────\n");
        if (manifest.getCapturedSettingsPreview() != null) {
            for (Map.Entry<String, Object> entry : manifest.getCapturedSettingsPreview().entrySet()) {
                sb.append(String.format("  %-19s %s\n", entry.getKey() + ":", entry.getValue()));
            }
        }
        sb.append("═══════════════════════════════════════════════════\n");

        return sb.toString();
    }
}
