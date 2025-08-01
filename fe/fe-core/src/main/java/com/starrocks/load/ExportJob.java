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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/load/ExportJob.java

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

package com.starrocks.load;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import com.starrocks.analysis.BaseTableRef;
import com.starrocks.analysis.BrokerDesc;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.TableRef;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MysqlTable;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.Status;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.common.util.BrokerUtil;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.common.util.NetUtils;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.common.util.concurrent.lock.AutoCloseableLock;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.fs.HdfsUtil;
import com.starrocks.persist.gson.GsonPostProcessable;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.planner.DataPartition;
import com.starrocks.planner.ExportSink;
import com.starrocks.planner.MysqlScanNode;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.PlanFragment;
import com.starrocks.planner.PlanFragmentId;
import com.starrocks.planner.PlanNodeId;
import com.starrocks.planner.ScanNode;
import com.starrocks.proto.UnlockTabletMetadataRequest;
import com.starrocks.qe.DefaultCoordinator;
import com.starrocks.qe.scheduler.Coordinator;
import com.starrocks.rpc.BrpcProxy;
import com.starrocks.rpc.LakeService;
import com.starrocks.rpc.ThriftConnectionPool;
import com.starrocks.rpc.ThriftRPCRequestExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.ExportStmt;
import com.starrocks.sql.ast.LoadStmt;
import com.starrocks.sql.ast.PartitionNames;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;
import com.starrocks.thrift.TAgentResult;
import com.starrocks.thrift.THdfsProperties;
import com.starrocks.thrift.TInternalScanRange;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TResultSinkType;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocation;
import com.starrocks.thrift.TScanRangeLocations;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.warehouse.cngroup.CRAcquireContext;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// NOTE: we must be carefully if we send next request
//       as soon as receiving one instance's report from one BE,
//       because we may change job's member concurrently.
//
// export file name format:
// <prefix>_<task-number>_<instance-number>_<file-number>.csv  (if include_query_id is false)
// <prefix>_<query-id>_<task-number>_<instance-number>_<file-number>.csv
public class ExportJob implements Writable, GsonPostProcessable {
    private static final Logger LOG = LogManager.getLogger(ExportJob.class);
    // descriptor used to register all column and table need
    private final DescriptorTable desc;
    private final Set<String> exportedTempFiles = Sets.newConcurrentHashSet();
    private Set<String> exportedFiles = Sets.newConcurrentHashSet();
    private final List<Coordinator> coordList = Lists.newArrayList();
    private final AtomicInteger nextId = new AtomicInteger(0);
    // backedn_address => snapshot path
    private List<Pair<TNetworkAddress, String>> snapshotPaths = Lists.newArrayList();
    // backend id => backend lastStartTime
    private final Map<Long, Long> beLastStartTime = Maps.newHashMap();

    @SerializedName("id")
    private long id;
    private UUID queryId;
    @SerializedName("qd")
    private String queryIdString;
    @SerializedName("dd")
    private long dbId;
    @SerializedName("td")
    private long tableId;
    @SerializedName("bd")
    private BrokerDesc brokerDesc;
    // exportPath has "/" suffix
    @SerializedName("ep")
    private String exportPath;
    private String exportTempPath;
    private String fileNamePrefix;
    @SerializedName("cs")
    private String columnSeparator;
    @SerializedName("rd")
    private String rowDelimiter;
    private boolean includeQueryId;
    @SerializedName("pt")
    private Map<String, String> properties = Maps.newHashMap();
    @SerializedName("ps")
    private List<String> partitions;
    @SerializedName("tn")
    private TableName tableName;
    private List<String> columnNames;
    private String sql = "";
    @SerializedName("se")
    private JobState state;
    @SerializedName("ct")
    private long createTimeMs;
    @SerializedName("st")
    private long startTimeMs;
    @SerializedName("ft")
    private long finishTimeMs;
    @SerializedName("pg")
    private int progress;
    @SerializedName("fm")
    private ExportFailMsg failMsg;
    @SerializedName("warehouseId")
    private long warehouseId = WarehouseManager.DEFAULT_WAREHOUSE_ID;
    // the resource used by this export job, it will be acquired from warehouse manager
    private ComputeResource computeResource = WarehouseManager.DEFAULT_RESOURCE;

    private TupleDescriptor exportTupleDesc;
    private Table exportTable;
    // when set to true, means this job instance is created by replay thread(FE restarted or master changed)
    private boolean isReplayed = false;
    private Thread doExportingThread;
    private List<TScanRangeLocations> tabletLocations = Lists.newArrayList();

