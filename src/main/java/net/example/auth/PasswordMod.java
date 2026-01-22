package net.example.auth;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static net.minecraft.commands.Commands.*;

public class PasswordMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("AuthMod");
    public static final Set<UUID> PENDING_PLAYERS = new HashSet<>();
    private static final Map<UUID, Vec3> JOIN_POSITIONS = new HashMap<>();
    private static final Map<UUID, GameType> PREVIOUS_MODES = new HashMap<>();
    private static final Map<UUID, Integer> LOGIN_ATTEMPTS = new HashMap<>();

    @Override
    public void onInitialize() {
        AuthStorage.load();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!AuthStorage.isAuthed(player.getIpAddress())) {
                UUID uuid = player.getUUID();
                GameType originalMode = player.gameMode.getGameModeForPlayer();
                if (originalMode == GameType.SPECTATOR) originalMode = GameType.SURVIVAL;

                PREVIOUS_MODES.put(uuid, originalMode);
                PENDING_PLAYERS.add(uuid);
                JOIN_POSITIONS.put(uuid, player.position());
                LOGIN_ATTEMPTS.put(uuid, 0);

                player.setGameMode(GameType.SPECTATOR);
                player.setInvulnerable(true);
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 99999, 10, false, false));

                player.sendSystemMessage(Component.literal("§6[Auth] Use: /login <password>"));
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("login")
                    .then(argument("password", MessageArgument.message())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                UUID uuid = player.getUUID();
                                if (!PENDING_PLAYERS.contains(uuid)) return 0;

                                String input = MessageArgument.getMessage(context, "password").getString();
                                String playerName = player.getScoreboardName();
                                String ip = player.getIpAddress();

                                if (input.equals(AuthStorage.serverPassword)) {
                                    // SUCCESS LOGGING
                                    LOGGER.info("[AUTH] {} logged in successfully.", playerName);
                                    sendDiscordNotification("✅ **" + playerName + "** logged in successfully (IP: " + ip + ")");

                                    AuthStorage.saveIP(ip);
                                    player.setGameMode(PREVIOUS_MODES.getOrDefault(uuid, GameType.SURVIVAL));
                                    player.setInvulnerable(false);
                                    player.removeEffect(MobEffects.BLINDNESS);

                                    PENDING_PLAYERS.remove(uuid);
                                    JOIN_POSITIONS.remove(uuid);
                                    LOGIN_ATTEMPTS.remove(uuid);
                                    return 1;
                                } else {
                                    // FAILURE LOGGING - Log exactly what they typed
                                    int attempts = LOGIN_ATTEMPTS.getOrDefault(uuid, 0) + 1;
                                    LOGGER.warn("[AUTH] {} failed login. Typed: '{}' (Attempt {}/5)", playerName, input, attempts);
                                    sendDiscordNotification("❌ **" + playerName + "** failed login. Typed: `" + input + "` (Attempt " + attempts + "/5)");

                                    if (attempts >= 5) {
                                        player.connection.disconnect(Component.literal("§cToo many failed attempts."));
                                    } else {
                                        LOGIN_ATTEMPTS.put(uuid, attempts);
                                        player.sendSystemMessage(Component.literal("§cWrong password! (" + (5 - attempts) + " left)"));
                                    }
                                }
                                return 1;
                            })));
        });

        // Teleport Lock Logic
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (PENDING_PLAYERS.contains(player.getUUID())) {
                    Vec3 joinPos = JOIN_POSITIONS.get(player.getUUID());
                    if (joinPos != null && player.position().distanceToSqr(joinPos) > 0.01) {
                        if (player.level() instanceof ServerLevel sl) {
                            player.teleport(new TeleportTransition(sl, joinPos, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
                        }
                    }
                }
            }
        });
    }

    private void sendDiscordNotification(String message) {
        if (AuthStorage.webhookUrl == null || AuthStorage.webhookUrl.isEmpty()) return;

        new Thread(() -> {
            try {
                URL url = new URL(AuthStorage.webhookUrl);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.addRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);

                String json = "{\"content\": \"" + message.replace("\"", "\\\"") + "\"}";
                try (OutputStream os = con.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                con.getResponseCode(); // Trigger the request
                con.disconnect();
            } catch (Exception e) {
                LOGGER.error("Failed to send Discord webhook: " + e.getMessage());
            }
        }).start();
    }
}