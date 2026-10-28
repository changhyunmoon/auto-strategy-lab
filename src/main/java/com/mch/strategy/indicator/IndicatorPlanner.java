package com.mch.strategy.indicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.mch.strategy.indicator.IndicatorConfig.Band;

/**
 * 지표 설정 → 만들 열 목록. 노션 「V2 — ○○ 지표 생성 규칙과 기간 조합」의 이름 규칙 · 조합 수 · 계산식을 따른다.
 * 화면 미리보기와 실제 생성이 이 한 곳을 쓰므로 미리보기와 결과가 어긋나지 않는다.
 */
public final class IndicatorPlanner {

    /** 생성 코드 버전. 계산식 · 이름 규칙을 바꾸면 올린다 (결과에 함께 저장되어 재현 기준이 된다). */
    public static final String CODE_VERSION = "indicator-v2.0";

    /** 기간 · 간격 최대값 (분). 7일 = 10,080분 */
    public static final int MAX_PERIOD = 10_080;

    /** 한 데이터셋에 만들 수 있는 최대 열 수 (집 PC 의 디스크 · 메모리 보호) */
    public static final int MAX_COLUMNS = 3_000;

    /** 지표별 묶음 코드 → 이름. 코드는 설정 필드 이름 · 화면 체크박스 이름과 같다. */
    public static final Map<IndicatorKind, Map<String, String>> BUNDLES = new LinkedHashMap<>();

    static {
        BUNDLES.put(IndicatorKind.VOLUME, ordered(
                "sma", "평균 거래량", "rvol", "상대 거래량(RVOL)", "vo", "거래량 오실레이터(VO)",
                "mfi", "MFI", "cmf", "CMF", "obv", "OBV"));
        BUNDLES.put(IndicatorKind.MA, ordered(
                "raw", "평균선 원값", "distance", "종가와 선의 거리", "slope", "선의 기울기",
                "gap", "두 선의 간격", "above", "두 선의 위치"));
        BUNDLES.put(IndicatorKind.RSI, ordered(
                "raw", "RSI 원값", "center", "50과의 거리", "delta", "RSI 변화량",
                "pairGap", "두 기간 RSI 차이", "pairAbove", "두 기간 RSI 위치", "zones", "하단·상단 구간 상태",
                "stoch", "StochRSI"));
        BUNDLES.put(IndicatorKind.MACD, ordered(
                "base", "MACD·신호선·히스토그램", "pct", "가격 대비 비율", "delta", "세 값의 변화량",
                "position", "0선·신호선 위치", "cross", "0선 교차 이벤트"));
        BUNDLES.put(IndicatorKind.ATR, ordered(
                "tr", "TR 원값", "atr", "ATR 원값", "atrPct", "가격 대비 ATR", "trToAtr", "현재 TR/ATR",
                "delta", "ATR 변화량", "pairRatio", "두 기간 ATR 비율", "pairAbove", "두 기간 ATR 위치"));
        BUNDLES.put(IndicatorKind.BB, ordered(
                "mid", "가운데선(SMA)", "std", "표준편차", "z", "z값", "bands", "상단·하단 밴드",
                "width", "밴드 폭", "percentB", "%B", "flags", "상단 초과·하단 미만"));
    }

    private IndicatorPlanner() {
    }

    public static IndicatorPlan plan(IndicatorConfig raw) {
        IndicatorConfig c = raw.normalized();
        Builder b = new Builder();
        if (c.volume().enabled()) volume(c.volume(), b);
        if (c.ma().enabled()) ma(c.ma(), b);
        if (c.rsi().enabled()) rsi(c.rsi(), b);
        if (c.macd().enabled()) macd(c.macd(), b);
        if (c.atr().enabled()) atr(c.atr(), b);
        if (c.bb().enabled()) bb(c.bb(), b);
        return b.build(c);
    }

    // =================================================================== 거래량

