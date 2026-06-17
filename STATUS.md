# Состояние GoidaGriefLogger

**Дата:** 17.06.2026
**Версия:** 2.2.0 · **mod_id:** `goidagrieflogger` · **пакет:** `com.gle.*`
**План:** `../GriefLoggerExtend/docs/06_Решение_единый_писатель.md` (Путь E)
**Собирается:** да — `gradlew jar` → `build/libs/goidagrieflogger-2.0.0.jar`
**Расположение:** `Z:/goidacraft/GoidaGriefLogger/` — отдельный каталог, **собственный git-репозиторий**.

GoidaGriefLogger — это форк и **поглощение** GriefLogger (Путь E): один мод, одно соединение,
один `WriteQueue` на все события. GriefLogger с сервера убирается; в `mods.toml` он помечен
`incompatible`. Создан копированием GLE и правкой (не переписыванием).

---

## Что реализовано

### Фаза 0 — фундамент единого writer'а
- **Ребрендинг:** mod_id/имя/версия 2.0.0, лицензия Apache-2.0, файлы `LICENSE` + `NOTICE`
  (атрибуция daqem по Apache-2.0). Java-пакет оставлен `com.gle.*` (минимум churn).
- **Своя конфигурация хранилища:** `core/db/StorageSettings` (платформо-нейтральный) + секция
  `[database]` в `GLEConfig`. Рефлексия-мост `GLConfigBridge` к конфигу GL **удалён** — мод
  стал standalone, GL ему больше не нужен.
- **Полная схема во владении мода:** `SchemaMigrator.createBaseTables()` создаёт все 11 базовых
  таблиц GL (users/levels/materials/entities/usernames/blocks/containers/items/sessions/chats/
  commands). DDL перенесён из репозиториев GL **дословно**, `IF NOT EXISTS` — существующая БД GL
  полностью совместима. FK сохранены как у GL (в SQLite не форсируются, в MySQL форсируются — с
  единым писателем дедлоков нет).
- **Модульный каркас:** `platform/Platform` (граница «ядро↔загрузчик») + `NeoForgePlatform`;
  `integration/ModIntegration` + `IntegrationRegistry` + модули `CreateIntegration`/`TomsIntegration`
  (активируются по наличию modid на старте сервера).
- **JVM shutdown hook** (Ошибка 3): дренаж очереди даже при крахе до `ServerStopping`.

### Фаза 1 — все игровые события через единый writer
Перенос логирования, которое раньше делал сам GriefLogger:

| Событие | Реализация | Таблица / action |
|---|---|---|
| Вход/выход игрока | `SessionDao` + `SessionListener` | sessions JOIN=0/QUIT=1 (+ users/usernames) |
| Слом/установка блока игроком | `PlayerBlockListener` | blocks BREAK=0/PLACE=1 |
| Выброс/крафт/выплавка/съедание | `PlayerItemListener` | items DROP=2/CRAFT=4/CONSUME=6 |
| Подбор предмета | `ItemPickupListener` (был в GLE) | items PICKUP=3 |
| Бросок/выстрел | `ProjectileMixin` (+`AbstractArrowAccessor`) | items THROW=7/SHOOT=8 |
| Поломка предмета от износа | `ItemDurabilityMixin` | items BREAK_ITEM=5 |
| Убийство сущности игроком | `EntityKillListener` + `BlockLogDao.insertEntityKill` | blocks KILL=3 (type→entities) |
| Чат / команды | `TextLogDao` + `ChatCommandListener` | chats / commands |
| Транзакции контейнеров (ваниль+моды) | `ContainerTransactionListener` | containers REMOVE=0/ADD=1 |
| Доступ к ванильным интерактивным блокам | `VanillaInteractables` + `VanillaInteractListener` | blocks INTERACT=2 |
| Доступ к модовым хранилищам | `ContainerAccessListener` | blocks INTERACT=2 |
| **Эндер-сундук** (улучшение поверх GL) | `EnderChestListener` | containers ADD_ENDER=9/REMOVE_ENDER=10 |

