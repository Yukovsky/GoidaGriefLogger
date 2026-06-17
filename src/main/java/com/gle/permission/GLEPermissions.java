package com.gle.permission;

import com.gle.GLE;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.Optional;

/**
 * Права в стиле CoreProtect: один узел на действие. По умолчанию каждый узел требует OP-уровень 2
 * (как {@code default: op} у CoreProtect), но менеджер прав может выдать узел любому игроку.
 *
 * <p>Узлы (точечная форма — то, что пишут в конфиге FTB Ranks / LuckPerms):
 * <ul>
 *   <li>{@code gle.rollback} — /gl rollback</li>
 *   <li>{@code gle.restore} — /gl restore</li>
 *   <li>{@code gle.lookup} — /gl search и /gl spage (просмотр истории)</li>
 *   <li>{@code gle.preview} — /gl preview [cancel]</li>
 *   <li>{@code gle.abort} — /gl abort</li>
 *   <li>{@code gle.status} — /gl status</li>
 *   <li>{@code gle.help} — /gl help и /gl без аргументов</li>
 * </ul>
 *
 * <p>Порядок проверки: NeoForge {@code PermissionAPI} (покрывает LuckPerms-как-мод и OP-fallback) →
 * FTB Ranks (через {@code FTBRanksAPI}, т.к. он не цепляется к {@code PermissionAPI}) → иначе отказ.
 * Консоль и командные блоки проходят по OP-уровню.
 */
@EventBusSubscriber(modid = GLE.MOD_ID)
public final class GLEPermissions {

    /** OP-уровень по умолчанию (CoreProtect: default op). */
    private static final int OP_LEVEL = 2;

    public static final PermissionNode<Boolean> ROLLBACK = node("rollback");
    public static final PermissionNode<Boolean> RESTORE  = node("restore");
    public static final PermissionNode<Boolean> LOOKUP   = node("lookup");
    public static final PermissionNode<Boolean> PREVIEW  = node("preview");
    public static final PermissionNode<Boolean> ABORT    = node("abort");
    public static final PermissionNode<Boolean> STATUS   = node("status");
    public static final PermissionNode<Boolean> HELP     = node("help");

    /** Точечные формы для FTB Ranks / LuckPerms-как-Bukkit. */
    public static final String N_ROLLBACK = "gle.rollback";
    public static final String N_RESTORE  = "gle.restore";
    public static final String N_LOOKUP   = "gle.lookup";
    public static final String N_PREVIEW  = "gle.preview";
    public static final String N_ABORT    = "gle.abort";
    public static final String N_STATUS   = "gle.status";
    public static final String N_HELP     = "gle.help";

    private GLEPermissions() {}

    private static PermissionNode<Boolean> node(String name) {
        return new PermissionNode<>(GLE.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, playerUUID, context) -> player != null && player.hasPermissions(OP_LEVEL));
    }

    @SubscribeEvent
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(ROLLBACK, RESTORE, LOOKUP, PREVIEW, ABORT, STATUS, HELP);
    }

    private static boolean has(CommandSourceStack src, PermissionNode<Boolean> node, String stringNode) {
        if (src.getEntity() instanceof ServerPlayer sp) {
            // PermissionAPI: LuckPerms-как-мод + OP-fallback внутри резолвера узла.
            if (Boolean.TRUE.equals(PermissionAPI.getPermission(sp, node))) {
                return true;
            }
            // FTB Ranks напрямую (его узлы не видны через PermissionAPI).
            Optional<Boolean> ftb = FtbRanksPermissions.check(sp, stringNode);
            return ftb.orElse(false);
        }
        // Консоль / командный блок.
        return src.hasPermission(OP_LEVEL);
    }

    public static boolean canRollback(CommandSourceStack src) { return has(src, ROLLBACK, N_ROLLBACK); }
    public static boolean canRestore(CommandSourceStack src)  { return has(src, RESTORE, N_RESTORE); }
    public static boolean canLookup(CommandSourceStack src)   { return has(src, LOOKUP, N_LOOKUP); }
    public static boolean canPreview(CommandSourceStack src)  { return has(src, PREVIEW, N_PREVIEW); }
    public static boolean canAbort(CommandSourceStack src)    { return has(src, ABORT, N_ABORT); }
    public static boolean canStatus(CommandSourceStack src)   { return has(src, STATUS, N_STATUS); }
    public static boolean canHelp(CommandSourceStack src)     { return has(src, HELP, N_HELP); }

    /** Доступ хотя бы к одному действию — гейт корневого узла {@code /gl}. */
    public static boolean canAny(CommandSourceStack src) {
        return canRollback(src) || canRestore(src) || canLookup(src) || canPreview(src)
                || canAbort(src) || canStatus(src) || canHelp(src);
    }
}
