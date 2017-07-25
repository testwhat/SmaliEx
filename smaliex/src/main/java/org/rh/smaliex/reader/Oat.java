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

package org.rh.smaliex.reader;

import org.rh.smaliex.LLog;
import org.rh.smaliex.MiscUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@SuppressWarnings("unused")
public class Oat {
    public final static String SECTION_RODATA = ".rodata";

    public enum Version {
        // https://android.googlesource.com/platform/art/+/lollipop-release/runtime/oat.cc#26
        L_50(21, 39),

        // https://android.googlesource.com/platform/art/+/lollipop-mr1-release/runtime/oat.cc#26
        L_MR1_51(22, 45),

        // https://android.googlesource.com/platform/art/+/marshmallow-release/runtime/oat.h#35
        M_60(23, 64),

        // https://android.googlesource.com/platform/art/+/nougat-release/runtime/oat.h#35
        N_70(24, 79),

        // https://android.googlesource.com/platform/art/+/nougat-mr1-release/runtime/oat.h#35
        N_MR1_71(25, 88);

        final int api;
        final int oat;

        Version(int api, int oat) {
            this.api = api;
            this.oat = oat;
        }
    }

    // /art/runtime/instruction_set.h
    public final static class InstructionSet {
        public final static int kNone = 0;
        public final static int kArm = 1;
        public final static int kArm64 = 2;
        public final static int kThumb2 = 3;
        public final static int kX86 = 4;
        public final static int kX86_64 = 5;
        public final static int kMips = 6;
        public final static int kMips64 = 7;
    }

