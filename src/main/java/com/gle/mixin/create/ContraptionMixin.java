package com.gle.mixin.create;

import com.gle.integration.CreateContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Интеграция Create: контрапции (REQ-LOG-MOD-002). Сборка ({@code removeBlocksFromWorld}) и
 * разборка ({@code addBlocksToWorld}) меняют мир прямым {@code level.setBlock} — событий нет.
 * Помечаем эти операции контекстом, а сами изменения логирует {@code LevelChunkMixin}.
 * <p>String-target + {@code remap=false} + {@code require=0}: если Create не установлен или метод
 * переименован, миксин просто не применится, сервер не падает.
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.Contraption", remap = false)
public class ContraptionMixin {

    @Inject(method = "addBlocksToWorld", at = @At("HEAD"), require = 0, remap = false)
    private void gle$pushAdd(CallbackInfo ci) { CreateContext.push("create:contraption"); }

    @Inject(method = "addBlocksToWorld", at = @At("RETURN"), require = 0, remap = false)
    private void gle$popAdd(CallbackInfo ci) { CreateContext.pop(); }

    @Inject(method = "removeBlocksFromWorld", at = @At("HEAD"), require = 0, remap = false)
    private void gle$pushRemove(CallbackInfo ci) { CreateContext.push("create:contraption"); }

    @Inject(method = "removeBlocksFromWorld", at = @At("RETURN"), require = 0, remap = false)
    private void gle$popRemove(CallbackInfo ci) { CreateContext.pop(); }
}
