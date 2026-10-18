package com.xckeji.bj.model;

public class TerrainTile {
    public int bmTerrain1Group;
    public int bmTerrain1Id;
    public int bmTerrain1X;
    public int bmTerrain1Y;
    public int decoration1Group, decoration1Id, decoration1X, decoration1Y;
    public int decoration2Group, decoration2Id, decoration2X, decoration2Y;
    public int floorGroup, floorId, floorX, floorY;

    public void parseFromBytes(byte[] data, int offset) {
        bmTerrain1Group   = data[offset] & 0xFF;
        bmTerrain1Id      = data[offset + 1] & 0xFF;
        bmTerrain1X       = data[offset + 2] & 0xFF;
        bmTerrain1Y       = data[offset + 3] & 0xFF;
        decoration1Group  = data[offset + 4] & 0xFF;
        decoration1Id     = data[offset + 5] & 0xFF;
        decoration1X      = data[offset + 6] & 0xFF;
        decoration1Y      = data[offset + 7] & 0xFF;
        decoration2Group  = data[offset + 8] & 0xFF;
        decoration2Id     = data[offset + 9] & 0xFF;
        decoration2X      = data[offset + 10] & 0xFF;
        decoration2Y      = data[offset + 11] & 0xFF;
        floorGroup        = data[offset + 12] & 0xFF;
        floorId           = data[offset + 13] & 0xFF;
        floorX            = data[offset + 14] & 0xFF;
        floorY            = data[offset + 15] & 0xFF;
    }

    public void toBytes(byte[] data, int offset) {
        data[offset]     = (byte) bmTerrain1Group;
        data[offset+1]   = (byte) bmTerrain1Id;
        data[offset+2]   = (byte) bmTerrain1X;
        data[offset+3]   = (byte) bmTerrain1Y;
        data[offset+4]   = (byte) decoration1Group;
        data[offset+5]   = (byte) decoration1Id;
        data[offset+6]   = (byte) decoration1X;
        data[offset+7]   = (byte) decoration1Y;
        data[offset+8]   = (byte) decoration2Group;
        data[offset+9]   = (byte) decoration2Id;
        data[offset+10]  = (byte) decoration2X;
        data[offset+11]  = (byte) decoration2Y;
        data[offset+12]  = (byte) floorGroup;
        data[offset+13]  = (byte) floorId;
        data[offset+14]  = (byte) floorX;
        data[offset+15]  = (byte) floorY;
    }

    /**
     * 设置地形组并清掉覆盖层。游戏用三层渲染：0x0 地形组 + 0x4/0x8 两个覆盖层，
     * 覆盖层 0x3F 表示“无”。只改地形组会残留旧覆盖层（例如海洋的波浪层），
     * 导致涂上去的地形被覆盖层遮住不显示。
     */
    public void setTerrain(int group) {
        bmTerrain1Group = group;
        bmTerrain1Id = (group == 0) ? 0xFF : 0;
        bmTerrain1X = 0;
        bmTerrain1Y = 0;
        decoration1Group = 0x3F;
        decoration1Id = 0xFF;
        decoration1X = 0;
        decoration1Y = 0;
        decoration2Group = 0x3F;
        decoration2Id = 0xFF;
        decoration2X = 0;
        decoration2Y = 0;
        floorGroup = 0;
        floorId = 0;
        floorX = 0;
        floorY = 0;
    }

    public int getTerrainColor() { return TerrainColors.getColor(bmTerrain1Group); }
}
