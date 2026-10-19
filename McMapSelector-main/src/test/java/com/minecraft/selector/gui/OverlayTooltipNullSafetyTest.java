package com.minecraft.selector.gui;

import com.minecraft.selector.core.WorldAnchors;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动时"没有地图"的根因：世界加载过程中 resetSelection() 会触发 refreshHover()，
 * 而画布还没渲染出图时 worldToScreenCoordinates 返回 null，tooltip() 直接调 .distance()
 * 抛 NPE，把 applyPreparedWorld() 整个打断 —— 初始地图因此从未启动。
 */
class OverlayTooltipNullSafetyTest {

    /** screen 返回 null 时不得抛异常（旧实现在这里 NPE）。 */
    @Test void tooltipToleratesNullScreenPoints() {
        ViewOverlays overlays = new ViewOverlays();
        overlays.markers = true;
        Function<Point, Point> nullScreen = p -> null;
        assertEquals("", overlays.tooltip(new Point(0, 0), nullScreen));
    }

    /** 有锚点（Spawn/Player）时同样要能安全处理 null。 */
    @Test void tooltipToleratesNullScreenWithAnchorsAndWaypoints() {
        ViewOverlays overlays = new ViewOverlays();
        overlays.markers = true;
        com.minecraft.selector.nbt.NBTReader.NBTCompound root = new com.minecraft.selector.nbt.NBTReader.NBTCompound("");
        root.put("SpawnX", new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnX", 5));
        root.put("SpawnZ", new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnZ", 7));
        overlays.anchors = WorldAnchors.parse(root);
        overlays.waypoints.add(new com.minecraft.selector.core.WaypointStore.Marker("base", 1, 2, "minecraft:overworld"));
        assertNotNull(overlays.anchors.spawn);
        assertEquals(1, overlays.waypoints.list().size());
        assertEquals("", overlays.tooltip(new Point(0, 0), p -> null));
    }

    /** 正常情况（screen 可用）仍要能命中锚点，别把功能改坏。 */
    @Test void tooltipStillMatchesNearbyAnchors() {
        ViewOverlays overlays = new ViewOverlays();
        overlays.markers = true;
        com.minecraft.selector.nbt.NBTReader.NBTCompound root = new com.minecraft.selector.nbt.NBTReader.NBTCompound("");
        root.put("SpawnX", new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnX", -20));
        root.put("SpawnZ", new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnZ", 30));
        overlays.anchors = WorldAnchors.parse(root);
        // 恒等映射：世界坐标就是屏幕坐标
        String hit = overlays.tooltip(new Point(-20, 30), p -> p);
        assertTrue(hit.contains("Spawn (-20, 30)"), hit);
        // 距离超过 12 就不该命中
        assertEquals("", overlays.tooltip(new Point(200, 200), p -> p));
    }

    /** markers 关掉时直接返回空串（不受 null 影响）。 */
    @Test void tooltipReturnsEmptyWhenMarkersDisabled() {
        ViewOverlays overlays = new ViewOverlays();
        overlays.markers = false;
        assertEquals("", overlays.tooltip(new Point(0, 0), p -> null));
    }
}
