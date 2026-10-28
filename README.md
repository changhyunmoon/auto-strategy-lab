# auto-strategy-lab V2 (자동 전략 탐색)

BTC·ETH 완료 1분봉에서 기술 지표 후보를 만들고, 「판단 뒤 H분 안에 목표 수익 P 와 허용 손실 L 중 무엇이 먼저 일어나는지」를 정답으로 정의한 뒤,
학습 · 검증 · 최종 테스트를 시간순으로 나누는 Spring Boot 앱입니다.

설계 문서: Notion「자동 전략 탐색 프로젝트 V2 — 1분봉·기술 지표·수익 기회 정답」과 하위 문서 9개.
V1(5·15분 재집계 · 3분류 정답 · 모델 학습 화면)은 `feature/model-training` 브랜치에 그대로 있습니다.

## 흐름

```
① 원본 1분봉 → ② 기술 지표 데이터셋 → ③ H·P·L 정답 → ④ 학습·검증·테스트 분할 → (후속 설계) 모델 학습·평가
```

| 단계 | 화면 | 하는 일 |
| --- | --- | --- |
| ① 원본 1분봉 | `/raw` | 거래소 하나(업비트 · 바이낸스)의 BTC·ETH 1분봉을 받고 입력 검사(빈 1분 · 범위 오류 · 미완료 봉 등)를 기록 |
| ② 기술 지표 | `/features` | 거래량 · 이동평균선(SMA·EMA·WMA·VWMA) · RSI · MACD · ATR · 볼린저 밴드를 한 페이지에서 설정. 기간 복수 선택, 변수 묶음 토글, 실시간 열 미리보기(중복 제거 · 워밍업 · 오류) |
| ③ 정답 | `/labels` | H · P · L, 방향, 진입 기준, 동시 도달 처리, 비용 가정. 목표 먼저 / 손실 먼저 / 시간 만료 / 판정 불가 / 데이터 부족을 구분해 저장. 「탐색 기간 끝」 이후 분포는 숨김 |
| ④ 분할 | `/splits` | 세 구간 날짜를 직접 지정. `label_end_time` 이 구간 끝을 넘는 행 제외, 경계 여유. 최종 테스트 분포는 열 때마다 기록 |

모든 결과는 덮어쓰지 않고 새 번호(버전)로 쌓이며, 설정 스냅샷과 계산 코드 버전(`indicator-v2.0` · `label-v2.0` · `split-v2.0`)을 함께 저장합니다.
용어 옆 ⓘ 에 마우스를 올리면 정의 · 계산 · 읽는 법 · 주의가 나옵니다 (`static/js/glossary.js`).

## 폴더

```
src/main/java/com/mch/strategy
├─ collect      1분봉 수집 (업비트 REST, 바이낸스 공개 파일 + REST) → candle_1m
├─ instrument   종목 · 거래쌍
├─ raw          ① 원본 데이터셋 · 입력 검사(RawQuality)
├─ indicator    ② 지표 계산식(Ta) · 설정(IndicatorConfig) · 설정 → 열 목록(IndicatorPlanner)
├─ feature      ② 지표 데이터셋 만들기 · 행 보기 · CSV 내보내기 · 설정 저장
├─ label        ③ H·P·L 판정(LabelCalculator) · 정답 데이터셋
├─ split        ④ 구간 배정(SplitCalculator) · 분할 계획
├─ job          작업 진행률 · 중지
└─ web          화면(PageController) · JSON API(ApiController)
```

DB(MySQL)에는 설정 · 요약 · 버전만 두고, 큰 행 데이터(지표 값 · 정답 · 분할 소속)는 `C:/strategy-data/v2/` 파일에 둡니다 (`strategy.data-dir`).
스키마는 Flyway `V5__v2_pipeline.sql` 이 추가합니다. V1 테이블은 지우지 않으며, 1분봉(candle_1m)은 V1 · V2 가 함께 씁니다.

## 실행

JDK 21 · MySQL 8 · 영문 `GRADLE_USER_HOME` 이 필요합니다 (자세한 설치는 V1 README 와 같음).

- 실행: `gradlew.bat bootRun` → http://localhost:8080
- 테스트: `gradlew.bat test` (DB 없이 돕니다. 지표 조합 수가 노션 예시 23 · 112/304 · 36/44 · 216 · 33 · 45/42 와 같은지 확인)

## 계산 규칙 메모

- 빈 1분은 채우지 않습니다. 지표는 빈 곳 앞뒤를 다른 구간으로 보고 다시 시작하고(워밍업 → null), 정답 관찰 구간에 빈 1분이 있으면 판정 불가입니다.
- EMA 첫 값 = 처음 N개 SMA, RSI · ATR = 와일더 평활, 볼린저 표준편차 = 모집단(ddof 0), 분모 0 → null.
- 정답 관찰 구간 = 판단 봉 다음 봉부터 H개. 진입은 다음 봉 시가(첫 제안) 또는 판단 봉 종가.
- 아직 정하지 않은 것(거래소 · 방향 · 체결 · 비용 · 모델)은 화면에서 직접 고르고, 예시값을 확정값으로 보지 않습니다.
