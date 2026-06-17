package com.gle.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Системные «пользователи» для атрибуции не-игровых действий — модель CoreProtect.
 * В БД GriefLogger каждое действие в таблице {@code blocks} ссылается на {@code users.id}.
 * Для взрывов/пистонов/хопперов/модов мы используем заранее заведённых системных юзеров,
 * а точный источник кладём в колонку {@code source_type}.
 * <p>
 * Эти записи добавляются один раз в таблицу {@code users} миграцией ({@code INSERT OR IGNORE}).
 */
public final class SystemUsers {

    private SystemUsers() {}

    public static final String SERVER  = "[SERVER]";
    public static final String HOPPER  = "[HOPPER]";
    public static final String PISTON  = "[PISTON]";
    public static final String FIRE    = "[FIRE]";
    public static final String LAVA    = "[LAVA]";
    public static final String WATER   = "[WATER]";
    public static final String CREATE  = "[CREATE]";
    public static final String GRAVITY = "[GRAVITY]";
    public static final String MOB     = "[MOB]";
    public static final String TNT     = "[TNT]";
    public static final String CREEPER = "[CREEPER]";
    public static final String AUTO    = "[AUTO]";

    /** name -> фиксированный UUID. Порядок сохранён для детерминированной миграции. */
    public static final Map<String, String> ALL = new LinkedHashMap<>();
    static {
        ALL.put(SERVER,  "00000000-0000-0000-0000-000000000001");
        ALL.put(HOPPER,  "00000000-0000-0000-0000-000000000002");
        ALL.put(PISTON,  "00000000-0000-0000-0000-000000000003");
        ALL.put(FIRE,    "00000000-0000-0000-0000-000000000004");
        ALL.put(WATER,   "00000000-0000-0000-0000-000000000006");
        ALL.put(LAVA,    "00000000-0000-0000-0000-000000000005");
        ALL.put(CREATE,  "00000000-0000-0000-0000-000000000007");
        ALL.put(GRAVITY, "00000000-0000-0000-0000-000000000008");
        ALL.put(MOB,     "00000000-0000-0000-0000-000000000009");
        ALL.put(TNT,     "00000000-0000-0000-0000-00000000000a");
        ALL.put(CREEPER, "00000000-0000-0000-0000-00000000000b");
        ALL.put(AUTO,    "00000000-0000-0000-0000-00000000000c");
    }

    public static String uuidOf(String name) {
        return ALL.get(name);
    }
}
