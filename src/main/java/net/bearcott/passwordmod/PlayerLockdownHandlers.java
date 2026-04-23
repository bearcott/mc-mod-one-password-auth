package net.bearcott.passwordmod;

import net.bearcott.passwordmod.util.Cosmetics;
import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.Notifications;
import net.bearcott.passwordmod.util.Notifications.Target;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class PlayerLockdownHandlers {
    private static final int BLINDNESS_DURATION_TICKS = 100_000;
    private static final int BLINDNESS_AMPLIFIER = 10;
    private static final int KICK_DELAY_TICKS = 5; // ~0.25s — enough for death/sound effects

    public static void handlePlayerJoin(ServerPlayer player, ExecutorService workerPool) {
        String ip = player.getIpAddress();
        boolean isWhitelisted = AuthStorage.isWhitelisted(ip, player.getUUID());

        String msg = String.format(
                isWhitelisted ? Messages.WEBHOOK_JOIN_WHITELISTED_FMT : Messages.WEBHOOK_JOIN_NEW_FMT,
                player.getScoreboardName());
        Notifications.broadcast(msg, ip, isWhitelisted ? Target.ADMIN : Target.BOTH, workerPool);

        if (!isWhitelisted) {
            applyLockdown(player);

            Cosmetics.chatLoginInstructions(player);
            Cosmetics.playSound(player, net.minecraft.sounds.SoundEvents.WITHER_SPAWN, 0.5f);
        }
    }

    public static void handleLoginAttempt(ServerPlayer player, String input, ExecutorService workerPool) {
        String ip = player.getIpAddress();
        UUID uuid = player.getUUID();

        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(uuid);
        if (session == null) {
            player.sendSystemMessage(Component.literal(Messages.FATAL_ERROR));
            return;
        }

        // Refuse to authenticate against an empty or unset password. This guards against
        // a default/blank config letting any player "log in" with an empty string.
        // Check BEFORE touching lastAttemptTime so the timeout clock still runs out.
        if (AuthStorage.serverPassword == null || AuthStorage.serverPassword.isEmpty()) {
            player.sendSystemMessage(Component.literal(Messages.FATAL_ERROR));
            return;
        }

        if (!session.didFetchLocation)
            session.setIpLocationAsync(ip, workerPool);

        session.lastAttemptTime = System.currentTimeMillis();

        if (input.equals(AuthStorage.serverPassword)) {
            AuthStorage.whitelist(ip, uuid);
            liftLockdown(player, session);

            Cosmetics.loginSuccessEffects(player);

            player.sendSystemMessage(Component.literal(Messages.AUTHENTICATED));
            PasswordMod.LOGGER.info("✅ {} logged in.", player.getScoreboardName());
            Notifications.broadcast(
                    String.format(Messages.WEBHOOK_AUTH_SUCCESS_FMT, player.getScoreboardName()),
                    null, Target.PUBLIC, workerPool);
        } else {
            session.loginAttempts++;

            if (session.loginAttempts >= PasswordMod.MAX_ATTEMPTS) {
                Cosmetics.startKickPlayerEffects(player);
                session.ticksUntilKick = KICK_DELAY_TICKS;
            } else {
                Cosmetics.playSound(player, net.minecraft.sounds.SoundEvents.CREEPER_PRIMED, 1.2f);
                player.sendSystemMessage(Component.literal(Helpers.getSassyMessage(session.loginAttempts, input)));
            }

            PasswordMod.LOGGER.warn("Failed login from {}: attempt {}/{} \"{}\"", player.getScoreboardName(),
                    session.loginAttempts, PasswordMod.MAX_ATTEMPTS, input);
            String msg = String.format(Messages.WEBHOOK_FAILED_ATTEMPT_FMT,
                    player.getScoreboardName(), session.loginAttempts, PasswordMod.MAX_ATTEMPTS, input);
            Notifications.broadcast(msg, null, Target.PUBLIC, workerPool);
        }
    }

    public static void createPendingPlayerSession(ServerPlayer player) {
        UUID uuid = player.getUUID();

        if (AuthStorage.hasPendingSession(uuid))
            return;

        if (player.level() instanceof ServerLevel sl) {
            PlayerList playerList = sl.getServer().getPlayerList();
            var profile = player.getGameProfile();
            var nameAndId = new NameAndId(profile.id(), profile.name());

            boolean isOp = playerList.isOp(nameAndId);
            int level = 0;

            if (isOp) {
                var opEntry = playerList.getOps().get(nameAndId);
                level = (opEntry != null) ? opEntry.permissions().level().id() : 4;

                playerList.deop(nameAndId);
            }

            AuthStorage.getOrCreatePendingPlayerSession(
                    uuid,
                    player.gameMode.getGameModeForPlayer(),
                    isOp,
                    level,
                    player.position());
        } else {
            player.sendSystemMessage(Component.literal(Messages.FATAL_ERROR));
        }
    }

    public static void applyLockdown(ServerPlayer player) {
        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(player.getUUID());
        if (session != null) {
            session.resetLockdownTimer();
        } else {
            createPendingPlayerSession(player);
        }

        // Idempotent: always re-assert so crash/desync or external state drift
        // (e.g. /gamemode, /effect clear) can't leave a session holder unlocked.
        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                BLINDNESS_DURATION_TICKS, BLINDNESS_AMPLIFIER, false, false));
    }

    public static void liftLockdown(ServerPlayer player, AuthStorage.PlayerSession session) {
        UUID uuid = player.getUUID();

        player.setGameMode(session.originalMode);

        if (session.wasOp && player.level() instanceof ServerLevel sl) {
            var profile = player.getGameProfile();
            var nameAndId = new NameAndId(profile.id(), profile.name());

            PermissionLevel pLevel = PermissionLevel.byId(session.opLevel);
            LevelBasedPermissionSet pSet = LevelBasedPermissionSet.forLevel(pLevel);
            ServerOpListEntry entry = new ServerOpListEntry(nameAndId, pSet, false);

            // force op update immediately so they don't have to rejoin
            sl.getServer().getPlayerList().getOps().add(entry);
            sl.getServer().getPlayerList().sendPlayerPermissionLevel(player);
            sl.getServer().getCommands().sendCommands(player);
            player.onUpdateAbilities();

            player.sendSystemMessage(Component.literal(
                    String.format(Messages.WELCOME_BACK_FMT, nameAndId.name(), session.opLevel)));
        }

        // remove any lockdown effects regardless of session, if this is called w/o a
        // session, things like game mode and op won't be restored
        AuthStorage.removePendingSession(uuid);
        player.setInvulnerable(false);
        player.removeEffect(MobEffects.BLINDNESS);

        Cosmetics.resetTitle(player);
    }

    public static void reassertIfDrifted(ServerPlayer player) {
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR
                || !player.isInvulnerable()
                || !player.hasEffect(MobEffects.BLINDNESS)) {
            applyLockdown(player);
        }
    }

    public static void restrictMovement(ServerPlayer player, AuthStorage.PlayerSession s) {
        if (s != null && s.joinPos != null && player.position().distanceToSqr(s.joinPos) > 0.01
                && player.level() instanceof ServerLevel sl) {
            player.teleport(new TeleportTransition(sl, s.joinPos, Vec3.ZERO, player.getYRot(), player.getXRot(),
                    TeleportTransition.DO_NOTHING));
        }
    }

    // ---- Lockdown action guards ----
    // Registered once at startup. Each event fires for every player, and the
    // handler runtime-checks isLocked(player) to decide whether to cancel.

    public static void registerGuards() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> allowOrDeny(player));
    }

    private static boolean allowOrDeny(Player player) {
        if (!isLocked(player))
            return true;
        if (player instanceof ServerPlayer sp)
            sp.sendSystemMessage(Component.literal(Messages.LOCKDOWN_DENIED));
        return false;
    }

    private static boolean isLocked(Player player) {
        if (!(player instanceof ServerPlayer sp))
            return false;
        UUID uuid = sp.getUUID();
        // Check session first — cheap ConcurrentHashMap containsKey. 99% of block-break
        // events are from authed players with no session; this short-circuits before the
        // isWhitelisted call, which allocates a String for the pair key.
        return AuthStorage.hasPendingSession(uuid)
                && !AuthStorage.isWhitelisted(sp.getIpAddress(), uuid);
    }
}
