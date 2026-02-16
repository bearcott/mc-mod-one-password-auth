package net.bearcott.passwordmod.util;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

import net.bearcott.passwordmod.AuthStorage;

public class Notifications {
    public static void broadcast(String message, String ip, boolean toPub, boolean toAdmin,
            ExecutorService workerPool) {
        if (toPub)
            postDiscordMessage(message, ip, AuthStorage.webhookUrl, workerPool);
        if (toAdmin)
            postDiscordMessage(message, ip, AuthStorage.adminWebhookUrl, workerPool);
    }

    public static void postDiscordMessage(String message, String ip, String target, ExecutorService workerPool) {
        Runnable send = () -> {
            try {
                Helpers.Location loc = (ip != null) ? Helpers.fetchLocationData(ip) : null;
                String content = message
                        + (loc != null && ip != null ? " \n📍 IP: `" + ip + "` (" + loc.full() + ")" : "");
                String body = "{\"content\": \"" + content.replace("\"", "\\\"") + "\"}";
                sendDiscordWebhookSync(target, body);
            } catch (Exception ignored) {
            }
        };

        if (workerPool != null)
            workerPool.submit(send);
        else
            send.run();
    }

    public static void sendDiscordWebhookSync(String targetUrl, String jsonBody) {
        try {
            HttpURLConnection con = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
            con.setRequestMethod("POST");
            con.addRequestProperty("Content-Type", "application/json");
            con.addRequestProperty("User-Agent", "Java-Discord-Webhook");
            con.setDoOutput(true);
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            try (OutputStream os = con.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            con.getResponseCode();
            con.disconnect();
        } catch (Exception ignored) {
        }
    }
}
