/*
 * Copyright 2012, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2.dexbacked;

import com.google.common.collect.ImmutableList;
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction;
import org.jf.dexlib2.dexbacked.raw.CodeItem;
import org.jf.dexlib2.dexbacked.util.DebugInfo;
import org.jf.dexlib2.dexbacked.util.FixedSizeList;
import org.jf.dexlib2.dexbacked.util.VariableSizeListIterator;
import org.jf.dexlib2.dexbacked.util.VariableSizeLookaheadIterator;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.debug.DebugItem;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.util.AlignmentUtils;
import org.jf.util.ExceptionWithContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;

public class DexBackedMethodImplementation implements MethodImplementation {
    @Nonnull public final DexBackedDexFile dexFile;
    @Nonnull public final DexBackedMethod method;
    private final int codeOffset;

    private static final int SIZEOF_UINT16 = 2;
    private static final int kRegistersSizeShift = 12;
    private static final int kInsSizeShift = 8;
    private static final int kOutsSizeShift = 4;
    private static final int kInsnsSizeShift = 5;

    private static final int kFlagPreHeaderRegisterSize = 0x1;
    private static final int kFlagPreHeaderInsSize = 0x1 << 1;
    private static final int kFlagPreHeaderOutsSize = 0x1 << 2;
    private static final int kFlagPreHeaderTriesSize = 0x1 << 3;
    private static final int kFlagPreHeaderInsnsSize = 0x1 << 4;
    private static final int kFlagPreHeaderCombined =
                    kFlagPreHeaderRegisterSize |
                    kFlagPreHeaderInsSize |
                    kFlagPreHeaderOutsSize |
                    kFlagPreHeaderTriesSize |
                    kFlagPreHeaderInsnsSize;

    // TODO: Sync to org.jf.dexlib2.dexbacked.raw.CodeItem
    static class CompactCodeItemInfo {
        final int registersSize;
        final int insSize;
        final int outsSize;
        final int triesSize;
        final int insnsCount;

        public CompactCodeItemInfo(int registersSize, int insSize, int outsSize, int triesSize, int insnsCount) {
            this.registersSize = registersSize;
            this.insSize = insSize;
            this.outsSize = outsSize;
            this.triesSize = triesSize;
            this.insnsCount = insnsCount;
        }

        @Override
        public String toString() {
            return " registers_size=" + registersSize
                    + " ins_size=" + insSize
                    + " outs_size=" + outsSize
                    + " tries_size=" + triesSize
                    + " insns_count=" + insnsCount;
        }
    }

    private final CompactCodeItemInfo codeItemInfo;

    public DexBackedMethodImplementation(@Nonnull DexBackedDexFile dexFile,
                                         @Nonnull DexBackedMethod method,
                                         int codeOffset) {
        this.dexFile = dexFile;
        this.method = method;
        this.codeOffset = codeOffset;

        // See art/libdexfile/dex/compact_dex_file.h, art/dexlayout/compact_dex_writer.cc
        if (dexFile.isCompact) {
            final int fields = dexFile.readUshort(codeOffset);
            int registers_size = (fields >> kRegistersSizeShift) & 0xF;
            int ins_size = (fields >> kInsSizeShift) & 0xF;
            int outs_size = (fields >> kOutsSizeShift) & 0xF;
            int tries_size = fields & 0xF;

            final int insns_count_and_flags_ = dexFile.readUshort(SIZEOF_UINT16 + codeOffset);
            int insns_count = insns_count_and_flags_ >> kInsnsSizeShift;

            if ((insns_count_and_flags_ & kFlagPreHeaderCombined) != 0) {
                // The code item has pre-headers.
                int preOffset = codeOffset;
                if ((insns_count_and_flags_ & kFlagPreHeaderInsnsSize) != 0) {
                    preOffset -= SIZEOF_UINT16;
                    insns_count += dexFile.readUshort(preOffset);
                    preOffset -= SIZEOF_UINT16;
                    insns_count += dexFile.readUshort(preOffset) << 16;
                }
                if ((insns_count_and_flags_ & kFlagPreHeaderRegisterSize) != 0) {
                    preOffset -= SIZEOF_UINT16;
                    registers_size += dexFile.readUshort(preOffset);
                }
                if ((insns_count_and_flags_ & kFlagPreHeaderInsSize) != 0) {
                    preOffset -= SIZEOF_UINT16;
                    ins_size += dexFile.readUshort(preOffset);
                }
                if ((insns_count_and_flags_ & kFlagPreHeaderOutsSize) != 0) {
                    preOffset -= SIZEOF_UINT16;
                    outs_size += dexFile.readUshort(preOffset);
                }
                if ((insns_count_and_flags_ & kFlagPreHeaderTriesSize) != 0) {
                    preOffset -= SIZEOF_UINT16;
                    tries_size += dexFile.readUshort(preOffset);
                }
            }
            registers_size += ins_size;
            codeItemInfo = new CompactCodeItemInfo(
                    registers_size, ins_size, outs_size, tries_size, insns_count);
        } else {
            codeItemInfo = null;
        }
    }

    @Override public int getRegisterCount() {
        if (codeItemInfo != null) {
            return codeItemInfo.registersSize;
        }
        return dexFile.readUshort(codeOffset);
    }

    public int getInstructionsCount() { // Size of the instructions list, in 16-bit code units.
        if (codeItemInfo != null) {
            return codeItemInfo.insnsCount;
        }
        return dexFile.readSmallUint(codeOffset + CodeItem.INSTRUCTION_COUNT_OFFSET);
    }

    public int getInstructionStartOffset() {
        if (codeItemInfo != null) {
            return codeOffset + 2 * SIZEOF_UINT16;
        }
        return codeOffset + CodeItem.INSTRUCTION_START_OFFSET;
    }

    public int getTriesSize() {
        if (codeItemInfo != null) {
            return codeItemInfo.triesSize;
        }
        return dexFile.readUshort(codeOffset + CodeItem.TRIES_SIZE_OFFSET);
    }

    @Nonnull @Override public Iterable<? extends Instruction> getInstructions() {
        final int instructionsStartOffset = getInstructionStartOffset();
        final int endOffset = instructionsStartOffset + (getInstructionsCount() * 2);
        return new Iterable<Instruction>() {
            @Override
            public Iterator<Instruction> iterator() {
                return new VariableSizeLookaheadIterator<Instruction>(dexFile, instructionsStartOffset) {
                    @Override
                    protected Instruction readNextItem(@Nonnull DexReader reader) {
                        if (reader.getOffset() >= endOffset) {
                            return endOfData();
                        }

                        Instruction instruction = DexBackedInstruction.readFrom(reader);

                        // Does the instruction extend past the end of the method?
                        int offset = reader.getOffset();
                        if (offset > endOffset || offset < 0) {
                            throw new ExceptionWithContext("The last instruction in the method " + method
                                    + " is truncated offset=" + offset + " endOffset=" + endOffset);
                        }
                        return instruction;
                    }
                };
            }
        };
    }

    @Nonnull
    @Override
    public List<? extends DexBackedTryBlock> getTryBlocks() {
        final int triesSize = getTriesSize();
        if (triesSize > 0) {
            final int triesStartOffset = AlignmentUtils.alignOffset(
                    getInstructionStartOffset() + getInstructionsCount() * 2, 4);
            final int handlersStartOffset = triesStartOffset + triesSize*CodeItem.TryItem.ITEM_SIZE;

            return new FixedSizeList<DexBackedTryBlock>() {
                @Nonnull
                @Override
                public DexBackedTryBlock readItem(int index) {
                    return new DexBackedTryBlock(dexFile,
                            triesStartOffset + index*CodeItem.TryItem.ITEM_SIZE,
                            handlersStartOffset);
                }

                @Override
                public int size() {
                    return triesSize;
                }
            };
        }
        return ImmutableList.of();
    }

    public int getDebugInfoOffset() {
        if (dexFile.debugInfoOffsets != null) {
            int offset = dexFile.debugInfoOffsets.getOffset(method.methodIndex);
            if (offset != 0) {
                offset += dexFile.compactDataOffset;
            }
            return offset;
        }
        return dexFile.readInt(codeOffset + CodeItem.DEBUG_INFO_OFFSET);
    }

    @Nonnull
    private DebugInfo getDebugInfo() {
        final int debugOffset = getDebugInfoOffset();

        if (debugOffset == -1 || debugOffset == 0) {
            return DebugInfo.newOrEmpty(dexFile, 0, this);
        }
        if (debugOffset < 0) {
            System.err.println(String.format("%s: Invalid debug offset %d", method, debugOffset));
            return DebugInfo.newOrEmpty(dexFile, 0, this);
        }
        if (debugOffset >= dexFile.buf.length) {
            System.err.println(String.format("%s: Invalid debug offset %d", method, debugOffset));
            return DebugInfo.newOrEmpty(dexFile, 0, this);
        }
        return DebugInfo.newOrEmpty(dexFile, debugOffset, this);
    }

    @Nonnull @Override
    public Iterable<? extends DebugItem> getDebugItems() {
        return getDebugInfo();
    }

    @Nonnull
    public Iterator<String> getParameterNames(@Nullable DexReader dexReader) {
        return getDebugInfo().getParameterNames(dexReader);
    }

    /**
     * Calculate and return the private size of a method implementation.
     *
     * Calculated as: debug info size + instructions size + try-catch size
     *
     * @return size in bytes
     */
    public int getSize() {
        int debugSize = getDebugInfo().getSize();

        //set last offset just before bytecode instructions (after insns_size)
        int lastOffset = getInstructionStartOffset();

        //set code_item ending offset to the end of instructions list (insns_size * ushort)
        lastOffset += getInstructionsCount() * 2;

        //read any exception handlers and move code_item offset to the end
        for (DexBackedTryBlock tryBlock: getTryBlocks()) {
            Iterator<? extends DexBackedExceptionHandler> tryHandlerIter =
                tryBlock.getExceptionHandlers().iterator();
            while (tryHandlerIter.hasNext()) {
                tryHandlerIter.next();
            }
            lastOffset = ((VariableSizeListIterator)tryHandlerIter).getReaderOffset();
        }

        //method impl size = debug block size + code_item size
        return debugSize + (lastOffset - codeOffset);
    }
}

// ===== CodeItem format =====
// [Standard dex]
//   uint16_t registers_size_;            // the number of registers used by this code
//   //   (locals + parameters)
//   uint16_t ins_size_;                  // the number of words of incoming arguments to the method
//   //   that this code is for
//   uint16_t outs_size_;                 // the number of words of outgoing argument space required
//   //   by this code for method invocation
//   uint16_t tries_size_;                // the number of try_items for this instance. If non-zero,
//   //   then these appear as the tries array just after the
//   //   insns in this instance.
//   uint32_t debug_info_off_;            // Holds file offset to debug info stream.
//
//   uint32_t insns_size_in_code_units_;  // size of the insns array, in 2 byte code units
//   uint16_t insns_[1];                  // actual array of bytecode.
//
// [Compact dex]
//   // Packed code item data, 4 bits each: [registers_size, ins_size, outs_size, tries_size]
//   uint16_t fields_;
//
//   // 5 bits for if either of the fields required preheader extension, 11 bits for the number of
//   // instruction code units.
//   uint16_t insns_count_and_flags_;
//
//   uint16_t insns_[1];                  // actual array of bytecode.
