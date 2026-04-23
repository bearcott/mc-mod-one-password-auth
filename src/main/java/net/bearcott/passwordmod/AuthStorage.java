package net.bearcott.passwordmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;

public class AuthStorage {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("auth_config.properties");
    private static final Path IP_PATH = FabricLoader.getInstance().getConfigDir().resolve("ip_whitelist.txt");
    private static final File SESSIONS_FILE = FabricLoader.getInstance().getConfigDir().resolve("auth_sessions.json")
            .toFile();

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private static final Set<String> whitelistedPairs = new HashSet<>();
    private static final Map<UUID, PlayerSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(
            namedDaemonFactory("one-password-auth/save"));

    private static ThreadFactory namedDaemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public static String serverPassword;
    public static String webhookUrl;
    public static String adminWebhookUrl;
    public static int timeoutSec;

    public static class PlayerSession {
        // Persistent fields (saved to JSON)
        public GameType originalMode;
        public boolean wasOp;
        public int opLevel;
        public long joinTime; // the first join time until successful login
        public Vec3 joinPos;
        public volatile String ip; // written from network worker; read from main thread

        // Transient fields (RAM only, reset on restart).
        // volatile: ipLocation / ip / didFetchLocation are written from the network
        // worker thread and read from the main tick thread.
        public transient int loginAttempts;
        public transient long lastAttemptTime;
        public transient volatile Helpers.Location ipLocation;
        public transient volatile boolean didFetchLocation;
        public transient int ticksUntilKick = -1; // delay kick a few ticks so effects play out

        public PlayerSession(GameType mode, boolean wasOp, int opLevel, Vec3 joinPos) {
            // not entirely sure if this is necessary
            this.originalMode = (mode != null) ? mode : GameType.SURVIVAL;
            this.wasOp = wasOp;
            this.opLevel = opLevel;
            this.joinTime = System.currentTimeMillis();
            this.lastAttemptTime = System.currentTimeMillis();
            this.joinPos = joinPos;
            this.ipLocation = Helpers.Location.unknown();
            this.didFetchLocation = false;
        }

        public void resetLockdownTimer() {
            this.lastAttemptTime = System.currentTimeMillis();
            this.loginAttempts = 0;
            this.ipLocation = Helpers.Location.unknown();
            this.ticksUntilKick = -1;
            this.didFetchLocation = false;
        }

        public void setIpLocationAsync(String ip, ExecutorService workerPool) {
            // Network fetch must not share the single-threaded persistence executor;
            // a slow origin would back up all session writes behind it.
            workerPool.submit(() -> {
                this.ipLocation = Helpers.fetchLocationData(ip);
                this.ip = ip;
                this.didFetchLocation = true;
            });
        }

        public void kickPlayerIfTickDelayed(ServerPlayer player) {
            if (this.ticksUntilKick > 0) {
                this.ticksUntilKick--;
            } else if (this.ticksUntilKick == 0) {
                this.ticksUntilKick = -1;
                player.connection.disconnect(Component.literal(Messages.terminatedDisconnect(this.ipLocation.city())));
            }
        }
    }

    // --------- Initialization ---------

    public static void load() {
        Properties props = new Properties();
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (InputStream is = Files.newInputStream(CONFIG_PATH)) {
                    props.load(is);
                }
            } else {
                props.setProperty("password", "");
                props.setProperty("webhook_url", "");
                props.setProperty("admin_webhook_url", "");
                props.setProperty("timeout_seconds", "180");
                try (OutputStream os = Files.newOutputStream(CONFIG_PATH)) {
                    props.store(os, "Auth Mod Config");
                }
            }

            serverPassword = props.getProperty("password");
            webhookUrl = props.getProperty("webhook_url");
            adminWebhookUrl = props.getProperty("admin_webhook_url");
            timeoutSec = Helpers.numberOrDefault(props.getProperty("timeout_seconds"), 180);

