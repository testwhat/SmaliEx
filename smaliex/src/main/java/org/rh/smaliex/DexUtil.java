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

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.MultiDex;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.analysis.ClassPath;
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
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.List;
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
        Opcodes opcodes = MiscUtil.getCache(opCodesCache, apiLevel);
        if (opcodes == null) {
            opcodes = Opcodes.forApi(apiLevel);
            MiscUtil.putCache(opCodesCache, apiLevel, opcodes);
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
        if (NO_NEED_BOOT_CLASSPATH.equals(bootClassPath)) {
            if (MiscUtil.isVdex(input)) {
                final Opcodes opcodes = getOpcodes(Math.max(Oat.Version.O_80.api, apiLevel));
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
            if (MiscUtil.checkFourBytes(input, 4, 0x30333700) && apiLevel < API_N) {
                LLog.i("The input has dex version 037, suggest to use api level " + API_N);
            }
            final Opcodes opcodes = getOpcodes(apiLevel);
            final DexFile odexFile = loadSingleDex(input, opcodes);
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

}
