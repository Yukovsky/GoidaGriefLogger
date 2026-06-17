package com.gle.mixin;

import com.gle.GLEConfig;
import com.gle.db.GLStorage;
import com.gle.db.GleEventsDao;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * REQ-LOG-006: логирование изменения текста табличек.
 * Инъекция в {@code SignBlockEntity.updateSignText(Player, boolean, List&lt;FilteredText&gt;)} —
 * штатный путь редактирования игроком (даёт и игрока, и сторону). На HEAD снимаем текст «до»,
 * на RETURN — «после», и пишем в {@code gle_signs}.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {

    @Unique private static final ThreadLocal<String[]> gle$before = new ThreadLocal<>();

    @Inject(method = "updateSignText", at = @At("HEAD"), require = 0)
    private void gle$captureBefore(Player player, boolean isFrontText, List<FilteredText> filteredText, CallbackInfo ci) {
        if (!GLEConfig.enableSigns.get()) return;
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        gle$before.set(new String[]{ gle$render(self.getFrontText()), gle$render(self.getBackText()) });
    }

    @Inject(method = "updateSignText", at = @At("RETURN"), require = 0)
    private void gle$logAfter(Player player, boolean isFrontText, List<FilteredText> filteredText, CallbackInfo ci) {
        if (!GLEConfig.enableSigns.get()) return;
        String[] before = gle$before.get();
        gle$before.remove();
        if (before == null) return;
        if (!GLStorage.isReady()) return;

        SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) return;

        String frontAfter = gle$render(self.getFrontText());
        String backAfter = gle$render(self.getBackText());
        if (before[0].equals(frontAfter) && before[1].equals(backAfter)) return; // без изменений

        SignText txt = self.getFrontText();
        String flags = "{\"color\":\"" + txt.getColor().getName() + "\",\"glowing\":" + txt.hasGlowingText() + "}";

        BlockPos pos = self.getBlockPos();
        GLStorage.get().events().insertSign(new GleEventsDao.SignEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                before[0], before[1], frontAfter, backAfter, flags
        ));
    }

    @Unique
    private static String gle$render(SignText text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append('\n');
            sb.append(text.getMessage(i, false).getString());
        }
        return sb.toString();
    }
}
