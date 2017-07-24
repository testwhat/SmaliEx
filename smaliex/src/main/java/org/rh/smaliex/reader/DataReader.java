/*
 * Copyright (C) 2014 Riddle Hsu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rh.smaliex.reader;

import org.rh.smaliex.LLog;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class DataReader implements Closeable {

    private final RandomAccessFile mRaf;
    private final File mFile;
    private final byte[] mByteBuffer = new byte[8];
    private boolean mIsLittleEndian = true;

    public DataReader(String file) throws FileNotFoundException {
        this(new File(file));
    }

    public DataReader(File file) throws FileNotFoundException {
        mFile = file;
        mRaf = new RandomAccessFile(mFile, "r");
    }

    public void setIsLittleEndian(boolean isLittleEndian) {
        mIsLittleEndian = isLittleEndian;
    }

    public void seek(long offset) throws IOException {
        mRaf.seek(offset);
    }

    public final int readBytes(byte[] b) throws IOException {
        return mRaf.read(b);
    }

    public final int readBytes(char[] b) throws IOException {
        byte[] bs = new byte[b.length];
        int read = mRaf.read(bs);
        for (int i = 0; i < b.length; i++) {
            b[i] = (char) bs[i];
        }
        return read;
    }

    public final short readShort() throws IOException {
        short s = mRaf.readShort();
        if (mIsLittleEndian) {
            return (short) (((s & 0x00ff) << 8) | ((s & 0xff00) >>> 8));
        }
        return s;
    }

    public final int readInt() throws IOException {
        int i = mRaf.readInt();
        if (mIsLittleEndian) {
            return ((i & 0x000000ff) << 24)
                    | ((i & 0x0000ff00) << 8)
                    | ((i & 0x00ff0000) >>> 8)
                    | ((i & 0xff000000) >>> 24);
        }
        return i;
    }

    public final void readIntArray(@Nonnull int[] array) throws IOException {
        for (int i = 0; i < array.length; i++) {
            array[i] = readInt();
        }
    }

    public int previewInt() throws IOException {
        long pos = mRaf.getFilePointer();
        int value = readInt();
        mRaf.seek(pos);
        return value;
    }

    public final long readLong() throws IOException {
        if (mIsLittleEndian) {
            mRaf.readFully(mByteBuffer, 0, 8);
            return (long) (mByteBuffer[7]) << 56
                    | (long) (mByteBuffer[6] & 0xff) << 48
                    | (long) (mByteBuffer[5] & 0xff) << 40
                    | (long) (mByteBuffer[4] & 0xff) << 32
                    | (long) (mByteBuffer[3] & 0xff) << 24
                    | (long) (mByteBuffer[2] & 0xff) << 16
                    | (long) (mByteBuffer[1] & 0xff) << 8
                    | (long) (mByteBuffer[0] & 0xff);
        } else {
            return mRaf.readLong();
        }
    }

    public final int readRaw(byte b[], int off, int len) throws IOException {
        return mRaf.read(b, off, len);
    }

    public long position() throws IOException {
        return mRaf.getFilePointer();
    }

    public File getFile() {
        return mFile;
    }

    public FileChannel getChannel() {
        return mRaf.getChannel();
    }

    public long length() throws IOException {
        return mRaf.length();
    }

    @Override
    public void close() {
        try {
            mRaf.close();
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

}
