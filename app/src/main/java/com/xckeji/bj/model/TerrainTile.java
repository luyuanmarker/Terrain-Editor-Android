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

    public int getTerrainColor() { return TerrainColors.getColor(bmTerrain1Group); }
}
