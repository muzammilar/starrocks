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

package com.starrocks.analysis;

import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.AnalyzeTestUtil;
import com.starrocks.sql.ast.CancelRefreshMaterializedViewStmt;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CancelRefreshMaterializedViewTest {
    private static ConnectContext connectContext;

    @BeforeAll
    public static void beforeClass() throws Exception {
        AnalyzeTestUtil.init();
        connectContext = AnalyzeTestUtil.getConnectContext();
    }

    @Test
    public void testNormal() throws Exception {
        String refreshMvSql = "cancel refresh materialized view test1.mv1";
        CancelRefreshMaterializedViewStmt cancelRefresh =
                (CancelRefreshMaterializedViewStmt) UtFrameUtils.parseStmtWithNewParser(refreshMvSql, connectContext);
        String dbName = cancelRefresh.getMvName().getDb();
        String mvName = cancelRefresh.getMvName().getTbl();
        Assertions.assertEquals("test1", dbName);
        Assertions.assertEquals("mv1", mvName);
        Assertions.assertFalse(cancelRefresh.isForce());
    }

    @Test
    public void testForceRefreshMaterializedView() throws Exception {
        String refreshMvSql = "cancel refresh materialized view test1.mv1 force";
        CancelRefreshMaterializedViewStmt cancelRefresh =
                (CancelRefreshMaterializedViewStmt) UtFrameUtils.parseStmtWithNewParser(refreshMvSql, connectContext);
        String dbName = cancelRefresh.getMvName().getDb();
        String mvName = cancelRefresh.getMvName().getTbl();
        Assertions.assertEquals("test1", dbName);
        Assertions.assertEquals("mv1", mvName);
        Assertions.assertTrue(cancelRefresh.isForce());
    }
}