    public ExportJob() {
        this.id = -1;
        this.queryId = null;
        this.dbId = -1;
        this.tableId = -1;
        this.state = JobState.PENDING;
        this.progress = 0;
        this.createTimeMs = System.currentTimeMillis();
        this.startTimeMs = -1;
        this.finishTimeMs = -1;
        this.failMsg = new ExportFailMsg(ExportFailMsg.CancelType.UNKNOWN, "");
        this.desc = new DescriptorTable();
        this.exportPath = "";
        this.exportTempPath = "";
        this.fileNamePrefix = "";
        this.columnSeparator = "\t";
        this.rowDelimiter = "\n";
        this.includeQueryId = true;
    }

    public ExportJob(long jobId, UUID queryId) {
        this();
        this.id = jobId;
        this.queryId = queryId;
        this.queryIdString = queryId.toString();
    }

    public ExportJob(long jobId, UUID queryId, long warehouseId) {
        this(jobId, queryId);
        this.warehouseId = warehouseId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public void setJob(ExportStmt stmt) throws StarRocksException {
        final WarehouseManager warehouseManager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
        // try to acquire resource from warehouse
        CRAcquireContext acquireContext = CRAcquireContext.of(this.warehouseId, this.computeResource);
        this.computeResource =  warehouseManager.acquireComputeResource(acquireContext);

        String dbName = stmt.getTblName().getDb();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        if (db == null) {
            throw new DdlException("Database " + dbName + " does not exist");
        }

        this.brokerDesc = stmt.getBrokerDesc();
        Preconditions.checkNotNull(brokerDesc);

        this.columnSeparator = stmt.getColumnSeparator();
        this.rowDelimiter = stmt.getRowDelimiter();
        this.includeQueryId = stmt.isIncludeQueryId();
        this.properties = stmt.getProperties();

        exportPath = stmt.getPath();
        Preconditions.checkArgument(!Strings.isNullOrEmpty(exportPath));
        exportTempPath = this.exportPath + "__starrocks_export_tmp_" + queryId.toString();
        fileNamePrefix = stmt.getFileNamePrefix();
        Preconditions.checkArgument(!Strings.isNullOrEmpty(fileNamePrefix));
        if (includeQueryId) {
            fileNamePrefix += queryId.toString() + "_";
        }

        this.partitions = stmt.getPartitions();
        this.columnNames = stmt.getColumnNames();

        this.dbId = db.getId();
        this.exportTable = GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(db.getFullName(), stmt.getTblName().getTbl());
        if (exportTable == null) {
            throw new DdlException("Table " + stmt.getTblName().getTbl() + " does not exist");
        }
        this.tableId = exportTable.getId();
        this.tableName = stmt.getTblName();

        try (AutoCloseableLock ignore = new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(this.tableId),
                    LockType.READ)) {
            genExecFragment(stmt);
        }

        this.sql = stmt.toSql();
    }

    private void genExecFragment(ExportStmt stmt) throws StarRocksException {
        registerToDesc();
        plan(stmt);
    }

    private void registerToDesc() throws StarRocksException {
        TableRef ref = new TableRef(tableName, null, partitions == null ? null : new PartitionNames(false, partitions));
        BaseTableRef tableRef = new BaseTableRef(ref, exportTable, tableName);
        exportTupleDesc = desc.createTupleDescriptor();
        exportTupleDesc.setTable(exportTable);
        exportTupleDesc.setRef(tableRef);

        Map<String, Column> nameToColumn = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        List<Column> tableColumns = exportTable.getBaseSchema();
        List<Column> exportColumns = Lists.newArrayList();
        for (Column column : tableColumns) {
            nameToColumn.put(column.getName(), column);
        }
        if (columnNames == null) {
            exportColumns.addAll(tableColumns);
        } else {
            for (String columnName : columnNames) {
                if (!nameToColumn.containsKey(columnName)) {
                    throw new StarRocksException("Column [" + columnName + "] does not exist in table.");
                }
                exportColumns.add(nameToColumn.get(columnName));
            }
        }

        for (Column col : exportColumns) {
            SlotDescriptor slot = desc.addSlotDescriptor(exportTupleDesc);
            slot.setIsMaterialized(true);
            slot.setColumn(col);
            slot.setIsNullable(col.isAllowNull());
        }
        desc.computeMemLayout();
    }

