package net.bearcott.passwordmod;

import net.bearcott.passwordmod.AuthPlayer.Particle;
import net.bearcott.passwordmod.AuthPlayer.Sound;
import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.Notifications;
import net.bearcott.passwordmod.util.Notifications.Target;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class PlayerLockdownHandlers {
    private static final int BLINDNESS_DURATION_TICKS = 100_000;
    private static final int BLINDNESS_AMPLIFIER = 10;
    private static final int KICK_DELAY_TICKS = 5; // ~0.25s — enough for death/sound effects

    public static void handlePlayerJoin(AuthPlayer player, ExecutorService workerPool) {
        String ip = player.ip();
        boolean isWhitelisted = AuthStorage.isWhitelisted(ip, player.uuid());

        String msg = String.format(
                isWhitelisted ? Messages.WEBHOOK_JOIN_WHITELISTED_FMT : Messages.WEBHOOK_JOIN_NEW_FMT,
                player.name());
        Notifications.broadcast(msg, ip, isWhitelisted ? Target.ADMIN : Target.BOTH, workerPool);

        if (!isWhitelisted) {
            applyLockdown(player);

            chatLoginInstructions(player);
            player.playSound(Sound.WITHER_SPAWN, 0.5f);
        }
    }

    public static void handleLoginAttempt(AuthPlayer player, String input, ExecutorService workerPool) {
        String ip = player.ip();
        UUID uuid = player.uuid();

        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(uuid);
        if (session == null) {
            player.sendMessage(Messages.FATAL_ERROR);
            return;
        }

        // Refuse to authenticate against an empty or unset password. This guards against
        // a default/blank config letting any player "log in" with an empty string.
        // Check BEFORE touching lastAttemptTime so the timeout clock still runs out.
        if (AuthStorage.serverPassword == null || AuthStorage.serverPassword.isEmpty()) {
            player.sendMessage(Messages.FATAL_ERROR);
            return;
        }

        if (!session.didFetchLocation)
            session.setIpLocationAsync(ip, workerPool);

        session.lastAttemptTime = System.currentTimeMillis();

        if (input.equals(AuthStorage.serverPassword)) {
            AuthStorage.whitelist(ip, uuid);
            liftLockdown(player, session);

            loginSuccessEffects(player);

            player.sendMessage(Messages.AUTHENTICATED);
            AuthCore.LOGGER.info("✅ {} logged in.", player.name());
            Notifications.broadcast(
                    String.format(Messages.WEBHOOK_AUTH_SUCCESS_FMT, player.name()),
                    null, Target.PUBLIC, workerPool);
        } else {
            session.loginAttempts++;

            if (session.loginAttempts >= AuthCore.MAX_ATTEMPTS) {
                startKickPlayerEffects(player);
                session.ticksUntilKick = KICK_DELAY_TICKS;
            } else {
                player.playSound(Sound.CREEPER_PRIMED, 1.2f);
                player.sendMessage(Helpers.getSassyMessage(session.loginAttempts, input));
            }

            AuthCore.LOGGER.warn("Failed login from {}: attempt {}/{} \"{}\"", player.name(),
                    session.loginAttempts, AuthCore.MAX_ATTEMPTS, input);
            String msg = String.format(Messages.WEBHOOK_FAILED_ATTEMPT_FMT,
                    player.name(), session.loginAttempts, AuthCore.MAX_ATTEMPTS, input);
            Notifications.broadcast(msg, null, Target.PUBLIC, workerPool);
        }
    }

    public static void createPendingPlayerSession(AuthPlayer player) {
        UUID uuid = player.uuid();

        if (AuthStorage.hasPendingSession(uuid))
            return;

        boolean isOp = player.isOp();
        int level = 0;

        if (isOp) {
            level = player.opLevel();
            player.deop();
        }

        AuthStorage.getOrCreatePendingPlayerSession(
                uuid,
                player.gameMode(),
                isOp,
                level,
                player.position());
    }

    public static void applyLockdown(AuthPlayer player) {
        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(player.uuid());
        if (session != null) {
            session.resetLockdownTimer();
            // suspendLockdown gave a pending op their op back when they left; take it away again.
            if (session.wasOp && player.isOp())
                player.deop();
        } else {
            createPendingPlayerSession(player);
        }

        // Idempotent: always re-assert so crash/desync or external state drift
        // (e.g. /gamemode, /effect clear) can't leave a session holder unlocked.
        player.setGameMode(GameMode.SPECTATOR);
        player.setInvulnerable(true);
        player.addBlindness(BLINDNESS_DURATION_TICKS, BLINDNESS_AMPLIFIER);
    }

    public static void liftLockdown(AuthPlayer player, AuthStorage.PlayerSession session) {
        UUID uuid = player.uuid();

        restoreVanillaState(player, session);
        if (session.wasOp) {
            player.sendMessage(String.format(Messages.WELCOME_BACK_FMT, player.name(), session.opLevel));
        }

        AuthStorage.removePendingSession(uuid);
        player.resetTitle();
    }

    /**
     * Called right before the server saves a pending player (disconnect or server stop). Puts their
     * real game mode, vulnerability, effects and op back, so the lockdown never reaches their
     * playerdata or ops.json. The session is kept, so rejoining locks them down again. This way
     * removing the mod can't strand anyone as a blind, invulnerable spectator or de-opped.
     */
    public static void suspendLockdown(AuthPlayer player, AuthStorage.PlayerSession session) {
        restoreVanillaState(player, session);
    }

    private static void restoreVanillaState(AuthPlayer player, AuthStorage.PlayerSession session) {
        player.setGameMode(session.originalMode);
        player.setInvulnerable(false);
        player.removeBlindness();

        // force op update immediately so they don't have to rejoin
        if (session.wasOp)
            player.restoreOp(session.opLevel);
    }

    public static void reassertIfDrifted(AuthPlayer player) {
        if (player.gameMode() != GameMode.SPECTATOR
                || !player.isInvulnerable()
                || !player.hasBlindness()) {
            applyLockdown(player);
        }
    }

    public static void restrictMovement(AuthPlayer player, AuthStorage.PlayerSession s) {
        if (s != null && s.joinPos != null && player.position().distanceToSqr(s.joinPos) > 0.01) {
            player.teleport(s.joinPos);
        }
    }

    // ---- Lockdown action guards ----
    // Each platform hooks its own block-break event and cancels it when this returns false.

    public static boolean allowOrDeny(AuthPlayer player) {
        if (!isLocked(player))
            return true;
        player.sendMessage(Messages.LOCKDOWN_DENIED);
        return false;
    }

    private static boolean isLocked(AuthPlayer player) {
        UUID uuid = player.uuid();
        // Check session first — cheap ConcurrentHashMap containsKey. 99% of block-break
        // events are from authed players with no session; this short-circuits before the
        // isWhitelisted call, which allocates a String for the pair key.
        return AuthStorage.hasPendingSession(uuid)
                && !AuthStorage.isWhitelisted(player.ip(), uuid);
    }

    // ---- Theatrics ----

    public static void startKickPlayerEffects(AuthPlayer player) {
        player.strikeLightningEffect();
        player.playSound(Sound.DRAGON_FIREBALL_EXPLODE, 1.0f);
        player.spawnParticles(Particle.EXPLOSION, 5);
    }

    public static void chatLoginInstructions(AuthPlayer player) {
        player.sendMessage(Messages.LOGIN_PROMPT_DIV);
        player.sendMessage(Messages.LOGIN_PROMPT_LINE);
        player.sendMessage(Messages.LOGIN_PROMPT_DIV);
    }

    public static void loginSuccessEffects(AuthPlayer player) {
        player.playSound(Sound.PLAYER_LEVELUP, 1.0f);
        player.spawnParticles(Particle.TOTEM_OF_UNDYING, 20);
    }

    public static void sendAuthTitle(AuthPlayer player) {
        player.showTitle(AuthStorage.loginTitle, AuthStorage.loginDescription, Messages.LOGIN_ACTION_BAR);
    }
}
