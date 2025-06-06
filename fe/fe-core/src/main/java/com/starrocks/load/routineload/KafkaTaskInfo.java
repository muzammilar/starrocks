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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/load/routineload/KafkaTaskInfo.java

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

package com.starrocks.load.routineload;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.KafkaUtil;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.load.streamload.StreamLoadTask;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TExecPlanFragmentParams;
import com.starrocks.thrift.TFileFormatType;
import com.starrocks.thrift.TKafkaLoadInfo;
import com.starrocks.thrift.TLoadSourceType;
import com.starrocks.thrift.TRoutineLoadTask;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.transaction.DatabaseTransactionMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.starrocks.common.ErrorCode.ERR_ROUTINE_LOAD_OFFSET_INVALID;

public class KafkaTaskInfo extends RoutineLoadTaskInfo {
    private static final Logger LOG = LogManager.getLogger(KafkaTaskInfo.class);

    private RoutineLoadMgr routineLoadManager = GlobalStateMgr.getCurrentState().getRoutineLoadMgr();

    // <partitionId, beginOffsetOfPartitionId>
    private Map<Integer, Long> partitionIdToOffset;

    // the latest offset before task submitted to be
    // offset is the latest existing message offset + 1
    private Map<Integer, Long> latestPartOffset;

    public KafkaTaskInfo(UUID id, RoutineLoadJob job, long taskScheduleIntervalMs, long timeToExecuteMs,
                         Map<Integer, Long> partitionIdToOffset, long taskTimeoutMs) {
        super(id, job, taskScheduleIntervalMs, timeToExecuteMs, taskTimeoutMs);
        this.partitionIdToOffset = partitionIdToOffset;
    }

    public KafkaTaskInfo(long timeToExecuteMs, KafkaTaskInfo kafkaTaskInfo, Map<Integer, Long> partitionIdToOffset,
                         Map<Integer, Long> latestPartOffset) {
        this(timeToExecuteMs, kafkaTaskInfo, partitionIdToOffset, kafkaTaskInfo.getTimeoutMs());
    }

    public KafkaTaskInfo(long timeToExecuteMs, KafkaTaskInfo kafkaTaskInfo, Map<Integer, Long> partitionIdToOffset,
                         long tastTimeoutMs) {
        super(UUIDUtil.genUUID(), kafkaTaskInfo.getJob(), kafkaTaskInfo.getTaskScheduleIntervalMs(), timeToExecuteMs,
                kafkaTaskInfo.getBeId(), tastTimeoutMs);
        this.partitionIdToOffset = partitionIdToOffset;
    }

    public List<Integer> getPartitions() {
        return new ArrayList<>(partitionIdToOffset.keySet());
    }

