package net.bearcott.passwordmod;

import net.bearcott.passwordmod.util.Cosmetics;
import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.Notifications;
import net.bearcott.passwordmod.util.Notifications.Target;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class PasswordMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Auth");
    public static final int MAX_ATTEMPTS = 7;
    private static final int REMINDER_INTERVAL_TICKS = 80; // 4 seconds at 20 TPS
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    public static final ExecutorService WORKER_POOL = Executors.newCachedThreadPool(namedDaemonFactory());

    private static ThreadFactory namedDaemonFactory() {
        return r -> {
            Thread t = new Thread(r, "one-password-auth/worker");
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void onInitialize() {
        AuthStorage.load();

        ServerStatusLogger.register();
        PlayerLockdownHandlers.registerGuards();

        // SERVER_STOPPING: persist everything before players get kicked.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AuthStorage.shutdown());

        // SERVER_STOPPED: DISCONNECT events have fired by now. Safe to tear down WORKER_POOL.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WORKER_POOL.shutdown();
            try {
                WORKER_POOL.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> PlayerLockdownHandlers.handlePlayerJoin(handler.getPlayer(), WORKER_POOL));

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
                                    PlayerLockdownHandlers.handleLoginAttempt(player,
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

                // If are whitelisted, lift their lockdown and skip the rest
                if (AuthStorage.isWhitelisted(player.getIpAddress(), player.getUUID())) {
                    // If they somehow still have the lockdown, lift it
                    if (session != null)
                        PlayerLockdownHandlers.liftLockdown(player, session);
                    continue;
                }

                // Logic for players in lockdown
                if (session != null) {
                    // kick if they exceed the timeout limit since joining or last attempt
                    if (System.currentTimeMillis() - session.lastAttemptTime > (long) AuthStorage.timeoutSec * 1000) {
                        player.connection.disconnect(Component.literal(Messages.TIMEOUT_DISCONNECT));

                        Notifications.broadcast(Messages.timeoutBroadcast(player.getScoreboardName()), null,
                                Target.PUBLIC, WORKER_POOL);
                        continue;
                    }

                    // handle ticked kicks (for visual effects)
                    session.kickPlayerIfTickDelayed(player);

                    // Periodic visual reminders
                    if (server.getTickCount() % REMINDER_INTERVAL_TICKS == 0)
                        Cosmetics.sendAuthTitle(player);

                    // hold all pending players in place
                    PlayerLockdownHandlers.restrictMovement(player, session);

                    // re-assert lockdown if external state changed it (drift check)
                    PlayerLockdownHandlers.reassertIfDrifted(player);
                }

                // If they have no session at all and aren't whitelisted, apply lockdown
                if (session == null && player.isAlive()) {
                    PlayerLockdownHandlers.applyLockdown(player);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Suppress the per-player webhook storm when the server is shutting down —
            // the "server stopping" notification already tells admins everyone is leaving.
            // isRunning() flips to false at the start of stopServer(), exactly the window
            // we want to suppress, and it's stateless so it survives JVM-reused lifecycles.
            if (!server.isRunning())
                return;

            ServerPlayer player = handler.getPlayer();
            UUID uuid = player.getUUID();

            boolean wasPending = AuthStorage.hasPendingSession(uuid);
            String msg = wasPending ? Messages.disconnectFailed(player.getScoreboardName())
                    : Messages.disconnectLeft(player.getScoreboardName());
            Notifications.broadcast(msg, null, wasPending ? Target.BOTH : Target.ADMIN, WORKER_POOL);
        });
    }
}