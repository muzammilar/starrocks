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

package com.starrocks.qe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.hash.Funnel;
import com.google.common.hash.Hashing;
import com.google.common.hash.PrimitiveSink;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.profile.Tracers;
import com.starrocks.common.util.ConsistentHashRing;
import com.starrocks.common.util.HashRing;
import com.starrocks.common.util.RendezvousHashRing;
import com.starrocks.planner.DeltaLakeScanNode;
import com.starrocks.planner.FileTableScanNode;
import com.starrocks.planner.HdfsScanNode;
import com.starrocks.planner.HudiScanNode;
import com.starrocks.planner.IcebergMetadataScanNode;
import com.starrocks.planner.IcebergScanNode;
import com.starrocks.planner.OdpsScanNode;
import com.starrocks.planner.PaimonScanNode;
import com.starrocks.planner.ScanNode;
import com.starrocks.qe.scheduler.CandidateWorkerProvider;
import com.starrocks.qe.scheduler.NonRecoverableException;
import com.starrocks.qe.scheduler.WorkerProvider;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.plan.HDFSScanNodePredicates;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.HistoricalNodeMgr;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocation;
import com.starrocks.thrift.TScanRangeLocations;
import com.starrocks.thrift.TScanRangeParams;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Hybrid backend selector for hive table.
 * Support hybrid and independent deployment with datanode.
 * <p>
 * Assign scan ranges to backend:
 * 1. local backend first,
 * 2. and smallest assigned scan ranges num or scan bytes.
 * <p>
 * If force_schedule_local variable is set, HybridBackendSelector will force to
 * assign scan ranges to local backend if there has one.
 */

public class HDFSBackendSelector implements BackendSelector {
    public static final Logger LOG = LogManager.getLogger(HDFSBackendSelector.class);
    // be -> assigned scans
    Map<ComputeNode, Long> assignedScansPerComputeNode = Maps.newHashMap();
    // be -> re-balance bytes
    Map<ComputeNode, Long> reBalanceBytesPerComputeNode = Maps.newHashMap();
    private final ScanNode scanNode;
    private final List<TScanRangeLocations> locations;
    private final FragmentScanRangeAssignment assignment;
    private final WorkerProvider workerProvider;
    private final WorkerProvider candidateWorkerProvider;
    private final ConnectContext connectContext;
    private final boolean forceScheduleLocal;
    private final boolean shuffleScanRange;
    private final boolean useIncrementalScanRanges;
    private final int kCandidateNumber = 3;
    // After testing, this value can ensure that the scan range size assigned to each BE is as uniform as possible,
    // and the largest scan data is not more than 1.1 times of the average value
    private final double kMaxImbalanceRatio = 1.1;
    public static final int CONSISTENT_HASH_RING_VIRTUAL_NUMBER = 256;

    class HdfsScanRangeHasher {
        String basePath;
        HDFSScanNodePredicates predicates;

        public HdfsScanRangeHasher() {
            if (scanNode instanceof HdfsScanNode) {
                HdfsScanNode node = (HdfsScanNode) scanNode;
                predicates = node.getScanNodePredicates();
                basePath = node.getHiveTable().getTableLocation();
            } else if (scanNode instanceof IcebergScanNode) {
                IcebergScanNode node = (IcebergScanNode) scanNode;
                predicates = node.getScanNodePredicates();
            } else if (scanNode instanceof HudiScanNode) {
                HudiScanNode node = (HudiScanNode) scanNode;
                predicates = node.getScanNodePredicates();
                basePath = node.getHudiTable().getTableLocation();
            } else if (scanNode instanceof DeltaLakeScanNode) {
                DeltaLakeScanNode node = (DeltaLakeScanNode) scanNode;
                predicates = node.getScanNodePredicates();
                basePath = node.getDeltaLakeTable().getTableLocation();
            } else if (scanNode instanceof FileTableScanNode) {
                FileTableScanNode node = (FileTableScanNode) scanNode;
                predicates = node.getScanNodePredicates();
                basePath = node.getFileTable().getTableLocation();
            } else if (scanNode instanceof PaimonScanNode) {
                PaimonScanNode node = (PaimonScanNode) scanNode;
                predicates = node.getScanNodePredicates();
                basePath = node.getPaimonTable().getTableLocation();
            } else if (scanNode instanceof OdpsScanNode) {
                OdpsScanNode node = (OdpsScanNode) scanNode;
                predicates = node.getScanNodePredicates();
            } else if (scanNode instanceof IcebergMetadataScanNode) {
                // ignored
            } else {
                Preconditions.checkState(false);
            }
        }

