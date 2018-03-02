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
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

public class DataReader implements Closeable {

    private final RandomAccessFile mRaf;
    private final File mFile;
    private final MappedByteBuffer mMappedBuffer;
    private ArrayList<DataReader> mAssociatedReaders;

    public DataReader(@Nonnull String file) throws IOException {
        this(new File(file));
    }

    public DataReader(@Nonnull File file) throws IOException {
        mFile = file;
        mRaf = new RandomAccessFile(mFile, "r");
        mMappedBuffer = mRaf.getChannel().map(
                FileChannel.MapMode.READ_ONLY, 0, file.length());
        mMappedBuffer.rewind();
        setLittleEndian(true);
    }

    public void setLittleEndian(boolean isLittleEndian) {
        mMappedBuffer.order(isLittleEndian
                ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    public void seek(long offset) {
        position((int) offset);
    }

    public void position(int newPosition) {
        mMappedBuffer.position(newPosition);
    }

    public int position() {
        return mMappedBuffer.position();
    }

    public int readByte() {
        return mMappedBuffer.get() & 0xff;
    }

    public void readBytes(@Nonnull byte[] b) {
        mMappedBuffer.get(b, 0, b.length);
    }

    public void readBytes(@Nonnull char[] b) {
        final byte[] bs = new byte[b.length];
        readBytes(bs);
        for (int i = 0; i < b.length; i++) {
            b[i] = (char) bs[i];
        }
    }

    public short readShort() {
        return mMappedBuffer.getShort();
    }

    public int readInt() {
        return mMappedBuffer.getInt();
    }

    public int previewInt() {
        mMappedBuffer.mark();
        final int value = readInt();
        mMappedBuffer.reset();
        return value;
    }

    public final long readLong() {
        return mMappedBuffer.getLong();
    }

    public int readUleb128() {
        int result = readByte();
        if (result > 0x7f) {
            int curVal = readByte();
            result = (result & 0x7f) | ((curVal & 0x7f) << 7);
            if (curVal > 0x7f) {
                curVal = readByte();
                result |= (curVal & 0x7f) << 14;
                if (curVal > 0x7f) {
                    curVal = readByte();
                    result |= (curVal & 0x7f) << 21;
                    if (curVal > 0x7f) {
                        curVal = readByte();
                        result |= curVal << 28;
                    }
                }
            }
        }
        return result;
    }

    public File getFile() {
        return mFile;
    }

    public FileChannel getChannel() {
        return mRaf.getChannel();
    }

    public void addAssociatedReader(DataReader reader) {
        if (mAssociatedReaders == null) {
            mAssociatedReaders = new ArrayList<>();
        }
        mAssociatedReaders.add(reader);
    }

    @Override
    public void close() {
        try {
            mRaf.close();
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        if (mAssociatedReaders != null) {
            for (DataReader r : mAssociatedReaders) {
                r.close();
            }
        }
    }

}
