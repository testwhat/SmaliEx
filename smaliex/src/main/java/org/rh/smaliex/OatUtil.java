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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jf.baksmali.baksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.AnalysisException;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.MethodAnalyzer;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
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
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;

public class OatUtil {

    public static void smaliRaw(File inputFile) throws IOException {
        if (!inputFile.isFile()) {
            LLog.i(inputFile + " is not a file.");
        }
        String folderName = MiscUtil.getFilenamePrefix(inputFile.getName());
        String outputBaseFolder = MiscUtil.path(
                inputFile.getAbsoluteFile().getParent(), folderName);
        baksmaliOptions options = new baksmaliOptions();
        Opcodes opc = new Opcodes(org.jf.dexlib2.Opcode.LOLLIPOP);
        options.apiLevel = opc.apiLevel;
        options.allowOdex = true;
        options.jobs = 4;

        java.util.List<DexBackedDexFile> dexFiles = new ArrayList<>();
        java.util.List<String> outSubFolders = new ArrayList<>();
        if (Elf.isElf(inputFile)) {
            final byte[] buf = new byte[8192];
            try (Elf e = new Elf(inputFile)) {
                Oat oat = getOat(e);
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.DexFile df = oat.mDexFiles[i];
                    dexFiles.add(readDex(df, df.mHeader.file_size_, opc, buf));
                    String outFile = new String(oat.mOatDexFiles[i].dex_file_location_data_);
                    outFile = MiscUtil.getFilenamePrefix(getOuputNameForSubDex(outFile));
                    outSubFolders.add(MiscUtil.path(outputBaseFolder, outFile));
                }
            } catch (IOException ex) {
                throw handleIOE(ex);
            }
        } else {
            dexFiles = DexUtil.loadMultiDex(inputFile, opc);
            String subFolder = "classes";
            for (int i = 0; i < dexFiles.size(); i++) {
                outSubFolders.add(MiscUtil.path(outputBaseFolder, subFolder));
                subFolder = "classes" + (i + 2);
            }
        }
        if (outSubFolders.size() == 1) {
            outSubFolders.set(0, outputBaseFolder);
        }

