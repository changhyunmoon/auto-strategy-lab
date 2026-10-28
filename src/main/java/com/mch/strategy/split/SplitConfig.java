package com.mch.strategy.split;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 학습 · 검증 · 최종 테스트 분할 설정. 날짜는 UTC 날짜이고 종료일 하루 전체를 포함한다.
 * 내부에서는 [시작일 00:00, 종료일 다음 날 00:00) 로 바꿔 쓴다 (시작 포함 · 끝 제외).
 *
 * @param useFrom        이번 실험에 사용할 기간 시작일 (비우면 데이터 처음부터)
 * @param useTo          사용 기간 종료일 (비우면 데이터 끝까지)
 * @param embargoMinutes 경계 여유: 학습 · 검증 구간 마지막 이 분 수 만큼의 행을 추가로 뺀다 (0이면 label_end_time 검사만)
 */
public record SplitConfig(
        LocalDate useFrom,
        LocalDate useTo,
        LocalDate trainFrom,
        LocalDate trainTo,
        LocalDate valFrom,
        LocalDate valTo,
        LocalDate testFrom,
        LocalDate testTo,
        int embargoMinutes
) {

    public static final String CODE_VERSION = "split-v2.0";

    public static Instant start(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** 종료일 하루 전체 포함 → 다음 날 00:00 UTC (끝 제외 경계) */
    public static Instant endExclusive(LocalDate d) {
        return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * @param dataFrom 데이터 확보 시작 (포함)
     * @param dataTo   데이터 확보 끝 (제외)
     */
    public List<String> problems(Instant dataFrom, Instant dataTo) {
        List<String> p = new ArrayList<>();
        if (trainFrom == null || trainTo == null || valFrom == null || valTo == null || testFrom == null || testTo == null) {
            p.add("학습 · 검증 · 최종 테스트의 시작일과 종료일을 모두 고르세요.");
            return p;
        }
        if (trainFrom.isAfter(trainTo)) p.add("학습 구간의 시작일이 종료일보다 늦습니다.");
        if (valFrom.isAfter(valTo)) p.add("검증 구간의 시작일이 종료일보다 늦습니다.");
        if (testFrom.isAfter(testTo)) p.add("최종 테스트 구간의 시작일이 종료일보다 늦습니다.");
        if (!valFrom.isAfter(trainTo)) p.add("검증 구간은 학습 구간이 끝난 뒤에 시작해야 합니다 (겹침 · 역순 불가).");
        if (!testFrom.isAfter(valTo)) p.add("최종 테스트 구간은 검증 구간이 끝난 뒤에 시작해야 합니다 (겹침 · 역순 불가).");
        if (embargoMinutes < 0 || embargoMinutes > 10_080) p.add("경계 여유는 0 이상 10,080분(7일) 이하로 입력하세요.");

        Instant useStart = useFrom == null ? dataFrom : start(useFrom);
        Instant useEnd = useTo == null ? dataTo : endExclusive(useTo);
        if (useFrom != null && useTo != null && useFrom.isAfter(useTo)) p.add("사용 기간의 시작일이 종료일보다 늦습니다.");
        if (useStart.isBefore(dataFrom) || useEnd.isAfter(dataTo)) p.add("사용 기간이 원본 데이터 확보 범위를 벗어났습니다.");
        if (start(trainFrom).isBefore(useStart)) p.add("학습 구간이 사용 기간보다 먼저 시작합니다.");
        if (endExclusive(testTo).isAfter(useEnd)) p.add("최종 테스트 구간이 사용 기간(또는 데이터 확보 범위)보다 늦게 끝납니다.");
        return p;
    }

    public Instant useStart(Instant dataFrom) {
        return useFrom == null ? dataFrom : start(useFrom);
    }

    public Instant useEnd(Instant dataTo) {
        return useTo == null ? dataTo : endExclusive(useTo);
    }
}
