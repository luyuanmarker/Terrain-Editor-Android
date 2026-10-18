package org.json;
public class JSONArray {
    public JSONArray(String s) { }
    public int length() { return 0; }
    public JSONObject getJSONObject(int i) { return new JSONObject(); }
    public JSONObject optJSONObject(int i) { return new JSONObject(); }
    public String optString(int i, String def) { return def; }
    public int optInt(int i, int def) { return def; }
}
