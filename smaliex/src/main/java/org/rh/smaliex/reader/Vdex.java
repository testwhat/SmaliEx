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

import org.rh.smaliex.LLog;

import javax.annotation.Nonnull;
import java.util.ArrayList;

// See art/runtime/vdex_file.cc
public class Vdex {

    public static class Header {

        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] magic_ = new char[4];
        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] version_ = new char[4];
        public final int number_of_dex_files_;
        final int dex_size_;
        final int verifier_deps_size_;
        final int quickening_info_size_;

        final int[] vdexCheckSums;
        public final String version;

        public Header(DataReader r) {
            r.readBytes(magic_);
            String magic = new String(magic_);
            if (!"vdex".equals(magic)) {
                LLog.e("Invalid dex magic '" + magic + "'");
            }

            r.readBytes(version_);
            version = new String(version_);
            number_of_dex_files_ = r.readInt();
            dex_size_ = r.readInt();
            verifier_deps_size_ = r.readInt();
            quickening_info_size_ = r.readInt();

            vdexCheckSums = new int[number_of_dex_files_];
            for (int i = 0; i < vdexCheckSums.length; i++) {
                vdexCheckSums[i] = r.readInt();
            }
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
        Header header;
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
            r.seek(begin);
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
            final int dexIndicesPos = end - Integer.BYTES * header.number_of_dex_files_;
            r.seek(dexIndicesPos + Integer.BYTES * dexIndex);
            final int offsetStartPos = r.readInt();
            final int codeItemEnd;
            if (dexIndex == header.number_of_dex_files_ - 1) {
                codeItemEnd = dexIndicesPos;
            } else {
                r.seek(dexIndicesPos + Integer.BYTES * (dexIndex + 1));
                codeItemEnd = begin + r.readInt();
            }
            LLog.i("QuickeningGroup dexIndex=" + dexIndex
                    + " offsetBegin=" + (begin + offsetStartPos)
                    + " offsetEnd=" + codeItemEnd);
            final ArrayList<QuickeningInfoV10.GroupOffsetInfo> offsetInfoList = new ArrayList<>();
            r.seek(begin + offsetStartPos);
            while (r.position() < codeItemEnd) {
                offsetInfoList.add(new QuickeningInfoV10.GroupOffsetInfo(r));
            }

            final QuickeningGroupList groupList = new QuickeningGroupList(
                    offsetInfoList.size(), false);
            for (QuickeningInfoV10.GroupOffsetInfo info : offsetInfoList) {
                r.seek(begin + info.sizeOffset);
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

    public static class QuickenDex extends Dex {
        public final QuickeningGroupList quickeningInfoList;

        QuickenDex(DataReader r, int dexIndex, QuickeningInfoReader infoReader) {
            super(r);
            quickeningInfoList = infoReader.read(r, dexIndex);
            LLog.i("QuickeningInfoSize[" + dexIndex + "]=" + quickeningInfoList.size());
        }
    }

    public final Header header;
    public final QuickenDex[] dexFiles;
    public final int dexBegin;
    public final int verifierDepsDataBegin;
    public final int quickeningInfoBegin;
    public final boolean isSingleQuickeningInfo;

    protected QuickeningInfoReader createQuickeningInfoReader() {
        final QuickeningInfoReader reader;
        if ("006".equals(header.version.trim())) {
            reader = new QuickeningInfoReaderV6(); // Oreo
        } else {
            reader = new QuickeningInfoReaderV10(); // Oreo MR1
        }
        reader.header = header;
        reader.begin = quickeningInfoBegin;
        reader.end = quickeningInfoBegin + header.quickening_info_size_;
        return reader;
    }

    public Vdex(@Nonnull DataReader r) {
        header = new Header(r);
        dexBegin = r.position();
        verifierDepsDataBegin = dexBegin + header.dex_size_;
        quickeningInfoBegin = verifierDepsDataBegin + header.verifier_deps_size_;

        final QuickeningInfoReader infoReader = createQuickeningInfoReader();
        isSingleQuickeningInfo = infoReader instanceof QuickeningInfoReaderV6;

        r.position(dexBegin);
        dexFiles = new QuickenDex[header.number_of_dex_files_];
        for (int i = 0; i < header.number_of_dex_files_; i++) {
            final QuickenDex dex = new QuickenDex(r, i, infoReader);
            dexFiles[i] = dex;
            r.position(dex.dexPosition + dex.header.file_size_);
        }
    }

}
