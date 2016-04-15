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
import java.util.List;
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

    public static List<DexBackedDexFile> getDexFiles(
            File file, int apiLevel, List<String> outputNames) {
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        if (MiscUtil.isElf(file)) {
            try (Elf e = new Elf(file)) {
                Oat oat = getOat(e);
                Opcodes opc = new Opcodes(apiLevel > 0 ? apiLevel : oat.guessApiLevel());
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.DexFile df = oat.mDexFiles[i];
                    dexFiles.add(readDex(df, df.mHeader.file_size_, opc));
                    if (outputNames != null) {
                        String dexName = new String(oat.mOatDexFiles[i].dex_file_location_data_);
                        dexName = getOutputNameForSubDex(dexName);
                        outputNames.add(MiscUtil.getFilenamePrefix(dexName));
                    }
                }
            } catch (IOException ex) {
                LLog.ex(ex);
            }
        } else {
            Opcodes opc = new Opcodes(apiLevel > 0 ? apiLevel : DexUtil.API_LEVEL);
            dexFiles = DexUtil.loadMultiDex(file, opc);
            if (outputNames != null) {
                String dexName = "classes";
                for (int i = 0; i < dexFiles.size(); i++) {
                    outputNames.add(dexName);
                    dexName = "classes" + (i + 2);
                }
            }
        }
        return dexFiles;
    }

    public static void smaliRaw(File inputFile, int apiLevel) {
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

        List<String> outSubFolders = new ArrayList<>();
        List<DexBackedDexFile> dexFiles = getDexFiles(inputFile, apiLevel, outSubFolders);
        if (outSubFolders.size() == 1) {
            outSubFolders.set(0, outputBaseFolder);
        } else {
            for (int i = 0; i < outSubFolders.size(); i++) {
                outSubFolders.set(i, MiscUtil.path(outputBaseFolder, outSubFolders.get(i)));
            }
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
        convertDexFromBootOat(bootOat, outputJarFolder, bootClassPathFolder,
                noClassJarFolder, true);
    }

    public static void bootOat2Dex(String bootOat, String outFolder) throws IOException {
        File odexFolder = prepareOdex(bootOat);
        String folderName = odexFolder.getName().replace("odex", "dex");
        File outputDexFolder = outFolder != null ? new File(outFolder) :
                new File(new File(bootOat).getParent(), folderName);
        convertDexFromBootOat(bootOat, outputDexFolder.getAbsolutePath(),
                odexFolder.getAbsolutePath(), null, true);
    }

    private static File prepareOdex(String bootOat) throws IOException {
        File oatFile = new File(bootOat);
        String folderName = (oatFile.isDirectory() ? oatFile.getName() + "-" : "") + "odex";
        File odexFolder = new File(oatFile.getParentFile(), folderName);
        MiscUtil.mkdirs(odexFolder);
        extractOdexFromOat(oatFile, odexFolder);
        return odexFolder;
    }

    public static void oat2dex(String inputPath, String bootClassPath, String outFolder)
            throws IOException {
        File oatPath = new File(inputPath);
        File outputFolder = outFolder != null ? new File(outFolder) :
                oatPath.getAbsoluteFile().getParentFile();
        convertDexFromBootOat(inputPath, outputFolder.getAbsolutePath(),
                bootClassPath, null, false);
    }

    public static Oat getOat(Elf e) throws IOException {
        DataReader r = e.getReader();
        // Currently the same as e.getSymbolTable("oatdata").getOffset(e)
        Elf.Elf_Shdr sec = e.getSection(Oat.SECTION_RODATA);
        if (sec != null) {
            r.seek(sec.getOffset());
            return new Oat(r);
        }
        throw new IOException("oat not found");
    }

    public static ArrayList<String> getBootJarNames(String bootPath, boolean fullPath) {
        ArrayList<String> names = new ArrayList<>();
        for (File oatFile : getOatFile(new File(bootPath))) {
            try (Elf e = new Elf(oatFile)) {
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
        }
        return names;
    }

    public static String getOutputNameForSubDex(String jarPathInOat) {
        int colonPos = jarPathInOat.indexOf(':');
        if (colonPos > 0) {
            // framework.jar:classes2.dex -> framework-classes2.dex
            jarPathInOat = jarPathInOat.substring(0, colonPos - 4)
                    + "-" + jarPathInOat.substring(colonPos + 1);
        }
        return jarPathInOat.substring(jarPathInOat.lastIndexOf('/') + 1);
    }

    static File[] getOatFile(File oatPath) {
        return oatPath.isDirectory() ? MiscUtil.getFiles(oatPath.getAbsolutePath(), ".oat;.odex")
                : new File[] { oatPath };
    }

    public static void extractOdexFromOat(File oatPath, File outputFolder) throws IOException {
        if (outputFolder == null) {
            String folderName = (oatPath.isDirectory() ? oatPath.getName() + "-" : "") + "odex";
            outputFolder = new File(oatPath.getParentFile(), folderName);
        }
        MiscUtil.mkdirs(outputFolder);
        IOException ioe = null;
        for (File oatFile : getOatFile(oatPath)) {
            if (!MiscUtil.isElf(oatFile)) {
                LLog.i("Skip not ELF: " + oatFile);
                continue;
            }
            try (Elf e = new Elf(oatFile)) {
                Oat oat = getOat(e);
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.OatDexFile odf = oat.mOatDexFiles[i];
                    Oat.DexFile df = oat.mDexFiles[i];
                    String outFile = new String(odf.dex_file_location_data_);
                    outFile = getOutputNameForSubDex(outFile);
                    File out = MiscUtil.changeExt(new File(outputFolder, outFile), "dex");
                    df.saveTo(out);
                    LLog.i("Output raw dex: " + out.getAbsolutePath());
                }
            } catch (IOException ex) {
                if (ioe == null) {
                    ioe = new IOException("Error at " + oatFile);
                }
                ioe.addSuppressed(ex);
            }
        }
        if (ioe != null) {
            throw handleIOE(ioe);
        }
    }

    public static void convertDexFromBootOat(String oatPath, String outputFolder,
            String bootClassPath, String noClassJarFolder, boolean isBoot) throws IOException {
        File outFolder = new File(outputFolder);
        MiscUtil.mkdirs(outFolder);

        IOException ioe = null;
        for (File oatFile : getOatFile(new File(oatPath))) {
            try (Elf e = new Elf(oatFile)) {
                Oat oat = getOat(e);
                if (noClassJarFolder == null) {
                    convertToDex(oat, outFolder, bootClassPath, isBoot);
                } else {
                    convertToDexJar(oat, outFolder, bootClassPath, noClassJarFolder, isBoot);
                }
            } catch (IOException ex) {
                if (ioe == null) {
                    ioe = new IOException("Error at " + oatFile);
                }
                ioe.addSuppressed(ex);
            }
        }
        if (ioe != null) {
            throw handleIOE(ioe);
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
            opcodes = DexUtil.getOpcodes(oat.guessApiLevel());
        }
        DexFile[] dexFiles = new DexFile[oat.mOatDexFiles.length];
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.DexFile dex = oat.mDexFiles[i];
            final int dexSize = dex.mHeader.file_size_;
            DexBackedDexFile dexFile = readDex(dex, dexSize, opcodes);
            if (!DexUtil.verifyStringOffset(dexFile)) {
                LLog.i("Bad string offset.");
                throw new IOException("The dex does not have formal format in: " + oat.mSrcFile);
            }
            dexFiles[i] = dexFile;
        }
        return dexFiles;
    }

    private static void convertToDex(Oat oat, File outputFolder,
            String bootClassPath, boolean isBoot) throws IOException {
        final Opcodes opcodes = DexUtil.getOpcodes(oat.guessApiLevel());
        if (bootClassPath == null || !new File(bootClassPath).exists()) {
            throw new IOException("Invalid bootclasspath: " + bootClassPath);
        }
        final ODexRewriter deOpt = DexUtil.getODexRewriter(bootClassPath, opcodes);
        if (LLog.VERBOSE) {
            deOpt.setFailInfoLocation(outputFolder.getAbsolutePath());
        }

        LLog.i("Art version=" + oat.getArtVersion() + " (" + oat.mSrcFile + ")");
        DexFile[] dexFiles = getOdexFromOat(oat, opcodes);
        if (!isBoot) {
            for (DexFile d : dexFiles) {
                deOpt.addDexToClassPath(d);
            }
        }
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String dexLoc = new String(odf.dex_file_location_data_);
            String outputName = getOutputNameForSubDex(dexLoc);
            if ("base.apk".equals(outputName)) {
                outputName = MiscUtil.getFilenamePrefix(oat.mSrcFile.getName());
            }
            File outputFile = MiscUtil.changeExt(new File(outputFolder, outputName), "dex");
            LLog.i("De-optimizing " + dexLoc);
            DexFile d = deOpt.rewriteDexFile(dexFiles[i]);
            if (!ODexRewriter.isValid(d)) {
                LLog.i("convertToDex: skip " + dexLoc);
                continue;
            }

            if (outputFile.exists()) {
                MiscUtil.delete(outputFile);
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
        final Opcodes opcodes = DexUtil.getOpcodes(oat.guessApiLevel());
        LLog.v("Use bootclasspath " + bootClassPath);
        final ODexRewriter deOpt = DexUtil.getODexRewriter(bootClassPath, opcodes);
        HashMap<String, ArrayList<Oat.DexFile>> dexFileGroup = new HashMap<>();
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String dexPath = new String(odf.dex_file_location_data_);
            int colonPos = dexPath.indexOf(':');
            if (colonPos > 0) {
                // .../framework.jar:classes2.dex
                dexPath = dexPath.substring(0, colonPos);
            }
            dexPath = dexPath.substring(dexPath.lastIndexOf('/') + 1);
            ArrayList<Oat.DexFile> dexFiles = dexFileGroup.get(dexPath);
            if (dexFiles == null) {
                dexFiles = new ArrayList<>();
                dexFileGroup.put(dexPath, dexFiles);
            }
            dexFiles.add(oat.mDexFiles[i]);
            if (!isBoot) {
                Oat.DexFile dex = oat.mDexFiles[i];
                deOpt.addDexToClassPath(readDex(dex, dex.mHeader.file_size_, opcodes));
            }
        }
        if (LLog.VERBOSE) {
            deOpt.setFailInfoLocation(outputFolder.getAbsolutePath());
        }

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
                            jos.write(MiscUtil.readBytes(is));
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
