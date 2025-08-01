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
package com.starrocks.sql.plan;

import com.starrocks.catalog.OlapTable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.optimizer.statistics.ColumnStatistic;
import com.starrocks.sql.optimizer.statistics.StatisticStorage;
import com.starrocks.utframe.StarRocksAssert;
import mockit.Expectations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class InnerToSemiTest extends PlanWithCostTestBase {
    @BeforeEach
    public void before() throws Exception {
        connectContext.getSessionVariable().setEnableJoinReorderBeforeDeduplicate(true);
        long t0Rows = 1000_000_000L;
        long t1Rows = 1000L;
        long t2Rows = 10_000_000L;

        GlobalStateMgr globalStateMgr = connectContext.getGlobalStateMgr();
        OlapTable t0 = (OlapTable) globalStateMgr.getLocalMetastore().getDb("test").getTable("t0");
        OlapTable t1 = (OlapTable) globalStateMgr.getLocalMetastore().getDb("test").getTable("t1");
        OlapTable t2 = (OlapTable) globalStateMgr.getLocalMetastore().getDb("test").getTable("t2");
        StatisticStorage ss = GlobalStateMgr.getCurrentState().getStatisticStorage();
        new Expectations(ss) {
            {
                ss.getColumnStatistic(t0, "v1");
                result = new ColumnStatistic(1, 2, 0, 4, t0Rows / 300.0);
                minTimes = 0;
                ss.getColumnStatistic(t0, "v2");
                result = new ColumnStatistic(1, 4000000, 0, 4, t0Rows / 2000.0);
                minTimes = 0;
                ss.getColumnStatistic(t0, "v3");
                result = new ColumnStatistic(1, 2000000, 0, 4, t0Rows / 300.0);
                minTimes = 0;

                ss.getColumnStatistic(t1, "v4");
                result = new ColumnStatistic(1, 2, 0, 4, t1Rows);
                minTimes = 0;
                ss.getColumnStatistic(t1, "v5");
                result = new ColumnStatistic(1, 100000, 0, 4, t1Rows / 100.0);
                minTimes = 0;
                ss.getColumnStatistic(t1, "v6");
                result = new ColumnStatistic(1, 200000, 0, 4, t1Rows / 1000.0);
                minTimes = 0;

                ss.getColumnStatistic(t2, "v7");
                result = new ColumnStatistic(1, 2, 0, 4, t2Rows / 200.0);
                minTimes = 0;
                ss.getColumnStatistic(t2, "v8");
                result = new ColumnStatistic(1, 100000, 0, 4, t2Rows / 2000.0);
                minTimes = 0;
                ss.getColumnStatistic(t2, "v9");
                result = new ColumnStatistic(1, 200000, 0, 4, t2Rows / 20000.0);
                minTimes = 0;
            }
        };
        connectContext.getSessionVariable().setCboPushDownAggregateMode(-1);
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        String createTbl0StmtStr = "" +
                "create table table_struct_smallint (\n" +
                "    col_int int,\n" +
                "    col_struct struct < c_smallint smallint >,\n" +
                "    col_struct2 struct < c_double double >,\n" +
                "    col_struct3 struct < c_int int >\n" +
                ")\n" +
                "duplicate key(col_int)\n" +
                "properties (\"replication_num\" = \"1\")";

        String createTbl1StmtStr = "" +
                "create table duplicate_table_struct (\n" +
                "    col_int int,\n" +
                "    col_string string,\n" +
                "    col_struct struct < c_int int,\n" +
                "    c_float float,\n" +
                "    c_double double,\n" +
                "    c_char char(30),\n" +
                "    c_varchar varchar(200),\n" +
                "    c_date date,\n" +
                "    c_timestamp datetime,\n" +
                "    c_boolean boolean >\n" +
                ")\n" +
                "duplicate key(col_int)\n" +
                "properties (\"replication_num\" = \"1\")";
        starRocksAssert.withTable(createTbl0StmtStr);
        starRocksAssert.withTable(createTbl1StmtStr);
    }

    @AfterEach
    public void after() {
        connectContext.getSessionVariable().setEnableJoinReorderBeforeDeduplicate(false);
    }

    @Test
    public void testInnerToSemi() throws Exception {
        String sql = "select distinct(t2.v9) from t0 join t1 on t0.v1=t1.v4 join t2 on t0.v2 = t2.v8;";
        String plan = getLogicalFragmentPlan(sql);
        assertContains(plan, "LEFT SEMI JOIN (join-predicate [8: v8 = 2: v2] post-join-predicate [null])\n" +
                "                SCAN (columns[8: v8, 9: v9] predicate[8: v8 IS NOT NULL])\n" +
                "                EXCHANGE BROADCAST\n" +
                "                    LEFT SEMI JOIN (join-predicate [1: v1 = 4: v4] post-join-predicate [null])");

        sql = "select distinct(t0.v1) from t0 join t1 on t0.v1 = t1.v4 join t2 on t1.v4 = t2.v7;";
        plan = getLogicalFragmentPlan(sql);
        assertContains(plan, " LEFT SEMI JOIN (join-predicate [1: v1 = 7: v7] post-join-predicate [null])\n" +
                "        LEFT SEMI JOIN (join-predicate [1: v1 = 4: v4] post-join-predicate [null])");

        sql = "select distinct t1.col_struct.c_char from duplicate_table_struct t1 " +
                "where col_struct.c_double = (select max(t2.col_struct2.c_double) from table_struct_smallint t2 " +
                "where t1.col_struct.c_int = t2.col_struct3.c_int ) order by 1;";
        plan = getLogicalFragmentPlan(sql);
        assertContains(plan, "LEFT SEMI JOIN (join-predicate [13: expr = 12: expr AND 14: expr = 9: max]");
    }
}
