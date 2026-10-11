package io.github.thebusybiscuit.slimefun4.core.monitor.sinks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEvent;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Level;

public class DiscordWebhookSink implements NotificationSink {
    private final String webhookUrl;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Slimefun plugin;

    public DiscordWebhookSink(Slimefun plugin, String webhookUrl) {
        this.plugin = plugin;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void handle(@Nonnull MonitorEvent event) {
        if (event instanceof MonitorEvent.CoreUpdateAvailable coreUpdate) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "🔥 New SlimefunCore Release");
            embed.addProperty("color", 0xFF6B00);
            
            JsonArray fields = new JsonArray();
            addField(fields, "Current Build", coreUpdate.currentBuild(), true);
            addField(fields, "Latest Build", coreUpdate.latestBuild(), true);
            addField(fields, "Changelog", coreUpdate.changelogUrl(), false);
            addField(fields, "Modrinth", "https://modrinth.com/plugin/slimefuncore/versions", false);
            addField(fields, "GitHub", "https://github.com/SlimeCore-MC/Slimefun4Core", false);
            embed.add("fields", fields);
            
            sendEmbed(embed);
        } else if (event instanceof MonitorEvent.AddonUpdateAvailable addonUpdate) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "🧩 Addon Update Available");
            embed.addProperty("color", 0x3498DB);
            
            JsonArray fields = new JsonArray();
            addField(fields, "Addon Name", addonUpdate.addonName(), true);
            addField(fields, "Version Update", addonUpdate.oldVersion() + " -> " + addonUpdate.newVersion(), true);
            addField(fields, "Build Number", "#" + addonUpdate.buildNumber(), true);
            embed.add("fields", fields);
            
            sendEmbed(embed);
        } else if (event instanceof MonitorEvent.AddonNewlyDiscovered addonNew) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "🧩 New Addon Discovered");
            embed.addProperty("color", 0x2ECC71);
            
            JsonArray fields = new JsonArray();
            addField(fields, "Name", addonNew.addonName(), true);
            addField(fields, "Version", addonNew.version(), true);
            addField(fields, "Compatibility", addonNew.compatibleMcVersion(), true);
            embed.add("fields", fields);
            
            sendEmbed(embed);
        } else if (event instanceof MonitorEvent.AddonManuallyDropped addonDrop) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "📦 New JAR Detected");
            embed.addProperty("color", 0xF1C40F);
            
            JsonArray fields = new JsonArray();
            addField(fields, "Filename", addonDrop.fileName(), true);
            addField(fields, "Recognized", addonDrop.recognized() ? "Yes" : "No", true);
            embed.add("fields", fields);
            
            sendEmbed(embed);
        }
        // Skip ReportSubmitted since it has a separate forum webhook.
    }

    private void sendEmbed(JsonObject embed) {
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        sendWebhook(payload);
    }

    private void sendWebhook(JsonObject payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Failed to send Discord webhook", x);
        }
    }

    private void addField(JsonArray fields, String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", inline);
        fields.add(field);
    }
}
