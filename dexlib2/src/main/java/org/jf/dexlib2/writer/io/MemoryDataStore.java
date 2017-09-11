package org.jf.dexlib2.writer.io;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class MemoryDataStore implements DexDataStore {
    private byte[] buf;
    private int dataLength;

    public MemoryDataStore() {
        this(1024 * 1024);
    }

    public MemoryDataStore(int initialCapacity) {
        buf = new byte[initialCapacity];
    }

    public byte[] getData() {
        return buf;
    }

    private void updateDataLength(int position) {
        if (position > dataLength) {
            dataLength = position;
        }
    }

    @Nonnull @Override public OutputStream outputAt(final int offset) {
        return new OutputStream() {
            private int position = offset;
            @Override public void write(int b) throws IOException {
                growBufferIfNeeded(position);
                buf[position++] = (byte)b;
                updateDataLength(position);
            }

            @Override public void write(@Nonnull byte[] b, int off, int len) throws IOException {
                growBufferIfNeeded(position + len);
                System.arraycopy(b, off, buf, position, len);
                position += len;
                updateDataLength(position);
            }
        };
    }

    private void growBufferIfNeeded(int index) {
        if (index < buf.length) {
            return;
        }
        buf = Arrays.copyOf(buf, index + (index >> 1));
    }

    @Nonnull @Override public InputStream readAt(final int offset) {
        return new InputStream() {
            private int position = offset;

            @Override public int read() throws IOException {
                if (position >= dataLength) {
                    return -1;
                }
                return buf[position++];
            }

            @Override public int read(@Nonnull byte[] b, int off, int len) throws IOException {
                int remain = available();
                if (remain == 0 || position >= dataLength) {
                    return -1;
                }
                int readLength = Math.min(len, remain);
                System.arraycopy(buf, position, b, off, readLength);
                position += readLength;
                return readLength;
            }

            @Override public long skip(long n) throws IOException {
                int skipLength = (int) Math.min(n, available());
                position += skipLength;
                return skipLength;
            }

            @Override public int available() throws IOException {
                return dataLength - position;
            }
        };
    }

    public void writeTo(OutputStream os) throws IOException {
        os.write(buf, 0, dataLength);
    }

    @Override public void close() throws IOException {
        // no-op
    }
}
