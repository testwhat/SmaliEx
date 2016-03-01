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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nonnull;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.analysis.AnalysisException;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.MethodAnalyzer;
import org.jf.dexlib2.analysis.UnresolvedClassException;
import org.jf.dexlib2.analysis.reflection.ReflectionClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.MethodImplementationRewriter;
import org.jf.dexlib2.rewriter.MethodRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.util.IndentingWriter;

import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class DexUtil {
    public static Opcodes DEFAULT_OPCODES;

    public static Opcodes getDefaultOpCodes(Opcodes opc) {
        if (opc == null) {
            if (DEFAULT_OPCODES == null) {
                DEFAULT_OPCODES = Opcodes.forApi(VersionMap.DEFAULT);
            }
            opc = DEFAULT_OPCODES;
        }
        return opc;
    }

    public static DexBackedDexFile loadSingleDex(File file, Opcodes opc) throws IOException {
        return new DexBackedDexFile(getDefaultOpCodes(opc),
                java.nio.file.Files.readAllBytes(file.toPath()));
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
                throw new IOException("Cannot find classes.dex in zip file");
            }
            return dexBytes;
        }
    }

    public static void odex2dex(String odex, String bootClassPath, String outFolder,
            int apiLevel) throws IOException {
        File outputFolder = new File(outFolder == null ? MiscUtil.workingDir() : outFolder);
        MiscUtil.mkdirs(outputFolder);

        Opcodes opcodes = Opcodes.forApi(apiLevel);
        File input = new File(odex);
        DexFile odexFile = DexUtil.loadSingleDex(input, opcodes);
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

    public static ClassPathEx getClassPath(String path, Opcodes opcodes, String ext) {
        ArrayList<DexFile> dexFiles = new ArrayList<>();
        for (File f : MiscUtil.getFiles(path, ext)) {
            dexFiles.addAll(DexUtil.loadMultiDex(f, opcodes));
        }
        return new ClassPathEx(dexFiles, opcodes.artVersion);
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
        options.apiLevel = VersionMap.mapArtVersionToApi(classPath.oatVersion);
        options.allowOdex = true;
        options.classPath = classPath;

        ClassDefinition cd = new ClassDefinition(options, classDef);
        try {
            IndentingWriter writer = new IndentingWriter(outWriter);
            cd.writeTo(writer);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    private static final ConcurrentHashMap<String, SoftReference<ODexRewriter>> rewriterCache =
            new ConcurrentHashMap<>();

    private static <T> T getCache(Map<String, SoftReference<T>> pool, String key) {
        SoftReference<T> ref = pool.get(key);
        if (ref != null) {
            return ref.get();
        }
        return null;
    }

    private static <T> void putCache(Map<String, SoftReference<T>> pool, String key, T val) {
        pool.put(key, new SoftReference<>(val));
    }

    public static ODexRewriter getODexRewriter(String bootClassPathFolder, Opcodes opcodes) {
        String key = bootClassPathFolder + " " + opcodes.api;
        ODexRewriter rewriter = getCache(rewriterCache, key);
        if (rewriter == null) {
            rewriter = new ODexRewriter(new ODexRewriterModule(bootClassPathFolder, opcodes));
            putCache(rewriterCache, key, rewriter);
        }
        return rewriter;
    }

    public static class ClassPathEx extends ClassPath {
        @Nonnull
        private HashMap<String, ClassDef> availableClasses = Maps.newHashMap();
        ArrayList<DexFile> additionalDexFiles;

        public ClassPathEx(@Nonnull Iterable<? extends DexFile> classPath, int oatVersion) {
            super(false, oatVersion);
            DexFile basicClasses = new ImmutableDexFile(Opcodes.forApi(VersionMap.DEFAULT),
                    ImmutableSet.of(
                            new ReflectionClassDef(Class.class),
                            new ReflectionClassDef(Cloneable.class),
                            new ReflectionClassDef(Object.class),
                            new ReflectionClassDef(Serializable.class),
                            new ReflectionClassDef(String.class),
                            new ReflectionClassDef(Throwable.class)));
            addDex(basicClasses, false);
            for (DexFile dexFile : classPath) {
                addDex(dexFile, false);
            }
        }

        public void addDex(DexFile dexFile, boolean additional) {
            for (ClassDef classDef : dexFile.getClasses()) {
                ClassDef prev = availableClasses.get(classDef.getType());
                if (prev == null) {
                    availableClasses.put(classDef.getType(), classDef);
                }
            }
            if (additional) {
                if (additionalDexFiles == null) {
                    additionalDexFiles = Lists.newArrayList();
                }
                additionalDexFiles.add(dexFile);
            }
        }

        public void reset() {
            if (additionalDexFiles != null) {
                for (DexFile dexFile : additionalDexFiles) {
                    for (ClassDef classDef : dexFile.getClasses()) {
                        availableClasses.remove(classDef.getType());
                    }
                }
                additionalDexFiles.clear();
            }
            loadedClasses = CacheBuilder.newBuilder().build(classLoader);
        }

        @Nonnull
        @Override
        public ClassDef getClassDef(String type) {
            ClassDef ret = availableClasses.get(type);
            if (ret == null) {
                throw new UnresolvedClassException("Could not resolve class %s", type);
            }
            return ret;
        }
    }

    public static class ODexRewriter extends DexRewriter {
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

            @Nonnull
            @Override
            public Opcodes getOpcodes() {
                return DexUtil.getDefaultOpCodes(null);
            }
        }
    }

    // Covert optimized dex in oat to normal dex
    static class ODexRewriterModule extends RewriterModule {
        private final ClassPathEx mClassPath;
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
                                    mClassPath, mCurrentMethod, null, true);
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