    // /art/runtime/oat.h
    public static class Header {

        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] magic_ = new char[4];
        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] version_ = new char[4];
        @DumpFormat(hex = true)
        final int adler32_checksum_;

        @DumpFormat(enumClass = InstructionSet.class)
        final int instruction_set_;
        final int instruction_set_features_; // instruction_set_features_bitmap_
        final int dex_file_count_;
        final int executable_offset_;
        final int interpreter_to_interpreter_bridge_offset_;
        final int interpreter_to_compiled_code_bridge_offset_;
        final int jni_dlsym_lookup_offset_;
        int portable_imt_conflict_trampoline_offset_;
        int portable_resolution_trampoline_offset_;
        int portable_to_interpreter_bridge_offset_;
        final int quick_generic_jni_trampoline_offset_;
        final int quick_imt_conflict_trampoline_offset_;
        final int quick_resolution_trampoline_offset_;
        final int quick_to_interpreter_bridge_offset_;

        final int image_patch_delta_;
        final int image_file_location_oat_checksum_;
        final int image_file_location_oat_data_begin_;
        final int key_value_store_size_;
        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] key_value_store_;

        int artVersion = Version.M_60.oat;

        public Header(DataReader r) throws IOException {
            r.readBytes(magic_);
            if (magic_[0] != 'o' || magic_[1] != 'a' || magic_[2] != 't') {
                LLog.e(String.format("Invalid art magic %c%c%c", magic_[0], magic_[1], magic_[2]));
            }
            r.readBytes(version_);
            artVersion = org.rh.smaliex.MiscUtil.toInt(new String(version_));

            adler32_checksum_ = r.readInt();
            instruction_set_ = r.readInt();
            instruction_set_features_ = r.readInt();

            dex_file_count_ = r.readInt();
            executable_offset_ = r.readInt();
            interpreter_to_interpreter_bridge_offset_ = r.readInt();
            interpreter_to_compiled_code_bridge_offset_ = r.readInt();

            jni_dlsym_lookup_offset_ = r.readInt();
            if (artVersion < 52) {
                // Remove portable. (since oat version 052)
                // https://android.googlesource.com/platform/art/+/956af0f0
                portable_imt_conflict_trampoline_offset_ = r.readInt();
                portable_resolution_trampoline_offset_ = r.readInt();
                portable_to_interpreter_bridge_offset_ = r.readInt();
            }
            quick_generic_jni_trampoline_offset_ = r.readInt();
            quick_imt_conflict_trampoline_offset_ = r.readInt();
            quick_resolution_trampoline_offset_ = r.readInt();
            quick_to_interpreter_bridge_offset_ = r.readInt();

            image_patch_delta_ = r.readInt();
            image_file_location_oat_checksum_ = r.readInt();
            image_file_location_oat_data_begin_ = r.readInt();
            key_value_store_size_ = r.readInt();
            key_value_store_ = new char[key_value_store_size_];
            r.readBytes(key_value_store_);
        }
    }

    // /art/runtime/dex_file.h
    public static class DexFile {

        public static class Header {
            @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
            final char[] magic_ = new char[4];
            @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
            final char[] version_ = new char[4];
            @DumpFormat(hex = true)
            final int checksum_;
            @DumpFormat(hex = true)
            final byte[] signature_ = new byte[20];
            public final int file_size_;
            public final int header_size_;
            final int endian_tag_;
            final int link_size_;
            final int link_off_;
            final int map_off_;
            final int string_ids_size_;
            final int string_ids_off_;
            final int type_ids_size_;
            final int type_ids_off_;
            final int proto_ids_size_;
            final int proto_ids_off_;
            final int field_ids_size_;
            final int field_ids_off_;
            final int method_ids_size_;
            final int method_ids_off_;
            final int class_defs_size_;
            final int class_defs_off_;
            final int data_size_;
            final int data_off_;

            public Header(DataReader r) throws IOException {
                r.readBytes(magic_);
                if (magic_[0] != 'd' || magic_[1] != 'e' || magic_[2] != 'x') {
                    LLog.e(String.format("Invalid dex magic '%c%c%c'",
                            magic_[0], magic_[1], magic_[2]));
                }
                r.readBytes(version_);
                checksum_= r.readInt();
                if (version_[0] != '0' || version_[1] != '3' || version_[2] < '5') {
                    LLog.e(String.format("Invalid dex version '%c%c%c'",
                            version_[0], version_[1], version_[2]));
                }
                r.readBytes(signature_);
                file_size_ = r.readInt();
                header_size_ = r.readInt();
                endian_tag_ = r.readInt();
                link_size_ = r.readInt();
                link_off_ = r.readInt();
                map_off_ = r.readInt();
                string_ids_size_ = r.readInt();
                string_ids_off_ = r.readInt();
                type_ids_size_ = r.readInt();
                type_ids_off_ = r.readInt();
                proto_ids_size_ = r.readInt();
                proto_ids_off_ = r.readInt();
                field_ids_size_ = r.readInt();
                field_ids_off_ = r.readInt();
                method_ids_size_ = r.readInt();
                method_ids_off_ = r.readInt();
                class_defs_size_ = r.readInt();
                class_defs_off_ = r.readInt();
                data_size_ = r.readInt();
                data_off_ = r.readInt();
            }
        }

        public final long mDexPosition;
        public final DataReader mReader;
        public final Header mHeader;

        public DexFile(DataReader r) throws IOException {
            mDexPosition = r.position();
            mReader = r;
            mHeader = new Header(r);
        }

        public void saveTo(File outputFile) throws IOException {
            String targetExt = "dex";
            String outPath = outputFile.getAbsolutePath();
            if (!outPath.endsWith(targetExt)) {
                int dotPos = outPath.lastIndexOf(".");
                if (dotPos > 0) {
                    outPath = outPath.substring(0, dotPos + 1) + targetExt;
                } else {
                    outPath = outPath + "." + targetExt;
                }
                outputFile = new File(outPath);
            }
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                mReader.getChannel().transferTo(mDexPosition, mHeader.file_size_, output.getChannel());
            }
        }
    }

    // See art/compiler/oat_writer OatDexFile::Write
    public static class OatDexFile {
        public final int dex_file_location_size_;
        @DumpFormat(type = DumpFormat.TYPE_BYTE, isString = true)
        public final byte[] dex_file_location_data_;
        @DumpFormat(hex = true)
        final int dex_file_location_checksum_;
        final int dex_file_offset_;
        File dex_file_pointer_; // If not null, dex_file_offset_ is the size of vdex header
        int class_offsets_offset_;
        int lookup_table_offset_;

        public OatDexFile(DataReader r, int version) throws IOException {
            dex_file_location_size_ = r.readInt();
            dex_file_location_data_ = new byte[dex_file_location_size_];
            r.readBytes(dex_file_location_data_);
            dex_file_location_checksum_ = r.readInt();
            dex_file_offset_ = r.readInt();

            File vdex = MiscUtil.changeExt(r.getFile(), "vdex");
            if (vdex.exists()) {
                dex_file_pointer_ = vdex;
            }
            if (version >= Version.N_70.oat) {
                class_offsets_offset_ = r.readInt();
                lookup_table_offset_ = r.readInt();
            }
        }

        public String getLocation() {
            return new String(dex_file_location_data_);
        }
    }

    public DexFile[] getDexFiles() {
        return mDexFiles;
    }

    public final long mOatPosition;
    public final Header mHeader;
    public final OatDexFile[] mOatDexFiles;
    public final DexFile[] mDexFiles;
    public final File mSrcFile;

    public Oat(DataReader reader) throws IOException {
        mOatPosition = reader.position();
        if (mOatPosition != 4096) {
             // Normally start from 4096(0x1000)
            LLog.i("Strange oat position " + mOatPosition);
        }
        mSrcFile = reader.getFile();
        mHeader = new Header(reader);
        mOatDexFiles = new OatDexFile[mHeader.dex_file_count_];
        mDexFiles = new DexFile[mHeader.dex_file_count_];
        for (int i = 0; i < mOatDexFiles.length; i++) {
            OatDexFile odf = new OatDexFile(reader, mHeader.artVersion);
            mOatDexFiles[i] = odf;
            long thisOatPos = reader.position();

            DexFile dex;
            if (odf.dex_file_pointer_ != null) {
                DataReader r = new DataReader(odf.dex_file_pointer_);
                reader.addAssociatedReader(r);
                r.seek(odf.dex_file_offset_);
                dex = new DexFile(r);
            } else {
                reader.seek(mOatPosition + odf.dex_file_offset_);
                dex = new DexFile(reader);
            }
            mDexFiles[i] = dex;

            if (mHeader.artVersion < Version.N_70.oat) {
                int num_methods_offsets_ = dex.mHeader.class_defs_size_;
                reader.seek(thisOatPos + 4 * num_methods_offsets_);
                if (reader.previewInt() > 0xff) { // workaround for samsung offset
                    //num_methods_offsets_ += 4;
                    reader.readInt();
                }
                // No need to read method information
                // methods_offsets_ change to method_bitmap_ since N
                //odf.methods_offsets_ = new int[num_methods_offsets_];
                //reader.seek(thisOatPos);
                //reader.readIntArray(odf.methods_offsets_);

            } else {
                reader.seek(thisOatPos);
            }
        }
    }

    public int getArtVersion() {
        return mHeader.artVersion;
    }

    public void dump() {
        try {
            System.out.println("===== Oat header =====");
            dump(mHeader);
            System.out.println();

            System.out.println("===== OatDex files =====");
            for (OatDexFile odf : mOatDexFiles) {
                dump(odf);
                System.out.println();
            }

            System.out.println("===== Dex files =====");
            for (DexFile df : mDexFiles) {
                dump(df.mHeader);
                System.out.println();
            }
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            LLog.ex(ex);
        }
    }

    public static void dump(final Object obj) throws IllegalArgumentException, IllegalAccessException {
        Field[] fields = obj.getClass().getDeclaredFields();

        for (Field field : fields) {
            if (field.getModifiers() == Modifier.STATIC
                    || !field.getName().endsWith("_")) {
                continue;
            }
            field.setAccessible(true);
            Class<?> type = field.getType();
            System.out.print(field.getName() + " = ");
            Object val = field.get(obj);
            if (val == null) {
                System.out.println("null");
                continue;
            }
            DumpFormat fmt = field.getAnnotation(DumpFormat.class);
            if (fmt != null) {
                if (fmt.isString()) {
                    String rawStr;
                    if (fmt.type() == DumpFormat.TYPE_BYTE) {
                        rawStr = new String((byte[]) val);
                    } else {
                        rawStr = new String((char[]) val);
                    }
                    System.out.println(rawStr.trim());
                } else if (fmt.enumClass() != DumpFormat.class) {
                    Field[] enumDefines = fmt.enumClass().getDeclaredFields();
                    boolean matched = false;
                    for (Field f : enumDefines) {
                        if (val.equals(f.get(null))) {
                            System.out.println(f.getName());
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        System.out.println(val);
                    }
                } else {
                    if (type.isArray()) {
                        byte[] bytes = (byte[]) val;
                        String sf = fmt.hex() ? "%02X " : "%d ";
                        for (byte b : bytes) {
                            System.out.printf(sf, b);
                        }
                        System.out.println();
                    } else {
                        String sf = fmt.hex() ? "0x%X\n" : "%d\n";
                        System.out.printf(sf, val);
                    }
                }
            } else {
                if (type.isArray()) {
                    Class<?> compType = type.getComponentType();
                    int len = Array.getLength(val);
                    System.out.println(compType + "[" + len + "]");
                } else {
                    System.out.println(val);
                }
            }
        }
    }

    public static void dump(String oatFile) {
        try (Elf e = new Elf(oatFile)) {
            DataReader r = e.getReader();
            Elf.Elf_Shdr sec = e.getSection(SECTION_RODATA);
            if (sec != null) {
                r.seek(sec.getOffset());
                Oat oat = new Oat(r);
                oat.dump();
            }
        } catch (IOException ex) {
            LLog.e("Failed to dump " + oatFile);
            LLog.ex(ex);
        }
    }

    @Retention(value = RetentionPolicy.RUNTIME)
    @Target(value = {ElementType.FIELD})
    public @interface DumpFormat {
        int TYPE_BYTE = 0;
        int TYPE_CHAR = 1;
        int type() default -1;
        boolean isString() default false;
        boolean hex() default false;
        Class<?> enumClass() default DumpFormat.class;
    }

// See compiler/oat_writer.h
// OatHeader         variable length with count of D OatDexFiles
//
// OatDexFile[0]     one variable sized OatDexFile with offsets to Dex and OatClasses
// OatDexFile[1]
// ...
// OatDexFile[D]
//
// Dex[0]            one variable sized DexFile for each OatDexFile.
// Dex[1]            these are literal copies of the input .dex files.
// ...
// Dex[D]
//
// OatClass[0]       one variable sized OatClass for each of C DexFile::ClassDefs
// OatClass[1]       contains OatClass entries with class status, offsets to code, etc.
// ...
// OatClass[C]
//
// GcMap             one variable sized blob with GC map.
// GcMap             GC maps are deduplicated.
// ...
// GcMap
//
// VmapTable         one variable sized VmapTable blob (quick compiler only).
// VmapTable         VmapTables are deduplicated.
// ...
// VmapTable
//
// MappingTable      one variable sized blob with MappingTable (quick compiler only).
// MappingTable      MappingTables are deduplicated.
// ...
// MappingTable
//
// padding           if necessary so that the following code will be page aligned
//
// OatMethodHeader   fixed size header for a CompiledMethod including the size of the MethodCode.
// MethodCode        one variable sized blob with the code of a CompiledMethod.
// OatMethodHeader   (OatMethodHeader, MethodCode) pairs are deduplicated.
// MethodCode
// ...
// OatMethodHeader
// MethodCode
//
}
