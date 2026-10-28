package com.mch.strategy.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** UTC 저장 · KST 표시 규칙을 한곳에 모은다. 날짜 범위 입력(원본 · 분할)은 UTC 날짜다. */
public final class TimeUtils {

    public static final ZoneId UTC = ZoneOffset.UTC;
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter KST_MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);
    private static final DateTimeFormatter UTC_MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(UTC);

    private TimeUtils() {
    }

    /** 화면 표시용 KST 문자열. */
    public static String toKstText(Instant instant) {
        return instant == null ? "" : KST_MINUTE.format(instant);
    }

    /** UTC 분 단위 문자열 (경계 표시용) */
    public static String toUtcText(Instant instant) {
        return instant == null ? "" : UTC_MINUTE.format(instant);
    }

    /** UTC 날짜 (시작 경계) */
    public static LocalDate utcDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, UTC);
    }

    /** 끝 제외 경계 → 포함되는 마지막 UTC 날짜 (예: 2/1 00:00 → 1/31) */
    public static LocalDate utcLastDate(Instant endExclusive) {
        return endExclusive == null ? null : LocalDate.ofInstant(endExclusive.minusMillis(1), UTC);
    }
}
