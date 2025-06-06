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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/alter/MaterializedViewHandler.java

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

package com.starrocks.alter;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.Expr;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeConstants;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.ListComparator;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.concurrent.lock.AutoCloseableLock;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.persist.BatchDropInfo;
import com.starrocks.persist.DropInfo;
import com.starrocks.persist.EditLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.OriginStatement;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.analyzer.AstToSQLBuilder;
import com.starrocks.sql.ast.AddRollupClause;
import com.starrocks.sql.ast.AlterClause;
import com.starrocks.sql.ast.CancelAlterTableStmt;
import com.starrocks.sql.ast.CancelStmt;
import com.starrocks.sql.ast.CreateMaterializedViewStmt;
import com.starrocks.sql.ast.DropMaterializedViewStmt;
import com.starrocks.sql.ast.DropRollupClause;
import com.starrocks.sql.ast.MVColumnItem;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/*
 * MaterializedViewHandler is responsible for ADD/DROP materialized view.
 * For compatible with older version, it is also responsible for ADD/DROP rollup.
 * In function level, the mv completely covers the rollup in the future.
 * In grammar level, there is some difference between mv and rollup.
 */
public class MaterializedViewHandler extends AlterHandler {
    private static final Logger LOG = LogManager.getLogger(MaterializedViewHandler.class);

    public MaterializedViewHandler() {
        super("materialized view");
    }

    // for batch submit rollup job, tableId -> jobId
    // keep table's not final state job size. The job size determine's table's state, = 0 means table is normal, otherwise is rollup
    private final Map<Long, Set<Long>> tableNotFinalStateJobMap = new ConcurrentHashMap<>();
    // keep table's running job,used for concurrency limit
    // table id -> set of running job ids
    private final Map<Long, Set<Long>> tableRunningJobMap = new ConcurrentHashMap<>();

    @Override
    public void addAlterJobV2(AlterJobV2 alterJob) {
        super.addAlterJobV2(alterJob);
        addAlterJobV2ToTableNotFinalStateJobMap(alterJob);
    }

    protected void batchAddAlterJobV2(List<AlterJobV2> alterJobV2List) {
        for (AlterJobV2 alterJobV2 : alterJobV2List) {
            addAlterJobV2(alterJobV2);
        }
    }

    // return true iff job is actually added this time
    private void addAlterJobV2ToTableNotFinalStateJobMap(AlterJobV2 alterJobV2) {
        if (alterJobV2.isDone()) {
            LOG.info("try to add a final job({}) to a unfinal set. db: {}, tbl: {}",
                    alterJobV2.getJobId(), alterJobV2.getDbId(), alterJobV2.getTableId());
            return;
        }

        Long tableId = alterJobV2.getTableId();
        Long jobId = alterJobV2.getJobId();

        synchronized (tableNotFinalStateJobMap) {
            Set<Long> tableNotFinalStateJobIdSet =
                    tableNotFinalStateJobMap.computeIfAbsent(tableId, k -> new HashSet<>());
            tableNotFinalStateJobIdSet.add(jobId);
        }
    }

    /**
     * @param alterJobV2
     * @return true iif we really removed a job from tableNotFinalStateJobMap,
     * and there is no running job of this table
     * false otherwise.
     */
    private boolean removeAlterJobV2FromTableNotFinalStateJobMap(AlterJobV2 alterJobV2) {
        Long tableId = alterJobV2.getTableId();
        Long jobId = alterJobV2.getJobId();

        synchronized (tableNotFinalStateJobMap) {
            Set<Long> tableNotFinalStateJobIdset = tableNotFinalStateJobMap.get(tableId);
            if (tableNotFinalStateJobIdset == null) {
                // This could happen when this job is already removed before.
                // return false, so that we will not set table's to NORMAL again.
                return false;
            }
            tableNotFinalStateJobIdset.remove(jobId);
            if (tableNotFinalStateJobIdset.size() == 0) {
                tableNotFinalStateJobMap.remove(tableId);
                return true;
            }
            return false;
        }
    }

    /**
     * There are 2 main steps in this function.
     * Step1: validate the request.
     * Step1.1: semantic analysis: the name of olapTable must be same as the base table name in addMVClause.
     * Step1.2: base table validation: the status of base table and partition could be NORMAL.
     * Step1.3: materialized view validation: the name and columns of mv is checked.
     * Step2: create mv job
     *
     * @param addMVClause
     * @param db
     * @param olapTable
     * @throws DdlException
     */
    public void processCreateMaterializedView(CreateMaterializedViewStmt addMVClause, Database db, OlapTable olapTable)
            throws DdlException, AnalysisException {

        if (olapTable.existTempPartitions()) {
            throw new DdlException("Can not alter table when there are temp partitions in table");
        }

        // Step1.1: semantic analysis
        // TODO(ML): support the materialized view as base index
        if (!addMVClause.getBaseIndexName().equals(olapTable.getName())) {
            throw new DdlException("The name of table in from clause must be same as the name of alter table");
        }
        // Step1.2: base table validation
        String baseIndexName = addMVClause.getBaseIndexName();
        String mvIndexName = addMVClause.getMVName();
        LOG.info("process add materialized view[{}] based on [{}]", mvIndexName, baseIndexName);

        // avoid conflict against with batch add rollup job
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL);

