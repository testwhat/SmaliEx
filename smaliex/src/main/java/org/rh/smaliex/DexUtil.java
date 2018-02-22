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

package org.rh.smaliex;

import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.dexlib2.DexFileFactory;
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
import org.jf.dexlib2.rewriter.MethodImplementationRewriter;
import org.jf.dexlib2.rewriter.MethodRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.deopt.VdexDecompiler;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Vdex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DexUtil {
    public static int DEFAULT_API_LEVEL = 20;
    public static int API_N = 24;
    public static Opcodes DEFAULT_OPCODES;
    private static final String NO_NEED_BOOT_CLASSPATH = "NO_NEED_BOOT_CLASSPATH";

    private static final ConcurrentHashMap<Integer, SoftReference<Opcodes>> opCodesCache =
            new ConcurrentHashMap<>();

    public static Opcodes getOpcodes(int apiLevel) {
        if (apiLevel <= 0) {
            apiLevel = DEFAULT_API_LEVEL;
        }
        Opcodes opcodes = getCache(opCodesCache, apiLevel);
        if (opcodes == null) {
            opcodes = Opcodes.forApi(apiLevel);
            putCache(opCodesCache, apiLevel, opcodes);
        }
        return opcodes;
    }

    @Nonnull
    public static Opcodes getDefaultOpCodes(@Nullable Opcodes opc) {
        if (opc == null) {
            if (DEFAULT_OPCODES == null) {
                DEFAULT_OPCODES = Opcodes.getDefault();
            }
            opc = DEFAULT_OPCODES;
        }
        return opc;
    }

    @Nonnull
    public static DexBackedDexFile loadSingleDex(@Nonnull File file,
                                                 @Nullable Opcodes opc) throws IOException {
        return DexFileFactory.loadDexFile(file, getDefaultOpCodes(opc));
    }

    @Nonnull
    public static List<DexBackedDexFile> loadMultiDex(@Nonnull File file, @Nullable Opcodes opc) {
        try {
            return DexFileFactory.loadDexFiles(file, null, getDefaultOpCodes(opc));
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return Collections.emptyList();
    }

    public static void vdex2dex(@Nonnull String vdex,
                                @Nullable String outPath) throws IOException {
        odex2dex(vdex, NO_NEED_BOOT_CLASSPATH, outPath, -1);
    }

    public static void odex2dex(@Nonnull String odex,
                                @Nonnull String bootClassPath,
                                @Nullable String outPath, int apiLevel) throws IOException {
        final File outputFolder = new File(outPath == null ? MiscUtil.workingDir() : outPath);
        MiscUtil.mkdirs(outputFolder);

        final File input = new File(odex);
        final DexFile dex;
        if (NO_NEED_BOOT_CLASSPATH.equals(bootClassPath)) {
            if (MiscUtil.isVdex(input)) {
                try (DataReader r = new DataReader(input)) {
                    VdexDecompiler red = new VdexDecompiler(new Vdex(r));
                    dex = red.getUnquickenDexFile();
                }
            } else throw new IOException("Not a vdex file: " + input);
        } else {
            if (MiscUtil.checkFourBytes(input, 4, 0x30333700) && apiLevel < API_N) {
                LLog.i("The input has dex version 037, suggest to use api level " + API_N);
            }
            final Opcodes opcodes = getOpcodes(apiLevel);
            final DexFile odexFile = loadSingleDex(input, opcodes);
            final OdexRewriter rewriter = getOdexRewriter(bootClassPath, opcodes);
            if (LLog.VERBOSE) {
                rewriter.setFailInfoLocation(outputFolder.getAbsolutePath());
            }
            dex = rewriter.rewriteDexFile(odexFile);
        }

        File outputFile = MiscUtil.changeExt(new File(outputFolder, input.getName()), "dex");
        if (outputFile.exists()) {
            outputFile = MiscUtil.appendTail(outputFile, "-deodex");
        }
        DexPool.writeTo(outputFile.getAbsolutePath(), dex);
        LLog.i("Output to " + outputFile);
    }

    @Nonnull
    public static ClassPathEx getClassPath(@Nonnull String path,
                                           @Nonnull Opcodes opcodes, @Nonnull String ext) {
        final ArrayList<DexFile> dexFiles = new ArrayList<>();
        for (File f : MiscUtil.getFiles(path, ext)) {
            dexFiles.addAll(OatUtil.getDexFiles(f, opcodes.api, null));
        }
        if (dexFiles.isEmpty()) {
            LLog.i("Not added any dex from " + path);
        }
        return new ClassPathEx(dexFiles, opcodes.artVersion);
    }

    // If return false, the dex may be customized format or encrypted.
    public static boolean verifyStringOffset(@Nonnull DexBackedDexFile dex) {
        final int strIdsStartOffset = dex.readSmallUint(
                org.jf.dexlib2.dexbacked.raw.HeaderItem.STRING_START_OFFSET);
        final int strStartOffset = dex.readInt(strIdsStartOffset);
        final int mapOffset = dex.readSmallUint(org.jf.dexlib2.dexbacked.raw.HeaderItem.MAP_OFFSET);
        final int mapSize = dex.readSmallUint(mapOffset);
        for (int i = 0; i < mapSize; i++) {
            final int mapItemOffset = mapOffset + 4 +
                    i * org.jf.dexlib2.dexbacked.raw.MapItem.ITEM_SIZE;
            if (dex.readUshort(mapItemOffset)
                    == org.jf.dexlib2.dexbacked.raw.ItemType.STRING_DATA_ITEM) {
                final int realStrStartOffset = dex.readSmallUint(
                        mapItemOffset + org.jf.dexlib2.dexbacked.raw.MapItem.OFFSET_OFFSET);
                if (strStartOffset != realStrStartOffset) {
                    return false;
                }
                break;
            }
        }
        return true;
    }

    public static void writeSmaliContent(@Nonnull String type,
                                         @Nonnull ClassPath classPath,
                                         @Nonnull java.io.Writer outWriter) {
        final org.jf.baksmali.BaksmaliOptions options = new org.jf.baksmali.BaksmaliOptions();
        final org.jf.dexlib2.iface.ClassDef classDef = classPath.getClassDef(type);
        options.apiLevel = VersionMap.mapArtVersionToApi(classPath.oatVersion);
        options.allowOdex = true;
        options.classPath = classPath;

        final ClassDefinition cd = new ClassDefinition(options, classDef);
        try {
            org.jf.util.IndentingWriter writer = new org.jf.util.IndentingWriter(outWriter);
            cd.writeTo(writer);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    private static final ConcurrentHashMap<String, SoftReference<OdexRewriter>> rewriterCache =
            new ConcurrentHashMap<>();

    @Nullable
    private static <K, T> T getCache(@Nonnull Map<K, SoftReference<T>> pool, K key) {
        final SoftReference<T> ref = pool.get(key);
        if (ref != null) {
            return ref.get();
        }
        return null;
    }

    private static <K, T> void putCache(@Nonnull Map<K, SoftReference<T>> pool, K key, T val) {
        pool.put(key, new SoftReference<>(val));
    }

    @Nonnull
    public static OdexRewriter getOdexRewriter(@Nonnull String bootClassPath,
                                               @Nonnull Opcodes opcodes) {
        final String key = bootClassPath + " " + opcodes.api;
        OdexRewriter rewriter = getCache(rewriterCache, key);
        if (rewriter == null) {
            rewriter = new OdexRewriter(new OdexRewriterModule(bootClassPath, opcodes));
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
            for (DexFile dexFile : classPath) {
                addDex(dexFile, false);
            }
            if (availableClasses.get("Ljava/lang/Class;") == null) {
                final DexFile basicClasses = new ImmutableDexFile(getOpcodes(DEFAULT_API_LEVEL),
                        ImmutableSet.of(
                                new ReflectionClassDef(Class.class),
                                new ReflectionClassDef(Cloneable.class),
                                new ReflectionClassDef(Object.class),
                                new ReflectionClassDef(Serializable.class),
                                new ReflectionClassDef(String.class),
                                new ReflectionClassDef(Throwable.class)));
                addDex(basicClasses, false);
            }
        }

        public void addDex(@Nonnull DexFile dexFile, boolean additional) {
            for (ClassDef classDef : dexFile.getClasses()) {
                final ClassDef prev = availableClasses.get(classDef.getType());
                if (prev == null) {
                    availableClasses.put(classDef.getType(), classDef);
                }
                //else {
                //    LLog.v("Duplicated class " + prev.getType());
                //}
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
            final ClassDef ret = availableClasses.get(type);
            if (ret == null) {
                throw new UnresolvedClassException("Could not resolve class %s", type);
            }
            return ret;
        }
    }

    public static class OdexRewriter extends org.jf.dexlib2.rewriter.DexRewriter {
        final OdexRewriterModule mRewriterModule;

        OdexRewriter(OdexRewriterModule module) {
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
                if (e instanceof NullPointerException
                        || e instanceof ArrayIndexOutOfBoundsException) {
                    LLog.ex(e);
                }
            }
            return new FailedDexFile();
        }

        public void addDexToClassPath(@Nonnull DexFile dexFile) {
            mRewriterModule.mClassPath.addDex(dexFile, true);
        }

        public void recycle() {
            mRewriterModule.mClassPath.reset();
        }

        public void setFailInfoLocation(@Nonnull String folder) {
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

    /**
     * Convert optimized dex to a normal dex.
     */
    static class OdexRewriterModule extends RewriterModule {
        private final ClassPathEx mClassPath;
        private Method mCurrentMethod;
        private String mFailInfoLocation;

        OdexRewriterModule(@Nonnull String bootClassPath, @Nonnull Opcodes opcodes, @Nonnull String ext) {
            mClassPath = getClassPath(bootClassPath, opcodes, ext);
        }

        OdexRewriterModule(@Nonnull String bootClassPath, @Nonnull Opcodes opcodes) {
            this(bootClassPath, opcodes, ".odex;.dex;.jar;.oat");
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
                            final MethodAnalyzer ma = new MethodAnalyzer(
                                    mClassPath, mCurrentMethod, null, false);
                            if (!ma.analysisInfo.isEmpty()) {
                                StringBuilder sb = new StringBuilder(256);
                                sb.append("Analysis info of ").append(mCurrentMethod.getDefiningClass())
                                        .append("->").append(mCurrentMethod.getName()).append(":\n");
                                for (String info : ma.analysisInfo) {
                                    sb.append(info).append("\n");
                                }
                                LLog.v(sb.toString());
                            }
                            final AnalysisException ae = ma.getAnalysisException();
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
            final StackTraceElement[] stacks = ae.getCause() == null
                    ? ae.getStackTrace() : ae.getCause().getStackTrace();
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
                final String fileName = mCurrentMethod.getDefiningClass().replace(
                        "/", "-").replace(";", "") + ".smali";
                final String failedCase = MiscUtil.path(mFailInfoLocation, fileName);
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
