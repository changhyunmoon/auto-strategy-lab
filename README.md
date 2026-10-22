# auto-strategy-lab (자동 전략 탐색)

BTC·ETH 1분봉을 업비트·바이낸스에서 받아 보조지표를 계산하고, 데이터세트로 관리·시각화하는 Spring Boot 앱입니다.
설계 문서: Notion「자동 전략 탐색 프로젝트 > 시스템 구조 — 1차 구현」

## 구성

| 영역 | 사용 |
| --- | --- |
| 백엔드 | Java 21, Spring Boot 3.3, Gradle |
| DB | MySQL 8 (스키마는 Flyway: `src/main/resources/db/migration`) |
| 화면 | Thymeleaf + Layout Dialect, jQuery, Bootstrap 5 + 노션 스타일 테마 CSS, ECharts |

## 폴더

```
src/main/java/com/mch/strategy
├─ common       시간 규칙(UTC 저장 · KST 표시), 오류 응답, JSON 배열, 비동기 설정
├─ instrument   종목(asset) · 거래소 마켓(market)
├─ collect      1분봉 수집: 업비트 REST, 바이낸스 공개 파일(+REST 보완), candle_1m 저장
├─ candle       5·15분 재집계, 시각 맞추기, 누락 구간 검사
├─ feature      지표 엔진 (Ta = 계산 함수, definitions/ = 그룹 A~G 지표 28개)
├─ dataset      데이터세트 만들기(DatasetBuilder) · 조회 · 삭제
├─ job          작업 진행률
├─ viz          시각화 계산 (표본 추출 · 분포 · 상관 · 산점도 · 품질)
└─ web          화면 컨트롤러(PageController) · JSON API(ApiController)
src/main/resources
├─ templates    Thymeleaf (layout/default.html 이 공통 틀)
├─ static/css   theme.css(디자인 값) · components.css(공통 요소)
├─ static/js    common.js · collect.js · datasets.js · dataset-detail.js · viz.js
└─ db/migration V1 테이블 · V2 종목 초기값
mockups/        서버 없이 여는 화면 시안
```

## 처음 실행

1. **JDK 21 설치** (예: Eclipse Temurin 21). `java -version` 으로 확인.
2. **Gradle 저장 폴더를 영문 경로로 지정** — Windows 사용자 폴더 이름에 한글이 있으면 Gradle 테스트·실행 프로세스가
   경로를 읽지 못해 실패합니다. 시스템 환경 변수에 `GRADLE_USER_HOME=C:\gradle-home` 처럼 영문 경로를 추가하세요.
3. **MySQL 8 설치** 후 DB와 사용자 만들기:
   ```sql
   CREATE DATABASE strategy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'strategy'@'localhost' IDENTIFIED BY '비밀번호';
   GRANT ALL PRIVILEGES ON strategy.* TO 'strategy'@'localhost';
   ```
4. **비밀번호 설정**: `src/main/resources/application-local.yml` 을 만듭니다 (깃에 올리지 않음).
   ```yaml
   spring:
     datasource:
       password: 비밀번호
   ```
5. **실행**: `gradlew.bat bootRun` → http://localhost:8080
   처음 실행할 때 Flyway가 테이블과 BTC·ETH 종목·마켓 4개를 만듭니다.

테스트: `gradlew.bat test` (DB 없이 돌아갑니다)

## 사용 순서

1. **데이터 가져오기**: 종목 · 기간 · 출처 · 간격 · 지표를 고르고 「받기」. 처음에는 7일로 시험해 보세요.
2. **데이터세트**: 진행 중이면 진행률이 보이고, 완료되면 열 목록 · 품질 · 미리보기를 볼 수 있습니다.
3. **시각화**: 캔들 + 지표 패널, 분포, 상관, 산점도, 날짜별 누락을 봅니다.

## 동작 메모

- 모든 시각은 UTC로 저장하고 화면에서만 KST로 보여줍니다. 데이터 가져오기의 날짜는 UTC 날짜입니다.
- 이미 성공적으로 받은 기간은 다시 받지 않습니다. 겹치는 봉은 `INSERT IGNORE` 로 건너뜁니다.
- 작업 도중 앱이 꺼지면 이어받지 않습니다. 다음 실행 때 실패로 표시되고, 「같은 설정으로 새로 만들기」로 다시 만듭니다.
- 데이터세트 행의 앞 6열은 봉 값(시고저종 · 거래량 · 거래대금), 그 뒤가 지표 열입니다. 값은 JSON 배열로 저장합니다.
- 업비트를 고르면 업비트가 실행 시장이고, 바이낸스는 같은 시각의 참고 값(매수 주도 거래량 · 선행 수익률 등)으로 붙습니다.
- 24개월 · 2종목 · 1분봉은 수집에 수십 분, 지표 계산·저장에 추가로 수십 분이 걸릴 수 있습니다.
