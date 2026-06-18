package com.gle.integration.create.mixin;

import com.gle.GLEConfig;
import com.gle.core.GriefContext;
import com.gle.core.SystemUsers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Интеграция Create: схематическая пушка (REQ-LOG-MOD-005). Пушка ставит блоки отложенно —
 * через {@code LaunchedItem.update(Level)} → {@code place()} → {@code level.setBlock}. Помечаем
 * операцию атрибуцией в общем {@link GriefContext} ({@code source_type=create:schematicannon},
 * пользователь {@code [CREATE]}); блок логирует {@code LevelChunkMixin}. Гейт
 * {@code enableCreateIntegration} проверяем здесь.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.cannon.LaunchedItem", remap = false)
public class LaunchedItemMixin {

    @Inject(method = "update", at = @At("HEAD"), require = 0, remap = false)
    private void gle$push(CallbackInfoReturnable<Boolean> cir) {
        if (GLEConfig.enableCreateIntegration.get()) {
            GriefContext.push("create:schematicannon", SystemUsers.CREATE);
        }
    }

    @Inject(method = "update", at = @At("RETURN"), require = 0, remap = false)
    private void gle$pop(CallbackInfoReturnable<Boolean> cir) {
        if (GLEConfig.enableCreateIntegration.get()) {
            GriefContext.pop();
        }
    }
}
