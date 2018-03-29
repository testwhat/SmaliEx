package org.jf.dexlib2.dexbacked.util;

import org.jf.dexlib2.dexbacked.BaseDexBuffer;
import org.jf.dexlib2.dexbacked.BaseDexReader;

import javax.annotation.Nonnull;

// libdexfile/dex/compact_offset_table.cc
public class CompactOffsetTable {
    private static final int kElementsPerIndex = 16;
    private static final int kBitsPerIntPtrT = 32;
    final DataReader reader;
    final int dataBegin;
    final int tableOffset;
    final int minimumOffset;

    public interface DataReader {
        void setOffset(int offset);
        int readInt(int offset);
        int readUbyte(int offset);
        int readSmallUint(int offset);
        int readSmallUleb128();
    }

    public static class DexReader implements DataReader {
        final BaseDexReader reader;

        public DexReader(BaseDexBuffer dex) {
            this.reader = dex.readerAt(0);
        }

        @Override
        public void setOffset(int offset) {
            reader.setOffset(offset);
        }

        @Override
        public int readInt(int offset) {
            return reader.readInt(offset);
        }

        @Override
        public int readUbyte(int offset) {
            return reader.readUbyte(offset);
        }

        @Override
        public int readSmallUint(int offset) {
            return reader.readSmallUint(offset);
        }

        @Override
        public int readSmallUleb128() {
            return reader.readSmallUleb128();
        }
    }

    public CompactOffsetTable(@Nonnull DataReader reader, int dataBegin) {
        this(reader, dataBegin + 8,
                reader.readInt(dataBegin), reader.readInt(dataBegin + 4));
    }

    public CompactOffsetTable(@Nonnull DataReader reader, int dataBegin,
                              int minimumOffset, int tableOffset) {

        this.reader = reader;
        this.dataBegin = dataBegin;
        this.tableOffset = tableOffset;
        this.minimumOffset = minimumOffset;
    }

    public int getOffset(int index) {
        final int offset = reader.readSmallUint(
                dataBegin + tableOffset + (index / kElementsPerIndex) * 4);
        final int bitIndex = index % kElementsPerIndex;

        final int bitMask = (reader.readUbyte(dataBegin + offset) << 8)
                | reader.readUbyte(dataBegin + offset + 1);
        if ((bitMask & (1 << bitIndex)) == 0) {
            return 0;
        }

        reader.setOffset(dataBegin + offset + 2);
        int count = Integer.bitCount(bitMask << (kBitsPerIntPtrT - 1 - bitIndex));
        int currentOffset = minimumOffset;
        do {
            currentOffset += reader.readSmallUleb128();
            --count;
        } while (count > 0);
        return currentOffset;
    }
}
