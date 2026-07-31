package com.xckeji.bj.model;

import android.graphics.Color;

public class TerrainColors {
    // HTML terrainColorMap — 完整32种
    private static final int[] COLORS = {
        0xFFcccccc, // 0 空地
        0xFFf0f0f0, // 1 平原
        0xFFf4e7c3, // 2 沙漠
        0xFFe0f7fa, // 3 矮雪山
        0xFFb2ebf2, // 4 中雪山
        0xFF80deea, // 5 高雪山
        0xFF8d6e63, // 6 矮土山
        0xFF6d4c41, // 7 中土山
        0xFF4e342e, // 8 高土山
        0xFF81c784, // 9 矮绿山
        0xFF4caf50, // 10 中绿山
        0xFF388e3c, // 11 高绿山
        0xFFffcc80, // 12 矮沙山
        0xFFffb74d, // 13 中沙山
        0xFFff9800, // 14 高沙山
        0xFF689f38, // 15 仙人掌
        0xFF2e7d32, // 16 阔叶林
        0xFFcccccc, // 17
        0xFFa5d6a7, // 18 积雪阔叶林
        0xFFcccccc, // 19
        0xFF1b5e20, // 20 针叶林
        0xFFb2dfdb, // 21 积雪针叶林
        0xFF1b5e20, // 22 热带森林
        0xFFcccccc, // 23
        0xFFcccccc, // 24
        0xFFcccccc, // 25
        0xFFffd54f, // 26 农田
        0xFFcccccc, // 27
        0xFFcccccc, // 28
        0xFFcccccc, // 29
        0xFF795548, // 30 坑
        0xFFffffff  // 31 雪地
    };

    // HTML terrainGroupNameMap — 完整名称
    private static final String[] NAMES = {
        "空地", "平原", "沙漠", "矮雪山", "中雪山", "高雪山",
        "矮土山", "中土山", "高土山", "矮绿山", "中绿山", "高绿山",
        "矮沙山", "中沙山", "高沙山", "仙人掌", "阔叶林", "",
        "积雪阔叶林", "", "针叶林", "积雪针叶林", "热带森林",
        "", "", "", "", "农田", "", "", "", "坑", "雪地"
    };

    public static int getColor(int groupId) {
        if (groupId >= 0 && groupId < COLORS.length) return COLORS[groupId];
        return 0xFFcccccc;
    }

    public static String getName(int groupId) {
        if (groupId >= 0 && groupId < NAMES.length) {
            String n = NAMES[groupId];
            return n.isEmpty() ? "组" + groupId : n;
        }
        return "未知";
    }

    public static boolean needsLightText(int groupId) {
        int c = getColor(groupId);
        double l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        return l < 0.5;
    }
}
