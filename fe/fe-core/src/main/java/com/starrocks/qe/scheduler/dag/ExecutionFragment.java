// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.qe.scheduler.dag;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.planner.ExchangeNode;
import com.starrocks.planner.JoinNode;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.PlanFragment;
import com.starrocks.planner.PlanFragmentId;
import com.starrocks.planner.PlanNode;
import com.starrocks.planner.PlanNodeId;
import com.starrocks.planner.RuntimeFilterDescription;
import com.starrocks.planner.ScanNode;
import com.starrocks.planner.SetOperationNode;
import com.starrocks.qe.ColocatedBackendSelector;
import com.starrocks.qe.CoordinatorPreprocessor;
import com.starrocks.qe.FragmentScanRangeAssignment;
import com.starrocks.qe.scheduler.ExplainBuilder;
import com.starrocks.thrift.TEsScanRange;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TInternalScanRange;
import com.starrocks.thrift.TPlanFragmentDestination;
import com.starrocks.thrift.TRuntimeFilterParams;
import com.starrocks.thrift.TScanRangeParams;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static com.starrocks.qe.scheduler.dag.FragmentInstance.ABSENT_DRIVER_SEQUENCE;

/**
 * An {@code ExecutionFragment} is a part of the {@link ExecutionDAG}, and it corresponds one-to-one with a {@link PlanFragment}.
 *
 * <p>The {@code ExecutionFragment} represents a collection of multiple parallel instances of a {@link PlanFragment}.
 * It contains a {@link FragmentInstance} for each parallel instance.
 * Additionally, it includes information about the source and sink, including assigned scan ranges
 * and destinations to the downstream.
 */
public class ExecutionFragment {
    private final ExecutionDAG executionDAG;
    private final int fragmentIndex;
    private final PlanFragment planFragment;
    private final Map<PlanNodeId, ScanNode> scanNodes;

    private final List<TPlanFragmentDestination> destinations;
    private final Map<Integer, Integer> numSendersPerExchange;

    private final List<FragmentInstance> instances;

    private final FragmentScanRangeAssignment scanRangeAssignment;
    private ColocatedBackendSelector.Assignment colocatedAssignment = null;

    public static class BucketSeqAssignment {
        public List<Integer> bucketSeqToInstance;
        public List<Integer> bucketSeqToDriverSeq;
        public List<Integer> bucketSeqToPartition;
    }
    private BucketSeqAssignment cachedBucketSeqAssignment = null;
    private boolean bucketSeqToInstanceForFilterIsSet = false;

    private final TRuntimeFilterParams runtimeFilterParams = new TRuntimeFilterParams();

    private Boolean cachedIsColocated = null;
    private Boolean cachedIsReplicated = null;
    private Boolean cachedIsLocalBucketShuffleJoin = null;

    private Boolean cachedIsColocateSet = null;

    private boolean isRightOrFullBucketShuffle = false;
    // used for phased schedule
    private boolean isScheduled = false;
    private boolean needReportFragmentFinish = false;

    public ExecutionFragment(ExecutionDAG executionDAG, PlanFragment planFragment, int fragmentIndex) {
        this.executionDAG = executionDAG;
        this.fragmentIndex = fragmentIndex;
        this.planFragment = planFragment;
        this.scanNodes = planFragment.collectScanNodes();

        this.destinations = Lists.newArrayList();
        this.numSendersPerExchange = Maps.newHashMap();

        this.instances = Lists.newArrayList();
        this.scanRangeAssignment = new FragmentScanRangeAssignment();
    }

    public String getExplainString() {
        ExplainBuilder builder = new ExplainBuilder();
        builder.addValue(String.format("PLAN FRAGMENT %d(%s)", fragmentIndex, getFragmentId()), () -> {
            builder.addValue("DOP", planFragment.getPipelineDop());
            builder.addValue("INSTANCES", () -> instances.forEach(instance -> instance.buildExplainString(builder)));
        });
        return builder.build();
    }

    public int getFragmentIndex() {
        return fragmentIndex;
    }

    public PlanFragment getPlanFragment() {
        return planFragment;
    }

    public PlanFragmentId getFragmentId() {
        return planFragment.getFragmentId();
    }

