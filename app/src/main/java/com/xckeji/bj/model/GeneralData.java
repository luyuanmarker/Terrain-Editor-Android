package com.xckeji.bj.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 将领基础数据（来自 GeneralSettings.json）：ID -> 名称 / 头像。 */
public class GeneralData {
    public int id;
    public String name;
    public String ename;
    public int photo;

    public static final List<GeneralData> ALL = new ArrayList<>();
    public static final Map<Integer, GeneralData> BY_ID = new HashMap<>();

    public static void load(byte[] jsonBytes) {
        ALL.clear();
        BY_ID.clear();
        try {
            JSONArray arr = new JSONArray(new String(jsonBytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int id = o.optInt("Id", 0);
                if (id <= 0) continue;
                GeneralData g = new GeneralData();
                g.id = id;
                g.name = o.optString("Name", "");
                g.ename = o.optString("EName", "");
                g.photo = o.optInt("Photo", 0);
                ALL.add(g);
                BY_ID.put(id, g);
            }
        } catch (Exception ignored) {
        }
    }

    public static String name(int id) {
        GeneralData g = BY_ID.get(id);
        return g != null && !g.name.isEmpty() ? g.name : (id > 0 ? "将领" + id : "");
    }
}
