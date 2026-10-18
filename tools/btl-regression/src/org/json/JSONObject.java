package org.json;
public class JSONObject {
    public int optInt(String k, int d) { return d; }
    public String optString(String k, String d) { return d; }
    public Object opt(String k) { return null; }
}
