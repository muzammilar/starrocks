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


package com.starrocks.sql.analyzer;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.Pair;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.AddPartitionClause;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.ListPartitionDesc;
import com.starrocks.sql.ast.PartitionDesc;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.UtFrameUtils;
import org.apache.hadoop.util.Sets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.starrocks.sql.analyzer.AnalyzeTestUtil.DB_NAME;
import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeSuccess;
import static com.starrocks.sql.analyzer.AnalyzeTestUtil.starRocksAssert;

public class AnalyzeUtilTest {
    @BeforeAll
    public static void beforeClass() throws Exception {
        AnalyzeTestUtil.init();
    }

    @Test
    public void testSubQuery() throws Exception {
        String sql;
        sql = "select count(*) from (select v1 from t0 group by v1) tx";
        List<StatementBase> statementBase =
                SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Map<String, Database> stringDatabaseMap =
                AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(1, stringDatabaseMap.size());
        sql = "select count(*) from (select * from tarray, unnest(v3))";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(1, stringDatabaseMap.size());
        sql = "with mview as (select count(*) from t0) select * from mview";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(1, stringDatabaseMap.size());
        // test view
        String viewTestDB = "view_test";
        AnalyzeTestUtil.getStarRocksAssert().withDatabase(viewTestDB).useDatabase(viewTestDB);
        sql = "create view basic as select v1 from test.t0;";
        CreateViewStmt createTableStmt =
                (CreateViewStmt) UtFrameUtils.parseStmtWithNewParser(sql, AnalyzeTestUtil.getConnectContext());
        GlobalStateMgr.getCurrentState().getLocalMetastore().createView(createTableStmt);
        sql = "select v1 from basic";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        final ConnectContext session = AnalyzeTestUtil.getConnectContext();
        com.starrocks.sql.analyzer.Analyzer.analyze(statementBase.get(0), session);
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(stringDatabaseMap.size(), 2);

        sql = "insert into test.t0 select * from db1.t0,db2.t1";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(stringDatabaseMap.size(), 3);
        Assertions.assertEquals("[db1, test, db2]", stringDatabaseMap.keySet().toString());

        sql = "update test.t0 set v1 = 1";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(stringDatabaseMap.size(), 1);
        Assertions.assertEquals("[test]", stringDatabaseMap.keySet().toString());

        sql = "delete from test.t0 where v1 = 1";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assertions.assertEquals(stringDatabaseMap.size(), 1);
        Assertions.assertEquals("[test]", stringDatabaseMap.keySet().toString());
    }