    public Collection<ScanNode> getScanNodes() {
        return scanNodes.values();
    }

    public ScanNode getScanNode(PlanNodeId scanId) {
        return scanNodes.get(scanId);
    }

    public ExecutionDAG getExecutionDAG() {
        return executionDAG;
    }

    public void setLayoutInfosForRuntimeFilters() {
        if (bucketSeqToInstanceForFilterIsSet) {
            return;
        }
        bucketSeqToInstanceForFilterIsSet = true;

        BucketSeqAssignment bucketSeqAssignment = getBucketSeqAssignment();
        for (RuntimeFilterDescription rf : planFragment.getBuildRuntimeFilters().values()) {
            rf.setNumInstances(instances.size());
            rf.setNumDriversPerInstance(planFragment.getPipelineDop());

            if (bucketSeqAssignment == null) {
                return;
            }
            rf.setBucketSeqToInstance(bucketSeqAssignment.bucketSeqToInstance);
            rf.setBucketSeqToDriverSeq(bucketSeqAssignment.bucketSeqToDriverSeq);
            rf.setBucketSeqToPartition(bucketSeqAssignment.bucketSeqToPartition);
        }
    }

    public FragmentScanRangeAssignment getScanRangeAssignment() {
        return scanRangeAssignment;
    }

    public ColocatedBackendSelector.Assignment getColocatedAssignment() {
        return colocatedAssignment;
    }

    public ColocatedBackendSelector.Assignment getOrCreateColocatedAssignment(ScanNode scanNode)
            throws StarRocksException {
        if (colocatedAssignment == null) {
            final int numScanNodes = scanNodes.size();
            ColocatedBackendSelector.Assignment.ScanRangeType type;
            if (scanNode instanceof OlapScanNode olapScanNode) {
                type = ColocatedBackendSelector.Assignment.ScanRangeType.NATIVE;
            } else {
                type = ColocatedBackendSelector.Assignment.ScanRangeType.NONNATIVE;
            }

            int bucketNum = scanNode.getBucketNums();
            colocatedAssignment = new ColocatedBackendSelector.Assignment(bucketNum, numScanNodes, type);
        }
        return colocatedAssignment;
    }

    public int getBucketNum() {
        if (colocatedAssignment == null) {
            return 0;
        }
        return colocatedAssignment.getBucketNum();
    }

    public BucketSeqAssignment getBucketSeqAssignment() {
        if (cachedBucketSeqAssignment != null) {
            return cachedBucketSeqAssignment;
        }

        if (colocatedAssignment == null) {
            return null;
        }

        int numBuckets = getBucketNum();
        Integer[] bucketSeqToInstance = new Integer[numBuckets];
        Integer[] bucketSeqToDriverSeq = new Integer[numBuckets];
        Integer[] bucketSeqToPartition = new Integer[numBuckets];
        // some buckets are pruned, so set the corresponding instance ordinal to BUCKET_ABSENT to indicate
        // absence of buckets.
        Arrays.fill(bucketSeqToInstance, CoordinatorPreprocessor.BUCKET_ABSENT);
        Arrays.fill(bucketSeqToDriverSeq, CoordinatorPreprocessor.BUCKET_ABSENT);
        Arrays.fill(bucketSeqToPartition, CoordinatorPreprocessor.BUCKET_ABSENT);
        boolean assignBucketsAmongDrivers =
                instances.stream().flatMap(instance -> instance.getBucketSeqToDriverSeq().values().stream())
                        .noneMatch(driverSeq -> driverSeq.equals(ABSENT_DRIVER_SEQUENCE));

        int nextPartition = 0;
        for (FragmentInstance instance : instances) {
            if (assignBucketsAmongDrivers) {
                List<Map.Entry<Integer, Integer>> sortedBucketSeqToDriverSeq =
                        instance.getBucketSeqToDriverSeq().entrySet().stream()
                                .sorted(Comparator.comparingInt(Map.Entry::getValue)).collect(Collectors.toList());
                for (Map.Entry<Integer, Integer> entry : sortedBucketSeqToDriverSeq) {
                    Integer bucketSeq = entry.getKey();
                    Integer driverSeq = entry.getValue();
                    Preconditions.checkState(bucketSeq < numBuckets,
                            "bucketSeq exceeds bucketNum in colocate Fragment");
                    bucketSeqToInstance[bucketSeq] = instance.getIndexInFragment();
                    bucketSeqToDriverSeq[bucketSeq] = driverSeq;
                    bucketSeqToPartition[bucketSeq] = nextPartition++;
                }
            } else {
                for (Integer bucketSeq : instance.getBucketSeqs()) {
                    Preconditions.checkState(bucketSeq < numBuckets,
                            "bucketSeq exceeds bucketNum in colocate Fragment");
                    bucketSeqToInstance[bucketSeq] = instance.getIndexInFragment();
                }
            }
        }

        cachedBucketSeqAssignment = new BucketSeqAssignment();
        cachedBucketSeqAssignment.bucketSeqToInstance = Arrays.asList(bucketSeqToInstance);
        if (assignBucketsAmongDrivers) {
            cachedBucketSeqAssignment.bucketSeqToDriverSeq = Arrays.asList(bucketSeqToDriverSeq);
            cachedBucketSeqAssignment.bucketSeqToPartition = Arrays.asList(bucketSeqToPartition);
        }
        return cachedBucketSeqAssignment;
    }

