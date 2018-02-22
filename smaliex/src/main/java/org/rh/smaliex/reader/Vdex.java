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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import org.rh.smaliex.LLog;
import org.rh.smaliex.reader.Vdex.QuickeningInfoList.GroupInfo;

// See art/runtime/vdex_file.cc
public class Vdex {

    public static class Header {

        final char[] magic_ = new char[4];
        final char[] version_ = new char[4];
        final int number_of_dex_files_;
        final int dex_size_;
        final int verifier_deps_size_;
        final int quickening_info_size_;

        final int[] mVdexCheckSums;
        final String mVersion;

        public Header(DataReader r) {
            r.readBytes(magic_);
            String magic = new String(magic_);
            if (!"vdex".equals(magic)) {
                LLog.e("Invalid dex magic '" + magic + "'");
            }

            r.readBytes(version_);
            mVersion = new String(version_);
            number_of_dex_files_ = r.readInt();
            dex_size_ = r.readInt();
            verifier_deps_size_ = r.readInt();
            quickening_info_size_ = r.readInt();

            mVdexCheckSums = new int[number_of_dex_files_];
            for (int i = 0; i < mVdexCheckSums.length; i++) {
                mVdexCheckSums[i] = r.readInt();
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
    }

    protected QuickeningInfoReader<? extends QuickeningInfo> getQuickeningInfoReader() {
        if ("006".equals(mHeader.mVersion.trim())) {
            return new QuickeningInfoReaderV6();
        }
        return new QuickeningInfoReaderV10();
    }

    public static class QuickeningInfoList<T extends QuickeningInfo> extends ArrayList<T> {
        public static class GroupInfo {
            public final int startIndex;
            public final int size;

            GroupInfo(int start, int size) {
                this.startIndex = start;
                this.size = size;
            }
        }

        public final ArrayList<GroupInfo> mGroupInfo = new ArrayList<>();
    }

    public abstract static class QuickeningInfoReader<T extends QuickeningInfo> {
        QuickeningInfoList<T> read(DataReader r, long begin, long end) {
            final QuickeningInfoList<T> infoList = new QuickeningInfoList<>();
            r.seek(begin);
            while (r.position() < end) {
                final int groupByteSize = r.readInt();
                final long groupEnd = r.position() + groupByteSize;
                final int start = infoList.size();
                while (r.position() < groupEnd) {
                    infoList.add(createInfo(r));
                }
                infoList.mGroupInfo.add(new GroupInfo(start, infoList.size() - start));
            }
            return infoList;
        }

        abstract T createInfo(DataReader r);
    }

    static class QuickeningInfoV6 extends QuickeningInfo {
        final int dexPc;

        QuickeningInfoV6(DataReader r) {
            dexPc = r.readUleb128();
            mIndex = r.readUleb128();
        }

        @Override
        public int getDexPc() {
            return dexPc;
        }
    }

    static class QuickeningInfoReaderV6 extends QuickeningInfoReader<QuickeningInfoV6> {
        @Override
        QuickeningInfoV6 createInfo(DataReader r) {
            return new QuickeningInfoV6(r);
        }
    }

    // https://android.googlesource.com/platform/art/+/de4b08ff24c330d5b36b5c4dc8664ed4848eeca6
    static class QuickeningInfoV10 extends QuickeningInfo {
        QuickeningInfoV10(DataReader r) {
            mIndex = r.readShort() & 0xffff;
        }
    }

    static class QuickeningInfoReaderV10 extends QuickeningInfoReader<QuickeningInfoV10> {
        @Override
        QuickeningInfoV10 createInfo(DataReader r) {
            return new QuickeningInfoV10(r);
        }
    }

    public final DataReader mReader;
    public final Header mHeader;

    public final long mDexBegin;
    public final long mVerifierDepsDataBegin;
    public final long mQuickeningInfoBegin;
    public final QuickeningInfoList<? extends QuickeningInfo> mQuickeningInfoList;

    public Vdex(DataReader r) {
        mReader = r;
        mHeader = new Header(r);
        mDexBegin = r.position();
        mVerifierDepsDataBegin = mDexBegin + mHeader.dex_size_;
        mQuickeningInfoBegin = mVerifierDepsDataBegin + mHeader.verifier_deps_size_;
        mQuickeningInfoList = getQuickeningInfoReader().read(r,
                mQuickeningInfoBegin, mQuickeningInfoBegin + mHeader.quickening_info_size_);
    }

    public int getSizeOfChecksumsSection() {
        return Integer.SIZE * mHeader.number_of_dex_files_;
    }

    // TODO multi-dex
    public byte[] getDexBytes() {
        final byte[] dexBytes = new byte[mHeader.dex_size_];
        mReader.seek(mDexBegin);
        mReader.readBytes(dexBytes);
        return dexBytes;
    }

    // TODO multi-dex
    public void saveTo(File outputFile) throws IOException {
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            mReader.getChannel().transferTo(mDexBegin, mHeader.dex_size_, output.getChannel());
        }
    }

}
