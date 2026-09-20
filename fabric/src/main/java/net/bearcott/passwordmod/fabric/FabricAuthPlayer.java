package net.bearcott.passwordmod.fabric;

import net.bearcott.passwordmod.AuthPlayer;
import net.bearcott.passwordmod.GameMode;
import net.bearcott.passwordmod.Pos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** {@link AuthPlayer} over a vanilla ServerPlayer. */
public record FabricAuthPlayer(ServerPlayer player) implements AuthPlayer {

    @Override
    public UUID uuid() {
        return player.getUUID();
    }

    @Override
    public String name() {
        return player.getScoreboardName();
    }

    @Override
    public String ip() {
        return player.getIpAddress();
    }

    @Override
    public boolean hasDisconnected() {
        return player.hasDisconnected();
    }

    @Override
    public boolean isAlive() {
        return player.isAlive();
    }

    @Override
    public GameMode gameMode() {
        return GameMode.valueOf(player.gameMode.getGameModeForPlayer().name());
    }

    @Override
    public void setGameMode(GameMode mode) {
        player.setGameMode(GameType.valueOf(mode.name()));
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
        return player.hasEffect(MobEffects.BLINDNESS);
    }

    @Override
    public void addBlindness(int durationTicks, int amplifier) {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, durationTicks, amplifier, false, false));
    }

    @Override
    public void removeBlindness() {
        player.removeEffect(MobEffects.BLINDNESS);
    }

    private NameAndId nameAndId() {
        var profile = player.getGameProfile();
        return new NameAndId(profile.id(), profile.name());
    }

    private PlayerList playerList() {
        return player.level().getServer().getPlayerList();
    }

    @Override
    public boolean isOp() {
        return playerList().isOp(nameAndId());
    }

    @Override
    public int opLevel() {
        var opEntry = playerList().getOps().get(nameAndId());
        return (opEntry != null) ? opEntry.permissions().level().id() : 4;
    }

    @Override
    public void deop() {
        playerList().deop(nameAndId());
    }

    @Override
    public void restoreOp(int level) {
        PermissionLevel pLevel = PermissionLevel.byId(level);
        LevelBasedPermissionSet pSet = LevelBasedPermissionSet.forLevel(pLevel);
        ServerOpListEntry entry = new ServerOpListEntry(nameAndId(), pSet, false);

        playerList().getOps().add(entry);
        playerList().sendPlayerPermissionLevel(player);
        player.level().getServer().getCommands().sendCommands(player);
        player.onUpdateAbilities();
    }

    @Override
    public Pos position() {
        Vec3 p = player.position();
        return new Pos(p.x, p.y, p.z);
    }

    @Override
    public void teleport(Pos pos) {
        player.teleport(new TeleportTransition(player.level(), new Vec3(pos.x(), pos.y(), pos.z()), Vec3.ZERO,
                player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
    }

    @Override
    public void sendMessage(String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    @Override
    public void kick(String reason) {
        player.connection.disconnect(Component.literal(reason));
    }

    @Override
    public void playSound(Sound sound, float pitch) {
        Cosmetics.playSound(player, switch (sound) {
            case WITHER_SPAWN -> SoundEvents.WITHER_SPAWN;
            case CREEPER_PRIMED -> SoundEvents.CREEPER_PRIMED;
            case DRAGON_FIREBALL_EXPLODE -> SoundEvents.DRAGON_FIREBALL_EXPLODE;
            case PLAYER_LEVELUP -> SoundEvents.PLAYER_LEVELUP;
        }, pitch);
    }

    @Override
    public void strikeLightningEffect() {
        Cosmetics.spawnLightning(player);
    }

    @Override
    public void spawnParticles(Particle particle, int count) {
        Cosmetics.spawnEffect(player, switch (particle) {
            case EXPLOSION -> ParticleTypes.EXPLOSION;
            case TOTEM_OF_UNDYING -> ParticleTypes.TOTEM_OF_UNDYING;
        }, count);
    }

    @Override
    public void showTitle(String title, String subtitle, String actionBar) {
        Cosmetics.sendAuthTitle(player, title, subtitle, actionBar);
    }

    @Override
    public void resetTitle() {
        Cosmetics.resetTitle(player);
    }
}
