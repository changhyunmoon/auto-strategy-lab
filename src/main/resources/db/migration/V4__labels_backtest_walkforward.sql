-- 정답 정의 확장 · 워크포워드 검증 · 기본 규칙 거래 성과

-- 정답: 예측 기간(N봉) · 기준 방식(고정 / 변동성 / 비용) · 세부 값
ALTER TABLE training_set ADD COLUMN label_horizon INT NOT NULL DEFAULT 1 AFTER label_method;
ALTER TABLE training_set ADD COLUMN threshold_mode VARCHAR(20) NOT NULL DEFAULT 'FIXED' AFTER label_horizon;
ALTER TABLE training_set ADD COLUMN label_params JSON NULL AFTER threshold_pct;
UPDATE training_set SET label_method = 'FORWARD_RETURN' WHERE label_method = 'NEXT_BAR_DIRECTION';

-- 검증 방식: HOLDOUT(학습 → 검증 1번) / WALK_FORWARD_EXPANDING / WALK_FORWARD_ROLLING
ALTER TABLE model_run ADD COLUMN validation VARCHAR(30) NOT NULL DEFAULT 'HOLDOUT' AFTER early_stopping;
ALTER TABLE model_run ADD COLUMN folds INT NOT NULL DEFAULT 1 AFTER validation;
ALTER TABLE model_run ADD COLUMN first_train_fraction DOUBLE NULL AFTER folds;   -- 워크포워드 첫 학습에 쓸 탐색 기간 비율
ALTER TABLE model_run ADD COLUMN rolling_days INT NULL AFTER first_train_fraction; -- 이동형: 학습 창 길이(일)

-- 폴드별 검증 Macro F1 의 평균 · 표준편차 · 최저 (워크포워드)
ALTER TABLE model_run ADD COLUMN val_fold_f1_mean DOUBLE NULL;
ALTER TABLE model_run ADD COLUMN val_fold_f1_std DOUBLE NULL;
ALTER TABLE model_run ADD COLUMN val_fold_f1_min DOUBLE NULL;

-- 기본 규칙(예측 클래스대로 롱·숏, 기본 수수료) 거래 성과. % 단위
ALTER TABLE model_run ADD COLUMN val_net_return DOUBLE NULL;
ALTER TABLE model_run ADD COLUMN val_max_drawdown DOUBLE NULL;
ALTER TABLE model_run ADD COLUMN val_trades INT NULL;
ALTER TABLE model_run ADD COLUMN test_net_return DOUBLE NULL;
ALTER TABLE model_run ADD COLUMN test_max_drawdown DOUBLE NULL;
ALTER TABLE model_run ADD COLUMN test_trades INT NULL;
