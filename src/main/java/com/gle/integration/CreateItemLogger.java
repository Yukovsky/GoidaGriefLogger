package com.gle.integration;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.ItemLogger;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Логирование перемещений предметов механизмами Create (Mechanical Arm и т.п.).
 * Пишет в таблицу containers как пользователь {@code [CREATE]} — так движение предметов в/из
 * Create-хранилищ (включая Item Vault) попадает в историю и поддаётся откату.
 */
public final class CreateItemLogger {

    private CreateItemLogger() {}

    /** @param add true = предмет добавлен в контейнер на pos; false = извлечён. */
    public static void log(ServerLevel level, BlockPos pos, ItemStack stack, int amount, boolean add) {
        if (!GLEConfig.enableCreateIntegration.get()) return;
        // если включён универсальный перехват предметов — он покроет руку тоже (не дублируем)
        if (AutomationItemLogger.enabled()) return;
        ItemLogger.log(level, pos, stack, amount,
                add ? GLActions.ADD_ITEM : GLActions.REMOVE_ITEM,
                "create:mechanical_arm", SystemUsers.CREATE);
    }
}
