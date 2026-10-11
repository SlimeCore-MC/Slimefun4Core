package io.github.thebusybiscuit.slimefun4.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEvent;
import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEventBus;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Manages active report sessions per-player and captures chat input.
 *
 * <p>All operational constants are hardcoded — they are not user-editable.
 * Only the Discord report webhook URL is read from {@code config.yml}
 * at {@code monitor.discord.report-webhook-url}.
 */
public final class ReportSessionManager implements Listener {

    // ── Hardcoded backend constants (not user-editable) ──────────────────
    private static final int COOLDOWN_SECONDS = 120;
    private static final int GLOBAL_RATE_LIMIT = 10;
    private static final long RATE_LIMIT_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes
    private static final boolean FORUM_MODE = true;
    private static final boolean AUTO_UPLOAD_SERVER_LOG = true;

    // ── Hardcoded links (integrated inside SFC) ──────────────────────────
    public static final String REPORT_WEBHOOK_URL = "https://discord.com/api/webhooks/1549354616293171323/hUyrkVlEfSGkX-Kcc51DAUKM8C7HRCgRgH-AZ6vK7X5pP-9zMbdA1mI2avQMi85dPQzA";
    public static final String DISCORD_INVITE = "https://discord.gg/HHS78rVDy";
    static final String GITHUB_URL = "https://github.com/SlimeCore-MC/Slimefun4Core";
    static final String MODRINTH_URL = "https://modrinth.com/plugin/slimefuncore/versions";
    static final String ADDON_INDEX_URL = "https://github.com/SlimeCore-MC/SF4-Addons/tree/main/Addons";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

    private final Slimefun plugin;
    private final Map<UUID, ReportSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicInteger globalReportCount = new AtomicInteger(0);
    private volatile long globalWindowStart = System.currentTimeMillis();
    private MonitorEventBus eventBus;

    public ReportSessionManager(Slimefun plugin) {
        this.plugin = plugin;
    }

    public void setEventBus(MonitorEventBus bus) {
        this.eventBus = bus;
    }

    public boolean startSession(Player player, ReportCategory category) {
        UUID uuid = player.getUniqueId();

        // Per-player cooldown
        Long lastReport = cooldowns.get(uuid);
        if (lastReport != null && (System.currentTimeMillis() - lastReport) < COOLDOWN_SECONDS * 1000L) {
            long remaining = COOLDOWN_SECONDS - (System.currentTimeMillis() - lastReport) / 1000;
            player.sendMessage("§cPlease wait " + remaining + " seconds before submitting another report.");
            return false;
        }

        // Global rate limit
        long now = System.currentTimeMillis();
        if (now - globalWindowStart > RATE_LIMIT_WINDOW_MS) {
            globalReportCount.set(0);
            globalWindowStart = now;
        }
        if (globalReportCount.get() >= GLOBAL_RATE_LIMIT) {
            player.sendMessage("§cToo many reports submitted recently. Please try again later.");
            return false;
        }

        if (activeSessions.containsKey(uuid)) {
            player.sendMessage("§cYou already have an active report session. Type 'cancel' to stop it.");
            return false;
        }

        ReportSession session = new ReportSession(uuid, player.getName(), category, plugin);
        activeSessions.put(uuid, session);
        player.sendMessage("§a\uD83D\uDCCB Starting a " + category.getColor() + category.getDisplayName() + " §areport. Type 'cancel' anytime to stop.");
        player.sendMessage("§eStep 1/5 §f— What happened? Describe it in your own words.");
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        ReportSession session = activeSessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        session.handleInput(player, event.getMessage());
        if (session.isComplete()) {
            completeReport(player, session);
        } else if (session.isCancelled()) {
            activeSessions.remove(player.getUniqueId());
        }
    }

    private void completeReport(Player player, ReportSession session) {
        activeSessions.remove(player.getUniqueId());
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        globalReportCount.incrementAndGet();

        // Auto-upload server log (hardcoded ON)
        String serverLogUrl = AUTO_UPLOAD_SERVER_LOG ? McLogsUploader.uploadServerLog() : null;

        ReportDiagnostics diagnostics = DiagnosticsCollector.collect(plugin);

        ReportTicket ticket = new ReportTicket(
            nextId.getAndIncrement(),
            session.getCategory(),
            session.getPlayerName(),
            session.getPlayerUuid(),
            session.getDiscordUsername(),
            session.getDescription(),
            session.getMclogsUrl(),
            session.getRole(),
            diagnostics,
            Instant.now()
        );

        player.sendMessage("§a✅ Report " + ticket.shortId() + " submitted. Thank you!");

        // Post to Discord webhook
        postToDiscord(REPORT_WEBHOOK_URL, ticket, diagnostics, serverLogUrl);

        // Fire monitor event
        if (eventBus != null) {
            eventBus.publish(new MonitorEvent.ReportSubmitted(ticket));
        }
    }

