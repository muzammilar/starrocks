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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/planner/OlapScanNode.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.planner;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotId;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.ExpressionRangePartitionInfo;
import com.starrocks.catalog.ExpressionRangePartitionInfoV2;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.HashDistributionInfo;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.VectorSearchOptions;
import com.starrocks.lake.LakeTablet;
import com.starrocks.persist.ColumnIdExpr;
import com.starrocks.qe.ConnectContext;
import com.starrocks.rowstore.RowStoreUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.service.FrontendOptions;
import com.starrocks.sql.ast.PartitionNames;
import com.starrocks.sql.ast.TableSampleClause;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.system.ComputeNode;
import com.starrocks.thrift.TColumn;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TInternalScanRange;
import com.starrocks.thrift.TLakeScanNode;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TNormalOlapScanNode;
import com.starrocks.thrift.TNormalPlanNode;
import com.starrocks.thrift.TOlapScanNode;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPlanNodeType;
import com.starrocks.thrift.TPrimitiveType;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocation;
import com.starrocks.thrift.TScanRangeLocations;
import com.starrocks.thrift.TTableSampleOptions;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.Warehouse;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.util.SizeEstimator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class OlapScanNode extends ScanNode {
    private static final Logger LOG = LogManager.getLogger(OlapScanNode.class);

    private final List<TScanRangeLocations> result = new ArrayList<>();
    private final List<String> selectedPartitionNames = Lists.newArrayList();
    private List<Long> selectedPartitionVersions = Lists.newArrayList();
    private final HashSet<Long> scanBackendIds = new HashSet<>();
    private final List<String> unUsedOutputStringColumns = new ArrayList<>();
    // a bucket seq may map to many tablets, and each tablet has a TScanRangeLocations.
    public ArrayListMultimap<Integer, TScanRangeLocations> bucketSeq2locations = ArrayListMultimap.create();
    public List<Expr> prunedPartitionPredicates = Lists.newArrayList();
    /*
     * When the field value is ON, the storage engine can return the data directly without pre-aggregation.
     * When the field value is OFF, the storage engine needs to aggregate the data before returning to scan node.
     * For example:
     * Aggregate table: k1, k2, v1 sum
     * Field value is ON
     * Query1: select k1, sum(v1) from table group by k1
     * This aggregation function in query is same as the schema.
     * So the field value is ON while the query can scan data directly.
     *
     * Field value is OFF
     * Query1: select k1 , k2 from table
     * This aggregation info is null.
     * Query2: select k1, min(v1) from table group by k1
     * This aggregation function in query is min which different from the schema.
     * So the data stored in storage engine need to be merged firstly before returning to scan node.
     */
    private boolean isPreAggregation = false;
    private String reasonOfPreAggregation = null;
    private OlapTable olapTable = null;
    private long selectedTabletsNum = 0;
    private long totalTabletsNum = 0;
    private long selectedIndexId = -1;
    private int selectedPartitionNum = 0;
    private List<Long> selectedPartitionIds = Lists.newArrayList();
    private long actualRows = 0;
    // List of tablets will be scanned by current olap_scan_node
    private List<Long> scanTabletIds = Lists.newArrayList();
    private List<Long> hintsReplicaIds = Lists.newArrayList();
    private boolean isFinalized = false;
    private boolean isSortedByKeyPerTablet = false;
    private boolean isOutputChunkByBucket = false;
    // only used in bucket agg
    private boolean withoutColocateRequirement = false;
    private boolean outputAscHint = true;
    private boolean sortKeyAscHint = true;
    private Optional<Boolean> partitionKeyAscHint = Optional.empty();

    private Map<Long, Integer> tabletId2BucketSeq = Maps.newHashMap();
    private List<Expr> bucketExprs = Lists.newArrayList();
    private List<ColumnRefOperator> bucketColumns = Lists.newArrayList();
    // record the selected physical partition with the selected tablets belong to it
    private Map<Long, List<Long>> partitionToScanTabletMap;
    // The dict id int column ids to dict string column ids
    private Map<Integer, Integer> dictStringIdToIntIds = Maps.newHashMap();

    private List<List<LiteralExpr>> rowStoreKeyLiterals = Lists.newArrayList();

    private boolean usePkIndex = false;
    private TableSampleClause sample;

    private long gtid = 0;

    private Map<Long, Long> scanPartitionVersions = Maps.newHashMap();

    private VectorSearchOptions vectorSearchOptions = new VectorSearchOptions();

    private boolean calcaulatedScanRange = false;

    private long totalScanRangeBytes = 0;

    // Set to true after it's confirmed at some point during the execution of this request that there is some living CN.
    // Set just once per query.
    private boolean alreadyFoundSomeLivingCn = false;

    boolean enableTopnFilterBackPressure = false;
    long backPressureThrottleTimeUpperBound = -1;
    int backPressureMaxRounds = -1;
    long backPressureThrottleTime = -1;
    long backPressureNumRows = -1;

    // Constructs node to scan given data files of table 'tbl'.
    // Constructs node to scan given data files of table 'tbl'.
    public OlapScanNode(PlanNodeId id, TupleDescriptor desc, String planNodeName) {
        super(id, desc, planNodeName);
        olapTable = (OlapTable) desc.getTable();
    }

    public OlapScanNode(PlanNodeId id, TupleDescriptor desc, String planNodeName, ComputeResource computeResource) {
        this(id, desc, planNodeName);
        this.computeResource = computeResource;
    }

    public Map<Long, Long> getScanPartitionVersions() {
        return scanPartitionVersions;
    }

    public void setVectorSearchOptions(VectorSearchOptions vectorSearchOptions) {
        this.vectorSearchOptions = vectorSearchOptions;
    }

    public void setIsPreAggregation(boolean isPreAggregation, String reason) {
        this.isPreAggregation = isPreAggregation;
        this.reasonOfPreAggregation = reason;
    }

    public List<Long> getScanTabletIds() {
        return scanTabletIds;
    }

    public boolean isPreAggregation() {
        return isPreAggregation;
    }

    public void setCanTurnOnPreAggr(boolean canChangePreAggr) {
    }

    public void setIsSortedByKeyPerTablet(boolean isSortedByKeyPerTablet) {
        this.isSortedByKeyPerTablet = isSortedByKeyPerTablet;
    }

    public void setIsOutputChunkByBucket(boolean isOutputChunkByBucket) {
        this.isOutputChunkByBucket = isOutputChunkByBucket;
    }

    public void setWithoutColocateRequirement(boolean withoutColocateRequirement) {
        this.withoutColocateRequirement = withoutColocateRequirement;
    }

    public boolean isLocalNativeTable() {
        return olapTable.isOlapTable();
    }

    public boolean getWithoutColocateRequirement() {
        return this.withoutColocateRequirement;
    }

    public void disablePhysicalPropertyOptimize() {
        setIsSortedByKeyPerTablet(false);
        setIsOutputChunkByBucket(false);
    }

    public void setOrderHint(boolean isAsc) {
        this.outputAscHint = isAsc;
    }

    public List<Long> getSelectedPartitionIds() {
        return selectedPartitionIds;
    }

    public void setSelectedPartitionIds(List<Long> selectedPartitionIds) {
        this.selectedPartitionIds = selectedPartitionIds;
    }

    public List<String> getSelectedPartitionNames() {
        return selectedPartitionNames;
    }

    public List<Long> getSelectedPartitionVersions() {
        return selectedPartitionVersions;
    }

    public List<Expr> getPrunedPartitionPredicates() {
        return prunedPartitionPredicates;
    }

    public void setDictStringIdToIntIds(Map<Integer, Integer> dictStringIdToIntIds) {
        this.dictStringIdToIntIds = dictStringIdToIntIds;
    }

    public long getActualRows() {
        return actualRows;
    }

    public List<ColumnRefOperator> getBucketColumns() {
        return bucketColumns;
    }

    // TODO: Determine local shuffle keys by FE to make local shuffle use bucket columns of OlapScanNode.
    public void setBucketColumns(List<ColumnRefOperator> bucketColumns) {
        this.bucketColumns = bucketColumns;
    }

    public List<Expr> getBucketExprs() {
        return bucketExprs;
    }

    @Override
    public int getBucketNums() {
        int bucketNum = olapTable.getDefaultDistributionInfo().getBucketNum();
        if (getSelectedPartitionIds().size() <= 1) {
            for (Long pid : getSelectedPartitionIds()) {
                bucketNum = olapTable.getPartition(pid).getDistributionInfo().getBucketNum();
            }
        }
        return bucketNum;
    }

    public void setBucketExprs(List<Expr> bucketExprs) {
        this.bucketExprs = bucketExprs;
    }

    public List<SlotDescriptor> getSlots() {
        return desc.getSlots();
    }

    public void setUnUsedOutputStringColumns(Set<Integer> unUsedOutputColumnIds) {
        for (SlotDescriptor slot : desc.getSlots()) {
            if (!slot.isMaterialized()) {
                continue;
            }
            if (unUsedOutputColumnIds.contains(slot.getId().asInt())) {
                unUsedOutputStringColumns.add(slot.getColumn().getColumnId().getId());
            }
        }
    }

    public OlapTable getOlapTable() {
        return olapTable;
    }

    @Override
    protected String debugString() {
        MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this);
        helper.addValue(super.debugString());
        helper.addValue("olapTable=" + olapTable.getName());
        return helper.toString();
    }

    @Override
    public void finalizeStats() throws StarRocksException {
        if (isFinalized) {
            return;
        }

        LOG.debug("OlapScanNode finalize. Tuple: {}", desc);
        try {
            getScanRangeLocations();
        } catch (AnalysisException e) {
            throw new StarRocksException(e.getMessage());
        }

        computeStats();
        isFinalized = true;
    }

    @Override
    public void computeStats() {
        if (cardinality > 0) {
            long totalBytes = 0;
            avgRowSize = totalBytes / (float) cardinality;
            if (hasLimit()) {
                cardinality = Math.min(cardinality, limit);
            }
        }
        // when node scan has no data, cardinality should be 0 instead of a invalid value after computeStats()
        cardinality = cardinality == -1 ? 0 : cardinality;
    }

    private List<Long> partitionPrune(RangePartitionInfo partitionInfo, PartitionNames partitionNames)
            throws AnalysisException {
        Map<Long, Range<PartitionKey>> keyRangeById = null;
        if (partitionNames != null) {
            keyRangeById = Maps.newHashMap();
            for (String partName : partitionNames.getPartitionNames()) {
                Partition part = olapTable.getPartition(partName, partitionNames.isTemp());
                if (part == null) {
                    ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_SUCH_PARTITION, partName);
                }
                keyRangeById.put(part.getId(), partitionInfo.getRange(part.getId()));
            }
        } else {
            keyRangeById = partitionInfo.getIdToRange(false);
        }
        PartitionPruner partitionPruner = new RangePartitionPruner(
                keyRangeById,
                partitionInfo.getPartitionColumns(olapTable.getIdToColumn()),
                columnFilters);
        return partitionPruner.prune();
    }

    private Collection<Long> distributionPrune(
            MaterializedIndex index,
            DistributionInfo distributionInfo) throws AnalysisException {
        DistributionPruner distributionPruner;
        if (DistributionInfo.DistributionInfoType.HASH == distributionInfo.getType()) {
            HashDistributionInfo info = (HashDistributionInfo) distributionInfo;
            distributionPruner = new HashDistributionPruner(index.getVirtualBuckets(),
                    index.getTabletIds(),
                    MetaUtils.getColumnsByColumnIds(olapTable, info.getDistributionColumns()),
                    columnFilters);
            return distributionPruner.prune();
        } else {
            return null;
        }
    }

    // update TScanRangeLocations based on the latest olapTable tablet distributions,
    // this function will make sure the version of each TScanRangeLocations doesn't change.
    public List<TScanRangeLocations> updateScanRangeLocations(List<TScanRangeLocations> locations,
                                                              ComputeResource computeResource)
            throws StarRocksException {
        if (selectedPartitionIds.size() == 0) {
            throw new StarRocksException("Scan node's partition is empty");
        }
        List<TScanRangeLocations> newLocations = Lists.newArrayList();
        for (TScanRangeLocations location : locations) {
            TInternalScanRange internalScanRange = location.scan_range.internal_scan_range;
            String expectedSchemaHashStr = internalScanRange.getSchema_hash();
            String expectedVersionStr = internalScanRange.getVersion();
            long tabletId = internalScanRange.getTablet_id();
            int expectedSchemaHash = Integer.parseInt(expectedSchemaHashStr);
            long expectedVersion = Long.parseLong(expectedVersionStr);
            Long physicalPartitionId = internalScanRange.partition_id;

            PhysicalPartition physicalPartition = olapTable.getPhysicalPartition(physicalPartitionId);
            final MaterializedIndex selectedTable = physicalPartition.getIndex(selectedIndexId);
            final Tablet selectedTablet = selectedTable.getTablet(tabletId);
            if (selectedTablet == null) {
                throw new StarRocksException("Tablet " + tabletId + " doesn't exist in partition " + physicalPartitionId);
            }

            int schemaHash = olapTable.getSchemaHashByIndexId(selectedTable.getId());
            if (schemaHash != expectedSchemaHash) {
                throw new StarRocksException("Tablet " + tabletId + " schema hash " + schemaHash +
                        " has changed, doesn't equal to expected schema hash " + expectedSchemaHash);
            }

            // random shuffle List && only collect one copy
            List<Replica> allQueryableReplicas = Lists.newArrayList();
            List<Replica> localReplicas = Lists.newArrayList();
            if (RunMode.getCurrentRunMode() == RunMode.SHARED_DATA) {
                selectedTablet.getQueryableReplicas(allQueryableReplicas, localReplicas,
                        expectedVersion, -1, schemaHash, computeResource);
            } else {
                selectedTablet.getQueryableReplicas(allQueryableReplicas, localReplicas,
                        expectedVersion, -1, schemaHash);
            }
            if (allQueryableReplicas.isEmpty()) {
                String replicaInfos = ((LocalTablet) selectedTablet).getReplicaInfos();
                String message = String.format("Failed to get scan range, no queryable replica found in " +
                                "tablet=%s replica=%s schemaHash=%d version=%d",
                        tabletId, replicaInfos, schemaHash, expectedVersion);
                LOG.error(message);
                throw new StarRocksException(message);
            }

            TScanRangeLocations scanRangeLocations = new TScanRangeLocations();
            TInternalScanRange internalRange = new TInternalScanRange();
            internalRange.setDb_name("");
            internalRange.setSchema_hash(expectedSchemaHashStr);
            internalRange.setVersion(expectedVersionStr);
            internalRange.setVersion_hash("0");
            internalRange.setTablet_id(tabletId);
            internalRange.setPartition_id(physicalPartitionId);
            internalRange.setRow_count(selectedTablet.getRowCount(0));
            if (isOutputChunkByBucket) {
                if (withoutColocateRequirement) {
                    internalRange.setBucket_sequence((int) tabletId);
                } else {
                    internalRange.setBucket_sequence(tabletId2BucketSeq.get(tabletId));
                }
            }

            List<Replica> replicas = allQueryableReplicas;

            Collections.shuffle(replicas);
            boolean tabletIsNull = true;
            for (Replica replica : replicas) {
                ComputeNode node =
                        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()
                                .getBackendOrComputeNode(replica.getBackendId());
                if (node == null) {
                    LOG.debug("replica {} not exists", replica.getBackendId());
                    continue;
                }
                String ip = node.getHost();
                int port = node.getBePort();
                TScanRangeLocation scanRangeLocation = new TScanRangeLocation(new TNetworkAddress(ip, port));
                scanRangeLocation.setBackend_id(replica.getBackendId());
                scanRangeLocations.addToLocations(scanRangeLocation);
                internalRange.addToHosts(new TNetworkAddress(ip, port));
                tabletIsNull = false;
            }
            if (tabletIsNull) {
                throw new StarRocksException(tabletId + "have no alive replicas");
            }
            TScanRange scanRange = new TScanRange();
            scanRange.setInternal_scan_range(internalRange);
            scanRangeLocations.setScan_range(scanRange);

            newLocations.add(scanRangeLocations);
        }
        return newLocations;
    }

    private void checkSomeAliveComputeNode() throws ErrorReportException {
        // Note that it's theoretically possible that there were some living CN earlier in this query's execution, and then
        // they all died, but in that case, the problem this will be surfaced later anyway.
        if (alreadyFoundSomeLivingCn) {
            return;
        }
        // We prefer to call getAliveComputeNodes infrequently, as it can come to dominate the execution time of a query in the
        // frontend if there are many calls per request (e.g. one per partition when there are many partitions).
        if (RunMode.getCurrentRunMode() == RunMode.SHARED_DATA) {
            WarehouseManager warehouseManager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
            if (CollectionUtils.isEmpty(warehouseManager.getAliveComputeNodes(computeResource))) {
                Warehouse warehouse = warehouseManager.getWarehouse(computeResource.getWarehouseId());
                throw ErrorReportException.report(ErrorCode.ERR_NO_NODES_IN_WAREHOUSE, warehouse.getName());
            }
        }
        alreadyFoundSomeLivingCn = true;
    }

    public void addScanRangeLocations(Partition partition,
                                      PhysicalPartition physicalPartition,
                                      MaterializedIndex index,
                                      List<Tablet> tablets,
                                      long localBeId) throws StarRocksException {
        boolean enableQueryTabletAffinity =
                ConnectContext.get() != null && ConnectContext.get().getSessionVariable().isEnableQueryTabletAffinity();
        boolean skipDiskCache = ConnectContext.get() != null && ConnectContext.get().getSessionVariable().isSkipLocalDiskCache();
        boolean skipPageCache = ConnectContext.get() != null && ConnectContext.get().getSessionVariable().isSkipPageCache();
        int logNum = 0;
        int schemaHash = olapTable.getSchemaHashByIndexId(index.getId());
        String schemaHashStr = String.valueOf(schemaHash);
        long visibleVersion = physicalPartition.getVisibleVersion();
        scanPartitionVersions.put(physicalPartition.getId(), visibleVersion);
        String visibleVersionStr = String.valueOf(visibleVersion);
        boolean fillDataCache = olapTable.isEnableFillDataCache(partition);
        selectedPartitionNames.add(partition.getName());
        selectedPartitionVersions.add(visibleVersion);

        checkSomeAliveComputeNode();
        for (Tablet tablet : tablets) {
            long tabletId = tablet.getId();
            LOG.debug("{} tabletId={}", (logNum++), tabletId);
            TScanRangeLocations scanRangeLocations = new TScanRangeLocations();

            TInternalScanRange internalRange = new TInternalScanRange();
            internalRange.setDb_name("");
            internalRange.setSchema_hash(schemaHashStr);
            internalRange.setVersion(visibleVersionStr);
            internalRange.setVersion_hash("0");
            internalRange.setTablet_id(tabletId);
            internalRange.setPartition_id(physicalPartition.getId());
            internalRange.setRow_count(tablet.getRowCount(0));
            if (isOutputChunkByBucket) {
                if (withoutColocateRequirement) {
                    internalRange.setBucket_sequence((int) tabletId);
                } else {
                    internalRange.setBucket_sequence(tabletId2BucketSeq.get(tabletId));
                }
            }

            if (gtid > 0) {
                internalRange.setGtid(gtid);
            }

            // random shuffle List && only collect one copy
            List<Replica> allQueryableReplicas = Lists.newArrayList();
            List<Replica> localReplicas = Lists.newArrayList();
            if (RunMode.getCurrentRunMode() == RunMode.SHARED_DATA) {
                tablet.getQueryableReplicas(allQueryableReplicas, localReplicas,
                        visibleVersion, localBeId, schemaHash, computeResource);
            } else {
                tablet.getQueryableReplicas(allQueryableReplicas, localReplicas,
                        visibleVersion, localBeId, schemaHash);
            }

            if (allQueryableReplicas.isEmpty()) {
                String replicaInfos = "";
                if (tablet instanceof LocalTablet) {
                    replicaInfos = ((LocalTablet) tablet).getReplicaInfos();
                }
                if (LOG.isDebugEnabled()) {
                    if (olapTable.isCloudNativeTableOrMaterializedView()) {
                        LOG.debug("tablet: {}, shard: {}, backends: {}", tabletId, ((LakeTablet) tablet).getShardId(),
                                tablet.getBackendIds());
                    } else {
                        for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                            LOG.debug("tablet {}, replica: {}", tabletId, replica.toString());
                        }
                    }
                }
                String message = String.format("Failed to get scan range, no queryable replica found in " +
                                "tablet=%s replica=%s schema_hash=%d version=%d",
                        tabletId, replicaInfos, schemaHash, visibleVersion);
                LOG.error(message);
                throw new StarRocksException(message);
            }

            List<Replica> replicas = null;
            if (!localReplicas.isEmpty()) {
                replicas = localReplicas;
            } else {
                replicas = allQueryableReplicas;
            }

            if (!hintsReplicaIds.isEmpty()) {
                replicas.removeIf(replica -> !hintsReplicaIds.contains(replica.getId()));
                // direct return if no expected replica
                if (replicas.isEmpty()) {
                    continue;
                }
            }

            // TODO: Implement a more robust strategy for tablet affinity.
            if (!enableQueryTabletAffinity) {
                Collections.shuffle(replicas);
            }

            boolean tabletIsNull = true;
            boolean collectedStat = false;
            for (Replica replica : replicas) {
                // TODO: need to refactor after be split into cn + dn
                ComputeNode node =
                        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()
                                .getBackendOrComputeNode(replica.getBackendId());
                if (node == null) {
                    LOG.debug("replica {} not exists", replica.getBackendId());
                    continue;
                }
                String ip = node.getHost();
                int port = node.getBePort();
                TScanRangeLocation scanRangeLocation = new TScanRangeLocation(new TNetworkAddress(ip, port));
                scanRangeLocation.setBackend_id(replica.getBackendId());
                scanRangeLocations.addToLocations(scanRangeLocation);
                internalRange.addToHosts(new TNetworkAddress(ip, port));
                internalRange.setFill_data_cache(fillDataCache);
                internalRange.setSkip_page_cache(skipPageCache);
                internalRange.setSkip_disk_cache(skipDiskCache);
                tabletIsNull = false;

                // for CBO
                if (!collectedStat && replica.getRowCount() != -1) {
                    actualRows += replica.getRowCount();
                    collectedStat = true;
                }
                scanBackendIds.add(node.getId());
            }
            if (tabletIsNull) {
                throw new StarRocksException(tabletId + "have no alive replicas");
            }
            TScanRange scanRange = new TScanRange();
            scanRange.setInternal_scan_range(internalRange);
            scanRangeLocations.setScan_range(scanRange);

            bucketSeq2locations.put(tabletId2BucketSeq.get(tabletId), scanRangeLocations);
            if (!calcaulatedScanRange) {
                long scanRangeSize = SizeEstimator.estimate(scanRangeLocations);
                checkIfScanRangeNumSafe(scanRangeSize);
                calcaulatedScanRange = true;
            }

            result.add(scanRangeLocations);
        }
    }

    public void computePartitionInfo() throws AnalysisException {
        long start = System.currentTimeMillis();
        // Step1: compute partition ids
        PartitionNames partitionNames = desc.getRef().getPartitionNames();
        PartitionInfo partitionInfo = olapTable.getPartitionInfo();
        if (partitionInfo.isRangePartition()) {
            selectedPartitionIds = partitionPrune((RangePartitionInfo) partitionInfo, partitionNames);
        } else {
            selectedPartitionIds = null;
        }
        if (selectedPartitionIds == null) {
            selectedPartitionIds = Lists.newArrayList();
            for (Partition partition : olapTable.getPartitions()) {
                if (!partition.hasData()) {
                    continue;
                }
                selectedPartitionIds.add(partition.getId());
            }
        } else {
            selectedPartitionIds = selectedPartitionIds.stream()
                    .filter(id -> olapTable.getPartition(id).hasData())
                    .collect(Collectors.toList());
        }
        selectedPartitionNum = selectedPartitionIds.size();
        LOG.debug("partition prune cost: {} ms, partitions: {}",
                (System.currentTimeMillis() - start), selectedPartitionIds);
    }

    public void selectBestRollupByRollupSelector() {
        selectedIndexId = olapTable.getBaseIndexId();
    }

    private void getScanRangeLocations() throws StarRocksException {
        if (selectedPartitionIds.size() == 0) {
            return;
        }
        Preconditions.checkState(selectedIndexId != -1);
        // compute tablet info by selected index id and selected partition ids
        long start = System.currentTimeMillis();
        computeTabletInfo();
        LOG.debug("distribution prune cost: {} ms", (System.currentTimeMillis() - start));
    }

    private void computeTabletInfo() throws StarRocksException {
        long localBeId = -1;
        if (Config.enable_local_replica_selection) {
            localBeId = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()
                    .getBackendIdByHost(FrontendOptions.getLocalHostAddress());
        }
        /**
         * The tablet info could be computed only once.
         * So the scanBackendIds should be empty in the beginning.
         */
        Preconditions.checkState(scanBackendIds.size() == 0);
        Preconditions.checkState(scanTabletIds.size() == 0);
        for (Long partitionId : selectedPartitionIds) {
            final Partition partition = olapTable.getPartition(partitionId);

            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                final List<Tablet> tablets = Lists.newArrayList();
                final MaterializedIndex selectedIndex = physicalPartition.getIndex(selectedIndexId);
                final Collection<Long> tabletIds = distributionPrune(selectedIndex, partition.getDistributionInfo());
                LOG.debug("distribution prune tablets: {}", tabletIds);

                List<Long> allTabletIds = selectedIndex.getTabletIds();
                if (tabletIds != null) {
                    for (Long id : tabletIds) {
                        tablets.add(selectedIndex.getTablet(id));
                    }
                    scanTabletIds.addAll(tabletIds);
                } else {
                    tablets.addAll(selectedIndex.getTablets());
                    scanTabletIds.addAll(allTabletIds);
                }

                for (int i = 0; i < allTabletIds.size(); i++) {
                    tabletId2BucketSeq.put(allTabletIds.get(i), i);
                }
                totalTabletsNum += selectedIndex.getTablets().size();
                selectedTabletsNum += tablets.size();
                addScanRangeLocations(partition, physicalPartition, selectedIndex, tablets, localBeId);
            }
        }
    }

    public void checkIfScanRangeNumSafe(long scanRangeSize) {
        long totalPartitionNum = 0;
        long totalTabletsNum = 0;
        for (long partitionId : selectedPartitionIds) {
            final Partition partition = olapTable.getPartition(partitionId);
            Collection<PhysicalPartition> physicalPartitions = partition.getSubPartitions();
            totalPartitionNum += physicalPartitions.size();
            for (PhysicalPartition physicalPartition : physicalPartitions) {
                final MaterializedIndex selectedTable = physicalPartition.getIndex(selectedIndexId);
                totalTabletsNum += selectedTable.getTablets().size();
            }
        }

        totalScanRangeBytes = scanRangeSize * totalTabletsNum;
        if (totalScanRangeBytes > 1024 * 1024) {
            Runtime runtime = Runtime.getRuntime();
            long freeMemory = runtime.freeMemory();
            if (totalScanRangeBytes > freeMemory / 2) {
                LOG.warn(
                        "Try to allocate too many scan ranges for table {}, which may cause FE OOM, Partition Num:{}, tablet " +
                                "Num:{}, Scan Range Total Bytes:{}",
                        olapTable.getName(), totalPartitionNum, totalTabletsNum, totalScanRangeBytes);
            }
        }

    }

    /**
     * We query meta to get request's data location
     * extra result info will pass to backend ScanNode
     */
    @Override
    public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
        return result;
    }

    @Override
    protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
        StringBuilder output = new StringBuilder();

        // TODO: unify them
        if (detailLevel != TExplainLevel.VERBOSE) {
            output.append(prefix).append("TABLE: ").append(olapTable.getName()).append("\n");
        } else {
            output.append(prefix).append("table: ").append(olapTable.getName())
                    .append(", ").append("rollup: ")
                    .append(olapTable.getIndexNameById(selectedIndexId)).append("\n");
        }

        if (null != sortColumn) {
            output.append(prefix).append("SORT COLUMN: ").append(sortColumn).append("\n");
        }

        if (Config.enable_experimental_vector) {
            if (vectorSearchOptions != null && vectorSearchOptions.isEnableUseANN()) {
                output.append(vectorSearchOptions.getExplainString(prefix));
            } else {
                output.append(prefix).append("VECTORINDEX: OFF").append("\n");
            }
        }

        if (detailLevel != TExplainLevel.VERBOSE) {
            if (isPreAggregation) {
                output.append(prefix).append("PREAGGREGATION: ON").append("\n");
            } else {
                output.append(prefix).append("PREAGGREGATION: OFF. Reason: ").append(reasonOfPreAggregation)
                        .append("\n");
            }
            if (!conjuncts.isEmpty()) {
                output.append(prefix).append("PREDICATES: ").append(
                        getExplainString(conjuncts)).append("\n");
            }
        } else {
            if (isPreAggregation) {
                output.append(prefix).append("preAggregation: on").append("\n");
            } else {
                output.append(prefix).append("preAggregation: off. Reason: ").append(reasonOfPreAggregation)
                        .append("\n");
            }
            if (!conjuncts.isEmpty()) {
                output.append(prefix).append("Predicates: ").append(getVerboseExplain(conjuncts)).append("\n");
            }
            if (!dictStringIdToIntIds.isEmpty()) {
                List<String> flatDictList = dictStringIdToIntIds.entrySet().stream().limit(5)
                        .map((entry) -> "(" + entry.getKey() + "," + entry.getValue() + ")")
                        .collect(Collectors.toList());
                String format_template = "dictStringIdToIntIds=%s";
                if (dictStringIdToIntIds.size() > 5) {
                    format_template = format_template + "...";
                }
                output.append(prefix).append(String.format(format_template, Joiner.on(",").join(flatDictList)));
                output.append("\n");
            }

            output.append(explainColumnDict(prefix));
        }

        if (detailLevel != TExplainLevel.VERBOSE) {
            output.append(prefix).append(String.format("partitions=%s/%s\n", selectedPartitionNum,
                    olapTable.getVisiblePartitionNames().size()));

            String indexName = olapTable.getIndexNameById(selectedIndexId);
            output.append(prefix).append(String.format("rollup: %s\n", indexName));

            output.append(prefix).append(String.format("tabletRatio=%s/%s\n", selectedTabletsNum, totalTabletsNum));

            // We print up to 10 tablet, and we print "..." if the number is more than 10
            if (scanTabletIds.size() > 10) {
                List<Long> firstTenTabletIds = scanTabletIds.subList(0, 10);
                output.append(prefix)
                        .append(String.format("tabletList=%s ...", Joiner.on(",").join(firstTenTabletIds)));
            } else {
                output.append(prefix).append(String.format("tabletList=%s", Joiner.on(",").join(scanTabletIds)));
            }

            if (gtid > 0) {
                output.append(prefix).append(String.format("gtid=%s", gtid));
            }

            output.append("\n");
            output.append(prefix).append(String.format("cardinality=%s\n", cardinality));
            output.append(prefix).append(String.format("avgRowSize=%s\n", avgRowSize));
        } else {
            output.append(prefix).append(String.format(
                            "partitionsRatio=%s/%s",
                            selectedPartitionNum,
                            olapTable.getVisiblePartitionNames().size())).append(", ")
                    .append(String.format("tabletsRatio=%s/%s", selectedTabletsNum, totalTabletsNum)).append("\n");

            if (scanTabletIds.size() > 10) {
                List<Long> firstTenTabletIds = scanTabletIds.subList(0, 10);
                output.append(prefix)
                        .append(String.format("tabletList=%s ...", Joiner.on(",").join(firstTenTabletIds)));
            } else {
                output.append(prefix).append(String.format("tabletList=%s", Joiner.on(",").join(scanTabletIds)));
            }

            if (gtid > 0) {
                output.append(prefix).append(String.format("gtid=%s", gtid));
            }

            output.append("\n");

            output.append(prefix).append(String.format("actualRows=%s", actualRows))
                    .append(", ").append(String.format("avgRowSize=%s\n", avgRowSize));
        }

        if (detailLevel == TExplainLevel.VERBOSE) {
            for (SlotDescriptor slotDescriptor : desc.getSlots()) {
                Type type = slotDescriptor.getOriginType();
                if (type.isComplexType()) {
                    output.append(prefix)
                            .append(String.format("Pruned type: %d <-> [%s]\n", slotDescriptor.getId().asInt(), type));
                }
            }

            if (!bucketColumns.isEmpty() && FeConstants.showScanNodeLocalShuffleColumnsInExplain) {
                output.append(prefix).append("LocalShuffleColumns:\n");
                for (ColumnRefOperator col : bucketColumns) {
                    output.append(prefix).append("- ").append(col.toString()).append("\n");
                }
            }
            output.append(explainColumnAccessPath(prefix));
        }

        if (olapTable.isMaterializedView()) {
            output.append(prefix).append("MaterializedView: true\n");
        }

        if (rowStoreKeyLiterals.size() != 0 && rowStoreKeyLiterals.get(0).size() != 0) {
            output.append(prefix).append("Short Circuit Scan: true\n");
        }

        if (sample != null) {
            output.append(prefix).append(sample.explain()).append("\n");
        }

        return output.toString();
    }

    private void assignOrderByHints(List<String> keyColumnNames) {
        // assign order by hint
        for (RuntimeFilterDescription probeRuntimeFilter : probeRuntimeFilters) {
            if (RuntimeFilterDescription.RuntimeFilterType.TOPN_FILTER.equals(
                    probeRuntimeFilter.runtimeFilterType())) {
                Expr expr = probeRuntimeFilter.getNodeIdToProbeExpr().get(getId().asInt());
                if (expr instanceof SlotRef) {
                    // check key columns
                    SlotId cid = ((SlotRef) expr).getSlotId();
                    String columnName = desc.getSlot(cid.asInt()).getColumn().getName();
                    if (!keyColumnNames.isEmpty() && keyColumnNames.get(0).equals(columnName)) {
                        sortKeyAscHint = outputAscHint;
                    }
                    // check partition column
                    PartitionInfo partitionInfo = olapTable.getPartitionInfo();
                    if (partitionInfo instanceof RangePartitionInfo) {
                        List<Column> partitionColumns = partitionInfo.getPartitionColumns(olapTable.getIdToColumn());
                        if (!partitionColumns.isEmpty() && partitionColumns.get(0).getName().equals(columnName)) {
                            partitionKeyAscHint = Optional.of(outputAscHint);
                        }
                    }
                }
                // we only care the first top-n filter
                return;
            }
        }
    }

    @Override
    protected void toThrift(TPlanNode msg) {
        List<String> keyColumnNames = new ArrayList<String>();
        List<TPrimitiveType> keyColumnTypes = new ArrayList<TPrimitiveType>();
        List<TColumn> columnsDesc = new ArrayList<TColumn>();
        Set<ColumnId> bfColumns = olapTable.getBfColumnIds();
        long schemaId = 0;

        if (selectedIndexId != -1) {
            MaterializedIndexMeta indexMeta = olapTable.getIndexMetaByIndexId(selectedIndexId);
            if (indexMeta != null) {
                schemaId = indexMeta.getSchemaId();
                for (Column col : olapTable.getSchemaByIndexId(selectedIndexId)) {
                    TColumn tColumn = col.toThrift();
                    tColumn.setColumn_name(col.getColumnId().getId());
                    col.setIndexFlag(tColumn, olapTable.getIndexes(), bfColumns);
                    columnsDesc.add(tColumn);
                }
                // process schema has order by columns
                if (indexMeta.getSortKeyIdxes() != null) {
                    for (Integer sortKeyIdx : indexMeta.getSortKeyIdxes()) {
                        Column col = indexMeta.getSchema().get(sortKeyIdx);
                        keyColumnNames.add(col.getName());
                        keyColumnTypes.add(col.getPrimitiveType().toThrift());
                    }
                } else {
                    for (Column col : olapTable.getSchemaByIndexId(selectedIndexId)) {
                        if (!col.isKey()) {
                            continue;
                        }

                        keyColumnNames.add(col.getName());
                        keyColumnTypes.add(col.getPrimitiveType().toThrift());
                    }
                }
            }
        }

        assignOrderByHints(keyColumnNames);
        if (olapTable.isCloudNativeTableOrMaterializedView()) {
            msg.node_type = TPlanNodeType.LAKE_SCAN_NODE;
            msg.lake_scan_node =
                    new TLakeScanNode(desc.getId().asInt(), keyColumnNames, keyColumnTypes, isPreAggregation);
            msg.lake_scan_node.setSort_key_column_names(keyColumnNames);
            msg.lake_scan_node.setRollup_name(olapTable.getIndexNameById(selectedIndexId));
            if (enableTopnFilterBackPressure) {
                msg.lake_scan_node.setEnable_topn_filter_back_pressure(true);
                msg.lake_scan_node.setBack_pressure_max_rounds(backPressureMaxRounds);
                msg.lake_scan_node.setBack_pressure_num_rows(backPressureNumRows);
                msg.lake_scan_node.setBack_pressure_throttle_time(backPressureThrottleTime);
                msg.lake_scan_node.setBack_pressure_throttle_time_upper_bound(backPressureThrottleTimeUpperBound);
            }
            if (!conjuncts.isEmpty()) {
                msg.lake_scan_node.setSql_predicates(getExplainString(conjuncts));
            }
            if (null != sortColumn) {
                msg.lake_scan_node.setSort_column(sortColumn);
            }
            if (ConnectContext.get() != null) {
                msg.lake_scan_node.setEnable_column_expr_predicate(
                        ConnectContext.get().getSessionVariable().isEnableColumnExprPredicate());
            }
            msg.lake_scan_node.setDict_string_id_to_int_ids(dictStringIdToIntIds);

            if (!olapTable.hasDelete()) {
                msg.lake_scan_node.setUnused_output_column_name(unUsedOutputStringColumns);
            }

            if (!bucketExprs.isEmpty()) {
                msg.lake_scan_node.setBucket_exprs(Expr.treesToThrift(bucketExprs));
            }

            if (CollectionUtils.isNotEmpty(columnAccessPaths)) {
                msg.lake_scan_node.setColumn_access_paths(columnAccessPathToThrift());
            }

            if (!scanTabletIds.isEmpty()) {
                msg.lake_scan_node.setSorted_by_keys_per_tablet(isSortedByKeyPerTablet);
                msg.lake_scan_node.setOutput_chunk_by_bucket(isOutputChunkByBucket);
            }

            msg.lake_scan_node.setOutput_asc_hint(sortKeyAscHint);
        } else { // If you find yourself changing this code block, see also the above code block
            msg.node_type = TPlanNodeType.OLAP_SCAN_NODE;
            msg.olap_scan_node =
                    new TOlapScanNode(desc.getId().asInt(), keyColumnNames, keyColumnTypes, isPreAggregation);
            msg.olap_scan_node.setColumns_desc(columnsDesc);
            msg.olap_scan_node.setSchema_id(schemaId);
            msg.olap_scan_node.setSort_key_column_names(keyColumnNames);
            msg.olap_scan_node.setRollup_name(olapTable.getIndexNameById(selectedIndexId));
            if (enableTopnFilterBackPressure) {
                msg.olap_scan_node.setEnable_topn_filter_back_pressure(true);
                msg.olap_scan_node.setBack_pressure_max_rounds(backPressureMaxRounds);
                msg.olap_scan_node.setBack_pressure_num_rows(backPressureNumRows);
                msg.olap_scan_node.setBack_pressure_throttle_time(backPressureThrottleTime);
                msg.olap_scan_node.setBack_pressure_throttle_time_upper_bound(backPressureThrottleTimeUpperBound);
            }
            if (!conjuncts.isEmpty()) {
                msg.olap_scan_node.setSql_predicates(getExplainString(conjuncts));
            }
            if (null != sortColumn) {
                msg.olap_scan_node.setSort_column(sortColumn);
            }
            if (ConnectContext.get() != null) {
                msg.olap_scan_node.setEnable_column_expr_predicate(
                        ConnectContext.get().getSessionVariable().isEnableColumnExprPredicate());
                msg.olap_scan_node.setMax_parallel_scan_instance_num(
                        ConnectContext.get().getSessionVariable().getMaxParallelScanInstanceNum());
                msg.olap_scan_node.setEnable_prune_column_after_index_filter(
                        ConnectContext.get().getSessionVariable().isEnablePruneColumnAfterIndexFilter());
                msg.olap_scan_node.setEnable_gin_filter(
                        ConnectContext.get().getSessionVariable().isEnableGinFilter());
            }
            msg.olap_scan_node.setDict_string_id_to_int_ids(dictStringIdToIntIds);

            if (!olapTable.hasDelete()) {
                msg.olap_scan_node.setUnused_output_column_name(unUsedOutputStringColumns);
            }

            if (!scanTabletIds.isEmpty()) {
                msg.olap_scan_node.setSorted_by_keys_per_tablet(isSortedByKeyPerTablet);
                msg.olap_scan_node.setOutput_chunk_by_bucket(isOutputChunkByBucket);
            }

            msg.olap_scan_node.setOutput_asc_hint(sortKeyAscHint);
            partitionKeyAscHint.ifPresent(aBoolean -> msg.olap_scan_node.setPartition_order_hint(aBoolean));
            if (!bucketExprs.isEmpty()) {
                msg.olap_scan_node.setBucket_exprs(Expr.treesToThrift(bucketExprs));
            }

            if (CollectionUtils.isNotEmpty(columnAccessPaths)) {
                msg.olap_scan_node.setColumn_access_paths(columnAccessPathToThrift());
            }

            if (vectorSearchOptions != null && vectorSearchOptions.isEnableUseANN()) {
                msg.olap_scan_node.setVector_search_options(vectorSearchOptions.toThrift());
            }

            msg.olap_scan_node.setUse_pk_index(usePkIndex);
            if (sample != null && sample.isUseSampling()) {
                TTableSampleOptions sampleOptions = new TTableSampleOptions();
                msg.olap_scan_node.setSample_options(sampleOptions);
                sample.toThrift(sampleOptions);
            }
        }
    }

    // export some tablets
    public static OlapScanNode createOlapScanNodeByLocation(
            PlanNodeId id, TupleDescriptor desc, String planNodeName, List<TScanRangeLocations> locationsList,
            ComputeResource computeResource) {
        OlapScanNode olapScanNode = new OlapScanNode(id, desc, planNodeName);
        olapScanNode.selectedIndexId = olapScanNode.olapTable.getBaseIndexId();
        olapScanNode.selectedPartitionNum = 1;
        olapScanNode.selectedTabletsNum = 1;
        olapScanNode.totalTabletsNum = 1;
        olapScanNode.isPreAggregation = false;
        olapScanNode.isFinalized = true;
        olapScanNode.computeResource = computeResource;
        olapScanNode.result.addAll(locationsList);

        return olapScanNode;
    }

    @Override
    public boolean canUseRuntimeAdaptiveDop() {
        return true;
    }

    /**
     * Below function is added by new analyzer
     */
    public void updateScanInfo(List<Long> selectedPartitionIds,
                               List<Long> scanTabletIds,
                               List<Long> hintsReplicaIds,
                               long selectedIndexId) {
        this.scanTabletIds = Lists.newArrayList(scanTabletIds);
        this.hintsReplicaIds = Lists.newArrayList(hintsReplicaIds);
        this.selectedTabletsNum = scanTabletIds.size();
        this.selectedPartitionIds = selectedPartitionIds;
        this.selectedPartitionNum = selectedPartitionIds.size();
        this.selectedIndexId = selectedIndexId;
        this.partitionToScanTabletMap = mapTabletsToPartitions();

        // FixMe(kks): For DUPLICATE table, isPreAggregation could always true
        this.isPreAggregation = true;
    }

    public void setTabletId2BucketSeq(Map<Long, Integer> tabletId2BucketSeq) {
        this.tabletId2BucketSeq = tabletId2BucketSeq;
    }

    public void setTotalTabletsNum(long totalTabletsNum) {
        this.totalTabletsNum = totalTabletsNum;
    }

    public void setUsePkIndex(boolean usePkIndex) {
        this.usePkIndex = usePkIndex;
    }

    public TableSampleClause getSample() {
        return sample;
    }

    public void setSample(TableSampleClause sample) {
        this.sample = sample;
    }

    @Override
    public boolean canDoReplicatedJoin() {
        // TODO(wyb): necessary to support?
        if (olapTable.isCloudNativeTableOrMaterializedView()) {
            return false;
        }
        ConnectContext ctx = ConnectContext.get();
        int backendSize = ctx.getTotalBackendNumber();
        int aliveBackendSize = ctx.getAliveBackendNumber();
        int schemaHash = olapTable.getSchemaHashByIndexId(selectedIndexId);
        for (Map.Entry<Long, List<Long>> entry : partitionToScanTabletMap.entrySet()) {
            PhysicalPartition partition = olapTable.getPhysicalPartition(entry.getKey());
            if (olapTable.getPartitionInfo().getReplicationNum(partition.getParentId()) < backendSize) {
                return false;
            }
            long visibleVersion = partition.getVisibleVersion();
            MaterializedIndex materializedIndex = partition.getIndex(selectedIndexId);
            for (Long id : entry.getValue()) {
                LocalTablet tablet = (LocalTablet) materializedIndex.getTablet(id);
                if (tablet.getQueryableReplicasSize(visibleVersion, schemaHash) != aliveBackendSize) {
                    return false;
                }
            }

        }
        return true;
    }

    @VisibleForTesting
    public long getSelectedIndexId() {
        return selectedIndexId;
    }

    /**
     * Get partition id -> tablets map, note that the partition id is unrolled partition id which may be subpartition id.
     */
    public Map<Long, List<Long>> getPartitionToScanTabletMap() {
        return partitionToScanTabletMap;
    }

    private Set<Long> getHotPartitionIds(RangePartitionInfo partitionInfo) {
        KeysType keysType = olapTable.getKeysType();
        ConnectContext connectContext = ConnectContext.get();
        Set<Long> hotIds = Sets.newHashSet();
        if (connectContext == null) {
            return hotIds;
        }
        List<Map.Entry<Long, Range<PartitionKey>>> partitions = partitionInfo.getSortedRangeMap(false);
        int numHotIds = 0;
        int numPartitions = partitions.size();
        if (!canUseMultiVersionCache()) {
            int pkHotNum = connectContext.getSessionVariable().getQueryCacheHotPartitionNum();
            numHotIds = Math.min(numPartitions, Math.max(0, pkHotNum));
        }
        return partitions.subList(numPartitions - numHotIds, numPartitions).stream().map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public Set<SlotId> getSlotIdsOfPartitionColumns(FragmentNormalizer normalizer) {
        PartitionInfo partitionInfo = olapTable.getPartitionInfo();
        if (!(partitionInfo instanceof RangePartitionInfo)) {
            return Collections.emptySet();
        }
        List<Column> partitionColumns = partitionInfo.getPartitionColumns(olapTable.getIdToColumn());
        Set<String> partColNames = partitionColumns.stream().map(Column::getName).collect(Collectors.toSet());

        List<SlotDescriptor> slots = normalizer.getExecPlan().getDescTbl().getTupleDesc(tupleIds.get(0)).getSlots();
        List<Pair<SlotId, String>> slotIdToColNames =
                slots.stream().map(s -> new Pair<>(s.getId(), s.getColumn().getName()))
                        .collect(Collectors.toList());

        return slotIdToColNames.stream()
                .filter(s -> partColNames.contains(s.second)).map(s -> s.first).collect(Collectors.toSet());
    }

    private Optional<SlotId> associateSlotIdsWithColumns(FragmentNormalizer normalizer, TNormalPlanNode planNode,
                                                         Optional<Column> optPartitionColumn) {
        List<SlotDescriptor> slots = normalizer.getExecPlan().getDescTbl().getTupleDesc(tupleIds.get(0)).getSlots();
        List<Pair<SlotId, String>> slotIdToColNames =
                slots.stream().map(s -> new Pair<>(s.getId(), s.getColumn().getName()))
                        .collect(Collectors.toList());

        Optional<SlotId> optPartitionSlotId = Optional.empty();
        if (optPartitionColumn.isPresent()) {
            Column column = optPartitionColumn.get();
            SlotId slotId = slotIdToColNames.stream()
                    .filter(s -> s.second.equalsIgnoreCase(column.getName()))
                    .findFirst().map(s -> s.first).orElse(new SlotId(-1));
            optPartitionSlotId = Optional.of(slotId);
            // slotId.isValid means that whereClause contains no predicates involving partition columns. so the query
            // is equivalent to the query with a superset of all the partitions, so we needs add a fake slotId that
            // represents the partition column to the slot remapping. for an example:
            // table t0 is partition by dt and has there partitions:
            //  p0=[2022-01-01, 2022-01-02),
            //  p1=[2022-01-02, 2022-01-03),
            //  p2=[2022-01-03, 2022-01-04).
            // Q1: select count(*) from t0 will be performed p0,p1,p2. but dt is absent from OlapScanNode.
            // Q2: select count(*) from t0 where dt between and '2022-01-01' and '2022-01-01' should use the partial result
            // of Q1 on p0. but Q1 and Q2 has different SlotId re-mappings, so Q2's cache key cannot match the Q1's. so
            // we should add a fake slotId represents the partition column when we re-map slotIds.
            if (!slotId.isValid()) {
                slotIdToColNames.add(new Pair<>(slotId, column.getName()));
            }
        }
        slotIdToColNames.sort(Pair.comparingBySecond());
        List<SlotId> slotIds = slotIdToColNames.stream().map(s -> s.first).collect(Collectors.toList());
        List<Integer> remappedSlotIds = normalizer.remapSlotIds(slotIds);

        planNode.olap_scan_node.setRemapped_slot_ids(remappedSlotIds);
        planNode.olap_scan_node.setSelected_column(
                slotIdToColNames.stream().map(c -> c.second).collect(Collectors.toList()));
        return optPartitionSlotId;
    }

    private List<Expr> decomposeRangePredicates(List<Column> partitionColumns, FragmentNormalizer normalizer,
                                                TNormalPlanNode planNode, RangePartitionInfo rangePartitionInfo,
                                                List<Expr> conjuncts) {
        Set<Long> selectedPartIdSet = new HashSet<>(selectedPartitionIds);
        selectedPartIdSet.removeAll(getHotPartitionIds(rangePartitionInfo));

        Column column = partitionColumns.get(0);
        Optional<SlotId> optSlotId = associateSlotIdsWithColumns(normalizer, planNode, Optional.of(column));
        List<Pair<Long, Range<PartitionKey>>> rangeMap = Lists.newArrayList();
        try {
            List<Map.Entry<Long, Range<PartitionKey>>> rangeMap2 = rangePartitionInfo.getSortedRangeMap(selectedPartIdSet);

            for (Map.Entry<Long, Range<PartitionKey>> partitionRange : rangeMap2) {
                Long partitionId = partitionRange.getKey();
                Partition partition = olapTable.getPartition(partitionId);
                for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                    rangeMap.add(new Pair<>(physicalPartition.getId(), partitionRange.getValue()));
                }
            }

        } catch (AnalysisException ignored) {
        }
        Preconditions.checkState(optSlotId.isPresent());
        return normalizer.getPartitionRangePredicates(conjuncts, rangeMap, partitionColumns, optSlotId.get());
    }

    private void normalizeConjunctsNonLeft(FragmentNormalizer normalizer, TNormalPlanNode planNode) {
        List<SlotDescriptor> slots = normalizer.getExecPlan().getDescTbl().getTupleDesc(tupleIds.get(0)).getSlots();
        List<Pair<SlotId, String>> slotIdToColNames =
                slots.stream().map(s -> new Pair<>(s.getId(), s.getColumn().getName())).sorted(Pair.comparingBySecond())
                        .collect(Collectors.toList());
        List<SlotId> slotIds = slotIdToColNames.stream().map(s -> s.first).collect(Collectors.toList());
        normalizer.remapSlotIds(slotIds);
        planNode.setConjuncts(normalizer.normalizeExprs(normalizer.getConjunctsByPlanNodeId(this)));
    }

    // Partition by exprs as follows can be decomposed
    // 1. SlotRef
    // 2. date_trunc
    // 3. str2date(dt, '%Y-%m-%d')
    private boolean isDecomposablePartitionExpr(Expr expr) {
        if (expr.getClass().equals(SlotRef.class)) {
            return true;
        }
        if (!expr.getClass().equals(FunctionCallExpr.class)) {
            return false;
        }
        FunctionCallExpr fcall = (FunctionCallExpr) expr;
        String fname = fcall.getFnName().getFunction();
        if (fname.equalsIgnoreCase(FunctionSet.DATE_TRUNC)) {
            return true;
        } else if (fname.equalsIgnoreCase(FunctionSet.STR2DATE)) {
            return expr.getChild(1).getClass().equals(StringLiteral.class) &&
                    ((StringLiteral) expr.getChild(1)).getValue().startsWith("%Y-%m-%d");
        } else {
            return false;
        }
    }

    private boolean isDecomposablePartitionInfo(PartitionInfo partitionInfo) {
        // TODO (by satanson): predicates' decomposition
        //  At present, we support predicates' decomposition on RangePartition with single-column partition key.
        //  in the future, predicates' decomposition on RangePartition with multi-column partition key will be
        //  supported.
        if (!partitionInfo.isRangePartition()) {
            return false;
        }
        RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) partitionInfo;
        if (rangePartitionInfo.getPartitionColumnsSize() != 1) {
            return false;
        }
        if (rangePartitionInfo.getClass().equals(RangePartitionInfo.class)) {
            return true;
        }

        Predicate<List<ColumnIdExpr>> isDecomposable = exprs -> exprs.stream()
                .map(ColumnIdExpr::getExpr)
                .allMatch(this::isDecomposablePartitionExpr);

        if (rangePartitionInfo.getClass().equals(ExpressionRangePartitionInfo.class)) {
            ExpressionRangePartitionInfo exprRangePartitionInfo = (ExpressionRangePartitionInfo) rangePartitionInfo;
            return isDecomposable.test(exprRangePartitionInfo.getPartitionColumnIdExprs());
        }

        if (rangePartitionInfo.getClass().equals(ExpressionRangePartitionInfoV2.class)) {
            ExpressionRangePartitionInfoV2 exprRangePartitionInfo = (ExpressionRangePartitionInfoV2) rangePartitionInfo;
            return isDecomposable.test(exprRangePartitionInfo.getPartitionColumnIdExprs());
        }
        return false;
    }

    @Override
    public void normalizeConjuncts(FragmentNormalizer normalizer, TNormalPlanNode planNode, List<Expr> conjuncts) {
        if (!normalizer.isProcessingLeftNode()) {
            // take column names of HashJoin RHS into cache digest computation
            associateSlotIdsWithColumns(normalizer, planNode, Optional.empty());
            normalizeConjunctsNonLeft(normalizer, planNode);
            return;
        }
        PartitionInfo partitionInfo = olapTable.getPartitionInfo();
        List<Column> partitionColumns = partitionInfo.getPartitionColumns(olapTable.getIdToColumn());

        if (isDecomposablePartitionInfo(partitionInfo)) {
            RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) partitionInfo;
            conjuncts = decomposeRangePredicates(partitionColumns, normalizer, planNode, rangePartitionInfo, conjuncts);
        } else {
            associateSlotIdsWithColumns(normalizer, planNode, Optional.empty());
            List<Long> physicalPartitionIds = new ArrayList<>();
            for (Long partitionId : selectedPartitionIds) {
                Partition partition = olapTable.getPartition(partitionId);
                physicalPartitionIds.addAll(partition.getSubPartitions().stream()
                        .map(PhysicalPartition::getId).collect(Collectors.toList()));
            }
            normalizer.createSimpleRangeMap(physicalPartitionIds);
        }
        planNode.setConjuncts(normalizer.normalizeExprs(conjuncts));
    }

    // Only DUP_KEYS and AGG_KEYS without columns carrying REPLACE modifier can support
    // multi-version cache, for other types of data models, we can not get the final result
    // of the tablet from merging the snapshot result in cache and the delta rowsets in disk.
    private boolean canUseMultiVersionCache() {
        switch (olapTable.getKeysType()) {
            case PRIMARY_KEYS:
            case UNIQUE_KEYS:
                return false;
            case DUP_KEYS:
            case AGG_KEYS: {
                List<Column> columns = selectedIndexId == -1 ? olapTable.getBaseSchema() :
                        olapTable.getSchemaByIndexId(selectedIndexId);
                return columns.stream().noneMatch(
                        c -> c.isAggregated() && c.getAggregationType().isReplaceFamily());
            }
        }
        return false;
    }

    protected void toNormalForm(TNormalPlanNode planNode, FragmentNormalizer normalizer) {
        TNormalOlapScanNode scanNode = new TNormalOlapScanNode();
        // Cache Key has this form: [digest, partition id, partition column range, tablet_id].
        // Partition information of OlapScanNodes is handled in different ways that depends on
        // the fact whether OlapScanNodes resides in leftmost path of the fragment or not.
        // 1. in case of OlapScanNodes inside of leftmost path: The partition information is not
        // packed into the digest of the cache keys, but selected partition ids form the second
        // component of cache keys and each partition id has a corresponding cache key.
        // 2. in case of OlapScanNodes outside of leftmost path: partition information is packed
        // into the digest of the cache keys.
        // In a conclusion, only the leftmost OlapScanNode affects multi-version cache and partition
        // column predicates' decomposition mechanism. OlapScanNodes in the right-sibling Fragment only
        // affect cache key identity.
        if (normalizer.isProcessingLeftNode()) {
            normalizer.setKeysType(olapTable.getKeysType());
            normalizer.setCanUseMultiVersion(canUseMultiVersionCache());

            List<Column> columns = selectedIndexId == -1 ? olapTable.getBaseSchema() :
                    olapTable.getSchemaByIndexId(selectedIndexId);

            Set<String> aggColumnNames =
                    columns.stream().filter(Column::isAggregated).map(Column::getName).collect(Collectors.toSet());
            Set<SlotId> aggColumnSlotIds =
                    normalizer.getExecPlan().getDescTbl().getTupleDesc(tupleIds.get(0)).getSlots().stream()
                            .filter(s -> aggColumnNames.contains(s.getColumn().getName())).map(s -> s.getId())
                            .collect(Collectors.toSet());
            normalizer.setSlotsUseAggColumns(aggColumnSlotIds);
        } else {
            List<Long> partitionIds = getSelectedPartitionIds();

            List<Long> physicalPartitionIds = new ArrayList<>();
            for (Long partitionId : partitionIds) {
                Partition partition = olapTable.getPartition(partitionId);
                physicalPartitionIds.addAll(partition.getSubPartitions().stream()
                        .map(PhysicalPartition::getId).collect(Collectors.toList()));
            }

            List<Long> partitionVersions = getSelectedPartitionVersions();
            Preconditions.checkState(physicalPartitionIds.size() == partitionVersions.size());
            List<Pair<Long, Long>> partitionVersionAndIds = IntStream.range(0, physicalPartitionIds.size())
                    .mapToObj(i -> Pair.create(partitionVersions.get(i), physicalPartitionIds.get(i)))
                    .sorted(Pair.comparingBySecond()).collect(Collectors.toList());
            scanNode.setSelected_partition_ids(
                    partitionVersionAndIds.stream().map(p -> p.second).collect(Collectors.toList()));
            scanNode.setSelected_partition_versions(
                    partitionVersionAndIds.stream().map(p -> p.first).collect(Collectors.toList()));
        }

        scanNode.setTablet_id(olapTable.getId());
        scanNode.setIndex_id(selectedIndexId);
        List<String> keyColumnNames = new ArrayList<String>();
        List<TPrimitiveType> keyColumnTypes = new ArrayList<TPrimitiveType>();
        if (selectedIndexId != -1) {
            for (Column col : olapTable.getSchemaByIndexId(selectedIndexId)) {
                if (!col.isKey()) {
                    break;
                }
                keyColumnNames.add(col.getName());
                keyColumnTypes.add(col.getPrimitiveType().toThrift());
            }
        }
        scanNode.setKey_column_names(keyColumnNames);
        scanNode.setKey_column_types(keyColumnTypes);
        scanNode.setIs_preaggregation(isPreAggregation);
        scanNode.setSort_column(sortColumn);
        scanNode.setRollup_name(olapTable.getIndexNameById(selectedIndexId));

        List<Integer> dictStringIds =
                dictStringIdToIntIds.keySet().stream().sorted(Integer::compareTo).collect(Collectors.toList());
        List<Integer> dictIntIds = dictStringIds.stream().map(dictStringIdToIntIds::get).collect(Collectors.toList());
        scanNode.setDict_string_ids(dictStringIds);
        scanNode.setDict_int_ids(dictIntIds);
        planNode.setNode_type(olapTable.isCloudNativeTableOrMaterializedView() ?
                TPlanNodeType.LAKE_SCAN_NODE : TPlanNodeType.OLAP_SCAN_NODE);
        planNode.setOlap_scan_node(scanNode);
        normalizeConjuncts(normalizer, planNode, conjuncts);
    }

    private Map<Long, List<Long>> mapTabletsToPartitions() {
        Map<Long, Long> tabletToPartitionMap = Maps.newHashMap();
        Map<Long, List<Long>> partitionToTabletMap = Maps.newHashMap();

        for (Long partitionId : selectedPartitionIds) {
            Partition partition = olapTable.getPartition(partitionId);
            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                MaterializedIndex materializedIndex = physicalPartition.getIndex(selectedIndexId);
                for (long tabletId : materializedIndex.getTabletIds()) {
                    tabletToPartitionMap.put(tabletId, physicalPartition.getId());
                }
                partitionToTabletMap.put(physicalPartition.getId(), Lists.newArrayList());
            }
        }
        for (Long tabletId : scanTabletIds) {
            // for query: select count(1) from t tablet(tablet_id0, tablet_id1,...), the user-provided tablet_id
            // maybe invalid.
            Preconditions.checkState(tabletToPartitionMap.containsKey(tabletId),
                    "Invalid tablet id: '" + tabletId + "'");
            long partitionId = tabletToPartitionMap.get(tabletId);
            partitionToTabletMap.computeIfAbsent(partitionId, k -> Lists.newArrayList()).add(tabletId);
        }

        LOG.debug("mapTabletsToPartitions. tabletToPartitionMap: {}, partitionToTabletMap: {}",
                tabletToPartitionMap, partitionToTabletMap);
        return partitionToTabletMap;
    }

    @Override
    protected boolean supportTopNRuntimeFilter() {
        return true;
    }

    @Override
    protected boolean canEliminateNull(SlotDescriptor slot) {
        return super.canEliminateNull(slot) ||
                prunedPartitionPredicates.stream().anyMatch(expr -> canEliminateNull(expr, slot));
    }

    public void computePointScanRangeLocations() {
        // must order in create table
        List<String> keyColumns = olapTable.getKeyColumnsInOrder().stream().map(Column::getName)
                .collect(Collectors.toList());
        Optional<List<List<LiteralExpr>>> points = RowStoreUtils.extractPointsLiteral(conjuncts, keyColumns);

        if (points.isPresent()) {
            rowStoreKeyLiterals = points.get();
        }
    }

    public List<List<LiteralExpr>> getRowStoreKeyLiterals() {
        return rowStoreKeyLiterals;
    }

    public void setGtid(long gtid) {
        this.gtid = gtid;
    }

    // clear scan node， reduce body size
    public void clearScanNodeForThriftBuild() {
        sortColumn = null;
        this.selectedIndexId = -1;
        selectedPartitionNames.clear();
        selectedPartitionVersions.clear();
        result.clear();
        scanBackendIds.clear();
        appliedDictStringColumns.clear();
        unUsedOutputStringColumns.clear();
        bucketSeq2locations.clear();
        prunedPartitionPredicates.clear();
        selectedPartitionIds.clear();
        hintsReplicaIds.clear();
        tabletId2BucketSeq.clear();
        bucketExprs.clear();
        bucketColumns.clear();
        rowStoreKeyLiterals = Lists.newArrayList();
    }

    @Override
    public boolean isRunningAsConnectorOperator() {
        return false;
    }

    @Override
    public boolean pushDownRuntimeFilters(RuntimeFilterPushDownContext context, Expr probeExpr,
                                          List<Expr> partitionByExprs) {
        boolean accept = super.pushDownRuntimeFilters(context, probeExpr, partitionByExprs);
        if (accept && context.getDescription().runtimeFilterType()
                .equals(RuntimeFilterDescription.RuntimeFilterType.TOPN_FILTER)) {
            boolean toManyData = this.getCardinality() != -1 && this.cardinality > 50000000;
            int backPressureMode = Optional.ofNullable(ConnectContext.get())
                    .map(ctx -> ctx.getSessionVariable().getTopnFilterBackPressureMode())
                    .orElse(0);
            if ((backPressureMode == 1 && toManyData) || backPressureMode == 2) {
                this.enableTopnFilterBackPressure = true;
                this.backPressureMaxRounds = ConnectContext.get().getSessionVariable().getBackPressureMaxRounds();
                this.backPressureThrottleTimeUpperBound =
                        ConnectContext.get().getSessionVariable().getBackPressureThrottleTimeUpperBound();
                this.backPressureNumRows = 10 * context.getDescription().getTopN();
                this.backPressureThrottleTime = this.backPressureThrottleTimeUpperBound /
                        Math.max(this.backPressureMaxRounds, 1);
            }
        }
        return accept;
    }
}