        long baseIndexId = checkAndGetBaseIndex(baseIndexName, olapTable);
        // Step1.3: mv clause validation
        List<Column> mvColumns = checkAndPrepareMaterializedView(addMVClause, db, olapTable);

        // Step2: create mv job
        AlterJobV2 rollupJobV2 = createMaterializedViewJob(mvIndexName, baseIndexName, mvColumns,
                addMVClause.getWhereClause(), addMVClause.getProperties(), olapTable, db, baseIndexId,
                addMVClause.getMVKeysType(), addMVClause.getOrigStmt(), addMVClause.getQueryStatement());

        addAlterJobV2(rollupJobV2);
        olapTable.setState(OlapTableState.ROLLUP);
        GlobalStateMgr.getCurrentState().getEditLog().logAlterJob(rollupJobV2);

        LOG.info("finished to create materialized view job: {}", rollupJobV2.getJobId());
    }

    /**
     * There are 2 main steps.
     * Step1: validate the request
     * Step1.1: base table validation: the status of base table and partition could be NORMAL.
     * Step1.2: rollup validation: the name and columns of rollup is checked.
     * Step2: create rollup job
     *
     * @param alterClauses
     * @param db
     * @param olapTable
     * @throws DdlException
     * @throws AnalysisException
     */
    public void processBatchAddRollup(List<AlterClause> alterClauses, Database db, OlapTable olapTable)
            throws DdlException, AnalysisException {
        Map<String, AlterJobV2> rollupNameJobMap = new LinkedHashMap<>();
        // save job id for log
        Set<Long> logJobIdSet = new HashSet<>();

        try {
            // 1 check and make rollup job
            for (AlterClause alterClause : alterClauses) {
                AddRollupClause addRollupClause = (AddRollupClause) alterClause;

                // step 1 check whether current alter is change storage format
                String rollupIndexName = addRollupClause.getRollupName();
                if (rollupIndexName.equalsIgnoreCase(olapTable.getName())) {
                    throw new DdlException("Rollup name should be different with base table:" + olapTable.getName());
                }

                // get base index schema
                String baseIndexName = addRollupClause.getBaseRollupName();
                if (baseIndexName == null) {
                    // use table name as base table name
                    baseIndexName = olapTable.getName();
                }

                // step 2 alter clause validation
                // step 2.1 check whether base index already exists in globalStateMgr
                long baseIndexId = checkAndGetBaseIndex(baseIndexName, olapTable);

                // step 2.2  check rollup schema
                List<Column> rollupSchema =
                        checkAndPrepareMaterializedView(addRollupClause, olapTable, baseIndexId);

                // step 3 create rollup job
                AlterJobV2 alterJobV2 = createMaterializedViewJob(rollupIndexName, baseIndexName, rollupSchema,
                        null, addRollupClause.getProperties(),
                        olapTable, db, baseIndexId, olapTable.getKeysType(), null,
                        null);

                rollupNameJobMap.put(addRollupClause.getRollupName(), alterJobV2);
                logJobIdSet.add(alterJobV2.getJobId());
            }
        } catch (Exception e) {
            // remove tablet which has already inserted into TabletInvertedIndex
            TabletInvertedIndex tabletInvertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
            for (AlterJobV2 rollupJobV2 : rollupNameJobMap.values()) {
                for (MaterializedIndex index : rollupJobV2.getPartitionIdToRollupIndex().values()) {
                    for (Tablet tablet : index.getTablets()) {
                        tabletInvertedIndex.deleteTablet(tablet.getId());
                    }
                }
            }
            throw e;
        }

        // set table' state to ROLLUP before adding rollup jobs.
        // so that when the AlterHandler thread run the jobs, it will see the expected table's state.
        // ATTN: This order is not mandatory, because database lock will protect us,
        // but this order is more reasonable
        olapTable.setState(OlapTableState.ROLLUP);

        // 2 batch submit rollup job
        List<AlterJobV2> rollupJobV2List = new ArrayList<>(rollupNameJobMap.values());
        batchAddAlterJobV2(rollupJobV2List);

        BatchAlterJobPersistInfo batchAlterJobV2 = new BatchAlterJobPersistInfo(rollupJobV2List);
        GlobalStateMgr.getCurrentState().getEditLog().logBatchAlterJob(batchAlterJobV2);
        LOG.info("finished to create materialized view job: {}", logJobIdSet);
    }

    /**
     * Step1: All replicas of the materialized view index will be created in meta and added to TabletInvertedIndex
     * Step2: Set table's state to ROLLUP.
     *
     * @param mvName
     * @param baseIndexName
     * @param mvColumns
     * @param properties
     * @param olapTable
     * @param db
     * @param baseIndexId
     * @throws DdlException
     * @throws AnalysisException
     */
    private AlterJobV2 createMaterializedViewJob(String mvName, String baseIndexName, List<Column> mvColumns,
                                                  Expr whereClause, Map<String, String> properties,
                                                  OlapTable olapTable, Database db, long baseIndexId,
                                                  KeysType mvKeysType, OriginStatement origStmt,
                                                  QueryStatement queryStatement)
            throws DdlException, AnalysisException {
        if (mvKeysType == null) {
            // assign rollup index's key type, same as base index's
            mvKeysType = olapTable.getKeysType();
        }
        // get short key column count
        short mvShortKeyColumnCount = GlobalStateMgr.calcShortKeyColumnCount(mvColumns, properties);
        // get timeout
        long timeoutMs = PropertyAnalyzer.analyzeTimeout(properties, Config.alter_table_timeout_second) * 1000;

        // create rollup job
        long dbId = db.getId();
        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        long jobId = globalStateMgr.getNextId();
        long mvIndexId = globalStateMgr.getNextId();
        String viewDefineSql = null;
        if (queryStatement != null) {
            viewDefineSql = AstToSQLBuilder.toSQL(queryStatement);
        }

        // Check table is in colocate group if the mv wants to use colocate mv optimization.
        boolean isColocateMv = PropertyAnalyzer.analyzeBooleanProp(properties,
                PropertyAnalyzer.PROPERTIES_COLOCATE_MV, false) && whereClause == null;
        if (isColocateMv && Strings.isNullOrEmpty(olapTable.getColocateGroup())) {
            throw new AnalysisException(String.format("Please ensure table %s is in colocate group if you want to use " +
                    "mv colocate optimization.", olapTable.getName()));
        }

        long warehouseId = WarehouseManager.DEFAULT_WAREHOUSE_ID;
        ComputeResource computeResource = WarehouseManager.DEFAULT_RESOURCE;
        if (RunMode.isSharedDataMode()) {
            // check warehouse
            final ConnectContext connectContext = ConnectContext.get();
            computeResource = connectContext != null ?
                    connectContext.getCurrentComputeResource() : WarehouseManager.DEFAULT_RESOURCE;
            final WarehouseManager warehouseManager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
            if (!warehouseManager.isResourceAvailable(computeResource)) {
                Warehouse warehouse = GlobalStateMgr.getCurrentState().getWarehouseMgr().getWarehouse(warehouseId);
                throw new DdlException("no available compute nodes in warehouse " + warehouse.getName());
            }
        }

        AlterJobV2Builder mvJobBuilder = olapTable.rollUp();
        try {
            AlterJobV2 mvJob = mvJobBuilder
                    .withJobId(jobId)
                    .withDbId(dbId)
                    .withTimeoutSeconds(timeoutMs * 1000)
                    .withBaseIndexId(baseIndexId)
                    .withMvIndexId(mvIndexId)
                    .withBaseIndexName(baseIndexName)
                    .withMvName(mvName)
                    .withMvColumns(mvColumns)
                    .withWhereClause(whereClause)
                    .withMvShortkeyColumnCoun(mvShortKeyColumnCount)
                    .withOriginStmt(origStmt)
                    .withViewDefineSql(viewDefineSql)
                    .withMvKeysType(mvKeysType)
                    .withIsColocateMv(isColocateMv)
                    .withComputeResource(computeResource)
                    .build();

            LOG.info("finished to create materialized view job: {}", mvJob.getJobId());
            return mvJob;
        } catch (StarRocksException exception) {
            throw new DdlException(exception.getMessage());
        }
    }

    private List<Column> checkAndPrepareMaterializedView(CreateMaterializedViewStmt addMVClause, Database db,
                                                         OlapTable olapTable)
            throws DdlException {
        String mvName = addMVClause.getMVName();
        // ensure mv's name is unique in the db and olap table's materialized indexes
        {
            // check if mv index already exists in this table
            if (olapTable.hasMaterializedIndex(mvName)) {
                throw new DdlException("Materialized view[" + mvName + "] already exists in the table "
                        + olapTable.getName());
            }

            // check if mv index already exists in db

            if (GlobalStateMgr.getCurrentState().getLocalMetastore().mayGetTable(db.getFullName(), mvName).isPresent()) {
                throw new DdlException("Table [" + mvName + "] already exists in the db " + db.getFullName());
            }

            // check if mv index already exists in other table's materialized indexes
            for (Table tbl : GlobalStateMgr.getCurrentState().getLocalMetastore().getTables(db.getId())) {
                if (tbl.isOlapOrCloudNativeTable()) {
                    OlapTable otherOlapTable = (OlapTable) tbl;
                    if (otherOlapTable.getIndexNameToId().size() > 1 && otherOlapTable.hasMaterializedIndex(mvName)) {
                        throw new DdlException("Materialized view[" + mvName + "] already exists in table "
                                + tbl.getName());
                    }
                }
            }
        }

        // check if mv columns are valid
        // a. Aggregate or Unique table:
        //     1. For aggregate table, mv columns with aggregate function should be same as base schema
        //     2. For aggregate table, the column which is the key of base table should be the key of mv as well.
        // b. Duplicate table:
        //     1. Columns resolved by semantics are legal
        // update mv columns
        List<MVColumnItem> mvColumnItemList = addMVClause.getMVColumnItemList();
        List<Column> newMVColumns = Lists.newArrayList();
        int numOfKeys = 0;
        if (olapTable.getKeysType().isAggregationFamily()) {
            if (addMVClause.getMVKeysType() != KeysType.AGG_KEYS) {
                throw new DdlException(
                        "The materialized view of aggregation or unique table must has grouping columns");
            }
            for (MVColumnItem mvColumnItem : mvColumnItemList) {
                Set<String> baseColumnNames = mvColumnItem.getBaseColumnNames();
                String mvColumnName = mvColumnItem.getName();
                if (mvColumnItem.isKey()) {
                    ++numOfKeys;
                }
                for (String baseColumnName : baseColumnNames) {
                    Column baseColumn = olapTable.getColumn(baseColumnName);
                    Preconditions.checkNotNull(baseColumn,
                            "The materialized view column[" + mvColumnName + "] of aggregation or unique or primary table " +
                                    "cannot be transformed from original column[" +
                                    baseColumnName + "]");
                    AggregateType baseAggregationType = baseColumn.getAggregationType();
                    AggregateType mvAggregationType = mvColumnItem.getAggregationType();
                    if (baseColumn.isKey() && !mvColumnItem.isKey()) {
                        throw new DdlException("The column[" + mvColumnName + "] must be the key of materialized view");
                    }
                    if (baseAggregationType != mvAggregationType) {
                        throw new DdlException(
                                "The aggregation type of column[" + mvColumnName + "] must be same as the aggregate " +
                                        "type of base column in aggregate table");
                    }
                    if (baseAggregationType != null && baseAggregationType.isReplaceFamily() && olapTable
                            .getKeysNum() != numOfKeys) {
                        throw new DdlException(
                                "The materialized view should contain all keys of base table if there is a" + " REPLACE "
                                        + "value");
                    }
                }
                newMVColumns.add(mvColumnItem.toMVColumn(olapTable));
            }
        } else {
            Set<String> partitionOrDistributedColumnName =
                    olapTable.getPartitionColumnNames().stream().map(String::toLowerCase).collect(
                            Collectors.toSet());
            //The restriction on bucket column was temporarily opened
            //partitionOrDistributedColumnName.addAll(olapTable.getDistributionColumnNames());
            for (MVColumnItem mvColumnItem : mvColumnItemList) {
                Set<String> baseColumnNames = mvColumnItem.getBaseColumnNames();
                for (String baseColumnName : baseColumnNames) {
                    if (partitionOrDistributedColumnName.contains(baseColumnName.toLowerCase())
                            && mvColumnItem.getAggregationType() != null
                            && mvColumnItem.getAggregationType() != AggregateType.NONE) {
                        throw new DdlException("The partition columns " + baseColumnName
                                + " must be key column in mv");
                    }
                }
                newMVColumns.add(mvColumnItem.toMVColumn(olapTable));
            }
        }

        return newMVColumns;
    }

    public List<Column> checkAndPrepareMaterializedView(AddRollupClause addRollupClause, OlapTable olapTable, long baseIndexId)
            throws DdlException {
        String rollupIndexName = addRollupClause.getRollupName();
        List<String> rollupColumnNames = addRollupClause.getColumnNames();

        // 2. check if rollup index already exists
        if (olapTable.hasMaterializedIndex(rollupIndexName)) {
            throw new DdlException("Rollup index[" + rollupIndexName + "] already exists");
        }

        // 3. check if rollup columns are valid
        // a. all columns should exist in base rollup schema
        // b. value after key
        // c. if rollup contains REPLACE column, all keys on base index should be included.
        List<Column> rollupSchema = new ArrayList<Column>();
        // check (a)(b)
        boolean meetValue = false;
        boolean hasKey = false;
        boolean meetReplaceValue = false;
        KeysType keysType = olapTable.getKeysType();
        Map<String, Column> baseColumnNameToColumn = Maps.newHashMap();
        for (Column column : olapTable.getSchemaByIndexId(baseIndexId)) {
            baseColumnNameToColumn.put(column.getName(), column);
        }
        if (keysType.isAggregationFamily()) {
            int keysNumOfRollup = 0;
            for (String columnName : rollupColumnNames) {
                Column oneColumn = baseColumnNameToColumn.get(columnName);
                if (oneColumn == null) {
                    throw new DdlException("Column[" + columnName + "] does not exist");
                }
                if (oneColumn.isKey() && meetValue) {
                    throw new DdlException("Invalid column order. value should be after key");
                }
                if (oneColumn.isKey()) {
                    keysNumOfRollup += 1;
                    hasKey = true;
                } else {
                    meetValue = true;
                    if (oneColumn.getAggregationType().isReplaceFamily()) {
                        meetReplaceValue = true;
                    }
                }
                rollupSchema.add(oneColumn);
            }

            if (!hasKey) {
                throw new DdlException("No key column is found");
            }

            if (KeysType.UNIQUE_KEYS == keysType || meetReplaceValue) {
                // rollup of unique key table or rollup with REPLACE value
                // should have all keys of base table
                if (keysNumOfRollup != olapTable.getKeysNum()) {
                    if (KeysType.UNIQUE_KEYS == keysType) {
                        throw new DdlException("Rollup should contains all unique keys in basetable");
                    } else {
                        throw new DdlException("Rollup should contains all keys if there is a REPLACE value");
                    }
                }
            }
        } else if (KeysType.DUP_KEYS == keysType) {
            // supplement the duplicate key
            if (addRollupClause.getDupKeys() == null || addRollupClause.getDupKeys().isEmpty()) {
                // check the column meta
                for (String columnName : rollupColumnNames) {
                    Column baseColumn = baseColumnNameToColumn.get(columnName);
                    if (baseColumn == null) {
                        throw new DdlException("Column[" + columnName + "] does not exist in base index");
                    }
                    Column rollupColumn = new Column(baseColumn);
                    rollupSchema.add(rollupColumn);
                }
                // Supplement key of MV columns
                int theBeginIndexOfValue = 0;
                int keySizeByte = 0;
                for (; theBeginIndexOfValue < rollupSchema.size(); theBeginIndexOfValue++) {
                    Column column = rollupSchema.get(theBeginIndexOfValue);
                    keySizeByte += column.getType().getIndexSize();
                    if (theBeginIndexOfValue + 1 > FeConstants.SHORTKEY_MAX_COLUMN_COUNT
                            || keySizeByte > FeConstants.SHORTKEY_MAXSIZE_BYTES) {
                        if (theBeginIndexOfValue == 0 && column.getType().getPrimitiveType().isCharFamily()) {
                            column.setIsKey(true);
                            theBeginIndexOfValue++;
                        }
                        break;
                    }
                    if (column.getType().isFloatingPointType() || column.getType().isComplexType()) {
                        break;
                    }
                    if (column.getType().isVarchar()) {
                        column.setIsKey(true);
                        theBeginIndexOfValue++;
                        break;
                    }
                    column.setIsKey(true);
                }
                if (rollupSchema.isEmpty()) {
                    throw new DdlException("Empty rollup schema");
                }
                if (theBeginIndexOfValue == 0) {
                    throw new DdlException("Data type of first column cannot be " + rollupSchema.get(0).getType());
                }
                // Supplement value of MV columns
                for (; theBeginIndexOfValue < rollupSchema.size(); theBeginIndexOfValue++) {
                    Column rollupColumn = rollupSchema.get(theBeginIndexOfValue);
                    rollupColumn.setIsKey(false);
                    rollupColumn.setAggregationType(AggregateType.NONE, true);
                }
            } else {
                /*
                 * eg.
                 * Base Table's schema is (k1,k2,k3,k4,k5) dup key (k1,k2,k3).
                 * The following rollup is allowed:
                 * 1. (k1) dup key (k1)
                 * 2. (k2,k3) dup key (k2)
                 * 3. (k1,k2,k3) dup key (k1,k2)
                 *
                 * The following rollup is forbidden:
                 * 1. (k1) dup key (k2)
                 * 2. (k2,k3) dup key (k3,k2)
                 * 3. (k1,k2,k3) dup key (k2,k3)
                 */
                // user specify the duplicate keys for rollup index
                List<String> dupKeys = addRollupClause.getDupKeys();
                if (dupKeys.size() > rollupColumnNames.size()) {
                    throw new DdlException("Num of duplicate keys should less than or equal to num of rollup columns.");
                }

                for (int i = 0; i < rollupColumnNames.size(); i++) {
                    String rollupColName = rollupColumnNames.get(i);
                    boolean isKey = false;
                    if (i < dupKeys.size()) {
                        String dupKeyName = dupKeys.get(i);
                        if (!rollupColName.equalsIgnoreCase(dupKeyName)) {
                            throw new DdlException("Duplicate keys should be the prefix of rollup columns");
                        }
                        isKey = true;
                    }

                    Column baseColumn = baseColumnNameToColumn.get(rollupColName);
                    if (baseColumn == null) {
                        throw new DdlException("Column[" + rollupColName + "] does not exist");
                    }

                    if (isKey && meetValue) {
                        throw new DdlException("Invalid column order. key should before all values: " + rollupColName);
                    }

                    Column oneColumn = new Column(baseColumn);
                    if (isKey) {
                        hasKey = true;
                        oneColumn.setIsKey(true);
                        oneColumn.setAggregationType(null, false);
                    } else {
                        meetValue = true;
                        oneColumn.setIsKey(false);
                        oneColumn.setAggregationType(AggregateType.NONE, true);
                    }
                    rollupSchema.add(oneColumn);
                }
            }
        }
        return rollupSchema;
    }

    /**
     * @param baseIndexName
     * @param olapTable
     * @return
     * @throws DdlException
     */
    private long checkAndGetBaseIndex(String baseIndexName, OlapTable olapTable) throws DdlException {
        // up to here, table's state can only be NORMAL
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL, olapTable.getState().name());

        Long baseIndexId = olapTable.getIndexIdByName(baseIndexName);
        if (baseIndexId == null) {
            throw new DdlException("Base index[" + baseIndexName + "] does not exist");
        }
        // check state
        for (PhysicalPartition partition : olapTable.getPhysicalPartitions()) {
            MaterializedIndex baseIndex = partition.getIndex(baseIndexId);
            // up to here. index's state should only be NORMAL
            Preconditions.checkState(baseIndex.getState() == IndexState.NORMAL, baseIndex.getState().name());
        }
        return baseIndexId;
    }

    public void processBatchDropRollup(List<AlterClause> dropRollupClauses, Database db, OlapTable olapTable)
            throws DdlException, MetaNotFoundException {
        // check drop rollup index operation
        for (AlterClause alterClause : dropRollupClauses) {
            DropRollupClause dropRollupClause = (DropRollupClause) alterClause;
            checkDropMaterializedView(dropRollupClause.getRollupName(), olapTable);
        }

        // drop data in memory
        Set<Long> indexIdSet = new HashSet<>();
        Set<String> rollupNameSet = new HashSet<>();
        for (AlterClause alterClause : dropRollupClauses) {
            DropRollupClause dropRollupClause = (DropRollupClause) alterClause;
            String rollupIndexName = dropRollupClause.getRollupName();
            long rollupIndexId = dropMaterializedView(rollupIndexName, olapTable);
            indexIdSet.add(rollupIndexId);
            rollupNameSet.add(rollupIndexName);
        }

        // batch log drop rollup operation
        EditLog editLog = GlobalStateMgr.getCurrentState().getEditLog();
        long dbId = db.getId();
        long tableId = olapTable.getId();
        editLog.logBatchDropRollup(new BatchDropInfo(dbId, tableId, indexIdSet));
        LOG.info("finished drop rollup index[{}] in table[{}]", String.join("", rollupNameSet),
                olapTable.getName());
    }

    public void processDropMaterializedView(DropMaterializedViewStmt dropMaterializedViewStmt, Database db,
                                            OlapTable olapTable) throws DdlException, MetaNotFoundException {
        Locker locker = new Locker();
        Preconditions.checkState(locker.isDbWriteLockHeldByCurrentThread(db));
        try {
            String mvName = dropMaterializedViewStmt.getMvName();
            // Step1: check drop mv index operation
            checkDropMaterializedView(mvName, olapTable);
            // Step2; drop data in memory
            long mvIndexId = dropMaterializedView(mvName, olapTable);
            // Step3: log drop mv operation
            EditLog editLog = GlobalStateMgr.getCurrentState().getEditLog();
            editLog.logDropRollup(new DropInfo(db.getId(), olapTable.getId(), mvIndexId, false));
            LOG.info("finished drop materialized view [{}] in table [{}]", mvName, olapTable.getName());
        } catch (MetaNotFoundException e) {
            if (dropMaterializedViewStmt.isSetIfExists()) {
                LOG.info(e.getMessage());
            } else {
                throw e;
            }
        }
    }

    /**
     * Make sure we got db write lock before using this method.
     * Up to here, table's state can only be NORMAL.
     *
     * @param mvName
     * @param olapTable
     */
    private void checkDropMaterializedView(String mvName, OlapTable olapTable)
            throws DdlException, MetaNotFoundException {
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL, olapTable.getState().name());
        if (mvName.equals(olapTable.getName())) {
            throw new DdlException("Cannot drop base index by using DROP ROLLUP or DROP MATERIALIZED VIEW.");
        }

        if (!olapTable.hasMaterializedIndex(mvName)) {
            throw new MetaNotFoundException(
                    "Materialized view [" + mvName + "] does not exist in table [" + olapTable.getName() + "]");
        }

        long mvIndexId = olapTable.getIndexIdByName(mvName);
        int mvSchemaHash = olapTable.getSchemaHashByIndexId(mvIndexId);
        Preconditions.checkState(mvSchemaHash != -1);

        for (PhysicalPartition partition : olapTable.getPhysicalPartitions()) {
            MaterializedIndex materializedIndex = partition.getIndex(mvIndexId);
            Preconditions.checkNotNull(materializedIndex);
        }
    }

    /**
     * Return mv index id which has been dropped
     *
     * @param mvName
     * @param olapTable
     * @return
     */
    private long dropMaterializedView(String mvName, OlapTable olapTable) {
        long mvIndexId = olapTable.getIndexIdByName(mvName);
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
        for (PhysicalPartition partition : olapTable.getPhysicalPartitions()) {
            MaterializedIndex rollupIndex = partition.getIndex(mvIndexId);
            // delete rollup index
            partition.deleteRollupIndex(mvIndexId);
            // remove tablets from inverted index
            for (Tablet tablet : rollupIndex.getTablets()) {
                invertedIndex.deleteTablet(tablet.getId());
            }
        }
        olapTable.deleteIndexInfo(mvName);
        return mvIndexId;
    }

    public void replayDropRollup(DropInfo dropInfo, GlobalStateMgr globalStateMgr) {
        Database db = globalStateMgr.getLocalMetastore().getDb(dropInfo.getDbId());
        long tableId = dropInfo.getTableId();
        long rollupIndexId = dropInfo.getIndexId();
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);

        try (AutoCloseableLock ignore =
                     new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tableId), LockType.WRITE)) {
            TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();

            for (PhysicalPartition partition : olapTable.getPhysicalPartitions()) {
                MaterializedIndex rollupIndex = partition.deleteRollupIndex(rollupIndexId);

                if (!GlobalStateMgr.isCheckpointThread()) {
                    // remove from inverted index
                    for (Tablet tablet : rollupIndex.getTablets()) {
                        invertedIndex.deleteTablet(tablet.getId());
                    }
                }
            }

            String rollupIndexName = olapTable.getIndexNameById(rollupIndexId);
            olapTable.deleteIndexInfo(rollupIndexName);
        }
        LOG.info("replay drop rollup {}", dropInfo.getIndexId());
    }

    @Override
    protected void runAfterCatalogReady() {
        super.runAfterCatalogReady();
        runAlterJobV2();
    }

    private Map<Long, AlterJobV2> getAlterJobsCopy() {
        return new HashMap<>(alterJobsV2);
    }

    private void removeJobFromRunningQueue(AlterJobV2 alterJob) {
        synchronized (tableRunningJobMap) {
            Set<Long> runningJobIdSet = tableRunningJobMap.get(alterJob.getTableId());
            if (runningJobIdSet != null) {
                runningJobIdSet.remove(alterJob.getJobId());
                if (runningJobIdSet.size() == 0) {
                    tableRunningJobMap.remove(alterJob.getTableId());
                }
            }
        }
    }

    private void changeTableStatus(long dbId, long tableId, OlapTableState olapTableState) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            LOG.warn("db {} has been dropped when changing table {} status after rollup job done",
                    dbId, tableId);
            return;
        }
        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            return;
        }

        Locker locker = new Locker();
        locker.lockTablesWithIntensiveDbLock(db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE);
        try {
            if (tbl.getState() == olapTableState) {
                return;
            }
            tbl.setState(olapTableState);
        } finally {
            locker.unLockTablesWithIntensiveDbLock(db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE);
        }
    }

    // replay the alter job v2
    @Override
    public void replayAlterJobV2(AlterJobV2 alterJob) {
        super.replayAlterJobV2(alterJob);
        if (!alterJob.isDone()) {
            addAlterJobV2ToTableNotFinalStateJobMap(alterJob);
            changeTableStatus(alterJob.getDbId(), alterJob.getTableId(), OlapTableState.ROLLUP);
        } else {
            if (removeAlterJobV2FromTableNotFinalStateJobMap(alterJob)) {
                changeTableStatus(alterJob.getDbId(), alterJob.getTableId(), OlapTableState.NORMAL);
            }
        }
    }

    /**
     * create tablet and alter tablet in be is thread safe,so we can run rollup job for one table concurrently
     */
    private void runAlterJobWithConcurrencyLimit(AlterJobV2 rollupJobV2) {
        if (rollupJobV2.isDone()) {
            return;
        }

        if (rollupJobV2.isTimeout()) {
            // in run(), the timeout job will be cancelled.
            rollupJobV2.run();
            return;
        }

        // check if rollup job can be run within limitation.
        long tblId = rollupJobV2.getTableId();
        long jobId = rollupJobV2.getJobId();
        boolean shouldJobRun = false;
        synchronized (tableRunningJobMap) {
            Set<Long> tableRunningJobSet = tableRunningJobMap.computeIfAbsent(tblId, k -> new HashSet<>());

            // current job is already in running
            if (tableRunningJobSet.contains(jobId)) {
                shouldJobRun = true;
            } else if (tableRunningJobSet.size() < Config.max_running_rollup_job_num_per_table) {
                // add current job to running queue
                tableRunningJobSet.add(jobId);
                shouldJobRun = true;
            } else {
                LOG.debug("number of running alter job {} in table {} exceed limit {}. job {} is suspended",
                        tableRunningJobSet.size(), rollupJobV2.getTableId(),
                        Config.max_running_rollup_job_num_per_table, rollupJobV2.getJobId());
            }
        }

        if (shouldJobRun) {
            rollupJobV2.run();
        }
    }

    private void runAlterJobV2() {
        for (Map.Entry<Long, AlterJobV2> entry : getAlterJobsCopy().entrySet()) {
            AlterJobV2 alterJob = entry.getValue();
            // run alter job
            runAlterJobWithConcurrencyLimit(alterJob);
            // the following check should be right after job's running, so that the table's state
            // can be changed to NORMAL immediately after the last alter job of the table is done.
            //
            // ATTN(cmy): there is still a short gap between "job finish" and "table become normal",
            // so if user send next alter job right after the "job finish",
            // it may encounter "table's state not NORMAL" error.

            if (alterJob.isDone()) {
                onJobDone(alterJob);
            }
        }
    }

    // remove job from running queue and state map, also set table's state to NORMAL if this is
    // the last running job of the table.
    private void onJobDone(AlterJobV2 alterJob) {
        removeJobFromRunningQueue(alterJob);
        if (removeAlterJobV2FromTableNotFinalStateJobMap(alterJob)) {
            changeTableStatus(alterJob.getDbId(), alterJob.getTableId(), OlapTableState.NORMAL);
        }
    }

    @Override
    public List<List<Comparable>> getAlterJobInfosByDb(Database db) {
        List<List<Comparable>> rollupJobInfos = new LinkedList<List<Comparable>>();

        getAlterJobV2Infos(db, rollupJobInfos);

        // sort by
        // "JobId", "TableName", "CreateTime", "FinishedTime", "BaseIndexName", "RollupIndexName"
        ListComparator<List<Comparable>> comparator = new ListComparator<List<Comparable>>(0, 1, 2, 3, 4, 5);
        Collections.sort(rollupJobInfos, comparator);

        return rollupJobInfos;
    }

    private void getAlterJobV2Infos(Database db, List<List<Comparable>> rollupJobInfos) {
        for (AlterJobV2 alterJob : alterJobsV2.values()) {
            if (alterJob.getDbId() != db.getId()) {
                continue;
            }
            alterJob.getInfo(rollupJobInfos);
        }
    }

    @Override
    public ShowResultSet process(List<AlterClause> alterClauses, Database db, OlapTable olapTable)
            throws DdlException, AnalysisException, MetaNotFoundException {
        if (olapTable.existTempPartitions()) {
            throw new DdlException("Can not alter table when there are temp partitions in table");
        }
        if (GlobalStateMgr.getCurrentState().getInsertOverwriteJobMgr().hasRunningOverwriteJob(olapTable.getId())) {
            throw new DdlException("Table[" + olapTable.getName() + "] is doing insert overwrite job, " +
                    "please create materialized view after insert overwrite");
        }
        Optional<AlterClause> alterClauseOptional = alterClauses.stream().findAny();
        if (alterClauseOptional.isPresent()) {
            if (alterClauseOptional.get() instanceof AddRollupClause) {
                if (olapTable.getKeysType() == KeysType.PRIMARY_KEYS) {
                    throw new DdlException(
                            "Do not support add rollup on primary key table[" + olapTable.getName() + "]");
                }
                processBatchAddRollup(alterClauses, db, olapTable);
            } else if (alterClauseOptional.get() instanceof DropRollupClause) {
                processBatchDropRollup(alterClauses, db, olapTable);
            } else {
                Preconditions.checkState(false);
            }
        }
        return null;
    }

    @Override
    public void cancel(CancelStmt stmt) throws DdlException {
        cancel(stmt, "user cancelled");
    }

    public void cancel(CancelStmt stmt, String reason) throws DdlException {
        CancelAlterTableStmt cancelAlterTableStmt = (CancelAlterTableStmt) stmt;

        String dbName = cancelAlterTableStmt.getDbName();
        String tableName = cancelAlterTableStmt.getTableName();
        Preconditions.checkState(!Strings.isNullOrEmpty(dbName));
        Preconditions.checkState(!Strings.isNullOrEmpty(tableName));

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        List<AlterJobV2> rollupJobV2List = new ArrayList<>();
        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), tableName);
        if (table == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName);
        }

        Locker locker = new Locker();
        locker.lockTablesWithIntensiveDbLock(db.getId(), Lists.newArrayList(table.getId()), LockType.WRITE);
        try {
            if (!(table instanceof OlapTable)) {
                ErrorReport.reportDdlException(ErrorCode.ERR_NOT_OLAP_TABLE, tableName);
            }
            OlapTable olapTable = (OlapTable) table;
            if (olapTable.getState() != OlapTableState.ROLLUP
                    && olapTable.getState() != OlapTableState.WAITING_STABLE) {
                throw new DdlException("Table[" + tableName + "] is not under ROLLUP/WAITING_STABLE. "
                        + "Use 'ALTER TABLE DROP ROLLUP' if you want to.");
            }

            // find from new alter jobs first
            if (cancelAlterTableStmt.getAlterJobIdList() != null) {
                for (Long jobId : cancelAlterTableStmt.getAlterJobIdList()) {
                    AlterJobV2 alterJobV2 = getUnfinishedAlterJobV2ByJobId(jobId);
                    if (alterJobV2 == null) {
                        continue;
                    }
                    rollupJobV2List.add(getUnfinishedAlterJobV2ByJobId(jobId));
                }
            } else {
                rollupJobV2List = getUnfinishedAlterJobV2ByTableId(olapTable.getId());
            }
            if (rollupJobV2List.size() == 0) {
                throw new DdlException("Table[" + tableName + "] is under ROLLUP but job does not exist.");
            }
        } finally {
            locker.unLockTablesWithIntensiveDbLock(db.getId(), Lists.newArrayList(table.getId()), LockType.WRITE);
        }

        // alter job v2's cancel must be called outside the database lock
        for (AlterJobV2 alterJobV2 : rollupJobV2List) {
            alterJobV2.cancel(reason);
            if (alterJobV2.isDone()) {
                onJobDone(alterJobV2);
            }
        }
    }

    public void cancelMV(CancelStmt stmt) throws DdlException {
        CancelAlterTableStmt cancelAlterTableStmt = (CancelAlterTableStmt) stmt;

        String dbName = cancelAlterTableStmt.getDbName();
        String tableName = cancelAlterTableStmt.getTableName();
        Preconditions.checkState(!Strings.isNullOrEmpty(dbName));
        Preconditions.checkState(!Strings.isNullOrEmpty(tableName));

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }
        if (tableName.isEmpty()) {
            throw new DdlException("table name should include in cancel materialized view from db-name.mv-name");
        }

        AlterJobV2 materializedViewJob = null;
        Locker locker = new Locker();
        locker.lockDatabase(db.getId(), LockType.WRITE);
        try {
            for (Table table : GlobalStateMgr.getCurrentState().getLocalMetastore().getTables(db.getId())) {
                if (table instanceof OlapTable) {
                    List<AlterJobV2> rollupJobV2List = getUnfinishedAlterJobV2ByTableId(table.getId());
                    for (AlterJobV2 alterJobV2 : rollupJobV2List) {
                        if (alterJobV2 instanceof RollupJobV2) {
                            if (((RollupJobV2) alterJobV2).getRollupIndexName().equals(tableName)) {
                                materializedViewJob = alterJobV2;
                            }
                        } else if (alterJobV2 instanceof LakeRollupJob) {
                            if (((LakeRollupJob) alterJobV2).getRollupIndexName().equals(tableName)) {
                                materializedViewJob = alterJobV2;
                            }
                        }
                    }
                }
            }
        } finally {
            locker.unLockDatabase(db.getId(), LockType.WRITE);
        }

        if (materializedViewJob == null) {
            throw new DdlException("Table[" + tableName + "] is not under MATERIALIZED VIEW. "
                    + "Use 'DROP MATERIALIZED VIEW' if you want to.");
        } else {
            // alter job v2's cancel must be called outside the database lock
            materializedViewJob.cancel("user cancelled");
            if (materializedViewJob.isDone()) {
                onJobDone(materializedViewJob);
            }
        }
    }

    // just for ut
    public Map<Long, Set<Long>> getTableRunningJobMap() {
        return tableRunningJobMap;
    }

}
