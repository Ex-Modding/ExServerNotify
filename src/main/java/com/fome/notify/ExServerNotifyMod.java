package com.fome.notify;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExServerNotifyMod implements ModInitializer {
    public static final String MOD_ID = "exservernotify";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String BASE_URL = "https://ex-server-web.vercel.app";
    private static final String NOTIFY_URL = BASE_URL + "/api/notify";
    private static final String RADAR_URL = BASE_URL + "/api/radar";
    private static final String SECRET = "fome_secret_webhook_2026";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static int radarTickCounter = 0;
    private static int lowTpsStrikeCounter = 0;
    private static long lastTpsAlertTime = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("[ExServerNotify] Initializing server notification & live radar module...");

        // Server Lifecycle Hooks
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[ExServerNotify] Server started! Sending webhook alert to ExServerWeb...");
            sendWebhook(NOTIFY_URL, "{\"event\":\"server_started\",\"secret\":\"" + SECRET + "\"}");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[ExServerNotify] Server stopping! Sending webhook alert to ExServerWeb...");
            sendWebhook(NOTIFY_URL, "{\"event\":\"server_stopping\",\"secret\":\"" + SECRET + "\"}");
        });

        // Live Radar Coordinates & Telemetry Hook (syncs every ~8 seconds if server is ticking)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            radarTickCounter++;
            if (radarTickCounter < 160) {
                return;
            }
            radarTickCounter = 0;

            // Measure MSPT, TPS and CPU
            double mspt = getAverageMspt(server);
            double tps = Math.min(20.0, 1000.0 / Math.max(mspt, 1.0));
            double cpu = getProcessCpuLoad();

            // Check severe TPS drop for the new Status debugging topic
            checkTpsLagAlert(server, tps, mspt);

            List<ServerPlayer> realPlayers = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.getScoreboardName().toLowerCase().contains("bot")) {
                    realPlayers.add(player);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "{\"secret\":\"%s\",\"tps\":%.1f,\"mspt\":%.1f,\"cpu\":%.0f,\"players\":[",
                    SECRET, tps, mspt, cpu));

            boolean first = true;
            for (ServerPlayer p : realPlayers) {
                if (!first) sb.append(",");
                first = false;

                Vec3 pos = p.position();
                String dim = "overworld";
                if (p.level().dimension() == Level.NETHER) dim = "the_nether";
                if (p.level().dimension() == Level.END) dim = "the_end";

                sb.append("{\"name\":\"").append(p.getScoreboardName())
                        .append("\",\"x\":").append((int) pos.x)
                        .append(",\"y\":").append((int) pos.y)
                        .append(",\"z\":").append((int) pos.z)
                        .append(",\"dimension\":\"").append(dim).append("\"}");
            }
            sb.append("]}");

            sendWebhook(RADAR_URL, sb.toString());
        });
    }

    private static double getAverageMspt(MinecraftServer server) {
        try {
            return server.getAverageTickTimeNanos() * 1.0E-6D;
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    private static double getProcessCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getProcessCpuLoad() * 100.0;
                return load >= 0 ? load : 0.0;
            }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private static void checkTpsLagAlert(MinecraftServer server, double tps, double mspt) {
        long now = System.currentTimeMillis();
        if (tps < 13.0) {
            lowTpsStrikeCounter++;
            // If TPS is suppressed for >3 checks (~24 seconds) and haven't alerted in last 5 minutes
            if (lowTpsStrikeCounter >= 3 && (now - lastTpsAlertTime > 300_000L)) {
                lastTpsAlertTime = now;
                lowTpsStrikeCounter = 0;
                int playerCount = server.getPlayerList().getPlayerCount();
                String lagJson = String.format(Locale.US,
                        "{\"event\":\"tps_drop\",\"secret\":\"%s\",\"tps\":%.1f,\"mspt\":%.1f,\"players\":%d}",
                        SECRET, tps, mspt, playerCount);
                sendWebhook(NOTIFY_URL, lagJson);
            }
        } else {
            lowTpsStrikeCounter = 0;
        }
    }

    private static void sendWebhook(String url, String jsonPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("x-notify-secret", SECRET)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        LOGGER.warn("[ExServerNotify] Webhook dispatch error to {}: {}", url, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.error("[ExServerNotify] Failed to construct HTTP request to " + url, e);
        }
    }
}
