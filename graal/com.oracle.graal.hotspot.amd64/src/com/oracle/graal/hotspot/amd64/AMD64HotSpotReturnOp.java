/*
 * Copyright (c) 2012, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.REG;

import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.hotspot.GraalHotSpotVMConfig;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.gen.DiagnosticLIRGeneratorTool.ZapStackArgumentSpaceBeforeInstruction;

import jdk.vm.ci.amd64.AMD64.CPUFeature;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

/**
 * Returns from a function.
 */
@Opcode("RETURN")
final class AMD64HotSpotReturnOp extends AMD64HotSpotEpilogueBlockEndOp implements ZapStackArgumentSpaceBeforeInstruction {

    public static final LIRInstructionClass<AMD64HotSpotReturnOp> TYPE = LIRInstructionClass.create(AMD64HotSpotReturnOp.class);
    @Use({REG, ILLEGAL}) protected Value value;
    private final boolean isStub;
    private final Register scratchForSafepointOnReturn;
    private final GraalHotSpotVMConfig config;

    AMD64HotSpotReturnOp(Value value, boolean isStub, Register scratchForSafepointOnReturn, GraalHotSpotVMConfig config) {
        super(TYPE);
        this.value = value;
        this.isStub = isStub;
        this.scratchForSafepointOnReturn = scratchForSafepointOnReturn;
        this.config = config;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        leaveFrameAndRestoreRbp(crb, masm);
        if (!isStub) {
            // Every non-stub compile method must have a poll before the return.
            AMD64HotSpotSafepointOp.emitCode(crb, masm, config, true, null, scratchForSafepointOnReturn);

            /*
             * We potentially return to the interpreter, and that's an AVX-SSE transition. The only
             * live value at this point should be the return value in either rax, or in xmm0 with
             * the upper half of the register unused, so we don't destroy any value here.
             */
            if (masm.supports(CPUFeature.AVX)) {
                masm.vzeroupper();
            }
        }
        masm.ret(0);
    }
}
