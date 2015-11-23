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

package org.rh.smaliex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.writer.io.DexDataStore;

import javax.annotation.Nonnull;

public class DexUtil {
    public static final int API_LEVEL = 19;
    public static Opcodes DEFAULT_OPCODES;

    public static boolean isZip(File f) {
        long n = 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            n = raf.readInt();
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return n == 0x504B0304;
    }

    public static Opcodes getDefaultOpCodes(Opcodes opc) {
        if (opc == null) {
            if (DEFAULT_OPCODES == null) {
                DEFAULT_OPCODES = Opcodes.forApi(API_LEVEL);
            }
            opc = DEFAULT_OPCODES;
        }
        return opc;
    }

    public static DexBackedDexFile loadSingleDex(File file, Opcodes opc) throws IOException {
        return new DexBackedDexFile(getDefaultOpCodes(opc),
                java.nio.file.Files.readAllBytes(file.toPath()));
    }

    public static List<DexBackedDexFile> loadMultiDex(File file) {
        return loadMultiDex(file, null);
    }

    public static List<DexBackedDexFile> loadMultiDex(File file, Opcodes opc) {
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        opc = getDefaultOpCodes(opc);
        try {
            if (isZip(file)) {
                List<byte[]> dexBytes = readMultipleDexFromJar(file);
                for (byte[] data : dexBytes) {
                    dexFiles.add(new DexBackedDexFile(opc, data));
                }
            } else {
                dexFiles.add(loadSingleDex(file, opc));
            }
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return dexFiles;
    }

    public static List<byte[]> readMultipleDexFromJar(File file) throws IOException {
        List<byte[]> dexBytes = new ArrayList<>(2);
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> zs = zipFile.entries();
            byte[] buf = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
            while (zs.hasMoreElements()) {
                ZipEntry entry = zs.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    if (entry.getSize() < 40) {
                        LLog.i("The dex file in " + file + " is too small to be a valid dex file");
                        continue;
                    }
                    InputStream is = zipFile.getInputStream(entry);
                    for (int c = is.read(buf); c > 0; c = is.read(buf)) {
                        out.write(buf, 0, c);
                    }
                    dexBytes.add(out.toByteArray());
                    out.reset();
                }
            }
            if (dexBytes.isEmpty()) {
                throw new IOException( "Cannot find classes.dex in zip file");
            }
            return dexBytes;
        }
    }

    public static class ByteData {
        private int mMaxDataPosition;
        private int mPosition;
        private byte[] mData;

        public ByteData(int initSize) {
            mData = new byte[initSize];
        }

        private void ensureCapacity(int writingPos) {
            int oldSize = mData.length;
            if (writingPos >= oldSize) {
                int newSize = (oldSize * 3) / 2 + 1;
                if (newSize <= writingPos) {
                    newSize = writingPos + 1;
                }
                mData = java.util.Arrays.copyOf(mData, newSize);
            }
            if (writingPos > mMaxDataPosition) {
                mMaxDataPosition = writingPos;
            }
        }

        public void put(byte c) {
            ensureCapacity(mPosition);
            mData[mPosition] = c;
        }

        public void put(byte[] bytes, int off, int len) {
            ensureCapacity(mPosition + len);
            System.arraycopy(bytes, off, mData, mPosition, len);
        }

        public byte get() {
            return mData[mPosition];
        }

        public void get(byte[] bytes, int off, int len) {
            System.arraycopy(mData, mPosition, bytes, off, len);
        }

        public boolean isPositionHasData() {
            return mPosition <= mMaxDataPosition;
        }

        public int remaining() {
            return mMaxDataPosition - mPosition;
        }

        public void position(int p) {
            mPosition = p;
        }
    }

    public static class MemoryDataStore implements DexDataStore {
        final ByteData mBuffer;

        public MemoryDataStore(int size) {
            mBuffer = new ByteData(size);
        }

        @Nonnull
        @Override
        public OutputStream outputAt(final int offset) {
            return new OutputStream() {
                private int mPos = offset;
                @Override
                public void write(int b) throws IOException {
                    mBuffer.position(mPos);
                    mPos++;
                    mBuffer.put((byte) b);
                }

                @Override
                public void write(@Nonnull byte[] bytes, int off, int len) throws IOException {
                    mBuffer.position(mPos);
                    mPos += len;
                    mBuffer.put(bytes, off, len);
                }
            };
        }

        @Nonnull
        @Override
        public InputStream readAt(final int offset) {
            mBuffer.position(offset);
            return new InputStream() {
                private int mPos = offset;

                @Override
                public int read() throws IOException {
                    mBuffer.position(mPos);
                    if (!mBuffer.isPositionHasData()) {
                        return -1;
                    }
                    mPos++;
                    return mBuffer.get() & 0xff;
                }

                @Override
                public int read(@Nonnull byte[] bytes, int off, int len) throws IOException {
                    mBuffer.position(mPos);
                    if (mBuffer.remaining() == 0 || !mBuffer.isPositionHasData()) {
                        return -1;
                    }
                    len = Math.min(len, mBuffer.remaining());
                    mPos += len;
                    mBuffer.get(bytes, off, len);
                    return len;
                }
            };
        }

        public void writeTo(OutputStream os) throws IOException {
            os.write(mBuffer.mData, 0, mBuffer.mMaxDataPosition);
        }

        @Override
        public void close() throws IOException {
        }
    }
}
