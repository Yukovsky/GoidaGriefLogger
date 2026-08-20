package com.gle.core.db;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * Метка мира, живущая ВМЕСТЕ с сохранением.
 * <p>
 * Смысл: логи имеют смысл только для того мира, в котором они собраны. Если карту сбросили,
 * координаты в базе указывают в никуда, а откат по ним способен изуродовать новый мир.
 * Нужен признак «это уже другой мир».
 * <p>
 * Ни сид, ни имя мира для этого не годятся: сид можно переиспользовать при регенерации, а имя
 * задаётся вручную и переживает сброс. Поэтому метка — случайный uuid, который кладётся
 * в {@link SavedData} мира ({@code <мир>/data/goidagrieflogger_world.dat}). Сброс карты уносит
 * файл вместе с миром, при следующем старте генерируется новая метка — и расхождение с той,
 * что записана в базе, однозначно означает смену мира.
 * <p>
 * В конфиге такую метку хранить нельзя: конфиг переживает сброс мира и ничего бы не показал.
 */
public final class WorldIdentity extends SavedData {

    private static final String FILE = "goidagrieflogger_world";

    private UUID id;

    private WorldIdentity(UUID id) {
        this.id = id;
    }

    private WorldIdentity() {
        this(UUID.randomUUID());
        setDirty(); // только что созданную метку обязательно сохранить
    }

    private static WorldIdentity load(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            return new WorldIdentity(UUID.fromString(tag.getString("id")));
        } catch (Exception e) {
            // Повреждённая метка — заводим новую, но НЕ считаем это сменой мира молча:
            // расхождение всплывёт обычной проверкой и админ увидит предупреждение.
            return new WorldIdentity();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("id", id.toString());
        return tag;
    }

    public UUID id() {
        return id;
    }

    /** Метка текущего мира; создаётся при первом обращении. Хранится в оверворлде. */
    public static WorldIdentity of(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorldIdentity::new, WorldIdentity::load), FILE);
    }
}
