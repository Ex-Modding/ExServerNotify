package com.fome.notify;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

        // Live Radar Coordinates Hook (syncs every ~8 seconds if players are online)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            radarTickCounter++;
            if (radarTickCounter < 160) {
                return;
            }
            radarTickCounter = 0;

            if (server.getPlayerList().getPlayerCount() == 0) {
                return;
            }

            List<ServerPlayer> realPlayers = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.getScoreboardName().toLowerCase().contains("bot")) {
                    realPlayers.add(player);
                }
            }

            if (realPlayers.isEmpty()) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"secret\":\"").append(SECRET).append("\",\"players\":[");
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
