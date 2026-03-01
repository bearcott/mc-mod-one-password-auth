package net.bearcott.passwordmod.mixin;

import net.bearcott.passwordmod.util.AdvancementsLogger;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow private ServerPlayer player;

    @Inject(method = "award", at = @At("TAIL"), remap = true)
    private void onAdvancementAwarded(AdvancementHolder advancement, String criterionName, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() != null && cir.getReturnValue()) {
            AdvancementsLogger.logAdvancement(this.player, advancement);
        }
    }
}