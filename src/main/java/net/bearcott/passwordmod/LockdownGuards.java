package net.bearcott.passwordmod;

import net.bearcott.passwordmod.util.Messages;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;
import java.util.function.Predicate;

public class LockdownGuards {

    public static void register() {
        denyBlockBreaks();
    }

    private static void denyBlockBreaks() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> allow(player));
    }

    private static boolean allow(Player player) {
        if (!isLocked(player))
            return true;
        if (player instanceof ServerPlayer sp)
            notifyDenied(sp);
        return false;
    }

    private static boolean isLocked(Player player) {
        UUID uuid = player.getUUID();
        return !AuthStorage.isWhitelisted(ipOf(player))
                && AuthStorage.hasPendingSession(uuid);
    }

    private static String ipOf(Player player) {
        return player instanceof ServerPlayer sp ? sp.getIpAddress() : "";
    }

    private static void notifyDenied(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(Messages.LOCKDOWN_DENIED));
    }

    // Composable helper for future guards (e.g. place, interact, drop, container click).
    // Usage: EventX.register((... , player, ...) -> gate(player, () -> <allow-return-value>));
    static <T> T gate(Player player, T allowValue, T denyValue) {
        if (!isLocked(player))
            return allowValue;
        if (player instanceof ServerPlayer sp)
            notifyDenied(sp);
        return denyValue;
    }

    static Predicate<Player> isLockedPredicate() {
        return LockdownGuards::isLocked;
    }
}