    private void plan(ExportStmt stmt) throws StarRocksException {
        List<PlanFragment> fragments = Lists.newArrayList();
        List<ScanNode> scanNodes = Lists.newArrayList();

        ScanNode scanNode = genScanNode();
        tabletLocations = scanNode.getScanRangeLocations(0);
        if (tabletLocations == null) {
            // not olap scan node
            PlanFragment fragment = genPlanFragment(exportTable.getType(), scanNode, 0);
            scanNodes.add(scanNode);
            fragments.add(fragment);
        } else {
            genTaskFragments(fragments, scanNodes);
        }

        genCoordinators(stmt, fragments, scanNodes);
    }

    private void genTaskFragments(List<PlanFragment> fragments, List<ScanNode> scanNodes) throws StarRocksException {
        Preconditions.checkNotNull(tabletLocations);

        for (TScanRangeLocations tablet : tabletLocations) {
            List<TScanRangeLocation> locations = tablet.getLocations();
            Collections.shuffle(locations);
            tablet.setLocations(locations.subList(0, 1));
        }

        long maxBytesPerBe = Config.export_max_bytes_per_be_per_task;
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
        List<TScanRangeLocations> copyTabletLocations = Lists.newArrayList(tabletLocations);
        int taskIdx = 0;
        while (!copyTabletLocations.isEmpty()) {
            Map<Long, Long> bytesPerBe = Maps.newHashMap();
            List<TScanRangeLocations> taskTabletLocations = Lists.newArrayList();
            Iterator<TScanRangeLocations> iter = copyTabletLocations.iterator();
            while (iter.hasNext()) {
                TScanRangeLocations scanRangeLocations = iter.next();
                long backendId = scanRangeLocations.getLocations().get(0).getBackend_id();
                long tabletId = scanRangeLocations.getScan_range().getInternal_scan_range().getTablet_id();
                TabletMeta tabletMeta = invertedIndex.getTabletMeta(tabletId);
                long dataSize = 0L;
                if (tabletMeta.isLakeTablet()) {
                    PhysicalPartition partition = exportTable.getPhysicalPartition(tabletMeta.getPhysicalPartitionId());
                    if (partition != null) {
                        MaterializedIndex index = partition.getIndex(tabletMeta.getIndexId());
                        if (index != null) {
                            Tablet tablet = index.getTablet(tabletId);
                            if (tablet != null) {
                                dataSize = tablet.getDataSize(true);
                            }
                        }
                    }
                } else {
                    Replica replica = invertedIndex.getReplica(tabletId, backendId);
                    dataSize = replica != null ? replica.getDataSize() : 0L;
                }

                Long assignedBytes = bytesPerBe.get(backendId);
                if (assignedBytes == null || assignedBytes < maxBytesPerBe) {
                    taskTabletLocations.add(scanRangeLocations);
                    bytesPerBe.put(backendId, assignedBytes != null ? assignedBytes + dataSize : dataSize);
                    iter.remove();
                }
            }

            OlapScanNode taskScanNode = genOlapScanNodeByLocation(taskTabletLocations);
            scanNodes.add(taskScanNode);
            PlanFragment fragment = genPlanFragment(exportTable.getType(), taskScanNode, taskIdx++);
            fragments.add(fragment);
        }

        LOG.info("total {} tablets of export job {}, and assign them to {} coordinators",
                    tabletLocations.size(), id, fragments.size());
    }

    private ScanNode genScanNode() throws StarRocksException {
        ScanNode scanNode = null;
        switch (exportTable.getType()) {
            case OLAP:
            case CLOUD_NATIVE:
                scanNode = new OlapScanNode(new PlanNodeId(0), exportTupleDesc, "OlapScanNodeForExport", computeResource);
                scanNode.setColumnFilters(Maps.newHashMap());
                ((OlapScanNode) scanNode).setIsPreAggregation(false, "This an export operation");
                ((OlapScanNode) scanNode).setCanTurnOnPreAggr(false);
                ((OlapScanNode) scanNode).computePartitionInfo();
                ((OlapScanNode) scanNode).selectBestRollupByRollupSelector();
                break;
            case MYSQL:
                scanNode = new MysqlScanNode(new PlanNodeId(0), exportTupleDesc, (MysqlTable) this.exportTable);
                break;
            default:
                throw new StarRocksException("Unsupported table type: " + exportTable.getType());
        }

        scanNode.finalizeStats();
        scanNode.setComputeResource(computeResource);
        return scanNode;
    }

    private OlapScanNode genOlapScanNodeByLocation(List<TScanRangeLocations> locations) {
        return OlapScanNode.createOlapScanNodeByLocation(
                    new PlanNodeId(nextId.getAndIncrement()),
                    exportTupleDesc,
                    "OlapScanNodeForExport",
                    locations,
                computeResource);
    }

