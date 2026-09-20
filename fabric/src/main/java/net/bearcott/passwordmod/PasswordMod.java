package net.bearcott.passwordmod;

import net.bearcott.passwordmod.fabric.FabricAuthPlayer;
import net.bearcott.passwordmod.util.ServerStatusLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Fabric entrypoint. All the behaviour lives in the shared core ({@link AuthCore}); this only wires
 * Fabric's events to it. The two things Fabric API has no event for are mixins: duplicate-login
 * denial and the restore-before-save on disconnect (PlayerListMixin), and advancement completion
 * (PlayerAdvancementsMixin).
 */
public class PasswordMod implements ModInitializer {

    private static List<FabricAuthPlayer> wrap(List<ServerPlayer> players) {
        return players.stream().map(FabricAuthPlayer::new).toList();
    }

    @Override
    public void onInitialize() {
        AuthStorage.load(FabricLoader.getInstance().getConfigDir());

        ServerLifecycleEvents.SERVER_STARTED.register(server -> ServerStatusLogger.onServerStarted());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerStatusLogger.onServerStopping());
        ServerStatusLogger.registerCrashHook();

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) ->
                !(player instanceof ServerPlayer sp) || PlayerLockdownHandlers.allowOrDeny(new FabricAuthPlayer(sp)));

        // SERVER_STOPPING: persist everything before players get kicked.
        // Also runs before vanilla's final saveAll, so pending players still online are saved
        // with their real state (PlayerListMixin covers the normal disconnect path).
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> AuthCore.onServerStopping(wrap(server.getPlayerList().getPlayers())));

        // SERVER_STOPPED: DISCONNECT events have fired by now. Safe to tear down WORKER_POOL.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AuthCore.onServerStopped());

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> AuthCore.onJoin(new FabricAuthPlayer(handler.getPlayer())));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess,
                environment) -> dispatcher.register(literal("login")
                        .then(argument("password", MessageArgument.message())
                                .executes(context -> AuthCore.onLoginCommand(
                                        new FabricAuthPlayer(context.getSource().getPlayerOrException()),
                                        MessageArgument.getMessage(context, "password").getString())))));

        ServerTickEvents.END_SERVER_TICK.register(
                server -> AuthCore.onTick(wrap(server.getPlayerList().getPlayers()), server.getTickCount()));

        // isRunning() flips to false at the start of stopServer(), exactly the window
        // we want to suppress, and it's stateless so it survives JVM-reused lifecycles.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                AuthCore.onDisconnect(new FabricAuthPlayer(handler.getPlayer()), server.isRunning()));
    }
}