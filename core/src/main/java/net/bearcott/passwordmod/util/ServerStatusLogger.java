package net.bearcott.passwordmod.util;

import net.bearcott.passwordmod.AuthStorage;

public class ServerStatusLogger {
    private static boolean isStoppingGracefully = false;

    // 1. Server Started Event
    public static void onServerStarted() {
        String json = String.format(
                "{\"embeds\": [{\"title\": \"%s\",\"description\": \"%s\",\"color\": %d,\"timestamp\": \"%s\"}]}",
                Messages.SERVER_ONLINE_TITLE, Messages.SERVER_STARTED_DESC, 0x57F287,
                java.time.Instant.now().toString());
        Notifications.sendDiscordWebhookSync(AuthStorage.adminWebhookUrl, json);
    }

    // 2. Server Stopping Event (Graceful Shutdown)
    public static void onServerStopping() {
        isStoppingGracefully = true;
        String json = String.format(
                "{\"embeds\": [{\"title\": \"%s\",\"description\": \"%s\",\"color\": %d,\"timestamp\": \"%s\"}]}",
                Messages.SERVER_STOPPING_TITLE, Messages.SERVER_STOPPING_DESC, 0xE67E22,
                java.time.Instant.now().toString());
        Notifications.sendDiscordWebhookSync(AuthStorage.adminWebhookUrl, json);
    }

    // 3. JVM Shutdown Hook (Catch Crashes/Kills)
    public static void registerCrashHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // If the JVM is dying but the server didn't tell us it was stopping, it's
            // likely a crash or a hard kill.
            if (!isStoppingGracefully) {
                System.out.println("AuthMod: Detected non-graceful shutdown!");
                String json = String.format(
                        "{\"embeds\": [{\"title\": \"%s\",\"description\": \"%s\",\"color\": %d,\"timestamp\": \"%s\"}]}",
                        Messages.SERVER_CRASHED_TITLE, Messages.SERVER_CRASHED_DESC, 0xED4245,
                        java.time.Instant.now().toString());
                Notifications.sendDiscordWebhookSync(AuthStorage.adminWebhookUrl, json);
            }
        }));
    }

}