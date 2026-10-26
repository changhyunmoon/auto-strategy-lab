package com.mch.strategy.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 학습 데이터 파일 · 실험 결과(모델 · 예측 · 평가 JSON)가 저장되는 폴더.
 * application.yml 의 strategy.ml.data-dir 로 바꿀 수 있다. 깃에 올리지 않는다.
 */
@Component
public class MlPaths {

    private final Path dataDir;

    public MlPaths(@Value("${strategy.ml.data-dir}") String dataDir) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
    }

    public Path trainingSetDir(long id) {
        return dataDir.resolve("trainsets").resolve(String.valueOf(id));
    }

    public Path runDir(long id) {
        return dataDir.resolve("runs").resolve(String.valueOf(id));
    }

    /** 폴더와 그 안의 파일을 모두 지운다. 없으면 아무 일도 하지 않는다. */
    public static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new IllegalStateException("파일을 지우지 못했습니다: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("폴더를 지우지 못했습니다: " + dir, e);
        }
    }
}
