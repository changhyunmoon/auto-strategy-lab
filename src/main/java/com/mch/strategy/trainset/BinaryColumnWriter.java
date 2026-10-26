package com.mch.strategy.trainset;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Python(numpy.fromfile)이 바로 읽을 수 있는 리틀 엔디언 원시 배열 파일 쓰기.
 * float32 · int8 · int64 만 쓴다.
 */
final class BinaryColumnWriter implements AutoCloseable {

    private final OutputStream out;
    private final ByteBuffer buf = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);

    BinaryColumnWriter(Path file) throws IOException {
        this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
    }

    void writeFloat(double v) throws IOException {
        ensure(4);
        buf.putFloat(Double.isFinite(v) ? (float) v : Float.NaN);
    }

    void writeByte(int v) throws IOException {
        ensure(1);
        buf.put((byte) v);
    }

    void writeLong(long v) throws IOException {
        ensure(8);
        buf.putLong(v);
    }

    private void ensure(int bytes) throws IOException {
        if (buf.remaining() < bytes) {
            flushBuffer();
        }
    }

    private void flushBuffer() throws IOException {
        out.write(buf.array(), 0, buf.position());
        buf.clear();
    }

    @Override
    public void close() throws IOException {
        flushBuffer();
        out.close();
    }
}
