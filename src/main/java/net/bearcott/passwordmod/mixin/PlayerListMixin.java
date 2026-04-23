package net.bearcott.passwordmod.mixin;

import com.mojang.authlib.GameProfile;
import net.bearcott.passwordmod.AuthStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    // Reject a new connection when an authenticated session for the same UUID is
    // already online. If the existing connection is still in lockdown, fall through
    // to vanilla (which kicks it) so a legitimate owner can recover a stuck login.
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, remap = true)
    private void onePasswordAuth$denyDuplicateAuthedLogin(
            SocketAddress address,
            GameProfile profile,
            CallbackInfoReturnable<Component> cir) {
        PlayerList self = (PlayerList) (Object) this;
        ServerPlayer existing = self.getPlayer(profile.id());
        if (existing == null)
            return;
        // Dead connection still in the player list — let vanilla clean up and accept the reconnect.
        if (existing.hasDisconnected())
            return;
        if (AuthStorage.hasPendingSession(profile.id()))
            return;
        cir.setReturnValue(Component.literal(
                "§cSomeone is already logged into this account. Try again later."));
    }
}
