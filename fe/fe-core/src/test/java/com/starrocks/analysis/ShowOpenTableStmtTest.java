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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/ShowTableStmtTest.java

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

import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ShowResultSetMetaData;
import com.starrocks.sql.ast.ShowOpenTableStmt;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ShowOpenTableStmtTest {

    private static ConnectContext ctx;

    @BeforeAll
    public static void beforeClass() throws Exception {
        ctx = UtFrameUtils.createDefaultCtx();
    }

    @BeforeEach
    public void setUp() throws Exception {
    }

    @Test
    public void testNormal() throws Exception {
        ShowOpenTableStmt stmt = (ShowOpenTableStmt) UtFrameUtils.parseStmtWithNewParser("SHOW OPEN TABLES", ctx);
        ShowResultSetMetaData metaData = stmt.getMetaData();
        Assertions.assertNotNull(metaData);
        Assertions.assertEquals(4, metaData.getColumnCount());
        Assertions.assertEquals("Database", metaData.getColumn(0).getName());
        Assertions.assertEquals("Table", metaData.getColumn(1).getName());
        Assertions.assertEquals("In_use", metaData.getColumn(2).getName());
        Assertions.assertEquals("Name_locked", metaData.getColumn(3).getName());
    }
}