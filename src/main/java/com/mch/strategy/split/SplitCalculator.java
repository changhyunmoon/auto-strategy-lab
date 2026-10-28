package com.mch.strategy.split;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mch.strategy.label.LabelOutcome;

/**
 * 행마다 학습 · 검증 · 최종 테스트 소속 또는 제외 사유를 정한다 (노션 「분할 화면」 3절).
 * <ol>
 *   <li>판단 시각이 사용 기간 밖 → 제외(사용 기간 밖). 세 구간 어디에도 없으면 → 제외(구간 사이 빈 기간).</li>
 *   <li>정답이 학습에 쓸 수 없는 상태(동시 도달 · 빈 1분 · 미래 부족) → 그 상태를 사유로 제외.</li>
 *   <li>label_end_time 이 자기 구간의 끝 경계를 넘음 → 제외(경계 초과). 다음 구간의 가격을 보고 정한 정답이기 때문.</li>
 *   <li>학습 · 검증 구간 끝에서 경계 여유(분) 안 → 제외(경계 여유).</li>
 * </ol>
 * 입력 지표가 아직 null 인 행(워밍업)은 빼지 않고 개수만 따로 센다. 결측 처리는 모델 학습 단계에서 학습 구간만으로 정한다.
 */
public final class SplitCalculator {

    public static final byte NONE = 0;
    public static final byte TRAIN = 1;
    public static final byte VAL = 2;
    public static final byte TEST = 3;

    /** 행별 사유 코드 (파일에 1바이트로 저장) */
    public enum Reason {
        OK(0, "사용"),
        OUTSIDE_USE(1, "사용 기간 밖"),
        UNASSIGNED(2, "구간 사이 빈 기간"),
        AMBIGUOUS(3, "정답 판정 불가 (동시 도달)"),
        GAP(4, "정답 판정 불가 (관찰 구간 빈 1분)"),
        NO_FUTURE(5, "정답 데이터 부족 (미래 봉 없음)"),
        BOUNDARY(6, "경계 초과 (정답이 다음 구간 가격을 봄)"),
        EMBARGO(7, "경계 여유 기간");

        public final byte code;
        public final String label;

        Reason(int code, String label) {
            this.code = (byte) code;
            this.label = label;
        }

        public static Reason of(byte code) {
            for (Reason r : values()) {
                if (r.code == code) return r;
            }
            throw new IllegalArgumentException("사유 코드: " + code);
        }
    }

    private SplitCalculator() {
    }

    /** 구간 경계 (밀리초, [from, to)) */
    public record Bounds(long useFrom, long useTo, long trainFrom, long trainTo, long valFrom, long valTo,
                         long testFrom, long testTo, long embargoMs) {

        public static Bounds of(SplitConfig c, java.time.Instant dataFrom, java.time.Instant dataTo) {
            return new Bounds(c.useStart(dataFrom).toEpochMilli(), c.useEnd(dataTo).toEpochMilli(),
                    SplitConfig.start(c.trainFrom()).toEpochMilli(), SplitConfig.endExclusive(c.trainTo()).toEpochMilli(),
                    SplitConfig.start(c.valFrom()).toEpochMilli(), SplitConfig.endExclusive(c.valTo()).toEpochMilli(),
                    SplitConfig.start(c.testFrom()).toEpochMilli(), SplitConfig.endExclusive(c.testTo()).toEpochMilli(),
                    c.embargoMinutes() * 60_000L);
        }
    }

    /**
     * @param time        판단 봉 시작 시각(밀리초)
     * @param status      정답 상태 코드 ({@link LabelOutcome})
     * @param endTime     label_end_time(밀리초)
     * @param inputNulls  행별 null 인 입력 열 수 (워밍업 표시용, null 이면 세지 않음)
     */
    public static Result assign(long[] time, byte[] status, long[] endTime, int[] inputNulls, Bounds b) {
        int n = time.length;
        Result r = new Result(n);
        for (int i = 0; i < n; i++) {
            long t = time[i];
            byte seg;
            long segEnd;
            if (t < b.useFrom || t >= b.useTo) {
                r.reason[i] = Reason.OUTSIDE_USE.code;
                r.count(NONE, Reason.OUTSIDE_USE, status[i], false);
                continue;
            }
            if (t >= b.trainFrom && t < b.trainTo) {
                seg = TRAIN;
                segEnd = Math.min(b.trainTo, b.useTo);
            } else if (t >= b.valFrom && t < b.valTo) {
                seg = VAL;
                segEnd = Math.min(b.valTo, b.useTo);
            } else if (t >= b.testFrom && t < b.testTo) {
                seg = TEST;
                segEnd = Math.min(b.testTo, b.useTo);
            } else {
                r.reason[i] = Reason.UNASSIGNED.code;
                r.count(NONE, Reason.UNASSIGNED, status[i], false);
                continue;
            }
            r.segment[i] = seg;
            Reason reason;
            LabelOutcome o = LabelOutcome.of(status[i]);
            if (o == LabelOutcome.AMBIGUOUS) reason = Reason.AMBIGUOUS;
            else if (o == LabelOutcome.GAP) reason = Reason.GAP;
            else if (o == LabelOutcome.NO_FUTURE) reason = Reason.NO_FUTURE;
            else if (endTime[i] > segEnd) reason = Reason.BOUNDARY;
            else if (seg != TEST && b.embargoMs > 0 && t >= segEnd - b.embargoMs) reason = Reason.EMBARGO;
            else reason = Reason.OK;
            r.reason[i] = reason.code;
            boolean warm = inputNulls != null && inputNulls[i] > 0;
            r.count(seg, reason, status[i], warm);
        }
        return r;
    }

    /** 행별 결과 + 구간별 집계 */
    public static final class Result {
        public final byte[] segment;
        public final byte[] reason;
        /** 구간(NONE·TRAIN·VAL·TEST) → 통계 */
        public final Map<Byte, SegmentStats> stats = new LinkedHashMap<>();

        Result(int n) {
            segment = new byte[n];
            reason = new byte[n];
            for (byte s : new byte[]{TRAIN, VAL, TEST, NONE}) {
                stats.put(s, new SegmentStats());
            }
        }

        void count(byte seg, Reason reason, byte status, boolean warm) {
            SegmentStats s = stats.get(seg);
            s.rows++;
            s.reasons.merge(reason.name(), 1L, Long::sum);
            if (reason == Reason.OK) {
                s.valid++;
                s.labels.merge(LabelOutcome.of(status).name(), 1L, Long::sum);
                if (warm) s.validWithNullInputs++;
            }
        }
    }

    public static final class SegmentStats {
        public long rows;
        public long valid;
        /** 유효 행 중 입력 지표에 null 이 있는 행 (워밍업 · 분모 0 등) */
        public long validWithNullInputs;
        public final Map<String, Long> reasons = new LinkedHashMap<>();
        public final Map<String, Long> labels = new LinkedHashMap<>();
    }
}
