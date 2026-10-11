package io.github.thebusybiscuit.slimefun4.core.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;

public final class McLogsUploader {
    private static final String MCLOGS_API = "https://api.mclo.gs/1/log";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private McLogsUploader() {}

    public static @Nullable String uploadServerLog() {
        try {
            Path logFile = Path.of("logs", "latest.log");
            if (!Files.exists(logFile)) return null;
            String content = Files.readString(logFile, StandardCharsets.UTF_8);
            // Truncate to 10MB to avoid API limits
            if (content.length() > 10_000_000) {
                content = content.substring(content.length() - 10_000_000);
            }
            String body = "content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(MCLOGS_API))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("success") && json.get("success").getAsBoolean() && json.has("url")) {
                    return json.get("url").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
