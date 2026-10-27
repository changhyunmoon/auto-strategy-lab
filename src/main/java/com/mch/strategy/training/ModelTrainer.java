package com.mch.strategy.training;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.mch.strategy.job.JobCancels;

import smile.base.cart.SplitRule;
import smile.classification.GradientTreeBoost;
import smile.classification.LogisticRegression;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.util.IterativeAlgorithmController;

/**
 * 모델 종류별 학습. 입력 x 는 결측을 채운 학습 표본이다.
 * 진행률은 progress(0~100, 설명) 로 알린다.
 */
final class ModelTrainer {

    private static final Formula FORMULA = Formula.lhs("y");

    private ModelTrainer() {
    }

    /**
     * @param stopX 그래디언트 부스팅 조기 종료용 자료 (없으면 null)
     * @param notes 학습 중 생긴 참고 사항 (실제 트리 수 등)을 적는다
     */
    static UnitModel train(ModelType type, Map<String, Object> params, double[][] x, int[] y,
                           double[][] stopX, int[] stopY, RunOptions.Balance balance, int seed,
                           Preprocessor pre, BiConsumer<Integer, String> progress,
                           StringBuilder notes) {
        double[] prior = prior(y);
        long distinct = java.util.Arrays.stream(y).distinct().count();
        if (!type.isBaseline() && distinct < 2) {
            notes.append("학습 표본에 정답 클래스가 하나뿐이라 최빈 클래스 모델로 대신했습니다. ");
            return new UnitModel.Constant(UnitModel.argmax(prior), prior);
        }
        return switch (type) {
            case BASELINE_MAJORITY -> new UnitModel.Constant(UnitModel.argmax(prior), prior);
            case BASELINE_PERSISTENCE -> new UnitModel.Persistence();
            case LOGISTIC -> logistic(params, x, y, pre);
            case RANDOM_FOREST -> randomForest(params, x, y, balance, seed, progress);
            case GRADIENT_BOOSTING -> gradientBoosting(params, x, y, stopX, stopY, progress, notes);
        };
    }

    static double[] prior(int[] y) {
        double[] p = new double[3];
        for (int v : y) {
            p[v]++;
        }
        for (int c = 0; c < 3; c++) {
            p[c] = y.length == 0 ? 1.0 / 3 : p[c] / y.length;
        }
        return p;
    }

    private static UnitModel logistic(Map<String, Object> params, double[][] x, int[] y, Preprocessor pre) {
        double[][] z = new double[x.length][];
        for (int i = 0; i < x.length; i++) {
            double[] row = new double[x[i].length];
            for (int j = 0; j < row.length; j++) {
                row[j] = (x[i][j] - pre.mean()[j]) / pre.std()[j];
            }
            z[i] = row;
        }
        LogisticRegression.Options o = new LogisticRegression.Options(d(params, "lambda"), 1e-5, i(params, "maxIter"));
        return new UnitModel.Logistic(LogisticRegression.fit(z, y, o), pre.mean(), pre.std());
    }

    private static UnitModel randomForest(Map<String, Object> params, double[][] x, int[] y, RunOptions.Balance balance,
                                          int seed, BiConsumer<Integer, String> progress) {
        int ntrees = i(params, "ntrees");
        int mtry = i(params, "mtry");
        if (mtry <= 0) {
            mtry = Math.max(1, (int) Math.floor(Math.sqrt(x[0].length)));
        }
        long[] seeds = new Random(seed).longs(ntrees).toArray();
        int[] classWeight = balance == RunOptions.Balance.CLASS_WEIGHT ? classWeight(y) : null;
        IterativeAlgorithmController<RandomForest.TrainingStatus> ctl = new IterativeAlgorithmController<>();
        CountDownLatch done = new CountDownLatch(1);
        ctl.subscribe(subscriber(ctl, s -> progress.accept(s.tree() * 100 / ntrees, "트리 " + s.tree() + " / " + ntrees), done));
        RandomForest.Options o = new RandomForest.Options(ntrees, Math.min(mtry, x[0].length), SplitRule.GINI,
                i(params, "maxDepth"), i(params, "maxNodes"), i(params, "nodeSize"), d(params, "subsample"),
                classWeight, seeds, ctl);
        RandomForest rf = RandomForest.fit(FORMULA, UnitModel.frame(x, y), o);
        finish(ctl, done);
        throwIfStopped(ctl);
        return new UnitModel.SmileFrame(rf, x[0].length);
    }

