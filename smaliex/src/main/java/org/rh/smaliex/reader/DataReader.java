/*
 * [The "BSD licence"]
 * Copyright (c) 2014 Riddle Hsu
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.rh.smaliex.reader;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import javax.annotation.Nonnull;

import org.rh.smaliex.LLog;

public class DataReader implements Closeable {

    private final RandomAccessFile mRaf;
    private final File mFile;
    private final byte[] mByteBuffer = new byte[8];
    private boolean mIsLittleEndian;

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
