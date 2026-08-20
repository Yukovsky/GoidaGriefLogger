package com.gle.core.command;

import com.gle.core.SystemUsers;
import com.gle.permission.GLEPermissions;
import com.gle.core.rollback.PreviewManager;
import com.gle.core.rollback.RollbackFilter;
import com.gle.core.db.GLStorage;
import com.gle.core.rollback.RollbackManager;
import com.gle.core.rollback.TimeParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Команда {@code /gl}: lookup / rollback / restore / preview / abort / status / help.
 * Op level 2 (restore чужих — level 3). Поддерживает фильтры в стиле CoreProtect и
 * автодополнение (Tab).
 */
public final class GLCommand {

    private static final UUID CONSOLE = new UUID(0L, 0L);

    /** Источники для подсказок s:. */
    private static final String[] SOURCE_TYPES = {
            "tnt", "creeper", "end_crystal", "ghast", "wither_skull", "wither", "explosion",
            "piston", "piston_destroy", "hopper", "gravity",
            "fire", "lava", "water", "melting", "sculk",
            "create:deployer", "create:contraption", "create:schematicannon"
    };
    private GLCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        SuggestionProvider<CommandSourceStack> filterSuggest = GLCommand::suggestFilters;

        dispatcher.register(Commands.literal("gl")
                // Корневой узел доступен, если есть право хотя бы на одно действие.
                // Узлы в стиле CoreProtect (gle.rollback, gle.restore, gle.lookup, …) — см. GLEPermissions.
                .requires(GLEPermissions::canAny)
                .executes(GLCommand::doHelp)
                .then(Commands.literal("help")
                        .requires(GLEPermissions::canHelp)
                        .executes(GLCommand::doHelp))
                .then(Commands.literal("rollback")
                        .requires(GLEPermissions::canRollback)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(filterSuggest)
                                .executes(ctx -> doRollback(ctx, StringArgumentType.getString(ctx, "args")))))
                .then(Commands.literal("preview")
                        .requires(GLEPermissions::canPreview)
                        .then(Commands.literal("cancel").executes(GLCommand::doPreviewCancel))
                        .then(Commands.literal("accept")
                                .requires(GLEPermissions::canRollback)
                                .executes(GLCommand::doPreviewAccept))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(filterSuggest)
                                .executes(ctx -> doPreview(ctx, StringArgumentType.getString(ctx, "args")))))
                .then(Commands.literal("restore")
                        .requires(GLEPermissions::canRestore)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(filterSuggest)
                                .executes(ctx -> doRestore(ctx, StringArgumentType.getString(ctx, "args")))))
                .then(Commands.literal("lookup")
                        .requires(GLEPermissions::canLookup)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(filterSuggest)
                                .executes(ctx -> doLookup(ctx, StringArgumentType.getString(ctx, "args")))))
                .then(Commands.literal("page")
                        // Постраничный вывод общий для lookup и inspect (кнопки навигации шлют /gl page).
                        .requires(src -> GLEPermissions.canLookup(src) || GLEPermissions.canInspect(src))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> doLookupPage(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("inspect")
                        .requires(GLEPermissions::canInspect)
                        .executes(GLCommand::doInspect))
                .then(Commands.literal("abort")
                        .requires(GLEPermissions::canAbort)
                        .executes(GLCommand::doAbort))
                // Сброс базы намеренно НЕ под правом оператора: см. isConsole().
                .then(Commands.literal("wipe")
                        .requires(GLCommand::isConsole)
                        .executes(GLCommand::doWipePrompt)
                        .then(Commands.literal("confirm").executes(GLCommand::doWipeConfirm)))
                .then(Commands.literal("status")
                        .requires(GLEPermissions::canStatus)
                        .executes(GLCommand::doStatus)));
    }

    // ---------- автодополнение ----------

    // --- принимаемые формы фильтров -------------------------------------------
    //
    // Один источник для парсера, автодополнения и подсказки. Раньше списки жили порознь,
    // и они разошлись: dim:, inc: и exc: парсер принимал, но автодополнение о них не знало,
    // и узнать об их существовании было неоткуда.

    private static final String[] F_TIME    = {"time:", "t:"};
    private static final String[] F_RADIUS  = {"radius:", "r:"};
    private static final String[] F_WORLD   = {"world:", "w:", "dim:"};
    private static final String[] F_USER    = {"user:", "u:"};
    private static final String[] F_ACTION  = {"action:", "a:"};
    private static final String[] F_SOURCE  = {"source:", "s:"};
    private static final String[] F_INCLUDE = {"include:", "inc:"};
    private static final String[] F_EXCLUDE = {"exclude:", "exc:"};
    /** Флаги без значения. */
    private static final String[] F_FLAGS   = {"blocks", "b", "items", "i"};

    /** Все формы разом — для подсказки автодополнения. */
    public static String[] allFilterForms() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] group : new String[][]{F_TIME, F_RADIUS, F_WORLD, F_USER, F_ACTION,
                F_SOURCE, F_INCLUDE, F_EXCLUDE, F_FLAGS}) {
            out.addAll(java.util.List.of(group));
        }
        return out.toArray(new String[0]);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static CompletableFuture<Suggestions> suggestFilters(CommandContext<CommandSourceStack> ctx,
                                                                 SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int tokenStart = remaining.lastIndexOf(' ') + 1;
        String token = remaining.substring(tokenStart);
        int base = builder.getStart() + tokenStart;
        SuggestionsBuilder b = builder.createOffset(base);
        String lower = token.toLowerCase();

        String pfx;
        if ((pfx = matched(lower, F_TIME)) != null) {
            for (String s : new String[]{"1h", "30m", "6h", "1d", "7d", "1d12h"}) if ((pfx + s).startsWith(lower)) b.suggest(pfx + s);
        } else if ((pfx = matched(lower, F_RADIUS)) != null) {
            for (String s : new String[]{"5", "10", "25", "50", "global"}) if ((pfx + s).startsWith(lower)) b.suggest(pfx + s);
        } else if ((pfx = matched(lower, F_WORLD)) != null) {
            b.suggest(pfx + "*");
            for (var key : ctx.getSource().getServer().levelKeys()) {
                String dim = key.location().toString();
                if ((pfx + dim).toLowerCase().startsWith(lower)) b.suggest(pfx + dim);
            }
        } else if ((pfx = matched(lower, F_USER)) != null) {
            String pref = token.substring(pfx.length()).toLowerCase();
            for (String name : SystemUsers.ALL.keySet()) if (name.toLowerCase().startsWith(pref)) b.suggest(pfx + name);
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp != null) for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
                String n = p.getGameProfile().getName();
                if (n.toLowerCase().startsWith(pref)) b.suggest(pfx + n);
            }
        } else if ((pfx = matched(lower, F_SOURCE)) != null) {
            for (String s : SOURCE_TYPES) if ((pfx + s).startsWith(lower)) b.suggest(pfx + s);
        } else if ((pfx = matched(lower, F_ACTION)) != null) {
            for (String s : new String[]{"place", "break", "use", "kill", "container", "session",
                    "!place", "!break", "!use", "!kill", "!container"}) if ((pfx + s).startsWith(lower)) b.suggest(pfx + s);
        } else if ((pfx = matched(lower, concat(F_INCLUDE, F_EXCLUDE))) != null) {
            // Любой предмет/блок игры (modid:name), как в GriefLogger.
            SuggestionsBuilder rb = builder.createOffset(base + pfx.length());
            return SharedSuggestionProvider.suggestResource(allMaterials(), rb);
        } else {
            // Из того же источника, что и парсер: добавленный фильтр попадает в подсказку сам.
            for (String key : allFilterForms()) {
                if (key.startsWith(lower)) b.suggest(key);
            }
        }
        return b.buildFuture();
    }

    /** Первый префикс из списка, с которого начинается токен (или null). */
    private static String matched(String lower, String... prefixes) {
        for (String p : prefixes) if (lower.startsWith(p)) return p;
        return null;
    }

    /** Все идентификаторы блоков и предметов игры для подсказок include:/exclude:. */
    private static java.util.stream.Stream<net.minecraft.resources.ResourceLocation> allMaterials() {
        return java.util.stream.Stream.concat(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().stream()).distinct();
    }

    /** Есть ли в реестре блок/предмет, подходящий под токен (та же семантика, что в SQL-фильтре). */
    private static boolean materialExists(String token) {
        return allMaterials().anyMatch(rl -> com.gle.core.rollback.MaterialMatcher.matches(
                com.gle.core.GLMaterials.normalize(rl), token));
    }

    /**
     * Предупредить, если токен include:/exclude: не соответствует ни одному предмету/блоку игры
     * (опечатка или неполное имя) — иначе фильтр молча сужает выборку до нуля. Только уведомление,
     * выполнение продолжается (include с пустым совпадением безопасно: откатывать нечего).
     */
    private static void warnUnknownMaterials(CommandSourceStack src, RollbackFilter f) {
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String t : f.includeMaterials) if (!materialExists(t)) unknown.add("include:" + t);
        for (String t : f.excludeMaterials) if (!materialExists(t)) unknown.add("exclude:" + t);
        if (!unknown.isEmpty()) {
            src.sendSystemMessage(Component.literal("§e[GLE] Фильтр не соответствует ни одному предмету/блоку: §f"
                    + String.join(", ", unknown)
                    + "§7. Проверьте имя (modid:name) или используйте маску, напр. include:*drain*."));
        }
    }

    // ---------- подкоманды ----------

    private static int doRollback(CommandContext<CommandSourceStack> ctx, String args) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }

        RollbackFilter f = parseFilter(args, player);
        if (f == null) { usageError(src, "rollback"); return 0; }
        warnUnknownMaterials(src, f);

        Consumer<Component> fb = msg -> src.sendSystemMessage(msg);
        String err = RollbackManager.get().startRollback(src.getServer(),
                player.getUUID(), player.getGameProfile().getName(), f, fb);
        if (err != null) { src.sendFailure(Component.literal("§c" + err)); return 0; }
        return 1;
    }

    private static int doPreview(CommandContext<CommandSourceStack> ctx, String args) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }
        RollbackFilter f = parseFilter(args, player);
        if (f == null) { usageError(src, "preview"); return 0; }
        warnUnknownMaterials(src, f);
        if (f.allWorlds) {
            src.sendFailure(Component.literal("§cpreview не поддерживает world:* — укажите конкретный мир."));
            return 0;
        }
        // Preview шлёт клиентские пакеты блоков игроку в его ТЕКУЩЕМ мире. Если указан другой мир —
        // пакеты легли бы на чужие координаты в текущем измерении (визуальный мусор). Запрещаем.
        String curDim = player.level().dimension().location().toString();
        if (!f.levelName.equals(curDim)) {
            src.sendFailure(Component.literal("§cpreview работает только в текущем мире (вы в " + curDim
                    + ", указан " + f.levelName + "). Перейдите в нужный мир или используйте /gl rollback."));
            return 0;
        }
        PreviewManager.get().start(player, f, msg -> src.sendSystemMessage(msg));
        return 1;
    }

    /**
     * Применить то, что показано в превью: берём фильтр той же команды и запускаем им откат.
     * Избавляет от повторного набора радиуса и времени — по аналогии с {@code preview cancel}.
     */
    private static int doPreviewAccept(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }
        RollbackFilter f = PreviewManager.get().accept(player);
        if (f == null) {
            src.sendSystemMessage(Component.literal("§7Активного preview нет — нечего применять."));
            return 0;
        }
        String err = RollbackManager.get().startRollback(player.server, player.getUUID(),
                player.getGameProfile().getName(), f, msg -> src.sendSystemMessage(msg));
        if (err != null) { src.sendFailure(Component.literal("§c" + err)); return 0; }
        return 1;
    }

    /**
     * Доступна ли команда сброса. Только настоящая консоль сервера и RCON: игрок с правами
     * оператора не должен мочь снести всю историю нарушений — именно от него она и защищает.
     * Командные блоки тоже отсекаются: у них свой источник, и запуск такого редстоуном
     * был бы отличным способом замести следы.
     */
    private static boolean isConsole(CommandSourceStack src) {
        return src.source instanceof net.minecraft.server.MinecraftServer
                || src.source instanceof net.minecraft.server.rcon.RconConsoleSource;
    }

    /** Пароль подтверждения живёт недолго: случайно повторить команду через час нельзя. */
    private static volatile long wipeArmedAt = 0;
    private static final long WIPE_CONFIRM_WINDOW_MS = 30_000;

    private static int doWipePrompt(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!GLStorage.isReady()) {
            src.sendFailure(Component.literal("Хранилище недоступно."));
            return 0;
        }
        wipeArmedAt = System.currentTimeMillis();
        src.sendSystemMessage(Component.literal(
                "ВНИМАНИЕ: будет удалена ВСЯ история логов и создана пустая база."));
        var guard = com.gle.core.db.WorldGuard.state();
        if (guard == com.gle.core.db.WorldGuard.State.MISMATCHED) {
            src.sendSystemMessage(Component.literal(
                    "Мир не совпадает с базой — логи относятся к прежней карте:"));
            src.sendSystemMessage(Component.literal(
                    "  в базе: " + com.gle.core.db.WorldGuard.expected()));
            src.sendSystemMessage(Component.literal(
                    "  сейчас: " + com.gle.core.db.WorldGuard.actual()));
        } else {
            src.sendSystemMessage(Component.literal(
                    "Мир СОВПАДАЕТ с базой — сброс сейчас уничтожит актуальную историю."));
        }
        src.sendSystemMessage(Component.literal(
                "Подтвердить: gl wipe confirm (в течение 30 секунд)."));
        return 1;
    }

    private static int doWipeConfirm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (System.currentTimeMillis() - wipeArmedAt > WIPE_CONFIRM_WINDOW_MS) {
            src.sendFailure(Component.literal("Подтверждение просрочено. Начните с 'gl wipe'."));
            return 0;
        }
        wipeArmedAt = 0;
        int dropped = GLStorage.wipe();
        if (dropped < 0) {
            src.sendFailure(Component.literal("Сброс не удался — подробности в логе сервера."));
            return 0;
        }
        com.gle.core.db.WorldGuard.adopt(src.getServer());
        src.sendSystemMessage(Component.literal(
                "База очищена (таблиц удалено: " + dropped + ") и закреплена за текущим миром."));
        return 1;
    }

    private static int doPreviewCancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }
        boolean any = PreviewManager.get().cancel(player);
        src.sendSystemMessage(Component.literal(any ? "§7Preview отменён." : "§7Активного preview нет."));
        return 1;
    }

    /** Restore по фильтрам (как CoreProtect): повторяет ранее откатанные изменения. */
    private static int doRestore(CommandContext<CommandSourceStack> ctx, String args) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }
        RollbackFilter f = parseFilter(args, player);
        if (f == null) { usageError(src, "restore"); return 0; }
        warnUnknownMaterials(src, f);
        Consumer<Component> fb = msg -> src.sendSystemMessage(msg);
        String err = RollbackManager.get().startRestore(src.getServer(),
                player.getUUID(), player.getGameProfile().getName(), f, fb);
        if (err != null) { src.sendFailure(Component.literal("§c" + err)); return 0; }
        return 1;
    }

    /** Lookup в стиле CoreProtect (откатанные записи — зачёркнуты). */
    private static int doLookup(CommandContext<CommandSourceStack> ctx, String args) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }
        RollbackFilter f = parseFilter(args, player);
        if (f == null) { usageError(src, "lookup"); return 0; }
        warnUnknownMaterials(src, f);
        LookupService.run(src.getServer(), f, player);
        return 1;
    }

    /** Переключить режим инспектора (клик по блоку → история места), как в CoreProtect. */
    private static int doInspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Команду должен выполнять игрок.")); return 0; }
        boolean on = InspectManager.toggle(player.getUUID());
        src.sendSystemMessage(Component.literal(on
                ? "§a[GLE] Инспектор включён. §7Клик по блоку — история места. §8(/gl inspect — выключить)"
                : "§7[GLE] Инспектор выключен."));
        return 1;
    }

    private static int doLookupPage(CommandContext<CommandSourceStack> ctx, int n) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendFailure(Component.literal("Только для игрока.")); return 0; }
        LookupService.showPage(player, n - 1);
        return 1;
    }

    private static int doAbort(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        UUID executor = player != null ? player.getUUID() : CONSOLE;
        boolean any = RollbackManager.get().abort(executor);
        src.sendSystemMessage(Component.literal(any ? "§eОстановка активного задания..." : "§7Нет активных заданий."));
        return 1;
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx) {
        // Состояние сверки мира: расхождение делает откат опасным, это должно быть видно.
        var guard = com.gle.core.db.WorldGuard.state();
        if (guard == com.gle.core.db.WorldGuard.State.MISMATCHED) {
            ctx.getSource().sendSystemMessage(Component.literal(
                    "§cМир: НЕ совпадает с базой (логи от прежней карты). Сброс: gl wipe из консоли."));
        } else if (guard == com.gle.core.db.WorldGuard.State.UNKNOWN) {
            ctx.getSource().sendSystemMessage(Component.literal("§7Мир: сверка не выполнена."));
        } else {
            ctx.getSource().sendSystemMessage(Component.literal("§aМир: совпадает с базой."));
        }
        CommandSourceStack src = ctx.getSource();
        RollbackManager mgr = RollbackManager.get();
        src.sendSystemMessage(Component.literal("§7[GLE] Активных заданий: " + mgr.activeCount()
                + ", хранилище: " + (com.gle.core.db.GLStorage.isReady() ? "§aОК" : "§cнет (см. лог)")));
        return 1;
    }

    /**
     * Подсказка рассчитана на того, кто видит команду впервые: сначала порядок работы,
     * потом сами команды, потом фильтры — обязательные отдельно от необязательных.
     * <p>
     * Консольные команды сюда НЕ попадают: из игры они всё равно недоступны, а в списке
     * только сбивали бы с толку. Их место — документация для администратора сервера.
     */
    private static int doHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        s.sendSystemMessage(Component.literal("§6=== GoidaGriefLogger — история мира и откат ==="));
        s.sendSystemMessage(Component.literal(
                "§7Кто, когда и что изменил. Можно вернуть территорию к состоянию на нужный момент."));

        s.sendSystemMessage(Component.literal("§6Порядок работы:"));
        s.sendSystemMessage(Component.literal(
                "§7 1. §e/gl inspect§7 или §e/gl lookup§7 — посмотреть, что здесь было"));
        s.sendSystemMessage(Component.literal(
                "§7 2. §e/gl preview§7 — увидеть результат отката, не меняя мир"));
        s.sendSystemMessage(Component.literal(
                "§7 3. §e/gl preview accept§7 — применить показанное"));

        s.sendSystemMessage(Component.literal("§6Команды:"));
        s.sendSystemMessage(Component.literal(
                "§e/gl inspect§7 — клик по блоку показывает историю места. Повторно — выключить."));
        s.sendSystemMessage(Component.literal(
                "§e/gl lookup <фильтры>§7 — история списком: наведение — подробности, клик — телепорт."));
        s.sendSystemMessage(Component.literal(
                "§7   Откатанное §mзачёркнуто§r§7. §e/gl page <n>§7 — другая страница."));
        s.sendSystemMessage(Component.literal(
                "§e/gl preview <фильтры>§7 — показать результат отката; область обведена частицами."));
        s.sendSystemMessage(Component.literal(
                "§7   §e/gl preview accept§7 — применить, §e/gl preview cancel§7 — убрать показ."));
        s.sendSystemMessage(Component.literal(
                "§e/gl rollback <фильтры>§7 — вернуть территорию к состоянию на указанный момент."));
        s.sendSystemMessage(Component.literal(
                "§e/gl restore <фильтры>§7 — обратная операция: вернуть то, что откатили."));
        s.sendSystemMessage(Component.literal(
                "§e/gl abort§7 — прервать своё активное задание. §e/gl status§7 — состояние мода."));

        s.sendSystemMessage(Component.literal("§6Фильтры §c— время и радиус обязательны§6, остальные по желанию:"));
        s.sendSystemMessage(Component.literal(
                "§7 §ftime:§7|§ft:§7<время> §c(обязательно)§7 — 1h, 30m, 1d, 1d12h"));
        s.sendSystemMessage(Component.literal(
                "§7 §fradius:§7|§fr:§7<число> §c(обязательно)§7 — блоков вокруг вас, либо §fr:global§7 — весь мир"));
        s.sendSystemMessage(Component.literal(
                "§7 §fuser:§7|§fu:§7<кто> — §fu:Steve§7; системные в скобках: §fu:[TNT]§7; §fu:!Steve§7 — исключить"));
        s.sendSystemMessage(Component.literal(
                "§7 §faction:§7|§fa:§7<что> — place, break, use, kill, container, session; §fa:!break§7 — исключить"));
        s.sendSystemMessage(Component.literal(
                "§7 §fworld:§7|§fw:§7|§fdim:§7<мир> — §fworld:the_nether§7, либо §fworld:*§7 — все миры"));
        s.sendSystemMessage(Component.literal(
                "§7 §fsource:§7|§fs:§7<источник> — §fs:tnt§7, §fs:creeper§7, §fs:create:deployer"));
        s.sendSystemMessage(Component.literal(
                "§7 §finclude:§7|§finc:§7 и §fexclude:§7|§fexc:§7<блок> — §fstone§7, §fminecraft:chest§7, маска §finc:*ore*"));
        s.sendSystemMessage(Component.literal(
                "§7 §fblocks§7|§fb§7 или §fitems§7|§fi§7 — только блоки либо только предметы"));

        s.sendSystemMessage(Component.literal("§8Примеры:"));
        s.sendSystemMessage(Component.literal(
                "§8 /gl lookup t:1h r:10§8 — что происходило рядом за последний час"));
        s.sendSystemMessage(Component.literal(
                "§8 /gl rollback t:2h r:20 u:Griefer§8 — откатить действия одного игрока"));
        s.sendSystemMessage(Component.literal(
                "§8 /gl rollback t:1h r:10 s:tnt§8 — откатить только урон от ТНТ"));
        return 1;
    }

    private static void usageError(CommandSourceStack src, String sub) {
        src.sendFailure(Component.literal("§cФормат: /gl " + sub + " time:<время> radius:<радиус|global> [world:<мир|*>] [user:<игрок>|u:!<игрок>] [action:<place|break|use|kill|container>] [source:<источник>] [include:<предмет>] [exclude:<предмет>] [blocks|items]"));
        src.sendFailure(Component.literal("§7Краткие имена: t: r: w: u: s:. Подробнее: /gl help. Пример: /gl " + sub + " t:1h r:10 s:tnt"));
    }

    // ---------- разбор фильтра ----------

    private static RollbackFilter parseFilter(String args, ServerPlayer player) {
        long now = System.currentTimeMillis();
        RollbackFilter f = new RollbackFilter();
        f.timeTo = now;
        f.levelName = player.level().dimension().location().toString();
        boolean hasTime = false, hasRadius = false;
        boolean itemsFlag = false, blocksFlag = false;

        for (String token : args.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase();
            String v;
            if ((v = strip(lower, token, F_TIME)) != null) {
                long dur = TimeParser.parseDurationMs(v);
                if (dur < 0) return null;
                f.timeFrom = now - dur;
                hasTime = true;
            } else if ((v = strip(lower, token, F_RADIUS)) != null) {
                if (isGlobal(v)) {
                    f.setGlobalBox();
                    hasRadius = true;
                } else {
                    try {
                        int r = Integer.parseInt(v);
                        BlockPos p = player.blockPosition();
                        f.setBox(p.getX(), p.getY(), p.getZ(), r);
                        hasRadius = true;
                    } catch (NumberFormatException e) { return null; }
                }
            } else if ((v = strip(lower, token, F_WORLD)) != null) {
                if (isGlobal(v)) f.allWorlds = true;
                else f.levelName = normDim(v);
            } else if ((v = strip(lower, token, F_USER)) != null) {
                // u:Имя — только этот игрок; u:!Имя — исключить игрока. Регистр сохраняем ([TNT] и ники).
                if (v.startsWith("!")) f.excludePlayerNames.add(v.substring(1));
                else f.playerNames.add(v);
            } else if ((v = strip(lower, token, F_ACTION)) != null) {
                // a:place / a:break / a:!use … (форма GriefLogger a:[CREATE] тоже принимается)
                boolean neg = v.startsWith("!");
                String cat = com.gle.core.rollback.ActionFilters.canon(neg ? v.substring(1) : v);
                if (cat == null) return null; // неизвестное действие — покажем формат
                (neg ? f.actionsExclude : f.actionsInclude).add(cat);
            } else if ((v = strip(lower, token, F_SOURCE)) != null) {
                f.sourceType = v;
            } else if ((v = strip(lower, token, F_INCLUDE)) != null) {
                f.includeMaterials.add(normMat(v));
            } else if ((v = strip(lower, token, F_EXCLUDE)) != null) {
                f.excludeMaterials.add(normMat(v));
            } else if (lower.equals("i") || lower.equals("items")) {
                itemsFlag = true;
            } else if (lower.equals("b") || lower.equals("blocks")) {
                blocksFlag = true;
            }
        }
        if (!hasTime || !hasRadius) return null;
        if (itemsFlag && !blocksFlag) { f.includeBlocks = false; f.includeItems = true; }
        else if (blocksFlag && !itemsFlag) { f.includeBlocks = true; f.includeItems = false; }
        return f;
    }

    /** Вернуть «хвост» токена после первого подходящего префикса (регистр сохраняется), иначе null. */
    private static String strip(String lower, String token, String... prefixes) {
        for (String p : prefixes) if (lower.startsWith(p)) return token.substring(p.length());
        return null;
    }

    private static boolean isGlobal(String v) {
        String l = v.toLowerCase();
        return l.equals("global") || l.equals("all") || l.equals("g") || l.equals("*");
    }

    /**
     * Нормализация токена материала под формат GriefLogger: нижний регистр (имена в реестре всегда
     * lowercase) и без префикса {@code minecraft:}. {@code *}-маски сохраняются как есть.
     */
    private static String normMat(String s) {
        String t = s.toLowerCase();
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }

    /** Имя измерения: алиасы overworld/nether/end → каноничные, иначе добавляем minecraft: при нужде. */
    private static String normDim(String s) {
        switch (s.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
            default: return s.contains(":") ? s : "minecraft:" + s;
        }
    }
}
