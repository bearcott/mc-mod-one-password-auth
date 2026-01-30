package net.example.auth;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AuthStorage {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("auth_config.properties");
    private static final Path IP_PATH = FabricLoader.getInstance().getConfigDir().resolve("ip_whitelist.txt");

    private static final Set<String> whitelistedIPs = new HashSet<>();

    // Default values are now only defined in the load() method or here
    public static String serverPassword;
    public static String webhookUrl;
    public static String adminWebhookUrl;
    public static int timeoutSec;

    public static void load() {
        Properties props = new Properties();

        // 1. Set Defaults
        props.setProperty("password", "");
        props.setProperty("webhook_url", "");
        props.setProperty("admin_webhook_url", "");
        props.setProperty("timeout_seconds", "180");

        // 2. Load or Create File
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (InputStream is = Files.newInputStream(CONFIG_PATH)) {
                    props.load(is);
                }
            } else {
                try (OutputStream os = Files.newOutputStream(CONFIG_PATH)) {
                    props.store(os, "Auth Mod Config");
                }
            }

            // 3. Assign Variables (The only place defaults are referenced is props.getProperty)
            serverPassword = props.getProperty("password");
            webhookUrl = props.getProperty("webhook_url");
            adminWebhookUrl = props.getProperty("admin_webhook_url");
            timeoutSec = tryParse(props.getProperty("timeout_seconds"), 180);

        } catch (IOException e) {
            e.printStackTrace();
        }

        // 4. Load IP Whitelist
        if (Files.exists(IP_PATH)) {
            try {
                whitelistedIPs.addAll(Files.readAllLines(IP_PATH));
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    public static void saveIP(String ip) {
        if (whitelistedIPs.add(ip)) { // Only write if the IP is actually new
            try {
                Files.write(IP_PATH, whitelistedIPs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    public static boolean isAuthed(String ip) {
        return whitelistedIPs.contains(ip);
    }

    // Helper to keep the load() method clean
    private static int tryParse(String val, int defaultVal) {
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}