    @Test
    public void testCollectTable() {
        String sql = "select * from db1.t0, db2.t0";
        StatementBase statementBase = analyzeSuccess(sql);
        Map<TableName, Table> m = AnalyzerUtils.collectAllTableAndView(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("select * from db1.t0 where t0.v1 = (select v1 from db2.t0)"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess(
                "select * from db1.t0 where t0.v1 = (select db2.t0.v1 from db2.t0 where db2.t0.v2 = db1.t0.v1)"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("select * from db1.t0 where t0.v1 in (select v1 from db2.t0)"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("select * from db1.t0 where exists (select v1 from db2.t0)"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("select db1.t0.v1 from db1.t0 group by db1.t0.v1 having db1.t0.v1 = (select v1 from db2.t0)"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("select (select v1 from db2.t0), db1.t0.v1 from db1.t0"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("with cte as (select v1 from db2.t0) select db1.t0.v1 from db1.t0, cte"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("with cte as (select v1 from db2.t0) select db1.t0.v1 from db1.t0 union select * from cte"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess("insert into db1.t0 select * from db2.t0"));
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess(
                "update tprimary set v2 = tp2.v2 from tprimary2 tp2 where tprimary.pk = tp2.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess(
                "update tprimary set v2 = tp2.v2 from tprimary2 tp2 join t0 where tprimary.pk = tp2.pk " +
                        "and tp2.pk = t0.v1 and t0.v2 > 0"));
        Assertions.assertEquals("[test.tprimary2, test.t0, test.tprimary]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess(
                "with tp2cte as (select * from tprimary2 where v2 < 10) update tprimary set v2 = tp2cte.v2 " +
                        "from tp2cte where tprimary.pk = tp2cte.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(
                analyzeSuccess("delete from tprimary using tprimary2 tp2 where tprimary.pk = tp2.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess(
                "delete from tprimary using tprimary2 tp2 join t0 where tprimary.pk = tp2.pk " +
                        "and tp2.pk = t0.v1 and t0.v2 > 0"));
        Assertions.assertEquals("[test.tprimary2, test.t0, test.tprimary]", m.keySet().toString());

        m = AnalyzerUtils.collectAllTableAndView(analyzeSuccess(
                "with tp2cte as (select * from tprimary2 where v2 < 10) delete from tprimary using " +
                        "tp2cte where tprimary.pk = tp2cte.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());
    }

    @Test
    public void testQueryStatementCollectColumns() {
        // base
        String sql = "select * from db1.t0";
        StatementBase statementBase = analyzeSuccess(sql);
        Map<TableName, Set<String>> m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[*]]", m.values().toString());

        // select count(*), count(*) FunctionCallExpr children are null, handle this problem in
        // com.starrocks.sql.analyzer.AuthorizerStmtVisitor.checkCanSelectFromColumns
        sql = "select count(*) from db1.t0";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[]", m.keySet().toString());
        Assertions.assertEquals("[]", m.values().toString());

        // multi table select *
        sql = "select * from db1.t0,db2.t0,test.t0";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.t0, db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[*], [*], [*]]", m.values().toString());

        // SubqueryRelation
        sql = "select v11 from (select v1 as v11 from db2.t0) t1";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1]]", m.values().toString());

        // view
        sql = "select k1 from test.view_to_drop";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.view_to_drop]", m.keySet().toString());
        Assertions.assertEquals("[[k1]]", m.values().toString());

        // subquery
        sql = "select (select v1 as v11 from db2.t0), v1 as v12,v2 from db1.t0";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1], [v1, v2]]", m.values().toString());

        // CTE
        sql = "with cte as (select * from db2.t0) select db1.t0.v1,v2 from db1.t0 union select v1,v2 from cte";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[*], [v1, v2]]", m.values().toString());

        // Predicate
        sql = "with cte as (select v1,v2 from db2.t0 where v3=1) select db1.t0.v1,v2 from db1.t0 union select v1,v2 from cte";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2, v3], [v1, v2]]", m.values().toString());

