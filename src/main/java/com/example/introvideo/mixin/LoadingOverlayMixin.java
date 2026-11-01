package com.example.introvideo.mixin;

import com.example.introvideo.VideoPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    /**
     * Поки відео грає, малюємо його замість логотипа Mojang і пропускаємо
     * оригінальний render. Оверлей завершиться сам вже після відео.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void introvideo$render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VideoPlayer.render(graphics)) {
            ci.cancel();
        }
    }
}
