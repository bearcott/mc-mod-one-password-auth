package net.bearcott.passwordmod.mixin;

import net.bearcott.passwordmod.AuthCore;
import net.bearcott.passwordmod.fabric.FabricAuthPlayer;
import net.bearcott.passwordmod.util.Messages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    // Reject a new connection when an authenticated session for the same UUID is
    // already online. If the existing connection is still in lockdown, fall through
    // to vanilla (which kicks it) so a legitimate owner can recover a stuck login.
    //
    // Signature note: 1.21.11 replaced GameProfile with NameAndId (class_11560) here.
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, remap = true)
    private void onePasswordAuth$denyDuplicateAuthedLogin(
            SocketAddress address,
            NameAndId profile,
            CallbackInfoReturnable<Component> cir) {
        PlayerList self = (PlayerList) (Object) this;
        ServerPlayer existing = self.getPlayer(profile.id());
        // A dead connection still in the player list doesn't count — let vanilla clean up and
        // accept the reconnect.
        boolean existingOnline = existing != null && !existing.hasDisconnected();
        if (AuthCore.shouldDenyDuplicateLogin(profile.id(), existingOnline))
            cir.setReturnValue(Component.literal(Messages.DUPLICATE_LOGIN_DENIED));
    }

    // remove() saves the player's data before anything else; put their real state back first so
    // the lockdown is never written to their playerdata or ops.json.
    @Inject(method = "remove", at = @At("HEAD"))
    private void onePasswordAuth$suspendLockdownBeforeSave(ServerPlayer player, CallbackInfo ci) {
        AuthCore.beforePlayerSaved(new FabricAuthPlayer(player));
    }
}