        // Predicate subquery
        sql = "select v2 from db1.t0 where t0.v1 in (select v1 from db2.t0)";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1], [v1, v2]]", m.values().toString());

        // OrderBy
        sql = "with cte as (select v1 from db2.t0 where v3=1 order by lower(v2))" +
                " select db1.t0.v1 from db1.t0 union select v1 from cte";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2, v3], [v1]]", m.values().toString());

        // GroupBy
        sql = "with cte as (select count(distinct v1) v11 from db2.t0 group by v2) " +
                "select db1.t0.v1 from db1.t0 union select v11 from cte";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2], [v1]]", m.values().toString());

        // Having
        sql = "with cte as (select v2 from db2.t0 group by v2 having count(v1) > 100) " +
                "select db1.t0.v1 from db1.t0 union select v2 from cte";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2], [v1]]", m.values().toString());

        // Join
        sql = "select v3, v4 from (select a.v2 v3,b.v2 v4 from db1.t0 a join db2.t0 b on a.v1=b.v1) c";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db2.t0, db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2], [v1, v2]]", m.values().toString());

        // alias
        sql = "select a.v2,a.v1 from db1.t0 a";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2]]", m.values().toString());

        // view alias
        sql = "select a.k1 from test.view_to_drop a";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.view_to_drop]", m.keySet().toString());
        Assertions.assertEquals("[[k1]]", m.values().toString());

    }

    @Test
    public void testDeleteStatementCollectColumns() {
        // PK table delete with subquery
        String sql = "delete from test.tprimary where v1 in (select v1 from db2.t0 where v2=3)";
        StatementBase statementBase = analyzeSuccess(sql);
        Map<TableName, Set<String>> m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.tprimary, db2.t0]", m.keySet().toString());
        Assertions.assertEquals("[[pk, v1], [v1, v2]]", m.values().toString());

        // PK table delete with subquery 2
        sql = "delete from test.tprimary where exists (select v1 from db2.t0 where test.tprimary.v1=db2.t0.v1 and v2=3)";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.tprimary, db2.t0]", m.keySet().toString());
        Assertions.assertEquals("[[pk, v1], [v1, v2]]", m.values().toString());

        // PK table delete with CTE
        sql = "with cte as (select v1 from db2.t0 where v2=3) " +
                "delete from test.tprimary using cte where test.tprimary.v1=cte.v1";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        // SelectRelation getRelation是joinRelation
        Assertions.assertEquals("[test.tprimary, db2.t0]", m.keySet().toString());
        Assertions.assertEquals("[[pk, v1], [v1, v2]]", m.values().toString());

        m = AnalyzerUtils.collectAllSelectTableColumns(
                analyzeSuccess("delete from test.tprimary using test.tprimary2 tp2 where test.tprimary.pk = tp2.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[pk], [pk]]", m.values().toString());

        m = AnalyzerUtils.collectAllSelectTableColumns(analyzeSuccess(
                "delete from test.tprimary using test.tprimary2 tp2 join test.t0 where test.tprimary.pk = tp2.pk " +
                        "and tp2.pk = t0.v1 and t0.v2 > 0"));
        Assertions.assertEquals("[test.t0, test.tprimary2, test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2], [pk], [pk]]", m.values().toString());

        m = AnalyzerUtils.collectAllSelectTableColumns(analyzeSuccess(
                "with tp2cte as (select * from test.tprimary2 where v2 < 10) delete from test.tprimary using " +
                        "tp2cte where test.tprimary.pk = tp2cte.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[*, v2], [pk]]", m.values().toString());

    }

    @Test
    public void testInsertStatementCollectColumns() {
        // insert into all
        String sql = "insert into db2.t0 values (1, 1, 1)";
        StatementBase statementBase = analyzeSuccess(sql);
        Map<TableName, Set<String>> m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[]", m.keySet().toString());

        // insert into with query
        sql = "insert into db2.t0 (v1, v2) select v1,v2 from db1.t0";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[db1.t0]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2]]", m.values().toString());

        // insert overwrite partition
        sql = "insert overwrite test.table_to_drop PARTITION(p1) select * from test.table_to_drop";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.table_to_drop]", m.keySet().toString());
        Assertions.assertEquals("[[*]]", m.values().toString());

    }

    @Test
    public void testUpdateStatementCollectColumns() {
        // Primary Key
        // multi table update
        String sql = "update test.tprimary set v2 = tp2.v2 from test.tprimary2 tp2 where test.tprimary.pk = tp2.pk";
        StatementBase statementBase = analyzeSuccess(sql);
        Map<TableName, Set<String>> m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[pk, v2], [pk]]", m.values().toString());

        // single table update without condition
        sql = "update test.tprimary set v2 = 1";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[]", m.keySet().toString());
        Assertions.assertEquals("[]", m.values().toString());

        // single table update
        sql = "update test.tprimary set v2 = 1 where v1 = 2";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[v1]]", m.values().toString());

        // single table update subquery
        sql = "update test.tprimary set v2 = v4+1 where v1 < (select avg(v1) from test.tprimary)";
        statementBase = analyzeSuccess(sql);
        m = AnalyzerUtils.collectAllSelectTableColumns(statementBase);
        Assertions.assertEquals("[test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v4]]", m.values().toString());

        // multi table update plus
        m = AnalyzerUtils.collectAllSelectTableColumns(analyzeSuccess(
                "update test.tprimary set v2 = tp2.v2 from test.tprimary2 tp2 join test.t0 where " +
                        "test.tprimary.pk = tp2.pk and tp2.pk = test.t0.v1 and test.t0.v2 > 0"));
        Assertions.assertEquals("[test.t0, test.tprimary2, test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[v1, v2], [pk, v2], [pk]]", m.values().toString());

        // multi table update cte
        m = AnalyzerUtils.collectAllSelectTableColumns(analyzeSuccess(
                "with tp2cte as (select * from test.tprimary2 where v2 < 10) update test.tprimary " +
                        "set v2 = tp2cte.v2 from tp2cte where test.tprimary.pk = tp2cte.pk"));
        Assertions.assertEquals("[test.tprimary2, test.tprimary]", m.keySet().toString());
        Assertions.assertEquals("[[*, v2], [pk]]", m.values().toString());

    }

    @Test
    public void testContainsNonDeterministicFunction() {
        String[] sqls = {
                "select current_date(), a.v2, a.v1 from db1.t0 a",
                "select a.v2, a.v1 from db1.t0 a where current_date() > '2024-08-06'",
                "select a.v2, a.v1 from db1.t0 a where curdate() > '2024-08-06'",
                "select a.v2, a.v1 from db1.t0 a where now() > '2024-08-06'",
                "select * from (select current_date(), a.v2, a.v1 from db1.t0 a) t",
                "with cte as (select * from (select current_date(), a.v2, a.v1 from db1.t0 a) t) select * from cte",
        };
        for (String sql : sqls) {
            StatementBase statementBase = analyzeSuccess(sql);
            Pair<Boolean, String> result = AnalyzerUtils.containsNonDeterministicFunction(statementBase);
            Assertions.assertEquals(true, result.first);
            Assertions.assertTrue(!Strings.isNullOrEmpty(result.second));
        }
    }
    @Test
    public void testCalculateStringDiff() throws Exception {
        OlapTable t1 = (OlapTable) starRocksAssert.getTable(DB_NAME, "auto_tbl1");
        List<String> combinations = generateCaseCombinations("aac_def?gHi|Gx.com");
        List<List<String>> partitionValues = combinations.stream()
                .map(s -> Lists.newArrayList(s))
                .collect(Collectors.toList());
        Map<String, PartitionDesc> partitionNames = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        {
            AddPartitionClause addPartitionClauses =
                    AnalyzerUtils.getAddPartitionClauseFromPartitionValues(t1, partitionValues, false, "");
            ListPartitionDesc listPartitionDesc = (ListPartitionDesc) addPartitionClauses.getPartitionDesc();
            List<PartitionDesc> descs = listPartitionDesc.getPartitionDescs();
            Assertions.assertEquals(combinations.size(), descs.size());
            for (PartitionDesc desc : descs) {
                Assertions.assertTrue(partitionNames.get(desc.getPartitionName()) == null);
                partitionNames.put(desc.getPartitionName(), desc);
            }
        }

        {
            AddPartitionClause addPartitionClauses =
                    AnalyzerUtils.getAddPartitionClauseFromPartitionValues(t1, partitionValues, false, "");
            ListPartitionDesc listPartitionDesc = (ListPartitionDesc) addPartitionClauses.getPartitionDesc();
            List<PartitionDesc> descs = listPartitionDesc.getPartitionDescs();
            Assertions.assertEquals(combinations.size(), descs.size());
            for (PartitionDesc desc : descs) {
                Assertions.assertTrue(partitionNames.get(desc.getPartitionName()) != null);
            }
        }

        {
            AddPartitionClause addPartitionClauses =
                    AnalyzerUtils.getAddPartitionClauseFromPartitionValues(t1, partitionValues, false, "");
            ListPartitionDesc listPartitionDesc = (ListPartitionDesc) addPartitionClauses.getPartitionDesc();
            List<PartitionDesc> descs = listPartitionDesc.getPartitionDescs();
            Assertions.assertEquals(combinations.size(), descs.size());
            for (PartitionDesc desc : descs) {
                Assertions.assertTrue(partitionNames.get(desc.getPartitionName()) != null);
            }
        }
    }

    @Test
    public void testIntToHexString() {
        int j = Integer.MAX_VALUE - 50;
        Set<String> values = Sets.newTreeSet();
        for (int i = 0; i < 100; i++) {
            j += 1;
            String v = Integer.toHexString(j);
            Assertions.assertTrue(!values.contains(v));
            values.add(v);
        }
    }

    public static List<String> generateCaseCombinations(String input) {
        List<String> results = Lists.newArrayList();
        generateHelper(input.toCharArray(), 0, results);
        return results;
    }

    private static void generateHelper(char[] chars, int index, List<String> results) {
        if (index == chars.length) {
            // Add the current combination to the results
            results.add(new String(chars));
            return;
        }

        // If the current character is alphabetic, generate both lowercase and uppercase
        if (Character.isLetter(chars[index])) {
            // Recurse with the lowercase version
            chars[index] = Character.toLowerCase(chars[index]);
            generateHelper(chars, index + 1, results);

            // Recurse with the uppercase version
            chars[index] = Character.toUpperCase(chars[index]);
            generateHelper(chars, index + 1, results);
        } else {
            // If not alphabetic, just continue to the next character
            generateHelper(chars, index + 1, results);
        }
    }
}