    private PlanFragment genPlanFragment(Table.TableType type, ScanNode scanNode, int taskIdx) throws
            StarRocksException {
        PlanFragment fragment = null;
        switch (exportTable.getType()) {
            case OLAP:
            case CLOUD_NATIVE:
                fragment = new PlanFragment(
                            new PlanFragmentId(nextId.getAndIncrement()), scanNode, DataPartition.RANDOM);
                break;
            case MYSQL:
                fragment = new PlanFragment(
                            new PlanFragmentId(nextId.getAndIncrement()), scanNode, DataPartition.UNPARTITIONED);
                break;
            default:
                break;
        }
        if (fragment == null) {
            throw new StarRocksException("invalid table type:" + exportTable.getType());
        }
        fragment.setOutputExprs(createOutputExprs());

        scanNode.setFragmentId(fragment.getFragmentId());
        THdfsProperties hdfsProperties = new THdfsProperties();
        if (!brokerDesc.hasBroker()) {
            HdfsUtil.getTProperties(exportTempPath, brokerDesc, hdfsProperties);
        }
        fragment.setSink(new ExportSink(exportTempPath, fileNamePrefix + taskIdx + "_", columnSeparator,
                    rowDelimiter, brokerDesc, hdfsProperties));
        try {
            fragment.createDataSink(TResultSinkType.MYSQL_PROTOCAL);
        } catch (Exception e) {
            LOG.info("Fragment finalize failed. e=", e);
            throw new StarRocksException("Fragment finalize failed");
        }

        return fragment;
    }

    private List<Expr> createOutputExprs() {
        List<Expr> outputExprs = Lists.newArrayList();
        for (int i = 0; i < exportTupleDesc.getSlots().size(); ++i) {
            SlotDescriptor slotDesc = exportTupleDesc.getSlots().get(i);
            SlotRef slotRef = new SlotRef(slotDesc);
            if (slotDesc.getType().getPrimitiveType() == PrimitiveType.CHAR) {
                slotRef.setType(Type.CHAR);
            }
            outputExprs.add(slotRef);
        }

        return outputExprs;
    }

    private Coordinator.Factory getCoordinatorFactory() {
        return new DefaultCoordinator.Factory();
    }

    private void genCoordinators(ExportStmt stmt, List<PlanFragment> fragments, List<ScanNode> nodes) {
        UUID uuid = UUIDUtil.genUUID();
        for (int i = 0; i < fragments.size(); ++i) {
            PlanFragment fragment = fragments.get(i);
            ScanNode scanNode = nodes.get(i);
            TUniqueId queryId = new TUniqueId(uuid.getMostSignificantBits() + i, uuid.getLeastSignificantBits());
            Coordinator coord = getCoordinatorFactory().createBrokerExportScheduler(
                        id, queryId, desc, Lists.newArrayList(fragment), Lists.newArrayList(scanNode),
                        TimeUtils.DEFAULT_TIME_ZONE, stmt.getExportStartTime(),
                    Maps.newHashMap(), getMemLimit(), computeResource);
            this.coordList.add(coord);
            LOG.info("split export job to tasks. job id: {}, job query id: {}, task idx: {}, task query id: {}",
                        id, DebugUtil.printId(this.queryId), i, DebugUtil.printId(queryId));
        }
        LOG.info("create {} coordinators for export job: {}", coordList.size(), id);
    }

