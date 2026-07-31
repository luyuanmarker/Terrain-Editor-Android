package com.xckeji.bj.model;

import java.util.Stack;

public class OperationHistory {
    private Stack<int[]> undoStack = new Stack<>();
    private Stack<int[]> redoStack = new Stack<>();

    public void save(MapData data) {
        int n = data.getTotalTiles();
        int[] snap = new int[n];
        for (int i = 0; i < n; i++) {
            snap[i] = (data.tiles.get(i).bmTerrain1Group << 8) | (data.buildingIds.get(i) & 0xFF);
        }
        undoStack.push(snap);
        redoStack.clear();
        if (undoStack.size() > 100) undoStack.remove(0);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void undo(MapData data) {
        if (!canUndo()) return;
        int n = data.getTotalTiles();
        int[] cur = new int[n];
        for (int i = 0; i < n; i++)
            cur[i] = (data.tiles.get(i).bmTerrain1Group << 8) | (data.buildingIds.get(i) & 0xFF);
        redoStack.push(cur);

        int[] snap = undoStack.pop();
        for (int i = 0; i < n && i < snap.length; i++) {
            data.tiles.get(i).bmTerrain1Group = (snap[i] >> 8) & 0xFF;
            data.buildingIds.set(i, snap[i] & 0xFF);
        }
    }

    public void redo(MapData data) {
        if (!canRedo()) return;
        int n = data.getTotalTiles();
        int[] cur = new int[n];
        for (int i = 0; i < n; i++)
            cur[i] = (data.tiles.get(i).bmTerrain1Group << 8) | (data.buildingIds.get(i) & 0xFF);
        undoStack.push(cur);

        int[] snap = redoStack.pop();
        for (int i = 0; i < n && i < snap.length; i++) {
            data.tiles.get(i).bmTerrain1Group = (snap[i] >> 8) & 0xFF;
            data.buildingIds.set(i, snap[i] & 0xFF);
        }
    }

    public void clear() { undoStack.clear(); redoStack.clear(); }
}
