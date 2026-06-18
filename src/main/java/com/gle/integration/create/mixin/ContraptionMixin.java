package com.gle.integration.create.mixin;

import com.gle.GLEConfig;
import com.gle.core.GriefContext;
import com.gle.core.SystemUsers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Интеграция Create: контрапции (REQ-LOG-MOD-002). Сборка ({@code removeBlocksFromWorld}) и
 * разборка ({@code addBlocksToWorld}) меняют мир прямым {@code level.setBlock} — событий нет.
 * Помечаем эти операции атрибуцией в общем {@link GriefContext} ({@code source_type=create:contraption},
 * пользователь {@code [CREATE]}), а сами изменения логирует {@code LevelChunkMixin} — ядро о Create
 * при этом ничего не знает (§9). Гейт {@code enableCreateIntegration} проверяем прямо здесь.
 * <p>String-target + {@code remap=false} + {@code require=0}: если Create не установлен или метод
 * переименован, миксин просто не применится, сервер не падает.
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.Contraption", remap = false)
public class ContraptionMixin {

    @Inject(method = "addBlocksToWorld", at = @At("HEAD"), require = 0, remap = false)
    private void gle$pushAdd(CallbackInfo ci) { gle$push(); }

    @Inject(method = "addBlocksToWorld", at = @At("RETURN"), require = 0, remap = false)
    private void gle$popAdd(CallbackInfo ci) { gle$pop(); }

    @Inject(method = "removeBlocksFromWorld", at = @At("HEAD"), require = 0, remap = false)
    private void gle$pushRemove(CallbackInfo ci) { gle$push(); }

    @Inject(method = "removeBlocksFromWorld", at = @At("RETURN"), require = 0, remap = false)
    private void gle$popRemove(CallbackInfo ci) { gle$pop(); }

    private static void gle$push() {
        if (GLEConfig.enableCreateIntegration.get()) {
            GriefContext.push("create:contraption", SystemUsers.CREATE);
        }
    }

    private static void gle$pop() {
        if (GLEConfig.enableCreateIntegration.get()) {
            GriefContext.pop();
        }
    }
}
