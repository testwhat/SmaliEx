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

import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.MultiDex;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.deopt.OdexRewriter;
import org.rh.smaliex.deopt.VdexDecompiler;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Oat;
import org.rh.smaliex.reader.Vdex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OdexUtil {
    private static final String NO_NEED_BOOT_CLASSPATH = "NO_NEED_BOOT_CLASSPATH";

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
        if (NO_NEED_BOOT_CLASSPATH.equals(bootClassPath)) {
            if (MiscUtil.isVdex(input)) {
                final Opcodes opcodes = DexUtil.getOpcodes(Math.max(Oat.Version.O_80.api, apiLevel));
                try (DataReader r = new DataReader(input)) {
                    final Vdex vdex = new Vdex(r);
                    LLog.i("Unquickening " + input + " ver=" + vdex.header.version);
                    final DexFile[] dexFiles = VdexDecompiler.unquicken(vdex, opcodes);
                    for (int i = 0; i < dexFiles.length; i++) {
                        final File outputFile = MiscUtil.changeExt(new File(outputFolder,
                                MultiDex.getDexFileName(input.getName(), i)), "dex");
                        outputDex(dexFiles[i], outputFile, false);
                    }
                }
            } else throw new IOException("Not a vdex file: " + input);
        } else {
            if (MiscUtil.checkFourBytes(input, 4, 0x30333700) && apiLevel < DexUtil.API_N) {
                LLog.i("The input has dex version 037, suggest to use api level " + DexUtil.API_N);
            }
            final Opcodes opcodes = DexUtil.getOpcodes(apiLevel);
            final DexFile odexFile = DexUtil.loadSingleDex(input, opcodes);
            final OdexRewriter rewriter = OdexRewriter.get(
                    bootClassPath, opcodes, outputFolder.getAbsolutePath());
            final File outputFile = MiscUtil.changeExt(
                    new File(outputFolder, input.getName()), "dex");
            outputDex(rewriter.rewriteDexFile(odexFile), outputFile, false);
        }
    }

    static void outputDex(@Nonnull DexFile dex, @Nonnull File output,
                          boolean replace) throws IOException {
        if (output.exists()) {
            if (replace) {
                MiscUtil.delete(output);
            } else {
                final File old = output;
                output = MiscUtil.appendTail(output, "-deodex");
                LLog.i(old + " already existed, use name " + output.getName());
            }
        }
        DexPool.writeTo(output.getAbsolutePath(), dex);
        LLog.i("Output to " + output);
    }

    /**
     * Extract smali from odex or oat file.
     *
     * If the input file has multiple embedded dex files, the smali files will be placed into subdirectories
     * within the output directory. Otherwise, the smali files are placed directly into the output directory.
     *
     * If there are multiple dex files in the input file, the subdirectories will be named as follows:
     * 1. If the input file is an oat file, then the subdirectory name is based on the dex_file_location_data_ field
     * 2. If the input file is not an oat file, then the subdirectory name is "classes", "classes2", etc.
     *
     * @param inputFile Input odex or oat file
     * @param outputPath Path to output directory. If null, the output will put at the same place of input
     */
    public static void smaliRaw(@Nonnull File inputFile,
                                @Nullable String outputPath, int apiLevel) {
        if (!inputFile.isFile()) {
            LLog.i(inputFile + " is not a file. ");
        }
        final String folderName = MiscUtil.getFilenameNoExt(inputFile.getName());
        final String outputBaseDir = outputPath != null ? outputPath
                : MiscUtil.path(inputFile.getAbsoluteFile().getParent(), folderName);
        final BaksmaliOptions options = new BaksmaliOptions();
        final Opcodes opc = DexUtil.getOpcodes(apiLevel);
        options.apiLevel = opc.api;
        options.allowOdex = true;

        final List<String> outSubDirs = new ArrayList<>();
        final List<DexBackedDexFile> dexFiles = DexUtil.getDexFiles(inputFile, apiLevel, outSubDirs);
        if (outSubDirs.size() == 1) {
            outSubDirs.set(0, outputBaseDir);
        } else {
            for (int i = 0; i < outSubDirs.size(); i++) {
                outSubDirs.set(i, MiscUtil.path(outputBaseDir, outSubDirs.get(i)));
            }
        }

        for (int i = 0; i < dexFiles.size(); i++) {
            final File outputDir = new File(outSubDirs.get(i));
            org.jf.baksmali.Baksmali.disassembleDexFile(dexFiles.get(i), outputDir, 4, options);
            LLog.i("Output to " + outputDir);
        }
        LLog.i("All done");
    }

    @Nonnull
    public static File extractOdex(@Nonnull File inputPath,
                                   @Nullable File outputDir) throws IOException {
        outputDir = MiscUtil.ensureOutputDir(inputPath, outputDir, "-odex");
        boolean oatFound = false;
        if (inputPath.isDirectory()) {
            final File[] files = inputPath.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (MiscUtil.isOat(f)) {
                        oatFound = true;
                        break;
                    }
                }
            }
        }
        if (oatFound || (inputPath.isFile() && MiscUtil.isOat(inputPath))) {
            return OatUtil.extractOdexFromOat(inputPath, outputDir);
        }
        for (File f : MiscUtil.getAsFiles(inputPath, ".vdex")) {
            try (DataReader r = new DataReader(f)) {
                final Vdex vdex = new Vdex(r);
                for (int i = 0; i < vdex.dexFiles.length; i++) {
                    final File outputFile = MiscUtil.changeExt(new File(outputDir,
                            MultiDex.getDexFileName(f.getName(), i)), "dex");
                    vdex.dexFiles[i].saveTo(outputFile);
                }
            }
        }
        return outputDir;
    }
}
