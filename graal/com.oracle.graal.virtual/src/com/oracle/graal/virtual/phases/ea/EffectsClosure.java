/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.virtual.phases.ea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.oracle.graal.compiler.common.CollectionsFactory;
import com.oracle.graal.compiler.common.cfg.BlockMap;
import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.GraalError;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeBitMap;
import com.oracle.graal.graph.NodeMap;
import com.oracle.graal.graph.iterators.NodeIterable;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.LogicConstantNode;
import com.oracle.graal.nodes.LogicNode;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.LoopExitNode;
import com.oracle.graal.nodes.PhiNode;
import com.oracle.graal.nodes.ProxyNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.ScheduleResult;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.ValuePhiNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.cfg.ControlFlowGraph;
import com.oracle.graal.nodes.extended.BoxNode;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.nodes.virtual.AllocatedObjectNode;
import com.oracle.graal.nodes.virtual.CommitAllocationNode;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;
import com.oracle.graal.phases.graph.ReentrantBlockIterator;
import com.oracle.graal.phases.graph.ReentrantBlockIterator.BlockIteratorClosure;
import com.oracle.graal.phases.graph.ReentrantBlockIterator.LoopInfo;

public abstract class EffectsClosure<BlockT extends EffectsBlockState<BlockT>> extends EffectsPhase.Closure<BlockT> {

    protected final ControlFlowGraph cfg;
    protected final ScheduleResult schedule;

    protected final NodeMap<ValueNode> aliases;
    protected final BlockMap<GraphEffectList> blockEffects;
    private final Map<Loop<Block>, GraphEffectList> loopMergeEffects = CollectionsFactory.newIdentityMap();
    private final Map<LoopBeginNode, BlockT> loopEntryStates = Node.newIdentityMap();
    private final NodeBitMap hasScalarReplacedInputs;

    protected boolean changed;

    public EffectsClosure(ScheduleResult schedule, ControlFlowGraph cfg) {
        this.schedule = schedule;
        this.cfg = cfg;
        this.aliases = cfg.graph.createNodeMap();
        this.hasScalarReplacedInputs = cfg.graph.createNodeBitMap();
        this.blockEffects = new BlockMap<>(cfg);
        for (Block block : cfg.getBlocks()) {
            blockEffects.put(block, new GraphEffectList());
        }
    }

    @Override
    public boolean hasChanged() {
        return changed;
    }

    @Override
    public void applyEffects() {
        final StructuredGraph graph = cfg.graph;
        final ArrayList<Node> obsoleteNodes = new ArrayList<>(0);
        final ArrayList<GraphEffectList> effectList = new ArrayList<>();
        BlockIteratorClosure<Void> closure = new BlockIteratorClosure<Void>() {

            @Override
            protected Void getInitialState() {
                return null;
            }

            private void apply(GraphEffectList effects) {
                if (effects != null && !effects.isEmpty()) {
                    effectList.add(effects);
                }
            }

            @Override
            protected Void processBlock(Block block, Void currentState) {
                apply(blockEffects.get(block));
                return currentState;
            }

            @Override
            protected Void merge(Block merge, List<Void> states) {
                return null;
            }

            @Override
            protected Void cloneState(Void oldState) {
                return oldState;
            }

            @Override
            protected List<Void> processLoop(Loop<Block> loop, Void initialState) {
                LoopInfo<Void> info = ReentrantBlockIterator.processLoop(this, loop, initialState);
                apply(loopMergeEffects.get(loop));
                return info.exitStates;
            }
        };
        ReentrantBlockIterator.apply(closure, cfg.getStartBlock());
        for (GraphEffectList effects : effectList) {
            Debug.log(" ==== effects");
            effects.apply(graph, obsoleteNodes, false);
        }
        for (GraphEffectList effects : effectList) {
            Debug.log(" ==== cfg kill effects");
            effects.apply(graph, obsoleteNodes, true);
        }
        Debug.dump(Debug.VERBOSE_LOG_LEVEL, graph, "After applying effects");
        assert VirtualUtil.assertNonReachable(graph, obsoleteNodes);
        for (Node node : obsoleteNodes) {
            if (node.isAlive()) {
                node.replaceAtUsages(null);
                GraphUtil.killWithUnusedFloatingInputs(node);
            }
        }
    }

