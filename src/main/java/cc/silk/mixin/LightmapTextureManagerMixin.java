package cc.silk.mixin;

import cc.silk.SilkClient;
import cc.silk.module.modules.render.FullBright;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public class BlindnessWorldRendererMixin {

    @Inject(
            method = "hasBlindnessOrDarkness",
            at = @At("HEAD"),
            cancellable = true
    )
    private void removeBlindness(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        var module = SilkClient.INSTANCE.moduleManager
                .getModule(FullBright.class)
                .orElse(null);

        if (module != null
                && module.isEnabled()
                && FullBright.antiBlindness.getValue()) {
            cir.setReturnValue(false);
        }
    }
}
