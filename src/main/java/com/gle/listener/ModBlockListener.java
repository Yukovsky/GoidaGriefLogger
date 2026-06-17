package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.GLESourceResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

/**
 * REQ-LOG-MOD-*: универсальный перехват изменений блоков НЕ настоящими игроками
 * (моды, мобы-через-EntityPlace, fake-players вроде Create Deployer).
 * <p>
 * Корректная атрибуция (исправление M5): пропускаем только НАСТОЯЩИХ игроков
 * ({@code ServerPlayer && !FakePlayer}) — их логирует сам GriefLogger. Fake-players
 * (Create Deployer и пр.) логируем мы, т.к. для GL это «игрок» с неверной атрибуцией.
 * <p>
 * Ограничение: {@code BlockEvent.BreakEvent} в NeoForge привязан к игроку, а прямые
 * {@code level.setBlock()} из модов событий не порождают. Полный перехват «любого» слома
 * блока требует Mixin в установку блока (отложено за пределы MVP).
 */
public final class ModBlockListener {

    /** Установка блока сущностью (падающие блоки, эндермен, fake-players, мод-сущности). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Entity entity = event.getEntity();
        if (isRealPlayer(entity)) return; // GriefLogger обрабатывает настоящих игроков

        GLESourceResolver.Resolved src = GLESourceResolver.resolve(entity);
        if (!categoryEnabled(src.sourceType())) return;

        BlockState placed = event.getPlacedBlock();
        BlockLogger.log(level, event.getPos(), placed, GLActions.PLACE_BLOCK,
                src.sourceType(), src.systemUser(), null, null, false);
    }

    /**
     * Слом блока: ловим только fake-players (Create Deployer и пр.). Настоящих игроков пишет
     * {@link PlayerBlockListener} (поглощение GL, Путь E) — здесь они пропускаются.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Player player = event.getPlayer();

        // Настоящий игрок — не наш случай (его пишет PlayerBlockListener).
        if (player instanceof ServerPlayer && !(player instanceof FakePlayer)) return;

        if (!(player instanceof FakePlayer)) return; // прочие — не наш случай

        GLESourceResolver.Resolved src = GLESourceResolver.resolve(player);
        if (!categoryEnabled(src.sourceType())) return;

        BlockState state = event.getState();
        BlockLogger.log(level, event.getPos(), state, GLActions.BREAK_BLOCK,
                src.sourceType(), src.systemUser(), null, null, true);
    }

    /** Включена ли категория источника в конфиге. */
    private static boolean categoryEnabled(String sourceType) {
        if ("gravity".equals(sourceType)) return GLEConfig.enableGravityBlocks.get();
        if (sourceType != null && sourceType.startsWith("entity:")) return GLEConfig.enableEntityGriefing.get();
        return GLEConfig.enableModBlockChanges.get();
    }

    private static boolean isRealPlayer(@Nullable Entity entity) {
        return entity instanceof ServerPlayer && !(entity instanceof FakePlayer);
    }
}
