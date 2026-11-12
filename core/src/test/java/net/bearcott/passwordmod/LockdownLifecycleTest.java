package net.bearcott.passwordmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import net.bearcott.passwordmod.util.Messages;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockdownLifecycleTest {

    @BeforeEach
    void reset() {
        AuthStorage.flushSavesForTests();
        AuthStorage.clearInMemoryStateForTests();
    }

    @AfterEach
    void drainSaves() {
        AuthStorage.flushSavesForTests();
    }

    @Test
    void applyLockdownSetsSpectatorInvulnerableBlind(@TempDir Path dir) {
        AuthStorage.load(dir);
        FakeAuthPlayer p = new FakeAuthPlayer("Alice", "1.2.3.4");

        PlayerLockdownHandlers.applyLockdown(p);

        assertEquals(GameMode.SPECTATOR, p.gameMode);
        assertTrue(p.invulnerable);
        assertTrue(p.blindness);
        assertTrue(AuthStorage.hasPendingSession(p.uuid()), "a pending session is created");
    }

    @Test
    void whitelistedPairLiftsLockdownOnTick(@TempDir Path dir) {
        AuthStorage.load(dir);
        FakeAuthPlayer p = new FakeAuthPlayer("Bob", "2.2.2.2");
        PlayerLockdownHandlers.applyLockdown(p);

        // Simulate a successful auth: the (ip,uuid) pair is now whitelisted.
        AuthStorage.whitelist(p.ip(), p.uuid());
        AuthCore.onTick(List.of(p), 1);

        assertEquals(GameMode.SURVIVAL, p.gameMode, "original game mode restored");
        assertFalse(p.invulnerable);
        assertFalse(p.blindness);
        assertFalse(AuthStorage.hasPendingSession(p.uuid()), "lockdown lifted and session removed");
    }

    @Test
    void timeoutKicksAfterTimeoutSec(@TempDir Path dir) {
        AuthStorage.load(dir);
        AuthStorage.timeoutSec = 1;
        FakeAuthPlayer p = new FakeAuthPlayer("Carol", "3.3.3.3");
        PlayerLockdownHandlers.applyLockdown(p);

        // Push the last-attempt time well past the timeout window.
        AuthStorage.PlayerSession s = AuthStorage.getPendingSession(p.uuid());
        s.lastAttemptTime = System.currentTimeMillis() - 10_000;

        AuthCore.onTick(List.of(p), 1);

        assertFalse(p.kicks.isEmpty(), "player is kicked on timeout");
        assertEquals(Messages.KICK_TIMEOUT, p.kicks.get(0));
    }
}
