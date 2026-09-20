package net.bearcott.passwordmod.util;

import net.bearcott.passwordmod.AuthCore;
import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.util.Notifications.Target;

import java.util.UUID;

public class AdvancementsLogger {

    /**
     * Call once per advancement a player completes (not per criterion).
     *
     * @param title the advancement's display title, or null if it has no display (recipe unlocks
     *              and other silent advancements)
     */
    public static void logAdvancement(String playerName, UUID uuid, String title) {
        // Skip recipe unlocks and other silent advancements — otherwise webhooks get spammed.
        if (title == null)
            return;

        boolean isAuthorized = !AuthStorage.hasPendingSession(uuid);

        String message = String.format(
                isAuthorized ? Messages.WEBHOOK_ADVANCEMENT_AUTHORIZED_FMT
                             : Messages.WEBHOOK_ADVANCEMENT_UNAUTHORIZED_FMT,
                playerName, title);

        // Authorized → public feed. Unauthorized → admin channel as a security alert.
        Notifications.broadcast(message, null,
                isAuthorized ? Target.PUBLIC : Target.ADMIN,
                AuthCore.WORKER_POOL);

        AuthCore.LOGGER.info("{} made advancement: {}", playerName, title);
    }
}
