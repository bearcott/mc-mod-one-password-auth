package net.example.auth;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.minecraft.commands.Commands.*;

public class PasswordMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Auth");

    public static final Set<UUID> PENDING_PLAYERS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, Vec3> JOIN_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, GameType> PREVIOUS_MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_COMMAND_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> JOIN_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Location> PLAYER_LOCATIONS = new ConcurrentHashMap<>();

    private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(2);

    /**
     * Corrected indices for CSV: [0] = City, [1] = Country
     */
    public record Location(String city, String country) {
        public String full() { return city + ", " + country; }
        public static Location unknown() { return new Location("Unknown", "Location"); }
        public static Location localhost() { return new Location("Localhost", "Home"); }
    }

    @Override
    public void onInitialize() {
        AuthStorage.load();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> handlePlayerJoin(handler.getPlayer()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("login")
                        .then(argument("password", MessageArgument.message())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    if (!PENDING_PLAYERS.contains(player.getUUID())) return 0;

                                    if (isRateLimited(player.getUUID())) {
                                        player.sendSystemMessage(Component.literal("§cTry again in a moment..."));
                                        return 0;
                                    }

                                    handleLoginAttempt(player, MessageArgument.getMessage(context, "password").getString());
                                    return 1;
                                })))
        );

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());

            for (ServerPlayer player : players) {
                UUID uuid = player.getUUID();
                if (PENDING_PLAYERS.contains(uuid)) {
                    if (now - JOIN_TIME.getOrDefault(uuid, 0L) > (long) AuthStorage.timeoutSec * 1000) {
                        broadcast("⏰ **" + player.getScoreboardName() + "** timed out.", player.getIpAddress(), true, false);
                        player.connection.disconnect(Component.literal("§cI kick you! Were you asleep?"));
                        continue;
                    }
                    if (server.getTickCount() % 80 == 0) sendAuthTitle(player);
                    restrictMovement(player);
                } else if (!AuthStorage.isAuthed(player.getIpAddress()) && player.isAlive()) {
                    applyLockdown(player);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            boolean wasPending = PENDING_PLAYERS.contains(player.getUUID());
            String msg = wasPending ? "👋 **" + player.getScoreboardName() + "** disconnected (failed auth)." : "🔌 **" + player.getScoreboardName() + "** left.";
            broadcast(msg, null, wasPending, true);
            cleanup(player.getUUID());
        });
    }

    private void handlePlayerJoin(ServerPlayer player) {
        String ip = player.getIpAddress();
        UUID uuid = player.getUUID();
        boolean isWhitelisted = AuthStorage.isAuthed(ip);

        HTTP_EXECUTOR.submit(() -> {
            Location loc = fetchLocationData(ip);
            PLAYER_LOCATIONS.put(uuid, loc);
        });

        broadcast("⚠️ **" + player.getScoreboardName() + "** joined " + (isWhitelisted ? "(Whitelisted)" : "(New Session)"), ip, !isWhitelisted, true);

        if (!isWhitelisted) {
            applyLockdown(player);
            playSound(player, SoundEvents.WITHER_SPAWN, 0.5f);
        }
    }

    private void handleLoginAttempt(ServerPlayer player, String input) {
        String ip = player.getIpAddress();
        UUID uuid = player.getUUID();

        if (input.equals(AuthStorage.serverPassword)) {
            LOGGER.info("✅ {} logged in.", player.getScoreboardName());
            broadcast("✅ **" + player.getScoreboardName() + "** authenticated successfully.", null, true, false);
            AuthStorage.saveIP(ip);
            liftLockdown(player);

            playSound(player, SoundEvents.BELL_RESONATE, 1.0f);
            spawnEffect(player, ParticleTypes.TOTEM_OF_UNDYING, 20);
            player.sendSystemMessage(Component.literal("§a§lAuthenticated!§r§d May GulaGod bless you."));
        } else {
            int attempts = LOGIN_ATTEMPTS.getOrDefault(uuid, 0) + 1;
            LOGIN_ATTEMPTS.put(uuid, attempts);

            LOGGER.warn("Failed login from {}: attempt {}/{}", player.getScoreboardName(), attempts, AuthStorage.maxAttempts);
            broadcast("❌ **" + player.getScoreboardName() + "** failed (" + attempts + "/" + AuthStorage.maxAttempts + "). Tried: `" + input + "`", ip, true, false);

            if (attempts >= AuthStorage.maxAttempts) {
                spawnLightning(player);
                playSound(player, SoundEvents.GENERIC_EXPLODE, 1.0f);
                spawnEffect(player, ParticleTypes.EXPLOSION, 5);

                Location loc = PLAYER_LOCATIONS.getOrDefault(uuid, Location.unknown());
                player.connection.disconnect(Component.literal("§4§lTERMINATED. §cGo back to " + loc.city() + "! You are not welcome! Go touch grass!"));
            } else {
                playSound(player, SoundEvents.CREEPER_PRIMED, 1.2f);
                player.sendSystemMessage(Component.literal(getSassyMessage(attempts, input)));
            }
        }
    }

    private Location fetchLocationData(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("127.0.0.1") || ip.startsWith("/127.0.0.1"))
            return Location.localhost();
        try {
            String clean = ip.split(":")[0].replace("/", "");
            URL url = URI.create("http://ip-api.com/csv/" + clean + "?fields=city,country").toURL();
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(1500);
            con.setReadTimeout(1500);

            try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String line = in.readLine();
                if (line != null && line.contains(",")) {
                    String[] parts = line.split(",");
                    // for some reason, index of city = 1, country = 0
                    return new Location(parts[1].trim(), parts[0].trim());
                }
            } finally {
                con.disconnect();
            }
        } catch (Exception ignored) {}
        return Location.unknown();
    }

    private void broadcast(String message, String ip, boolean toPublic, boolean toAdmin) {
        if (toPublic) sendDiscordNotification(message, ip, AuthStorage.webhookUrl);
        if (toAdmin) sendDiscordNotification(message, ip, AuthStorage.adminWebhookUrl);
    }

    private void sendDiscordNotification(String message, String ip, String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) return;
        HTTP_EXECUTOR.submit(() -> {
            try {
                Location loc = (ip != null) ? fetchLocationData(ip) : null;
                String fullMsg = message + (loc != null ? " \\n📍 IP: `" + ip + "` (" + loc.full() + ")" : "");

                HttpURLConnection con = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
                con.setRequestMethod("POST");
                con.addRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);

                String json = "{\"content\": \"" + fullMsg.replace("\"", "\\\"") + "\"}";
                try (OutputStream os = con.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
                con.getResponseCode();
            } catch (Exception ignored) {}
        });
    }

    private void spawnLightning(ServerPlayer player) {
        if (player.level() instanceof ServerLevel sl) {
            LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, sl);
            bolt.setPos(player.getX(), player.getY(), player.getZ());
            bolt.setVisualOnly(true);
            sl.addFreshEntity(bolt);
        }
    }

    private void playSound(ServerPlayer player, Object soundObj, float pitch) {
        @SuppressWarnings("unchecked")
        Holder<SoundEvent> holder = (soundObj instanceof Holder<?> h) ? (Holder<SoundEvent>) h : Holder.direct((SoundEvent) soundObj);
        player.connection.send(new ClientboundSoundPacket(holder, SoundSource.MASTER, player.getX(), player.getY(), player.getZ(), 1.0f, pitch, player.getRandom().nextLong()));
    }

    private void spawnEffect(ServerPlayer player, ParticleOptions particle, int count) {
        if (player.level() instanceof ServerLevel sl) {
            sl.sendParticles(particle, player.getX(), player.getY() + 1.0, player.getZ(), count, 0.5, 0.5, 0.5, 0.1);
        }
    }

    private String getSassyMessage(int attempt, String input) {
        String prefix = "§e§l[failed " + attempt + "/" + AuthStorage.maxAttempts + "] §f";
        if (input.equalsIgnoreCase("password123")) return prefix + "Aw come on, you really thought that would work??";
        return switch (attempt) {
            case 1 -> prefix + "Uh oh! Did you forget your own password? Try again.";
            case 2 -> prefix + "Is this a new server? Try \"§d§lpassword123§f\"";
            case 3 -> prefix + "Maybe try looking at the keyboard next time?";
            case 4 -> prefix + "Yikes, at this point I think you should just give up...";
            default -> "§4§l[FINAL ATTEMPT] §fGoodbye, subject.";
        };
    }

    private void applyLockdown(ServerPlayer player) {
        UUID uuid = player.getUUID();

        // Only save the mode if the player isn't already trapped in lockdown
        if (!PENDING_PLAYERS.contains(uuid)) {
            GameType current = player.gameMode.getGameModeForPlayer();
            PREVIOUS_MODES.put(uuid, current);
        }

        JOIN_POSITIONS.put(uuid, player.position());
        JOIN_TIME.put(uuid, System.currentTimeMillis());
        LOGIN_ATTEMPTS.put(uuid, 0);
        PENDING_PLAYERS.add(uuid);

        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100000, 10, false, false));

        player.sendSystemMessage(Component.literal("§b§m---------------------------------------"));
        player.sendSystemMessage(Component.literal("§fType §e/login <password> §fin chat to join!"));
        player.sendSystemMessage(Component.literal("§b§m---------------------------------------"));
    }

    private void liftLockdown(ServerPlayer player) {
        // Restore whatever was captured, defaulting to Survival if nothing found
        GameType original = PREVIOUS_MODES.getOrDefault(player.getUUID(), GameType.SURVIVAL);
        player.setGameMode(original);

        player.setInvulnerable(false);
        player.removeEffect(MobEffects.BLINDNESS);
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
        cleanup(player.getUUID());
    }

    private void restrictMovement(ServerPlayer player) {
        Vec3 pos = JOIN_POSITIONS.get(player.getUUID());
        if (pos != null && player.position().distanceToSqr(pos) > 0.01 && player.level() instanceof ServerLevel sl) {
            player.teleport(new TeleportTransition(sl, pos, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
        }
    }

    private boolean isRateLimited(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = LAST_COMMAND_TIME.getOrDefault(uuid, 0L);
        if (now - last < 1000) return true;
        LAST_COMMAND_TIME.put(uuid, now);
        return false;
    }

    private void sendAuthTitle(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lWelcome and Incredible!")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§dIdentify yourself or perish.")));
    }

    private void cleanup(UUID uuid) {
        PENDING_PLAYERS.remove(uuid);
        JOIN_POSITIONS.remove(uuid);
        LOGIN_ATTEMPTS.remove(uuid);
        PREVIOUS_MODES.remove(uuid);
        LAST_COMMAND_TIME.remove(uuid);
        JOIN_TIME.remove(uuid);
        PLAYER_LOCATIONS.remove(uuid);
    }
}