    private static UnitModel gradientBoosting(Map<String, Object> params, double[][] x, int[] y,
                                              double[][] stopX, int[] stopY,
                                              BiConsumer<Integer, String> progress, StringBuilder notes) {
        int ntrees = i(params, "ntrees");
        int patience = i(params, "patience");
        IterativeAlgorithmController<GradientTreeBoost.TrainingStatus> ctl = new IterativeAlgorithmController<>();
        CountDownLatch done = new CountDownLatch(1);
        ctl.subscribe(subscriber(ctl, s -> progress.accept(s.tree() * 100 / ntrees, "트리 " + s.tree() + " / " + ntrees), done));
        GradientTreeBoost.Options o = new GradientTreeBoost.Options(ntrees, i(params, "maxDepth"), i(params, "maxNodes"),
                i(params, "nodeSize"), d(params, "shrinkage"), d(params, "subsample"), null, ctl);
        GradientTreeBoost gb = GradientTreeBoost.fit(FORMULA, UnitModel.frame(x, y), o);
        finish(ctl, done);
        throwIfStopped(ctl);
        int trained = treeCount(gb);

        if (stopX == null || stopX.length == 0) {
            notes.append("트리 ").append(trained).append("개 사용. ");
            return new UnitModel.SmileFrame(gb, x[0].length);
        }
        // 조기 종료: 다 학습한 뒤, 조기 종료 구간의 Log loss 가 가장 낮은 트리 수만 남긴다.
        // Smile 이 주는 Log loss 는 확률 0 이면 무한대가 되므로 확률을 잘라 직접 계산한다.
        progress.accept(100, "조기 종료 구간으로 트리 수 고르기");
        DataFrame stop = UnitModel.frame(stopX, stopY);
        int step = Math.max(1, Math.min(10, trained / 30));
        double best = Double.POSITIVE_INFINITY;
        int bestTrees = trained;
        for (int k = step; k <= trained; k += step) {
            double loss = logLoss(k == trained ? gb : gb.trim(k), stop, stopY);
            if (loss < best - 1e-9) {
                best = loss;
                bestTrees = k;
            } else if (k - bestTrees >= patience) {
                break;
            }
        }
        if (bestTrees < trained) {
            gb = gb.trim(bestTrees);
        }
        notes.append("트리 ").append(trained).append("개 학습 → 조기 종료 구간 Log loss 가 가장 낮은 ")
                .append(bestTrees).append("개 사용 (Log loss ").append(String.format("%.4f", best)).append("). ");
        return new UnitModel.SmileFrame(gb, x[0].length);
    }

    /** 다중 분류에서 Smile 의 size() 는 클래스 수를 돌려주므로 실제 트리 수는 trees()[0] 길이로 센다 */
    static int treeCount(GradientTreeBoost gb) {
        return gb.trees()[0].length;
    }

    private static double logLoss(GradientTreeBoost model, DataFrame df, int[] y) {
        int[] classes = model.classes();
        double[] post = new double[model.numClasses()];
        double sum = 0;
        for (int i = 0; i < y.length; i++) {
            model.predict(df.get(i), post);
            double p = 0;
            for (int c = 0; c < classes.length; c++) {
                if (classes[c] == y[i]) {
                    p = post[c];
                }
            }
            sum -= Math.log(Math.max(p, 1e-15));
        }
        return sum / y.length;
    }

    /** 적은 클래스일수록 큰 정수 가중치 (최대 1000) */
    static int[] classWeight(int[] y) {
        long[] n = new long[3];
        for (int v : y) {
            n[v]++;
        }
        long max = Math.max(n[0], Math.max(n[1], n[2]));
        int[] w = new int[3];
        for (int c = 0; c < 3; c++) {
            w[c] = n[c] == 0 ? 1 : (int) Math.min(1000, Math.max(1, Math.round((double) max / n[c])));
        }
        return w;
    }

    /**
     * 진행 알림을 받는 쪽. 진행률을 알리다가 「중지」 요청을 만나면 Smile 에 멈추라고 알린다
     * (Smile 은 트리 하나를 끝낼 때마다 멈춤 여부를 확인한다).
     */
    private static <T> Flow.Subscriber<T> subscriber(IterativeAlgorithmController<T> ctl,
                                                     java.util.function.Consumer<T> onNext, CountDownLatch done) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(T item) {
                if (ctl.isInterrupted()) {
                    return;
                }
                try {
                    onNext.accept(item);
                } catch (JobCancels.CancelledException e) {
                    ctl.stop();
                }
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        };
    }

    /** 학습 도중 「중지」 했으면 덜 만든 모델을 쓰지 않고 멈춘다 */
    private static void throwIfStopped(IterativeAlgorithmController<?> ctl) {
        if (ctl.isInterrupted()) {
            throw new JobCancels.CancelledException();
        }
    }

    /** 진행 알림을 다 받은 뒤 끝낸다 (조기 종료 판단이 마지막 트리까지 반영되도록) */
    private static void finish(IterativeAlgorithmController<?> ctl, CountDownLatch done) {
        ctl.close();
        try {
            done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int i(Map<String, Object> p, String key) {
        return ((Number) p.get(key)).intValue();
    }

    private static double d(Map<String, Object> p, String key) {
        return ((Number) p.get(key)).doubleValue();
    }
}
