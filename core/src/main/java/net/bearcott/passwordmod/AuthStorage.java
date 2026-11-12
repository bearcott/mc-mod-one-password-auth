package net.bearcott.passwordmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.bearcott.passwordmod.util.Helpers;
import net.bearcott.passwordmod.util.Messages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;

public class AuthStorage {
    // Set by load(): Fabric's config/ folder, or the plugin's data folder on Paper. The file
    // names are the same on both, so the files can be copied across as-is.
    private static Path CONFIG_PATH;
    private static Path IP_PATH;
    private static File SESSIONS_FILE;

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    // Upper bound on simultaneously-pending (unauthenticated) sessions kept in memory and on disk.
    // Without it an attacker joining with many unique usernames/UUIDs grows SESSIONS (and the JSON
    // file) without bound. Evicting a pending session is safe: the player's real state is already
    // restored into their playerdata on disconnect, so a rejoin just re-snapshots correctly.
    public static final int MAX_PENDING_SESSIONS = 2000;

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
    public static String loginTitle;
    public static String loginDescription;
    public static int timeoutSec;
    // Base URL for best-effort IP geolocation. Blank disables third-party IP lookups entirely.
    public static String geolocationUrl;

    public static class PlayerSession {
        // Persistent fields (saved to JSON)
        public GameMode originalMode;
        public boolean wasOp;
        public int opLevel;
        public long joinTime; // the first join time until successful login
        public Pos joinPos;
        public volatile String ip; // written from network worker; read from main thread

        // Transient fields (RAM only, reset on restart).
        // volatile: ipLocation / ip / didFetchLocation are written from the network
        // worker thread and read from the main tick thread.
        public transient int loginAttempts;
        public transient long lastAttemptTime;
        public transient volatile Helpers.Location ipLocation;
        public transient volatile boolean didFetchLocation;
        public transient int ticksUntilKick = -1; // delay kick a few ticks so effects play out

        public PlayerSession(GameMode mode, boolean wasOp, int opLevel, Pos joinPos) {
            // not entirely sure if this is necessary
            this.originalMode = (mode != null) ? mode : GameMode.SURVIVAL;
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

        public void kickPlayerIfTickDelayed(AuthPlayer player) {
            if (this.ticksUntilKick > 0) {
                this.ticksUntilKick--;
            } else if (this.ticksUntilKick == 0) {
                this.ticksUntilKick = -1;
                player.kick(String.format(Messages.KICK_TERMINATED_FMT, this.ipLocation.city()));
            }
        }
    }

    // --------- Initialization ---------

    public static void load(Path configDir) {
        CONFIG_PATH = configDir.resolve("one_password_auth_config.properties");
        IP_PATH = configDir.resolve("one_password_auth_ip_whitelist.txt");
        SESSIONS_FILE = configDir.resolve("one_password_auth_sessions.json").toFile();

        Properties props = new Properties();
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            AuthCore.LOGGER.error("Failed to create config folder {}", configDir, e);
        }
        try {
            if (!Files.exists(CONFIG_PATH))
                writeDefaultConfig();
            try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                props.load(r);
            }

            serverPassword = props.getProperty("password", "");
            webhookUrl = props.getProperty("webhook_url", "");
            adminWebhookUrl = props.getProperty("admin_webhook_url", "");
            loginTitle = props.getProperty("login_title", Messages.LOGIN_DEFAULT_TITLE);
            loginDescription = props.getProperty("login_description", Messages.LOGIN_DEFAULT_DESCRIPTION);
            timeoutSec = Helpers.numberOrDefault(props.getProperty("timeout_seconds"), 180);
            geolocationUrl = props.getProperty("geolocation_url", "https://ipwho.is/");

            if (serverPassword.isEmpty()) {
                AuthCore.LOGGER.error(
                        "Auth mod has no password configured at {}. Set 'password=' in the file "
                        + "to a non-empty value — until then no player can authenticate.",
                        CONFIG_PATH);
            }
        } catch (IOException e) {
            AuthCore.LOGGER.error("Failed to load auth config at {}", CONFIG_PATH, e);
        }