    // For olap table, it may have multiple replica, 
    // rebalance process may schedule tablet from one BE to another BE.
    // In such case, coord will return 'Not found tablet xxx' error. To solve this, 
    // we need to find a new replica for that tablet and generate a new coord.
    // Also, if the version has been compacted in one BE's tablet, coord will return 
    // 'version already been compacted' error msg, find a new replica may be able to 
    // alleviate this problem.
    public Coordinator resetCoord(int taskIndex, TUniqueId newQueryId) throws StarRocksException {
        Coordinator coord = coordList.get(taskIndex);
        OlapScanNode olapScanNode = (OlapScanNode) coord.getScanNodes().get(0);
        List<TScanRangeLocations> locations = olapScanNode.getScanRangeLocations(0);
        if (locations.size() == 0) {
            throw new StarRocksException("SubExportTask " + taskIndex + " scan range is empty");
        }

        OlapScanNode newOlapScanNode = new OlapScanNode(new PlanNodeId(0), exportTupleDesc, "OlapScanNodeForExport");
        newOlapScanNode.setColumnFilters(Maps.newHashMap());
        newOlapScanNode.setIsPreAggregation(false, "This an export operation");
        newOlapScanNode.setCanTurnOnPreAggr(false);
        newOlapScanNode.computePartitionInfo();
        newOlapScanNode.selectBestRollupByRollupSelector();
        List<TScanRangeLocations> newLocations = newOlapScanNode.updateScanRangeLocations(locations, computeResource);

        // random select a new location for each TScanRangeLocations
        for (TScanRangeLocations tablet : newLocations) {
            List<TScanRangeLocation> tabletLocations = tablet.getLocations();
            Collections.shuffle(tabletLocations);
            tablet.setLocations(tabletLocations.subList(0, 1));
        }

        OlapScanNode newTaskScanNode = genOlapScanNodeByLocation(newLocations);
        PlanFragment newFragment = genPlanFragment(exportTable.getType(), newTaskScanNode, taskIndex);

        Coordinator newCoord = getCoordinatorFactory().createBrokerExportScheduler(
                    id, newQueryId, desc, Lists.newArrayList(newFragment), Lists.newArrayList(newTaskScanNode),
                    TimeUtils.DEFAULT_TIME_ZONE, coord.getStartTimeMs(), Maps.newHashMap(), getMemLimit(), computeResource);
        this.coordList.set(taskIndex, newCoord);
        LOG.info("reset coordinator for export job: {}, taskIdx: {}", id, taskIndex);
        return newCoord;
    }

    public boolean needResetCoord() {
        return exportTable.isOlapTable();
    }

    public void setSnapshotPaths(List<Pair<TNetworkAddress, String>> snapshotPaths) {
        this.snapshotPaths = snapshotPaths;
    }

    public void setExportTempPath(String exportTempPath) {
        this.exportTempPath = exportTempPath;
    }

    public void setExportedFiles(Set<String> exportedFiles) {
        this.exportedFiles = exportedFiles;
    }

    public void setBeStartTime(long beId, long lastStartTime) {
        this.beLastStartTime.put(beId, lastStartTime);
    }

    public void setFailMsg(ExportFailMsg failMsg) {
        this.failMsg = failMsg;
    }

    public Map<Long, Long> getBeStartTimeMap() {
        return this.beLastStartTime;
    }

    public long getId() {
        return id;
    }

    public UUID getQueryId() {
        return queryId;
    }

    public long getDbId() {
        return dbId;
    }

    public long getTableId() {
        return this.tableId;
    }

    public JobState getState() {
        return state;
    }

    public BrokerDesc getBrokerDesc() {
        return brokerDesc;
    }

    public void setBrokerDesc(BrokerDesc brokerDesc) {
        this.brokerDesc = brokerDesc;
    }

    public String getExportPath() {
        return exportPath;
    }

    public String getColumnSeparator() {
        return this.columnSeparator;
    }

    public String getRowDelimiter() {
        return this.rowDelimiter;
    }

    public long getMemLimit() {
        // The key is exec_mem_limit before version 1.18, check first
        if (properties.containsKey(LoadStmt.LOAD_MEM_LIMIT)) {
            return Long.parseLong(properties.get(LoadStmt.LOAD_MEM_LIMIT));
        } else {
            return 0;
        }
    }

    public int getTimeoutSecond() {
        if (properties.containsKey(LoadStmt.TIMEOUT_PROPERTY)) {
            return Integer.parseInt(properties.get(LoadStmt.TIMEOUT_PROPERTY));
        } else {
            // for compatibility, some export job in old version does not have this property. use default.
            return Config.export_task_default_timeout_second;
        }
    }

