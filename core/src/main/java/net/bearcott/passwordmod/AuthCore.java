package net.bearcott.passwordmod;

import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.Notifications;
import net.bearcott.passwordmod.util.Notifications.Target;
import net.bearcott.passwordmod.util.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * What the mod does on each server event, shared by the Fabric mod and the Paper plugin. The
 * platform adapters only translate their events into these calls and wrap their players in
 * {@link AuthPlayer}.
 */
public final class AuthCore {
    public static final Logger LOGGER = LoggerFactory.getLogger("Auth");
    public static final int MAX_ATTEMPTS = 7;
    private static final int REMINDER_INTERVAL_TICKS = 80; // 4 seconds at 20 TPS
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    // ~60s at 20 TPS: how often to sweep stale per-IP rate-limiter state. Not per tick (no alloc).
    private static final int RATE_LIMIT_CLEANUP_INTERVAL_TICKS = 1200;

    public static final ExecutorService WORKER_POOL = Executors.newCachedThreadPool(namedDaemonFactory());

    // Shared per-IP rate limiter: connection anti-flood gate + per-IP /login attempt speed cap.
    // Keyed by IP so both controls survive reconnects.
    public static final RateLimiter RATE_LIMITER = new RateLimiter();

    private AuthCore() {
    }

    private static ThreadFactory namedDaemonFactory() {
        return r -> {
            Thread t = new Thread(r, "one-password-auth/worker");
            t.setDaemon(true);
            return t;
        };
    }

    /** /login &lt;password&gt; */
    public static int onLoginCommand(AuthPlayer player, String password) {
        // if already authed, skip
        if (!AuthStorage.hasPendingSession(player.uuid()))
            return 0;

        // Prevent too many attempts in a short time. The per-IP attempt throttle is the real
        // brute-force speed cap (it survives reconnects); the per-session check is kept as a
        // cheap first line. Short-circuit so a blocked attempt doesn't advance the per-IP clock.
        if (Helpers.isRateLimited(player.uuid())
                || !RATE_LIMITER.tryAcquireLoginAttempt(player.ip(), System.currentTimeMillis())) {
            player.sendMessage(Messages.RATE_LIMITED);
            return 0;
        }

        // handle the login attempt asynchronously to avoid blocking the main server
        // thread
        PlayerLockdownHandlers.handleLoginAttempt(player, password, WORKER_POOL);
        return 1;
    }

    public static void onJoin(AuthPlayer player) {
        PlayerLockdownHandlers.handlePlayerJoin(player, WORKER_POOL);
    }

    /**
     * The connection anti-flood gate, called at the door before a player is admitted. Returns true
     * if the connection from {@code ip} is allowed, false if it should be refused for flooding.
     * Kept here (not in the mixin/listener) so it's platform-free and unit-testable.
     */
    public static boolean onIncomingConnection(String ip) {
        return RATE_LIMITER.tryAcquireConnection(ip, System.currentTimeMillis());
    }

    /** Runs at the end of every server tick with a snapshot of the online players. */
    public static void onTick(List<? extends AuthPlayer> players, int tickCount) {
        // Periodically sweep stale per-IP rate-limiter state so the table can't grow unbounded.
        if (tickCount % RATE_LIMIT_CLEANUP_INTERVAL_TICKS == 0)
            RATE_LIMITER.cleanup(System.currentTimeMillis());

        for (AuthPlayer player : players) {
            // 1.21.x safety: skip players already gone
            if (player.hasDisconnected())
                continue;

            AuthStorage.PlayerSession session = AuthStorage.getPendingSession(player.uuid());

            // If are whitelisted, lift their lockdown and skip the rest
            if (AuthStorage.isWhitelisted(player.ip(), player.uuid())) {
                // If they somehow still have the lockdown, lift it
                if (session != null)
                    PlayerLockdownHandlers.liftLockdown(player, session);
                continue;
            }

            // Logic for players in lockdown
            if (session != null) {
                // kick if they exceed the timeout limit since joining or last attempt
                if (System.currentTimeMillis() - session.lastAttemptTime > (long) AuthStorage.timeoutSec * 1000) {
                    player.kick(Messages.KICK_TIMEOUT);

                    Notifications.broadcast(
                            String.format(Messages.WEBHOOK_TIMEOUT_FMT, player.name()),
                            null, Target.PUBLIC, WORKER_POOL);
                    continue;
                }

                // handle ticked kicks (for visual effects)
                session.kickPlayerIfTickDelayed(player);

                // A kick can remove the player (and restore their real state) right away on some
                // platforms; locking them down again now would undo that restore.
                if (player.hasDisconnected())
                    continue;

                // Periodic visual reminders
                if (tickCount % REMINDER_INTERVAL_TICKS == 0)
                    PlayerLockdownHandlers.sendAuthTitle(player);

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
    }

    /**
     * Must run right before the server writes the player's data on disconnect, so the lockdown
     * never reaches their playerdata or ops.json.
     */
    public static void beforePlayerSaved(AuthPlayer player) {
        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(player.uuid());
        if (session != null)
            PlayerLockdownHandlers.suspendLockdown(player, session);
    }

    /**
     * Webhook for a disconnect. {@code serverRunning} is false while the server is shutting down;
     * the "server stopping" notification already tells admins everyone is leaving, so the
     * per-player webhook storm is suppressed.
     */
    public static void onDisconnect(AuthPlayer player, boolean serverRunning) {
        if (!serverRunning)
            return;

        UUID uuid = player.uuid();

        boolean wasPending = AuthStorage.hasPendingSession(uuid);
        String msg = String.format(
                wasPending ? Messages.WEBHOOK_DISCONNECT_PENDING_FMT : Messages.WEBHOOK_DISCONNECT_LEFT_FMT,
                player.name());
        Notifications.broadcast(msg, null, wasPending ? Target.BOTH : Target.ADMIN, WORKER_POOL);
    }

    /**
     * Whether a new connection for {@code uuid} should be refused because an authenticated session
     * for the same UUID is already online. If the existing connection is still in lockdown, it is
     * allowed through (the server then kicks the old one) so a legitimate owner can recover a
     * stuck login.
     *
     * @param existingOnline a live (not already disconnected) connection with this UUID exists
     */
    public static boolean shouldDenyDuplicateLogin(UUID uuid, boolean existingOnline) {
        return existingOnline && !AuthStorage.hasPendingSession(uuid);
    }

    /**
     * Server is stopping, before the final player save: persist everything and put every pending
     * player's real state back so it's what gets saved.
     */
    public static void onServerStopping(List<? extends AuthPlayer> players) {
        for (AuthPlayer player : players) {
            AuthStorage.PlayerSession session = AuthStorage.getPendingSession(player.uuid());
            if (session != null)
                PlayerLockdownHandlers.suspendLockdown(player, session);
        }
        AuthStorage.shutdown();
    }

    /** Server has stopped and every disconnect has been handled: safe to tear down WORKER_POOL. */
    public static void onServerStopped() {
        WORKER_POOL.shutdown();
        try {
            WORKER_POOL.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
