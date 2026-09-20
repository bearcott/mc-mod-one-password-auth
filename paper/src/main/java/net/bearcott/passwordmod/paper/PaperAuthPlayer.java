package net.bearcott.passwordmod.paper;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bearcott.passwordmod.AuthCore;
import net.bearcott.passwordmod.AuthPlayer;
import net.bearcott.passwordmod.GameMode;
import net.bearcott.passwordmod.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.net.InetSocketAddress;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/** {@link AuthPlayer} over a Bukkit Player, using only the Paper API. */
public record PaperAuthPlayer(Player player) implements AuthPlayer {
    // The server's ops file. The Paper API has no op levels, so the level is read from here.
    private static final Path OPS_FILE = Path.of("ops.json");
    private static final Path SERVER_PROPERTIES = Path.of("server.properties");
    private static final int DEFAULT_OP_LEVEL = 4;
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(10 * 50), Duration.ofMillis(70 * 50), Duration.ofMillis(20 * 50));

    static Component text(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public String ip() {
        // Same formatting as vanilla's ServerPlayer.getIpAddress(), so whitelist entries written by
        // the Fabric mod still match (IPv6 in particular).
        InetSocketAddress address = player.getAddress();
        return address != null && address.getAddress() != null
                ? InetAddresses.toAddrString(address.getAddress())
                : "<unknown>";
    }

    @Override
    public boolean hasDisconnected() {
        return !player.isConnected();
    }

    @Override
    public boolean isAlive() {
        return !player.isDead();
    }

    @Override
    public GameMode gameMode() {
        return GameMode.valueOf(player.getGameMode().name());
    }

    @Override
    public void setGameMode(GameMode mode) {
        player.setGameMode(org.bukkit.GameMode.valueOf(mode.name()));
    }

    @Override
    public boolean isInvulnerable() {
        return player.isInvulnerable();
    }

    @Override
    public void setInvulnerable(boolean invulnerable) {
        player.setInvulnerable(invulnerable);
    }

    @Override
    public boolean hasBlindness() {
        return player.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    @Override
    public void addBlindness(int durationTicks, int amplifier) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, durationTicks, amplifier, false, false));
    }

    @Override
    public void removeBlindness() {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    @Override
    public boolean isOp() {
        return player.isOp();
    }

    @Override
    public int opLevel() {
        try (Reader r = Files.newBufferedReader(OPS_FILE)) {
            String uuid = player.getUniqueId().toString();
            for (JsonElement e : JsonParser.parseReader(r).getAsJsonArray()) {
                JsonObject op = e.getAsJsonObject();
                if (uuid.equals(op.get("uuid").getAsString()) && op.has("level"))
                    return op.get("level").getAsInt();
            }
        } catch (Exception ignored) {
        }
        return 4;
    }

    @Override
    public void deop() {
        player.setOp(false);
    }

    /**
     * Bukkit has no op levels, so the player is re-opped at the server's op-permission-level.
     * setOp also resends their permission level and command tree. If they were opped at a
     * different level, say so loudly — this is the one thing the Paper plugin can't put back
     * exactly, and it would otherwise silently promote (or demote) them.
     */
    @Override
    public void restoreOp(int level) {
        player.setOp(true);
        int restored = opPermissionLevel();
        if (level != restored) {
            AuthCore.LOGGER.warn(
                    "{} was op level {} before the lockdown, but Bukkit can only re-op at the server's "
                    + "op-permission-level ({}). Set their level back by hand in ops.json if that matters.",
                    player.getName(), level, restored);
        }
    }

    private static int opPermissionLevel() {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(SERVER_PROPERTIES)) {
            props.load(r);
            return Integer.parseInt(props.getProperty("op-permission-level", String.valueOf(DEFAULT_OP_LEVEL)).trim());
        } catch (Exception e) {
            return DEFAULT_OP_LEVEL;
        }
    }

    @Override
    public Pos position() {
        Location l = player.getLocation();
        return new Pos(l.getX(), l.getY(), l.getZ());
    }

    @Override
    public void teleport(Pos pos) {
        Location l = player.getLocation();
        player.teleport(new Location(player.getWorld(), pos.x(), pos.y(), pos.z(), l.getYaw(), l.getPitch()));
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(text(message));
    }

    @Override
    public void kick(String reason) {
        player.kick(text(reason));
    }

    @Override
    public void playSound(Sound sound, float pitch) {
        // Only this player hears it, like the Fabric version's direct sound packet.
        player.playSound(player.getLocation(), switch (sound) {
            case WITHER_SPAWN -> org.bukkit.Sound.ENTITY_WITHER_SPAWN;
            case CREEPER_PRIMED -> org.bukkit.Sound.ENTITY_CREEPER_PRIMED;
            case DRAGON_FIREBALL_EXPLODE -> org.bukkit.Sound.ENTITY_DRAGON_FIREBALL_EXPLODE;
            case PLAYER_LEVELUP -> org.bukkit.Sound.ENTITY_PLAYER_LEVELUP;
        }, SoundCategory.MASTER, 1.0f, pitch);
    }

    @Override
    public void strikeLightningEffect() {
        player.getWorld().strikeLightningEffect(player.getLocation());
    }

    @Override
    public void spawnParticles(Particle particle, int count) {
        Location l = player.getLocation();
        player.getWorld().spawnParticle(switch (particle) {
            case EXPLOSION -> org.bukkit.Particle.EXPLOSION;
            case TOTEM_OF_UNDYING -> org.bukkit.Particle.TOTEM_OF_UNDYING;
        }, l.getX(), l.getY() + 1.0, l.getZ(), count, 0.5, 0.5, 0.5, 0.1);
    }

    @Override
    public void showTitle(String title, String subtitle, String actionBar) {
        player.showTitle(Title.title(text(title), text(subtitle), TITLE_TIMES));
        player.sendActionBar(text(actionBar));
    }

    @Override
    public void resetTitle() {
        player.clearTitle();
        player.sendActionBar(Component.empty());
    }
}
