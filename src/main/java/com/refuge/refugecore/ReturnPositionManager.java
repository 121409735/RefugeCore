package com.refuge.refugecore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 返回坐标管理器
 * 记录玩家进入家园前在主世界的精确位置和朝向，返回时恢复；支持跨重启持久化。
 * 存档路径：<世界>/refugecore/return_positions.json
 */
public class ReturnPositionManager {
    private static ReturnPositionManager INSTANCE;

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, double[]> positions = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, double[]>>() {}.getType();

    private ReturnPositionManager(MinecraftServer server) {
        this.server = server;
        this.file = server.getWorldPath(LevelResource.ROOT)
                .resolve("refugecore").resolve("return_positions.json");
    }

    public static void init(MinecraftServer server) {
        ReturnPositionManager mgr = new ReturnPositionManager(server);
        try {
            if (Files.exists(mgr.file)) {
                Map<String, double[]> loaded = mgr.gson.fromJson(Files.readString(mgr.file), MAP_TYPE);
                if (loaded != null) mgr.positions.putAll(loaded);
            }
            Files.createDirectories(mgr.file.getParent());
        } catch (Exception e) {
            RefugeCore.LOGGER.error("Failed to load return positions", e);
        }
        INSTANCE = mgr;
        RefugeCore.LOGGER.info("ReturnPositionManager loaded {} entries", mgr.positions.size());
    }

    public static ReturnPositionManager get() {
        return INSTANCE;
    }

    public void savePosition(UUID uuid, double x, double y, double z, float yaw, float pitch) {
        positions.put(uuid.toString(), new double[]{x, y, z, yaw, pitch});
        save();
    }

    public double[] removePosition(UUID uuid) {
        double[] pos = positions.remove(uuid.toString());
        if (pos != null) save();
        return pos;
    }

    private void save() {
        try {
            Files.writeString(file, gson.toJson(positions, MAP_TYPE));
        } catch (Exception e) {
            RefugeCore.LOGGER.error("Failed to save return positions", e);
        }
    }
}
