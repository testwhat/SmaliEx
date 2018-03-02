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

package org.rh.smaliex.deopt;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.analysis.OdexedFieldInstructionMapper;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.DexBackedMethod;
import org.jf.dexlib2.dexbacked.reference.DexBackedFieldReference;
import org.jf.dexlib2.dexbacked.reference.DexBackedReference;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction22cs;
import org.jf.dexlib2.iface.instruction.formats.Instruction35ms;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rms;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.InstructionRewriter;
import org.jf.dexlib2.rewriter.MethodRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.util.MethodUtil;
import org.rh.smaliex.DexUtil;
import org.rh.smaliex.LLog;
import org.rh.smaliex.reader.Oat;
import org.rh.smaliex.reader.Vdex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.ListIterator;

public class VdexDecompiler {

    private VdexDecompiler() {
    }

    @Nonnull
    public static DexFile[] unquicken(@Nonnull Vdex vdex, @Nullable Opcodes opcodes) {
        if (opcodes == null) {
            opcodes = DexUtil.getOpcodes(Oat.Version.O_80.api);
        }

        VdexRewriterModule previousModule = null;
        final DexFile[] mDeodexedFiles = new DexFile[vdex.dexFiles.length];
        for (int i = 0; i < mDeodexedFiles.length; i++) {
            final VdexRewriterModule rewriterModule;
            if (vdex.isSingleQuickeningInfo && previousModule != null) {
                // All dex share the same iterator.
                rewriterModule = new VdexRewriterModule(vdex.dexFiles[i], previousModule);
            } else {
                rewriterModule = new VdexRewriterModule(vdex.dexFiles[i], opcodes);
            }
            final DexRewriter vdexRewriter = new DexRewriter(rewriterModule);
            mDeodexedFiles[i] = ImmutableDexFile.of(
                    vdexRewriter.rewriteDexFile(rewriterModule.mDex));
            previousModule = rewriterModule;
            if (VdexRewriterModule.DEBUG) {
                rewriterModule.fillLastInfo();
                rewriterModule.printUnquickenInfo();
            }
        }
        return mDeodexedFiles;
    }

    // See art/runtime/dex_to_dex_decompiler.cc
    public static class VdexRewriterModule extends RewriterModule {
        public static boolean DEBUG;
        private static final int kDexNoIndex16 = 0xffff;
        private final Vdex.QuickenDex mOdex;
        private final DexBackedDexFile mDex;
        private ListIterator<Vdex.QuickeningInfoList> mGiIter;
        private ListIterator<Vdex.QuickeningInfo> mQiIter;
        private DexBackedMethod mCurrentMethod;
        private boolean mDecompileReturnInstruction = true;
        private boolean mNoQuickenInfo;
        private int mDexPc;
        private int mQuickenInstrCount;

        final static OdexedFieldInstructionMapper sInstrMapper =
                new OdexedFieldInstructionMapper(true);

        static class MethodInfo {
            final Method method;
            int quickenInstrCount;

            MethodInfo(Method m) {
                method = m;
            }
        }

        private final ArrayList<MethodInfo> mMethodInfoList = DEBUG ? new ArrayList<>() : null;

        public VdexRewriterModule(Vdex.QuickenDex odex, Opcodes opcodes) {
            mOdex = odex;
            mDex = new DexBackedDexFile(opcodes, odex.getBytes());
            mGiIter = odex.quickeningInfoList.listIterator();
        }

        private VdexRewriterModule(Vdex.QuickenDex odex, VdexRewriterModule module) {
            mOdex = odex;
            mDex = new DexBackedDexFile(module.mDex.getOpcodes(), odex.getBytes());
            mGiIter = module.mGiIter;
            mQiIter = module.mQiIter;
        }

        public void setDecompileReturnInstruction(boolean enable) {
            mDecompileReturnInstruction = enable;
        }

        Vdex.QuickeningInfo nextInfo() {
            if (!mQiIter.hasNext()) {
                return null;
            }
            return mQiIter.next();
        }

        Instruction decompileNop() {
            final Vdex.QuickeningInfo ni = nextInfo();
            if (ni == null) {
                return null;
            }
            final int refIndex = ni.getIndex();
            if (refIndex == kDexNoIndex16 || !ni.matchDexPc(mDexPc)) {
                mQiIter.previous();
                return null;
            }
            if (refIndex > 0xff) {
                LLog.i("Skip incorrect NOP ref " + refIndex + " in " + mCurrentMethod);
                mQiIter.previous();
                return null;
            }
            final int typeIndex = nextInfo().getIndex();
            final Reference ref = DexBackedReference.makeReference(
                    mDex, ReferenceType.TYPE, typeIndex);
            return new ImmutableInstruction21c(Opcode.CHECK_CAST, refIndex, ref);
        }

        Instruction decompileInstanceFieldAccess(Instruction instruction) {
            final int fieldIndex = nextInfo().getIndex();
            final DexBackedFieldReference fieldRef = (DexBackedFieldReference)
                    DexBackedReference.makeReference(mDex, ReferenceType.FIELD, fieldIndex);
            final Opcode newOpcode = sInstrMapper.getAndCheckDeodexedOpcode(
                    fieldRef.getType(), instruction.getOpcode());
            final Instruction22cs instr = (Instruction22cs) instruction;
            return new ImmutableInstruction22c(newOpcode,
                    instr.getRegisterA(), instr.getRegisterB(), fieldRef);
        }

