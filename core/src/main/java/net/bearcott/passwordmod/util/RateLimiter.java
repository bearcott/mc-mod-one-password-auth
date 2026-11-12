package net.bearcott.passwordmod.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiting, pure Java so it lives in the platform-free core. Thread-safe: the bucket
 * table is a {@link ConcurrentHashMap} and each bucket's fields are mutated under the bucket's own
 * monitor. Keyed by IP string (not by session), so every control here survives a reconnect.
 *
 * <p>Two independent concerns share one per-IP bucket:
 * <ul>
 *   <li><b>Connection throttle</b> — a token bucket ({@link #CONNECTION_CAPACITY} tokens, one
 *       refilled every {@link #CONNECTION_REFILL_INTERVAL_MS} ms). This is the anti-flood gate at
 *       the door; {@link #tryAcquireConnection} returns false when empty.</li>
 *   <li><b>Attempt throttle</b> — a minimum interval ({@link #ATTEMPT_MIN_INTERVAL_MS} ms) between
 *       {@code /login} attempts per IP, the real brute-force speed cap.</li>
 * </ul>
 *
 * <p>The bucket table itself must not become a DoS, so {@link #cleanup} drops buckets idle longer
 * than {@link #IDLE_EVICT_MS} and enforces a hard cap of {@link #maxTrackedIps} IPs (evicting the
 * oldest-idle when exceeded). Timing is injected via a {@code long now} parameter so tests can
 * drive it deterministically; callers pass {@link System#currentTimeMillis()}.
 */
public final class RateLimiter {

    public static final int CONNECTION_CAPACITY = 8;
    public static final long CONNECTION_REFILL_INTERVAL_MS = 8000L;
    public static final long ATTEMPT_MIN_INTERVAL_MS = 3000L;
    public static final long IDLE_EVICT_MS = 10L * 60L * 1000L; // 10 minutes
    public static final int MAX_TRACKED_IPS = 50_000;

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
        long lastAttemptMs; // 0 = no attempt yet
        volatile long lastSeenMs; // read without the monitor during cleanup (benign staleness)

        Bucket(long now) {
            this.tokens = CONNECTION_CAPACITY;
            this.lastRefillMs = now;
            this.lastAttemptMs = 0L;
            this.lastSeenMs = now;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxTrackedIps;

    public RateLimiter() {
        this(MAX_TRACKED_IPS);
    }

    /** Package-visible seam: a smaller cap keeps the hard-cap eviction test fast and deterministic. */
    RateLimiter(int maxTrackedIps) {
        this.maxTrackedIps = maxTrackedIps;
    }

    /**
     * Anti-flood gate: consume one connection token for {@code ip}. Returns false when the bucket
     * is empty (too many recent connection attempts from this IP).
     */
    public boolean tryAcquireConnection(String ip, long now) {
        Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket(now));
        synchronized (b) {
            refill(b, now);
            b.lastSeenMs = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /**
     * Brute-force speed cap: allow a {@code /login} attempt only if at least
     * {@link #ATTEMPT_MIN_INTERVAL_MS} ms have passed since this IP's last attempt.
     */
    public boolean tryAcquireLoginAttempt(String ip, long now) {
        Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket(now));
        synchronized (b) {
            b.lastSeenMs = now;
            if (b.lastAttemptMs != 0L && now - b.lastAttemptMs < ATTEMPT_MIN_INTERVAL_MS)
                return false;
            b.lastAttemptMs = now;
            return true;
        }
    }

    private void refill(Bucket b, long now) {
        long elapsed = now - b.lastRefillMs;
        if (elapsed <= 0)
            return;
        double added = (double) elapsed / CONNECTION_REFILL_INTERVAL_MS;
        b.tokens = Math.min(CONNECTION_CAPACITY, b.tokens + added);
        b.lastRefillMs = now;
    }

    /**
     * Evict stale per-IP state so the table can't itself become a memory DoS. Drops buckets idle
     * longer than {@link #IDLE_EVICT_MS}, then, if still over the hard cap, evicts the oldest-idle
     * buckets until within the cap. Cheap to call periodically (e.g. once a minute from the tick).
     */
    public void cleanup(long now) {
        buckets.entrySet().removeIf(e -> now - e.getValue().lastSeenMs > IDLE_EVICT_MS);

        int overflow = buckets.size() - maxTrackedIps;
        if (overflow <= 0)
            return;
        // Rare path: sort a snapshot by idle age and drop the oldest-idle extras.
        List<Map.Entry<String, Bucket>> entries = new ArrayList<>(buckets.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().lastSeenMs, b.getValue().lastSeenMs));
        for (int i = 0; i < overflow && i < entries.size(); i++)
            buckets.remove(entries.get(i).getKey());
    }

    /** Number of IPs currently tracked (test/diagnostic hook). */
    public int trackedIpCount() {
        return buckets.size();
    }

    /** Test seam: whether {@code ip} currently has a bucket. */
    boolean isTracked(String ip) {
        return buckets.containsKey(ip);
    }
}
