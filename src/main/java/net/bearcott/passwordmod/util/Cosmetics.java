package net.bearcott.passwordmod.util;

import net.bearcott.passwordmod.AuthStorage;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;

public class Cosmetics {
    public static void startKickPlayerEffects(ServerPlayer player) {
        Cosmetics.spawnLightning(player);
        Cosmetics.playSound(player, net.minecraft.sounds.SoundEvents.DRAGON_FIREBALL_EXPLODE, 1.0f);
        Cosmetics.spawnEffect(player, ParticleTypes.EXPLOSION, 5);
    }

    public static void chatLoginInstructions(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(Messages.LOGIN_PROMPT_DIV));
        player.sendSystemMessage(Component.literal(Messages.LOGIN_PROMPT_LINE));
        player.sendSystemMessage(Component.literal(Messages.LOGIN_PROMPT_DIV));
    }

    public static void loginSuccessEffects(ServerPlayer player) {
        Cosmetics.playSound(player, net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.0f);
        Cosmetics.spawnEffect(player, ParticleTypes.TOTEM_OF_UNDYING, 20);
    }

    public static void playSound(ServerPlayer p, SoundEvent s, float pitch) {
        p.connection.send(new ClientboundSoundPacket(Holder.direct(s), SoundSource.MASTER, p.getX(), p.getY(), p.getZ(),
                1.0f, pitch, p.getRandom().nextLong()));
    }

    public static void spawnLightning(ServerPlayer player) {
        if (player.level() instanceof ServerLevel sl) {
            LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, sl);
            bolt.setPos(player.getX(), player.getY(), player.getZ());
            bolt.setVisualOnly(true);
            sl.addFreshEntity(bolt);
        }
    }

    public static void spawnEffect(ServerPlayer p, ParticleOptions part, int count) {
        if (p.level() instanceof ServerLevel sl)
            sl.sendParticles(part, p.getX(), p.getY() + 1.0, p.getZ(), count, 0.5, 0.5, 0.5, 0.1);
    }

    public static void sendAuthTitle(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(AuthStorage.loginTitle)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(AuthStorage.loginDescription)));
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(Messages.LOGIN_ACTION_BAR)));
    }

    public static void resetTitle(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("")));
    }
}