    public List<String> getPartitions() {
        return partitions;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public synchronized int getProgress() {
        return progress;
    }

    public synchronized void setProgress(int progress) {
        this.progress = progress;
    }

    public long getCreateTimeMs() {
        return createTimeMs;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getFinishTimeMs() {
        return finishTimeMs;
    }

    public ExportFailMsg getFailMsg() {
        return failMsg;
    }

    public Set<String> getExportedTempFiles() {
        return this.exportedTempFiles;
    }

    public String getExportedTempPath() {
        return this.exportTempPath;
    }

    public Set<String> getExportedFiles() {
        return this.exportedFiles;
    }

    public synchronized void addExportedTempFiles(List<String> files) {
        exportedTempFiles.addAll(files);
        LOG.debug("exported temp files: {}", this.exportedTempFiles);
    }

    public synchronized void clearExportedTempFiles() {
        exportedTempFiles.clear();
    }

    public synchronized void addExportedFile(String file) {
        exportedFiles.add(file);
        LOG.debug("exported files: {}", this.exportedFiles);
    }

    public synchronized Thread getDoExportingThread() {
        return doExportingThread;
    }

    public synchronized void setDoExportingThread(Thread isExportingThread) {
        this.doExportingThread = isExportingThread;
    }

    public List<Coordinator> getCoordList() {
        return coordList;
    }

    public List<TScanRangeLocations> getTabletLocations() {
        return tabletLocations;
    }

    public List<Pair<TNetworkAddress, String>> getSnapshotPaths() {
        return this.snapshotPaths;
    }

    public void addSnapshotPath(Pair<TNetworkAddress, String> snapshotPath) {
        this.snapshotPaths.add(snapshotPath);
    }

    public String getSql() {
        return sql;
    }

    public TableName getTableName() {
        return tableName;
    }

    public synchronized boolean updateState(JobState newState) {
        return this.updateState(newState, false, System.currentTimeMillis());
    }

    public ComputeResource getComputeResource() {
        return computeResource;
    }

    public synchronized boolean updateState(JobState newState, boolean isReplay, long stateChangeTime) {
        if (isExportDone()) {
            LOG.warn("export job state is finished or cancelled");
            return false;
        }

        state = newState;
        switch (newState) {
            case PENDING:
                progress = 0;
                break;
            case EXPORTING:
                startTimeMs = stateChangeTime;
                break;
            case FINISHED:
            case CANCELLED:
                finishTimeMs = stateChangeTime;
                progress = 100;
                break;
            default:
                Preconditions.checkState(false, "wrong job state: " + newState.name());
                break;
        }
        if (!isReplay) {
            GlobalStateMgr.getCurrentState().getEditLog().logExportUpdateState(id, newState, stateChangeTime,
                        snapshotPaths, exportTempPath, exportedFiles, failMsg);
        }
        return true;
    }

    public Status releaseSnapshots() {
        switch (exportTable.getType()) {
            case OLAP:
            case MYSQL:
                return releaseSnapshotPaths();
            case CLOUD_NATIVE:
                return releaseMetadataLocks();
            default:
                return Status.OK;
        }
    }

    public Status releaseSnapshotPaths() {
        List<Pair<TNetworkAddress, String>> snapshotPaths = getSnapshotPaths();
        LOG.debug("snapshotPaths:{}", snapshotPaths);
        for (Pair<TNetworkAddress, String> snapshotPath : snapshotPaths) {
            TNetworkAddress address = snapshotPath.first;
            String host = address.getHostname();
            int port = address.getPort();

            Backend backend = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendWithBePort(host, port);
            if (backend == null) {
                continue;
            }
            long backendId = backend.getId();
            if (!GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().checkBackendAvailable(backendId)) {
                continue;
            }

            try {
                TAgentResult result = ThriftRPCRequestExecutor.callNoRetry(
                            ThriftConnectionPool.backendPool,
                            new TNetworkAddress(host, port),
                            client -> client.release_snapshot(snapshotPath.second)
                );
                if (result.getStatus().getStatus_code() != TStatusCode.OK) {
                    continue;
                }
            } catch (TException e) {
                continue;
            }
        }
        snapshotPaths.clear();
        return Status.OK;
    }

    public Status releaseMetadataLocks() {
        for (TScanRangeLocations tablet : tabletLocations) {
            TScanRange scanRange = tablet.getScan_range();
            if (!scanRange.isSetInternal_scan_range()) {
                continue;
            }

            TInternalScanRange internalScanRange = scanRange.getInternal_scan_range();
            List<TScanRangeLocation> locations = tablet.getLocations();
            for (TScanRangeLocation location : locations) {
                TNetworkAddress address = location.getServer();
                String host = address.getHostname();
                int port = address.getPort();
                ComputeNode node = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()
                            .getBackendOrComputeNodeWithBePort(host, port);
                if (!GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().checkNodeAvailable(node)) {
                    continue;
                }
                try {
                    LakeService lakeService = BrpcProxy.getLakeService(host, port);
                    UnlockTabletMetadataRequest request = new UnlockTabletMetadataRequest();
                    request.tabletId = internalScanRange.getTablet_id();
                    request.version = Long.parseLong(internalScanRange.getVersion());
                    request.expireTime = (getCreateTimeMs() / 1000) + getTimeoutSecond();
                    lakeService.unlockTabletMetadata(request);
                } catch (Throwable e) {
                    LOG.error("Fail to release metadata lock, job id {}, tablet id {}, version {}", id,
                                tableId, internalScanRange.getVersion());
                }
            }
        }
        return Status.OK;
    }

    public synchronized boolean isExportDone() {
        return state == JobState.FINISHED || state == JobState.CANCELLED;
    }

    public synchronized void cancel(ExportFailMsg.CancelType type, String msg) throws StarRocksException {
        if (isExportDone()) {
            throw new StarRocksException("Export job [" + queryId.toString() + "] is already finished or cancelled");
        }

        cancelInternal(type, msg);
    }

    public synchronized void cancelInternal(ExportFailMsg.CancelType type, String msg) {
        if (isExportDone()) {
            LOG.warn("export job state is finished or cancelled");
            return;
        }

        try {
            if (msg != null && failMsg.getCancelType() == ExportFailMsg.CancelType.UNKNOWN) {
                failMsg = new ExportFailMsg(type, msg);
            }

            // cancel all running coordinators
            for (Coordinator coord : coordList) {
                coord.cancel(msg);
            }

            // try to remove exported temp files
            try {
                if (!brokerDesc.hasBroker()) {
                    HdfsUtil.deletePath(exportTempPath, brokerDesc);
                } else {
                    BrokerUtil.deletePath(exportTempPath, brokerDesc);
                }
                LOG.info("remove export temp path success, path: {}", exportTempPath);
            } catch (StarRocksException e) {
                LOG.warn("remove export temp path fail, path: {}", exportTempPath);
            }
            // try to remove exported files
            for (String exportedFile : exportedFiles) {
                try {
                    if (!brokerDesc.hasBroker()) {
                        HdfsUtil.deletePath(exportedFile, brokerDesc);
                    } else {
                        BrokerUtil.deletePath(exportedFile, brokerDesc);
                    }
                    LOG.info("remove exported file success, path: {}", exportedFile);
                } catch (StarRocksException e) {
                    LOG.warn("remove exported file fail, path: {}", exportedFile);
                }
            }

            // release snapshot
            releaseSnapshots();
        } finally {
            updateState(ExportJob.JobState.CANCELLED);
            LOG.info("export job cancelled. job: {}", this);
        }
    }

    public synchronized void finish() {
        if (isExportDone()) {
            LOG.warn("export job state is finished or cancelled");
            return;
        }

        try {
            // release snapshot
            releaseSnapshots();

            // try to remove exported temp files
            try {
                if (!brokerDesc.hasBroker()) {
                    HdfsUtil.deletePath(exportTempPath, brokerDesc);
                } else {
                    BrokerUtil.deletePath(exportTempPath, brokerDesc);
                }
                LOG.info("remove export temp path success, path: {}", exportTempPath);
            } catch (StarRocksException e) {
                LOG.warn("remove export temp path fail, path: {}", exportTempPath);
            }
        } finally {
            updateState(JobState.FINISHED);
            LOG.info("export job finished. job: {}", this);
        }
    }

    @Override
    public String toString() {
        return "ExportJob [jobId=" + id
                    + ", dbId=" + dbId
                    + ", tableId=" + tableId
                    + ", state=" + state
                    + ", path=" + exportPath
                    + ", partitions=(" + StringUtils.join(partitions, ",") + ")"
                    + ", progress=" + progress
                    + ", createTimeMs=" + TimeUtils.longToTimeString(createTimeMs)
                    + ", exportStartTimeMs=" + TimeUtils.longToTimeString(startTimeMs)
                    + ", exportFinishTimeMs=" + TimeUtils.longToTimeString(finishTimeMs)
                    + ", failMsg=" + failMsg
                    + ", tmp files=(" + StringUtils.join(exportedTempFiles, ",") + ")"
                    + ", files=(" + StringUtils.join(exportedFiles, ",") + ")"
                    + "]";
    }



    /**
     * for ut only
     */
    public void setTableName(TableName tableName) {
        this.tableName = tableName;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof ExportJob)) {
            return false;
        }

        ExportJob job = (ExportJob) obj;

        return this.id == job.id;
    }

