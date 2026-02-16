package net.bearcott.passwordmod;

import net.bearcott.passwordmod.util.Cosmetics;
import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.Notifications;
import net.bearcott.passwordmod.util.ServerStatusLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class PasswordMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Auth");
    public static final Integer MAX_ATTEMPTS = 7;
    public static final ExecutorService WORKER_POOL = Executors.newCachedThreadPool();

    @Override
    public void onInitialize() {
        AuthStorage.load();

        ServerStatusLogger.register();

        // Ensure sessions are saved when the server shuts down
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AuthStorage.saveSessionsToFile());

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> PlayerHandlers.handlePlayerJoin(handler.getPlayer(), WORKER_POOL));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess,
                environment) -> dispatcher.register(literal("login")
                        .then(argument("password", MessageArgument.message())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();

                                    // if already authed, skip
                                    if (!AuthStorage.hasPendingSession(player.getUUID()))
                                        return 0;

                                    // prevent player from sending too many attempts in a short time
                                    if (Helpers.isRateLimited(player.getUUID())) {
                                        player.sendSystemMessage(Component.literal(Messages.RATE_LIMITED));
                                        return 0;
                                    }

                                    // handle the login attempt asynchronously to avoid blocking the main server
                                    // thread
                                    PlayerHandlers.handleLoginAttempt(player,
                                            MessageArgument.getMessage(context, "password").getString(), WORKER_POOL);
                                    return 1;
                                }))));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());

            for (ServerPlayer player : players) {
                // 1.21.x safety: skip players already gone
                if (player.hasDisconnected())
                    continue;

                AuthStorage.PlayerSession session = AuthStorage.getPendingSession(player.getUUID());

                // If are whitelisted, ignore the rest
                if (AuthStorage.isWhitelisted(player.getIpAddress())) {
                    // If they somehow still have the lockdown, lift it
                    if (session != null)
                        PlayerHandlers.liftLockdown(player);
                    continue;
                }

                // Logic for players in lockdown
                if (session != null) {
                    // kick if they exceed the timeout limit since joining or last attempt
                    if (System.currentTimeMillis() - session.lastAttemptTime > (long) AuthStorage.timeoutSec * 1000) {
                        Notifications.broadcast(Messages.timeoutBroadcast(player.getScoreboardName()), null, true,
                                false, WORKER_POOL);
                        player.connection.disconnect(Component.literal(Messages.TIMEOUT_DISCONNECT));
                        continue;
                    }

                    // Periodic visual reminders
                    if (server.getTickCount() % 80 == 0)
                        Cosmetics.sendAuthTitle(player);

                    PlayerHandlers.restrictMovement(player);
                }

                // If they have no session at all and aren't whitelisted, apply it
                else if (session == null && player.isAlive()) {
                    PlayerHandlers.applyLockdown(player);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID uuid = player.getUUID();

            // Check session state for notification type
            boolean wasPending = AuthStorage.hasPendingSession(uuid);

            String msg = wasPending ? Messages.disconnectFailed(player.getScoreboardName())
                    : Messages.disconnectLeft(player.getScoreboardName());

            Notifications.broadcast(msg, null, wasPending, true, WORKER_POOL);
        });
    }
}