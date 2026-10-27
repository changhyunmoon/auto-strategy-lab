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
    /** 예측 기간(N봉) 뒤 종가 수익률 float32 (%) */
    public static final String FORWARD = "forward.f32";
    /** 직전 N봉 수익률을 같은 기준으로 나눈 방향 float32 (0·1·2, 없으면 NaN) — 「직전 봉 방향 유지」 기준 모델용. 입력 열이 아니다 */
    public static final String PREVIOUS = "previous.f32";
    /** 판단 봉 종가 float32 — 거래 성과(백테스트) 계산용. 입력 열이 아니다 */
    public static final String CLOSE = "close.f32";
    /** 판단 봉 고가 · 저가 float32 — 지정가 체결 판단용. 입력 열이 아니다. 예전 학습 데이터에는 없다 */
    public static final String HIGH = "high.f32";
    public static final String LOW = "low.f32";
    /** 이 행의 정답 기준 float32 (%) — 변동성 기준이면 행마다 다르다 */
    public static final String THRESHOLD = "threshold.f32";
    /** 파일 형식 버전. 2 = N봉 정답 · 종가 · 기준 파일 추가 */
    public static final int FORMAT = 2;
    /** 열 이름 · 자산 · 정답 설정 · 구간 날짜 */
    public static final String META = "meta.json";

    private TrainingFiles() {
    }
}
