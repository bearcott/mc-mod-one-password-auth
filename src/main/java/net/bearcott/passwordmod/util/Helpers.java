package net.bearcott.passwordmod.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.UUID;

import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.PasswordMod;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Helpers {
    public static record Location(String city, String country) {
        public String full() {
            return city + ", " + country;
        }

        public static Location unknown() {
            return new Location("Unknown", "Location");
        }

        public static Location localhost() {
            return new Location("Localhost", "Home");
        }
    }

    public static Location fetchLocationData(String ip) {
        try {
            URL url = URI.create("http://ip-api.com/csv/" + ip.split(":")[0].replace("/", "") + "?fields=city,country")
                    .toURL();
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF_8))) {
                String l = r.readLine();
                if (l != null && l.contains(",")) {
                    String[] pts = l.split(",");
                    return new Location(pts[1].trim(), pts[0].trim());
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        return Location.unknown();
    }

    public static boolean isRateLimited(UUID uuid) {
        AuthStorage.PlayerSession s = AuthStorage.getPendingSession(uuid);
        long now = System.currentTimeMillis();
        long last = s.lastAttemptTime;
        if (now - last < 1000)
            return true;
        return false;
    }

    public static boolean check67Answer(String input) {
        String clean = input.toLowerCase().replaceAll("[^a-z0-9]", "");
        return clean.contains("67") || clean.contains("sixtyseven") || clean.contains("sixseven");
    }

    public static String getSassyMessage(int attempt, String input) {
        String prefix = "§e§l[failed " + attempt + "/" + PasswordMod.MAX_ATTEMPTS + "] §f";
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

    public static int numberOrDefault(String val, int defaultVal) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