        public void acceptScanRangeLocations(TScanRangeLocations tScanRangeLocations, PrimitiveSink primitiveSink) {
            THdfsScanRange hdfsScanRange = tScanRangeLocations.scan_range.hdfs_scan_range;
            if (hdfsScanRange.isSetFull_path()) {
                primitiveSink.putString(hdfsScanRange.full_path, StandardCharsets.UTF_8);
            } else {
                if (hdfsScanRange.isSetPartition_id() &&
                        predicates.getIdToPartitionKey().containsKey(hdfsScanRange.getPartition_id())) {
                    PartitionKey partitionKey = predicates.getIdToPartitionKey().get(hdfsScanRange.getPartition_id());
                    primitiveSink.putInt(partitionKey.hashCode());
                }
                if (hdfsScanRange.isSetRelative_path()) {
                    primitiveSink.putString(hdfsScanRange.relative_path, StandardCharsets.UTF_8);
                }
            }
            if (hdfsScanRange.isSetOffset()) {
                primitiveSink.putLong(hdfsScanRange.getOffset());
            }
        }
    }

    private final HdfsScanRangeHasher hdfsScanRangeHasher;

    public HDFSBackendSelector(ScanNode scanNode, List<TScanRangeLocations> locations,
                               FragmentScanRangeAssignment assignment, WorkerProvider workerProvider,
                               boolean forceScheduleLocal,
                               boolean shuffleScanRange,
                               boolean useIncrementalScanRanges,
                               ConnectContext connectContext) {
        this.scanNode = scanNode;
        this.locations = locations;
        this.assignment = assignment;
        this.workerProvider = workerProvider;
        this.forceScheduleLocal = forceScheduleLocal;
        this.connectContext = connectContext;
        this.hdfsScanRangeHasher = new HdfsScanRangeHasher();
        this.shuffleScanRange = shuffleScanRange;
        this.useIncrementalScanRanges = useIncrementalScanRanges;
        this.candidateWorkerProvider = initCandidateWorkerProvider();
    }

    private WorkerProvider initCandidateWorkerProvider() {
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        if (!sessionVariable.isEnableDataCacheSharing() ||
                isCacheSharingExpired(sessionVariable.getDataCacheSharingWorkPeriod())) {
            return null;
        }

        WorkerProvider.Factory factory = new CandidateWorkerProvider.Factory();
        WorkerProvider candidateWorkerProvider = factory.captureAvailableWorkers(
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                sessionVariable.isPreferComputeNode(), sessionVariable.getUseComputeNodes(),
                sessionVariable.getComputationFragmentSchedulingPolicy(), workerProvider.getComputeResource());
        return candidateWorkerProvider;
    }

