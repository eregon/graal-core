/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.nodeinfo.NodeCycles.CYCLES_50;
import static com.oracle.graal.nodeinfo.NodeSize.SIZE_50;

import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.DeoptimizingNode;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.memory.address.AddressNode;

@NodeInfo(cycles = CYCLES_50, size = SIZE_50)
public final class G1PreWriteBarrier extends ObjectWriteBarrier implements DeoptimizingNode.DeoptBefore {

    public static final NodeClass<G1PreWriteBarrier> TYPE = NodeClass.create(G1PreWriteBarrier.class);

    @OptionalInput(InputType.State) FrameState stateBefore;
    protected final boolean nullCheck;
    protected final boolean doLoad;

    public G1PreWriteBarrier(AddressNode address, ValueNode expectedObject, boolean doLoad, boolean nullCheck) {
        super(TYPE, address, expectedObject, true);
        this.doLoad = doLoad;
        this.nullCheck = nullCheck;
    }

    public ValueNode getExpectedObject() {
        return getValue();
    }

    public boolean doLoad() {
        return doLoad;
    }

    public boolean getNullCheck() {
        return nullCheck;
    }

    @Override
    public boolean canDeoptimize() {
        return nullCheck;
    }

    @Override
    public FrameState stateBefore() {
        return stateBefore;
    }

    @Override
    public void setStateBefore(FrameState state) {
        updateUsages(stateBefore, state);
        stateBefore = state;
    }
}
