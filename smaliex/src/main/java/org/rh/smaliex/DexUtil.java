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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nonnull;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.AnalysisException;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.MethodAnalyzer;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.rewriter.MethodImplementationRewriter;
import org.jf.dexlib2.rewriter.MethodRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;

public class DexUtil {
    public static final int API_LEVEL = 19;
    public static Opcodes DEFAULT_OPCODES;

    private static final ConcurrentHashMap<Integer, SoftReference<Opcodes>> opCodesCache =
            new ConcurrentHashMap<>();

    public static Opcodes getOpcodes(int apiLevel) {
        Opcodes opcodes = getCache(opCodesCache, apiLevel);
        if (opcodes == null) {
            opcodes = new Opcodes(apiLevel);
            putCache(opCodesCache, apiLevel, opcodes);
        }
        return opcodes;
    }

    public static Opcodes getDefaultOpCodes(Opcodes opc) {
        if (opc == null) {
            if (DEFAULT_OPCODES == null) {
                DEFAULT_OPCODES = getOpcodes(API_LEVEL);
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
            if (MiscUtil.isZip(file)) {
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
            while (zs.hasMoreElements()) {
                ZipEntry entry = zs.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    if (entry.getSize() < 40) {
                        LLog.i("The dex file in " + file + " is too small to be a valid dex file");
                        continue;
                    }
                    dexBytes.add(MiscUtil.readBytes(zipFile.getInputStream(entry)));
                }
            }
            if (dexBytes.isEmpty()) {
                throw new IOException("Cannot find classes.dex in zip file");
            }
            return dexBytes;
        }
    }

    public static void odex2dex(String odex, String bootClassPath, String outFolder,
            int apiLevel) throws IOException {
        File outputFolder = new File(outFolder == null ? MiscUtil.workingDir() : outFolder);
        MiscUtil.mkdirs(outputFolder);

        Opcodes opcodes = new Opcodes(apiLevel);
        File input = new File(odex);
        DexFile odexFile = loadSingleDex(input, opcodes);
        ODexRewriter rewriter = getODexRewriter(bootClassPath, opcodes);
        if (LLog.VERBOSE) {
            rewriter.setFailInfoLocation(outputFolder.getAbsolutePath());
        }
        DexFile dex = rewriter.rewriteDexFile(odexFile);

        File outputFile = MiscUtil.changeExt(new File(outputFolder, input.getName()), "dex");
        if (outputFile.exists()) {
            outputFile = MiscUtil.appendTail(outputFile, "-deodex");
        }
        org.jf.dexlib2.writer.pool.DexPool.writeTo(outputFile.getAbsolutePath(), dex);
        LLog.i("Output to " + outputFile);
    }

    public static ClassPath getClassPath(String path, Opcodes opcodes, String ext) {
        ArrayList<DexFile> dexFiles = new ArrayList<>();
        for (File f : MiscUtil.getFiles(path, ext)) {
            dexFiles.addAll(loadMultiDex(f, opcodes));
        }
        return new ClassPath(dexFiles, opcodes.apiLevel);
    }

    // If return false, the dex may be customized format or encrypted.
    public static boolean verifyStringOffset(DexBackedDexFile dex) {
        int strIdsStartOffset = dex.readSmallUint(
                org.jf.dexlib2.dexbacked.raw.HeaderItem.STRING_START_OFFSET);
        int strStartOffset = dex.readInt(strIdsStartOffset);
        int mapOffset = dex.readSmallUint(org.jf.dexlib2.dexbacked.raw.HeaderItem.MAP_OFFSET);
        int mapSize = dex.readSmallUint(mapOffset);
        for (int i = 0; i < mapSize; i++) {
            int mapItemOffset = mapOffset + 4 + i * org.jf.dexlib2.dexbacked.raw.MapItem.ITEM_SIZE;
            if (dex.readUshort(mapItemOffset)
                    == org.jf.dexlib2.dexbacked.raw.ItemType.STRING_DATA_ITEM) {
                int realStrStartOffset = dex.readSmallUint(
                        mapItemOffset + org.jf.dexlib2.dexbacked.raw.MapItem.OFFSET_OFFSET);
                if (strStartOffset != realStrStartOffset) {
                    return false;
                }
                break;
            }
        }
        return true;
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

    public static class MemoryDataStore implements org.jf.dexlib2.writer.io.DexDataStore {
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

    public static void writeSmaliContent(String type, ClassPath classPath,
            java.io.Writer outWriter) {
        org.jf.baksmali.baksmaliOptions options = new org.jf.baksmali.baksmaliOptions();
        org.jf.dexlib2.iface.ClassDef classDef = classPath.getClassDef(type);
        options.apiLevel = classPath.apiLevel;
        options.allowOdex = true;
        options.classPath = classPath;

        ClassDefinition cd = new ClassDefinition(options, classDef);
        try {
            org.jf.util.IndentingWriter writer = new org.jf.util.IndentingWriter(outWriter);
            cd.writeTo(writer);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    private static final ConcurrentHashMap<String, SoftReference<ODexRewriter>> rewriterCache =
            new ConcurrentHashMap<>();

    private static <K, T> T getCache(Map<K, SoftReference<T>> pool, K key) {
        SoftReference<T> ref = pool.get(key);
        if (ref != null) {
            return ref.get();
        }
        return null;
    }

    private static <K, T> void putCache(Map<K, SoftReference<T>> pool, K key, T val) {
        pool.put(key, new SoftReference<>(val));
    }

    public static ODexRewriter getODexRewriter(String bootClassPathFolder, Opcodes opcodes) {
        String key = bootClassPathFolder + " " + opcodes.apiLevel;
        ODexRewriter rewriter = getCache(rewriterCache, key);
        if (rewriter == null) {
            rewriter = new ODexRewriter(new ODexRewriterModule(bootClassPathFolder, opcodes));
            putCache(rewriterCache, key, rewriter);
        }
        return rewriter;
    }

    public static class ODexRewriter extends org.jf.dexlib2.rewriter.DexRewriter {
        final ODexRewriterModule mRewriterModule;

        ODexRewriter(ODexRewriterModule module) {
            super(module);
            mRewriterModule = module;
        }

        @Nonnull
        @Override
        public DexFile rewriteDexFile(@Nonnull DexFile dexFile) {
            try {
                return org.jf.dexlib2.immutable.ImmutableDexFile.of(super.rewriteDexFile(dexFile));
            } catch (Exception e) {
                LLog.i("Failed to re-construct dex " + e);
                //LLog.ex(e);
            }
            return new FailedDexFile();
        }

        public void addDexToClassPath(DexFile dexFile) {
            mRewriterModule.mClassPath.addDex(dexFile, true);
        }

        public void recycle() {
            mRewriterModule.mClassPath.reset();
        }

        public void setFailInfoLocation(String folder) {
            mRewriterModule.mFailInfoLocation = folder;
        }

        public static boolean isValid(DexFile dexFile) {
            return !(dexFile instanceof FailedDexFile);
        }

        static final class FailedDexFile implements DexFile {
            @Nonnull
            @Override
            public java.util.Set<? extends org.jf.dexlib2.iface.ClassDef> getClasses() {
                return new java.util.HashSet<>(0);
            }
        }
    }

    // Covert optimized dex in oat to normal dex
    static class ODexRewriterModule extends RewriterModule {
        private final ClassPath mClassPath;
        private Method mCurrentMethod;
        private String mFailInfoLocation;

        public ODexRewriterModule(String bootClassPath, Opcodes opcodes, String ext) {
            mClassPath = getClassPath(bootClassPath, opcodes, ext);
        }

        public ODexRewriterModule(String bootClassPath, Opcodes opcodes) {
            this(bootClassPath, opcodes, ".dex;.jar");
        }

        @Nonnull
        @Override
        public Rewriter<MethodImplementation> getMethodImplementationRewriter(
                @Nonnull Rewriters rewriters) {
            return new MethodImplementationRewriter(rewriters) {
                @Nonnull
                @Override
                public MethodImplementation rewrite(@Nonnull MethodImplementation methodImpl) {
                    return new MethodImplementationRewriter.RewrittenMethodImplementation(
                            methodImpl) {
                        @Nonnull
                        @Override
                        public Iterable<? extends Instruction> getInstructions() {
                            MethodAnalyzer ma = new MethodAnalyzer(
                                    mClassPath, mCurrentMethod, null);
                            if (!ma.analysisInfo.isEmpty()) {
                                StringBuilder sb = new StringBuilder(256);
                                sb.append("Analysis info of ").append(mCurrentMethod.getDefiningClass())
                                        .append("->").append(mCurrentMethod.getName()).append(":\n");
                                for (String info : ma.analysisInfo) {
                                    sb.append(info).append("\n");
                                }
                                LLog.v(sb.toString());
                            }
                            AnalysisException ae = ma.getAnalysisException();
                            if (ae != null) {
                                handleAnalysisException(ae);
                            }
                            return ma.getInstructions();
                        }
                    };
                }
            };
        }

        void handleAnalysisException(AnalysisException ae) {
            LLog.e("Analysis error in class=" + mCurrentMethod.getDefiningClass()
                    + " method=" + mCurrentMethod.getName() + "\n" + ae.getContext());
            StackTraceElement[] stacks = ae.getStackTrace();
            if (LLog.VERBOSE || stacks.length < 10) {
                LLog.ex(ae);
            } else {
                final int printLine = 5;
                StringBuilder sb = new StringBuilder(1024);
                sb.append(ae.toString()).append("\n");
                int i = 0;
                int s = Math.min(printLine, stacks.length);
                for (; i < s; i++) {
                    sb.append("\tat ").append(stacks[i]).append("\n");
                }
                i = Math.max(i, stacks.length - printLine);
                if (i > s) {
                    sb.append("\t...(Skip ").append(i - s - 1).append(" traces)\n");
                }
                for (; i < stacks.length; i++) {
                    sb.append("\tat ").append(stacks[i]).append("\n");
                }
                LLog.i(sb.toString());
            }
            if (mFailInfoLocation != null) {
                String fileName = mCurrentMethod.getDefiningClass().replace(
                        "/", "-").replace(";", "") + ".smali";
                String failedCase = MiscUtil.path(mFailInfoLocation, fileName);
                try (FileWriter writer = new FileWriter(failedCase)) {
                    writeSmaliContent(mCurrentMethod.getDefiningClass(), mClassPath, writer);
                    LLog.i("Output failed class content to " + failedCase);
                } catch (IOException e) {
                    LLog.ex(e);
                }
            }
        }

        @Nonnull
        @Override
        public Rewriter<Method> getMethodRewriter(@Nonnull Rewriters rewriters) {
            return new MethodRewriter(rewriters) {
                @Nonnull
                @Override
                public Method rewrite(@Nonnull Method method) {
                    return new MethodRewriter.RewrittenMethod(method) {
                        @Override
                        public MethodImplementation getImplementation() {
                            //System.out.println("" + method.getName());
                            mCurrentMethod = method;
                            return super.getImplementation();
                        }
                    };
                }
            };
        }
    }
}
