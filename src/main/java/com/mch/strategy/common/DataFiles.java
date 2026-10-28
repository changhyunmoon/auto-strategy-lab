package com.mch.strategy.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V2 결과 파일 위치와 읽기 · 쓰기. 기본 위치는 C:/strategy-data/v2 (application.yml 의 strategy.data-dir).
 * <pre>
 * v2/features/{id}/{marketId}.time   행 시각 (long, 1분봉 시작 · UTC 밀리초)
 * v2/features/{id}/{marketId}.cols   지표 값 (float, 열 우선: 열 0 의 모든 행 → 열 1 …, null = NaN)
 * v2/features/{id}/{marketId}.nulls  행별 null 인 열 수 (int)
 * v2/labels/{id}/{marketId}.*        정답 상태 · label_end_time · 진입가 · 경과 분 · 만료 수익률
 * v2/splits/{id}/{marketId}.*        구간 소속 · 제외 사유
 * </pre>
 * 모든 숫자는 리틀 엔디언이고 머리말은 없다 (행 수는 DB 와 .time 파일 크기로 안다).
 */
@Component
public class DataFiles {

    private final Path root;

    public DataFiles(@Value("${strategy.data-dir}") String dataDir) {
        this.root = Path.of(dataDir).toAbsolutePath().normalize().resolve("v2");
    }

    public Path featureDir(long id) {
        return root.resolve("features").resolve(String.valueOf(id));
    }

    public Path labelDir(long id) {
        return root.resolve("labels").resolve(String.valueOf(id));
    }

    public Path splitDir(long id) {
        return root.resolve("splits").resolve(String.valueOf(id));
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------ 배열 저장 · 읽기

    public static void writeLongs(Path file, long[] values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.asLongBuffer().put(values);
        write(file, buf);
    }

    public static long[] readLongs(Path file) {
        ByteBuffer buf = read(file);
        long[] out = new long[buf.remaining() / 8];
        buf.asLongBuffer().get(out);
        return out;
    }

    public static void writeInts(Path file, int[] values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.asIntBuffer().put(values);
        write(file, buf);
    }

    public static int[] readInts(Path file) {
        ByteBuffer buf = read(file);
        int[] out = new int[buf.remaining() / 4];
        buf.asIntBuffer().get(out);
        return out;
    }

    public static void writeDoubles(Path file, double[] values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.asDoubleBuffer().put(values);
        write(file, buf);
    }

    public static double[] readDoubles(Path file) {
        ByteBuffer buf = read(file);
        double[] out = new double[buf.remaining() / 8];
        buf.asDoubleBuffer().get(out);
        return out;
    }

    public static void writeBytes(Path file, byte[] values) {
        write(file, ByteBuffer.wrap(values));
    }

    public static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("파일을 읽지 못했습니다: " + file, e);
        }
    }

    private static void write(Path file, ByteBuffer buf) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                buf.rewind();
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("파일을 쓰지 못했습니다: " + file, e);
        }
    }

    private static ByteBuffer read(Path file) {
        try {
            return ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException e) {
            throw new IllegalStateException("파일을 읽지 못했습니다: " + file, e);
        }
    }

    // ------------------------------------------------------------ 열 우선 float 행렬

    /** 열 우선 float 행렬 파일에 [row, row + values.length) 칸을 쓴다. */
    public static void writeFloatsAt(FileChannel ch, long rows, int col, int row, double[] values, int from, int count) {
        ByteBuffer buf = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            buf.putFloat((float) values[from + i]);
        }
        buf.flip();
        long pos = ((long) col * rows + row) * 4L;
        try {
            while (buf.hasRemaining()) {
                pos += ch.write(buf, pos);
            }
        } catch (IOException e) {
            throw new IllegalStateException("지표 파일을 쓰지 못했습니다", e);
        }
    }

    /** 열 하나의 [row, row + count) 값을 읽는다 */
    public static float[] readFloatsAt(Path file, long rows, int col, int row, int count) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
            long pos = ((long) col * rows + row) * 4L;
            while (buf.hasRemaining()) {
                int r = ch.read(buf, pos);
                if (r < 0) break;
                pos += r;
            }
            buf.flip();
            float[] out = new float[count];
            buf.asFloatBuffer().get(out);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("지표 파일을 읽지 못했습니다: " + file, e);
        }
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
