package net.bearcott.passwordmod.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.bearcott.passwordmod.util.AdvancementsLogger;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
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

    @Shadow public abstract AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    // award() runs once per criterion, not once per advancement, so it returns true every time a
    // multi-criteria advancement makes progress (e.g. each new biome for Adventuring Time). Only
    // log the call that completes it — the same wasDone -> isDone check vanilla uses to announce.
    @Inject(method = "award", at = @At("HEAD"))
    private void recordWasDone(AdvancementHolder advancement, String criterionName, CallbackInfoReturnable<Boolean> cir,
                               @Share("wasDone") LocalBooleanRef wasDone) {
        wasDone.set(this.getOrStartProgress(advancement).isDone());
    }

    @Inject(method = "award", at = @At("TAIL"))
    private void onAdvancementAwarded(AdvancementHolder advancement, String criterionName, CallbackInfoReturnable<Boolean> cir,
                                      @Share("wasDone") LocalBooleanRef wasDone) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) && !wasDone.get() && this.getOrStartProgress(advancement).isDone()) {
            AdvancementsLogger.logAdvancement(this.player, advancement);
        }
    }
}
