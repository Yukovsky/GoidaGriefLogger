package com.gle.core;

import com.gle.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Логирование активации блоков, которую сам GriefLogger не пишет: нажимные плиты и тропвайр
 * (срабатывают наступанием без права-клика). Кнопки/рычаги/двери/люки/калитки/репитеры
 * GriefLogger логирует сам по right-click — их здесь НЕ дублируем.
 * <p>
 * Атрибуция: реальный игрок — пишется под своим именем (его UUID уже есть в {@code users});
 * моб/прочая сущность — под системным [MOB] с {@code source_type=entity:<тип>}; срабатывание
 * по тику без сущности (например, снятие сигнала) не логируется.
 */
public final class ActivationLogger {

    private ActivationLogger() {}

    /** Залогировать факт активации блока сущностью {@code entity} (нажатие). */
    public static void logActivation(Level level, BlockPos pos, BlockState state, @Nullable Entity entity) {
        if (!CoreConfig.get().blockActivationEnabled()) return;
        if (entity == null) return; // активация без сущности (тик/редстоун) — не атрибутируем
        if (!(level instanceof ServerLevel serverLevel)) return;

        String userUuid;
        String sourceType;
        if (entity instanceof ServerPlayer player && !Platform.isFake(player)) {
            userUuid = player.getUUID().toString();
            sourceType = SourceType.ACTIVATE;
        } else {
            userUuid = SystemUsers.uuidOf(SystemUsers.MOB);
            sourceType = SourceType.entity(GLESourceResolver.entityTypePath(entity));
        }

        BlockLogger.logAs(serverLevel, pos.immutable(), state, GLActions.INTERACT_BLOCK,
                sourceType, userUuid, null, null, false);
    }
}