    @Override
    protected BlockT processBlock(Block block, BlockT state) {
        if (!state.isDead()) {
            GraphEffectList effects = blockEffects.get(block);

            if (block.getBeginNode().predecessor() instanceof IfNode) {
                IfNode ifNode = (IfNode) block.getBeginNode().predecessor();
                LogicNode condition = ifNode.condition();
                Node alias = getScalarAlias(condition);
                if (alias instanceof LogicConstantNode) {
                    LogicConstantNode constant = (LogicConstantNode) alias;
                    boolean deadBranch = constant.getValue() != (block.getBeginNode() == ifNode.trueSuccessor());

                    if (deadBranch) {
                        state.markAsDead();
                        effects.killIfBranch(ifNode, constant.getValue());
                        return state;
                    }
                }
            }

            VirtualUtil.trace("\nBlock: %s, preds: %s, succ: %s (", block, block.getPredecessors(), block.getSuccessors());

            FixedWithNextNode lastFixedNode = block.getBeginNode().predecessor() instanceof FixedWithNextNode ? (FixedWithNextNode) block.getBeginNode().predecessor() : null;
            Iterable<? extends Node> nodes = schedule != null ? schedule.getBlockToNodesMap().get(block) : block.getNodes();
            for (Node node : nodes) {
                aliases.set(node, null);
                if (node instanceof LoopExitNode) {
                    LoopExitNode loopExit = (LoopExitNode) node;
                    for (ProxyNode proxy : loopExit.proxies()) {
                        aliases.set(proxy, null);
                        changed |= processNode(proxy, state, effects, lastFixedNode) && isSignificantNode(node);
                    }
                    processLoopExit(loopExit, loopEntryStates.get(loopExit.loopBegin()), state, blockEffects.get(block));
                }
                changed |= processNode(node, state, effects, lastFixedNode) && isSignificantNode(node);
                if (node instanceof FixedWithNextNode) {
                    lastFixedNode = (FixedWithNextNode) node;
                }
                if (state.isDead()) {
                    break;
                }
            }
            VirtualUtil.trace(")\n    end state: %s\n", state);
        }
        return state;
    }

    private static boolean isSignificantNode(Node node) {
        return !(node instanceof CommitAllocationNode || node instanceof AllocatedObjectNode || node instanceof BoxNode);
    }

    /**
     * Collects the effects of virtualizing the given node.
     *
     * @return {@code true} if the effects include removing the node, {@code false} otherwise.
     */
    protected abstract boolean processNode(Node node, BlockT state, GraphEffectList effects, FixedWithNextNode lastFixedNode);

    @Override
    protected BlockT merge(Block merge, List<BlockT> states) {
        assert blockEffects.get(merge).isEmpty();
        MergeProcessor processor = createMergeProcessor(merge);
        doMergeWithoutDead(processor, states);
        processor.commitEnds(states);
        blockEffects.get(merge).addAll(processor.mergeEffects);
        blockEffects.get(merge).addAll(processor.afterMergeEffects);
        return processor.newState;
    }

    @Override
    protected final List<BlockT> processLoop(Loop<Block> loop, BlockT initialState) {
        if (initialState.isDead()) {
            ArrayList<BlockT> states = new ArrayList<>();
            for (int i = 0; i < loop.getExits().size(); i++) {
                states.add(initialState);
            }
            return states;
        }

        BlockT loopEntryState = initialState;
        BlockT lastMergedState = cloneState(initialState);
        processInitialLoopState(loop, lastMergedState);
        MergeProcessor mergeProcessor = createMergeProcessor(loop.getHeader());
        for (int iteration = 0; iteration < 10; iteration++) {
            LoopInfo<BlockT> info = ReentrantBlockIterator.processLoop(this, loop, cloneState(lastMergedState));

            List<BlockT> states = new ArrayList<>();
            states.add(initialState);
            states.addAll(info.endStates);
            doMergeWithoutDead(mergeProcessor, states);

            Debug.log("================== %s", loop.getHeader());
            Debug.log("%s", mergeProcessor.newState);
            Debug.log("===== vs.");
            Debug.log("%s", lastMergedState);

            if (mergeProcessor.newState.equivalentTo(lastMergedState)) {
                mergeProcessor.commitEnds(states);

                blockEffects.get(loop.getHeader()).insertAll(mergeProcessor.mergeEffects, 0);
                loopMergeEffects.put(loop, mergeProcessor.afterMergeEffects);

                assert info.exitStates.size() == loop.getExits().size();
                loopEntryStates.put((LoopBeginNode) loop.getHeader().getBeginNode(), loopEntryState);
                assert assertExitStatesNonEmpty(loop, info);

                return info.exitStates;
            } else {
                lastMergedState = mergeProcessor.newState;
                for (Block block : loop.getBlocks()) {
                    blockEffects.get(block).clear();
                }
            }
        }
        throw new GraalError("too many iterations at %s", loop);
    }

