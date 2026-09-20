package net.bearcott.passwordmod.paper;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.bearcott.passwordmod.AuthCore;
import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.PlayerLockdownHandlers;
import net.bearcott.passwordmod.util.AdvancementsLogger;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.ServerStatusLogger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Paper entrypoint. All the behaviour lives in the shared core ({@link AuthCore}); this only wires
 * Paper's events to it.
 *
 * Timing that the lockdown restore relies on (checked against Paper 26.2):
 *  - PlayerQuitEvent fires in PlayerList.remove() before the player's data is saved.
 *  - onDisable() runs in stopServer() before the final player save and before players are kicked
 *    (and no events reach a disabled plugin after that).
 */
public class PasswordPlugin extends JavaPlugin implements Listener {
    private static final int PRE_LOGIN_CHECK_TIMEOUT_SECONDS = 5;

    private static List<PaperAuthPlayer> wrap(Collection<? extends Player> players) {
        return players.stream().map(PaperAuthPlayer::new).toList();
    }

    @Override
    public void onEnable() {
        AuthStorage.load(getDataPath());
        ServerStatusLogger.registerCrashHook();

        getServer().getPluginManager().registerEvents(this, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("login")
                        .then(Commands.argument("password", StringArgumentType.greedyString())
                                .executes(context -> {
                                    if (!(context.getSource().getExecutor() instanceof Player player)) {
                                        context.getSource().getSender().sendMessage("Only players can /login.");
                                        return 0;
                                    }
                                    return AuthCore.onLoginCommand(new PaperAuthPlayer(player),
                                            StringArgumentType.getString(context, "password"));
                                }))
                        .build(),
                "Log in with the server password"));
    }

    @Override
    public void onDisable() {
        // Paper disables plugins at the start of stopServer(), before the final player save:
        // the equivalent of Fabric's SERVER_STOPPING. Pending players get their real state back
        // here so that's what is saved.
        boolean stopping = Bukkit.isStopping();
        if (stopping)
            ServerStatusLogger.onServerStopping();
        AuthCore.onServerStopping(wrap(Bukkit.getOnlinePlayers()));
        AuthCore.onServerStopped();
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.STARTUP)
            ServerStatusLogger.onServerStarted();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        AuthCore.onJoin(new PaperAuthPlayer(event.getPlayer()));
    }

    // LOWEST: put the player's real state back before any other plugin looks at them on quit.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        PaperAuthPlayer player = new PaperAuthPlayer(event.getPlayer());
        AuthCore.beforePlayerSaved(player);
        AuthCore.onDisconnect(player, !Bukkit.isStopping());
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        AuthCore.onTick(wrap(Bukkit.getOnlinePlayers()), event.getTickNumber());
    }

    // Runs before the server kicks an existing connection with the same UUID, so an authenticated
    // player can't be kicked by someone logging in as them.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
            return;
        UUID uuid = event.getUniqueId();
        boolean existingOnline;
        try {
            // This event is async; look at the player list on the main thread.
            existingOnline = Bukkit.getScheduler().callSyncMethod(this, () -> {
                Player existing = Bukkit.getPlayer(uuid);
                return existing != null && existing.isConnected();
            }).get(PRE_LOGIN_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return;
        }
        if (AuthCore.shouldDenyDuplicateLogin(uuid, existingOnline))
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, PaperAuthPlayer.text(Messages.DUPLICATE_LOGIN_DENIED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!PlayerLockdownHandlers.allowOrDeny(new PaperAuthPlayer(event.getPlayer())))
            event.setCancelled(true);
    }

    // Fires once per completed advancement (not per criterion).
    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        var display = event.getAdvancement().getDisplay();
        // Paper's plain-text serializer resolves vanilla translation keys to English titles.
        String title = display == null ? null : PlainTextComponentSerializer.plainText().serialize(display.title());
        AdvancementsLogger.logAdvancement(event.getPlayer().getName(), event.getPlayer().getUniqueId(), title);
    }
}
