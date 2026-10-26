package com.mch.strategy.trainset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 테스트용 학습 데이터 폴더를 만든다. 자산 2개 · 열 4개.
 * 첫 열(signal)이 정답을 잘 설명하고, 나머지는 잡음이다. 결측(NaN)도 조금 섞는다.
 * 자산마다 앞 60% 학습 · 20% 검증 · 20% 최종 테스트.
 */
public final class TestTrainingFiles {

    private TestTrainingFiles() {
    }

    public static void write(Path dir, int rowsPerAsset) throws IOException {
        Files.createDirectories(dir);
        Random r = new Random(3);
        try (BinaryColumnWriter fx = new BinaryColumnWriter(dir.resolve(TrainingFiles.FEATURES));
             BinaryColumnWriter fy = new BinaryColumnWriter(dir.resolve(TrainingFiles.LABEL));
             BinaryColumnWriter fs = new BinaryColumnWriter(dir.resolve(TrainingFiles.SPLIT));
             BinaryColumnWriter fa = new BinaryColumnWriter(dir.resolve(TrainingFiles.ASSET));
             BinaryColumnWriter ft = new BinaryColumnWriter(dir.resolve(TrainingFiles.TIME));
             BinaryColumnWriter ff = new BinaryColumnWriter(dir.resolve(TrainingFiles.FORWARD));
             BinaryColumnWriter fp = new BinaryColumnWriter(dir.resolve(TrainingFiles.PREVIOUS))) {
            for (int a = 0; a < 2; a++) {
                double prev = Double.NaN;
                for (int i = 0; i < rowsPerAsset; i++) {
                    double signal = r.nextGaussian();
                    double fwd = signal * 0.4 + r.nextGaussian() * 0.1;
                    fx.writeFloat(signal);
                    fx.writeFloat(r.nextGaussian());
                    fx.writeFloat(i % 50 == 0 ? Double.NaN : r.nextDouble());
                    fx.writeFloat(a);
                    fy.writeByte(TrainingRows.label(fwd, 0.3));
                    fs.writeByte(i < rowsPerAsset * 0.6 ? 0 : i < rowsPerAsset * 0.8 ? 1 : 2);
                    fa.writeByte(a);
                    ft.writeLong(1_760_000_000_000L + i * 300_000L);
                    ff.writeFloat(fwd);
                    fp.writeFloat(prev);
                    prev = fwd;
                }
            }
        }
        new ObjectMapper().writeValue(dir.resolve(TrainingFiles.META).toFile(), Map.of(
                "rows", rowsPerAsset * 2,
                "columns", List.of("signal", "noise", "noise_with_gaps", "asset_flag"),
                "assets", List.of("BTC", "ETH")));
    }
}
