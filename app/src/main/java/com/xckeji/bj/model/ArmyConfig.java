package com.xckeji.bj.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 兵种基础数值（来自 Armysettings.json）。 */
public class ArmyConfig {
    public int id;
    public String name;
    public int army;        // BTL 兵种段 0x2 的兵种代码
    public int type;        // 兵种类别
    public int minAttack, maxAttack;
    public int minRange, maxRange;
    public int hp, defence, mobility;
    public int costMoney, costGear, costAtomic;
    public int maxFormation, carrier, buildTime;
    public int[] feature;

    public static List<ArmyConfig> ALL = new ArrayList<>();
    public static Map<Integer, ArmyConfig> BY_ARMY = new HashMap<>();

    public static void load(byte[] jsonBytes) {
        ALL = new ArrayList<>();
        BY_ARMY = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(new String(jsonBytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ArmyConfig c = new ArmyConfig();
                c.id = o.optInt("Id", 0);
                c.name = o.optString("Name", "");
                c.army = o.optInt("Army", 0);
                c.type = o.optInt("Type", 0);
                c.minAttack = o.optInt("MinAttack", 0);
                c.maxAttack = o.optInt("MaxAttack", 0);
                c.minRange = o.optInt("MinRange", 0);
                c.maxRange = o.optInt("MaxRange", 0);
                c.hp = o.optInt("HP", 0);
                c.defence = o.optInt("Defence", 0);
                c.mobility = o.optInt("Mobility", 0);
                c.costMoney = o.optInt("CostMoney", 0);
                c.costGear = o.optInt("CostGear", 0);
                c.costAtomic = o.optInt("CostAtomic", 0);
                c.maxFormation = o.optInt("MaxFormation", 0);
                c.carrier = o.optInt("Carrier", 0);
                c.buildTime = o.optInt("BuildTime", 0);
                ALL.add(c);
                if (c.army > 0 && !BY_ARMY.containsKey(c.army)) BY_ARMY.put(c.army, c);
            }
        } catch (Exception ignored) {
        }
    }

    public static ArmyConfig byArmy(int code) {
        return BY_ARMY.get(code);
    }

    /** 概要行：攻 16-22 血 80 防 1 移 6 射 1 */
    public String summary() {
        return "攻 " + minAttack + "-" + maxAttack
                + " 血 " + hp
                + " 防 " + defence
                + " 移 " + mobility
                + " 射 " + minRange + (maxRange > minRange ? "-" + maxRange : "");
    }
}
