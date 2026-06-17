package com.gle.mixin.create;

import com.gle.integration.CreateContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Интеграция Create: схематическая пушка (REQ-LOG-MOD-005). Пушка ставит блоки отложенно —
 * через {@code LaunchedItem.update(Level)} → {@code place()} → {@code level.setBlock}. Помечаем
 * операцию контекстом {@code create:schematicannon}; блок логирует {@code LevelChunkMixin}.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.cannon.LaunchedItem", remap = false)
public class LaunchedItemMixin {

    @Inject(method = "update", at = @At("HEAD"), require = 0, remap = false)
    private void gle$push(CallbackInfoReturnable<Boolean> cir) { CreateContext.push("create:schematicannon"); }

    @Inject(method = "update", at = @At("RETURN"), require = 0, remap = false)
    private void gle$pop(CallbackInfoReturnable<Boolean> cir) { CreateContext.pop(); }
}
