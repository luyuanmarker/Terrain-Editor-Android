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
    public int[] medals = new int[6];   // 胸章一/二/三 + 勋带一/二/三
    public int[] skills = new int[0];   // 技能ID列表

    public static final List<GeneralData> ALL = new ArrayList<>();
    public static final Map<Integer, GeneralData> BY_ID = new HashMap<>();
    public static final Map<Integer, String> SKILL_NAMES = new HashMap<>();

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
                if (o.has("Medals")) {
                    try {
                        org.json.JSONArray ma = o.getJSONArray("Medals");
                        for (int m = 0; m < ma.length() && m < 6; m++) {
                            g.medals[m] = ma.optInt(m, 0);
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (o.has("Skills")) {
                    try {
                        org.json.JSONArray sa = o.getJSONArray("Skills");
                        g.skills = new int[sa.length()];
                        for (int s = 0; s < sa.length(); s++) g.skills[s] = sa.optInt(s, 0);
                    } catch (Exception ignored) {
                    }
                }
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

    public static void loadSkills(byte[] jsonBytes) {
        SKILL_NAMES.clear();
        try {
            JSONArray arr = new JSONArray(new String(jsonBytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int id = o.optInt("Id", 0);
                String n = o.optString("Name", "");
                if (id > 0 && !n.isEmpty()) SKILL_NAMES.put(id, n);
            }
        } catch (Exception ignored) {
        }
    }
}