            if (serverPassword == null || serverPassword.isEmpty()) {
                PasswordMod.LOGGER.error(
                        "Auth mod has no password configured at {}. Set 'password=' in the file "
                        + "to a non-empty value — until then no player can authenticate.",
                        CONFIG_PATH);
            }
        } catch (IOException e) {
            PasswordMod.LOGGER.error("Failed to load auth config at {}", CONFIG_PATH, e);
        }

        if (Files.exists(IP_PATH)) {
            try {
                for (String line : Files.readAllLines(IP_PATH)) {
                    // Legacy format was ip-only; require ip|uuid now so the pair is proven.
                    if (line.contains("|"))
                        whitelistedPairs.add(line);
                }
            } catch (IOException e) {
                PasswordMod.LOGGER.error("Failed to load whitelist at {}", IP_PATH, e);
            }
        }
        loadSessionsFromFile();
    }

    // --------- Session Management ---------

    /**
     * Single source of truth for creating or retrieving a session.
     * Prevents overwriting original metadata if a player rejoins.
     */

    public static PlayerSession getOrCreatePendingPlayerSession(UUID uuid, GameType mode, boolean wasOp, int opLevel,
            Vec3 joinPos) {
        PlayerSession ps = new PlayerSession(mode, wasOp, opLevel, joinPos);
        PlayerSession previous = SESSIONS.putIfAbsent(uuid, ps);
        if (previous != null)
            return previous;
        // Durable save before returning: the caller will mutate the player's vanilla state
        // (setGameMode/invuln/blindness) next, so the session file must already reflect the
        // true originalMode. Otherwise a crash between mutation and the next async save
        // would leave vanilla playerdata in lockdown-state but no session on disk, and the
        // next join would snapshot SPECTATOR as originalMode, stranding the player.
        // Route through SAVE_EXECUTOR so this write is serialized with any in-flight async
        // saves — two concurrent FileWriter truncates would interleave and corrupt the JSON.
        saveSessionsToFileBlocking();
        return ps;
    }

    public static PlayerSession getPendingSession(UUID uuid) {
        return SESSIONS.get(uuid);
    }

    public static boolean hasPendingSession(UUID uuid) {
        return SESSIONS.containsKey(uuid);
    }

    public static void removePendingSession(UUID uuid) {
        if (SESSIONS.remove(uuid) != null)
            saveSessionsToFileAsync();
    }

    // --------- Whitelist ---------

    public static void whitelist(String ip, UUID uuid) {
        if (ip == null || uuid == null)
            return;
        if (whitelistedPairs.add(pairKey(ip, uuid))) {
            // Snapshot before handing off to SAVE_EXECUTOR — the HashSet would CME if a
            // second login added to it while Files.write was iterating.
            List<String> snapshot = new ArrayList<>(whitelistedPairs);
            SAVE_EXECUTOR.submit(() -> {
                try {
                    Files.write(IP_PATH, snapshot,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    PasswordMod.LOGGER.error("Failed to write whitelist at {}", IP_PATH, e);
                }
            });
        }
    }

    public static boolean isWhitelisted(String ip, UUID uuid) {
        if (ip == null || uuid == null)
            return false;
        return whitelistedPairs.contains(pairKey(ip, uuid));
    }

    private static String pairKey(String ip, UUID uuid) {
        return ip + "|" + uuid;
    }

    // --------- Persistence ---------

    private static void loadSessionsFromFile() {
        if (!SESSIONS_FILE.exists())
            return;
        try (Reader r = new FileReader(SESSIONS_FILE)) {
            Map<UUID, PlayerSession> loaded = GSON.fromJson(r, new TypeToken<Map<UUID, PlayerSession>>() {
            }.getType());
            if (loaded != null) {
                // Gson bypasses constructors/field initializers via Unsafe, so transient
                // fields load as JVM defaults (ticksUntilKick=0 not -1, ipLocation=null).
                // Reset each to safe defaults before publishing to avoid instant-kick + NPE
                // on the first tick after restart. Also guard originalMode against null
                // (missing-field deserialization) so liftLockdown can't NPE later.
                loaded.values().forEach(ps -> {
                    if (ps.originalMode == null)
                        ps.originalMode = GameType.SURVIVAL;
                    ps.resetLockdownTimer();
                });
                SESSIONS.putAll(loaded);
            }
        } catch (Exception e) {
            PasswordMod.LOGGER.error("Failed to load sessions from {}", SESSIONS_FILE, e);
        }
    }

    public static void saveSessionsToFile() {
        try (Writer w = new FileWriter(SESSIONS_FILE)) {
            GSON.toJson(SESSIONS, w);
        } catch (IOException e) {
            PasswordMod.LOGGER.error("Failed to save sessions to {}", SESSIONS_FILE, e);
        }
    }

    public static void saveSessionsToFileAsync() {
        SAVE_EXECUTOR.submit(AuthStorage::saveSessionsToFile);
    }

    // Submit + await: serializes with other SAVE_EXECUTOR writes so two threads can't both
    // truncate-and-write the same file. Callers get a happens-before guarantee that the
    // session is on disk before this method returns.
    public static void saveSessionsToFileBlocking() {
        try {
            SAVE_EXECUTOR.submit(AuthStorage::saveSessionsToFile).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            PasswordMod.LOGGER.error("Failed sync save of sessions", e);
        }
    }

    // --------- Shutdown ---------

    public static void shutdown() {
        // Let queued async saves drain, then do a final sync save as a safety net
        // so anything queued during shutdown still lands on disk.
        SAVE_EXECUTOR.shutdown();
        try {
            SAVE_EXECUTOR.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveSessionsToFile();
    }
}