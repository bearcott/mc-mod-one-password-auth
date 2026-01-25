package net.example.auth;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.*;

public class PasswordMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Auth");

    public static final Set<UUID> PENDING_PLAYERS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, Vec3> JOIN_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, GameType> PREVIOUS_MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_COMMAND_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> JOIN_TIME = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 120 * 1000;

    @Override
    public void onInitialize() {
        AuthStorage.load();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> applyLockdown(handler.getPlayer()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("login")
                    .then(argument("password", MessageArgument.message())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                UUID uuid = player.getUUID();
                                if (!PENDING_PLAYERS.contains(uuid)) return 0;

                                long now = System.currentTimeMillis();
                                if (now - LAST_COMMAND_TIME.getOrDefault(uuid, 0L) < 1500) {
                                    player.sendSystemMessage(Component.literal("§cWait a moment."));
                                    return 0;
                                }
                                LAST_COMMAND_TIME.put(uuid, now);

                                String input = MessageArgument.getMessage(context, "password").getString();
                                String name = player.getScoreboardName();

                                if (input.equals(AuthStorage.serverPassword)) {
                                    LOGGER.info("✅ {} logged in.", name);
                                    sendDiscordNotification("✅ **" + name + "** logged in.");
                                    AuthStorage.saveIP(player.getIpAddress());
                                    player.setGameMode(PREVIOUS_MODES.getOrDefault(uuid, GameType.SURVIVAL));
                                    player.setInvulnerable(false);
                                    player.removeEffect(MobEffects.BLINDNESS);
                                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
                                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
                                    cleanup(uuid);
                                    player.sendSystemMessage(Component.literal("§aAuthenticated!"));
                                } else {
                                    int attempts = LOGIN_ATTEMPTS.getOrDefault(uuid, 0) + 1;
                                    LOGGER.error("❌ {} failed (Attempt {}/5). Tried: '{}'", name, attempts, input);
                                    sendDiscordNotification("❌ **Failed:** `" + name + "` tried `" + input + "` (" + attempts + "/5)");
                                    if (attempts >= 5) {
                                        player.connection.disconnect(Component.literal("§cToo many failed attempts."));
                                        cleanup(uuid);
                                    } else {
                                        LOGIN_ATTEMPTS.put(uuid, attempts);
                                        player.sendSystemMessage(Component.literal("§cWrong password! (" + (5 - attempts) + " left)"));
                                    }
                                }
                                return 1;
                            })));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long currentTime = System.currentTimeMillis();
            // We use a copy of the list to iterate to avoid modification errors during the loop
            for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                UUID uuid = player.getUUID();

                if (!AuthStorage.isAuthed(player.getIpAddress()) && !PENDING_PLAYERS.contains(uuid)) {
                    if (player.isAlive()) applyLockdown(player);
                    continue;
                }

                if (PENDING_PLAYERS.contains(uuid)) {
                    long joinedAt = JOIN_TIME.getOrDefault(uuid, 0L);
                    if (currentTime - joinedAt > TIMEOUT_MS) {
                        LOGGER.warn("⏰ {} timed out.", player.getScoreboardName());
                        sendDiscordNotification("👋 **Timeout:** `" + player.getScoreboardName() + "` timed out.");
                        // Trigger disconnect - cleanup will be handled by the DISCONNECT event
                        player.connection.disconnect(Component.literal("§cLogin timeout."));
                        continue;
                    }

                    if (server.getTickCount() % 80 == 0) sendAuthTitle(player);

                    if (player.isAlive()) {
                        Vec3 joinPos = JOIN_POSITIONS.get(uuid);
                        if (joinPos != null && player.position().distanceToSqr(joinPos) > 0.001) {
                            if (player.level() instanceof ServerLevel sl) {
                                player.teleport(new TeleportTransition(sl, joinPos, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
                            }
                        }
                    }
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            if (PENDING_PLAYERS.contains(uuid)) {
                LOGGER.info("👋 {} disconnected while pending.", handler.getPlayer().getScoreboardName());
                sendDiscordNotification("👋 **Left:** `" + handler.getPlayer().getScoreboardName() + "` disconnected.");
            }
            cleanup(uuid);
        });
    }

    private void applyLockdown(ServerPlayer player) {
        if (AuthStorage.isAuthed(player.getIpAddress()) || !player.isAlive()) return;
        UUID uuid = player.getUUID();
        GameType current = player.gameMode.getGameModeForPlayer();
        GameType target = (current != GameType.SPECTATOR) ? current :
                (player.gameMode.getPreviousGameModeForPlayer() != null && player.gameMode.getPreviousGameModeForPlayer() != GameType.SPECTATOR) ?
                        player.gameMode.getPreviousGameModeForPlayer() : GameType.SURVIVAL;

        PREVIOUS_MODES.put(uuid, target);
        JOIN_POSITIONS.put(uuid, player.position());
        JOIN_TIME.put(uuid, System.currentTimeMillis());
        LOGIN_ATTEMPTS.put(uuid, 0);
        PENDING_PLAYERS.add(uuid);

        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100000, 10, false, false));
        sendAuthTitle(player);
        player.sendSystemMessage(Component.literal("§6[Auth] Please login with /login <password>"));
        sendDiscordNotification("⚠️ **Join:** `" + player.getScoreboardName() + "` awaits login.");
    }

    private void sendAuthTitle(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lHey Buddy")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§fType §e/login <password> §fin chat to join!")));
    }

    // ADDED 'synchronized' to prevent the crash you experienced
    private synchronized void cleanup(UUID uuid) {
        if (uuid == null) return;
        PENDING_PLAYERS.remove(uuid);
        JOIN_POSITIONS.remove(uuid);
        LOGIN_ATTEMPTS.remove(uuid);
        PREVIOUS_MODES.remove(uuid);
        LAST_COMMAND_TIME.remove(uuid);
        JOIN_TIME.remove(uuid);
    }

    private void sendDiscordNotification(String message) {
        if (AuthStorage.webhookUrl == null || AuthStorage.webhookUrl.isEmpty()) return;
        new Thread(() -> {
            try {
                URL url = URI.create(AuthStorage.webhookUrl).toURL();
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.addRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);
                String json = "{\"content\": \"" + message.replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
                try (OutputStream os = con.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
                con.getResponseCode();
                con.disconnect();
            } catch (Exception e) { LOGGER.error("Webhook error: {}", e.getMessage()); }
        }, "Discord-Thread").start();
    }
}