        if (Files.exists(IP_PATH)) {
            try {
                for (String line : Files.readAllLines(IP_PATH)) {
                    // Legacy format was ip-only; require ip|uuid now so the pair is proven.
                    if (line.contains("|"))
                        whitelistedPairs.add(line);
                }
            } catch (IOException e) {
                AuthCore.LOGGER.error("Failed to load whitelist at {}", IP_PATH, e);
            }
        }
        loadSessionsFromFile();
    }

    private static void writeDefaultConfig() throws IOException {
        // Written manually (not via Properties.store) so per-key comments survive for
        // admins reading the file. UTF-8 so § color codes round-trip through editors.
        String generatedPassword = Helpers.generateDefaultPassword();
        String content = """
                # One Password Auth — server config
                #
                # password
                #   Required. The shared password everyone uses with /login.
                #   A random phonetic password is generated on first run — change it below if you want.
                #
                # webhook_url
                #   Public Discord channel (player activity feed).
                #   Logs: joins, login attempts (including what the player typed),
                #         auth successes, timeouts, advancements by authed players.
                #
                # admin_webhook_url
                #   Admin/security Discord channel.
                #   Logs: server start / stop / crash, all connect & disconnect events,
                #         unauthorized advancements (advancements earned while unauthed).
                #
                # login_title
                #   Big title shown on the lockdown screen. Supports § color codes.
                #
                # login_description
                #   Subtitle under the title. Supports § color codes.
                #
                # timeout_seconds
                #   Kick unauthenticated players after this many seconds of inactivity.
                #
                # geolocation_url
                #   Base URL for best-effort IP geolocation (shown in Discord logs). HTTPS.
                #   Leave blank to disable third-party IP lookups entirely.

                password=%s
                webhook_url=
                admin_webhook_url=
                login_title=§6Welcome and Incredible!
                login_description=§7Identify yourself or perish.
                timeout_seconds=180
                geolocation_url=https://ipwho.is/
                """.formatted(generatedPassword);
        Files.writeString(CONFIG_PATH, content, StandardCharsets.UTF_8);
        // Do NOT log the generated password: server logs are often shared/retained, which would
        // leak the shared secret. Point admins at the config file to view or change it instead.
        AuthCore.LOGGER.info(
                "No config file found — generated a random default /login password. "
                + "Open {} to view or change it.",
                CONFIG_PATH);
    }

    // --------- Session Management ---------

    /**
     * Single source of truth for creating or retrieving a session.
     * Prevents overwriting original metadata if a player rejoins.
     */

    public static PlayerSession getOrCreatePendingPlayerSession(UUID uuid, GameMode mode, boolean wasOp, int opLevel,
            Pos joinPos) {
        PlayerSession ps = new PlayerSession(mode, wasOp, opLevel, joinPos);
        PlayerSession previous = SESSIONS.putIfAbsent(uuid, ps);
        if (previous != null)
            return previous;

        // A genuinely new pending session: keep the store bounded so a flood of unique UUIDs
        // can't grow memory/the JSON file without limit. Evict the oldest pending session(s)
        // (by joinTime) once over the cap — safe because a disconnect already restored each
        // player's real state into their playerdata, so a rejoin re-snapshots correctly.
        if (SESSIONS.size() > MAX_PENDING_SESSIONS)
            evictOldestPendingSessions(uuid);

        // Persist asynchronously (non-blocking). We deliberately do NOT block the main server
        // thread on disk I/O here: saveSessionsToFileBlocking() called .get() on every join,
        // which a join-flood could weaponise into a trivial main-thread-stall DoS. We accept
        // the tiny crash-window-vs-correctness tradeoff (a crash in the narrow gap between this
        // call and the async write landing could lose the just-created session). The
        // single-threaded SAVE_EXECUTOR still serializes writes, so concurrent truncates can't
        // interleave and corrupt the JSON.
        saveSessionsToFileAsync();
        return ps;
    }

    /** Evict oldest-by-joinTime pending sessions until back within MAX_PENDING_SESSIONS. */
    private static void evictOldestPendingSessions(UUID keep) {
        while (SESSIONS.size() > MAX_PENDING_SESSIONS) {
            UUID oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<UUID, PlayerSession> e : SESSIONS.entrySet()) {
                if (e.getKey().equals(keep))
                    continue;
                if (e.getValue().joinTime < oldestTime) {
                    oldestTime = e.getValue().joinTime;
                    oldest = e.getKey();
                }
            }
            if (oldest == null)
                break;
            SESSIONS.remove(oldest);
        }
    }

    public static PlayerSession getPendingSession(UUID uuid) {
        return SESSIONS.get(uuid);
    }

    /** Test seam: clear in-memory state so tests in a shared JVM don't contaminate each other. */
    static void clearInMemoryStateForTests() {
        SESSIONS.clear();
        whitelistedPairs.clear();
    }

    /** Test seam: number of pending sessions currently held in memory. */
    static int pendingSessionCount() {
        return SESSIONS.size();
    }

    /**
     * Test seam: block until all queued async saves have run. Tests call this before their
     * {@code @TempDir} is deleted so a background write can't race the directory cleanup (and so a
     * trailing save can't clobber the next test's file, since SESSIONS_FILE is static).
     */
    static void flushSavesForTests() {
        try {
            SAVE_EXECUTOR.submit(() -> {
            }).get();
        } catch (Exception ignored) {
        }
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
                    AuthCore.LOGGER.error("Failed to write whitelist at {}", IP_PATH, e);
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
                // A previously-bloated file must not re-bloat memory on boot: if it holds more
                // than the cap, keep only the newest MAX_PENDING_SESSIONS (by joinTime).
                if (loaded.size() > MAX_PENDING_SESSIONS)
                    loaded = pruneToNewest(loaded);
                // Gson bypasses constructors/field initializers via Unsafe, so transient
                // fields load as JVM defaults (ticksUntilKick=0 not -1, ipLocation=null).
                // Reset each to safe defaults before publishing to avoid instant-kick + NPE
                // on the first tick after restart. Also guard originalMode against null
                // (missing-field deserialization) so liftLockdown can't NPE later.
                loaded.values().forEach(ps -> {
                    if (ps.originalMode == null)
                        ps.originalMode = GameMode.SURVIVAL;
                    ps.resetLockdownTimer();
                });
                SESSIONS.putAll(loaded);
            }
        } catch (Exception e) {
            AuthCore.LOGGER.error("Failed to load sessions from {}", SESSIONS_FILE, e);
        }
    }

    /** Keep only the newest MAX_PENDING_SESSIONS entries (by joinTime), dropping the oldest. */
    private static Map<UUID, PlayerSession> pruneToNewest(Map<UUID, PlayerSession> loaded) {
        Map<UUID, PlayerSession> kept = new LinkedHashMap<>();
        loaded.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().joinTime, a.getValue().joinTime))
                .limit(MAX_PENDING_SESSIONS)
                .forEach(e -> kept.put(e.getKey(), e.getValue()));
        return kept;
    }

    public static void saveSessionsToFile() {
        try (Writer w = new FileWriter(SESSIONS_FILE)) {
            GSON.toJson(SESSIONS, w);
        } catch (IOException e) {
            AuthCore.LOGGER.error("Failed to save sessions to {}", SESSIONS_FILE, e);
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
            AuthCore.LOGGER.error("Failed sync save of sessions", e);
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