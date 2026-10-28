package com.mch.strategy.indicator;

import java.util.List;
import java.util.function.Function;

/**
 * 만들어질 열 하나의 정의. 화면의 미리보기 · 열 설명과 실제 계산이 같은 정의를 쓴다.
 *
 * @param name    열 이름 (예: slope_ema_20_5)
 * @param key     계산 내용의 고유 키. 다른 설정에서 같은 키가 나오면 같은 값이므로 열은 하나만 둔다 (예: bb_mid_20 = sma_20 → "sma:20")
 * @param bundle  지표 안의 변수 묶음 코드 (화면 체크박스 이름과 같다)
 * @param title   사람이 읽는 이름
 * @param formula 계산식
 * @param read    값 읽는 법 · 주의
 * @param inputs  사용한 원본 열 (close · volume …)
 * @param params  사용한 기간 · 배수 · 간격 요약
 * @param warmup  구간 시작부터 첫 유효 값이 나오기까지 필요한 봉 수 (그 전은 null)
 * @param calc    끊김 없는 1분봉 한 구간에서 이 열 값을 계산하는 함수
 */
public record ColumnSpec(
        String name,
        String key,
        IndicatorKind kind,
        String bundle,
        String title,
        String formula,
        String read,
        List<String> inputs,
        String params,
        int warmup,
        Function<Ctx, double[]> calc
) {
}
