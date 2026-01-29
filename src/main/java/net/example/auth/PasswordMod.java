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

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private static final long TIMEOUT_MS = 120 * 1000;

    public static final Set<UUID> PENDING_PLAYERS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, Vec3> JOIN_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, GameType> PREVIOUS_MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_COMMAND_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> JOIN_TIME = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        AuthStorage.load();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> handlePlayerJoin(handler.getPlayer()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("login")
                    .then(argument("password", MessageArgument.message())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                UUID uuid = player.getUUID();
                                if (!PENDING_PLAYERS.contains(uuid)) return 0;

                                if (isRateLimited(uuid)) {
                                    player.sendSystemMessage(Component.literal("§cWait a moment."));
                                    return 0;
                                }

                                String input = MessageArgument.getMessage(context, "password").getString();
                                handleLoginAttempt(player, input);
                                return 1;
                            })));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                UUID uuid = player.getUUID();
                String ip = player.getIpAddress();

                if (!AuthStorage.isAuthed(ip) && !PENDING_PLAYERS.contains(uuid)) {
                    if (player.isAlive()) applyLockdown(player);
                    continue;
                }

                if (PENDING_PLAYERS.contains(uuid)) {
                    if (now - JOIN_TIME.getOrDefault(uuid, 0L) > TIMEOUT_MS) {
                        broadcast("⏰ **" + player.getScoreboardName() + "** timed out.", null, true, false);
                        player.connection.disconnect(Component.literal("§cLogin timeout."));
                        continue;
                    }
                    if (server.getTickCount() % 80 == 0) sendAuthTitle(player);
                    restrictMovement(player);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getScoreboardName();
            boolean wasPending = PENDING_PLAYERS.contains(player.getUUID());

            String msg = wasPending ? "👋 **" + name + "** disconnected (pending auth)." : "🔌 **" + name + "** left the server.";

            // Public only sees disconnects if they were pending; Admin sees all departures.
            broadcast(msg, null, wasPending, true);
            cleanup(player.getUUID());
        });
    }

    private void handlePlayerJoin(ServerPlayer player) {
        String ip = player.getIpAddress();
        String name = player.getScoreboardName();
        boolean isWhitelisted = AuthStorage.isAuthed(ip);

        // ALWAYS log the join to the Admin Webhook with IP/Location
        broadcast("⚠️ **" + name + "** joined " + (isWhitelisted ? "(Whitelisted)" : "(New Session)"), ip, !isWhitelisted, true);

        if (!isWhitelisted) {
            applyLockdown(player);
        }
    }

    private void handleLoginAttempt(ServerPlayer player, String input) {
        UUID uuid = player.getUUID();
        String name = player.getScoreboardName();

        if (input.equals(AuthStorage.serverPassword)) {
            LOGGER.info("✅ {} logged in.", name);
            broadcast("✅ **" + name + "** authenticated.", null, true, false);
            AuthStorage.saveIP(player.getIpAddress());
            liftLockdown(player);
            player.sendSystemMessage(Component.literal("§aAuthenticated!"));
        } else {
            int attempts = LOGIN_ATTEMPTS.getOrDefault(uuid, 0) + 1;
            LOGGER.error("❌ {} failed attempt {}/5.", name, attempts);
            broadcast("❌ **" + name + "** failed (" + attempts + "/5). Tried: `" + input + "`", null, true, false);

            if (attempts >= 5) {
                player.connection.disconnect(Component.literal("§cToo many failed attempts."));
            } else {
                LOGIN_ATTEMPTS.put(uuid, attempts);
                player.sendSystemMessage(Component.literal("§cWrong password! (" + (5 - attempts) + " left)"));
            }
        }
    }

    private void applyLockdown(ServerPlayer player) {
        UUID uuid = player.getUUID();
        GameType current = player.gameMode.getGameModeForPlayer();
        GameType target = (current != GameType.SPECTATOR) ? current : GameType.SURVIVAL;

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
    }

    private void liftLockdown(ServerPlayer player) {
        UUID uuid = player.getUUID();
        player.setGameMode(PREVIOUS_MODES.getOrDefault(uuid, GameType.SURVIVAL));
        player.setInvulnerable(false);
        player.removeEffect(MobEffects.BLINDNESS);
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
        cleanup(uuid);
    }

    private void restrictMovement(ServerPlayer player) {
        if (!player.isAlive()) return;
        Vec3 joinPos = JOIN_POSITIONS.get(player.getUUID());
        if (joinPos != null && player.position().distanceToSqr(joinPos) > 0.01) {
            if (player.level() instanceof ServerLevel sl) {
                player.teleport(new TeleportTransition(sl, joinPos, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
            }
        }
    }

    private boolean isRateLimited(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = LAST_COMMAND_TIME.getOrDefault(uuid, 0L);
        LAST_COMMAND_TIME.put(uuid, now);
        return (now - last < 1500);
    }

    private void sendAuthTitle(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lHey Buddy")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§fType §e/login <password> §fin chat to join!")));
    }

    private synchronized void cleanup(UUID uuid) {
        PENDING_PLAYERS.remove(uuid);
        JOIN_POSITIONS.remove(uuid);
        LOGIN_ATTEMPTS.remove(uuid);
        PREVIOUS_MODES.remove(uuid);
        LAST_COMMAND_TIME.remove(uuid);
        JOIN_TIME.remove(uuid);
    }

    private void broadcast(String message, String ip, boolean toPublic, boolean toAdmin) {
        if (toPublic) sendDiscordNotification(message, ip, AuthStorage.webhookUrl);
        if (toAdmin) sendDiscordNotification(message, ip, AuthStorage.adminWebhookUrl);
    }

    private void sendDiscordNotification(String message, String ip, String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) return;
        new Thread(() -> {
            try {
                StringBuilder fullMsg = new StringBuilder(message);
                if (ip != null) {
                    String loc = "Unknown Location";
                    try {
                        String clean = ip.split(":")[0].replace("/", "");
                        URL url = URI.create("http://ip-api.com/csv/" + clean + "?fields=city,country").toURL();
                        HttpURLConnection con = (HttpURLConnection) url.openConnection();
                        con.setConnectTimeout(2000);
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                            String line = in.readLine();
                            if (line != null) loc = line.replace(",", ", ");
                        }
                    } catch (Exception e) { LOGGER.error("IP Lookup failed: {}", e.getMessage()); }
                    fullMsg.append(" \\n📍 IP: `").append(ip).append("` (").append(loc).append(")");
                }

                HttpURLConnection con = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
                con.setRequestMethod("POST");
                con.addRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);
                String json = "{\"content\": \"" + fullMsg.toString().replace("\"", "\\\"") + "\"}";
                try (OutputStream os = con.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
                con.getResponseCode();
            } catch (Exception e) { LOGGER.error("Webhook error: {}", e.getMessage()); }
        }).start();
    }
}