    /**
     * Submits an automatically-detected error report to the Discord report webhook. This is invoked by
     * {@link AutoErrorReporter} when Slimefun or one of its addons logs a {@code SEVERE} error carrying a
     * stack trace. No player is involved — the ticket is built from server diagnostics and the throwable.
     *
     * <p>De-duplication and rate limiting are handled by the caller ({@link AutoErrorReporter}); this
     * method only builds and dispatches the ticket.
     *
     * @param pluginName
     *            The name of the Slimefun/addon plugin the error originated from
     * @param thrown
     *            The captured {@link Throwable}
     * @param message
     *            The log message that accompanied the error (may be {@code null})
     */
    public void submitAutomaticReport(String pluginName, Throwable thrown, String message) {
        globalReportCount.incrementAndGet();

        ReportDiagnostics diagnostics = DiagnosticsCollector.collect(plugin);

        StringBuilder description = new StringBuilder();
        description.append("Origin: ").append(pluginName != null ? pluginName : "Slimefun").append('\n');
        if (message != null && !message.isBlank()) {
            description.append("Message: ").append(message).append('\n');
        }
        description.append("Exception: ").append(thrown.getClass().getName());
        if (thrown.getMessage() != null) {
            description.append(": ").append(thrown.getMessage());
        }
        description.append('\n').append(formatStackTrace(thrown));

        ReportTicket ticket = new ReportTicket(
            nextId.getAndIncrement(),
            ReportCategory.AUTO_ERROR,
            "AUTO-DETECTED",
            new UUID(0L, 0L),
            null,
            truncate(description.toString(), 1000),
            null,
            "system",
            diagnostics,
            Instant.now()
        );

        postToDiscord(REPORT_WEBHOOK_URL, ticket, diagnostics, null);

        if (eventBus != null) {
            eventBus.publish(new MonitorEvent.ReportSubmitted(ticket));
        }
    }

    /**
     * Renders a compact stack trace (the throwable plus its causes, first frames only) suitable for a
     * Discord embed field. The output is later truncated to fit Discord's 1024-character field limit.
     */
    private static String formatStackTrace(Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        Throwable current = thrown;
        int causeDepth = 0;

        while (current != null && causeDepth < 3) {
            if (causeDepth > 0) {
                sb.append("Caused by: ").append(current.getClass().getName());
                if (current.getMessage() != null) {
                    sb.append(": ").append(current.getMessage());
                }
                sb.append('\n');
            }

            StackTraceElement[] frames = current.getStackTrace();
            int limit = Math.min(frames.length, 6);
            for (int i = 0; i < limit; i++) {
                sb.append("  at ").append(frames[i]).append('\n');
            }
            if (frames.length > limit) {
                sb.append("  ... ").append(frames.length - limit).append(" more\n");
            }

            current = current.getCause();
            causeDepth++;
        }

        return sb.toString();
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }

    private void postToDiscord(String webhookUrl, ReportTicket ticket, ReportDiagnostics diag, String serverLogUrl) {
        try {
            JsonObject payload = new JsonObject();
            if (FORUM_MODE) {
                payload.addProperty("thread_name", "[" + ticket.category().getDisplayName() + "] " + ticket.shortId());
            }

            boolean auto = ticket.category() == ReportCategory.AUTO_ERROR;

            JsonObject embed = new JsonObject();
            embed.addProperty("title", (auto ? "🛑 " : "📋 ") + ticket.category().getDisplayName() + "  •  " + ticket.shortId());
            embed.addProperty("color", auto ? 0xE74C3C : 0xE67E22);
            embed.addProperty("timestamp", ticket.timestamp().toString());

            JsonObject footer = new JsonObject();
            footer.addProperty("text", "SlimefunCore Report System");
            embed.add("footer", footer);

            JsonArray fields = new JsonArray();

            fields.add(createField("👤 Reporter", ticket.username() + "\n`" + ticket.uuid() + "`", false));
            if (!auto) {
                fields.add(createField("💬 Discord", ticket.discordUsername() != null ? ticket.discordUsername() : "N/A", true));
            }
            fields.add(createField("🏷️ Role", ticket.role(), true));
            fields.add(createField("📝 Description", truncate(ticket.description(), 1000), false));
            if (!auto) {
                fields.add(createField("📄 mclo.gs", ticket.mclogsUrl() != null ? ticket.mclogsUrl() : "N/A", true));
                fields.add(createField("📜 Server Log", serverLogUrl != null ? serverLogUrl : "N/A", true));
            }
            fields.add(createField("🖥️ Server", diag.serverVersion(), false));
            fields.add(createField("⚙️ Build", diag.slimefunBuild() + " (" + diag.branch() + ")", true));
            fields.add(createField("☕ Java", diag.javaVersion(), true));
            fields.add(createField("💾 RAM", diag.usedRamMb() + " / " + diag.maxRamMb() + " MB", true));
            fields.add(createField("🎮 Players", String.valueOf(diag.onlinePlayers()), true));

            StringBuilder addons = new StringBuilder();
            if (diag.installedAddons().isEmpty()) {
                addons.append("None");
            } else {
                for (String addon : diag.installedAddons()) {
                    addons.append("• ").append(addon).append('\n');
                }
            }
            fields.add(createField("🧩 Addons", truncate(addons.toString().trim(), 1000), false));

            embed.add("fields", fields);

            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to post report to Discord: " + e.getMessage());
        }
    }

    private JsonObject createField(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", inline);
        return field;
    }

}
