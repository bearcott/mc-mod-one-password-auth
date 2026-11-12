package net.bearcott.passwordmod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void connectionBucketAllowsBurstThenThrottles() {
        RateLimiter rl = new RateLimiter();
        long now = 1000L;
        for (int i = 0; i < RateLimiter.CONNECTION_CAPACITY; i++)
            assertTrue(rl.tryAcquireConnection("1.2.3.4", now), "burst token " + i + " should be allowed");
        assertFalse(rl.tryAcquireConnection("1.2.3.4", now), "bucket should be empty after the burst");
    }

    @Test
    void connectionBucketRefillsOverTime() {
        RateLimiter rl = new RateLimiter();
        long now = 1000L;
        for (int i = 0; i < RateLimiter.CONNECTION_CAPACITY; i++)
            rl.tryAcquireConnection("1.2.3.4", now);
        assertFalse(rl.tryAcquireConnection("1.2.3.4", now));
        // After one refill interval, exactly one token is back.
        long later = now + RateLimiter.CONNECTION_REFILL_INTERVAL_MS;
        assertTrue(rl.tryAcquireConnection("1.2.3.4", later));
        assertFalse(rl.tryAcquireConnection("1.2.3.4", later));
    }

    @Test
    void connectionThrottleSurvivesReconnect() {
        RateLimiter rl = new RateLimiter();
        long now = 5000L;
        for (int i = 0; i < RateLimiter.CONNECTION_CAPACITY; i++)
            rl.tryAcquireConnection("9.9.9.9", now);
        // A "reconnect" is just another connection attempt from the same IP — state is keyed by IP,
        // not by session, so it's still throttled.
        assertFalse(rl.tryAcquireConnection("9.9.9.9", now), "reconnect from same IP must stay throttled");
    }

    @Test
    void attemptThrottleEnforcesMinInterval() {
        RateLimiter rl = new RateLimiter();
        long t0 = 1000L;
        assertTrue(rl.tryAcquireLoginAttempt("1.2.3.4", t0), "first attempt allowed");
        assertFalse(rl.tryAcquireLoginAttempt("1.2.3.4", t0 + 100), "too soon");
        assertFalse(rl.tryAcquireLoginAttempt("1.2.3.4", t0 + RateLimiter.ATTEMPT_MIN_INTERVAL_MS - 1), "still too soon");
        assertTrue(rl.tryAcquireLoginAttempt("1.2.3.4", t0 + RateLimiter.ATTEMPT_MIN_INTERVAL_MS), "interval elapsed");
    }

    @Test
    void attemptThrottleSurvivesReconnect() {
        RateLimiter rl = new RateLimiter();
        long t0 = 2000L;
        assertTrue(rl.tryAcquireLoginAttempt("7.7.7.7", t0));
        // New session, same IP, moments later: still blocked because last-attempt is stored per IP.
        assertFalse(rl.tryAcquireLoginAttempt("7.7.7.7", t0 + 500));
    }

    @Test
    void cleanupEvictsStaleEntries() {
        RateLimiter rl = new RateLimiter();
        long now = 1000L;
        rl.tryAcquireConnection("1.2.3.4", now);
        assertEquals(1, rl.trackedIpCount());
        rl.cleanup(now + RateLimiter.IDLE_EVICT_MS + 1);
        assertEquals(0, rl.trackedIpCount(), "idle entry should be evicted");
    }

    @Test
    void cleanupKeepsFreshEntries() {
        RateLimiter rl = new RateLimiter();
        long now = 1000L;
        rl.tryAcquireConnection("1.2.3.4", now);
        rl.cleanup(now + 1000); // well within the idle window
        assertEquals(1, rl.trackedIpCount());
    }

    @Test
    void hardCapEvictsOldestIdle() {
        RateLimiter rl = new RateLimiter(3); // small cap for a deterministic test
        rl.tryAcquireConnection("a", 1L);
        rl.tryAcquireConnection("b", 2L);
        rl.tryAcquireConnection("c", 3L);
        rl.tryAcquireConnection("d", 4L);
        assertEquals(4, rl.trackedIpCount());
        rl.cleanup(5L); // not idle, but over the hard cap of 3
        assertEquals(3, rl.trackedIpCount());
        // "a" was the oldest-idle, so it is the one dropped; "d" (newest) is kept.
        assertFalse(rl.isTracked("a"), "oldest-idle entry should be evicted under the hard cap");
        assertTrue(rl.isTracked("d"), "newest entry should be kept");
    }
}
