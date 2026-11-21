package network.vonix.serverutilities.fabric.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import network.vonix.serverutilities.pvp.PvpManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric 1.18.2 fallback: Fabric API's damage callback was added later. */
@Mixin(LivingEntity.class)
public abstract class PvpMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void vsu$pvpCheck(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (PvpManager.shouldCancelDamage((LivingEntity) (Object) this, source)) {
            cir.setReturnValue(false);
        }
    }
}