    // checkReadyToExecuteFast compares the local latest partition offset and the consumed offset.
    public boolean checkReadyToExecuteFast() {
        KafkaRoutineLoadJob kafkaRoutineLoadJob = (KafkaRoutineLoadJob) job;
        for (Map.Entry<Integer, Long> entry : partitionIdToOffset.entrySet()) {
            int partitionId = entry.getKey();
            Long consumeOffset = entry.getValue();
            Long localLatestOffset = kafkaRoutineLoadJob.getPartitionOffset(partitionId);
            // If any partition has newer data, the task should be scheduled.
            if (localLatestOffset != null && localLatestOffset > consumeOffset) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean readyToExecute() throws StarRocksException {
        if (checkReadyToExecuteFast()) {
            return true;
        }

        KafkaRoutineLoadJob kafkaRoutineLoadJob = (KafkaRoutineLoadJob) job;
        Map<Integer, Long> latestOffsets = KafkaUtil.getLatestOffsets(kafkaRoutineLoadJob.getBrokerList(),
                kafkaRoutineLoadJob.getTopic(),
                ImmutableMap.copyOf(kafkaRoutineLoadJob.getConvertedCustomProperties()),
                new ArrayList<>(partitionIdToOffset.keySet()), getComputeResource());
        for (Map.Entry<Integer, Long> entry : latestOffsets.entrySet()) {
            kafkaRoutineLoadJob.setPartitionOffset(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<Integer, Long> entry : partitionIdToOffset.entrySet()) {
            int partitionId = entry.getKey();
            Long latestOffset = latestOffsets.get(partitionId);
            Long consumeOffset = entry.getValue();
            if (latestOffset != null) {
                if (latestOffset > consumeOffset) {
                    this.latestPartOffset = latestOffsets;
                    return true;
                } else if (latestOffset < consumeOffset) {
                    throw new RoutineLoadPauseException(
                            ERR_ROUTINE_LOAD_OFFSET_INVALID.formatErrorMsg(consumeOffset, latestOffset, partitionId));
                }
            }
        }

        return false;
    }

    @Override
    public boolean isProgressKeepUp(RoutineLoadProgress progress) {
        KafkaProgress kProgress = (KafkaProgress) progress;
        if (latestPartOffset == null) {
            return true;
        }

        for (Map.Entry<Integer, Long> entry : latestPartOffset.entrySet()) {
            int part = entry.getKey();
            Long latestOffset = entry.getValue();
            Long consumedOffset = kProgress.getOffsetByPartition(part);
            if (consumedOffset != null && consumedOffset < latestOffset - 1) {
                return false;
            }
        }
        return true;
    }

    @Override
    public TRoutineLoadTask createRoutineLoadTask() throws StarRocksException {
        KafkaRoutineLoadJob routineLoadJob = (KafkaRoutineLoadJob) job;

        // init tRoutineLoadTask and create plan fragment
        TRoutineLoadTask tRoutineLoadTask = new TRoutineLoadTask();
        TUniqueId queryId = new TUniqueId(id.getMostSignificantBits(), id.getLeastSignificantBits());
        tRoutineLoadTask.setId(queryId);
        tRoutineLoadTask.setJob_id(routineLoadJob.getId());
        tRoutineLoadTask.setTxn_id(txnId);
        Database database = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(routineLoadJob.getDbId());
        if (database == null) {
            throw new MetaNotFoundException("database " + routineLoadJob.getDbId() + " does not exist");
        }
        tRoutineLoadTask.setDb(database.getFullName());
        Table tbl = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(database.getId(), routineLoadJob.getTableId());
        if (tbl == null) {
            throw new MetaNotFoundException("table " + routineLoadJob.getTableId() + " does not exist");
        }
        tRoutineLoadTask.setTbl(tbl.getName());
        tRoutineLoadTask.setLabel(label);
        tRoutineLoadTask.setAuth_code(routineLoadJob.getAuthCode());
        TKafkaLoadInfo tKafkaLoadInfo = new TKafkaLoadInfo();
        tKafkaLoadInfo.setTopic((routineLoadJob).getTopic());
        tKafkaLoadInfo.setBrokers((routineLoadJob).getBrokerList());
        tKafkaLoadInfo.setPartition_begin_offset(partitionIdToOffset);
        tKafkaLoadInfo.setProperties(routineLoadJob.getConvertedCustomProperties());
        if ((routineLoadJob).getConfluentSchemaRegistryUrl() != null) {
            tKafkaLoadInfo.setConfluent_schema_registry_url((routineLoadJob).getConfluentSchemaRegistryUrl());
        }
        tRoutineLoadTask.setKafka_load_info(tKafkaLoadInfo);
        tRoutineLoadTask.setType(TLoadSourceType.KAFKA);
        tRoutineLoadTask.setParams(plan(routineLoadJob));
        // When the transaction times out, we reduce the consumption time to lower the BE load.
        if (msg != null && msg.contains(DatabaseTransactionMgr.TXN_TIMEOUT_BY_MANAGER)) {
            tRoutineLoadTask.setMax_interval_s(routineLoadJob.getTaskConsumeSecond() / 2);
        } else {
            tRoutineLoadTask.setMax_interval_s(routineLoadJob.getTaskConsumeSecond());
        }
        tRoutineLoadTask.setMax_batch_rows(routineLoadJob.getMaxBatchRows());
        tRoutineLoadTask.setMax_batch_size(Config.max_routine_load_batch_size);
        if (!routineLoadJob.getFormat().isEmpty() && routineLoadJob.getFormat().equalsIgnoreCase("json")) {
            tRoutineLoadTask.setFormat(TFileFormatType.FORMAT_JSON);
        } else if (!routineLoadJob.getFormat().isEmpty() && routineLoadJob.getFormat().equalsIgnoreCase("avro")) {
            tRoutineLoadTask.setFormat(TFileFormatType.FORMAT_AVRO);
        } else {
            tRoutineLoadTask.setFormat(TFileFormatType.FORMAT_CSV_PLAIN);
        }
        if (Math.abs(routineLoadJob.getMaxFilterRatio() - 1) > 0.001) {
            tRoutineLoadTask.setMax_filter_ratio(routineLoadJob.getMaxFilterRatio());
        }

        return tRoutineLoadTask;
    }

    @Override
    protected String getTaskDataSourceProperties() {
        StringBuilder result = new StringBuilder();

        Gson gson = new Gson();
        result.append("Progress:").append(gson.toJson(partitionIdToOffset));
        result.append(",");
        result.append("LatestOffset:").append(gson.toJson(latestPartOffset));
        return result.toString();
    }

    @Override
    String dataSourceType() {
        return "kafka";
    }

    public Map<Integer, Long> getLatestOffset() {
        return latestPartOffset;
    }

    private TExecPlanFragmentParams plan(RoutineLoadJob routineLoadJob) throws StarRocksException {
        TUniqueId loadId = new TUniqueId(id.getMostSignificantBits(), id.getLeastSignificantBits());
        // plan for each task, in case table has change(rollup or schema change)
        TExecPlanFragmentParams tExecPlanFragmentParams = routineLoadJob.plan(loadId, txnId, label);
        if (tExecPlanFragmentParams.query_options.enable_profile) {
            StreamLoadTask streamLoadTask = GlobalStateMgr.getCurrentState().
                    getStreamLoadMgr().getTaskByLabel(label);
            setStreamLoadTask(streamLoadTask);
        }
        return tExecPlanFragmentParams;
    }
}
