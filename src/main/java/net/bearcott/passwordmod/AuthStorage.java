package net.bearcott.passwordmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.bearcott.passwordmod.util.Helpers;
import net.fabricmc.loader.api.FabricLoader;
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

    private static final Set<String> whitelistedIPs = new HashSet<>();
    private static final Map<UUID, PlayerSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor();

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

        // Transient fields (RAM only, reset on restart)
        public transient int loginAttempts;
        public transient long lastAttemptTime;
        public transient Helpers.Location location;

        public PlayerSession(GameType mode, boolean wasOp, int opLevel, Vec3 joinPos) {
            this.originalMode = mode;
            this.wasOp = wasOp;
            this.opLevel = opLevel;
            this.joinTime = System.currentTimeMillis();
            this.lastAttemptTime = System.currentTimeMillis();
            this.joinPos = joinPos;
        }

        public void refresh() {
            this.joinTime = System.currentTimeMillis();
            this.loginAttempts = 0;
            this.lastAttemptTime = 0L;
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
                props.setProperty("password", "change_me");
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
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (Files.exists(IP_PATH)) {
            try {
                whitelistedIPs.addAll(Files.readAllLines(IP_PATH));
            } catch (IOException e) {
                e.printStackTrace();
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
        return SESSIONS.computeIfAbsent(uuid, k -> {
            PlayerSession ps = new PlayerSession(mode, wasOp, opLevel, joinPos);
            // saveSessionsToFileAsync();
            return ps;
        });
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

    public static void saveIP(String ip) {
        if (whitelistedIPs.add(ip)) {
            try {
                Files.write(IP_PATH, whitelistedIPs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean isWhitelisted(String ip) {
        return whitelistedIPs.contains(ip);
    }

    // --------- Persistence ---------

    private static void loadSessionsFromFile() {
        if (!SESSIONS_FILE.exists())
            return;
        try (Reader r = new FileReader(SESSIONS_FILE)) {
            Map<UUID, PlayerSession> loaded = GSON.fromJson(r, new TypeToken<Map<UUID, PlayerSession>>() {
            }.getType());
            if (loaded != null)
                SESSIONS.putAll(loaded);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveSessionsToFile() {
        try (Writer w = new FileWriter(SESSIONS_FILE)) {
            GSON.toJson(SESSIONS, w);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveSessionsToFileAsync() {
        SAVE_EXECUTOR.submit(AuthStorage::saveSessionsToFile);
    }
}