Плюс не-игровые источники из GLE (взрывы, пистоны, хопперы, гравитация, моды, мобы, таблички,
рамки, смерть игрока) — без изменений, уже идут через тот же writer.

**Покрытие GL-событий — полное.** Из слушателей и миксинов GriefLogger ничего существенного
не осталось неперенесённым.

### Коммиты (ветка main репозитория GoidaGriefLogger)
```
76ff938  эндер-сундук и взаимодействия с ванильными блоками
93eaaba  smelt, убийство сущностей, чат и команды
4d317b8  миксин-порт throw/shoot/break предметов
835b713  перенос слушателей предметов и контейнеров на единый writer
fde3825  v2.0.0: форк и поглощение GriefLogger (Фаза 0 + начало Фазы 1)
```

---

## Что из плана ещё НЕ реализовано

### Фаза 2 — оптимизация схемы под нагрузку (docs/06 §6, §8)
- [x] **In-memory кэши id** (главный пункт) — `core/db/IdCache` (uuid→id для users; name→id для
      materials/levels/entities). `INSERT` в справочник идёт только при ПЕРВОМ появлении имени;
      попадание в кэш отдаёт готовый int-id без обращения к БД. Все 6 DAO переведены: горячие
      вставки (blocks/containers/items/sessions/chats/commands/gle_*) кладут int-id напрямую —
      без `INSERT IGNORE` на каждое событие и без подзапросов `(SELECT id ...)`. Кэш разделяется
      всеми DAO через `GLStorage`. Кэш живёт только на потоке `WriteQueue` (гонок нет).
      **Инвалидация:** при откате пакета `WriteQueue` вызывает `IdCache.clear()` (хук `setOnRollback`),
      т.к. откат мог отменить вставку в справочник, уже попавшую в кэш → иначе повисла бы FK-ссылка.
      Пользователь, которого ещё нет (промах по uuid), не кэшируется; для NOT NULL колонок строка
      пропускается (как и раньше — подзапрос GL давал NULL и ронял вставку).
- [x] **`DROP FOREIGN KEY`** с blocks/containers/items/sessions/chats/commands — `SchemaMigrator.
      dropForeignKeys()`. Имена FK берутся из `information_schema`, снимаются `ALTER TABLE ... DROP
      FOREIGN KEY` (в InnoDB — мгновенно, метаданные). Только MySQL: в SQLite FK не форсируются
      (PRAGMA off) и локов не берут — снимать нечего. Идемпотентно (нет FK → no-op).
