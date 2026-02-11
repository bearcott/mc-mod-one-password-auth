package net.example.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
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

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class PasswordMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Auth");
    public static final Integer MAX_ATTEMPTS = 7;

    public static final Set<UUID> PENDING_PLAYERS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, Vec3> JOIN_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_COMMAND_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> JOIN_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CUSTOM_TIMEOUT_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Location> PLAYER_LOCATIONS = new ConcurrentHashMap<>();

    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(2);

    public record Location(String city, String country) {
        public String full() { return city + ", " + country; }
        public static Location unknown() { return new Location("Unknown", "Location"); }
        public static Location localhost() { return new Location("Localhost", "Home"); }
    }

    @Override
    public void onInitialize() {
        AuthStorage.load();
        AuthSessionManager.load();

        ServerStatusLogger.register();

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AuthSessionManager.save());

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
                String ip = player.getIpAddress();

                if (AuthStorage.isAuthed(ip)) {
                    if (PENDING_PLAYERS.contains(uuid)) liftLockdown(player);
                    continue;
                }

                if (PENDING_PLAYERS.contains(uuid)) {
                    long limit = CUSTOM_TIMEOUT_MS.getOrDefault(uuid, (long) AuthStorage.timeoutSec * 1000);
                    if (now - JOIN_TIME.getOrDefault(uuid, 0L) > limit) {
                        broadcast("⏰ **" + player.getScoreboardName() + "** timed out.", null, true, false);
                        player.connection.disconnect(Component.literal("§cHello? Were you asleep? I kick you!"));
                        continue;
                    }
                    if (server.getTickCount() % 60 == 0) sendAuthTitle(player);
                    restrictMovement(player);
                } else if (player.isAlive()) {
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

        WORKER_POOL.submit(() -> {
            Location loc = fetchLocationData(ip);
            PLAYER_LOCATIONS.put(uuid, loc);
        });

        String msg = "⚠️ **" + player.getScoreboardName() + "** joined " + (isWhitelisted ? "(Whitelisted)" : "(New Session)");
        broadcast(msg, ip, !isWhitelisted, true);

        if (!isWhitelisted) {
            applyLockdown(player);
            playSound(player, SoundEvents.WITHER_SPAWN, 0.5f);
        }
    }

    private void handleLoginAttempt(ServerPlayer player, String input) {
        String ip = player.getIpAddress();
        UUID uuid = player.getUUID();

        // Feature: Reset timeout to 2 minutes on every attempt
        JOIN_TIME.put(uuid, System.currentTimeMillis());
        CUSTOM_TIMEOUT_MS.put(uuid, 120 * 1000L);

        if (input.equals(AuthStorage.serverPassword)) {
            LOGGER.info("✅ {} logged in.", player.getScoreboardName());
            broadcast("✅ **" + player.getScoreboardName() + "** authenticated successfully.", null, true, false);
            AuthStorage.saveIP(ip);
            liftLockdown(player);

            playSound(player, SoundEvents.PLAYER_LEVELUP, 1.0f);
            spawnEffect(player, ParticleTypes.TOTEM_OF_UNDYING, 20);
            player.sendSystemMessage(Component.literal("§a§lAuthenticated!§r May GulaGod bless you."));
        } else {
            int attempts = LOGIN_ATTEMPTS.getOrDefault(uuid, 0) + 1;
            LOGIN_ATTEMPTS.put(uuid, attempts);

            LOGGER.warn("Failed login from {}: attempt {}/{}", player.getScoreboardName(), attempts, MAX_ATTEMPTS);
            String msg = "❌ **" + player.getScoreboardName() + "** failed (" + attempts + "/" + MAX_ATTEMPTS + "). Tried: `" + input + "`";
            broadcast(msg, null, true, false);

            if (attempts >= MAX_ATTEMPTS) {
                spawnLightning(player);
                playSound(player, SoundEvents.DRAGON_FIREBALL_EXPLODE, 1.0f);
                spawnEffect(player, ParticleTypes.EXPLOSION, 5);

                Location loc = PLAYER_LOCATIONS.getOrDefault(uuid, Location.unknown());
                player.connection.disconnect(Component.literal("§4§lTERMINATED. §cGo back to " + loc.city() + "! You are not welcome! Go touch grass!"));
            } else {
                playSound(player, SoundEvents.CREEPER_PRIMED, 1.2f);
                player.sendSystemMessage(Component.literal(getSassyMessage(attempts, input)));
            }
        }
    }

    // get rid of warning 'ServerLevel' used without 'try'-with-resources statement
    @SuppressWarnings("resource")
    private void applyLockdown(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (PENDING_PLAYERS.contains(uuid)) return;

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

            if (!AuthSessionManager.hasData(uuid)) {
                AuthSessionManager.savePlayerState(uuid, player.gameMode.getGameModeForPlayer(), isOp, level);
            }
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

    // get rid of warning 'ServerLevel' used without 'try'-with-resources statement
    @SuppressWarnings("resource")
    private void liftLockdown(ServerPlayer player) {
        UUID uuid = player.getUUID();
        AuthSessionManager.SessionData data = AuthSessionManager.getData(uuid);

        player.setGameMode((data != null) ? data.mode : GameType.SURVIVAL);

        if (data != null && data.wasOp && player.level() instanceof ServerLevel sl) {
            var profile = player.getGameProfile();
            var nameAndId = new NameAndId(profile.id(), profile.name());

            // 1. Get the Enum constant using the saved ID
            PermissionLevel pLevel = PermissionLevel.byId(data.opLevel());

            // 2. Use the static factory method found in your source code
            LevelBasedPermissionSet pSet = LevelBasedPermissionSet.forLevel(pLevel);

            // 3. Create the entry using the exact types the constructor expects
            ServerOpListEntry entry = new ServerOpListEntry(nameAndId, pSet, false);

            // 4. Add to the server's OpList
            sl.getServer().getPlayerList().getOps().add(entry);

            // refresh server
            sl.getServer().getPlayerList().sendPlayerPermissionLevel(player);
            sl.getServer().getCommands().sendCommands(player);
            player.onUpdateAbilities();

            player.sendSystemMessage(Component.literal("§b[Security]§r Welcome back comrade " + nameAndId.name() + "! Lvl " + data.opLevel() + " OP restored."));
        }

        AuthSessionManager.removeData(uuid);
        player.setInvulnerable(false);
        player.removeEffect(MobEffects.BLINDNESS);

        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("")));

        cleanup(uuid);
    }

    // get rid of warning 'ServerLevel' used without 'try'-with-resources statement
    @SuppressWarnings("resource")
    private void restrictMovement(ServerPlayer player) {
        Vec3 pos = JOIN_POSITIONS.get(player.getUUID());
        if (pos != null && player.position().distanceToSqr(pos) > 0.01 && player.level() instanceof ServerLevel sl) {
            player.teleport(new TeleportTransition(sl, pos, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
        }
    }

    private void sendAuthTitle(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lWelcome and Incredible!")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7Identify yourself or perish.")));
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§eType §f/login <password> §eto join")));
    }

    private boolean check67Answer(String input) {
        String clean = input.toLowerCase().replaceAll("[^a-z0-9]", "");
        return clean.contains("67") || clean.contains("sixtyseven") || clean.contains("sixseven");
    }

    private String getSassyMessage(int attempt, String input) {
        String prefix = "§e§l[failed " + attempt + "/" + MAX_ATTEMPTS + "] §f";
        return switch (attempt) {
            case 1 -> prefix + "Uh oh! Did you forget your own password? Try again.";
            case 2 -> prefix + "Is this a new server? Try \"§d§lPassWord123§f\"";
            case 3 -> {
                if (input.equals("PassWord123")) {
                    yield prefix + "Aw come on, you really thought that would work??";
                } else {
                    yield prefix + "Maybe try looking at the keyboard next time?";
                }
            }
            case 4 -> "§a§lSuccess!§r Please solve the captcha: §d§lWhat is 48+19?§f";
            case 5 -> {
                if (check67Answer(input)) {
                    yield prefix + "You thought that was gonna get you in?? Math??";
                } else {
                    yield prefix + "...Do you not know how to do addition or something?";
                }
            }
            case 6 -> prefix + "You're a failure. That's so sad.";
            default -> prefix;
        };
    }

    // --- Sidecar Storage ---
    public static class AuthSessionManager {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final File FILE = FabricLoader.getInstance().getConfigDir().resolve("auth_sessions.json").toFile();
        private static final Map<UUID, SessionData> DATA_MAP = new ConcurrentHashMap<>();

        public record SessionData(GameType mode, boolean wasOp, int opLevel) {}

        public static void savePlayerState(UUID uuid, GameType mode, boolean wasOp, int opLevel) {
            DATA_MAP.put(uuid, new SessionData(mode, wasOp, opLevel));
            CompletableFuture.runAsync(AuthSessionManager::save);
        }

        public static SessionData getData(UUID uuid) { return DATA_MAP.get(uuid); }
        public static boolean hasData(UUID uuid) { return DATA_MAP.containsKey(uuid); }
        public static void removeData(UUID uuid) { DATA_MAP.remove(uuid); CompletableFuture.runAsync(AuthSessionManager::save); }

        public static void load() {
            if (!FILE.exists()) return;
            try (Reader r = new FileReader(FILE)) {
                Map<UUID, SessionData> loaded = GSON.fromJson(r, new TypeToken<Map<UUID, SessionData>>(){}.getType());
                if (loaded != null) DATA_MAP.putAll(loaded);
            } catch (Exception e) { LOGGER.error("Load failed", e); }
        }

        public static void save() {
            try (Writer w = new FileWriter(FILE)) { GSON.toJson(DATA_MAP, w); } catch (IOException e) { LOGGER.error("Save failed", e); }
        }
    }

    // --- Helpers & Cleanups ---
    private boolean isRateLimited(UUID uuid) {
        long now = System.currentTimeMillis();
        if (now - LAST_COMMAND_TIME.getOrDefault(uuid, 0L) < 1000) return true;
        LAST_COMMAND_TIME.put(uuid, now);
        return false;
    }

    private void cleanup(UUID uuid) {
        PENDING_PLAYERS.remove(uuid); JOIN_POSITIONS.remove(uuid); LOGIN_ATTEMPTS.remove(uuid);
        LAST_COMMAND_TIME.remove(uuid); JOIN_TIME.remove(uuid); CUSTOM_TIMEOUT_MS.remove(uuid);
        PLAYER_LOCATIONS.remove(uuid);
    }

    private void broadcast(String message, String ip, boolean toPub, boolean toAdmin) {
        if (toPub) sendDiscordNotification(message, ip, AuthStorage.webhookUrl);
        if (toAdmin) sendDiscordNotification(message, ip, AuthStorage.adminWebhookUrl);
    }

    private void sendDiscordNotification(String message, String ip, String target) {
        if (target == null || target.isEmpty()) return;
        WORKER_POOL.submit(() -> {
            try {
                Location loc = (ip != null) ? fetchLocationData(ip) : null;
                String body = "{\"content\": \"" + (message + (loc != null ? " \\n📍 IP: `" + ip + "` (" + loc.full() + ")" : "")).replace("\"", "\\\"") + "\"}";
                HttpURLConnection con = (HttpURLConnection) URI.create(target).toURL().openConnection();
                con.setRequestMethod("POST");
                con.addRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);
                try (OutputStream os = con.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                con.getResponseCode();
            } catch (Exception ignored) {}
        });
    }

    // get rid of warning 'ServerLevel' used without 'try'-with-resources statement
    @SuppressWarnings("resource")
    private void spawnLightning(ServerPlayer player) {
        if (player.level() instanceof ServerLevel sl) {
            LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, sl);
            bolt.setPos(player.getX(), player.getY(), player.getZ());
            bolt.setVisualOnly(true);
            sl.addFreshEntity(bolt);
        }
    }

    private void playSound(ServerPlayer p, SoundEvent s, float pitch) {
        p.connection.send(new ClientboundSoundPacket(Holder.direct(s), SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, pitch, p.getRandom().nextLong()));
    }

    // get rid of warning 'ServerLevel' used without 'try'-with-resources statement
    @SuppressWarnings("resource")
    private void spawnEffect(ServerPlayer p, ParticleOptions part, int count) {
        if (p.level() instanceof ServerLevel sl) sl.sendParticles(part, p.getX(), p.getY() + 1.0, p.getZ(), count, 0.5, 0.5, 0.5, 0.1);
    }

    private Location fetchLocationData(String ip) {
        if (ip == null || ip.isEmpty() || ip.contains("127.0.0.1")) return Location.localhost();
        try {
            URL url = URI.create("http://ip-api.com/csv/" + ip.split(":")[0].replace("/", "") + "?fields=city,country").toURL();
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(1500);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String l = r.readLine();
                if (l != null && l.contains(",")) {
                    String[] pts = l.split(",");
                    return new Location(pts[1].trim(), pts[0].trim());
                }
            } finally { c.disconnect(); }
        } catch (Exception ignored) {}
        return Location.unknown();
    }
}