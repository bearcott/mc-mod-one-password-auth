package net.bearcott.passwordmod.util;

import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.PasswordMod;
import net.bearcott.passwordmod.util.Notifications.Target;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;

public class AdvancementsLogger {
    private static final String SECRET_ADVANCEMENT_TITLE = "Secret/Internal Advancement";

    public static void logAdvancement(ServerPlayer player, AdvancementHolder advancement) {
        String title = advancement.value().display()
                .map(display -> display.getTitle().getString())
                .orElse(SECRET_ADVANCEMENT_TITLE);

        // Skip recipe unlocks and other silent advancements — otherwise webhooks get spammed.
        if (title.equals(SECRET_ADVANCEMENT_TITLE))
            return;

        boolean isAuthorized = !AuthStorage.hasPendingSession(player.getUUID());
        String playerName = player.getName().getString();

        String message = String.format(
                isAuthorized ? Messages.WEBHOOK_ADVANCEMENT_AUTHORIZED_FMT
                             : Messages.WEBHOOK_ADVANCEMENT_UNAUTHORIZED_FMT,
                playerName, title);

        // Authorized → public feed. Unauthorized → admin channel as a security alert.
        Notifications.broadcast(message, null,
                isAuthorized ? Target.PUBLIC : Target.ADMIN,
                PasswordMod.WORKER_POOL);

        PasswordMod.LOGGER.info("{} made advancement: {}", playerName, title);
    }
}