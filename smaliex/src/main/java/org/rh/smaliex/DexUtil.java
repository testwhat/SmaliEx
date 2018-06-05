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
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.raw.HeaderItem;
import org.jf.dexlib2.dexbacked.raw.MapItem;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Dex;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;
import org.rh.smaliex.reader.Vdex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class DexUtil {
    private static Opcodes DEFAULT_OPCODES;

    private static final ConcurrentHashMap<Integer, SoftReference<Opcodes>> opCodesCache =
            new ConcurrentHashMap<>();

    public static Opcodes getOpcodes(int apiLevel) {
        if (apiLevel <= 0) {
            return getDefaultOpCodes();
        }
        Opcodes opcodes = MiscUtil.getCache(opCodesCache, apiLevel);
        if (opcodes == null) {
            opcodes = Opcodes.forApi(apiLevel);
            MiscUtil.putCache(opCodesCache, apiLevel, opcodes);
        }
        return opcodes;
    }

    @Nonnull
    public static Opcodes getDefaultOpCodes() {
        if (DEFAULT_OPCODES == null) {
            DEFAULT_OPCODES = Opcodes.getDefault();
        }
        return DEFAULT_OPCODES;
    }

    @Nonnull
    public static DexBackedDexFile loadSingleDex(@Nonnull File file,
                                                 @Nullable Opcodes opc) throws IOException {
        return DexFileFactory.loadDexFile(file, opc);
    }

    @Nonnull
    public static List<DexBackedDexFile> loadMultiDex(@Nonnull File file, @Nullable Opcodes opc) {
        try {
            return DexFileFactory.loadDexFiles(file, null, opc);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return Collections.emptyList();
    }

    // If return false, the dex may be customized format or encrypted.
    public static boolean verifyStringOffset(@Nonnull DexBackedDexFile dex) {
        final int strIdsStartOffset = dex.readSmallUint(HeaderItem.STRING_START_OFFSET);
        final int strStartOffset = dex.readInt(strIdsStartOffset);
        final int mapOffset = dex.readSmallUintPlusDataOffset(HeaderItem.MAP_OFFSET);
        final int mapSize = dex.readSmallUint(mapOffset);
        for (int i = 0; i < mapSize; i++) {
            final int mapItemOffset = mapOffset + 4 + i * MapItem.ITEM_SIZE;
            if (dex.readUshort(mapItemOffset)
                    == org.jf.dexlib2.dexbacked.raw.ItemType.STRING_DATA_ITEM) {
                final int realStrStartOffset = dex.readSmallUint(
                        mapItemOffset + MapItem.OFFSET_OFFSET);
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

    @Nonnull
    public static List<DexBackedDexFile> getDexFiles(@Nonnull File file,
                                                     int apiLevel,
                                                     @Nullable List<String> outputNames) {
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        if (MiscUtil.isElf(file)) {
            try (Elf e = new Elf(file)) {
                final Oat oat = OatUtil.getOat(e);
                final Opcodes opc = apiLevel > 0 ? getOpcodes(apiLevel) : OatUtil.getOpcodes(oat);
                for (int i = 0; i < oat.dexFiles.length; i++) {
                    final Dex df = oat.dexFiles[i];
                    dexFiles.add(new DexBackedDexFile(opc, df.getBytes()));
                    if (outputNames != null) {
                        final String dexName = OatUtil.getOutputNameForSubDex(
                                new String(oat.oatDexFiles[i].dex_file_location_data_));
                        outputNames.add(MiscUtil.getFilenameNoExt(dexName));
                    }
                }
            } catch (IOException ex) {
                LLog.ex(ex);
            }
            return dexFiles;
        } else if (MiscUtil.isVdex(file)) {
            try (DataReader r = new DataReader(file)) {
                final Vdex vdex = new Vdex(r);
                final Opcodes opc = getOpcodes(Math.max(Oat.Version.O_80.api, apiLevel));
                for (Dex dex : vdex.dexFiles) {
                    dexFiles.add(new DexBackedDexFile(opc, dex.getBytes()));
                }
            } catch (IOException ex) {
                LLog.ex(ex);
            }
        } else {
            final Opcodes opc = getOpcodes(apiLevel);
            dexFiles = loadMultiDex(file, opc);
        }
        if (outputNames != null) {
            String dexName = "classes";
            for (int i = 0; i < dexFiles.size(); i++) {
                outputNames.add(dexName);
                dexName = "classes" + (i + 2);
            }
        }
        return dexFiles;
    }
}