    public void addDestination(TPlanFragmentDestination destination) {
        destinations.add(destination);
    }

    public List<TPlanFragmentDestination> getDestinations() {
        return destinations;
    }

    public int childrenSize() {
        return planFragment.getChildren().size();
    }

    public ExecutionFragment getChild(int i) {
        return executionDAG.getFragment(planFragment.getChild(i).getFragmentId());
    }

    public List<FragmentInstance> getInstances() {
        return instances;
    }

    public void addInstance(FragmentInstance instance) {
        instance.setIndexInFragment(instances.size());
        instances.add(instance);
    }

    public void shuffleInstances(Random random) {
        Collections.shuffle(instances, random);
        for (int i = 0; i < instances.size(); i++) {
            instances.get(i).setIndexInFragment(i);
        }
    }

    public PlanNode getLeftMostNode() {
        PlanNode node = planFragment.getPlanRoot();
        while (!node.getChildren().isEmpty() && !(node instanceof ExchangeNode)) {
            node = node.getChild(0);
        }
        return node;
    }

    public Map<Integer, Integer> getNumSendersPerExchange() {
        return numSendersPerExchange;
    }

    public TRuntimeFilterParams getRuntimeFilterParams() {
        return runtimeFilterParams;
    }

    public boolean isColocated() {
        if (cachedIsColocated != null) {
            return cachedIsColocated;
        }

        cachedIsColocated = isColocated(planFragment.getPlanRoot());
        return cachedIsColocated;
    }

    public boolean isReplicated() {
        if (cachedIsReplicated != null) {
            return cachedIsReplicated;
        }

        cachedIsReplicated = isReplicated(planFragment.getPlanRoot());
        return cachedIsReplicated;
    }

    public boolean isLocalBucketShuffleJoin() {
        if (cachedIsLocalBucketShuffleJoin != null) {
            return cachedIsLocalBucketShuffleJoin;
        }

        cachedIsLocalBucketShuffleJoin = isLocalBucketShuffleJoin(planFragment.getPlanRoot());
        return cachedIsLocalBucketShuffleJoin;
    }

    private boolean isColocateSet(PlanNode root) {
        if (root instanceof ExchangeNode) {
            return false;
        }
        if (root instanceof SetOperationNode) {
            return root.isColocate();
        }
        return root.getChildren().stream().anyMatch(this::isColocateSet);
    }

    public boolean isColocateSet() {
        if (cachedIsColocateSet == null) {
            cachedIsColocateSet = isColocateSet(planFragment.getPlanRoot());
        }
        return cachedIsColocateSet;
    }

    public boolean isRightOrFullBucketShuffle() {
        isLocalBucketShuffleJoin(); // isRightOrFullBucketShuffle is calculated when calculating isBucketShuffleJoin.
        return isRightOrFullBucketShuffle;
    }

