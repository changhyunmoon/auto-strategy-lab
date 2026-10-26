package com.mch.strategy.trainset;

/**
 * 학습 데이터 폴더(trainsets/{id}/)의 파일 이름과 형식. 모두 리틀 엔디언 원시 배열이고 행 순서가 같다.
 * 행 순서: 자산별로 모은 뒤 시각순.
 */
public final class TrainingFiles {

    /** 입력 행렬 float32 [행 × 열], 행 우선. 값이 없으면 NaN */
    public static final String FEATURES = "features.f32";
    /** 정답 int8: 하락 0 · 중립 1 · 상승 2 */
    public static final String LABEL = "label.i8";
    /** 구간 int8: 학습 0 · 검증 1 · 최종 테스트 2 */
    public static final String SPLIT = "split.i8";
    /** 자산 int8: meta.json 의 assets 순서 */
    public static final String ASSET = "asset.i8";
    /** 판단 봉 시작 시각 int64 (epoch ms, UTC) */
    public static final String TIME = "time.i64";
    /** 다음 봉 종가 수익률 float32 (%) */
    public static final String FORWARD = "forward.f32";
    /** 직전 봉 대비 현재 종가 수익률 float32 (%) — 「직전 봉 방향 유지」 기준 모델용. 입력 열이 아니다 */
    public static final String PREVIOUS = "previous.f32";
    /** 열 이름 · 자산 · 정답 설정 · 구간 날짜 */
    public static final String META = "meta.json";

    private TrainingFiles() {
    }
}
