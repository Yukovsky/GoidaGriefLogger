package com.gle.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Единая точка получения предметного хендлера блока — для снятия снимков и для отката.
 * <p>
 * Оба пути обязаны видеть контейнер ОДИНАКОВО, иначе откат применяется не к тому, что логировалось.
 * Раньше слушатель транзакций спрашивал только capability, а восстановление умело ещё и
 * {@link Container} — из-за этого модовые машины, публикующие хендлер только по граням
 * (например горн с интерфейсом печи), не логировались вообще.
 * <p>
 * Выбирается ПОЛНЕЙШИЙ вид контейнера:
 * <ul>
 *   <li>у двойного сундука capability отдаёт объединённые 54 слота, а {@code Container}
 *       блок-сущности — только свои 27;</li>
 *   <li>у модовой машины capability нередко отдаёт лишь часть слотов (или ничего для {@code side=null}),
 *       тогда как {@code Container} — весь инвентарь, который видит игрок.</li>
 * </ul>
 */
public final class ContainerAccess {

    private ContainerAccess() {}

    @Nullable
    public static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler cap = null;
        try {
            // side=null: у ванильных сторонних контейнеров это полный инвентарь и прямая
            // нумерация слотов (SidedInvWrapper при null отдаёт getContainerSize).
            cap = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        } catch (Exception ignored) {
            // кривой провайдер у мода не должен ронять логирование
        }
        int capSlots = cap == null ? 0 : cap.getSlots();

        BlockEntity be = level.getBlockEntity(pos);
        int invSlots = be instanceof Container c ? c.getContainerSize() : 0;

        if (capSlots == 0 && invSlots == 0) return null;
        // При равенстве побеждает capability: она учитывает объединение и правила слотов.
        return invSlots > capSlots ? new InvWrapper((Container) be) : cap;
    }
}
