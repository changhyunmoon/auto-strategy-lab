package com.mch.strategy.collect.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 바이낸스 1분봉 형식 해석.
 * 열 순서: 시작시각, 시가, 고가, 저가, 종가, 거래량, 종료시각, 거래대금, 체결 건수, 매수 주도 거래량, 매수 주도 거래대금, 무시
 */
public final class BinanceKlines {

    /** 이 값보다 크면 마이크로초 (2025년 이후 현물 파일). 밀리초 값은 약 1.7e12. */
    private static final long MICROS_THRESHOLD = 100_000_000_000_000L;

    private BinanceKlines() {
    }

    /** 공개 데이터 파일(CSV) 내용. 머리줄이 있으면 건너뛴다. */
    public static List<Candle1m> parseCsv(String content) {
        List<Candle1m> out = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) {
            if (line.isBlank() || !Character.isDigit(line.charAt(0))) {
                continue;
            }
            out.add(fromFields(line.split(",")));
        }
        return out;
    }

    /** REST /api/v3/klines 응답의 한 행. */
    public static Candle1m fromRestRow(List<Object> row) {
        String[] fields = new String[row.size()];
        for (int i = 0; i < row.size(); i++) {
            fields[i] = String.valueOf(row.get(i));
        }
        return fromFields(fields);
    }

    static Candle1m fromFields(String[] f) {
        return new Candle1m(
                toInstant(Long.parseLong(f[0].trim())),
                new BigDecimal(f[1].trim()),
                new BigDecimal(f[2].trim()),
                new BigDecimal(f[3].trim()),
                new BigDecimal(f[4].trim()),
                new BigDecimal(f[5].trim()),
                new BigDecimal(f[7].trim()),
                Integer.parseInt(f[8].trim()),
                new BigDecimal(f[9].trim()),
                new BigDecimal(f[10].trim()));
    }

    static Instant toInstant(long raw) {
        return raw > MICROS_THRESHOLD ? Instant.ofEpochMilli(raw / 1000) : Instant.ofEpochMilli(raw);
    }
}
