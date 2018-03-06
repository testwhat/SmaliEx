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

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.deopt.OdexRewriter;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Dex;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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

public class OatUtil {
    public static boolean SKIP_EXISTS;

    public static Opcodes getOpcodes(Oat oat) {
        return DexUtil.getOpcodes(VersionMap.mapArtVersionToApi(oat.getArtVersion()));
    }

    /**
     * Output jar with de-optimized dex and also pack with other files from original jar
     *
     * @param bootOat Path to boot*.oat files
     * @param noClassJarPath Path to jars without dex
     * @param outputJarPath Path to output the repacked jar
     * @throws IOException Failed to read oat,dex file
     */
    public static void bootOat2Jar(@Nonnull String bootOat,
                                   @Nullable String noClassJarPath,
                                   @Nullable String outputJarPath) throws IOException {
        convertDexFromBootOat(bootOat, outputJarPath, bootOat, noClassJarPath, true);
    }

    /**
     * Convert boot*.oat to dex.
     *
     * @param bootOat Path to boot*.oat file(s)
     * @param outPath Output directory for odex files. If null, it default puts at the same place of input.
     * @throws IOException Failed to read oat or write odex, dex
     */
    public static void bootOat2Dex(@Nonnull String bootOat,
                                   @Nullable String outPath) throws IOException {
        convertDexFromBootOat(bootOat, outPath, bootOat, null, true);
    }

    public static void oat2dex(@Nonnull String oatPath,
                               @Nonnull String bootClassPath,
                               @Nullable String outPath) throws IOException {
        convertDexFromBootOat(oatPath, outPath, bootClassPath, null, false);
    }

    @Nonnull
    public static Oat getOat(@Nonnull Elf e) throws IOException {
        final DataReader r = e.getReader();
        // Currently the same as e.getSymbolTable("oatdata").getOffset(e)
        final Elf.Elf_Shdr sec = e.getSection(Oat.SECTION_RODATA);
        if (sec != null) {
            r.seek(sec.getOffset());
            return new Oat(r);
        }
        throw new IOException("oat not found");
    }

