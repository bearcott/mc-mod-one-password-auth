package net.bearcott.passwordmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthStorageTest {

    @BeforeEach
    void reset() {
        AuthStorage.flushSavesForTests(); // drain any saves left queued by an earlier test
        AuthStorage.clearInMemoryStateForTests();
    }

    @AfterEach
    void drainSaves() {
        // Let queued async saves finish writing before JUnit deletes @TempDir.
        AuthStorage.flushSavesForTests();
    }

    @Test
    void isWhitelistedRequiresExactPair(@TempDir Path dir) {
        AuthStorage.load(dir);
        UUID u = UUID.randomUUID();
        AuthStorage.whitelist("1.2.3.4", u);

        assertTrue(AuthStorage.isWhitelisted("1.2.3.4", u), "exact (ip,uuid) pair");
        assertFalse(AuthStorage.isWhitelisted("1.2.3.5", u), "different ip");
        assertFalse(AuthStorage.isWhitelisted("1.2.3.4", UUID.randomUUID()), "different uuid");
        assertFalse(AuthStorage.isWhitelisted(null, u));
        assertFalse(AuthStorage.isWhitelisted("1.2.3.4", null));
    }

    @Test
    void legacyIpOnlyLinesAreIgnoredOnLoad(@TempDir Path dir) throws Exception {
        UUID u = UUID.randomUUID();
        Path ipFile = dir.resolve("one_password_auth_ip_whitelist.txt");
        Files.write(ipFile, List.of("5.5.5.5", "6.6.6.6|" + u)); // legacy ip-only + modern pair

        AuthStorage.load(dir);

        assertTrue(AuthStorage.isWhitelisted("6.6.6.6", u), "modern ip|uuid pair honoured");
        assertFalse(AuthStorage.isWhitelisted("5.5.5.5", u), "legacy ip-only line must be ignored");
    }

    @Test
    void pendingSessionEvictionKeepsNewest(@TempDir Path dir) {
        AuthStorage.load(dir);
        int cap = AuthStorage.MAX_PENDING_SESSIONS;

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            UUID u = UUID.randomUUID();
            ids.add(u);
            AuthStorage.PlayerSession s =
                    AuthStorage.getOrCreatePendingPlayerSession(u, GameMode.SURVIVAL, false, 0, new Pos(0, 0, 0));
            s.joinTime = i; // ids[0] oldest ... ids[cap-1] newest
        }
        assertEquals(cap, AuthStorage.pendingSessionCount());

        UUID extra = UUID.randomUUID();
        AuthStorage.getOrCreatePendingPlayerSession(extra, GameMode.SURVIVAL, false, 0, new Pos(0, 0, 0));

        assertEquals(cap, AuthStorage.pendingSessionCount(), "stays at the cap after inserting over it");
        assertFalse(AuthStorage.hasPendingSession(ids.get(0)), "oldest pending session evicted");
        assertTrue(AuthStorage.hasPendingSession(ids.get(cap - 1)), "newest kept");
        assertTrue(AuthStorage.hasPendingSession(extra), "just-inserted session kept");
    }

    @Test
    void loadPrunesOversizedSessionsFile(@TempDir Path dir) throws Exception {
        int cap = AuthStorage.MAX_PENDING_SESSIONS;
        Map<UUID, AuthStorage.PlayerSession> big = new LinkedHashMap<>();
        UUID oldest = null;
        UUID newest = null;
        for (int i = 0; i < cap + 5; i++) {
            UUID u = UUID.randomUUID();
            AuthStorage.PlayerSession s = new AuthStorage.PlayerSession(GameMode.SURVIVAL, false, 0, new Pos(0, 0, 0));
            s.joinTime = i;
            big.put(u, s);
            if (i == 0)
                oldest = u;
            if (i == cap + 4)
                newest = u;
        }
        Path sessionsFile = dir.resolve("one_password_auth_sessions.json");
        Files.writeString(sessionsFile, new Gson().toJson(big));

        AuthStorage.load(dir);

        assertEquals(cap, AuthStorage.pendingSessionCount(), "oversized file pruned to the cap on load");
        assertFalse(AuthStorage.hasPendingSession(oldest), "oldest dropped");
        assertTrue(AuthStorage.hasPendingSession(newest), "newest kept");
    }
}
