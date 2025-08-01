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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/planner/StreamLoadScanNode.java

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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.NullLiteral;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.CsvFormat;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.load.Load;
import com.starrocks.load.streamload.StreamLoadInfo;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.ComputeNode;
import com.starrocks.thrift.TBrokerRangeDesc;
import com.starrocks.thrift.TBrokerScanRange;
import com.starrocks.thrift.TBrokerScanRangeParams;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TFileFormatType;
import com.starrocks.thrift.TFileScanNode;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPlanNodeType;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocation;
import com.starrocks.thrift.TScanRangeLocations;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.starrocks.catalog.DefaultExpr.isValidDefaultFunction;;

/**
 * used to scan from stream
 */
public class StreamLoadScanNode extends LoadScanNode {
    private static final Logger LOG = LogManager.getLogger(StreamLoadScanNode.class);

    private TUniqueId loadId;
    // TODO(zc): now we use scanRange
    // input parameter
    private Table dstTable;
    private StreamLoadInfo streamLoadInfo;
    private int numInstances;

    private List<TScanRangeLocations> locationsList = Lists.newArrayList();

    // columns in column list is case insensitive
    private Map<String, SlotDescriptor> slotDescByName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
    private Map<String, Expr> exprsByName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);

    // Use vectorized load for improving load performance
    // 1. now for orcfile only
    // 2. remove cast string, and transform data from orig datatype directly
    // 3. use vectorized engine
    private boolean useVectorizedLoad;

    private boolean needAssignBE;

    private boolean enableBatchWrite = false;
    private int batchWriteIntervalMs;
    private ImmutableMap<String, String> batchWriteParameters;
    private Set<Long> batchWriteBackendIds;

    private List<ComputeNode> computeNodes;
    private int nextBe = 0;
    private final Random random = new Random(System.currentTimeMillis());
    private String dbName;
    private String label;
    private long txnId;
    private int curChannelId;

    private static class ParamCreateContext {
        public TBrokerScanRangeParams params;
        public TupleDescriptor tupleDescriptor;
    }

    private ParamCreateContext paramCreateContext;
    private boolean nullExprInAutoIncrement;
    private DescriptorTable descriptorTable;

    // used to construct for streaming loading
    public StreamLoadScanNode(TUniqueId loadId, PlanNodeId id, TupleDescriptor tupleDesc, Table dstTable, StreamLoadInfo streamLoadInfo) {
        super(id, tupleDesc, "StreamLoadScanNode");
        this.loadId = loadId;
        this.dstTable = dstTable;
        this.streamLoadInfo = streamLoadInfo;
        this.useVectorizedLoad = false;
        this.numInstances = 1;
        this.nextBe = 0;
        this.needAssignBE = false;
        this.nullExprInAutoIncrement = true;
    }

    public StreamLoadScanNode(
            TUniqueId loadId, PlanNodeId id, TupleDescriptor tupleDesc, Table dstTable,
            StreamLoadInfo streamLoadInfo, String dbName, String label,
            int numInstances, long txnId, ComputeResource computeResource) {
        super(id, tupleDesc, "StreamLoadScanNode");
        this.loadId = loadId;
        this.dstTable = dstTable;
        this.streamLoadInfo = streamLoadInfo;
        this.useVectorizedLoad = false;
        this.dbName = dbName;
        this.label = label;
        this.numInstances = numInstances;
        this.nextBe = 0;
        this.needAssignBE = false;
        this.txnId = txnId;
        this.curChannelId = 0;
        this.nullExprInAutoIncrement = true;
        this.computeResource = computeResource;
    }

    public void setUseVectorizedLoad(boolean useVectorizedLoad) {
        this.useVectorizedLoad = useVectorizedLoad;
    }

    public void setNeedAssignBE(boolean needAssignBE) {
        this.needAssignBE = needAssignBE;
    }

    public void setBatchWrite(int batchWriteIntervalMs, ImmutableMap<String, String> loadParameters, Set<Long> batchWriteBackendIds) {
        setNeedAssignBE(true);
        this.enableBatchWrite = true;
        this.batchWriteIntervalMs = batchWriteIntervalMs;
        this.batchWriteParameters = loadParameters;
        this.batchWriteBackendIds = new HashSet<>(batchWriteBackendIds);
    }

    public boolean nullExprInAutoIncrement() {
        return nullExprInAutoIncrement;
    }

    public void init(DescriptorTable descriptorTable) throws StarRocksException {
        // can't call super.init(), because after super.init, conjuncts would be null
        if (needAssignBE) {
            assignBackends();
        }

        this.descriptorTable = descriptorTable;
        paramCreateContext = new ParamCreateContext();
        initParams();
    }

    // Called from init, construct source tuple information
    private void initParams() throws StarRocksException {
        TBrokerScanRangeParams params = new TBrokerScanRangeParams();
        paramCreateContext.params = params;

        if (streamLoadInfo.getColumnSeparator() != null) {
            String sep = streamLoadInfo.getColumnSeparator().getColumnSeparator();
            byte[] setBytes = sep.getBytes(StandardCharsets.UTF_8);
            params.setColumn_separator(setBytes[0]);
            if (setBytes.length > CsvFormat.MAX_COLUMN_SEPARATOR_LENGTH) {
                ErrorReport.reportUserException(ErrorCode.ERR_ILLEGAL_BYTES_LENGTH, "column separator", 1,
                        CsvFormat.MAX_COLUMN_SEPARATOR_LENGTH);
            }
            if (setBytes.length > 1) {
                params.setMulti_column_separator(sep);
            }
        } else {
            params.setColumn_separator((byte) '\t');
        }
        if (streamLoadInfo.getRowDelimiter() != null) {
            String sep = streamLoadInfo.getRowDelimiter().getRowDelimiter();
            byte[] sepBytes = sep.getBytes(StandardCharsets.UTF_8);
            params.setRow_delimiter(sepBytes[0]);
            if (sepBytes.length > CsvFormat.MAX_ROW_DELIMITER_LENGTH) {
                ErrorReport.reportUserException(ErrorCode.ERR_ILLEGAL_BYTES_LENGTH, "row delimiter",
                        1, CsvFormat.MAX_ROW_DELIMITER_LENGTH);
            }
            if (sepBytes.length > 1) {
                params.setMulti_row_delimiter(sep);
            }
        } else {
            params.setRow_delimiter((byte) '\n');
        }
        params.setTrim_space(streamLoadInfo.getTrimSpace());
        params.setSkip_header(streamLoadInfo.getSkipHeader());
        params.setEnclose(streamLoadInfo.getEnclose());
        params.setEscape(streamLoadInfo.getEscape());
        params.setStrict_mode(streamLoadInfo.isStrictMode());
        params.setJson_file_size_limit(Config.json_file_size_limit);
        if (streamLoadInfo.getConfluentSchemaRegistryUrl() != null) {
            params.setConfluent_schema_registry_url(streamLoadInfo.getConfluentSchemaRegistryUrl());
        }
        initColumns();
        initWhereExpr(streamLoadInfo.getWhereExpr());
    }

    private void initColumns() throws StarRocksException {
        paramCreateContext.tupleDescriptor = descriptorTable.createTupleDescriptor("StreamLoadScanNode");
        Load.initColumns(dstTable, streamLoadInfo.getColumnExprDescs(), null /* no hadoop function */,
                exprsByName, descriptorTable, paramCreateContext.tupleDescriptor, slotDescByName,
                paramCreateContext.params, true, useVectorizedLoad, Lists.newArrayList(),
                streamLoadInfo.getFormatType() == TFileFormatType.FORMAT_JSON, streamLoadInfo.isPartialUpdate());
    }

    @Override
    public void finalizeStats() throws StarRocksException, StarRocksException {
        finalizeParams();
    }

    private void assignBackends() throws StarRocksException {
        if (enableBatchWrite) {
            computeNodes = Lists.newArrayList();
            for (long backendId : batchWriteBackendIds) {
                // backendId is assigned by CoordinatorBackendAssignerImpl which have considered to use
                // backend or cn for different deployment mode. Here just try to get the node from both
                ComputeNode computeNode = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendOrComputeNode(backendId);
                if (computeNode == null) {
                    throw new StarRocksException(String.format("Can't find batch write backend [%s]", backendId));
                }
                if (!computeNode.isAvailable()) {
                    throw new StarRocksException(String.format("Batch write backend [%s] is not available", backendId));
                }
                computeNodes.add(computeNode);
            }
        } else {
            computeNodes = getAvailableComputeNodes(computeResource);
            Collections.shuffle(computeNodes, random);
        }
        if (computeNodes.isEmpty()) {
            throw new StarRocksException("No available backends: " + computeResource);
        }
    }

    private void finalizeParams() throws StarRocksException {
        boolean negative = streamLoadInfo.getNegative();
        Map<Integer, Integer> destSidToSrcSidWithoutTrans = Maps.newHashMap();
        for (SlotDescriptor dstSlotDesc : desc.getSlots()) {
            if (!dstSlotDesc.isMaterialized()) {
                continue;
            }
            Expr expr = null;
            if (exprsByName != null) {
                expr = exprsByName.get(dstSlotDesc.getColumn().getName());
            }
            if (expr == null) {
                SlotDescriptor srcSlotDesc = slotDescByName.get(dstSlotDesc.getColumn().getName());
                if (srcSlotDesc != null) {
                    destSidToSrcSidWithoutTrans.put(dstSlotDesc.getId().asInt(), srcSlotDesc.getId().asInt());
                    // If dest is allowed null, we set source to nullable
                    if (dstSlotDesc.getColumn().isAllowNull()) {
                        srcSlotDesc.setIsNullable(true);
                    }
                    SlotRef slotRef = new SlotRef(srcSlotDesc);
                    slotRef.setColumnName(dstSlotDesc.getColumn().getName());
                    expr = slotRef;
                } else {
                    Column column = dstSlotDesc.getColumn();
                    Column.DefaultValueType defaultValueType = column.getDefaultValueType();
                    if (defaultValueType == Column.DefaultValueType.CONST) {
                        expr = new StringLiteral(column.calculatedDefaultValue());
                    } else if (defaultValueType == Column.DefaultValueType.VARY) {
                        if (isValidDefaultFunction(column.getDefaultExpr().getExpr())) {
                            expr = column.getDefaultExpr().obtainExpr();
                        } else {
                            throw new StarRocksException("Column(" + column + ") has unsupported default value:"
                                    + column.getDefaultExpr().getExpr());
                        }
                    } else if (defaultValueType == Column.DefaultValueType.NULL) {
                        if (column.isAllowNull() || column.isAutoIncrement()) {
                            expr = NullLiteral.create(column.getType());
                            if (column.isAutoIncrement()) {
                                nullExprInAutoIncrement = false;
                            }
                        } else {
                            throw new AnalysisException("column has no source field, column=" + column.getName());
                        }
                    }
                }
            }

            // check hll_hash
            if (dstSlotDesc.getType().getPrimitiveType() == PrimitiveType.HLL) {
                if (!(expr instanceof FunctionCallExpr)) {
                    throw new AnalysisException("HLL column must use " + FunctionSet.HLL_HASH + " function, like "
                            + dstSlotDesc.getColumn().getName() + "=" + FunctionSet.HLL_HASH + "(xxx)");
                }
                FunctionCallExpr fn = (FunctionCallExpr) expr;
                if (!fn.getFnName().getFunction().equalsIgnoreCase(FunctionSet.HLL_HASH)
                        && !fn.getFnName().getFunction().equalsIgnoreCase("hll_empty")) {
                    throw new AnalysisException("HLL column must use " + FunctionSet.HLL_HASH + " function, like "
                            + dstSlotDesc.getColumn().getName() + "=" + FunctionSet.HLL_HASH
                            + "(xxx) or " + dstSlotDesc.getColumn().getName() + "=hll_empty()");
                }
                expr.setType(Type.HLL);
            }

            checkBitmapCompatibility(dstSlotDesc, expr);

            if (negative && dstSlotDesc.getColumn().getAggregationType() == AggregateType.SUM) {
                expr = new ArithmeticExpr(ArithmeticExpr.Operator.MULTIPLY, expr, new IntLiteral(-1));
                expr = Expr.analyzeAndCastFold(expr);
            }
            expr = castToSlot(dstSlotDesc, expr);

            paramCreateContext.params.putToExpr_of_dest_slot(dstSlotDesc.getId().asInt(), expr.treeToThrift());
        }
        paramCreateContext.params.setDest_sid_to_src_sid_without_trans(destSidToSrcSidWithoutTrans);
        paramCreateContext.params.setSrc_tuple_id(paramCreateContext.tupleDescriptor.getId().asInt());
        paramCreateContext.params.setDest_tuple_id(desc.getId().asInt());
        if (needAssignBE) {
            paramCreateContext.params.setTxn_id(txnId);
            paramCreateContext.params.setDb_name(dbName);
            paramCreateContext.params.setTable_name(dstTable.getName());
            paramCreateContext.params.setLabel(label);
        }

        paramCreateContext.tupleDescriptor.computeMemLayout();

        createScanRange();
    }

    private void createScanRange() throws StarRocksException {
        for (int i = 0; i < this.numInstances; i++) {
            TBrokerScanRange brokerScanRange = new TBrokerScanRange();
            brokerScanRange.setParams(paramCreateContext.params);

            TBrokerRangeDesc rangeDesc = new TBrokerRangeDesc();
            rangeDesc.setFile_type(streamLoadInfo.getFileType());
            rangeDesc.setFormat_type(streamLoadInfo.getFormatType());
            if (rangeDesc.format_type == TFileFormatType.FORMAT_JSON) {
                if (!streamLoadInfo.getJsonPaths().isEmpty()) {
                    rangeDesc.setJsonpaths(streamLoadInfo.getJsonPaths());
                }
                if (!streamLoadInfo.getJsonRoot().isEmpty()) {
                    rangeDesc.setJson_root(streamLoadInfo.getJsonRoot());
                }
                rangeDesc.setStrip_outer_array(streamLoadInfo.isStripOuterArray());
            }
            if (rangeDesc.format_type == TFileFormatType.FORMAT_AVRO) {
                if (!streamLoadInfo.getJsonPaths().isEmpty()) {
                    rangeDesc.setJsonpaths(streamLoadInfo.getJsonPaths());
                }
            }
            rangeDesc.setSplittable(false);
            switch (streamLoadInfo.getFileType()) {
                case FILE_LOCAL:
                    rangeDesc.setPath(streamLoadInfo.getPath());
                    break;
                case FILE_STREAM:
                    rangeDesc.setPath("Invalid Path");
                    if (needAssignBE) {
                        rangeDesc.setLoad_id(UUIDUtil.genTUniqueId());
                    } else {
                        rangeDesc.setLoad_id(loadId);
                    }
                    break;
                default:
                    throw new StarRocksException("unsupported file type, type=" + streamLoadInfo.getFileType());
            }
            rangeDesc.setStart_offset(0);
            rangeDesc.setSize(-1);
            rangeDesc.setNum_of_columns_from_file(paramCreateContext.params.getSrc_slot_idsSize());
            rangeDesc.setCompression_type(streamLoadInfo.getPayloadCompressionType());
            brokerScanRange.addToRanges(rangeDesc);
            brokerScanRange.setBroker_addresses(Lists.newArrayList());
            if (needAssignBE) {
                brokerScanRange.setChannel_id(curChannelId++);
                brokerScanRange.setEnable_batch_write(enableBatchWrite);
                brokerScanRange.setBatch_write_interval_ms(batchWriteIntervalMs);
                brokerScanRange.setBatch_write_parameters(batchWriteParameters);
            }
            TScanRangeLocations locations = new TScanRangeLocations();
            TScanRange scanRange = new TScanRange();
            scanRange.setBroker_scan_range(brokerScanRange);
            locations.setScan_range(scanRange);

            if (needAssignBE) {
                ComputeNode selectedBackend = computeNodes.get(nextBe++);
                nextBe = nextBe % computeNodes.size();
                TScanRangeLocation location = new TScanRangeLocation();
                location.setBackend_id(selectedBackend.getId());
                location.setServer(new TNetworkAddress(selectedBackend.getHost(), selectedBackend.getBePort()));
                locations.addToLocations(location);
            } else {
                locations.setLocations(Lists.newArrayList());
            }
            locationsList.add(locations);
        }
    }

    @Override
    protected void toThrift(TPlanNode planNode) {
        planNode.setNode_type(TPlanNodeType.FILE_SCAN_NODE);
        TFileScanNode fileScanNode = new TFileScanNode(desc.getId().asInt());
        fileScanNode.setEnable_pipeline_load(true);
        planNode.setFile_scan_node(fileScanNode);
    }

    @Override
    public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
        return locationsList;
    }

    @Override
    protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
        return "StreamLoadScanNode";
    }

    @Override
    public boolean canUsePipeLine() {
        return true;
    }
}
