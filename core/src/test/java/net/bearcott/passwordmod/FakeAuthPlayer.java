package net.bearcott.passwordmod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-memory {@link AuthPlayer} test double. Records the state the lockdown logic changes
 * (gamemode, invulnerability, blindness, op) and the actions it takes (kicks, teleports, messages)
 * so tests can assert on them without any Minecraft/Fabric/Paper runtime.
 */
public final class FakeAuthPlayer implements AuthPlayer {
    private final UUID uuid;
    private final String name;
    private final String ip;

    public boolean disconnected = false;
    public boolean alive = true;

    public GameMode gameMode = GameMode.SURVIVAL;
    public boolean invulnerable = false;
    public boolean blindness = false;
    public boolean op = false;
    public int opLevel = 4;

    public Pos position = new Pos(0, 64, 0);

    public final List<String> messages = new ArrayList<>();
    public final List<String> kicks = new ArrayList<>();
    public final List<Pos> teleports = new ArrayList<>();
    public int restoredOpLevel = -1;

    public FakeAuthPlayer(UUID uuid, String name, String ip) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
    }

    public FakeAuthPlayer(String name, String ip) {
        this(UUID.randomUUID(), name, ip);
    }

    @Override public UUID uuid() { return uuid; }
    @Override public String name() { return name; }
    @Override public String ip() { return ip; }
    @Override public boolean hasDisconnected() { return disconnected; }
    @Override public boolean isAlive() { return alive; }

    @Override public GameMode gameMode() { return gameMode; }
    @Override public void setGameMode(GameMode mode) { this.gameMode = mode; }

    @Override public boolean isInvulnerable() { return invulnerable; }
    @Override public void setInvulnerable(boolean invulnerable) { this.invulnerable = invulnerable; }

    @Override public boolean hasBlindness() { return blindness; }
    @Override public void addBlindness(int durationTicks, int amplifier) { this.blindness = true; }
    @Override public void removeBlindness() { this.blindness = false; }

    @Override public boolean isOp() { return op; }
    @Override public int opLevel() { return opLevel; }
    @Override public void deop() { this.op = false; }
    @Override public void restoreOp(int level) { this.op = true; this.opLevel = level; this.restoredOpLevel = level; }

    @Override public Pos position() { return position; }
    @Override public void teleport(Pos pos) { this.position = pos; teleports.add(pos); }

    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public void kick(String reason) { kicks.add(reason); disconnected = true; }

    @Override public void playSound(Sound sound, float pitch) { }
    @Override public void strikeLightningEffect() { }
    @Override public void spawnParticles(Particle particle, int count) { }
    @Override public void showTitle(String title, String subtitle, String actionBar) { }
    @Override public void resetTitle() { }
}