        for (int i = 0; i < dexFiles.size(); i++) {
            options.outputDirectory = outSubFolders.get(i);
            org.jf.baksmali.baksmali.disassembleDexFile(dexFiles.get(i), options);
            LLog.i("Output to " + options.outputDirectory);
        }
        LLog.i("All done");
    }

    // A sample to de-optimize system folder of an extracted ROM
    public static void deOptimizeFiles(String systemFolder, String workingDir) throws IOException {
        File bootOat = new File(MiscUtil.path(systemFolder, "framework", "arm", "boot.oat"));
        if (!bootOat.exists()) {
            LLog.i(bootOat + " not found");
            return;
        }
        String outputJarFolder = MiscUtil.path(workingDir, "result-jar");
        bootOat2Jar(bootOat.getAbsolutePath(),
                MiscUtil.path(systemFolder, "framework"), outputJarFolder);
    }

    // Output de-optimized jar and also pack with other files in original jar
    public static void bootOat2Jar(String bootOat, String noClassJarFolder,
            String outputJarFolder) throws IOException {
        File odexFolder = prepareOdex(bootOat);
        String bootClassPathFolder = odexFolder.getAbsolutePath();
        extractDexFromBootOat(bootOat, outputJarFolder, bootClassPathFolder, noClassJarFolder);
    }

    public static void bootOat2Dex(String bootOat, String outFolder) throws IOException {
        File odexFolder = prepareOdex(bootOat);
        File outputJarFolder = outFolder != null ? new File(outFolder) :
                new File(new File(bootOat).getParent(), "dex");
        extractDexFromBootOat(bootOat, outputJarFolder.getAbsolutePath(),
                odexFolder.getAbsolutePath(), null);
    }

    private static File prepareOdex(String bootOat) throws IOException {
        File oatFile = new File(bootOat);
        File odexFolder = new File(oatFile.getParentFile(), "odex");
        odexFolder.mkdirs();
        extractOdexFromOat(oatFile, odexFolder);
        return odexFolder;
    }

    public static void oat2dex(String oatFile, String bootClassPath, String outFolder)
            throws IOException {
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            File outputFolder = outFolder != null ? new File(outFolder) :
                    new File(oatFile).getAbsoluteFile().getParentFile();
            outputFolder.mkdirs();
            convertToDex(oat, outputFolder, bootClassPath, true);
        }
    }

    public static Oat getOat(Elf e) throws IOException {
        DataReader r = e.getReader();
        // Currently the same as e.getSymbolTable("oatdata").getOffset(e)
        Elf.Elf_Shdr sec = e.getSection(Oat.SECTION_RODATA);
        if (sec != null) {
            r.seek(sec.getOffset());
            return new Oat(r, true);
        }
        throw new IOException("oat not found");
    }

    public static ArrayList<String> getBootJarNames(String bootOat) throws IOException {
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
            throw handleIOE(ex);
        }
        return names;
    }

    public static String getOuputNameForSubDex(String jarPathInOat) {
        int spos = jarPathInOat.indexOf(':');
        if (spos > 0) {
            // framework.jar:classes2.dex -> framework-classes2.dex
            jarPathInOat = jarPathInOat.substring(0, spos - 4)
                    + "-" + jarPathInOat.substring(spos + 1);
        }
        return jarPathInOat.substring(jarPathInOat.lastIndexOf('/') + 1);
    }

    // Get optimized dex from oat
    public static void extractOdexFromOat(File oatFile, File outputFolder) throws IOException {
        if (outputFolder == null) {
            outputFolder = new File(MiscUtil.workingDir());
            outputFolder.mkdirs();
        }
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            for (int i = 0; i < oat.mDexFiles.length; i++) {
                Oat.OatDexFile odf = oat.mOatDexFiles[i];
                Oat.DexFile df = oat.mDexFiles[i];
                String outFile = new String(odf.dex_file_location_data_);
                outFile = getOuputNameForSubDex(outFile);
                File out = MiscUtil.changeExt(new File(outputFolder, outFile), "dex");
                df.saveTo(out);
                LLog.i("Output raw dex: " + out.getAbsolutePath());
            }
        } catch (IOException ex) {
            throw handleIOE(ex);
        }
    }

    public static void extractDexFromBootOat(String oatFile, String outputFolder,
            String bootClassPath, String noClassJarFolder) throws IOException {
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            File outFolder = new File(outputFolder);
            if (!outFolder.exists()) {
                outFolder.mkdirs();
            }
            if (noClassJarFolder == null) {
                convertToDex(oat, outFolder, bootClassPath, false);
            } else {
                convertToDexJar(oat, outFolder, bootClassPath, noClassJarFolder, true);
            }
        } catch (IOException ex) {
            throw handleIOE(ex);
        }
    }

    static DexBackedDexFile readDex(Oat.DexFile od, int dexSize,
            Opcodes opcodes, byte[] buf) throws IOException {
        if (buf == null) {
            buf = new byte[8192];
        }
        ByteBuffer dexBytes = ByteBuffer.allocateDirect(dexSize);
        od.mReader.seek(od.mDexPosition);
        int remain = dexSize;
        int read = buf.length > dexSize ? dexSize : buf.length;
        int readSize;
        while ((readSize = od.mReader.readRaw(buf, 0, read)) != -1 && remain > 0) {
            dexBytes.put(buf, 0, readSize);
            remain -= readSize;
            if (remain < buf.length) {
                read = remain;
            }
        }
        int length = dexBytes.position();
        dexBytes.flip();
        byte[] data = new byte[length];
        dexBytes.get(data);
        return new DexBackedDexFile(opcodes, data);
    }

    public static DexFile[] getOdexFromOat(Oat oat, Opcodes opcodes) throws IOException {
        final byte[] buf = new byte[8192];
        if (opcodes == null) {
            opcodes = new Opcodes(oat.guessApiLevel());
        }
        DexFile[] dexFiles = new DexFile[oat.mOatDexFiles.length];
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.DexFile dex = oat.mDexFiles[i];
            final int dexSize = dex.mHeader.file_size_;
            dexFiles[i] = readDex(dex, dexSize, opcodes, buf);
        }
        return dexFiles;
    }

    private static void convertToDex(Oat oat, File outputFolder,
            String bootClassPath, boolean addSelfToBcp) throws IOException {
        final Opcodes opcodes = new Opcodes(oat.guessApiLevel());
        if (bootClassPath == null || !new File(bootClassPath).exists()) {
            throw new IOException("Invalid bootclasspath: " + bootClassPath);
        }
        final OatDexRewriterModule odr = new OatDexRewriterModule(bootClassPath, opcodes);
        final DexRewriter deOpt = odr.getRewriter();

        DexFile[] dexFiles = getOdexFromOat(oat, opcodes);
        if (addSelfToBcp) {
            for (DexFile d : dexFiles) {
                odr.mBootClassPath.addDex(d);
            }
        }
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String dexLoc = new String(odf.dex_file_location_data_);
            String opath = getOuputNameForSubDex(dexLoc);
            if ("base.apk".equals(opath)) {
                opath = MiscUtil.getFilenamePrefix(oat.mSrcFile.getName());
            }
            File outputFile = MiscUtil.changeExt(new File(outputFolder, opath), "dex");
            LLog.i("De-optimizing " + dexLoc);
            DexFile d = deOpt.rewriteDexFile(dexFiles[i]);
            if (!OatDexRewriter.isValid(d)) {
                LLog.i("convertToDex: skip " + dexLoc);
                continue;
            }

            if (outputFile.exists()) {
                outputFile.delete();
                //File old = outputFile;
                //outputFile = MiscUtil.appendTail(outputFile, "-deodex");
                //LLog.i(old + " already existed, use name " + outputFile.getName());
            }
            DexPool.writeTo(outputFile.getAbsolutePath(), d);
            LLog.i("Output to " + outputFile);
        }
    }

    public static void convertToDexJar(Oat oat, File outputFolder,
            String bootClassPath, String noClassJarFolder, boolean isBoot) throws IOException {
        final Opcodes opcodes = new Opcodes(oat.guessApiLevel());
        LLog.i("Preparing bootclasspath from " + bootClassPath);
        final OatDexRewriterModule odr = new OatDexRewriterModule(bootClassPath, opcodes);
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
            if (!isBoot) {
                Oat.DexFile dex = oat.mDexFiles[i];
                odr.mBootClassPath.addDex(
                        readDex(dex, dex.mHeader.file_size_, opcodes, null));
            }
        }

        final int BSIZE = 8192;
        final byte[] buf = new byte[BSIZE];
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
                    if (!OatDexRewriter.isValid(d)) {
                        LLog.i("convertToDexJar: skip " + jarName);
                        continue;
                    }

                    DexUtil.MemoryDataStore m = new DexUtil.MemoryDataStore(dexSize + 512);
                    DexPool.writeTo(m, d);
                    m.writeTo(jos);
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
            } catch (IOException ex) {
                throw handleIOE(ex);
            }
        }
    }

    static IOException handleIOE(IOException ex) {
        LLog.ex(ex);
        return ex;
    }

    public static class OatDexRewriter extends DexRewriter {
        public OatDexRewriter(OatDexRewriterModule module) {
            super(module);
        }

        @Override
        public DexFile rewriteDexFile(DexFile dexFile) {
            try {
                return org.jf.dexlib2.immutable.ImmutableDexFile.of(super.rewriteDexFile(dexFile));
            } catch (Exception e) {
                LLog.i("Failed to re-construct dex " + e);
                //LLog.ex(e);
            }
            return new FailedDexFile();
        }

        public static boolean isValid(DexFile dexFile) {
            return !(dexFile instanceof FailedDexFile);
        }

        static final class FailedDexFile implements DexFile {
            @Override
            public java.util.Set<? extends org.jf.dexlib2.iface.ClassDef> getClasses() {
                return new java.util.HashSet<>(0);
            }
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
                                        .append("->").append(mCurrentMethod.getName()).append(":\n");
                                for (String info : ma.analysisInfo) {
                                    sb.append(info).append("\n");
                                }
                                LLog.i(sb.toString());
                            }
                            AnalysisException ae = ma.getAnalysisException();
                            if (ae != null) {
                                LLog.e("Analysis error in class=" + mCurrentMethod.getDefiningClass()
                                    + " method=" + mCurrentMethod.getName() + "\n" + ae.getContext());
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
