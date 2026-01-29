package net.example.auth;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AuthStorage {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("auth_config.properties");
    private static final Path IP_PATH = FabricLoader.getInstance().getConfigDir().resolve("ip_whitelist.txt");

    private static final Set<String> whitelistedIPs = new HashSet<>();
    public static String serverPassword = "PASSSWORDHERE";
    public static String webhookUrl = "";
    public static String adminWebhookUrl = "";

    public static void load() {
        try {
            Properties props = new Properties();
            if (Files.exists(CONFIG_PATH)) {
                props.load(Files.newInputStream(CONFIG_PATH));
                serverPassword = props.getProperty("password", "PASSSWORDHERE");
                webhookUrl = props.getProperty("webhook_url", "");
                adminWebhookUrl = props.getProperty("admin_webhook_url", "");
            } else {
                props.setProperty("password", "PASSSWORDHERE");
                props.setProperty("webhook_url", "");
                props.setProperty("admin_webhook_url", "");
                props.store(Files.newOutputStream(CONFIG_PATH), "Auth Mod Config");
            }
        } catch (IOException e) { e.printStackTrace(); }

        if (Files.exists(IP_PATH)) {
            try (BufferedReader reader = Files.newBufferedReader(IP_PATH)) {
                String line;
                while ((line = reader.readLine()) != null) whitelistedIPs.add(line.trim());
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    public static void saveIP(String ip) {
        whitelistedIPs.add(ip);
        try { Files.write(IP_PATH, whitelistedIPs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException e) { e.printStackTrace(); }
    }

    public static boolean isAuthed(String ip) { return whitelistedIPs.contains(ip); }
}