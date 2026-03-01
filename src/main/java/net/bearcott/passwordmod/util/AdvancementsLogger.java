package net.bearcott.passwordmod.util;

import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.PasswordMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;

public class AdvancementsLogger {

    public static void logAdvancement(ServerPlayer player, AdvancementHolder advancement) {
        String title = advancement.value().display()
                .map(display -> display.getTitle().getString())
                .orElse("Secret/Internal Advancement");

        // Skip logging if it's just a recipe unlock (prevents Discord spam)
        if (title.equals("Secret/Internal Advancement")) return;

        boolean isAuthorized = !AuthStorage.hasPendingSession(player.getUUID());
        String playerName = player.getName().getString();

        String statusPrefix = isAuthorized ? "🎖️" : "⚠️ [UNAUTHORIZED!]";
        String message = String.format("%s **%s** just made the advancement `%s`",
                statusPrefix, playerName, title);

        Notifications.broadcast(
                message,
                null,
                isAuthorized,  // toPub: Only if logged in
                !isAuthorized, // toAdmin: Only if NOT logged in (Security Alert)
                PasswordMod.WORKER_POOL
        );

        PasswordMod.LOGGER.info("{} made advancement: {}", playerName, title);
    }
}