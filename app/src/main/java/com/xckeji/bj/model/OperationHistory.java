package com.xckeji.bj.model;

import java.util.ArrayList;
import java.util.List;

/** 统一撤销/重做：每次编辑前 save()，undo()/redo() 恢复全量地图内容快照。 */
public class OperationHistory {
    private static final int MAX_STEPS = 30;
    private final List<MapSnapshot> undoStack = new ArrayList<>();
    private final List<MapSnapshot> redoStack = new ArrayList<>();
    /** 供 UI 显示的步骤描述（可选）。 */
    private final List<String> undoLabels = new ArrayList<>();

    public void save(MapData data) { save(data, "编辑"); }

    public void save(MapData data, String label) {
        if (data == null) return;
        undoStack.add(MapSnapshot.of(data));
        undoLabels.add(label == null ? "编辑" : label);
        redoStack.clear();
        while (undoStack.size() > MAX_STEPS) {
            undoStack.remove(0);
            if (!undoLabels.isEmpty()) undoLabels.remove(0);
        }
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** 返回被撤销的步骤描述，失败返回 null。 */
    public String undo(MapData data) {
        if (!canUndo() || data == null) return null;
        redoStack.add(MapSnapshot.of(data));
        MapSnapshot snap = undoStack.remove(undoStack.size() - 1);
        String label = undoLabels.isEmpty() ? "编辑" : undoLabels.remove(undoLabels.size() - 1);
        if (!snap.restore(data)) {
            // 结构已变（截取/扩展后），撤销栈作废
            undoStack.clear();
            undoLabels.clear();
            redoStack.clear();
            return null;
        }
        return label;
    }

    public String redo(MapData data) {
        if (!canRedo() || data == null) return null;
        undoStack.add(MapSnapshot.of(data));
        undoLabels.add("重做");
        MapSnapshot snap = redoStack.remove(redoStack.size() - 1);
        if (!snap.restore(data)) return null;
        return "重做";
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        undoLabels.clear();
    }
}
