package com.xckeji.bj.model;

import java.util.HashMap;
import java.util.Map;

/** 国家 ID -> 名称（军团段 0x4 的国家代码）。 */
public class CountryData {
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        NAMES.put(1, "英国"); NAMES.put(2, "法国"); NAMES.put(3, "德国"); NAMES.put(4, "西德");
        NAMES.put(5, "苏联"); NAMES.put(6, "美国"); NAMES.put(7, "意大利"); NAMES.put(8, "民国");
        NAMES.put(9, "中国"); NAMES.put(10, "日本"); NAMES.put(11, "芬兰"); NAMES.put(12, "波兰");
        NAMES.put(13, "南斯拉夫"); NAMES.put(14, "加拿大"); NAMES.put(15, "澳大利亚"); NAMES.put(16, "挪威");
        NAMES.put(17, "瑞典"); NAMES.put(18, "丹麦"); NAMES.put(19, "荷兰"); NAMES.put(20, "比利时");
        NAMES.put(21, "西班牙"); NAMES.put(22, "葡萄牙"); NAMES.put(23, "匈牙利"); NAMES.put(24, "罗马尼亚");
        NAMES.put(25, "保加利亚"); NAMES.put(26, "瑞士"); NAMES.put(27, "希腊"); NAMES.put(28, "土耳其");
        NAMES.put(29, "沙特阿拉伯"); NAMES.put(30, "伊拉克"); NAMES.put(31, "伊朗"); NAMES.put(32, "印度");
        NAMES.put(33, "泰国"); NAMES.put(34, "蒙古"); NAMES.put(35, "朝鲜"); NAMES.put(36, "韩国");
        NAMES.put(37, "墨西哥"); NAMES.put(38, "古巴"); NAMES.put(39, "哥伦比亚"); NAMES.put(40, "巴西");
        NAMES.put(41, "玻利维亚"); NAMES.put(42, "委内瑞拉"); NAMES.put(43, "秘鲁"); NAMES.put(44, "智利");
        NAMES.put(45, "阿根廷"); NAMES.put(46, "埃及"); NAMES.put(47, "利比里亚"); NAMES.put(48, "黑蝎");
    }

    public static String name(int countryId) {
        String n = NAMES.get(countryId);
        return n != null ? n : ("国家" + countryId);
    }
}
