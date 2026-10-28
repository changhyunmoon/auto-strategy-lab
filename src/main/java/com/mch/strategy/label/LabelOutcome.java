package com.mch.strategy.label;

/**
 * 한 판단 시점의 정답 상태. code 는 파일에 1바이트로 저장하는 값이다 (바꾸지 않는다).
 * 학습에 쓸 수 있는 상태는 목표 먼저 · 손실 먼저 · 시간 만료 세 가지다.
 */
public enum LabelOutcome {
    TARGET(1, "목표 먼저 도달", true),
    STOP(2, "손실 먼저 도달", true),
    TIMEOUT(3, "시간 만료", true),
    AMBIGUOUS(4, "판정 불가 (같은 1분봉에서 동시 도달)", false),
    GAP(5, "판정 불가 (관찰 구간에 빈 1분)", false),
    NO_FUTURE(6, "데이터 부족 (뒤쪽 미래 봉 없음)", false);

    private final byte code;
    private final String label;
    private final boolean valid;

    LabelOutcome(int code, String label, boolean valid) {
        this.code = (byte) code;
        this.label = label;
        this.valid = valid;
    }

    public byte code() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean isValid() {
        return valid;
    }

    public static LabelOutcome of(byte code) {
        for (LabelOutcome o : values()) {
            if (o.code == code) {
                return o;
            }
        }
        throw new IllegalArgumentException("정답 코드: " + code);
    }
}
