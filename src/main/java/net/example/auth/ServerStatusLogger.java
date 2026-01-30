package net.example.auth;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ServerStatusLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("AuthLogger");
    private static boolean isStoppingGracefully = false;

    public static void register() {
        // 1. Server Started Event
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            sendWebhook("🟢 **Server Online**", "The server has started successfully and is ready for connections.", 0x57F287);
        });

        // 2. Server Stopping Event (Graceful Shutdown)
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            isStoppingGracefully = true;
            sendWebhook("🛑 **Server Stopping**", "The server is shutting down...", 0xE67E22);
        });

        // 3. JVM Shutdown Hook (Catch Crashes/Kills)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // If the JVM is dying but the server didn't tell us it was stopping, it's likely a crash or a hard kill.
            if (!isStoppingGracefully) {
                System.out.println("AuthMod: Detected non-graceful shutdown!");
                sendWebhook("☠️ **Server Crashed / Killed**", "The server process terminated unexpectedly!", 0xED4245);
            }
        }));
    }

    private static void sendWebhook(String title, String description, int color) {
        String url = AuthStorage.adminWebhookUrl;
        if (url == null || url.isEmpty()) return;

        // We run this synchronously (on the current thread) because if the server is crashing,
        // async threads (Executors) might already be dead.
        try {
            URL targetUrl = URI.create(url).toURL();
            HttpURLConnection con = (HttpURLConnection) targetUrl.openConnection();
            con.setRequestMethod("POST");
            con.addRequestProperty("Content-Type", "application/json");
            con.addRequestProperty("User-Agent", "Java-Discord-Webhook");
            con.setDoOutput(true);
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);

            // Simple JSON payload with an embed for nicer formatting
            String json = String.format(
                    "{\"embeds\": [{" +
                            "\"title\": \"%s\"," +
                            "\"description\": \"%s\"," +
                            "\"color\": %d," +
                            "\"timestamp\": \"%s\"" +
                            "}]}",
                    title, description, color, java.time.Instant.now().toString()
            );

            try (OutputStream os = con.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            con.getResponseCode(); // Trigger the request
            con.disconnect();
        } catch (Exception e) {
            LOGGER.error("Failed to send lifecycle webhook", e);
        }
    }
}