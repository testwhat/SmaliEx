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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jf.baksmali.baksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.DexUtil.ODexRewriter;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;

public class OatUtil {

    public static void smaliRaw(File inputFile, int apiLevel) throws IOException {
        if (!inputFile.isFile()) {
            LLog.i(inputFile + " is not a file.");
        }
        String folderName = MiscUtil.getFilenamePrefix(inputFile.getName());
        String outputBaseFolder = MiscUtil.path(
                inputFile.getAbsoluteFile().getParent(), folderName);
        baksmaliOptions options = new baksmaliOptions();
        Opcodes opc = new Opcodes(apiLevel);
        options.apiLevel = opc.apiLevel;
        options.allowOdex = true;
        options.jobs = 4;

        java.util.List<DexBackedDexFile> dexFiles = new ArrayList<>();
        java.util.List<String> outSubFolders = new ArrayList<>();
        if (Elf.isElf(inputFile)) {
            try (Elf e = new Elf(inputFile)) {
                Oat oat = getOat(e);
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.DexFile df = oat.mDexFiles[i];
                    dexFiles.add(readDex(df, df.mHeader.file_size_, opc));
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
        MiscUtil.mkdirs(odexFolder);
        extractOdexFromOat(oatFile, odexFolder);
        return odexFolder;
    }

    public static void oat2dex(String oatFile, String bootClassPath, String outFolder)
            throws IOException {
        try (Elf e = new Elf(oatFile)) {
            Oat oat = getOat(e);
            File outputFolder = outFolder != null ? new File(outFolder) :
                    new File(oatFile).getAbsoluteFile().getParentFile();
            MiscUtil.mkdirs(outputFolder);
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

    public static ArrayList<String> getBootJarNames(String bootOat, boolean fullPath) {
        ArrayList<String> names = new ArrayList<>();
        try (Elf e = new Elf(bootOat)) {
            Oat oat = getOat(e);
            for (Oat.OatDexFile df : oat.mOatDexFiles) {
                String s = new String(df.dex_file_location_data_);
                if (s.contains(":")) {
                    continue;
                }
                names.add(fullPath ? s : s.substring(s.lastIndexOf('/') + 1));
            }
        } catch (IOException ex) {
            LLog.ex(ex);
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
            MiscUtil.mkdirs(outputFolder);
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
                MiscUtil.mkdirs(outFolder);
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

    static DexBackedDexFile readDex(Oat.DexFile od, int dexSize, Opcodes opcodes)
            throws IOException {
        byte[] dexBytes = new byte[dexSize];
        int remain = dexSize;
        int read = 0;
        int readSize;
        od.mReader.seek(od.mDexPosition);
        while (remain > 0 && (readSize = od.mReader.readRaw(dexBytes, read, remain)) != -1) {
            remain -= readSize;
            read += readSize;
        }
        return new DexBackedDexFile(opcodes, dexBytes);
    }

    public static DexFile[] getOdexFromOat(Oat oat, Opcodes opcodes) throws IOException {
        if (opcodes == null) {
            opcodes = new Opcodes(oat.guessApiLevel());
        }
        DexFile[] dexFiles = new DexFile[oat.mOatDexFiles.length];
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.DexFile dex = oat.mDexFiles[i];
            final int dexSize = dex.mHeader.file_size_;
            dexFiles[i] = readDex(dex, dexSize, opcodes);
        }
        return dexFiles;
    }

    private static void convertToDex(Oat oat, File outputFolder,
            String bootClassPath, boolean addSelfToBcp) throws IOException {
        final Opcodes opcodes = new Opcodes(oat.guessApiLevel());
        if (bootClassPath == null || !new File(bootClassPath).exists()) {
            throw new IOException("Invalid bootclasspath: " + bootClassPath);
        }
        final ODexRewriter deOpt = DexUtil.getODexRewriter(bootClassPath, opcodes);
        if (LLog.VERBOSE) {
            deOpt.setFailInfoLocation(outputFolder.getAbsolutePath());
        }

        DexFile[] dexFiles = getOdexFromOat(oat, opcodes);
        if (addSelfToBcp) {
            for (DexFile d : dexFiles) {
                deOpt.addDexToClassPath(d);
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
            if (!ODexRewriter.isValid(d)) {
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
        LLog.v("Use bootclasspath " + bootClassPath);
        final ODexRewriter deOpt = DexUtil.getODexRewriter(bootClassPath, opcodes);
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
                deOpt.addDexToClassPath(readDex(dex, dex.mHeader.file_size_, opcodes));
            }
        }

        final byte[] buf = new byte[8192];
        for (String jarName : dexFileGroup.keySet()) {
            File outputFile = MiscUtil.changeExt(new File(outputFolder, jarName), "jar");
            String classesIdx = "";
            int i = 1;
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFile))) {
                for (Oat.DexFile dex : dexFileGroup.get(jarName)) {
                    jos.putNextEntry(new ZipEntry("classes" + classesIdx + ".dex"));
                    final int dexSize = dex.mHeader.file_size_;
                    LLog.i("De-optimizing " + jarName + (i > 1 ? (" part-" + classesIdx) : ""));
                    DexFile d = readDex(dex, dexSize, opcodes);
                    d = deOpt.rewriteDexFile(d);
                    if (!ODexRewriter.isValid(d)) {
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
        deOpt.recycle();
    }

    static IOException handleIOE(IOException ex) {
        LLog.ex(ex);
        return ex;
    }
}
