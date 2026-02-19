package net.bearcott.passwordmod;

import net.bearcott.passwordmod.util.Cosmetics;
import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.bearcott.passwordmod.util.Notifications;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class PlayerHandlers {
    public static void handlePlayerJoin(ServerPlayer player, ExecutorService workerPool) {
        String ip = player.getIpAddress();
        UUID uuid = player.getUUID();
        boolean isWhitelisted = AuthStorage.isWhitelisted(ip);

        String msg = "⚠️ **" + player.getScoreboardName() + "** joined "
                + (isWhitelisted ? "(Whitelisted)" : "(New Session)");
        Notifications.broadcast(msg, ip, !isWhitelisted, true, workerPool);

        if (!isWhitelisted) {
            applyLockdown(player);

            Cosmetics.chatLoginInstructions(player);
            Cosmetics.playSound(player, net.minecraft.sounds.SoundEvents.WITHER_SPAWN, 0.5f);
        }
    }

    public static void handleLoginAttempt(ServerPlayer player, String input, ExecutorService workerPool) {
        String ip = player.getIpAddress();
        UUID uuid = player.getUUID();

        if (!AuthStorage.hasPendingSession(uuid)) {
            player.sendSystemMessage(Component.literal(Messages.FATAL_ERROR));
            return;
        }

        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(uuid);

        // fetch their location to BM them if they get kicked from server
        if (session.ipLocation == null)
            session.setIpLocationAsync(ip);

        // Record the last attempt time and reset per-attempt counters as needed
        session.lastAttemptTime = System.currentTimeMillis();

        if (input.equals(AuthStorage.serverPassword)) {
            AuthStorage.whitelistIP(ip);
            liftLockdown(player, session);

            Cosmetics.loginSuccessEffects(player);

            // notifications
            player.sendSystemMessage(Component.literal(Messages.AUTHENTICATED_MESSAGE));
            PasswordMod.LOGGER.info("✅ {} logged in.", player.getScoreboardName());
            Notifications.broadcast(Messages.authSuccess(player.getScoreboardName()), null, true, false,
                    workerPool);
        } else {
            session.loginAttempts = session.loginAttempts + 1;

            if (session.loginAttempts >= PasswordMod.MAX_ATTEMPTS) {
                Cosmetics.startKickPlayerEffects(player);

                // kick the player
                session.ticksUntilKick = 5; // 5 x 50ms = 0.25s delay the kick to let the effects play out
            } else {
                Cosmetics.playSound(player, net.minecraft.sounds.SoundEvents.CREEPER_PRIMED, 1.2f);
                player.sendSystemMessage(Component.literal(Helpers.getSassyMessage(session.loginAttempts, input)));
            }

            PasswordMod.LOGGER.warn("Failed login from {}: attempt {}/{} \"{}\"", player.getScoreboardName(),
                    session.loginAttempts,
                    PasswordMod.MAX_ATTEMPTS, input);
            String msg = "❌ **" + player.getScoreboardName() + "** failed (" + session.loginAttempts + "/"
                    + PasswordMod.MAX_ATTEMPTS
                    + "). Tried: `" + input + "`";
            Notifications.broadcast(msg, null, true, false, workerPool);
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

            // create the player session
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

    public static void resetLockdownTimer(UUID uuid) {
        AuthStorage.PlayerSession session = AuthStorage.getPendingSession(uuid);
        if (session != null) {
            session.lastAttemptTime = System.currentTimeMillis();
            session.loginAttempts = 0;
        }
    }

    public static void applyLockdown(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (AuthStorage.hasPendingSession(uuid)) {
            // if the session already exists, only reset their timeout
            resetLockdownTimer(uuid);
            return;
        }

        createPendingPlayerSession(player);

        // lockdown regardless whether or not we can save their previous state to
        // prevent bypassing login.
        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100000, 10, false, false));

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

            player.sendSystemMessage(Component.literal(Messages.welcomeBack(nameAndId.name(), session.opLevel)));
        }

        // remove any lockdown effects regardless of session, if this is called w/o a
        // session, things like gamemode and op won't be restored
        AuthStorage.removePendingSession(uuid);
        player.setInvulnerable(false);
        player.removeEffect(MobEffects.BLINDNESS);

        Cosmetics.resetTitle(player);
    }

    public static void restrictMovement(ServerPlayer player, AuthStorage.PlayerSession s) {
        if (s != null && s.joinPos != null && player.position().distanceToSqr(s.joinPos) > 0.01
                && player.level() instanceof ServerLevel sl) {
            player.teleport(new TeleportTransition(sl, s.joinPos, Vec3.ZERO, player.getYRot(), player.getXRot(),
                    TeleportTransition.DO_NOTHING));
        }
    }
}