    public boolean isReplayed() {
        return isReplayed;
    }

    public boolean exportLakeTable() {
        return exportTable.isCloudNativeTableOrMaterializedView();
    }

    public boolean exportOlapTable() {
        return exportTable.isOlapTable();
    }

    public enum JobState {
        PENDING,
        EXPORTING,
        FINISHED,
        CANCELLED,
    }

    @Override
    public void gsonPostProcess() throws IOException {
        if (!Strings.isNullOrEmpty(queryIdString)) {
            queryId = UUID.fromString(queryIdString);
        }
        isReplayed = true;
        GlobalStateMgr stateMgr = GlobalStateMgr.getCurrentState();
        Database db = null;
        if (stateMgr.getLocalMetastore() != null) {
            db = stateMgr.getLocalMetastore().getDb(dbId);
        }
        if (db != null) {
            exportTable = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        }
    }

    // for only persist op when switching job state.
    public static class StateTransfer implements Writable {
        long jobId;
        JobState state;

        public StateTransfer() {
            this.jobId = -1;
            this.state = JobState.CANCELLED;
        }

        public StateTransfer(long jobId, JobState state) {
            this.jobId = jobId;
            this.state = state;
        }

        public long getJobId() {
            return jobId;
        }

        public JobState getState() {
            return state;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            out.writeLong(jobId);
            Text.writeString(out, state.name());
        }
    }

