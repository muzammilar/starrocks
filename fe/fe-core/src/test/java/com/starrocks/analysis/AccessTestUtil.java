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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/AccessTestUtil.java

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

package com.starrocks.analysis;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.FakeEditLog;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.RandomDistributionInfo;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.Type;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.journal.JournalTask;
import com.starrocks.persist.EditLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TStorageType;
import mockit.Expectations;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class AccessTestUtil {

    public static SystemInfoService fetchSystemInfoService() {
        return new SystemInfoService();
    }

    public static GlobalStateMgr fetchAdminCatalog() {
        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        BlockingQueue<JournalTask> journalQueue = new ArrayBlockingQueue<JournalTask>(100);
        EditLog editLog = new EditLog(journalQueue);
        globalStateMgr.setEditLog(editLog);

        FakeEditLog fakeEditLog = new FakeEditLog();

        Database db = new Database(50000L, "testCluster:testDb");
        MaterializedIndex baseIndex = new MaterializedIndex(30001, IndexState.NORMAL);
        RandomDistributionInfo distributionInfo = new RandomDistributionInfo(10);
        Partition partition = new Partition(20000L, 20001L,"testTbl", baseIndex, distributionInfo);
        List<Column> baseSchema = new LinkedList<Column>();
        Column column = new Column("k1", Type.INT);
        baseSchema.add(column);
        OlapTable table = new OlapTable(30000, "testTbl", baseSchema,
                KeysType.AGG_KEYS, new SinglePartitionInfo(), distributionInfo, null);
        table.setIndexMeta(baseIndex.getId(), "testTbl", baseSchema, 0, 1, (short) 1,
                TStorageType.COLUMN, KeysType.AGG_KEYS);
        table.addPartition(partition);
        table.setBaseIndexId(baseIndex.getId());
        db.registerTableUnlocked(table);
        return globalStateMgr;
    }

    public static OlapTable mockTable(String name) {
        Column column1 = new Column("col1", Type.BIGINT);
        Column column2 = new Column("col2", Type.DOUBLE);

        MaterializedIndex index = new MaterializedIndex();
        new Expectations(index) {
            {
                index.getId();
                minTimes = 0;
                result = 30000L;
            }
        };

        PhysicalPartition physicalPartition = Deencapsulation.newInstance(PhysicalPartition.class);
        new Expectations(physicalPartition) {
            {
                physicalPartition.getBaseIndex();
                minTimes = 0;
                result = index;

                physicalPartition.getIndex(30000L);
                minTimes = 0;
                result = index;
            }
        };

        Partition partition = Deencapsulation.newInstance(Partition.class);
        new Expectations(partition) {
            {
                partition.getDefaultPhysicalPartition();
                minTimes = 0;
                result = physicalPartition;
            }
        };

        OlapTable table = new OlapTable();
        new Expectations(table) {
            {
                table.getBaseSchema();
                minTimes = 0;
                result = Lists.newArrayList(column1, column2);

                table.getPartition(40000L);
                minTimes = 0;
                result = partition;
            }
        };
        return table;
    }

    public static Database mockDb(String name) {
        Database db = new Database();
        OlapTable olapTable = mockTable("testTable");

        new Expectations(db) {
            {
                db.getTable("testTable");
                minTimes = 0;
                result = olapTable;

                db.getTable("emptyTable");
                minTimes = 0;
                result = null;

                db.getTableNamesViewWithLock();
                minTimes = 0;
                result = Sets.newHashSet("testTable");

                db.getTables();
                minTimes = 0;
                result = Lists.newArrayList(olapTable);

                db.getFullName();
                minTimes = 0;
                result = name;
            }
        };
        return db;
    }

    public static GlobalStateMgr fetchBlockCatalog() {
        GlobalStateMgr globalStateMgr = Deencapsulation.newInstance(GlobalStateMgr.class);

        Database db = mockDb("testDb");

        /*
        new Expectations(globalStateMgr) {
            {
                globalStateMgr.getLocalMetastore().getDb("testDb");
                minTimes = 0;
                result = db;

                globalStateMgr.getLocalMetastore().getDb("emptyDb");
                minTimes = 0;
                result = null;

                globalStateMgr.getLocalMetastore().getDb(anyString);
                minTimes = 0;
                result = new Database();

                globalStateMgr.getLocalMetastore().getDb("emptyCluster");
                minTimes = 0;
                result = null;
            }
        };

         */
        return globalStateMgr;
    }
}

