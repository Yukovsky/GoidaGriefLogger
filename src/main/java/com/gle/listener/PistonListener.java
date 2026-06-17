package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import java.util.List;

/**
 * REQ-LOG-002: логирование пистонов. На {@link PistonEvent.Pre} вычисляем структуру движения
 * через {@link PistonStructureResolver}:
 * <ul>
 *   <li>{@code getToDestroy()} — блоки, уничтожаемые пистоном (source_type=piston_destroy);</li>
 *   <li>{@code getToPush()} — перемещаемые блоки: пишем BREAK на старой позиции и PLACE на новой,
 *       сохраняя связь в extra_data.</li>
 * </ul>
 * Точное состояние снимаем синхронно в Pre (блоки ещё на месте).
 */
public final class PistonListener {

    @SubscribeEvent
    public void onPistonPre(PistonEvent.Pre event) {
        if (!GLEConfig.enablePistons.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;

        Direction facing = event.getDirection();
        boolean extend = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND;
        Direction moveDir = extend ? facing : facing.getOpposite();

        // Уничтожаемые блоки
        for (BlockPos pos : resolver.getToDestroy()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            BlockLogger.log(level, pos, state, GLActions.BREAK_BLOCK,
                    SourceType.PISTON_DESTROY, SystemUsers.PISTON, null, null, true);
        }

        // Перемещаемые блоки
        List<BlockPos> toPush = resolver.getToPush();
        for (BlockPos from : toPush) {
            BlockState state = level.getBlockState(from);
            if (state.isAir()) continue;
            BlockPos to = from.relative(moveDir);

            String fromStr = from.getX() + "," + from.getY() + "," + from.getZ();
            String toStr = to.getX() + "," + to.getY() + "," + to.getZ();

            // Блок покидает старую позицию
            BlockLogger.log(level, from, state, GLActions.BREAK_BLOCK,
                    SourceType.PISTON, SystemUsers.PISTON, null,
                    "{\"move\":\"out\",\"to\":\"" + toStr + "\"}", true);
            // Блок появляется на новой
            BlockLogger.log(level, to, state, GLActions.PLACE_BLOCK,
                    SourceType.PISTON, SystemUsers.PISTON, null,
                    "{\"move\":\"in\",\"from\":\"" + fromStr + "\"}", false);
        }
    }
}
