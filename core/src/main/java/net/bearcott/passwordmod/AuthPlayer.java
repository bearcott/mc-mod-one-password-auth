package net.bearcott.passwordmod;

import java.util.UUID;

/**
 * An online player, as the shared lockdown logic sees it. Each platform (Fabric, Paper) wraps its
 * own player type in this; everything else in this module is platform-free.
 */
public interface AuthPlayer {

    enum Sound {
        WITHER_SPAWN,
        CREEPER_PRIMED,
        DRAGON_FIREBALL_EXPLODE,
        PLAYER_LEVELUP
    }

    enum Particle {
        EXPLOSION,
        TOTEM_OF_UNDYING
    }

    UUID uuid();

    String name();

    /** Remote IP without port, formatted the way vanilla's ServerPlayer.getIpAddress() does. */
    String ip();

    boolean hasDisconnected();

    boolean isAlive();

    // ---- state the lockdown changes, and must put back before the player is saved ----

    GameMode gameMode();

    void setGameMode(GameMode mode);

    boolean isInvulnerable();

    void setInvulnerable(boolean invulnerable);

    boolean hasBlindness();

    void addBlindness(int durationTicks, int amplifier);

    void removeBlindness();

    boolean isOp();

    /** The player's op level, or 4 if they're an op but the level can't be read. */
    int opLevel();

    void deop();

    /** Re-ops the player and resends their permissions and command tree so it applies at once. */
    void restoreOp(int level);

    // ---- movement ----

    Pos position();

    /** Teleports within the player's current world, keeping their rotation. */
    void teleport(Pos pos);

    // ---- messages and theatrics ----

    /** Sends a chat line; § color codes are honoured. */
    void sendMessage(String message);

    void kick(String reason);

    void playSound(Sound sound, float pitch);

    void strikeLightningEffect();

    void spawnParticles(Particle particle, int count);

    /** Title + subtitle (10/70/20 tick fade) and an action bar line. */
    void showTitle(String title, String subtitle, String actionBar);

    void resetTitle();
}
