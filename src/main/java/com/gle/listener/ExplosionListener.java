package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.GLESourceResolver;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * REQ-LOG-001: логирование взрывов. Перехватывает {@link ExplosionEvent.Detonate} на
 * {@code LOWEST}, чтобы прочитать итоговый список затронутых блоков ПОСЛЕ их фильтрации,
 * но ДО фактического удаления (состояние блоков ещё в мире).
 */
public final class ExplosionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/Explosion");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDetonate(ExplosionEvent.Detonate event) {
        if (!GLEConfig.enableExplosions.get()) return;
        Level level = event.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;

        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected.isEmpty()) return;

        Explosion explosion = event.getExplosion();
        Entity direct = explosion.getDirectSourceEntity();
        Entity indirect = explosion.getIndirectSourceEntity();

        // Центр взрыва — по нему связываем взрывы без сущности (кровать, якорь) с игроком.
        BlockPos center = BlockPos.containing(explosion.center());

        String sourceType = resolveSourceType(direct, indirect, center);
        UUID causingPlayer = resolvePlayer(direct, indirect, center);
        String systemUser = resolveSystemUser(sourceType);

        int max = GLEConfig.maxExplosionBlocks.get();
        int count = 0;
        for (BlockPos pos : affected) {
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir()) continue;
            if (count >= max) {
                LOGGER.warn("Взрыв превысил лимит {} блоков (всего {}) в {} около {} — ОСТАТОК НЕ ЗАЛОГИРОВАН "
                                + "и не может быть откачен. Увеличьте maxExplosionBlocks.",
                        max, affected.size(), level.dimension().location(), pos);
                break;
            }
            // Автор строки — системный [EXPLOSION]: в списке взрыв должен читаться как взрыв,
            // а не как обычный слом блока игроком. Кто его устроил, видно при наведении —
            // для этого uuid кладётся в source_player_uuid.
            BlockLogger.log(serverLevel, pos, state, GLActions.BREAK_BLOCK,
                    sourceType, systemUser, causingPlayer, null, true);
            count++;
        }
    }

    /**
     * Игрок, устроивший взрыв, если он известен.
     * <p>
     * Ваниль уже отвечает на это в большинстве случаев: у зажжённого динамита
     * {@code getIndirectSourceEntity} возвращает того, кто его поджёг, а у снаряда — стрелка
     * (так же работают снаряды Create Big Cannons — они наследуют {@code Projectile}).
     * Остаётся случай без сущности-источника: кровать и якорь возрождения — их закрывает
     * {@link BlastPrimingTracker}.
     */
    @Nullable
    private UUID resolvePlayer(@Nullable Entity direct, @Nullable Entity indirect, BlockPos center) {
        if (indirect instanceof Player p) return p.getUUID();
        if (direct instanceof Player p) return p.getUUID();
        if (direct == null && indirect == null) return BlastPrimingTracker.resolve(center);
        return null;
    }

    /**
     * Что именно взорвалось. Неизвестные источники раньше сваливались в общий «explosion»,
     * из-за чего модовые взрывы нельзя было отличить друг от друга и отфильтровать.
     * Теперь у них остаётся registry-имя сущности, например {@code explosion:createbigcannons:shell}.
     */
    private String resolveSourceType(@Nullable Entity direct, @Nullable Entity indirect, BlockPos center) {
        Entity e = direct != null ? direct : indirect;
        if (e instanceof PrimedTnt) return SourceType.TNT;
        if (e instanceof MinecartTNT) return SourceType.TNT;
        if (e instanceof Creeper) return SourceType.CREEPER;
        if (e instanceof EndCrystal) return SourceType.END_CRYSTAL;
        if (e instanceof LargeFireball) return SourceType.GHAST;
        if (e instanceof WitherSkull) return SourceType.WITHER_SKULL;
        if (e instanceof WitherBoss) return SourceType.WITHER;
        if (e == null) {
            // Без сущности-источника: кровать и якорь возрождения. Их различает трекер.
            return BlastPrimingTracker.primedNear(center) ? SourceType.BED : SourceType.EXPLOSION_OTHER;
        }
        // Модовый источник — сохраняем его имя, иначе всё сливается в один «explosion».
        return SourceType.EXPLOSION_OTHER + ":" + GLESourceResolver.entityTypeId(e);
    }

    /**
     * Все взрывы пишутся на одного системного пользователя. Раньше их было несколько
     * ({@code [TNT]}, {@code [CREEPER]}, {@code [SERVER]}), и по автору строки нельзя было понять,
     * что это вообще взрыв: {@code [SERVER]} означал и модовый взрыв, и что угодно ещё.
     * Чем именно взорвано — в {@code source_type}, кто устроил — в {@code source_player_uuid}.
     */
    private String resolveSystemUser(String sourceType) {
        return SystemUsers.EXPLOSION;
    }
}
