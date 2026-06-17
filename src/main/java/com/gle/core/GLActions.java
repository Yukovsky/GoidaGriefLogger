package com.gle.core;

/**
 * Целочисленные коды действий GriefLogger (НЕ строки — таблицы {@code actions} в GL не существует).
 * Значения совпадают с enum {@code BlockAction}/{@code ItemAction} в GriefLogger и читаются его
 * инспектором. GLE переиспользует именно их, различая источник через {@code source_type}.
 */
public final class GLActions {

    private GLActions() {}

    // BlockAction (таблица blocks)
    public static final int BREAK_BLOCK    = 0;
    public static final int PLACE_BLOCK    = 1;
    public static final int INTERACT_BLOCK = 2;
    public static final int KILL_ENTITY    = 3;

    // ItemAction (таблицы containers/items) — значения совпадают с enum ItemAction в GL
    public static final int REMOVE_ITEM  = 0;
    public static final int ADD_ITEM     = 1;
    public static final int DROP_ITEM    = 2;
    public static final int PICKUP_ITEM  = 3;
    public static final int CRAFT_ITEM   = 4;
    public static final int BREAK_ITEM   = 5;
    public static final int CONSUME_ITEM = 6;
    public static final int THROW_ITEM   = 7;
    public static final int SHOOT_ITEM   = 8;

    // SessionAction (таблица sessions)
    public static final int SESSION_JOIN = 0;
    public static final int SESSION_QUIT = 1;
}
