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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/DropMaterializedViewStmtTest.java

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

import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.Type;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TStorageType;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.BeforeEach;

import java.util.LinkedList;
import java.util.List;

public class DropMaterializedViewStmtTest {

    private GlobalStateMgr globalStateMgr;
    @Mocked
    private ConnectContext connectContext;

    @BeforeEach
    public void setUp() {
        globalStateMgr = Deencapsulation.newInstance(GlobalStateMgr.class);
        Database db = new Database(50000L, "test");

        Column column1 = new Column("col1", Type.BIGINT);
        Column column2 = new Column("col2", Type.DOUBLE);

        List<Column> baseSchema = new LinkedList<>();
        baseSchema.add(column1);
        baseSchema.add(column2);

        SinglePartitionInfo singlePartitionInfo = new SinglePartitionInfo();
        OlapTable table = new OlapTable(30000, "table",
                baseSchema, KeysType.AGG_KEYS, singlePartitionInfo, null);
        table.setBaseIndexId(100);
        db.registerTableUnlocked(table);
        table.addPartition(new Partition(100, 101, "p",
                new MaterializedIndex(200, MaterializedIndex.IndexState.NORMAL), null));
        table.setIndexMeta(200, "mvname", baseSchema, 0, 0, (short) 0,
                TStorageType.COLUMN, KeysType.AGG_KEYS);

        new MockUp<GlobalStateMgr>() {
            @Mock
            GlobalStateMgr getCurrentState() {
                return globalStateMgr;
            }

            @Mock
            Database getDb(long dbId) {
                return db;
            }

            @Mock
            Database getDb(String dbName) {
                return db;
            }
        };
    }
}