    private static void volume(IndicatorConfig.Volume v, Builder b) {
        IndicatorKind k = IndicatorKind.VOLUME;
        boolean any = v.sma() || v.rvol() || v.vo() || v.mfi() || v.cmf() || v.obv();
        if (!any) {
            b.error(k, "거래량: 만들 변수 묶음을 하나 이상 고르세요.");
            return;
        }
        boolean needPeriods = v.sma() || v.rvol() || v.vo() || v.mfi() || v.cmf();
        if (needPeriods && !b.checkPeriods(k, "거래량 기간", v.periods(), 1)) {
            return;
        }
        List<Integer> s = v.periods();
        for (int n : s) {
            if (v.sma()) {
                b.add(k, "sma", "volume_sma_" + n, "volume_sma:" + n,
                        "평균 거래량 (최근 " + n + "분)",
                        "t를 포함한 최근 " + n + "개 1분봉 volume 의 산술평균",
                        "단위는 기준 자산 수량(BTC·ETH 개수)입니다. 값이 클수록 최근 " + n + "분 동안 거래가 활발했습니다.",
                        List.of("volume"), "N=" + n, n - 1, x -> Ta.sma(x.volume, n));
            }
        }
        for (int n : s) {
            if (v.rvol()) {
                b.add(k, "rvol", "rvol_" + n, "rvol:" + n,
                        "상대 거래량 (직전 " + n + "분 평균 대비)",
                        "volume[t] ÷ volume_sma_" + n + "[t−1]  (분모는 현재 봉을 뺀 직전 " + n + "개 봉의 평균)",
                        "1 = 평소 수준, 3 = 평소의 3배. 현재 봉을 평균에서 빼서 급증 정도가 희석되지 않게 했습니다. 분모가 0이면 null.",
                        List.of("volume"), "N=" + n, n, x -> Ta.rvol(x.volume, n));
            }
        }
        if (v.vo()) {
            if (s.size() < 2) {
                b.warn(k, "거래량 오실레이터(VO)는 짧은·긴 기간 쌍이 필요해 기간이 2개 이상일 때만 만듭니다.");
            }
            for (int i = 0; i < s.size(); i++) {
                for (int j = i + 1; j < s.size(); j++) {
                    int sh = s.get(i);
                    int lo = s.get(j);
                    b.add(k, "vo", "vo_" + sh + "_" + lo, "vo:" + sh + ":" + lo,
                            "거래량 오실레이터 (" + sh + "분 평균 vs " + lo + "분 평균)",
                            "100 × (volume_sma_" + sh + " − volume_sma_" + lo + ") ÷ volume_sma_" + lo,
                            "+ 면 최근 " + sh + "분 거래량이 " + lo + "분 평균보다 많다는 뜻입니다(%). 긴 평균이 0이면 null.",
                            List.of("volume"), "short=" + sh + ", long=" + lo, lo - 1,
                            x -> Ta.volumeOscillator(x.volume, sh, lo));
                }
            }
        }
        for (int n : s) {
            if (v.mfi()) {
                b.add(k, "mfi", "mfi_" + n, "mfi:" + n,
                        "MFI (" + n + "분)",
                        "대표가격 (H+L+C)/3 × volume 을 직전보다 오르면 양·내리면 음의 흐름으로 나눠, 100 − 100/(1 + 최근 " + n + "개 양의 합 ÷ 음의 합)",
                        "0~100. 거래량을 반영한 RSI 입니다. 음의 흐름만 0이면 100, 양의 흐름만 0이면 0, 둘 다 0이면 50.",
                        List.of("high", "low", "close", "volume"), "N=" + n, n,
                        x -> Ta.mfi(x.high, x.low, x.close, x.volume, n));
            }
        }
        for (int n : s) {
            if (v.cmf()) {
                b.add(k, "cmf", "cmf_" + n, "cmf:" + n,
                        "CMF (" + n + "분)",
                        "최근 " + n + "봉 Σ(((C−L) − (H−C)) ÷ (H−L) × volume) ÷ Σvolume",
                        "−1 ~ +1. + 면 종가가 봉의 위쪽에서 끝난 거래량이 많았다는 뜻입니다. 실제 매수 주도 체결량은 아닙니다. H = L 이면 그 봉의 배수는 0, 거래량 합이 0이면 null.",
                        List.of("high", "low", "close", "volume"), "N=" + n, n - 1,
                        x -> Ta.cmf(x.high, x.low, x.close, x.volume, n));
            }
        }
        if (v.obv()) {
            b.add(k, "obv", "obv", "obv",
                    "OBV (누적 거래량 방향)",
                    "종가가 오르면 +volume, 내리면 −volume, 같으면 그대로 누적. 시작값 0",
                    "누적값이라 수준보다 변화가 의미 있습니다. 데이터 시작과 빈 1분 뒤 새 구간마다 0부터 다시 셉니다.",
                    List.of("close", "volume"), "기간 없음", 0, x -> Ta.obv(x.close, x.volume));
        }
    }

    // =================================================================== 이동평균선

    private record Line(String type, int n) {
        String id() {
            return type.toLowerCase(Locale.ROOT) + "_" + n;
        }

        String label() {
            return type + " " + n;
        }
    }

