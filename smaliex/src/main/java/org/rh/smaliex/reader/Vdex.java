/*
 * Copyright (C) 2018 Riddle Hsu
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

import static org.rh.smaliex.MiscUtil.DumpFormat;

import org.jf.dexlib2.dexbacked.BaseDexBuffer;
import org.jf.dexlib2.dexbacked.util.CompactOffsetTable;
import org.rh.smaliex.LLog;
import org.rh.smaliex.MiscUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;

// See art/runtime/vdex_file.cc
public class Vdex {

    static final int VERSION_OREO_006 = 6;
    static final int VERSION_OREO_MR1_010 = 10;
    static final int VERSION_P_018 = 18;

    public static class Header {

        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] magic_ = new char[4];
        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] version_ = new char[4];
        public final int number_of_dex_files_;
        final int dex_size_;
        final int dex_shared_data_size_;
        final int verifier_deps_size_;
        final int quickening_info_size_;

        final int[] vdexCheckSums;
        public final int version;

        public Header(DataReader r) {
            r.readBytes(magic_);
            String magic = new String(magic_);
            if (!"vdex".equals(magic)) {
                LLog.e("Invalid dex magic '" + magic + "'");
            }

            r.readBytes(version_);
            version = MiscUtil.toInt(new String(version_));
            number_of_dex_files_ = r.readInt();
            dex_size_ = r.readInt();
            dex_shared_data_size_ = versionNears(VERSION_P_018) ? r.readInt() : 0;
            verifier_deps_size_ = r.readInt();
            quickening_info_size_ = r.readInt();

            vdexCheckSums = new int[number_of_dex_files_];
            for (int i = 0; i < vdexCheckSums.length; i++) {
                vdexCheckSums[i] = r.readInt();
            }
        }

        public boolean versionNears(int version) {
            return Math.abs(this.version - version) <= 1;
        }
    }

    public static class QuickeningInfo {
        public static final int NO_DEX_PC = -1;
        int mIndex;

        public int getIndex() {
            return mIndex;
        }

        public int getDexPc() {
            return NO_DEX_PC;
        }

        public boolean matchDexPc(int dexPc) {
            return getDexPc() == NO_DEX_PC || getDexPc() == dexPc;
        }
    }

    // A group means a set of quicken info for a method.
    public static class QuickeningGroupList extends ArrayList<QuickeningInfoList> {
        public final boolean shouldIterateAll;

        QuickeningGroupList(int initialCapacity, boolean shouldIterateAll) {
            super(initialCapacity);
            this.shouldIterateAll = shouldIterateAll;
        }
    }

    public static class QuickeningInfoList extends ArrayList<QuickeningInfo> {
        interface OffsetChecker {
            boolean matchCodeOffset(int codeOffset);
        }
        OffsetChecker mOffsetChecker;

        QuickeningInfoList(int initialCapacity) {
            super(initialCapacity);
        }

        public boolean matchCodeOffset(int codeOffset) {
            return mOffsetChecker == null || mOffsetChecker.matchCodeOffset(codeOffset);
        }
    }

    public static abstract class QuickeningInfoReader {
        Vdex vdex;
        int begin;
        int end;

        abstract QuickeningGroupList read(DataReader r, int dexIndex);
    }

    static class QuickeningInfoV6 extends QuickeningInfo {
        final int mDexPc;

        QuickeningInfoV6(DataReader r) {
            mDexPc = r.readUleb128();
            mIndex = r.readUleb128();
        }

        @Override
        public int getDexPc() {
            return mDexPc;
        }
    }

    static class QuickeningInfoReaderV6 extends QuickeningInfoReader {
        private QuickeningGroupList mGroupList;

        @Override
        public QuickeningGroupList read(DataReader r, int dexIndex) {
            if (mGroupList != null) {
                // V6 does not separate by dex files, so just reuse it.
                return mGroupList;
            }
            mGroupList = new QuickeningGroupList(16, true);
            r.position(begin);
            while (r.position() < end) {
                final int groupByteSize = r.readInt();
                final int groupEnd = r.position() + groupByteSize;
                final QuickeningInfoList infoList = new QuickeningInfoList(groupByteSize / 2);
                while (r.position() < groupEnd) {
                    infoList.add(new QuickeningInfoV6(r));
                }
                mGroupList.add(infoList);
            }
            return mGroupList;
        }
    }

    // https://android.googlesource.com/platform/art/+/de4b08ff24c330d5b36b5c4dc8664ed4848eeca6
    static class QuickeningInfoV10 extends QuickeningInfo {
        QuickeningInfoV10(DataReader r) {
            mIndex = r.readShort() & 0xffff;
        }

        static class GroupOffsetInfo implements QuickeningInfoList.OffsetChecker {
            final int codeOffset;
            final int sizeOffset;

            GroupOffsetInfo(DataReader r) {
                codeOffset = r.readInt();
                sizeOffset = r.readInt();
            }

            @Override
            public boolean matchCodeOffset(int codeOffset) {
                return this.codeOffset == codeOffset;
            }
        }
    }

    static class QuickeningInfoReaderV10 extends QuickeningInfoReader {
        @Override
        public QuickeningGroupList read(DataReader r, int dexIndex) {
            final int dexIndicesPos = end - Integer.BYTES * vdex.header.number_of_dex_files_;
            r.position(dexIndicesPos + Integer.BYTES * dexIndex);
            final int offsetStartPos = r.readInt();
            final int codeItemEnd;
            if (dexIndex == vdex.header.number_of_dex_files_ - 1) {
                codeItemEnd = dexIndicesPos;
            } else {
                r.position(dexIndicesPos + Integer.BYTES * (dexIndex + 1));
                codeItemEnd = begin + r.readInt();
            }
            LLog.i("QuickeningGroup dexIndex=" + dexIndex
                    + " offsetBegin=" + (begin + offsetStartPos)
                    + " offsetEnd=" + codeItemEnd);
            final ArrayList<QuickeningInfoV10.GroupOffsetInfo> offsetInfoList = new ArrayList<>();
            r.position(begin + offsetStartPos);
            while (r.position() < codeItemEnd) {
                offsetInfoList.add(new QuickeningInfoV10.GroupOffsetInfo(r));
            }

            final QuickeningGroupList groupList = new QuickeningGroupList(
                    offsetInfoList.size(), false);
            for (QuickeningInfoV10.GroupOffsetInfo info : offsetInfoList) {
                r.position(begin + info.sizeOffset);
                final int groupByteSize = r.readInt();
                final int groupEnd = r.position() + groupByteSize;
                final QuickeningInfoList infoList = new QuickeningInfoList(
                        groupByteSize / Integer.BYTES);
                infoList.mOffsetChecker = info;
                while (r.position() < groupEnd) {
                    infoList.add(new QuickeningInfoV10(r));
                }
                groupList.add(infoList);
            }
            return groupList;
        }
    }

    static class QuickeningInfoReaderV18 extends QuickeningInfoReader {
        static class CompactOffsetReader implements CompactOffsetTable.DataReader {
            final DataReader reader;

            CompactOffsetReader(DataReader reader) {
                this.reader = reader;
            }

            @Override
            public void setOffset(int offset) {
                reader.position(offset);
            }

            @Override
            public int readInt(int offset) {
                reader.position(offset);
                return reader.readInt();
            }

            @Override
            public int readUbyte(int offset) {
                reader.position(offset);
                return reader.readByte();
            }

            @Override
            public int readSmallUint(int offset) {
                reader.position(offset);
                return reader.readInt();
            }

            @Override
            public int readSmallUleb128() {
                return reader.readUleb128();
            }
        }

        @Override
        QuickeningGroupList read(DataReader r, int dexIndex) {
            CompactOffsetTable table = new CompactOffsetTable(new CompactOffsetReader(r),
                    begin + vdex.quickeningTableOffsets[dexIndex]);
            // TODO wait formal release
            final QuickeningGroupList empty = new QuickeningGroupList(0, false);
            return empty;
        }
    }

    public static class QuickenDex extends Dex {
        public final QuickeningGroupList quickeningInfoList;

        QuickenDex(DataReader r, int dexIndex, QuickeningInfoReader infoReader) {
            super(r);
            quickeningInfoList = infoReader.read(r, dexIndex);
            LLog.i("QuickeningInfoSize[" + dexIndex + "]="
                    + quickeningInfoList.size() + " @ " + r.getFile());
        }
    }

    public final Header header;
    public final QuickenDex[] dexFiles;
    public final int[] quickeningTableOffsets;
    public final int dexBegin;
    public final int verifierDepsDataBegin;
    public final int quickeningInfoBegin;
    public final boolean isSingleQuickeningInfo;

    protected QuickeningInfoReader createQuickeningInfoReader() {
        final QuickeningInfoReader reader;
        if (header.versionNears(VERSION_OREO_006)) {
            reader = new QuickeningInfoReaderV6();
        } else if (header.versionNears(VERSION_OREO_MR1_010)) {
            reader = new QuickeningInfoReaderV10();
        } else {
            reader = new QuickeningInfoReaderV18();
        }
        reader.vdex = this;
        reader.begin = quickeningInfoBegin;
        reader.end = quickeningInfoBegin + header.quickening_info_size_;
        return reader;
    }

    public Vdex(@Nonnull DataReader r) {
        header = new Header(r);
        dexBegin = r.position();
        verifierDepsDataBegin = dexBegin + header.dex_size_ + header.dex_shared_data_size_;
        quickeningInfoBegin = verifierDepsDataBegin + header.verifier_deps_size_;

        final QuickeningInfoReader infoReader = createQuickeningInfoReader();
        isSingleQuickeningInfo = header.versionNears(VERSION_OREO_006);

        r.position(dexBegin);
        quickeningTableOffsets = header.versionNears(VERSION_P_018)
                ? new int[header.number_of_dex_files_] : null;
        dexFiles = new QuickenDex[header.number_of_dex_files_];
        for (int i = 0; i < header.number_of_dex_files_; i++) {
            if (quickeningTableOffsets != null) {
                quickeningTableOffsets[i] = r.readInt();
            }
            final QuickenDex dex = new QuickenDex(r, i, infoReader);
            dexFiles[i] = dex;
            r.position(dex.dexPosition + dex.header.file_size_);
        }
    }

}

// From https://android.googlesource.com/platform/art/+/ ...
// ==========================================================
// [Oreo][006][oreo-release/runtime/vdex_file.h]
// File format:
//   VdexFile::Header    fixed-length header
//
//   DEX[0]              array of the input DEX files
//   DEX[1]              the bytecode may have been quickened
//   ...
//   DEX[D]
//
// ==========================================================
// [Oreo MR1][010][oreo-mr1-release/runtime/vdex_file.h]
// File format:
//   VdexFile::Header    fixed-length header
//
//   DEX[0]              array of the input DEX files
//   DEX[1]              the bytecode may have been quickened
//   ...
//   DEX[D]
//   QuickeningInfo
//     uint8[]                     quickening data
//     unaligned_uint32_t[2][]     table of offsets pair:
//                                    uint32_t[0] contains code_item_offset
//                                    uint32_t[1] contains quickening data offset from the start
//                                                of QuickeningInfo
//     unalgined_uint32_t[D]       start offsets (from the start of QuickeningInfo) in previous
//
// ==========================================================
// [P][018][p*-release/runtime/vdex_file.h]
// File format:
//   VdexFile::Header    fixed-length header
//
//   quicken_table_off[0]  offset into QuickeningInfo section for offset table for DEX[0].
//   DEX[0]                array of the input DEX files, the bytecode may have been quickened.
//   quicken_table_off[1]
//   DEX[1]
//   ...
//   DEX[D]
//   VerifierDeps
//      uint8[D][]                 verification dependencies
//   QuickeningInfo
//     uint8[D][]                  quickening data
//     uint32[D][]                 quickening data offset tables
// ==========================================================
