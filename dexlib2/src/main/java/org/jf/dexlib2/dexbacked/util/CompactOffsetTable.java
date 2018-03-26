package org.jf.dexlib2.dexbacked.util;

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.DexReader;

import javax.annotation.Nonnull;

// libdexfile/dex/compact_offset_table.cc
public class CompactOffsetTable {
    private static final int kElementsPerIndex = 16;
    private static final int kBitsPerIntPtrT = 32;
    final DexBackedDexFile dexFile;
    final int dataBegin;
    final int tableOffset;
    final int minimumOffset;

    public CompactOffsetTable(@Nonnull DexBackedDexFile dexFile,
                              int dataBegin, int minimumOffset, int tableOffset) {

        this.dexFile = dexFile;
        this.dataBegin = dataBegin;
        this.tableOffset = tableOffset;
        this.minimumOffset = minimumOffset;
        // TODO preload
    }

    public int getOffset(int index) {
        final int offset = dexFile.readSmallUint(
                dataBegin + tableOffset + (index / kElementsPerIndex)*4);
        final int bitIndex = index % kElementsPerIndex;

        final int bitMask = (dexFile.readUbyte(dataBegin + offset) << 8)
                | dexFile.readUbyte(dataBegin + offset + 1);
        if ((bitMask & (1 << bitIndex)) == 0) {
            return 0;
        }

        final DexReader reader = dexFile.readerAt(dataBegin + offset + 2);
        int count = Integer.bitCount(bitMask << (kBitsPerIntPtrT - 1 - bitIndex));
        int currentOffset = minimumOffset + dexFile.compactDataOffset;
        do {
            currentOffset += reader.readSmallUleb128();
            --count;
        } while (count > 0);
        return currentOffset;
    }
}