        Instruction decompileInvokeVirtual(Instruction instruction, boolean isRange) {
            final int methodIndex = nextInfo().getIndex();
            final Reference methodRef = DexBackedReference.makeReference(
                    mDex, ReferenceType.METHOD, methodIndex);
            if (isRange) {
                final Instruction3rms instr = (Instruction3rms) instruction;
                return new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,
                        instr.getStartRegister(),
                        instr.getRegisterCount(), methodRef);
            }
            final Instruction35ms instr = (Instruction35ms) instruction;
            return new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,
                    instr.getRegisterCount(),
                    instr.getRegisterC(), instr.getRegisterD(),
                    instr.getRegisterE(), instr.getRegisterF(),
                    instr.getRegisterG(), methodRef);
        }

        @Nonnull
        @Override
        public Rewriter<Instruction> getInstructionRewriter(@Nonnull Rewriters rewriters) {
            return new InstructionRewriter(rewriters) {

                @Nonnull
                @Override
                public Instruction rewrite(@Nonnull Instruction instruction) {
                    try {
                        return decompile(instruction);
                    } catch (Exception e) {
                        LLog.e("=== Error ===");
                        LLog.e("Method: " + mCurrentMethod);
                        LLog.e("Instruction: " + instruction.getOpcode()
                                + " " + instruction.getOpcode().format
                                + " pos: " + mDexPc);
                        throw e;
                    }
                }

                Instruction decompile(@Nonnull Instruction instruction) {
                    boolean isQuickenInstr = true;
                    Instruction newInstr = null;
                    switch (instruction.getOpcode()) {
                        case RETURN_VOID_NO_BARRIER:
                            if (mDecompileReturnInstruction) {
                                newInstr = new ImmutableInstruction10x(Opcode.RETURN_VOID);
                            }
                            isQuickenInstr = false;
                            break;
                        case NOP:
                            if (!mNoQuickenInfo) {
                                newInstr = decompileNop();
                            }
                            break;
                        case IGET_QUICK:
                        case IGET_WIDE_QUICK:
                        case IGET_OBJECT_QUICK:
                        case IGET_BOOLEAN_QUICK:
                        case IGET_BYTE_QUICK:
                        case IGET_CHAR_QUICK:
                        case IGET_SHORT_QUICK:
                        case IPUT_QUICK:
                        case IPUT_BOOLEAN_QUICK:
                        case IPUT_BYTE_QUICK:
                        case IPUT_CHAR_QUICK:
                        case IPUT_SHORT_QUICK:
                        case IPUT_WIDE_QUICK:
                        case IPUT_OBJECT_QUICK:
                            newInstr = decompileInstanceFieldAccess(instruction);
                            break;
                        case INVOKE_VIRTUAL_QUICK:
                            newInstr = decompileInvokeVirtual(instruction, false);
                            break;
                        case INVOKE_VIRTUAL_QUICK_RANGE:
                            newInstr = decompileInvokeVirtual(instruction, true);
                            break;
                        default:
                            isQuickenInstr = false;
                            break;
                    }
                    if (isQuickenInstr) {
                        mQuickenInstrCount++;
                    }
                    mDexPc += instruction.getCodeUnits();
                    if (newInstr != null) {
                        return newInstr;
                    }
                    return instruction;
                }
            };
        }

        @Nonnull
        @Override
        public Rewriter<Method> getMethodRewriter(@Nonnull Rewriters rewriters) {
            return new MethodRewriter(rewriters) {

                void nextQuickenGroup() {
                    if (mOdex.quickeningInfoList.shouldIterateAll
                            && mQiIter != null && mQiIter.hasNext()) {
                        return;
                    }

                    if (!mGiIter.hasNext()) {
                        if (DEBUG) {
                            LLog.v("Reach end @ " + mCurrentMethod);
                        }
                        return;
                    }
                    final Vdex.QuickeningInfoList list = mOdex.quickeningInfoList.get(
                            mGiIter.nextIndex());
                    if (list.matchCodeOffset(mCurrentMethod.getCodeOffset())) {
                        mQiIter = list.listIterator();
                        mGiIter.next();
                    } else {
                        mNoQuickenInfo = true;
                    }
                }

                @Nonnull
                @Override
                public Method rewrite(@Nonnull Method method) {
                    mDexPc = 0;
                    mNoQuickenInfo = false;
                    mCurrentMethod = (DexBackedMethod) method;
                    if (!AccessFlags.ABSTRACT.isSet(method.getAccessFlags())) {
                        nextQuickenGroup();
                        if (DEBUG) {
                            fillLastInfo();
                            mMethodInfoList.add(new MethodInfo(method));
                        }
                    }
                    mQuickenInstrCount = 0;
                    return super.rewrite(method);
                }
            };
        }

        void fillLastInfo() { // Debug usage
            // The counter is updated after seeing a method, so here updates the previous.
            final int size = mMethodInfoList.size();
            if (size > 0) {
                mMethodInfoList.get(size - 1).quickenInstrCount = mQuickenInstrCount;
            }
        }

        public void printUnquickenInfo() { // Debug usage
            if (mMethodInfoList != null) {
                System.out.println("===== Method =====");
                for (int i = 0; i < mMethodInfoList.size(); i++) {
                    final MethodInfo mi = mMethodInfoList.get(i);
                    System.out.println(String.format("# %4d %c [%3d] %s", (i + 1),
                            MethodUtil.isDirect(mi.method) ? 'D' : 'V',
                            mi.quickenInstrCount, mi.method.toString()));
                }
            }
            System.out.println("\n===== mQuickeningInfo =====");
            final ArrayList<Vdex.QuickeningInfoList> groupInfoList = mOdex.quickeningInfoList;
            for (int i = 0; i < groupInfoList.size(); i++) {
                final Vdex.QuickeningInfoList gi = groupInfoList.get(i);
                System.out.println(String.format("# %4d size: %d", (i + 1), gi.size()));
            }
        }

    }
}
