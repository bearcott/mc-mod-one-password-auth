package net.bearcott.passwordmod.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.UUID;

import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.PasswordMod;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Helpers {
    private static final int LOCATION_LOOKUP_TIMEOUT_MS = 5000;
    private static final long RATE_LIMIT_WINDOW_MS = 1000L;

    // Pronounceable-password alphabet. Consonants + vowels build CV syllables that are
    // easy to read aloud; a digit tail and a symbol push entropy past ~7e8 combinations.
    private static final char[] PW_CONSONANTS = "bcdfghjklmnprstvwz".toCharArray();
    private static final char[] PW_VOWELS = "aeiou".toCharArray();
    private static final char[] PW_SYMBOLS = "!@#$".toCharArray();
    private static final SecureRandom PW_RANDOM = new SecureRandom();

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
            c.setConnectTimeout(LOCATION_LOOKUP_TIMEOUT_MS);
            c.setReadTimeout(LOCATION_LOOKUP_TIMEOUT_MS);
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
        if (s == null)
            return false;
        return System.currentTimeMillis() - s.lastAttemptTime < RATE_LIMIT_WINDOW_MS;
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
            case 3 -> prefix + (input.equals("PassWord123")
                    ? "Aw come on, you really thought that would work??"
                    : "Maybe try looking at the keyboard next time?");
            case 4 -> "§a§lSuccess!§r Please solve the captcha: §d§lWhat is 48+19?§f";
            case 5 -> prefix + (check67Answer(input)
                    ? "You thought that was gonna get you in?? Math??"
                    : "...Do you not know how to do addition or something?");
            case 6 -> prefix + "You're a failure.";
            default -> prefix;
        };
    }

    // Three CV syllables + 1–3 digits + 1 symbol, e.g. "komipu42!".
    // Safe to paste into a Properties value: none of !@#$ is a comment or delimiter
    // character mid-value. Excludes \ and % so no escape/format-specifier surprises.
    public static String generateDefaultPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(PW_CONSONANTS[PW_RANDOM.nextInt(PW_CONSONANTS.length)]);
            sb.append(PW_VOWELS[PW_RANDOM.nextInt(PW_VOWELS.length)]);
        }
        int digits = 1 + PW_RANDOM.nextInt(3);
        for (int i = 0; i < digits; i++)
            sb.append(PW_RANDOM.nextInt(10));
        sb.append(PW_SYMBOLS[PW_RANDOM.nextInt(PW_SYMBOLS.length)]);
        return sb.toString();
    }

    public static int numberOrDefault(String val, int defaultVal) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