    public static class ExportUpdateInfo implements Writable {
        @SerializedName("jobId")
        long jobId;
        @SerializedName("state")
        JobState state;
        @SerializedName("stateChangeTime")
        long stateChangeTime;
        @SerializedName("snapshotPaths")
        List<Pair<NetworkAddress, String>> snapshotPaths;
        @SerializedName("exportTempPath")
        String exportTempPath;
        @SerializedName("exportedFiles")
        Set<String> exportedFiles;
        @SerializedName("failMsg")
        ExportFailMsg failMsg;

        public ExportUpdateInfo() {
            this.jobId = -1;
            this.state = JobState.CANCELLED;
            this.snapshotPaths = Lists.newArrayList();
            this.exportTempPath = "";
            this.exportedFiles = Sets.newConcurrentHashSet();
            this.failMsg = new ExportFailMsg();
        }

        public ExportUpdateInfo(long jobId, JobState state, long stateChangeTime,
                                List<Pair<TNetworkAddress, String>> snapshotPaths,
                                String exportTempPath, Set<String> exportedFiles, ExportFailMsg failMsg) {
            this.jobId = jobId;
            this.state = state;
            this.stateChangeTime = stateChangeTime;
            this.snapshotPaths = serialize(snapshotPaths);
            this.exportTempPath = exportTempPath;
            this.exportedFiles = exportedFiles;
            this.failMsg = failMsg;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = GsonUtils.GSON.toJson(this, ExportUpdateInfo.class);
            Text.writeString(out, json);

            // Due to TNetworkAddress unsupport to_json, snapshotPaths can not be seralized to GSON automatically,
            // here we manually seralize it
            out.writeInt(snapshotPaths.size());
            for (Pair<NetworkAddress, String> entry : snapshotPaths) {
                Text.writeString(out, entry.first.hostname);
                out.writeInt(entry.first.port);
                Text.writeString(out, entry.second);
            }
        }

        public static ExportUpdateInfo read(DataInput input) throws IOException {
            ExportUpdateInfo info = GsonUtils.GSON.fromJson(Text.readString(input), ExportUpdateInfo.class);

            int snapshotPathsLen = input.readInt();
            for (int i = 0; i < snapshotPathsLen; i++) {
                String hostName = Text.readString(input);
                int port = input.readInt();
                String path = Text.readString(input);
                Pair<NetworkAddress, String> entry = Pair.create(new NetworkAddress(hostName, port), path);
                info.snapshotPaths.set(i, entry);
            }

            return info;
        }

        public List<Pair<NetworkAddress, String>> serialize(List<Pair<TNetworkAddress, String>> snapshotPaths) {
            return snapshotPaths
                        .stream()
                        .map(snapshotPath
                                    -> Pair.create(new NetworkAddress(snapshotPath.first.hostname, snapshotPath.first.port),
                                    snapshotPath.second))
                        .collect(Collectors.toList());
        }

        public List<Pair<TNetworkAddress, String>> deserialize(List<Pair<NetworkAddress, String>> snapshotPaths) {
            return snapshotPaths
                        .stream()
                        .map(snapshotPath
                                    -> Pair.create(new TNetworkAddress(snapshotPath.first.hostname, snapshotPath.first.port),
                                    snapshotPath.second))
                        .collect(Collectors.toList());
        }
    }

    public static class NetworkAddress {
        @SerializedName("h")
        String hostname;
        @SerializedName("p")
        int port;

        public NetworkAddress() {

        }

        public NetworkAddress(String hostname, int port) {
            this.hostname = hostname;
            this.port = port;
        }

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof NetworkAddress
                        && NetUtils.isSameIP(this.hostname, ((NetworkAddress) obj).hostname)
                        && this.port == ((NetworkAddress) obj).port;
        }

        @Override
        public int hashCode() {
            return Objects.hash(hostname, port);
        }

        @Override
        public String toString() {
            return NetUtils.getHostPortInAccessibleFormat(hostname, port);
        }
    }
}
