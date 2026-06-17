package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
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

        String sourceType = resolveSourceType(direct, indirect);
        String systemUser = resolveSystemUser(sourceType);
        UUID causingPlayer = (indirect instanceof Player p) ? p.getUUID() : null;

        int max = GLEConfig.maxExplosionBlocks.get();
        int count = 0;
        for (BlockPos pos : affected) {
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir()) continue;
            if (count >= max) {
                LOGGER.warn("Взрыв превысил лимит {} блоков (всего {}) в {} около {} — остаток не залогирован.",
                        max, affected.size(), level.dimension().location(), pos);
                break;
            }
            BlockLogger.log(serverLevel, pos, state, GLActions.BREAK_BLOCK,
                    sourceType, systemUser, causingPlayer, null, true);
            count++;
        }
    }

    private String resolveSourceType(@Nullable Entity direct, @Nullable Entity indirect) {
        Entity e = direct != null ? direct : indirect;
        if (e instanceof PrimedTnt) return SourceType.TNT;
        if (e instanceof Creeper) return SourceType.CREEPER;
        if (e instanceof EndCrystal) return SourceType.END_CRYSTAL;
        if (e instanceof LargeFireball) return SourceType.GHAST;
        if (e instanceof WitherSkull) return SourceType.WITHER_SKULL;
        if (e instanceof WitherBoss) return SourceType.WITHER;
        return SourceType.EXPLOSION_OTHER;
    }

    private String resolveSystemUser(String sourceType) {
        return switch (sourceType) {
            case SourceType.TNT -> SystemUsers.TNT;
            case SourceType.CREEPER -> SystemUsers.CREEPER;
            default -> SystemUsers.SERVER;
        };
    }
}
