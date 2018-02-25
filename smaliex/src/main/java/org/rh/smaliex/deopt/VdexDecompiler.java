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
import org.rh.smaliex.LLog;
import org.rh.smaliex.reader.Oat;
import org.rh.smaliex.reader.Vdex;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.ListIterator;

public class VdexDecompiler {
    public final VdexRewriterModule mRewriterModule;
    private final DexRewriter mVdexRewriter;
    private DexFile mResult;

    public VdexDecompiler(Vdex vdex) {
        mRewriterModule = new VdexRewriterModule(vdex);
        mVdexRewriter = new DexRewriter(mRewriterModule);
    }

    public DexFile getUnquickenDexFile() {
        if (mResult == null) {
            mResult = ImmutableDexFile.of(
                    mVdexRewriter.rewriteDexFile(mRewriterModule.mDex));
            if (VdexRewriterModule.DEBUG) {
                mRewriterModule.fillLastInfo();
            }
        }
        return mResult;
    }

    // See art/runtime/dex_to_dex_decompiler.cc
    public static class VdexRewriterModule extends RewriterModule {
        public static boolean DEBUG;
        private static final int kDexNoIndex16 = 0xffff;
        private final Vdex mVdex;
        private final DexBackedDexFile mDex; // TODO multi-dex
        private ListIterator<? extends Vdex.QuickeningInfo> mQiIter;
        private int mDexPc;
        private Method mCurrentMethod;
        private boolean mDecompileReturnInstruction = true;
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

        private final ArrayList<MethodInfo> mMethodInfoList;

        public VdexRewriterModule(Vdex vdex) {
            this(vdex, Opcodes.forApi(Oat.Version.O_80.api));
        }

        public VdexRewriterModule(Vdex vdex, Opcodes opcodes) {
            mVdex = vdex;
            mDex = new DexBackedDexFile(opcodes, vdex.getDexBytes());
            mQiIter = vdex.mQuickeningInfoList.listIterator();
            mMethodInfoList = DEBUG ? new ArrayList<>() : null;
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
            if (ni == null || ni.getIndex() == kDexNoIndex16
                    || (ni.getDexPc() != Vdex.QuickeningInfo.NO_DEX_PC && ni.getDexPc() != mDexPc)) {
                mQiIter.previous();
                return null;
            }
            final int refIndex = ni.getIndex();
            if (refIndex >= 0xff) {
                LLog.i("Skip incorrect NOP ref in " + mCurrentMethod);
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
                                + " " + instruction.getOpcode().format);
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
                            newInstr = decompileNop();
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

                @Nonnull
                @Override
                public Method rewrite(@Nonnull Method method) {
                    mDexPc = 0;
                    mCurrentMethod = method;
                    if (DEBUG && !AccessFlags.ABSTRACT.isSet(method.getAccessFlags())) {
                        fillLastInfo();
                        mMethodInfoList.add(new MethodInfo(method));
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
            final ArrayList<Vdex.QuickeningInfoList.GroupInfo> groupInfoList =
                    mVdex.mQuickeningInfoList.mGroupInfo;
            for (int i = 0; i < groupInfoList.size(); i++) {
                final Vdex.QuickeningInfoList.GroupInfo gi = groupInfoList.get(i);
                System.out.println(String.format("# %4d size: %d", (i + 1), gi.size));
            }
        }

    }
}
