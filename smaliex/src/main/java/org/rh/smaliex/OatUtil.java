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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jf.baksmali.baksmaliOptions;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.AnalysisException;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.MethodAnalyzer;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.MethodImplementationRewriter;
import org.jf.dexlib2.rewriter.MethodRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.DexDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;

public class OatUtil {

    public final static String FOLDER_ORIGINAL_JAR = "original-jar";
    public final static String FOLDER_RESULT_JAR = "result-jar";
    // A sample to de-optimize system folder of an extracted ROM
    public static void deOptimizeFiles(String systemFolder, String workingDir) {
        File bootOat = new File(MiscUtil.path(systemFolder, "framework", "arm", "boot.oat"));
        if (!bootOat.exists()) {
            File bootOat64 = new File(MiscUtil.path(systemFolder, "framework", "arm64", "boot.oat"));
            if (!bootOat64.exists()) {
                LLog.i(bootOat + " not found");
                return;
            } else {
                bootOat = bootOat64;
                LLog.i("Using 64-bit boot oat " + bootOat);
            }
        }
        String outputJarFolder = MiscUtil.path(workingDir, FOLDER_RESULT_JAR);
        try {
            bootOat2Jar(bootOat.getAbsolutePath(),
                    MiscUtil.path(systemFolder, "framework"),
                    outputJarFolder);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    // Output de-optimized jar and also pack with other files in original jar
    public static void bootOat2Jar(String bootOat, String noClassJarFolder,
            String outputJarFolder) throws IOException {
        File odexFolder = prepareOdex(bootOat);
        String bootClassPathFolder = odexFolder.getAbsolutePath();
        extractDexFromBootOat(bootOat, outputJarFolder, bootClassPathFolder, noClassJarFolder);
    }

    public static void bootOat2Dex(String bootOat) {
        File odexFolder = prepareOdex(bootOat);
        File outputJarFolder = new File(new File(bootOat).getParent(), "dex");
        extractDexFromBootOat(bootOat, outputJarFolder.getAbsolutePath(),
                odexFolder.getAbsolutePath(), null);
    }

    private static File prepareOdex(String bootOat) {
        File oatFile = new File(bootOat);
        File odexFolder = new File(oatFile.getParentFile(), "odex");
        odexFolder.mkdirs();
        extractOdexFromOat(oatFile, odexFolder);
        return odexFolder;
    }

    public static void oat2dex(String oatFile, String bootClassPath) throws IOException {
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            File outFolder = new File(oatFile).getAbsoluteFile().getParentFile();
            outFolder.mkdirs();
            extractToDex(oat, outFolder, bootClassPath, true);
        }
    }

    public static Oat getOat(Elf e) throws IOException {
        DataReader r = e.getReader();
        Elf.Elf_Shdr sec = e.getSectionByName(Oat.SECTION_RODATA);
        if (sec != null) {
            r.seek(sec.getOffset());
            return new Oat(r, true);
        }
        throw new IOException("oat not found");
    }

    public static DexFile forceOdex2dex(DexFile d, ClassPath classPath) {
        baksmaliOptions options = new baksmaliOptions();
        options.classPath = classPath;
        options.apiLevel = classPath.api;
        options.allowOdex = true;
        options.deodex = true;
        options.checkPackagePrivateAccess = false;
        options.noAccessorComments = true;
        final HashSet<ClassDef> out = new HashSet<>();
        for (ClassDef c : d.getClasses()) {
            String s = SmaliUtil.getSmaliContent(c, options);
            out.add(SmaliUtil.assembleSmali(s));
        }
        return new DexFile() {
            @Override
            public Set<? extends ClassDef> getClasses() {
                return out;
            }
        };
    }

    public static ArrayList<String> getBootJarNames(String bootOat) {
        ArrayList<String> names = new ArrayList<>();
        try (Elf e = new Elf(bootOat)) {
            Oat oat = getOat(e);
            for (Oat.OatDexFile df : oat.mOatDexFiles) {
                String s = new String(df.dex_file_location_data_);
                if (s.contains(":")) {
                    continue;
                }
                names.add(s.substring(s.lastIndexOf('/') + 1));
            }
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return names;
    }

    // Get optimized dex from oat
    public static void extractOdexFromOat(File oatFile, File outputFolder) {
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            for (int i = 0; i < oat.mDexFiles.length; i++) {
                Oat.OatDexFile odf = oat.mOatDexFiles[i];
                Oat.DexFile df = oat.mDexFiles[i];
                String opath = new String(odf.dex_file_location_data_);
                int spos = opath.indexOf(':');
                if (spos > 0) {
                    // framework.jar:classes2.dex -> framework-classes2.dex
                    opath = opath.substring(0, spos - 4) + "-" + opath.substring(spos + 1);
                }
                opath = opath.substring(opath.lastIndexOf('/') + 1);
                if (outputFolder == null) {
                    outputFolder = new File(MiscUtil.workingDir());
                }
                File out = MiscUtil.changeExt(new File(outputFolder, opath), "dex");
                df.saveTo(out);
                LLog.i("Output raw dex: " + out.getAbsolutePath());
            }
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    public static void extractDexFromBootOat(String oatFile, String outputFolder,
            String bootClassPath, String noClassJarFolder) {
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            File outFolder = new File(outputFolder);
            if (!outFolder.exists()) {
                outFolder.mkdirs();
            }
            if (noClassJarFolder == null) {
                extractToDex(oat, outFolder, bootClassPath, false);
            } else {
                extractBootToDexJar(oat, outFolder, bootClassPath, noClassJarFolder);
            }
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    private static void extractToDex(Oat oat, File outputFolder,
            String bootClassPath, boolean addSelfToBcp) throws IOException {
        final int BSIZE = 8192;
        final byte[] buf = new byte[BSIZE];
        final Opcodes opcodes = new Opcodes(Opcode.LOLLIPOP);
        LLog.i("Preparing bootclasspath from " + bootClassPath);
        if (bootClassPath == null || !new File(bootClassPath).exists()) {
            LLog.e("Invalid bootclasspath: " + bootClassPath);
        }
        final OatDexRewriterModule odr = new OatDexRewriterModule(bootClassPath, opcodes);
        final DexRewriter deOpt = odr.getRewriter();

        DexFile[] dexFiles = new DexFile[oat.mOatDexFiles.length];
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.DexFile dex = oat.mDexFiles[i];
            final int dexSize = dex.mHeader.file_size_;
            ByteBuffer dexBytes = ByteBuffer.allocateDirect(dexSize);
            dex.mReader.seek(dex.mDexPosition);
            int remain = dexSize;
            int read = BSIZE > dexSize ? dexSize : BSIZE;
            int readSize;
            while ((readSize = dex.mReader.readRaw(buf, 0, read)) != -1 && remain > 0) {
                dexBytes.put(buf, 0, readSize);
                remain -= readSize;
                if (remain < BSIZE) {
                    read = remain;
                }
            }
            int length = dexBytes.position();
            dexBytes.flip();
            byte[] data = new byte[length];
            dexBytes.get(data);
            dexFiles[i] = new DexBackedDexFile(opcodes, data);
            if (addSelfToBcp) {
                odr.mBootClassPath.addDex(dexFiles[i]);
            }
        }
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String dexLoc = new String(odf.dex_file_location_data_);
            String opath = dexLoc;
            int spos = opath.indexOf(':');
            if (spos > 0) {
                // framework.jar:classes2.dex -> framework-classes2.dex
                opath = opath.substring(0, spos - 4) + "-" + opath.substring(spos + 1);
            }
            opath = opath.substring(opath.lastIndexOf('/') + 1);
            if ("base.apk".equals(opath)) {
                opath = MiscUtil.getFileNamePrefix(oat.mSrcFile.getName());
            }
            File outputFile = MiscUtil.changeExt(new File(outputFolder, opath), "dex");
            LLog.i("De-optimizing " + dexLoc);
            DexFile d = deOpt.rewriteDexFile(dexFiles[i]);

            if (outputFile.exists()) {
                File old = outputFile;
                outputFile = MiscUtil.appendTail(outputFile, "-deodex");
                LLog.i(old + " already existed, use name " + outputFile.getName());
            }
            DexPool.writeTo(outputFile.getAbsolutePath() , d);
            LLog.i("Output to " + outputFile);
        }
    }

    private static void extractBootToDexJar(Oat oat, File outputFolder,
            String bootClassPath, String noClassJarFolder) throws IOException {
        HashMap<String, ArrayList<Oat.DexFile>> dexFileGroup = new HashMap<>();
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String opath = new String(odf.dex_file_location_data_);
            int spos = opath.indexOf(':');
            if (spos > 0) {
                // .../framework.jar:classes2.dex
                opath = opath.substring(0, spos);
            }
            opath = opath.substring(opath.lastIndexOf('/') + 1);
            ArrayList<Oat.DexFile> dfiles = dexFileGroup.get(opath);
            if (dfiles == null) {
                dfiles = new ArrayList<>();
                dexFileGroup.put(opath, dfiles);
            }
            dfiles.add(oat.mDexFiles[i]);
        }

        final int BSIZE = 8192;
        final byte[] buf = new byte[BSIZE];
        final Opcodes opcodes = new Opcodes(Opcode.LOLLIPOP);
        LLog.i("Preparing bootclasspath from " + bootClassPath);
        final OatDexRewriterModule odr = new OatDexRewriterModule(bootClassPath, opcodes);
        final DexRewriter deOpt = odr.getRewriter();

        for (String jarName : dexFileGroup.keySet()) {
            File outputFile = MiscUtil.changeExt(new File(outputFolder, jarName), "jar");
            String classesIdx = "";
            int i = 1;
            int readSize;
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFile))) {
                for (Oat.DexFile dex : dexFileGroup.get(jarName)) {
                    jos.putNextEntry(new ZipEntry("classes" + classesIdx + ".dex"));
                    final int dexSize = dex.mHeader.file_size_;
                    ByteBuffer dexBytes = ByteBuffer.allocateDirect(dexSize);
                    dex.mReader.seek(dex.mDexPosition);
                    int remain = dexSize;
                    int read = BSIZE > dexSize ? dexSize : BSIZE;
                    while ((readSize = dex.mReader.readRaw(buf, 0, read)) != -1 && remain > 0) {
                        dexBytes.put(buf, 0, readSize);
                        remain -= readSize;
                        if (remain < BSIZE) {
                            read = remain;
                        }
                    }

                    LLog.i("De-optimizing " + jarName + (i > 1 ? (" part-" + classesIdx) : ""));
                    int length = dexBytes.position();
                    dexBytes.flip();
                    byte[] data = new byte[length];
                    dexBytes.get(data);
                    DexFile d = new DexBackedDexFile(opcodes, data);
                    d = deOpt.rewriteDexFile(d);

                    MemoryDataStore m = new MemoryDataStore(dexSize + 512);
                    DexPool.writeTo(m, d);

                    jos.write(m.mBuffer.mData, 0, m.mBuffer.mMaxDataPosition);
                    classesIdx = String.valueOf(++i);
                    jos.closeEntry();
                }

                // Copy files from original jar
                try (JarFile jarFile = new JarFile(new File(noClassJarFolder, jarName))) {
                    final Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        final JarEntry e = entries.nextElement();
                        String name = e.getName();
                        if (name.startsWith("classes") && name.endsWith(".dex")) {
                            continue;
                        }
                        jos.putNextEntry(new ZipEntry(name));
                        try (InputStream is = jarFile.getInputStream(e)) {
                            int bytesRead;
                            while ((bytesRead = is.read(buf)) != -1) {
                                jos.write(buf, 0, bytesRead);
                            }
                        }
                        jos.closeEntry();
                    }
                }
                LLog.i("Output " + outputFile);
            } catch (IOException e) {
                LLog.ex(e);
            }
        }
    }

    static class ByteData {
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

    static class MemoryDataStore implements DexDataStore {
        final ByteData mBuffer;

        public MemoryDataStore(int size) {
            mBuffer = new ByteData(size);
        }

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
                public void write(byte[] bytes, int off, int len) throws IOException {
                    mBuffer.position(mPos);
                    mPos += len;
                    mBuffer.put(bytes, off, len);
                }
            };
        }

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
                public int read(byte[] bytes, int off, int len) throws IOException {
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

        @Override
        public void close() throws IOException {
        }
    }

    public static class OatDexRewriter extends DexRewriter {
        private final OatDexRewriterModule mModule;
        public OatDexRewriter(OatDexRewriterModule module) {
            super(module);
            mModule = module;
        }

        @Override
        public DexFile rewriteDexFile(DexFile dexFile) {
            try {
                return org.jf.dexlib2.immutable.ImmutableDexFile.of(super.rewriteDexFile(dexFile));
                //return super.rewriteDexFile(dexFile);
            } catch (Exception e) {
                LLog.i("Fall into force mode, there may have unresolved instructions:\n" + e);
            }
            return forceOdex2dex(dexFile, mModule.mBootClassPath);
        }
    }

    // Covert optimized dex in oat to normal dex
    public static class OatDexRewriterModule extends RewriterModule {
        private final ClassPath mBootClassPath;
        private Method mCurrentMethod;
        
        public OatDexRewriterModule(String bootClassPath, Opcodes opcodes, String ext) {
            mBootClassPath = MiscUtil.getClassPath(bootClassPath, opcodes, ext);
        }

        public OatDexRewriterModule(String bootClassPath, Opcodes opcodes) {
            this(bootClassPath, opcodes, ".dex");
        }

        public DexRewriter getRewriter() {
            return new OatDexRewriter(this);
        }

        @Override
        public Rewriter<MethodImplementation> getMethodImplementationRewriter(Rewriters rewriters) {
            return new MethodImplementationRewriter(rewriters) {
                @Override
                public MethodImplementation rewrite(MethodImplementation methodImplementation) {
                    return new MethodImplementationRewriter.RewrittenMethodImplementation(
                            methodImplementation) {
                        @Override
                        public Iterable<? extends Instruction> getInstructions() {
                            MethodAnalyzer ma = new MethodAnalyzer(
                                    mBootClassPath, mCurrentMethod, null);
                            if (!ma.analysisInfo.isEmpty()) {
                                StringBuilder sb = new StringBuilder(256);
                                sb.append("Analysis info of ").append(mCurrentMethod.getDefiningClass())
                                        .append(" : ").append(mCurrentMethod.getName()).append(":\n");
                                for (String info : ma.analysisInfo) {
                                    sb.append(info).append("\n");
                                }
                                LLog.i(sb.toString());
                            }
                            AnalysisException ae = ma.getAnalysisException();
                            if (ae != null) {
                                LLog.e("Analysis error in class=" + mCurrentMethod.getDefiningClass()
                                    + " method=" + mCurrentMethod.getName());
                                LLog.ex(ae);
                            }
                            return ma.getInstructions();
                        }
                    };
                }
            };
        }

        @Override
        public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
            return new MethodRewriter(rewriters) {
                @Override
                public Method rewrite(Method method) {
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