    private static void ma(IndicatorConfig.Ma m, Builder b) {
        IndicatorKind k = IndicatorKind.MA;
        if (!(m.raw() || m.distance() || m.slope() || m.gap() || m.above())) {
            b.error(k, "이동평균선: 만들 변수 묶음을 하나 이상 고르세요.");
            return;
        }
        if (m.types().isEmpty()) {
            b.error(k, "이동평균선: 종류(SMA·EMA·WMA·VWMA)를 하나 이상 고르세요.");
            return;
        }
        if (!b.checkPeriods(k, "이동평균 기간", m.periods(), 1)) {
            return;
        }
        List<Line> lines = new ArrayList<>();
        for (String t : m.types()) {
            for (int n : m.periods()) {
                lines.add(new Line(t, n));
            }
        }
        // 화면 · 결과에서 기간 → 종류 순으로 보이도록 정렬
        lines.sort((a, c) -> a.n != c.n ? Integer.compare(a.n, c.n)
                : Integer.compare(IndicatorConfig.Ma.TYPE_ORDER.indexOf(a.type), IndicatorConfig.Ma.TYPE_ORDER.indexOf(c.type)));

        for (Line l : lines) {
            if (m.raw()) {
                b.add(k, "raw", l.id(), l.type.toLowerCase(Locale.ROOT) + ":" + l.n, maTitle(l), maFormula(l),
                        "가격 단위(원·달러)입니다. 다른 종목과 비교하려면 거리 · 간격 같은 비율 변수를 쓰세요.",
                        maInputs(l), "N=" + l.n, l.n - 1, x -> x.ma(l.type, l.n));
            }
        }
        for (Line l : lines) {
            if (m.distance()) {
                b.add(k, "distance", "distance_" + l.id(), "distance:" + l.id(),
                        "종가와 " + l.label() + " 의 거리",
                        "close ÷ " + l.id() + " − 1",
                        "0.01 = 종가가 " + l.label() + " 보다 약 1% 위, −0.02 = 2% 아래. 평균선이 0이거나 없으면 null.",
                        maInputs(l), "N=" + l.n, l.n - 1, x -> Ta.ratioMinusOne(x.close, x.ma(l.type, l.n)));
            }
        }
        if (m.slope()) {
            if (m.slopeLags().isEmpty()) {
                b.warn(k, "이동평균선: 기울기 비교 간격(L)이 없어 기울기 변수를 만들지 않습니다.");
            } else if (b.checkPeriods(k, "기울기 비교 간격", m.slopeLags(), 1)) {
                for (Line l : lines) {
                    for (int lag : m.slopeLags()) {
                        b.add(k, "slope", "slope_" + l.id() + "_" + lag, "slope:" + l.id() + ":" + lag,
                                l.label() + " 의 " + lag + "분 기울기",
                                l.id() + "[t] ÷ " + l.id() + "[t−" + lag + "] − 1",
                                "+ 면 평균선이 " + lag + "분 전보다 올라간 상태입니다. 각도가 아니라 " + lag + "분 동안의 상대 변화율입니다.",
                                maInputs(l), "N=" + l.n + ", L=" + lag, l.n - 1 + lag,
                                x -> Ta.pctChange(x.ma(l.type, l.n), lag));
                    }
                }
            }
        }
        if (m.gap() || m.above()) {
            List<Line[]> pairs = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                for (int j = i + 1; j < lines.size(); j++) {
                    Line a = lines.get(i);
                    Line c = lines.get(j);
                    if (IndicatorConfig.Ma.SAME_TYPE.equals(m.pairScope()) && (!a.type.equals(c.type) || a.n == c.n)) {
                        continue;
                    }
                    pairs.add(new Line[]{a, c});
                }
            }
            if (pairs.isEmpty()) {
                b.warn(k, "이동평균선: 비교할 두 선의 쌍이 없어 간격 · 위치 변수를 만들지 않습니다 (같은 종류 안에서는 기간이 2개 이상 필요).");
            }
            for (Line[] p : pairs) {
                Line a = p[0];
                Line c = p[1];
                String pid = a.id() + "__" + c.id();
                int w = Math.max(a.n, c.n) - 1;
                if (m.gap()) {
                    b.add(k, "gap", "gap_" + pid, "gap:" + pid,
                            a.label() + " 와 " + c.label() + " 의 간격",
                            a.id() + " ÷ " + c.id() + " − 1",
                            "+ 면 " + a.label() + " 가 " + c.label() + " 보다 위입니다(비율). 비율이라 BTC·ETH 를 함께 비교하기 쉽습니다.",
                            mergeInputs(a, c), a.label() + " vs " + c.label(), w,
                            x -> Ta.ratioMinusOne(x.ma(a.type, a.n), x.ma(c.type, c.n)));
                }
                if (m.above()) {
                    b.add(k, "above", "above_" + pid, "above:" + pid,
                            a.label() + " 가 " + c.label() + " 위에 있는가",
                            "1 if " + a.id() + " > " + c.id() + " else 0  (같으면 0)",
                            "간격(gap)의 부호와 같은 정보를 0/1 로 나타낸 중복 표현입니다. 변수 평가에서 추가 가치가 없으면 뺄 수 있습니다.",
                            mergeInputs(a, c), a.label() + " vs " + c.label(), w,
                            x -> Ta.greater(x.ma(a.type, a.n), x.ma(c.type, c.n)));
                }
            }
        }
    }

    private static String maTitle(Line l) {
        return switch (l.type) {
            case "SMA" -> "SMA " + l.n + " (최근 " + l.n + "분 종가 단순 평균)";
            case "EMA" -> "EMA " + l.n + " (최근 값에 무게를 둔 지수 평균)";
            case "WMA" -> "WMA " + l.n + " (최근일수록 큰 선형 가중 평균)";
            default -> "VWMA " + l.n + " (거래량 가중 평균)";
        };
    }

    private static String maFormula(Line l) {
        return switch (l.type) {
            case "SMA" -> "최근 " + l.n + "개 종가의 산술평균";
            case "EMA" -> "alpha = 2/(" + l.n + "+1). 첫 값 = 처음 " + l.n + "개 종가의 SMA, 이후 alpha×close + (1−alpha)×이전 EMA";
            case "WMA" -> "가장 최근 봉 가중치 " + l.n + ", 그 전 " + (l.n - 1) + ", …, 가장 오래된 봉 1 로 가중 평균";
            default -> "최근 " + l.n + "개 Σ(close×volume) ÷ Σvolume (거래량 합이 0이면 null)";
        };
    }

    private static List<String> maInputs(Line l) {
        return "VWMA".equals(l.type) ? List.of("close", "volume") : List.of("close");
    }

    private static List<String> mergeInputs(Line a, Line c) {
        return "VWMA".equals(a.type) || "VWMA".equals(c.type) ? List.of("close", "volume") : List.of("close");
    }

    // =================================================================== RSI

    private static void rsi(IndicatorConfig.Rsi r, Builder b) {
        IndicatorKind k = IndicatorKind.RSI;
        if (!(r.raw() || r.center() || r.delta() || r.pairGap() || r.pairAbove() || r.zones() || r.stoch())) {
            b.error(k, "RSI: 만들 변수 묶음을 하나 이상 고르세요.");
            return;
        }
        if (!b.checkPeriods(k, "RSI 기간", r.periods(), 1)) {
            return;
        }
        List<Integer> s = r.periods();
        for (int n : s) {
            if (r.raw()) {
                b.add(k, "raw", "rsi_" + n, "rsi:" + n, "RSI " + n,
                        "delta = close[t] − close[t−1]. 처음 " + n + "개 변화량의 평균 상승·하락폭으로 시작해 와일더 평활 ((" + n + "−1)×이전 + 현재)/" + n + ". RSI = 100 − 100/(1 + 평균 상승 ÷ 평균 하락)",
                        "0~100. 최근 상승폭이 클수록 높습니다. 30·70 은 전통 참고값일 뿐 1분봉의 반전 신호로 확정된 값이 아닙니다. 강한 추세에서는 극단값이 오래 이어질 수 있습니다.",
                        List.of("close"), "N=" + n, n, x -> x.rsi(n));
            }
        }
        for (int n : s) {
            if (r.center()) {
                b.add(k, "center", "rsi_center_" + n, "rsi_center:" + n, "RSI " + n + " 과 50의 거리",
                        "rsi_" + n + " − 50",
                        "+ 면 상승 우위, − 면 하락 우위. RSI 원값에서 다시 계산할 수 있는 중복 표현입니다.",
                        List.of("close"), "N=" + n, n, x -> Ta.minus(x.rsi(n), Ta.scale(onesLike(x), 50)));
            }
        }
        if (r.delta()) {
            if (r.deltaLags().isEmpty()) {
                b.warn(k, "RSI: 변화량 비교 간격(L)이 없어 RSI 변화량을 만들지 않습니다.");
            } else if (b.checkPeriods(k, "RSI 변화량 간격", r.deltaLags(), 1)) {
                for (int n : s) {
                    for (int lag : r.deltaLags()) {
                        b.add(k, "delta", "rsi_delta_" + n + "_" + lag, "rsi_delta:" + n + ":" + lag,
                                "RSI " + n + " 의 " + lag + "분 변화량",
                                "rsi_" + n + "[t] − rsi_" + n + "[t−" + lag + "]",
                                "+ 면 RSI 가 " + lag + "분 전보다 올라갔습니다 (포인트 단위).",
                                List.of("close"), "N=" + n + ", L=" + lag, n + lag, x -> Ta.delta(x.rsi(n), lag));
                    }
                }
            }
        }
        if ((r.pairGap() || r.pairAbove()) && s.size() < 2) {
            b.warn(k, "RSI: 두 기간 비교(차이 · 위치)는 기간이 2개 이상일 때만 만듭니다.");
        }
        for (int i = 0; i < s.size(); i++) {
            for (int j = i + 1; j < s.size(); j++) {
                int sh = s.get(i);
                int lo = s.get(j);
                if (r.pairGap()) {
                    b.add(k, "pairGap", "rsi_gap_" + sh + "_" + lo, "rsi_gap:" + sh + ":" + lo,
                            "RSI " + sh + " − RSI " + lo,
                            "rsi_" + sh + " − rsi_" + lo,
                            "+ 면 짧은 기간 RSI 가 더 높습니다 = 최근 움직임이 더 강한 상승 쪽.",
                            List.of("close"), "short=" + sh + ", long=" + lo, lo, x -> Ta.minus(x.rsi(sh), x.rsi(lo)));
                }
                if (r.pairAbove()) {
                    b.add(k, "pairAbove", "rsi_pair_above_" + sh + "_" + lo, "rsi_pair_above:" + sh + ":" + lo,
                            "RSI " + sh + " 가 RSI " + lo + " 보다 큰가",
                            "1 if rsi_" + sh + " > rsi_" + lo + " else 0  (같으면 0)",
                            "차이(rsi_gap)의 부호와 같은 정보를 0/1 로 나타낸 중복 표현입니다.",
                            List.of("close"), "short=" + sh + ", long=" + lo, lo, x -> Ta.greater(x.rsi(sh), x.rsi(lo)));
                }
            }
        }
        if (r.zones()) {
            if (r.bands().isEmpty()) {
                b.warn(k, "RSI: 하단·상단 기준값 쌍이 없어 구간 상태 변수를 만들지 않습니다.");
            }
            for (Band band : r.bands()) {
                if (!(band.lower() >= 0 && band.lower() < band.upper() && band.upper() <= 100)) {
                    b.error(k, "RSI 기준값은 0 ≤ 하단 < 상단 ≤ 100 이어야 합니다: (" + num(band.lower()) + ", " + num(band.upper()) + ")");
                    return;
                }
            }
            for (int n : s) {
                for (Band band : r.bands()) {
                    String lo = num(band.lower());
                    String up = num(band.upper());
                    b.add(k, "zones", "rsi_lower_" + n + "_" + enc(band.lower()), "rsi_lower:" + n + ":" + band.lower(),
                            "RSI " + n + " 이 " + lo + " 미만인가 (하단 구간)",
                            "1 if rsi_" + n + " < " + lo + " else 0  (같으면 0 = 구간 밖)",
                            "전통적으로 '과매도' 라고 부르는 구간이지만 반전을 보장하지 않습니다.",
                            List.of("close"), "N=" + n + ", 하단=" + lo, n, x -> Ta.below(x.rsi(n), band.lower()));
                    b.add(k, "zones", "rsi_upper_" + n + "_" + enc(band.upper()), "rsi_upper:" + n + ":" + band.upper(),
                            "RSI " + n + " 이 " + up + " 초과인가 (상단 구간)",
                            "1 if rsi_" + n + " > " + up + " else 0  (같으면 0 = 구간 밖)",
                            "전통적으로 '과매수' 라고 부르는 구간이지만 반전을 보장하지 않습니다.",
                            List.of("close"), "N=" + n + ", 상단=" + up, n, x -> Ta.above(x.rsi(n), band.upper()));
                }
            }
        }
        if (r.stoch()) {
            if (r.stochRanges().isEmpty()) {
                b.warn(k, "RSI: StochRSI 범위 기간(R)이 없어 StochRSI 를 만들지 않습니다.");
            } else if (b.checkPeriods(k, "StochRSI 범위 기간", r.stochRanges(), 2)) {
                for (int n : s) {
                    for (int rr : r.stochRanges()) {
                        b.add(k, "stoch", "stochrsi_" + n + "_" + rr, "stochrsi:" + n + ":" + rr,
                                "StochRSI (RSI " + n + ", 최근 " + rr + "분 범위)",
                                "100 × (rsi_" + n + " − 최근 " + rr + "개 RSI 최솟값) ÷ (최댓값 − 최솟값)",
                                "0~100. RSI 가 최근 자기 범위의 어디쯤인지입니다. 기본 RSI 보다 극단값(0·100)에 자주 닿습니다. 범위가 0이면 null.",
                                List.of("close"), "N=" + n + ", R=" + rr, n + rr - 1, x -> Ta.stochRsi(x.rsi(n), rr));
                    }
                }
            }
        }
    }

    private static double[] onesLike(Ctx x) {
        double[] o = new double[x.size()];
        java.util.Arrays.fill(o, 1);
        return o;
    }

    // =================================================================== MACD

    private static void macd(IndicatorConfig.Macd m, Builder b) {
        IndicatorKind k = IndicatorKind.MACD;
        if (!(m.base() || m.pct() || m.delta() || m.position() || m.cross())) {
            b.error(k, "MACD: 만들 변수 묶음을 하나 이상 고르세요.");
            return;
        }
        if (!b.checkPeriods(k, "빠른 EMA 기간", m.fast(), 1) || !b.checkPeriods(k, "느린 EMA 기간", m.slow(), 1)
                || !b.checkPeriods(k, "신호 EMA 기간", m.signal(), 1)) {
            return;
        }
        List<int[]> pairs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int f : m.fast()) {
            for (int s : m.slow()) {
                if (f < s) pairs.add(new int[]{f, s});
                else skipped.add("(" + f + ", " + s + ")");
            }
        }
        if (pairs.isEmpty()) {
            b.error(k, "MACD: 빠른 기간 < 느린 기간인 조합이 없습니다. " + String.join(" ", skipped));
            return;
        }
        if (!skipped.isEmpty()) {
            b.warn(k, "MACD: 빠른 기간 ≥ 느린 기간인 조합 " + skipped.size() + "개는 만들지 않습니다: " + String.join(" ", skipped));
        }
        if (m.delta() && m.deltaLags().isEmpty()) {
            b.warn(k, "MACD: 변화량 비교 간격(L)이 없어 변화량 변수를 만들지 않습니다.");
        }
        if (m.delta() && !m.deltaLags().isEmpty() && !b.checkPeriods(k, "MACD 변화량 간격", m.deltaLags(), 1)) {
            return;
        }
        if (m.signal().size() > 1) {
            b.info(k, "MACD 선과 그 파생값(비율·변화량·0선 위치·교차)은 신호 기간과 무관해서, 신호 기간이 여러 개면 같은 값이 반복됩니다. 결과에는 하나만 저장합니다.");
        }
        for (int[] p : pairs) {
            int f = p[0];
            int s = p[1];
            for (int g : m.signal()) {
                String id = f + "_" + s + "_" + g;
                String fs = f + ":" + s;
                String fsg = fs + ":" + g;
                int wM = s - 1;
                int wS = s - 1 + g - 1;
                List<String> in = List.of("close");
                String prm = "fast=" + f + ", slow=" + s + ", signal=" + g;
                if (m.base()) {
                    b.add(k, "base", "macd_" + id, "macd:" + fs, "MACD 선 (" + f + "/" + s + ")",
                            "EMA_" + f + "(close) − EMA_" + s + "(close)",
                            "가격 단위입니다. > 0 이면 빠른 EMA 가 느린 EMA 보다 위(상승 쪽 추세).",
                            in, prm, wM, x -> x.macd(f, s));
                    b.add(k, "base", "signal_" + id, "signal:" + fsg, "MACD 신호선 (" + f + "/" + s + "/" + g + ")",
                            "EMA_" + g + "(MACD 선). 첫 값은 MACD 유효 값 " + g + "개의 평균",
                            "MACD 선을 한 번 더 부드럽게 한 선입니다.",
                            in, prm, wS, x -> x.signal(f, s, g));
                    b.add(k, "base", "hist_" + id, "hist:" + fsg, "MACD 히스토그램 (" + f + "/" + s + "/" + g + ")",
                            "MACD 선 − 신호선",
                            "> 0 이면 MACD 가 신호선 위 = 최근 추세가 가속되는 쪽.",
                            in, prm, wS, x -> x.hist(f, s, g));
                }
                if (m.pct()) {
                    b.add(k, "pct", "macd_pct_" + id, "macd_pct:" + fs, "MACD 선 ÷ 종가",
                            "macd ÷ close", "가격 수준을 지운 비율이라 BTC·ETH 와 기간을 넘어 비교하기 쉽습니다. 종가가 0이면 null.",
                            in, prm, wM, x -> Ta.ratio(x.macd(f, s), x.close));
                    b.add(k, "pct", "signal_pct_" + id, "signal_pct:" + fsg, "신호선 ÷ 종가",
                            "signal ÷ close", "신호선을 가격 대비 비율로 나타낸 값입니다.",
                            in, prm, wS, x -> Ta.ratio(x.signal(f, s, g), x.close));
                    b.add(k, "pct", "hist_pct_" + id, "hist_pct:" + fsg, "히스토그램 ÷ 종가",
                            "hist ÷ close", "히스토그램을 가격 대비 비율로 나타낸 값입니다.",
                            in, prm, wS, x -> Ta.ratio(x.hist(f, s, g), x.close));
                }
                if (m.delta()) {
                    for (int lag : m.deltaLags()) {
                        b.add(k, "delta", "macd_delta_" + id + "_" + lag, "macd_delta:" + fs + ":" + lag,
                                "MACD 선의 " + lag + "분 변화량", "macd[t] − macd[t−" + lag + "]",
                                "+ 면 MACD 선이 " + lag + "분 전보다 올라갔습니다 (가격 단위).",
                                in, prm + ", L=" + lag, wM + lag, x -> Ta.delta(x.macd(f, s), lag));
                        b.add(k, "delta", "signal_delta_" + id + "_" + lag, "signal_delta:" + fsg + ":" + lag,
                                "신호선의 " + lag + "분 변화량", "signal[t] − signal[t−" + lag + "]",
                                "+ 면 신호선이 " + lag + "분 전보다 올라갔습니다.",
                                in, prm + ", L=" + lag, wS + lag, x -> Ta.delta(x.signal(f, s, g), lag));
                        b.add(k, "delta", "hist_delta_" + id + "_" + lag, "hist_delta:" + fsg + ":" + lag,
                                "히스토그램의 " + lag + "분 변화량", "hist[t] − hist[t−" + lag + "]",
                                "+ 면 MACD 와 신호선의 차이가 " + lag + "분 전보다 커졌습니다.",
                                in, prm + ", L=" + lag, wS + lag, x -> Ta.delta(x.hist(f, s, g), lag));
                    }
                }
                if (m.position()) {
                    b.add(k, "position", "macd_pos_" + id, "macd_pos:" + fs, "MACD 선이 0 위인가",
                            "1 if macd > 0 else 0",
                            "빠른 EMA 가 느린 EMA 보다 위인지와 같은 정보입니다 (중복 표현).",
                            in, prm, wM, x -> Ta.above(x.macd(f, s), 0));
                    b.add(k, "position", "hist_pos_" + id, "hist_pos:" + fsg, "MACD 가 신호선 위인가",
                            "1 if hist > 0 else 0",
                            "MACD 선이 신호선보다 위인지와 같은 정보입니다 (중복 표현).",
                            in, prm, wS, x -> Ta.above(x.hist(f, s, g), 0));
                }
                if (m.cross()) {
                    b.add(k, "cross", "macd_zero_cross_up_" + id, "macd_cross_up:" + fs, "MACD 선 0선 상향 교차",
                            "직전 macd ≤ 0 이고 현재 macd > 0 이면 1, 아니면 0",
                            "이번 1분에 MACD 가 0선을 위로 뚫은 순간에만 1 입니다. 드물게 1 이 나옵니다.",
                            in, prm, wM + 1, x -> Ta.crossUp(x.macd(f, s)));
                    b.add(k, "cross", "macd_zero_cross_down_" + id, "macd_cross_down:" + fs, "MACD 선 0선 하향 교차",
                            "직전 macd ≥ 0 이고 현재 macd < 0 이면 1, 아니면 0",
                            "이번 1분에 MACD 가 0선을 아래로 뚫은 순간에만 1 입니다.",
                            in, prm, wM + 1, x -> Ta.crossDown(x.macd(f, s)));
                    b.add(k, "cross", "hist_zero_cross_up_" + id, "hist_cross_up:" + fsg, "히스토그램 0선 상향 교차 (MACD 가 신호선 위로)",
                            "직전 hist ≤ 0 이고 현재 hist > 0 이면 1, 아니면 0",
                            "흔히 '골든 크로스' 라고 부르는 순간입니다.",
                            in, prm, wS + 1, x -> Ta.crossUp(x.hist(f, s, g)));
                    b.add(k, "cross", "hist_zero_cross_down_" + id, "hist_cross_down:" + fsg, "히스토그램 0선 하향 교차 (MACD 가 신호선 아래로)",
                            "직전 hist ≥ 0 이고 현재 hist < 0 이면 1, 아니면 0",
                            "흔히 '데드 크로스' 라고 부르는 순간입니다.",
                            in, prm, wS + 1, x -> Ta.crossDown(x.hist(f, s, g)));
                }
            }
        }
    }

    // =================================================================== ATR

    private static void atr(IndicatorConfig.Atr a, Builder b) {
        IndicatorKind k = IndicatorKind.ATR;
        if (!(a.tr() || a.atr() || a.atrPct() || a.trToAtr() || a.delta() || a.pairRatio() || a.pairAbove())) {
            b.error(k, "ATR: 만들 변수 묶음을 하나 이상 고르세요.");
            return;
        }
        List<String> hlc = List.of("high", "low", "close");
        if (a.tr()) {
            b.add(k, "tr", "tr", "tr", "TR (이번 1분의 실제 변동 폭)",
                    "max(H − L, |H − 이전 C|, |L − 이전 C|). 구간 첫 봉은 이전 종가가 없어 H − L",
                    "가격 단위입니다. 방향은 나타내지 않고 움직임의 크기만 나타냅니다.",
                    hlc, "기간 없음", 0, Ctx::tr);
        }
        boolean needPeriods = a.atr() || a.atrPct() || a.trToAtr() || a.delta() || a.pairRatio() || a.pairAbove();
        if (!needPeriods) {
            return;
        }
        if (!b.checkPeriods(k, "ATR 기간", a.periods(), 1)) {
            return;
        }
        List<Integer> s = a.periods();
        for (int n : s) {
            if (a.atr()) {
                b.add(k, "atr", "atr_" + n, "atr:" + n, "ATR " + n + " (평균 변동 폭)",
                        "첫 값 = 처음 " + n + "개 TR 평균, 이후 와일더 평활 ((" + n + "−1)×이전 ATR + TR)/" + n,
                        "가격 단위입니다. 클수록 최근 " + n + "분 동안 가격이 거칠게 움직였습니다. 방향 · 다음 움직임 크기를 확정하지 않습니다.",
                        hlc, "N=" + n, n - 1, x -> x.atr(n));
            }
        }
        for (int n : s) {
            if (a.atrPct()) {
                b.add(k, "atrPct", "atr_pct_" + n, "atr_pct:" + n, "가격 대비 ATR " + n + " (%)",
                        "100 × atr_" + n + " ÷ close",
                        "0.1 = 최근 평균 1분 변동 폭이 가격의 0.1%. 가격 수준이 다른 BTC·ETH 를 비교하기 쉽습니다.",
                        hlc, "N=" + n, n - 1, x -> Ta.scale(Ta.ratio(x.atr(n), x.close), 100));
            }
        }
        for (int n : s) {
            if (a.trToAtr()) {
                b.add(k, "trToAtr", "tr_to_atr_" + n, "tr_to_atr:" + n, "현재 TR ÷ ATR " + n,
                        "tr ÷ atr_" + n,
                        "2 = 이번 1분의 변동 폭이 최근 평균의 2배. 갑작스러운 큰 봉을 찾는 데 씁니다. ATR 이 0이면 null.",
                        hlc, "N=" + n, n - 1, x -> Ta.ratio(x.tr(), x.atr(n)));
            }
        }
        if (a.delta()) {
            if (a.deltaLags().isEmpty()) {
                b.warn(k, "ATR: 변화량 비교 간격(L)이 없어 ATR 변화량을 만들지 않습니다.");
            } else if (b.checkPeriods(k, "ATR 변화량 간격", a.deltaLags(), 1)) {
                for (int n : s) {
                    for (int lag : a.deltaLags()) {
                        b.add(k, "delta", "atr_delta_" + n + "_" + lag, "atr_delta:" + n + ":" + lag,
                                "ATR " + n + " 의 " + lag + "분 변화량", "atr_" + n + "[t] − atr_" + n + "[t−" + lag + "]",
                                "+ 면 변동성이 " + lag + "분 전보다 커지는 중입니다 (가격 단위).",
                                hlc, "N=" + n + ", L=" + lag, n - 1 + lag, x -> Ta.delta(x.atr(n), lag));
                    }
                }
            }
        }
        if ((a.pairRatio() || a.pairAbove()) && s.size() < 2) {
            b.warn(k, "ATR: 두 기간 비교(비율 · 위치)는 기간이 2개 이상일 때만 만듭니다.");
        }
        for (int i = 0; i < s.size(); i++) {
            for (int j = i + 1; j < s.size(); j++) {
                int sh = s.get(i);
                int lo = s.get(j);
                if (a.pairRatio()) {
                    b.add(k, "pairRatio", "atr_ratio_" + sh + "_" + lo, "atr_ratio:" + sh + ":" + lo,
                            "ATR " + sh + " ÷ ATR " + lo, "atr_" + sh + " ÷ atr_" + lo,
                            "1 보다 크면 최근 " + sh + "분 변동 폭이 " + lo + "분 평균보다 큽니다 = 변동성이 커지는 중.",
                            hlc, "short=" + sh + ", long=" + lo, lo - 1, x -> Ta.ratio(x.atr(sh), x.atr(lo)));
                }
                if (a.pairAbove()) {
                    b.add(k, "pairAbove", "atr_above_" + sh + "_" + lo, "atr_above:" + sh + ":" + lo,
                            "ATR " + sh + " 이 ATR " + lo + " 보다 큰가", "1 if atr_" + sh + " > atr_" + lo + " else 0",
                            "비율(atr_ratio)이 1보다 큰지와 같은 정보입니다 (중복 표현).",
                            hlc, "short=" + sh + ", long=" + lo, lo - 1, x -> Ta.greater(x.atr(sh), x.atr(lo)));
                }
            }
        }
    }

    // =================================================================== 볼린저 밴드

    private static void bb(IndicatorConfig.Bb c, Builder b) {
        IndicatorKind k = IndicatorKind.BB;
        if (!(c.mid() || c.std() || c.z() || c.bands() || c.width() || c.percentB() || c.flags())) {
            b.error(k, "볼린저 밴드: 만들 변수 묶음을 하나 이상 고르세요.");
            return;
        }
        if (!b.checkPeriods(k, "볼린저 기간", c.periods(), 2)) {
            return;
        }
        boolean needK = c.bands() || c.width() || c.percentB() || c.flags();
        if (needK) {
            if (c.multipliers().isEmpty()) {
                b.warn(k, "볼린저 밴드: 표준편차 배수(K)가 없어 밴드 · 폭 · %B · 상태 변수를 만들지 않습니다.");
            }
            for (double m : c.multipliers()) {
                if (!(m > 0) || m > 20) {
                    b.error(k, "볼린저 밴드: 표준편차 배수는 0보다 크고 20 이하인 수여야 합니다: " + num(m));
                    return;
                }
            }
        }
        List<String> in = List.of("close");
        for (int n : c.periods()) {
            if (c.mid()) {
                b.add(k, "mid", "bb_mid_" + n, "sma:" + n, "볼린저 가운데선 (SMA " + n + ")",
                        "최근 " + n + "개 종가의 산술평균 (= sma_" + n + ")",
                        "이동평균선의 SMA " + n + " 과 같은 값입니다. 두 화면에서 모두 고르면 열은 하나만 둡니다.",
                        in, "N=" + n, n - 1, x -> x.ma("SMA", n));
            }
            if (c.std()) {
                b.add(k, "std", "bb_std_" + n, "std:" + n, "최근 " + n + "분 종가 표준편차",
                        "sqrt(Σ(close − 평균)² ÷ " + n + ")  (모집단 표준편차, ddof = 0)",
                        "가격 단위입니다. 클수록 최근 가격이 평균에서 멀리 흩어졌습니다.",
                        in, "N=" + n, n - 1, x -> x.std(n));
            }
            if (c.z()) {
                b.add(k, "z", "bb_z_" + n, "bb_z:" + n, "종가의 z값 (" + n + "분)",
                        "(close − bb_mid_" + n + ") ÷ bb_std_" + n,
                        "2 = 종가가 평균보다 표준편차 2개만큼 위. 배수 K 와 무관해 기간마다 하나만 만듭니다. 표준편차가 0이면 null.",
                        in, "N=" + n, n - 1, x -> Ta.ratio(Ta.minus(x.close, x.ma("SMA", n)), x.std(n)));
            }
        }
        if (!needK) {
            return;
        }
        for (int n : c.periods()) {
            for (double m : c.multipliers()) {
                String id = n + "_" + enc(m);
                String key = n + ":" + m;
                String kk = num(m);
                String prm = "N=" + n + ", K=" + kk;
                if (c.bands()) {
                    b.add(k, "bands", "bb_upper_" + id, "bb_upper:" + key, "볼린저 상단 (" + n + "분, " + kk + "σ)",
                            "bb_mid_" + n + " + " + kk + " × bb_std_" + n, "가격 단위입니다.",
                            in, prm, n - 1, x -> upper(x, n, m));
                    b.add(k, "bands", "bb_lower_" + id, "bb_lower:" + key, "볼린저 하단 (" + n + "분, " + kk + "σ)",
                            "bb_mid_" + n + " − " + kk + " × bb_std_" + n, "가격 단위입니다.",
                            in, prm, n - 1, x -> lower(x, n, m));
                }
                if (c.width()) {
                    b.add(k, "width", "bb_width_" + id, "bb_width:" + key, "볼린저 밴드 폭 (" + n + "분, " + kk + "σ)",
                            "(상단 − 하단) ÷ bb_mid_" + n + "  (비율로 저장, 화면에서만 ×100 해 % 로 표시)",
                            "넓을수록 최근 변동이 큽니다. 방향은 나타내지 않습니다. 가운데선이 0이면 null.",
                            in, prm, n - 1, x -> Ta.ratio(Ta.minus(upper(x, n, m), lower(x, n, m)), x.ma("SMA", n)));
                }
                if (c.percentB()) {
                    b.add(k, "percentB", "bb_percent_b_" + id, "bb_percent_b:" + key, "%B (" + n + "분, " + kk + "σ)",
                            "(close − 하단) ÷ (상단 − 하단)",
                            "0 = 하단, 0.5 = 가운데, 1 = 상단. 밴드 밖이면 0 미만 · 1 초과도 나옵니다. 표준편차가 0이면 null.",
                            in, prm, n - 1, x -> Ta.ratio(Ta.minus(x.close, lower(x, n, m)), Ta.minus(upper(x, n, m), lower(x, n, m))));
                }
                if (c.flags()) {
                    b.add(k, "flags", "bb_above_upper_" + id, "bb_above_upper:" + key, "종가가 상단 밴드 위인가 (" + n + "분, " + kk + "σ)",
                            "1 if close > 상단 else 0",
                            "밴드에 닿았다는 사실만으로 다음 봉의 반전 · 방향을 확정할 수 없습니다.",
                            in, prm, n - 1, x -> Ta.greater(x.close, upper(x, n, m)));
                    b.add(k, "flags", "bb_below_lower_" + id, "bb_below_lower:" + key, "종가가 하단 밴드 아래인가 (" + n + "분, " + kk + "σ)",
                            "1 if close < 하단 else 0",
                            "밴드에 닿았다는 사실만으로 다음 봉의 반전 · 방향을 확정할 수 없습니다.",
                            in, prm, n - 1, x -> Ta.greater(lower(x, n, m), x.close));
                }
            }
        }
    }

    private static double[] upper(Ctx x, int n, double m) {
        return x.get("bb_upper:" + n + ":" + m, () -> {
            double[] mid = x.ma("SMA", n);
            double[] sd = x.std(n);
            double[] out = new double[mid.length];
            for (int i = 0; i < out.length; i++) out[i] = mid[i] + m * sd[i];
            return out;
        });
    }

    private static double[] lower(Ctx x, int n, double m) {
        return x.get("bb_lower:" + n + ":" + m, () -> {
            double[] mid = x.ma("SMA", n);
            double[] sd = x.std(n);
            double[] out = new double[mid.length];
            for (int i = 0; i < out.length; i++) out[i] = mid[i] - m * sd[i];
            return out;
        });
    }

    // =================================================================== 공통

    /** 1.5 → "1p5", 2.0 → "2" (파일 · SQL 에 안전한 열 이름) */
    static String enc(double v) {
        return num(v).replace('.', 'p').replace('-', 'm');
    }

    /** 2.0 → "2", 1.5 → "1.5" */
    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return String.valueOf((long) v);
        }
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 열을 모으면서 같은 계산(같은 key)은 하나만 남긴다 */
    private static final class Builder {
        private final Map<String, ColumnSpec> byKey = new LinkedHashMap<>();
        private final Map<String, String> nameToKey = new LinkedHashMap<>();
        private final List<IndicatorPlan.Duplicate> duplicates = new ArrayList<>();
        private final List<IndicatorPlan.Message> messages = new ArrayList<>();
        private final Map<String, int[]> counts = new LinkedHashMap<>(); // kind:bundle → [계획, 추가]
        private int planned;

        void add(IndicatorKind kind, String bundle, String name, String key, String title, String formula, String read,
                 List<String> inputs, String params, int warmup, Function<Ctx, double[]> calc) {
            planned++;
            int[] cnt = counts.computeIfAbsent(kind.name() + ":" + bundle, x -> new int[2]);
            cnt[0]++;
            ColumnSpec existing = byKey.get(key);
            if (existing != null) {
                duplicates.add(new IndicatorPlan.Duplicate(name, existing.name(), kind));
                return;
            }
            if (nameToKey.containsKey(name)) {
                // 이름은 같은데 계산이 다른 경우는 이름 규칙상 없어야 한다
                throw new IllegalStateException("열 이름 충돌: " + name);
            }
            cnt[1]++;
            nameToKey.put(name, key);
            byKey.put(key, new ColumnSpec(name, key, kind, bundle, title, formula, read, inputs, params, warmup, calc));
        }

        void error(IndicatorKind k, String text) {
            messages.add(new IndicatorPlan.Message("ERROR", k, text));
        }

        void warn(IndicatorKind k, String text) {
            messages.add(new IndicatorPlan.Message("WARN", k, text));
        }

        void info(IndicatorKind k, String text) {
            messages.add(new IndicatorPlan.Message("INFO", k, text));
        }

        /** 기간 목록이 비었거나 범위를 벗어나면 오류를 남기고 false */
        boolean checkPeriods(IndicatorKind k, String label, List<Integer> values, int min) {
            if (values.isEmpty()) {
                error(k, k.getLabel() + ": " + label + "을(를) 하나 이상 입력하세요.");
                return false;
            }
            for (int v : values) {
                if (v < min || v > MAX_PERIOD) {
                    error(k, k.getLabel() + ": " + label + "은(는) " + min + " 이상 " + MAX_PERIOD + " 이하의 정수여야 합니다: " + v);
                    return false;
                }
            }
            return true;
        }

        IndicatorPlan build(IndicatorConfig c) {
            List<ColumnSpec> cols = new ArrayList<>(byKey.values());
            if (cols.size() > MAX_COLUMNS) {
                messages.add(new IndicatorPlan.Message("ERROR", null,
                        "열이 " + cols.size() + "개로 최대 " + MAX_COLUMNS + "개를 넘습니다. 기간 · 조합을 줄이세요."));
            }
            boolean anyEnabled = c.volume().enabled() || c.ma().enabled() || c.rsi().enabled() || c.macd().enabled()
                    || c.atr().enabled() || c.bb().enabled();
            if (!anyEnabled) {
                messages.add(new IndicatorPlan.Message("ERROR", null, "사용할 지표를 하나 이상 켜세요."));
            }
            List<IndicatorPlan.BundleCount> bundles = new ArrayList<>();
            BUNDLES.forEach((kind, map) -> map.forEach((code, label) -> {
                int[] cnt = counts.get(kind.name() + ":" + code);
                if (cnt != null) {
                    bundles.add(new IndicatorPlan.BundleCount(kind, code, label, cnt[0], cnt[1]));
                }
            }));
            int maxWarmup = cols.stream().mapToInt(ColumnSpec::warmup).max().orElse(0);
            return new IndicatorPlan(cols, planned, duplicates, messages, bundles, maxWarmup);
        }
    }
}
