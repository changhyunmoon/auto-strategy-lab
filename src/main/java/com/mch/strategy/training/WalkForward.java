package com.mch.strategy.training;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 워크포워드 폴드 나누기. 탐색 기간(학습 + 검증 구간)을 시간순으로 앞으로 굴리며 여러 번 검증한다.
 * <pre>
 *  탐색 기간 |------- 첫 학습 (비율) -------|-- 검증 1 --|-- 검증 2 --|-- 검증 3 --|
 *  폴드 1    [====== 학습 ======]·purge·[검증 1]
 *  폴드 2    [========= 학습 =========]·purge·[검증 2]          (확장형: 시작 고정)
 *  폴드 2    ......[==== 학습 창 ====]·purge·[검증 2]            (이동형: 최근 N일만)
 * </pre>
 * purge: 학습 행의 정답이 쓰는 미래 봉(예측 기간 N봉)이 검증 블록에 닿지 않도록, 검증 시작 전 (N+1)봉 안의 학습 행을 뺀다.
 * 최종 테스트 구간 행(split 2)은 쓰지 않는다.
 */
public final class WalkForward {

    private WalkForward() {
    }

    public record Fold(int index, long trainFrom, long trainTo, long valFrom, long valTo, int[] trainIdx, int[] valIdx) {
        public String text() {
            return Instant.ofEpochMilli(valFrom) + " ~ " + Instant.ofEpochMilli(valTo);
        }
    }

    /**
     * @param time          행 시각 (epoch ms)
     * @param split         행 구간 (0 학습 · 1 검증 · 2 테스트)
     * @param barMs         판단 간격 (ms)
     * @param horizon       정답 예측 기간 N봉
     * @param folds         검증 블록 수
     * @param firstFraction 첫 학습에 쓸 탐색 기간 비율 (0.2 ~ 0.9)
     * @param rollingDays   이동형이면 학습 창 길이(일), 확장형이면 null
     */
    public static List<Fold> plan(long[] time, byte[] split, long barMs, int horizon, int folds,
                                  double firstFraction, Integer rollingDays) {
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
        for (int i = 0; i < time.length; i++) {
            if (split[i] < 2) {
                start = Math.min(start, time[i]);
                end = Math.max(end, time[i] + barMs);
            }
        }
        if (start == Long.MAX_VALUE) {
            throw new IllegalStateException("탐색 기간(학습 · 검증 구간)에 행이 없습니다.");
        }
        long valStart = start + (long) ((end - start) * firstFraction);
        long block = (end - valStart) / folds;
        if (block <= barMs) {
            throw new IllegalStateException("검증 블록이 너무 짧습니다. 폴드 수를 줄이거나 첫 학습 비율을 낮추세요.");
        }
        long purge = (long) (horizon + 1) * barMs;
        List<Fold> out = new ArrayList<>();
        for (int k = 0; k < folds; k++) {
            long vs = valStart + k * block;
            long ve = k == folds - 1 ? end : vs + block;
            long trainTo = vs - purge;
            long trainFrom = rollingDays == null ? start : Math.max(start, vs - rollingDays * 86_400_000L);
            int nt = 0, nv = 0;
            for (int i = 0; i < time.length; i++) {
                if (split[i] >= 2) {
                    continue;
                }
                // 학습 행: 정답이 쓰는 마지막 봉(N봉 뒤)까지 검증 시작 전에 끝나야 한다
                if (time[i] >= trainFrom && time[i] + purge <= vs) {
                    nt++;
                } else if (time[i] >= vs && time[i] < ve) {
                    nv++;
                }
            }
            int[] tr = new int[nt], va = new int[nv];
            int a = 0, b = 0;
            for (int i = 0; i < time.length; i++) {
                if (split[i] >= 2) {
                    continue;
                }
                if (time[i] >= trainFrom && time[i] + purge <= vs) {
                    tr[a++] = i;
                } else if (time[i] >= vs && time[i] < ve) {
                    va[b++] = i;
                }
            }
            if (nt == 0 || nv == 0) {
                throw new IllegalStateException("폴드 " + (k + 1) + "의 " + (nt == 0 ? "학습" : "검증") + " 행이 없습니다. "
                        + "폴드 수 · 첫 학습 비율 · 학습 창 길이를 조정하세요.");
            }
            out.add(new Fold(k + 1, trainFrom, trainTo, vs, ve, tr, va));
        }
        return out;
    }
}
