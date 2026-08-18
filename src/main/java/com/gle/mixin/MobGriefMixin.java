package com.gle.mixin;

import com.gle.GLEConfig;
import com.gle.core.GriefContext;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Гриферство мобами (эндермен берёт/ставит блок, зомби ломает дверь, рейвагер/овца едят
 * растения и т.п.). Такие изменения идут прямым {@code level.setBlock} внутри AI-тика моба
 * и не порождают событий. Помечаем поток на время {@code aiStep()} ссылкой на моба, а
 * {@link LevelChunkMixin} лениво резолвит атрибуцию ({@code entity:<тип>}, пользователь [MOB])
 * через {@link com.gle.core.GLESourceResolver} только если блок реально изменился.
 * {@code require=0} — при несовпадении маппинга фича просто не активна.
 */
@Mixin(Mob.class)
public abstract class MobGriefMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), require = 0)
    private void gle$aiStepHead(CallbackInfo ci) {
        if (GLEConfig.enableEntityGriefing.get()) {
            GriefContext.enterEntity((Mob) (Object) this);
        }
    }

    @Inject(method = "aiStep", at = @At("RETURN"), require = 0)
    private void gle$aiStepReturn(CallbackInfo ci) {
        // Передаём себя: HEAD мог не сработать (конфиг выключен) — тогда снимать нечего.
        GriefContext.exitEntity((Mob) (Object) this);
    }
}
