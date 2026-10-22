package com.mch.strategy.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** UTC 저장 · KST 표시 규칙을 한곳에 모은다. */
public final class TimeUtils {

    public static final ZoneId UTC = ZoneOffset.UTC;
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter KST_MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);

    private TimeUtils() {
    }

    /** 화면 표시용 KST 문자열. */
    public static String toKstText(Instant instant) {
        return instant == null ? "" : KST_MINUTE.format(instant);
    }
}