    @Nonnull
    public static ArrayList<String> getBootJarNames(@Nonnull String bootPath, boolean fullPath) {
        final ArrayList<String> names = new ArrayList<>();
        for (File oatFile : getOatFile(new File(bootPath))) {
            try (Elf e = new Elf(oatFile)) {
                final Oat oat = getOat(e);
                for (Oat.OatDexFile df : oat.oatDexFiles) {
                    final String s = new String(df.dex_file_location_data_);
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

    @Nonnull
    public static String getOutputNameForSubDex(@Nonnull String jarPathInOat) {
        int colonPos = jarPathInOat.indexOf(':');
        if (colonPos > 0) {
            // framework.jar:classes2.dex -> framework-classes2.dex
            jarPathInOat = jarPathInOat.substring(0, colonPos - 4)
                    + "-" + jarPathInOat.substring(colonPos + 1);
        }
        return jarPathInOat.substring(jarPathInOat.lastIndexOf('/') + 1);
    }

    @Nonnull
    static File[] getOatFile(@Nonnull File oatPath) {
        return MiscUtil.getAsFiles(oatPath, ".oat;.odex");
    }

    /**
     * Extract odex files from oat file.
     *
     * NOTE: The odex files created in the output directory will have the ".dex" extension if
     * the input oat also used ".odex" extension.
     *
     * @param oatPath Path to boot*.oat file(s)
     * @param outputDir Output directory. If null, it will puts at the same place of input oat.
     * @return The directory which contains odex files
     * @throws IOException Failed to read oat or write odex
     */
    @Nonnull
    public static File extractOdexFromOat(@Nonnull File oatPath,
                                          @Nullable File outputDir) throws IOException {
        outputDir = MiscUtil.ensureOutputDir(oatPath, outputDir, "-odex");
        IOException ioe = null;
        for (File oatFile : getOatFile(oatPath)) {
            if (!MiscUtil.isElf(oatFile)) {
                LLog.i("Skip not ELF: " + oatFile);
                continue;
            }
            try (Elf e = new Elf(oatFile)) {
                final Oat oat = getOat(e);
                for (int i = 0; i < oat.dexFiles.length; i++) {
                    final Oat.OatDexFile odf = oat.oatDexFiles[i];
                    final Dex df = oat.dexFiles[i];
                    final String outFile = getOutputNameForSubDex(
                            new String(odf.dex_file_location_data_));
                    final File out = MiscUtil.changeExt(new File(outputDir, outFile),
                            oatFile.getName().endsWith(".odex") ? "dex" : "odex");
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
        return outputDir;
    }

    public static void convertDexFromBootOat(@Nonnull String oatPath,
                                             @Nullable String outputPath,
                                             @Nonnull String bootClassPath,
                                             @Nullable String noClassJarFolder,
                                             boolean isBoot) throws IOException {
        final boolean dexOnly = noClassJarFolder == null;
        final File outDir = MiscUtil.ensureOutputDir(oatPath, outputPath, dexOnly ? "-dex" : "-jar");

        LLog.v("Use bootclasspath " + bootClassPath);
        IOException ioe = null;
        for (File oatFile : getOatFile(new File(oatPath))) {
            try (Elf e = new Elf(oatFile)) {
                Oat oat = getOat(e);
                if (dexOnly) {
                    convertToDex(oat, outDir, bootClassPath, isBoot);
                } else {
                    convertToDexJar(oat, outDir, bootClassPath, noClassJarFolder, isBoot);
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

    /**
     * Get list of DexFile objects from an Oat file.
     *
     * @param oat Oat file
     * @param opcodes Opcodes for the API level (if null, guessed from oat file)
     * @return List of DexFile objects
     * @throws IOException Wrong file format
     */
    @Nonnull
    public static DexFile[] getOdexFromOat(@Nonnull Oat oat,
                                           @Nullable Opcodes opcodes) throws IOException {
        if (opcodes == null) {
            opcodes = getOpcodes(oat);
        }
        final DexFile[] dexFiles = new DexFile[oat.oatDexFiles.length];
        for (int i = 0; i < oat.oatDexFiles.length; i++) {
            final Dex dex = oat.dexFiles[i];
            final DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dex.getBytes());
            if (!DexUtil.verifyStringOffset(dexFile)) {
                LLog.i("Bad string offset.");
                throw new IOException("The dex does not have formal format in: " + oat.srcFile);
            }
            dexFiles[i] = dexFile;
        }
        return dexFiles;
    }

    /**
     * Convert oat file to dex files.
     *
     * @param oat Oat file
     * @param outputDir Output directory for dex files
     * @param bootClassPath Boot class path (path to framework odex files)
     * @param isBoot Whether the input oat to add dex files to boot class path field
     * @throws IOException Failed to read input or write output
     */
    public static void convertToDex(@Nonnull Oat oat, @Nonnull File outputDir,
                                     String bootClassPath, boolean isBoot) throws IOException {
        final Opcodes opcodes = getOpcodes(oat);
        if (bootClassPath == null || !new File(bootClassPath).exists()) {
            throw new IOException("Invalid bootclasspath: " + bootClassPath);
        }
        final OdexRewriter deOpt = OdexRewriter.get(
                bootClassPath, opcodes, outputDir.getAbsolutePath());

        LLog.i("Art version=" + oat.getArtVersion() + " (" + oat.srcFile + ")");
        final DexFile[] dexFiles = getOdexFromOat(oat, opcodes);
        if (!isBoot) {
            for (DexFile d : dexFiles) {
                deOpt.addDexToClassPath(d);
            }
        }
        for (int i = 0; i < oat.oatDexFiles.length; i++) {
            final Oat.OatDexFile odf = oat.oatDexFiles[i];
            final String dexLoc = new String(odf.dex_file_location_data_);
            String outputName = getOutputNameForSubDex(dexLoc);
            if ("base.apk".equals(outputName)) {
                outputName = MiscUtil.getFilenameNoExt(oat.srcFile.getName());
            }
            File outputFile = MiscUtil.changeExt(new File(outputDir, outputName), "dex");
            if (SKIP_EXISTS && outputFile.exists()) continue;

            LLog.i("De-optimizing " + dexLoc);
            final DexFile d = deOpt.rewriteDexFile(dexFiles[i]);
            if (OdexRewriter.isInvalid(d)) {
                LLog.i("convertToDex: skip " + dexLoc);
                continue;
            }

            OdexUtil.outputDex(d, outputFile, true);
        }
    }

    public static void convertToDexJar(@Nonnull Oat oat,
                                       @Nonnull File outputFolder,
                                       @Nonnull String bootClassPath,
                                       @Nonnull String noClassJarFolder,
                                       boolean isBoot) throws IOException {
        final Opcodes opcodes = getOpcodes(oat);
        final OdexRewriter deOpt = OdexRewriter.get(
                bootClassPath, opcodes, outputFolder.getAbsolutePath());
        final HashMap<String, ArrayList<Dex>> dexFileGroup = new HashMap<>();
        for (int i = 0; i < oat.oatDexFiles.length; i++) {
            final Oat.OatDexFile odf = oat.oatDexFiles[i];
            String dexPath = new String(odf.dex_file_location_data_);
            int colonPos = dexPath.indexOf(':');
            if (colonPos > 0) {
                // .../framework.jar:classes2.dex
                dexPath = dexPath.substring(0, colonPos);
            }
            dexPath = dexPath.substring(dexPath.lastIndexOf('/') + 1);
            final ArrayList<Dex> dexFiles = dexFileGroup.computeIfAbsent(
                    dexPath, k -> new ArrayList<>());
            dexFiles.add(oat.dexFiles[i]);
            if (!isBoot) {
                deOpt.addDexToClassPath(new DexBackedDexFile(opcodes, oat.dexFiles[i].getBytes()));
            }
        }

        for (String jarName : dexFileGroup.keySet()) {
            final File outputJar = MiscUtil.changeExt(new File(outputFolder, jarName), "jar");
            if (SKIP_EXISTS && outputJar.exists()) continue;

            String classesIdx = "";
            int i = 1;
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar))) {
                for (Dex dex : dexFileGroup.get(jarName)) {
                    jos.putNextEntry(new ZipEntry("classes" + classesIdx + ".dex"));
                    LLog.i("De-optimizing " + jarName + (i > 1 ? (" part-" + classesIdx) : ""));
                    final DexFile d = deOpt.rewriteDexFile(
                            new DexBackedDexFile(opcodes, dex.getBytes()));
                    if (OdexRewriter.isInvalid(d)) {
                        LLog.i("convertToDexJar: skip " + jarName);
                        continue;
                    }

                    final MemoryDataStore m = new MemoryDataStore(dex.header.file_size_ + 512);
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
                        final String name = e.getName();
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
                LLog.i("Output " + outputJar);
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