    @SuppressWarnings("unused")
    protected void processInitialLoopState(Loop<Block> loop, BlockT initialState) {
        // nothing to do
    }

    private void doMergeWithoutDead(MergeProcessor mergeProcessor, List<BlockT> states) {
        int alive = 0;
        for (BlockT state : states) {
            if (!state.isDead()) {
                alive++;
            }
        }
        if (alive == 0) {
            mergeProcessor.setNewState(states.get(0));
        } else if (alive == states.size()) {
            int[] stateIndexes = new int[states.size()];
            for (int i = 0; i < stateIndexes.length; i++) {
                stateIndexes[i] = i;
            }
            mergeProcessor.setStateIndexes(stateIndexes);
            mergeProcessor.merge(states);
        } else {
            ArrayList<BlockT> aliveStates = new ArrayList<>(alive);
            int[] stateIndexes = new int[alive];
            for (int i = 0; i < states.size(); i++) {
                if (!states.get(i).isDead()) {
                    stateIndexes[aliveStates.size()] = i;
                    aliveStates.add(states.get(i));
                }
            }
            mergeProcessor.setStateIndexes(stateIndexes);
            mergeProcessor.merge(aliveStates);
        }
    }

    private boolean assertExitStatesNonEmpty(Loop<Block> loop, LoopInfo<BlockT> info) {
        for (int i = 0; i < loop.getExits().size(); i++) {
            assert info.exitStates.get(i) != null : "no loop exit state at " + loop.getExits().get(i) + " / " + loop.getHeader();
        }
        return true;
    }

    protected abstract void processLoopExit(LoopExitNode exitNode, BlockT initialState, BlockT exitState, GraphEffectList effects);

    protected abstract MergeProcessor createMergeProcessor(Block merge);

    protected class MergeProcessor {

        private final Block mergeBlock;
        private final AbstractMergeNode merge;

        protected final GraphEffectList mergeEffects;
        protected final GraphEffectList afterMergeEffects;

        private int[] stateIndexes;
        protected BlockT newState;

        public MergeProcessor(Block mergeBlock) {
            this.mergeBlock = mergeBlock;
            this.merge = (AbstractMergeNode) mergeBlock.getBeginNode();
            this.mergeEffects = new GraphEffectList();
            this.afterMergeEffects = new GraphEffectList();
        }

        /**
         * @param states the states that should be merged.
         */
        protected void merge(List<BlockT> states) {
            setNewState(getInitialState());
        }

        private void setNewState(BlockT state) {
            newState = state;
            mergeEffects.clear();
            afterMergeEffects.clear();
        }

        private void setStateIndexes(int[] stateIndexes) {
            this.stateIndexes = stateIndexes;
        }

        @SuppressWarnings("unused")
        protected void commitEnds(List<BlockT> states) {
        }

        protected final Block getPredecessor(int index) {
            return mergeBlock.getPredecessors()[stateIndexes[index]];
        }

        protected final NodeIterable<PhiNode> getPhis() {
            return merge.phis();
        }

        protected final ValueNode getPhiValueAt(PhiNode phi, int index) {
            return phi.valueAt(stateIndexes[index]);
        }

        protected final ValuePhiNode createValuePhi(Stamp stamp) {
            return new ValuePhiNode(stamp, merge, new ValueNode[mergeBlock.getPredecessorCount()]);
        }

        protected final void setPhiInput(PhiNode phi, int index, ValueNode value) {
            afterMergeEffects.initializePhiInput(phi, stateIndexes[index], value);
        }

        protected final int getStateIndex(int i) {
            return stateIndexes[i];
        }

        protected final StructuredGraph graph() {
            return merge.graph();
        }

        @Override
        public String toString() {
            return "MergeProcessor@" + merge;
        }
    }

    public void addScalarAlias(ValueNode node, ValueNode alias) {
        assert !(alias instanceof VirtualObjectNode);
        aliases.set(node, alias);
        for (Node usage : node.usages()) {
            if (!hasScalarReplacedInputs.isNew(usage)) {
                hasScalarReplacedInputs.mark(usage);
            }
        }
    }

    protected final boolean hasScalarReplacedInputs(Node node) {
        return hasScalarReplacedInputs.isMarked(node);
    }

    public ValueNode getScalarAlias(ValueNode node) {
        assert !(node instanceof VirtualObjectNode);
        if (node == null || !node.isAlive() || aliases.isNew(node)) {
            return node;
        }
        ValueNode result = aliases.get(node);
        return (result == null || result instanceof VirtualObjectNode) ? node : result;
    }
}
