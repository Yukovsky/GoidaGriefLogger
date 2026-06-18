package com.gle.mixin;

import com.gle.GLEConfig;
import com.gle.core.GriefContext;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Гравитационные блоки (песок, гравий, бетонная пудра, наковальни и любые {@code FallingBlock}).
 * Падающий блок не порождает событий установки/слома — он меняет мир прямым {@code level.setBlock}.
 * Поэтому помечаем поток контекстом {@code gravity} на время:
 * <ul>
 *   <li>{@code fall(...)} — статический старт падения: убирает блок в исходной позиции (BREAK);</li>
 *   <li>{@code tick()} — приземление: ставит блок на новом месте (PLACE).</li>
 * </ul>
 * Сам перехват изменения блока делает {@link LevelChunkMixin}, читая {@link GriefContext}.
 * {@code require=0} — при несовпадении маппинга фича просто не активна, сервер не падает.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Inject(method = "fall", at = @At("HEAD"), require = 0)
    private static void gle$fallHead(Level level, BlockPos pos, BlockState blockState, CallbackInfoReturnable<FallingBlockEntity> cir) {
        if (GLEConfig.enableGravityBlocks.get()) GriefContext.push(SourceType.GRAVITY, SystemUsers.GRAVITY);
    }

    @Inject(method = "fall", at = @At("RETURN"), require = 0)
    private static void gle$fallReturn(Level level, BlockPos pos, BlockState blockState, CallbackInfoReturnable<FallingBlockEntity> cir) {
        if (GLEConfig.enableGravityBlocks.get()) GriefContext.pop();
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void gle$tickHead(CallbackInfo ci) {
        if (GLEConfig.enableGravityBlocks.get()) GriefContext.push(SourceType.GRAVITY, SystemUsers.GRAVITY);
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void gle$tickReturn(CallbackInfo ci) {
        if (GLEConfig.enableGravityBlocks.get()) GriefContext.pop();
    }
}