- [x] **Append-only** в горячем пути — уже выполнено: все DAO делают только `INSERT`, ни одного
      `UPDATE`/`REPLACE`/`ON DUPLICATE` в пакете `db` (проверено grep'ом). Флаг `rolled_back`
      обновляется только при откате, не на горячем пути.
- [x] **Ревизия композитных индексов** — `SchemaMigrator.createLookupIndexes()`. Для blocks/
      containers/items/sessions: `(level,x,z,time)` (заменил прежний `(level,x,z)` — был его
      префиксом), `(user,time)`, плюс `(time)` для запросов «во всех мирах». `(type,time)` из
      общего списка плана НЕ заводим: lookup фильтрует материал уже после JOIN по `m.name`, а не
      по `blocks.type` — индекс был бы налогом на запись без выигрыша. Проверено на SQLite:
      идемпотентно, планировщик берёт `idx_*_pos_time` на радиус+время.
- [ ] **Бенч на копии БД** (10M+ строк, пиковые bulk-события) — единственный незакрытый пункт
      Фазы 2. Требует живой копии БД и запущенного сервера (которого ещё не было — см. cutover
      ниже); из кода не выполняется. Делать на cutover вместе с прогоном dev-сервера.

### Модуляризация интеграций (docs/06 §1 Фаза 1, §9)
- [ ] Перенести **обвязку** Create/Tom's/Backpacks в модули `integration/<mod>/` за `ModIntegration`.
      Сейчас `CreateIntegration`/`TomsIntegration` — только точки учёта/гейта; сама логика
      по-прежнему в `integration/CreateLogger`, `TomsTerminalLogger` и подключается миксинами
      `mixin/create/*`, `mixin/toms/*` (self-activate через `require=0`). Нужно завести отдельные
      mixin-конфиги на интеграцию и переселить листенеры под `onActivate()`.
- [ ] Backpacks как отдельный модуль интеграции (сейчас покрывается общими listener'ами).

### Чистота ядра (docs/06 §9 — правило «core без импортов loader/модов»)
- [ ] `com.gle.core.*` ещё импортирует `net.minecraft.*` (допустимо) **и** `com.gle.GLEConfig`
      (который тянет NeoForge `ModConfigSpec`). `BlockLogger`, `ItemLogger`, `GLESourceResolver`,
      `NbtUtil` и др. не «чистые». Нужно развести: вынести конфиг-доступ за интерфейс ядра, чтобы
      `core` зависел только от ванильных классов и чистой Java (требование для будущего Fabric).
- [ ] `db/GLDatabase`, `WriteQueue`, DAO, `rollback/`, `command/` физически ещё в пакетах
      `com.gle.db`/`com.gle.rollback`/`com.gle.command`, а не в `com.gle.core.*` (как в целевой
      схеме §6). Сейчас они платформо-нейтральны по содержимому, но не переселены в `core/`.

### Мелкие фиксы (docs/06 §8)
- [ ] **#1 refmap в jar** (`goidagrieflogger.refmap.json`). На NeoForge 1.21 рантайм Mojmap →
      функционально не критично, убирает WARN; **для будущего Fabric обязательно**.
- [ ] **Бандлинг JDBC-драйверов** в проде. GL раньше вёз sqlite-jdbc; теперь его нет. Сейчас
      драйверы только `compileOnly`+`localRuntime` (dev). Для прода: MySQL-коннектор ставится
      отдельно (как и раньше), а sqlite-jdbc нужно положить в classpath **или** забандлить через
      jarJar (теперь безопасно — конфликт был только с sqlite от GL, а GL удалён).

### Fabric (docs/06 §9)
- [ ] СЕЙЧАС НЕ ДЕЛАЕМ намеренно. Каркас (`Platform`/`ModIntegration`) заложен, чтобы дописать
      потом без правки ядра. Требует: реализацию `Platform` для Fabric, Fabric-варианты мод-модулей,
      перевод прав/capability. Зависит от пункта «чистота ядра».

### Cutover и проверка (docs/06 §10, §12)
- [ ] **Рантайм не проверялся на dev-сервере.** Компиляция и сборка jar зелёные, но применимость
      миксинов (`ProjectileMixin`, `ItemDurabilityMixin`, `AbstractArrowAccessor`) и работа всех
      новых листенеров на живом сервере **не верифицированы**. Нужен прогон dev-сервера + проверка
      `/gl search`/`rollback` на свежих и старых записях.
- [ ] Cutover-чеклист (§10): бэкап БД → стоп сервера → убрать `grieflogger.jar` → поставить
      `goidagrieflogger.jar` → старт → проверка lookup/rollback. Держать старый GL.jar для отката.

---

## Известные осознанные решения
- **Эндер-сундук:** GL объявлял коды ADD_ENDER/REMOVE_ENDER, но НЕ логировал их. Реализовано как
  улучшение поверх GL (снимок личного эндер-инвентаря open→close).
- **Имя сущности в kill-строках** хранится С префиксом `minecraft:` (как у GL), в отличие от
  материалов (без префикса).
- **Взаимодействия с блоками** портированы дословно из `BlockHandler.isBlockIntractable` (точный
  набор классов, не эвристика). `ContainerAccessListener` откатан к модовым блокам, чтобы не
  дублировать ваниль.
- **FK НЕ снимаются в Фазе 1** — схема сохранена идентичной GL ради совместимости старой БД;
  снятие FK — отдельный шаг Фазы 2.
