package com.mch.strategy.training;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실험 하나 = 학습 데이터 + 모델 + 설정. 목록 정렬용 요약 점수만 DB 에 두고,
 * 상세 결과(혼동행렬 · 월별 성능 · 중요도 등)는 실험 폴더(runs/{id}/)의 JSON 파일에 둔다.
 */
@Entity
@Table(name = "model_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "training_set_id", nullable = false)
    private Long trainingSetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_code", nullable = false, length = 40)
    private ModelType modelType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params;

    @Enumerated(EnumType.STRING)
    @Column(name = "train_unit", nullable = false, length = 20)
    private RunOptions.TrainUnit trainUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "class_weight", nullable = false, length = 20)
    private RunOptions.Balance balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "early_stopping", nullable = false, length = 20)
    private RunOptions.EarlyStopping earlyStopping;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunOptions.Validation validation;

    @Column(nullable = false)
    private int folds;

    @Column(name = "first_train_fraction")
    private Double firstTrainFraction;

    @Column(name = "rolling_days")
    private Integer rollingDays;

    @Column(nullable = false)
    private int seed;

    @Column(name = "max_train_rows", nullable = false)
    private int maxTrainRows;

    @Column(name = "permutation_importance", nullable = false)
    private boolean permutationImportance;

    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModelRunStatus status;

    @Column(name = "train_accuracy")
    private Double trainAccuracy;
    @Column(name = "train_macro_f1")
    private Double trainMacroF1;
    @Column(name = "val_accuracy")
    private Double valAccuracy;
    @Column(name = "val_balanced_accuracy")
    private Double valBalancedAccuracy;
    @Column(name = "val_macro_f1")
    private Double valMacroF1;
    @Column(name = "val_log_loss")
    private Double valLogLoss;
    @Column(name = "val_baseline_macro_f1")
    private Double valBaselineMacroF1;
    @Column(name = "val_fold_f1_mean")
    private Double valFoldF1Mean;
    @Column(name = "val_fold_f1_std")
    private Double valFoldF1Std;
    @Column(name = "val_fold_f1_min")
    private Double valFoldF1Min;
    @Column(name = "val_net_return")
    private Double valNetReturn;
    @Column(name = "val_max_drawdown")
    private Double valMaxDrawdown;
    @Column(name = "val_trades")
    private Integer valTrades;

    @Enumerated(EnumType.STRING)
    @Column(name = "test_status", nullable = false, length = 20)
    private TestStatus testStatus;
    @Column(name = "test_accuracy")
    private Double testAccuracy;
    @Column(name = "test_balanced_accuracy")
    private Double testBalancedAccuracy;
    @Column(name = "test_macro_f1")
    private Double testMacroF1;
    @Column(name = "test_log_loss")
    private Double testLogLoss;
    @Column(name = "test_net_return")
    private Double testNetReturn;
    @Column(name = "test_max_drawdown")
    private Double testMaxDrawdown;
    @Column(name = "test_trades")
    private Integer testTrades;
    @Column(name = "test_run_count", nullable = false)
    private int testRunCount;
    @Column(name = "test_evaluated_at")
    private Instant testEvaluatedAt;

    @Column(name = "train_seconds")
    private Double trainSeconds;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 최종 테스트 진행 상태 */
    public enum TestStatus {
        NONE, RUNNING, DONE, FAILED
    }

    public static ModelRun create(String name, long trainingSetId, ModelType type, Map<String, Object> params,
                                  RunOptions.TrainUnit unit, RunOptions.Balance balance, RunOptions.EarlyStopping earlyStopping,
                                  RunOptions.Validation validation, int folds, Double firstTrainFraction, Integer rollingDays,
                                  int seed, int maxTrainRows, boolean permutationImportance, String memo) {
        ModelRun r = new ModelRun();
        r.name = name;
        r.trainingSetId = trainingSetId;
        r.modelType = type;
        r.params = params;
        r.trainUnit = unit;
        r.balance = balance;
        r.earlyStopping = earlyStopping;
        r.validation = validation;
        r.folds = validation.isWalkForward() ? folds : 1;
        r.firstTrainFraction = validation.isWalkForward() ? firstTrainFraction : null;
        r.rollingDays = validation == RunOptions.Validation.WALK_FORWARD_ROLLING ? rollingDays : null;
        r.seed = seed;
        r.maxTrainRows = maxTrainRows;
        r.permutationImportance = permutationImportance;
        r.memo = memo;
        r.status = ModelRunStatus.PENDING;
        r.testStatus = TestStatus.NONE;
        return r;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isBusy() {
        return status == ModelRunStatus.PENDING || status == ModelRunStatus.RUNNING || testStatus == TestStatus.RUNNING;
    }

    public void running() {
        this.status = ModelRunStatus.RUNNING;
    }

    public void trained(Scores train, Scores val, Double baselineMacroF1, FoldStats folds, Backtester.Summary trade, double seconds) {
        if (folds != null) {
            this.valFoldF1Mean = folds.mean();
            this.valFoldF1Std = folds.std();
            this.valFoldF1Min = folds.min();
        }
        if (trade != null) {
            this.valNetReturn = trade.netReturnPct();
            this.valMaxDrawdown = trade.maxDrawdownPct();
            this.valTrades = trade.trades();
        }
        this.status = ModelRunStatus.READY;
        this.trainAccuracy = train.accuracy();
        this.trainMacroF1 = train.macroF1();
        this.valAccuracy = val.accuracy();
        this.valBalancedAccuracy = val.balancedAccuracy();
        this.valMacroF1 = val.macroF1();
        this.valLogLoss = val.logLoss();
        this.valBaselineMacroF1 = baselineMacroF1;
        this.trainSeconds = seconds;
        this.message = null;
    }

    public void fail(String message) {
        this.status = ModelRunStatus.FAILED;
        this.message = message;
    }

    public void testRunning() {
        this.testStatus = TestStatus.RUNNING;
    }

    public void tested(Scores test, Backtester.Summary trade) {
        if (trade != null) {
            this.testNetReturn = trade.netReturnPct();
            this.testMaxDrawdown = trade.maxDrawdownPct();
            this.testTrades = trade.trades();
        }
        this.testStatus = TestStatus.DONE;
        this.testAccuracy = test.accuracy();
        this.testBalancedAccuracy = test.balancedAccuracy();
        this.testMacroF1 = test.macroF1();
        this.testLogLoss = test.logLoss();
        this.testRunCount++;
        this.testEvaluatedAt = Instant.now();
    }

    public void testFailed(String message) {
        this.testStatus = TestStatus.FAILED;
        this.message = "최종 테스트 실패: " + message;
    }

    /** 워크포워드 폴드별 검증 Macro F1 요약 */
    public record FoldStats(Double mean, Double std, Double min) {
    }

    /** 목록에 저장하는 요약 점수 */
    public record Scores(Double accuracy, Double balancedAccuracy, Double macroF1, Double logLoss) {
    }
}
