package net.bearcott.passwordmod.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.bearcott.passwordmod.AuthStorage;
import net.bearcott.passwordmod.AuthCore;

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
        // Best-effort and privacy-respecting: a blank geolocation_url disables third-party lookups.
        String base = AuthStorage.geolocationUrl;
        if (base == null || base.isBlank())
            return Location.unknown();
        try {
            // Reduce to a bare IP for the lookup. Handles IPv4, IPv4:port, bare IPv6, [IPv6]:port
            // and Java's "host/ip" prefix — crucially NOT truncating IPv6 at its first colon.
            String cleanIp = stripToHostAddress(ip);
            String endpoint = base.endsWith("/") ? base + cleanIp : base + "/" + cleanIp;
            URL url = URI.create(endpoint).toURL(); // HTTPS by default (https://ipwho.is/<ip>)
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(LOCATION_LOOKUP_TIMEOUT_MS);
            c.setReadTimeout(LOCATION_LOOKUP_TIMEOUT_MS);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null)
                    sb.append(line);
                return parseLocationJson(sb.toString());
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        return Location.unknown();
    }

    /**
     * Parse an ipwho.is-shaped JSON body ({@code {"success":true,"city":"...","country":"..."}})
     * into a {@link Location}. Pure (no I/O) for unit testing. Any malformed/empty input or an
     * explicit {@code "success":false} yields {@link Location#unknown()}.
     */
    public static Location parseLocationJson(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("success") && !o.get("success").getAsBoolean())
                return Location.unknown();
            String city = stringOrNull(o, "city");
            String country = stringOrNull(o, "country");
            if (city == null && country == null)
                return Location.unknown();
            return new Location(city != null ? city : "Unknown", country != null ? country : "Location");
        } catch (Exception ignored) {
            return Location.unknown();
        }
    }

    private static String stringOrNull(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    /**
     * Reduce a connection-address string to a bare IP for a geolocation lookup. Handles the forms
     * the platform may hand us — a clean IPv4/IPv6, Java's "host/ip" or "/ip" prefix, an
     * "IPv4:port", and a bracketed "[ipv6]:port" — and, unlike a naive split on ':', returns a bare
     * IPv6 address (multiple colons, no brackets) unchanged instead of truncating it at the first.
     */
    public static String stripToHostAddress(String ip) {
        if (ip == null)
            return "";
        String s = ip.trim();
        int slash = s.lastIndexOf('/'); // "hostname/1.2.3.4" or "/1.2.3.4"
        if (slash >= 0)
            s = s.substring(slash + 1);
        if (s.startsWith("[")) { // "[2001:db8::1]:5678"
            int end = s.indexOf(']');
            if (end > 0)
                return s.substring(1, end);
        }
        int first = s.indexOf(':');
        if (first >= 0 && first == s.lastIndexOf(':')) // exactly one ':' => IPv4:port
            return s.substring(0, first);
        return s; // IPv4 (no port) or bare IPv6 (multiple colons)
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
        String prefix = "§e§l[failed " + attempt + "/" + AuthCore.MAX_ATTEMPTS + "] §f";
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
