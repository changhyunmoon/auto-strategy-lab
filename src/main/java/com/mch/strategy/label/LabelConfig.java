package com.mch.strategy.label;

import java.util.ArrayList;
import java.util.List;

/**
 * H · P · L 수익 기회 정답 정의 하나.
 *
 * @param horizon     H: 진입 뒤 최대 관찰 시간(분 = 1분봉 개수)
 * @param targetPct   P: 목표 수익률(%), 진입 가격 기준 양수
 * @param stopPct     L: 허용 손실률(%), 진입 가격 기준 양수
 * @param direction   LONG(매수 후 매도) · SHORT(하락 수익 방향)
 * @param entry       NEXT_OPEN(판단 봉 다음 1분봉 시가에 진입) · CLOSE(판단 봉 종가에 진입)
 * @param sameBar     같은 1분봉에서 목표 · 손실 모두 닿을 때: AMBIGUOUS(판정 불가) · STOP_FIRST(손실 먼저로 보수 처리)
 * @param costEnabled 비용 반영: P · L 을 왕복 비용을 뺀 「순」 기준으로 해석
 * @param feePct      수수료(%, 한쪽)
 * @param slippagePct 슬리피지(%, 한쪽)
 */
public record LabelConfig(
        int horizon,
        double targetPct,
        double stopPct,
        String direction,
        String entry,
        String sameBar,
        boolean costEnabled,
        double feePct,
        double slippagePct
) {

    public static final String CODE_VERSION = "label-v2.0";
    public static final int MAX_HORIZON = 10_080;

    public boolean isLong() {
        return !"SHORT".equals(direction);
    }

    public boolean entryNextOpen() {
        return !"CLOSE".equals(entry);
    }

    public boolean stopFirst() {
        return "STOP_FIRST".equals(sameBar);
    }

    /** 왕복 비용(%) = (수수료 + 슬리피지) × 2. 비용을 반영하지 않으면 0 */
    public double roundTripCostPct() {
        return costEnabled ? (feePct + slippagePct) * 2 : 0;
    }

    /** 실제 가격 경로에서 확인할 목표 폭(%) = P + 왕복 비용 */
    public double grossTargetPct() {
        return targetPct + roundTripCostPct();
    }

    /** 실제 가격 경로에서 확인할 손실 폭(%) = L − 왕복 비용 (비용만큼 덜 움직여도 순손실이 L 이 된다) */
    public double grossStopPct() {
        return stopPct - roundTripCostPct();
    }

    /** 문제 목록. 비어 있으면 생성할 수 있다. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (horizon < 1 || horizon > MAX_HORIZON) p.add("H(최대 관찰 시간)는 1 이상 " + MAX_HORIZON + " 이하의 정수(분)여야 합니다.");
        if (!(targetPct > 0) || targetPct > 100) p.add("P(목표 수익률)는 0보다 크고 100 이하인 % 값이어야 합니다.");
        if (!(stopPct > 0) || stopPct >= 100) p.add("L(허용 손실률)은 0보다 크고 100 미만인 % 값이어야 합니다.");
        if (!List.of("LONG", "SHORT").contains(String.valueOf(direction))) p.add("거래 방향을 고르세요.");
        if (!List.of("NEXT_OPEN", "CLOSE").contains(String.valueOf(entry))) p.add("진입 기준을 고르세요.");
        if (!List.of("AMBIGUOUS", "STOP_FIRST").contains(String.valueOf(sameBar))) p.add("동시 도달 처리를 고르세요.");
        if (costEnabled) {
            if (feePct < 0 || slippagePct < 0 || feePct > 5 || slippagePct > 5) {
                p.add("수수료 · 슬리피지는 0 이상 5 이하의 % 값이어야 합니다.");
            } else if (!(grossStopPct() > 0)) {
                p.add("비용을 반영하면 L 이 왕복 비용(" + roundTripCostPct() + "%)보다 커야 합니다. 그렇지 않으면 손실 기준 가격이 진입가 이상이 됩니다.");
            }
        }
        return p;
    }
}