    private boolean isCacheSharingExpired(long cacheSharingWorkPeriod) {
        HistoricalNodeMgr historicalNodeMgr = GlobalStateMgr.getCurrentState().getHistoricalNodeMgr();
        ComputeResource computeResource = workerProvider.getComputeResource();

        long lastUpdateTime = historicalNodeMgr.getLastUpdateTime(computeResource.getWarehouseId(),
                computeResource.getWorkerGroupId());
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime > cacheSharingWorkPeriod * 1000) {
            return true;
        }
        return false;
    }

    // re-balance scan ranges for compute node if needed, return the compute node which scan range is assigned to
    private ComputeNode reBalanceScanRangeForComputeNode(List<ComputeNode> backends, long avgNodeScanRangeBytes,
                                                         TScanRangeLocations scanRangeLocations) {
        if (backends == null || backends.isEmpty()) {
            return null;
        }

        SessionVariable sessionVariable = connectContext.getSessionVariable();
        boolean forceReBalance = sessionVariable.getHdfsBackendSelectorForceRebalance();
        boolean enableDataCache = sessionVariable.isEnableScanDataCache();
        // If force-rebalancing is not specified and cache is used, skip the rebalancing directly.
        if (!forceReBalance && enableDataCache) {
            return backends.get(0);
        }

        ComputeNode node = null;
        long addedScans = scanRangeLocations.scan_range.hdfs_scan_range.length;
        for (ComputeNode backend : backends) {
            long assignedScanRanges = assignedScansPerComputeNode.get(backend);
            if (assignedScanRanges + addedScans < avgNodeScanRangeBytes * kMaxImbalanceRatio) {
                node = backend;
                break;
            }
        }
        if (node == null) {
            node = backends.get(0);
        }
        return node;
    }

    static class ComputeNodeFunnel implements Funnel<ComputeNode> {
        @Override
        public void funnel(ComputeNode computeNode, PrimitiveSink primitiveSink) {
            primitiveSink.putString(computeNode.getHost(), StandardCharsets.UTF_8);
            primitiveSink.putInt(computeNode.getBePort());
        }
    }

    class TScanRangeLocationsFunnel implements Funnel<TScanRangeLocations> {
        @Override
        public void funnel(TScanRangeLocations tScanRangeLocations, PrimitiveSink primitiveSink) {
            hdfsScanRangeHasher.acceptScanRangeLocations(tScanRangeLocations, primitiveSink);
        }
    }

    @VisibleForTesting
    public HashRing makeHashRing(Collection<ComputeNode> nodes) {
        HashRing hashRing = null;
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        String hashAlgorithm = sessionVariable.getHdfsBackendSelectorHashAlgorithm();
        int virtualNodeNum = sessionVariable.getConsistentHashVirtualNodeNum();
        if (hashAlgorithm.equalsIgnoreCase("rendezvous")) {
            hashRing = new RendezvousHashRing(Hashing.murmur3_128(), new TScanRangeLocationsFunnel(),
                    new ComputeNodeFunnel(), nodes);
        } else {
            hashRing = new ConsistentHashRing(Hashing.murmur3_128(), new TScanRangeLocationsFunnel(),
                    new ComputeNodeFunnel(), nodes, virtualNodeNum);
        }
        return hashRing;
    }

    private long computeTotalSize() {
        long size = 0;
        for (TScanRangeLocations scanRangeLocations : locations) {
            size += scanRangeLocations.scan_range.hdfs_scan_range.getLength();
        }
        return size;
    }

    @Override
    public void computeScanRangeAssignment() throws StarRocksException {
        computeGeneralAssignment();
        if (useIncrementalScanRanges) {
            boolean hasMore = scanNode.hasMoreScanRanges();
            TScanRangeParams end = new TScanRangeParams();
            end.setScan_range(new TScanRange());
            end.setEmpty(true);
            end.setHas_more(hasMore);
            for (ComputeNode computeNode : workerProvider.getAllWorkers()) {
                assignment.put(computeNode.getId(), scanNode.getId().asInt(), end);
            }
        }
    }

    private List<TScanRangeLocations> computeForceScheduleLocalAssignment(long avgNodeScanRangeBytes) throws
            StarRocksException {
        // be host -> bes
        Multimap<String, ComputeNode> hostToBackends = HashMultimap.create();
        for (ComputeNode computeNode : workerProvider.getAllWorkers()) {
            hostToBackends.put(computeNode.getHost(), computeNode);
        }

        List<TScanRangeLocations> unassigned = Lists.newArrayList();
        for (int i = 0; i < locations.size(); ++i) {
            TScanRangeLocations scanRangeLocations = locations.get(i);
            List<ComputeNode> backends = new ArrayList<>();
            // select all backends that are co-located with this scan range.
            for (final TScanRangeLocation location : scanRangeLocations.getLocations()) {
                Collection<ComputeNode> servers = hostToBackends.get(location.getServer().getHostname());
                if (servers.isEmpty()) {
                    continue;
                }
                backends.addAll(servers);
            }
            ComputeNode node =
                    reBalanceScanRangeForComputeNode(backends, avgNodeScanRangeBytes, scanRangeLocations);
            if (node == null) {
                unassigned.add(scanRangeLocations);
            } else {
                recordScanRangeAssignment(node, null, backends, scanRangeLocations);
            }
        }
        return unassigned;
    }

    private void computeGeneralAssignment() throws StarRocksException {
        if (locations.size() == 0) {
            return;
        }

        long totalSize = computeTotalSize();
        long avgNodeScanRangeBytes = totalSize / Math.max(workerProvider.getAllWorkers().size(), 1) + 1;
        for (ComputeNode computeNode : workerProvider.getAllWorkers()) {
            assignedScansPerComputeNode.put(computeNode, 0L);
            reBalanceBytesPerComputeNode.put(computeNode, 0L);
        }

        // schedule scan ranges to co-located backends.
        // and put rest scan ranges into remote scan ranges.
        List<TScanRangeLocations> remoteScanRangeLocations = locations;
        if (forceScheduleLocal) {
            remoteScanRangeLocations = computeForceScheduleLocalAssignment(avgNodeScanRangeBytes);
        }
        if (remoteScanRangeLocations.isEmpty()) {
            return;
        }

        // use consistent hashing to schedule remote scan ranges
        HashRing hashRing = makeHashRing(assignedScansPerComputeNode.keySet());
        HashRing candidateHashRing = null;
        if (candidateWorkerProvider != null) {
            Collection<ComputeNode> candidateWorkers = candidateWorkerProvider.getAllWorkers();
            if (!candidateWorkers.isEmpty()) {
                candidateHashRing = makeHashRing(candidateWorkers);
            }
        }

        if (shuffleScanRange) {
            Collections.shuffle(remoteScanRangeLocations);
        }
        // assign scan ranges.
        for (int i = 0; i < remoteScanRangeLocations.size(); ++i) {
            TScanRangeLocations scanRangeLocations = remoteScanRangeLocations.get(i);
            List<ComputeNode> backends = hashRing.get(scanRangeLocations, kCandidateNumber);
            ComputeNode node = reBalanceScanRangeForComputeNode(backends, avgNodeScanRangeBytes, scanRangeLocations);
            if (node == null) {
                throw new StarRocksException("Failed to find backend to execute");
            }

            ComputeNode candidateNode = null;
            if (candidateHashRing != null) {
                List<ComputeNode> candidateBackends = candidateHashRing.get(scanRangeLocations, kCandidateNumber);
                // if datacache is enable, skip rebalance because it make the cache position undefined.
                candidateNode = candidateBackends.get(0);
            }
            recordScanRangeAssignment(node, candidateNode, backends, scanRangeLocations);
        }

        recordScanRangeStatistic();
    }

    private void recordScanRangeAssignment(ComputeNode worker, ComputeNode candidateWorker, List<ComputeNode> backends,
                                           TScanRangeLocations scanRangeLocations)
            throws NonRecoverableException {
        workerProvider.selectWorker(worker.getId());

        // update statistic
        long addedScans = scanRangeLocations.scan_range.hdfs_scan_range.length;
        assignedScansPerComputeNode.put(worker, assignedScansPerComputeNode.get(worker) + addedScans);
        // the fist item in backends will be assigned if there is no re-balance, we compute re-balance bytes
        // if the worker is not the first item in backends.
        if (worker != backends.get(0)) {
            reBalanceBytesPerComputeNode.put(worker, reBalanceBytesPerComputeNode.get(worker) + addedScans);
        }

        // add scan range params
        TScanRangeParams scanRangeParams = new TScanRangeParams();
        scanRangeParams.scan_range = scanRangeLocations.scan_range;
        if (candidateWorker != null) {
            scanRangeParams.scan_range.hdfs_scan_range.setCandidate_node(
                    String.format("%s:%d", candidateWorker.getHost(), candidateWorker.getBrpcPort()));
        }
        assignment.put(worker.getId(), scanNode.getId().asInt(), scanRangeParams);
    }

    private void recordScanRangeStatistic() {
        // record scan range size for each backend
        for (Map.Entry<ComputeNode, Long> entry : assignedScansPerComputeNode.entrySet()) {
            String host = entry.getKey().getAddress().hostname.replace('.', '_');
            long value = entry.getValue();
            String key = String.format("Placement.%s.assign.%s", scanNode.getTableName(), host);
            Tracers.count(Tracers.Module.EXTERNAL, key, value);
        }
        // record re-balance bytes for each backend
        for (Map.Entry<ComputeNode, Long> entry : reBalanceBytesPerComputeNode.entrySet()) {
            String host = entry.getKey().getAddress().hostname.replace('.', '_');
            long value = entry.getValue();
            String key = String.format("Placement.%s.balance.%s", scanNode.getTableName(), host);
            Tracers.count(Tracers.Module.EXTERNAL, key, value);
        }
    }
}
