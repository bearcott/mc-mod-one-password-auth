package net.bearcott.passwordmod.util;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.google.gson.Gson;

import net.bearcott.passwordmod.AuthStorage;

public class Notifications {

    public enum Target {
        PUBLIC(true, false),
        ADMIN(false, true),
        BOTH(true, true);

        final boolean toPublic;
        final boolean toAdmin;

        Target(boolean toPublic, boolean toAdmin) {
            this.toPublic = toPublic;
            this.toAdmin = toAdmin;
        }
    }

    private static final int WEBHOOK_TIMEOUT_MS = 3000;
    private static final Gson GSON = new Gson();

    public static void broadcast(String message, String ip, Target target, ExecutorService workerPool) {
        if (target.toPublic)
            postDiscordMessage(message, ip, AuthStorage.webhookUrl, workerPool);
        if (target.toAdmin)
            postDiscordMessage(message, ip, AuthStorage.adminWebhookUrl, workerPool);
    }

    public static void postDiscordMessage(String message, String ip, String target, ExecutorService workerPool) {
        Runnable send = () -> {
            try {
                Helpers.Location loc = (ip != null) ? Helpers.fetchLocationData(ip) : null;
                String content = message
                        + (loc != null && ip != null
                                ? String.format(Messages.WEBHOOK_IP_SUFFIX_FMT, ip, loc.full())
                                : "");
                String body = buildWebhookPayload(content);
                sendDiscordWebhookSync(target, body);
            } catch (Exception ignored) {
            }
        };

        if (workerPool != null)
            workerPool.submit(send);
        else
            send.run();
    }

    /**
     * Build the Discord webhook JSON body for {@code content}. Pure (no I/O) so it can be
     * unit-tested. Two things it must guarantee:
     * <ul>
     *   <li>Gson escaping — naive string escaping missed backslashes and control chars, which let
     *       malformed player input break webhooks silently.</li>
     *   <li>{@code allowed_mentions: {parse: []}} — without it, a player typing {@code @everyone}
     *       (e.g. in a /login attempt that gets logged) would actually ping the admin's Discord.
     *       An empty parse list neutralizes the ping while keeping the literal text (we still want
     *       to see what was typed).</li>
     * </ul>
     */
    public static String buildWebhookPayload(String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("allowed_mentions", Map.of("parse", List.of()));
        return GSON.toJson(payload);
    }

    public static void sendDiscordWebhookSync(String targetUrl, String jsonBody) {
        try {
            HttpURLConnection con = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
            con.setRequestMethod("POST");
            con.addRequestProperty("Content-Type", "application/json");
            con.addRequestProperty("User-Agent", "Java-Discord-Webhook");
            con.setDoOutput(true);
            con.setConnectTimeout(WEBHOOK_TIMEOUT_MS);
            con.setReadTimeout(WEBHOOK_TIMEOUT_MS);
            try (OutputStream os = con.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            con.getResponseCode();
            con.disconnect();
        } catch (Exception ignored) {
        }
    }
}
