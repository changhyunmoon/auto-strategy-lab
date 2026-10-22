package com.mch.strategy.common;

/**
 * dataset_row.feature_values 의 JSON 숫자 배열을 빠르게 쓰고 읽는다.
 * 값이 없거나 계산할 수 없는 칸(NaN·무한대)은 null 로 저장하고, 읽을 때 NaN 으로 돌려준다.
 */
public final class JsonArrays {

    private JsonArrays() {
    }

    /**
     * @param fullPrecisionCount 앞에서부터 이 개수만큼은 double 정밀도로 쓴다 (시고저종·거래량).
     *                           나머지 지표 값은 float 정밀도(유효숫자 약 7자리)로 써서 용량을 줄인다.
     */
    public static String write(double[] values, int fullPrecisionCount) {
        StringBuilder sb = new StringBuilder(values.length * 11 + 2);
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            double v = values[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                sb.append("null");
            } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                sb.append((long) v);
            } else if (i < fullPrecisionCount) {
                sb.append(v);
            } else {
                sb.append((float) v);
            }
        }
        return sb.append(']').toString();
    }

    public static double[] read(String json) {
        int start = json.indexOf('[') + 1;
        int end = json.lastIndexOf(']');
        if (end <= start) {
            return new double[0];
        }
        int count = 1;
        for (int i = start; i < end; i++) {
            if (json.charAt(i) == ',') {
                count++;
            }
        }
        double[] out = new double[count];
        int idx = 0;
        int tokenStart = start;
        for (int i = start; i <= end; i++) {
            if (i == end || json.charAt(i) == ',') {
                String token = json.substring(tokenStart, i).trim();
                out[idx++] = token.isEmpty() || token.equals("null") ? Double.NaN : Double.parseDouble(token);
                tokenStart = i + 1;
            }
        }
        return out;
    }
}
