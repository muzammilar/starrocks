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
import com.starrocks.qe.ShowExecutor;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.sql.analyzer.AstToStringBuilder;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.ShowTableStatusStmt;
import com.starrocks.sql.ast.ShowTableStmt;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.Preconditions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ShowTableStmtTest {

    private ConnectContext ctx;
    private static StarRocksAssert starRocksAssert;

    @BeforeEach
    public void setUp() throws Exception {
        ctx = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(ctx);
        UtFrameUtils.createMinStarRocksCluster();
    }

    @Test
    public void testNormal() throws Exception {
        ctx.setDatabase("testDb");

        ShowTableStmt stmt = new ShowTableStmt("", false, null);

        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals("testDb", stmt.getDb());
        Assertions.assertFalse(stmt.isVerbose());
        Assertions.assertEquals(1, stmt.getMetaData().getColumnCount());
        Assertions.assertEquals("Tables_in_testDb", stmt.getMetaData().getColumn(0).getName());

        stmt = new ShowTableStmt("abc", true, null);
        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals(2, stmt.getMetaData().getColumnCount());
        Assertions.assertEquals("Tables_in_abc", stmt.getMetaData().getColumn(0).getName());
        Assertions.assertEquals("Table_type", stmt.getMetaData().getColumn(1).getName());

        stmt = new ShowTableStmt("abc", true, "bcd");
        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals("bcd", stmt.getPattern());
        Assertions.assertEquals(2, stmt.getMetaData().getColumnCount());
        Assertions.assertEquals("Tables_in_abc", stmt.getMetaData().getColumn(0).getName());
        Assertions.assertEquals("Table_type", stmt.getMetaData().getColumn(1).getName());
        Assertions.assertEquals("bcd", stmt.getPattern());

        String sql = "show full tables where table_type !='VIEW'";
        stmt = (ShowTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, ctx);
        Preconditions.notNull(stmt.toSelectStmt().getOrigStmt(), "stmt's original stmt should not be null");

        QueryStatement queryStatement = stmt.toSelectStmt();
        String expect = "SELECT information_schema.tables.TABLE_NAME AS Tables_in_testDb, " +
                "information_schema.tables.TABLE_TYPE AS Table_type FROM " +
                "information_schema.tables WHERE (information_schema.tables.TABLE_SCHEMA = 'testDb') AND (information_schema.tables.TABLE_TYPE != 'VIEW')";
        Assertions.assertEquals(expect, AstToStringBuilder.toString(queryStatement));
    }

    @Test
    public void testShowTableStatus() throws Exception {
        starRocksAssert.withDatabase("test").useDatabase("test")
                .withTable("CREATE TABLE test.tbl1\n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int sum\n" +
                        ")\n" +
                        "PARTITION BY RANGE(k1)\n" +
                        "(\n" +
                        "    PARTITION p1 values less than('2020-02-01'),\n" +
                        "    PARTITION p2 values less than('2020-03-01')\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");

        String sql = "show table status from test";
        ShowTableStatusStmt stmt = (ShowTableStatusStmt) UtFrameUtils.parseStmtWithNewParser(sql, ctx);
        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        ShowResultSet showResultSet = ShowExecutor.execute(stmt, ctx);
        List<List<String>> resultRows = showResultSet.getResultRows();
        Assertions.assertEquals(1, resultRows.size());
        Assertions.assertEquals("tbl1", resultRows.get(0).get(0));
        Assertions.assertEquals("StarRocks", resultRows.get(0).get(1));
        Assertions.assertEquals("0", resultRows.get(0).get(4));
        Assertions.assertEquals("0", resultRows.get(0).get(5));
        Assertions.assertEquals("0", resultRows.get(0).get(6));
        Assertions.assertEquals("utf8_general_ci", resultRows.get(0).get(14));
    }

    @Test
    public void testNoDb() {
        assertThrows(SemanticException.class, () -> {
            ctx = UtFrameUtils.createDefaultCtx();
            ShowTableStmt stmt = new ShowTableStmt("", false, null);
            com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
            Assertions.fail("No exception throws");
        });
    }
}