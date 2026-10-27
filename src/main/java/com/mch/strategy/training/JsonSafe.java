package com.mch.strategy.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 에 NaN · 무한대가 들어가지 않게 null 로 바꾼다 (브라우저 JSON.parse 가 NaN 을 못 읽는다). */
public final class JsonSafe {

    private JsonSafe() {
    }

    public static Object clean(Object v) {
        if (v instanceof Double d) {
            return Double.isFinite(d) ? d : null;
        }
        if (v instanceof Float f) {
            return Float.isFinite(f) ? (double) f : null;
        }
        if (v instanceof double[] arr) {
            List<Double> out = new ArrayList<>(arr.length);
            for (double x : arr) {
                out.add(Double.isFinite(x) ? x : null);
            }
            return out;
        }
        if (v instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object x : arr) {
                out.add(clean(x));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object x : list) {
                out.add(clean(x));
            }
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, x) -> out.put(String.valueOf(k), clean(x)));
            return out;
        }
        return v;
    }
}
