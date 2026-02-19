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
        public String ip; // for logging and potential future use

        // Transient fields (RAM only, reset on restart)
        public transient int loginAttempts;
        public transient long lastAttemptTime;
        public transient Helpers.Location ipLocation;
        public transient int ticksUntilKick = -1; // used to delay kick by a few seconds for effect

        public PlayerSession(GameType mode, boolean wasOp, int opLevel, Vec3 joinPos) {
            // not entirely sure if this is necessary
            this.originalMode = (mode != null) ? mode : GameType.SURVIVAL;
            this.wasOp = wasOp;
            this.opLevel = opLevel;
            this.joinTime = System.currentTimeMillis();
            this.lastAttemptTime = System.currentTimeMillis();
            this.joinPos = joinPos;
            this.ipLocation = Helpers.Location.unknown();
        }

        // TODO: fix this initialization issue with file save
        public void resetLockdownTimer() {
            this.lastAttemptTime = System.currentTimeMillis();
            this.loginAttempts = 0;
            this.ipLocation = Helpers.Location.unknown();
            this.ticksUntilKick = -1;
        }

        public void refresh() {
            this.joinTime = System.currentTimeMillis();
            this.loginAttempts = 0;
            this.lastAttemptTime = 0L;
        }

        public void setIpLocationAsync(String ip) {
            // get their location to BM them if they get kicked from server
            // consider using a separate worker since this is slow network task
            SAVE_EXECUTOR.submit(() -> {
                Helpers.Location loc = Helpers.fetchLocationData(ip);
                this.ipLocation = loc;
                this.ip = ip;
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
            saveSessionsToFileAsync();
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

    public static void whitelistIP(String ip) {
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