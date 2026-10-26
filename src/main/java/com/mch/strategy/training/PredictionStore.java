package com.mch.strategy.training;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 검증 · 최종 테스트 예측을 실험 폴더에 저장한다 (거래 성과를 다른 규칙 · 비용으로 다시 계산할 때 쓴다).
 * 형식: 행 수 · 확률 유무 · [행 번호 int · 예측 byte · (확률 3개 float)] 반복
 */
final class PredictionStore {

    private PredictionStore() {
    }

    static String file(String split) {
        return "pred_" + split + ".bin";
    }

    record Stored(int[] idx, int[] pred, double[][] proba) {
    }

    static void write(Path file, int[] idx, int[] pred, double[][] proba) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
            out.writeInt(idx.length);
            out.writeBoolean(proba != null);
            for (int i = 0; i < idx.length; i++) {
                out.writeInt(idx[i]);
                out.writeByte(pred[i]);
                if (proba != null) {
                    for (int c = 0; c < 3; c++) {
                        out.writeFloat((float) proba[i][c]);
                    }
                }
            }
        }
    }

    static Stored read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            int n = in.readInt();
            boolean hasProba = in.readBoolean();
            int[] idx = new int[n], pred = new int[n];
            double[][] proba = hasProba ? new double[n][] : null;
            for (int i = 0; i < n; i++) {
                idx[i] = in.readInt();
                pred[i] = in.readByte();
                if (hasProba) {
                    proba[i] = new double[]{in.readFloat(), in.readFloat(), in.readFloat()};
                }
            }
            return new Stored(idx, pred, proba);
        }
    }
}
