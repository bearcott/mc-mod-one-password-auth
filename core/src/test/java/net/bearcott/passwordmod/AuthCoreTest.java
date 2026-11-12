package net.bearcott.passwordmod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import net.bearcott.passwordmod.util.RateLimiter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthCoreTest {

    @AfterEach
    void drainSaves() {
        AuthStorage.flushSavesForTests(); // finish queued saves before @TempDir is deleted
    }

    @Test
    void shouldDenyDuplicateLoginTruthTable(@TempDir Path dir) {
        AuthStorage.flushSavesForTests();
        AuthStorage.clearInMemoryStateForTests();
        AuthStorage.load(dir);
        UUID u = UUID.randomUUID();

        // No live connection with this UUID -> never deny.
        assertFalse(AuthCore.shouldDenyDuplicateLogin(u, false));

        // A live, authenticated (no pending session) connection exists -> deny the newcomer.
        assertTrue(AuthCore.shouldDenyDuplicateLogin(u, true));

        // The existing connection is still in lockdown (pending) -> allow, so the real owner can
        // recover a stuck login (vanilla then kicks the old one).
        AuthStorage.getOrCreatePendingPlayerSession(u, GameMode.SURVIVAL, false, 0, new Pos(0, 0, 0));
        assertFalse(AuthCore.shouldDenyDuplicateLogin(u, true));
    }

    @Test
    void onIncomingConnectionThrottlesByIp() {
        String ip = "203.0.113.7"; // unique to this test so the shared limiter's bucket is fresh
        for (int i = 0; i < RateLimiter.CONNECTION_CAPACITY; i++)
            assertTrue(AuthCore.onIncomingConnection(ip), "burst connection " + i + " allowed");
        assertFalse(AuthCore.onIncomingConnection(ip), "flood past capacity is refused");
    }
}
