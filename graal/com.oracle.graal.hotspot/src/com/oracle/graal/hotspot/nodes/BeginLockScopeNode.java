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
package com.oracle.graal.hotspot.nodes;

import static com.oracle.graal.nodeinfo.InputType.Memory;
import static com.oracle.graal.nodeinfo.NodeCycles.CYCLES_2;
import static com.oracle.graal.nodeinfo.NodeSize.SIZE_1;

import com.oracle.graal.compiler.common.LocationIdentity;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.hotspot.HotSpotLIRGenerator;
import com.oracle.graal.lir.VirtualStackSlot;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.extended.MonitorEnter;
import com.oracle.graal.nodes.memory.AbstractMemoryCheckpoint;
import com.oracle.graal.nodes.memory.MemoryCheckpoint;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;
import com.oracle.graal.word.Word;
import com.oracle.graal.word.WordTypes;

import jdk.vm.ci.meta.Value;

/**
 * Intrinsic for opening a scope binding a stack-based lock with an object. A lock scope must be
 * closed with an {@link EndLockScopeNode}. The frame state after this node denotes that the object
 * is locked (ensuring the GC sees and updates the object) so it must come after any null pointer
 * check on the object.
 */
@NodeInfo(allowedUsageTypes = Memory, cycles = CYCLES_2, size = SIZE_1)
public final class BeginLockScopeNode extends AbstractMemoryCheckpoint implements LIRLowerable, MonitorEnter, MemoryCheckpoint.Single {

    public static final NodeClass<BeginLockScopeNode> TYPE = NodeClass.create(BeginLockScopeNode.class);
    protected int lockDepth;

    public BeginLockScopeNode(@InjectedNodeParameter WordTypes wordTypes, int lockDepth) {
        super(TYPE, StampFactory.forKind(wordTypes.getWordKind()));
        this.lockDepth = lockDepth;
    }

    @Override
    public boolean hasSideEffect() {
        return false;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return LocationIdentity.any();
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert lockDepth != -1;
        HotSpotLIRGenerator hsGen = (HotSpotLIRGenerator) gen.getLIRGeneratorTool();
        VirtualStackSlot slot = hsGen.getLockSlot(lockDepth);
        Value result = gen.getLIRGeneratorTool().emitAddress(slot);
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Word beginLockScope(@ConstantNodeParameter int lockDepth);
}
