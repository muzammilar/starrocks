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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/catalog/MaterializedIndexTest.java

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

package com.starrocks.catalog;

import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.common.FeConstants;
import com.starrocks.server.GlobalStateMgr;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

public class MaterializedIndexTest {

    private MaterializedIndex index;
    private long indexId;

    private List<Column> columns;
    @Mocked
    private GlobalStateMgr globalStateMgr;

    private FakeGlobalStateMgr fakeGlobalStateMgr;

    @BeforeEach
    public void setUp() {
        indexId = 10000;

        columns = new LinkedList<Column>();
        columns.add(new Column("k1", ScalarType.createType(PrimitiveType.TINYINT), true, null, "", ""));
        columns.add(new Column("k2", ScalarType.createType(PrimitiveType.SMALLINT), true, null, "", ""));
        columns.add(new Column("v1", ScalarType.createType(PrimitiveType.INT), false, AggregateType.REPLACE, "", ""));
        index = new MaterializedIndex(indexId, IndexState.NORMAL);

        fakeGlobalStateMgr = new FakeGlobalStateMgr();
        FakeGlobalStateMgr.setGlobalStateMgr(globalStateMgr);
        FakeGlobalStateMgr.setMetaVersion(FeConstants.META_VERSION);
    }

    @Test
    public void getMethodTest() {
        Assertions.assertEquals(indexId, index.getId());
    }

    @Test
    public void testVisibleForTransaction() throws Exception {
        index = new MaterializedIndex(10);
        Assertions.assertEquals(IndexState.NORMAL, index.getState());
        Assertions.assertTrue(index.visibleForTransaction(0));
        Assertions.assertTrue(index.visibleForTransaction(10));

        index = new MaterializedIndex(10, IndexState.NORMAL, 10,
                PhysicalPartition.INVALID_SHARD_GROUP_ID);
        Assertions.assertTrue(index.visibleForTransaction(0));
        Assertions.assertTrue(index.visibleForTransaction(9));
        Assertions.assertTrue(index.visibleForTransaction(10));
        Assertions.assertTrue(index.visibleForTransaction(11));

        index = new MaterializedIndex(10, IndexState.SHADOW, 10,
                PhysicalPartition.INVALID_SHARD_GROUP_ID);
        Assertions.assertFalse(index.visibleForTransaction(0));
        Assertions.assertFalse(index.visibleForTransaction(9));
        Assertions.assertTrue(index.visibleForTransaction(10));
        Assertions.assertTrue(index.visibleForTransaction(11));
    }
}