    // Append range information
    // [tablet_id(version),tablet_id(version)]
    public void appendScanRange(StringBuilder sb, List<TScanRangeParams> params) {
        sb.append("range=[");
        int idx = 0;
        for (TScanRangeParams range : params) {
            TInternalScanRange internalScanRange = range.getScan_range().getInternal_scan_range();
            if (internalScanRange != null) {
                if (idx++ != 0) {
                    sb.append(",");
                }
                sb.append("{tid=").append(internalScanRange.getTablet_id())
                        .append(",ver=").append(internalScanRange.getVersion()).append("}");
            }
            TEsScanRange esScanRange = range.getScan_range().getEs_scan_range();
            if (esScanRange != null) {
                sb.append("{ index=").append(esScanRange.getIndex())
                        .append(", shardid=").append(esScanRange.getShard_id())
                        .append("}");
            }
            THdfsScanRange hdfsScanRange = range.getScan_range().getHdfs_scan_range();
            if (hdfsScanRange != null) {
                sb.append("{relative_path=").append(hdfsScanRange.getRelative_path())
                        .append(", offset=").append(hdfsScanRange.getOffset())
                        .append(", length=").append(hdfsScanRange.getLength())
                        .append("}");
            }
        }
        sb.append("]");
    }

    public void appendTo(StringBuilder sb) {
        // append fragment
        sb.append("{plan=");
        planFragment.getPlanRoot().appendTrace(sb);
        sb.append(",instance=[");
        // append instance
        for (int i = 0; i < instances.size(); ++i) {
            if (i != 0) {
                sb.append(",");
            }

            FragmentInstance instance = instances.get(i);

            Map<Integer, List<TScanRangeParams>> scanRanges =
                    scanRangeAssignment.get(instance.getWorkerId());
            sb.append("{");
            sb.append("id=").append(DebugUtil.printId(instance.getInstanceId()));
            sb.append(",host=").append(instance.getWorker().getAddress());
            if (scanRanges == null) {
                sb.append("}");
                continue;
            }
            sb.append(",range=[");
            int eIdx = 0;
            for (Map.Entry<Integer, List<TScanRangeParams>> entry : scanRanges.entrySet()) {
                if (eIdx++ != 0) {
                    sb.append(",");
                }
                sb.append("id").append(entry.getKey()).append(",");
                appendScanRange(sb, entry.getValue());
            }
            sb.append("]");
            sb.append("}");
        }
        sb.append("]"); // end of instances
        sb.append("}");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        appendTo(sb);
        return sb.toString();
    }

    private boolean isColocated(PlanNode root) {
        if (root instanceof ExchangeNode) {
            return false;
        }

        if (root.isColocate()) {
            return true;
        }

        if (root.isReplicated()) {
            // Only check left if node is replicate join
            return isColocated(root.getChild(0));
        } else {
            return root.getChildren().stream().anyMatch(this::isColocated);
        }
    }

    public boolean isRuntimeFilterCoordinator() {
        return runtimeFilterParams.isSetRuntime_filter_builder_number();
    }

    private boolean isReplicated(PlanNode root) {
        if (root instanceof ExchangeNode) {
            return false;
        }

        if (root.isReplicated()) {
            return true;
        }

        return root.getChildren().stream().anyMatch(PlanNode::isReplicated);
    }

    private boolean isLocalBucketShuffleJoin(PlanNode root) {
        if (root instanceof ExchangeNode) {
            return false;
        }

        boolean hasBucketShuffle = false;
        if (root instanceof JoinNode) {
            JoinNode joinNode = (JoinNode) root;
            if (joinNode.isLocalHashBucket()) {
                hasBucketShuffle = true;
                isRightOrFullBucketShuffle |= joinNode.getJoinOp().isFullOuterJoin() || joinNode.getJoinOp().isRightJoin();
            }
        }

        for (PlanNode child : root.getChildren()) {
            hasBucketShuffle |= isLocalBucketShuffleJoin(child);
        }

        return hasBucketShuffle;
    }

    public boolean isScheduled() {
        return isScheduled;
    }

    public void setIsScheduled(boolean isScheduled) {
        this.isScheduled = isScheduled;
    }

    public boolean isNeedReportFragmentFinish() {
        return needReportFragmentFinish;
    }
    public void setNeedReportFragmentFinish(boolean needReportFragmentFinish) {
        this.needReportFragmentFinish = needReportFragmentFinish;
    }
}
