package com.mch.strategy.training;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mch.strategy.trainset.TrainingFiles;

/**
 * 학습 데이터 폴더의 파일을 읽는다. 작은 열(정답 · 구간 · 자산 · 시각 · 수익률)은 메모리에 올리고,
 * 큰 입력 행렬은 4MB 창 단위로 필요한 부분만 읽는다 (힙을 거의 쓰지 않는다). 한 스레드에서만 쓴다.
 */
public final class TrainingData implements AutoCloseable {

    /** 한 번에 읽는 창 크기 */
    private static final int WINDOW_BYTES = 4 << 20;

    public final int rows;
    public final int cols;
    public final List<String> columns;
    public final List<String> assets;
    public final byte[] label;
    public final byte[] split;
    public final byte[] asset;
    public final long[] time;
    public final float[] forward;
    public final float[] previous;
    /** 판단 봉 종가 (거래 성과 계산용) */
    public final float[] close;
    /** 판단 봉 고가 · 저가 (지정가 체결 판단용). 예전 학습 데이터면 null */
    public final float[] high;
    public final float[] low;
    /** 행마다의 정답 기준 % */
    public final float[] threshold;
    /** 정답 예측 기간 N봉 · 판단 간격(분) */
    public final int horizon;
    public final int decisionMinutes;

    private final FileChannel channel;
    private final long rowBytes;
    private final int windowRows;
    private final ByteBuffer window;
    private int windowStart = -1;
    private int windowEnd = -1;

    @SuppressWarnings("unchecked")
    public TrainingData(Path dir, ObjectMapper mapper) throws IOException {
        Path metaFile = dir.resolve(TrainingFiles.META);
        if (!Files.exists(metaFile)) {
            throw new IllegalStateException("학습 데이터 파일이 없습니다: " + dir);
        }
        Map<String, Object> meta = mapper.readValue(metaFile.toFile(), Map.class);
        Object format = meta.get("format");
        if (!(format instanceof Number f) || f.intValue() < TrainingFiles.FORMAT) {
            throw new IllegalStateException("예전 형식의 학습 데이터입니다. 「같은 설정으로 새로 만들기」로 다시 만들어 주세요.");
        }
        Map<String, Object> labelSpec = (Map<String, Object>) meta.get("label");
        this.horizon = ((Number) labelSpec.get("horizon")).intValue();
        this.decisionMinutes = ((Number) meta.get("decisionMinutes")).intValue();
        this.rows = ((Number) meta.get("rows")).intValue();
        this.columns = (List<String>) meta.get("columns");
        this.assets = (List<String>) meta.get("assets");
        this.cols = columns.size();

        this.label = readBytes(dir.resolve(TrainingFiles.LABEL));
        this.split = readBytes(dir.resolve(TrainingFiles.SPLIT));
        this.asset = readBytes(dir.resolve(TrainingFiles.ASSET));
        this.time = readLongs(dir.resolve(TrainingFiles.TIME));
        this.forward = readFloats(dir.resolve(TrainingFiles.FORWARD));
        this.previous = readFloats(dir.resolve(TrainingFiles.PREVIOUS));
        this.close = readFloats(dir.resolve(TrainingFiles.CLOSE));
        this.threshold = readFloats(dir.resolve(TrainingFiles.THRESHOLD));
        Path highFile = dir.resolve(TrainingFiles.HIGH), lowFile = dir.resolve(TrainingFiles.LOW);
        boolean hasRange = Files.exists(highFile) && Files.exists(lowFile);
        this.high = hasRange ? readFloats(highFile) : null;
        this.low = hasRange ? readFloats(lowFile) : null;
        if (label.length != rows || time.length != rows || forward.length != rows) {
            throw new IllegalStateException("학습 데이터 파일의 행 수가 맞지 않습니다. 학습 데이터를 다시 만들어 주세요.");
        }

        Path features = dir.resolve(TrainingFiles.FEATURES);
        this.rowBytes = (long) cols * 4;
        if (Files.size(features) != rowBytes * rows) {
            throw new IllegalStateException("입력 행렬 파일 크기가 맞지 않습니다. 학습 데이터를 다시 만들어 주세요.");
        }
        this.windowRows = (int) Math.max(1, WINDOW_BYTES / Math.max(1, rowBytes));
        this.window = ByteBuffer.allocate((int) (windowRows * rowBytes)).order(ByteOrder.LITTLE_ENDIAN);
        this.channel = FileChannel.open(features, StandardOpenOption.READ);
    }

    /** i 번째 행의 입력 값을 out 에 채운다 (NaN = 값 없음) */
    public void row(int i, double[] out) {
        if (i < windowStart || i >= windowEnd) {
            load(i);
        }
        int base = (int) ((i - windowStart) * rowBytes);
        for (int j = 0; j < cols; j++) {
            out[j] = window.getFloat(base + j * 4);
        }
    }

    private void load(int start) {
        int count = Math.min(windowRows, rows - start);
        window.clear();
        window.limit((int) (count * rowBytes));
        try {
            long pos = start * rowBytes;
            while (window.hasRemaining()) {
                int n = channel.read(window, pos + window.position());
                if (n < 0) {
                    throw new IOException("입력 행렬 파일이 예상보다 짧습니다.");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("입력 행렬을 읽지 못했습니다: " + e.getMessage(), e);
        }
        windowStart = start;
        windowEnd = start + count;
    }

    /** 조건에 맞는 행 번호 (파일 순서 = 자산별 시각순) */
    public int[] indices(int splitValue, int assetValue) {
        int n = 0;
        for (int i = 0; i < rows; i++) {
            if (split[i] == splitValue && (assetValue < 0 || asset[i] == assetValue)) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < rows; i++) {
            if (split[i] == splitValue && (assetValue < 0 || asset[i] == assetValue)) {
                out[k++] = i;
            }
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static byte[] readBytes(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    private static long[] readLongs(Path p) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        long[] out = new long[b.remaining() / 8];
        b.asLongBuffer().get(out);
        return out;
    }

    private static float[] readFloats(Path p) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[b.remaining() / 4];
        b.asFloatBuffer().get(out);
        return out;
    }
}
