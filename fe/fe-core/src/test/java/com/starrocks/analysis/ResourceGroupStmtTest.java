package com.starrocks.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.starrocks.catalog.ResourceGroup;
import com.starrocks.catalog.ResourceGroupClassifier;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.persist.ResourceGroupOpEntry;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.DDLStmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.ResourceGroupAnalyzer;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.system.BackendResourceStat;
import com.starrocks.thrift.TWorkGroup;
import com.starrocks.thrift.TWorkGroupOpType;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.starrocks.common.ErrorCode.ERROR_NO_RG_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ResourceGroupStmtTest {
    private static final Pattern idPattern = Pattern.compile("\\bid=(\\b\\d+\\b)");
    private static StarRocksAssert starRocksAssert;
    private final String createRg1Sql = "create resource group rg1\n" +
            "to\n" +
            "    (user='rg1_user1', role='rg1_role1', query_type in ('select'), source_ip='192.168.2.1/24'),\n" +
            "    (user='rg1_user2', query_type in ('select'), source_ip='192.168.3.1/24'),\n" +
            "    (user='rg1_user3', source_ip='192.168.4.1/24'),\n" +
            "    (user='rg1_user4')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '10',\n" +
            "    'max_cpu_cores' = '8',\n" +
            "    'mem_limit' = '20%',\n" +
            "    'concurrency_limit' = '11',\n" +
            "    'type' = 'normal'\n" +
            ");";
    private final String createRg1IfNotExistsSql = "create resource group if not exists rg1\n" +
            "to\n" +
            "    (user='rg1_if_not_exists', role='rg1_role1', query_type in ('select'), source_ip='192.168.2.1/24')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '10',\n" +
            "    'max_cpu_cores' = '8',\n" +
            "    'mem_limit' = '20%',\n" +
            "    'concurrency_limit' = '11',\n" +
            "    'type' = 'normal'\n" +
            ");";

    private final String createRg1OrReplaceSql = "create resource group or replace rg1\n" +
            "to\n" +
            "    (user='rg1_or_replace', role='rg1_role1', query_type in ('select'), source_ip='192.168.2.1/24')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '10',\n" +
            "    'max_cpu_cores' = '8',\n" +
            "    'mem_limit' = '20%',\n" +
            "    'concurrency_limit' = '11',\n" +
            "    'type' = 'normal'\n" +
            ");";

    private final String createRg1IfNotExistsOrReplaceSql = "create resource group if not exists or replace rg1\n" +
            "to\n" +
            "    (user='rg1_if_not_exists_or_replace', role='rg1_role1', " +
            "query_type in ('select'), source_ip='192" +
            ".168.2.1/24')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '10',\n" +
            "    'max_cpu_cores' = '8',\n" +
            "    'mem_limit' = '20%',\n" +
            "    'concurrency_limit' = '11',\n" +
            "    'type' = 'normal'\n" +
            ");";

    private final String createRg2Sql = "create resource group rg2\n" +
            "to\n" +
            "    (role='rg2_role1', query_type in ('select' ), source_ip='192.168.5.1/24'),\n" +
            "    (role='rg2_role2', source_ip='192.168.6.1/24'),\n" +
            "    (role='rg2_role3')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '30',\n" +
            "    'mem_limit' = '50%',\n" +
            "    'concurrency_limit' = '20',\n" +
            "    'type' = 'normal'\n" +
            ");";
    private String createRg3Sql = "create resource group rg3\n" +
            "to\n" +
            "    (query_type in ('select'), source_ip='192.168.6.1/24'),\n" +
            "    (query_type in ('select'))\n" +
            "with (\n" +
            "    'cpu_core_limit' = '32',\n" +
            "    'mem_limit' = '80%',\n" +
            "    'concurrency_limit' = '10',\n" +
            "    'type' = 'normal'\n" +
            ");";
    private final String createRg4Sql = "create resource group rg4\n" +
            "to\n" +
            "     (source_ip='192.168.7.1/24')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '25',\n" +
            "    'mem_limit' = '80%',\n" +
            "    'concurrency_limit' = '10',\n" +
            "   'big_query_mem_limit'='1024',\n" +
            "   'big_query_scan_rows_limit'='1024',\n" +
            "   'big_query_cpu_second_limit'='1024',\n" +
            "    'type' = 'normal'\n" +
            ");";
    private final String createRg5Sql = "create resource group rg5\n" +
            "to\n" +
            "     (`db`='db1')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '25',\n" +
            "    'mem_limit' = '80%',\n" +
            "    'concurrency_limit' = '10',\n" +
            "    'type' = 'normal'\n" +
            ");";
    private final String createRg6Sql = "create resource group rg6\n" +
            "to\n" +
            "    (query_type in ('insert'), source_ip='192.168.6.1/24')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '32',\n" +
            "    'mem_limit' = '80%',\n" +
            "    'concurrency_limit' = '10',\n" +
            "    'type' = 'normal'\n" +
            ");";

    private final String createRg7Sql = "create resource group rg7\n" +
            "to\n" +
            "    (query_type in ('select'), source_ip='192.168.6.1/24')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '32',\n" +
            "    'mem_limit' = '80%',\n" +
            "    'concurrency_limit' = '10',\n" +
            "    'spill_mem_limit_threshold' = '0.3',\n" +
            "    'type' = 'normal'\n" +
            ");";

    private String createRtRg1Sql = "create resource group rt_rg1\n" +
            "to\n" +
            "     (user='rt_rg_user')\n" +
            "with (\n" +
            "    'cpu_core_limit' = '25',\n" +
            "    'mem_limit' = '80%',\n" +
            "    'concurrency_limit' = '10',\n" +
            "    'type' = 'short_query'\n" +
            ");";

    private final String createMVRg = "create resource group if not exists mv_rg" +
            "   with (" +
            "   'cpu_core_limit' = '10'," +
            "   'mem_limit' = '20%'," +
            "   'concurrency_limit' = '11'," +
            "   'type' = 'mv'" +
            "   );";

    @BeforeAll
    public static void setUp() throws Exception {
        BackendResourceStat.getInstance().setNumHardwareCoresOfBe(1, 32);

        UtFrameUtils.createMinStarRocksCluster();
        ConnectContext ctx = UtFrameUtils.createDefaultCtx();
        Config.alter_scheduler_interval_millisecond = 1;
        starRocksAssert = new StarRocksAssert(ctx);
        starRocksAssert.withRole("rg1_role1");
        starRocksAssert.withUser("rg1_user1");
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser("grant rg1_role1 to rg1_user1",
                ctx), ctx);
        List<String> databases = Arrays.asList("db1", "db2");
        for (String db : databases) {
            starRocksAssert.withDatabase(db);
        }
    }

    private static String rowsToString(List<List<String>> rows) {
        List<String> lines = rows.stream().map(
                row -> {
                    row.remove(1);
                    return java.lang.String.join("|",
                            row.toArray(new String[0])).replaceAll("id=\\d+(,\\s+)?", "");
                }
        ).collect(Collectors.toList());
        return java.lang.String.join("\n", lines.toArray(new String[0]));
    }

    private static String getClassifierId(String classifierStr) {
        Matcher mat = idPattern.matcher(classifierStr);
        Assertions.assertTrue(mat.find());
        return mat.group(1);
    }

    private void createResourceGroups() throws Exception {
        String[] sqls = new String[] {createRg1Sql, createRg2Sql,
                createRg3Sql, createRg4Sql, createRg5Sql, createRg6Sql, createRg7Sql, createRtRg1Sql};
        for (String sql : sqls) {
            starRocksAssert.executeResourceGroupDdlSql(sql);
        }
    }

    private void dropResourceGroups() throws Exception {
        String[] rgNames = new String[] {"rg1", "rg2", "rg3", "rg4", "rg5", "rg6", "rg7", "rt_rg1"};
        for (String name : rgNames) {
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP " + name);
        }
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        assertThat(rows.size()).isEqualTo(2);
    }

    private void assertResourceGroupNotExist(String wg) {
        Assertions.assertThrows(SemanticException.class,
                () -> starRocksAssert.executeResourceGroupShowSql("show verbose resource group " + wg),
                ERROR_NO_RG_ERROR.formatErrorMsg(wg));
    }

    @Test
    public void testCreateResourceGroup() throws Exception {
        createResourceGroups();
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String result = rowsToString(rows);
        String expect = "default_mv_wg|1|0|80.0%|null|0|0|0|null|80%|NORMAL|(weight=0.0)\n" +
                "default_wg|32|0|100.0%|null|0|0|0|null|100%|NORMAL|(weight=0.0)\n" +
                "rg1|10|0|20.0%|8|0|0|0|11|100%|NORMAL|(weight=4.475, user=rg1_user1, role=rg1_role1, query_type in (SELECT), source_ip=192.168.2.1/24)\n" +
                "rg1|10|0|20.0%|8|0|0|0|11|100%|NORMAL|(weight=3.475, user=rg1_user2, query_type in (SELECT), source_ip=192.168.3.1/24)\n" +
                "rg1|10|0|20.0%|8|0|0|0|11|100%|NORMAL|(weight=2.375, user=rg1_user3, source_ip=192.168.4.1/24)\n" +
                "rg1|10|0|20.0%|8|0|0|0|11|100%|NORMAL|(weight=1.0, user=rg1_user4)\n" +
                "rg2|30|0|50.0%|null|0|0|0|20|100%|NORMAL|(weight=3.475, role=rg2_role1, query_type in (SELECT), source_ip=192.168.5.1/24)\n" +
                "rg2|30|0|50.0%|null|0|0|0|20|100%|NORMAL|(weight=2.375, role=rg2_role2, source_ip=192.168.6.1/24)\n" +
                "rg2|30|0|50.0%|null|0|0|0|20|100%|NORMAL|(weight=1.0, role=rg2_role3)\n" +
                "rg3|32|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=2.475, query_type in (SELECT), source_ip=192.168.6.1/24)\n" +
                "rg3|32|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=1.1, query_type in (SELECT))\n" +
                "rg4|25|0|80.0%|null|1024|1024|1024|10|100%|NORMAL|(weight=1.375, source_ip=192.168.7.1/24)\n" +
                "rg5|25|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=10.0, db='db1')\n" +
                "rg6|32|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=2.475, query_type in (INSERT), source_ip=192.168.6.1/24)\n" +
                "rg7|32|0|80.0%|null|0|0|0|10|30%|NORMAL|(weight=2.475, query_type in (SELECT), source_ip=192.168.6.1/24)\n" +
                "rt_rg1|25|25|80.0%|null|0|0|0|10|100%|SHORT_QUERY|(weight=1.0, user=rt_rg_user)";
        Assertions.assertEquals(expect, result);
        dropResourceGroups();
    }

    @Test
    public void testCreateDuplicateResourceGroup() throws Exception {
        starRocksAssert.executeResourceGroupDdlSql(createRg1Sql);
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        assertResourceGroupNotExist("rg1");

        starRocksAssert.executeResourceGroupDdlSql(createRg1IfNotExistsSql);
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        Assertions.assertEquals(rows.size(), 1);
        String actual = rowsToString(rows);
        Assertions.assertTrue(actual.contains("rg1_if_not_exists"));

        starRocksAssert.executeResourceGroupDdlSql(createRg1OrReplaceSql);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        Assertions.assertEquals(rows.size(), 1);
        actual = rowsToString(rows);
        Assertions.assertTrue(actual.contains("rg1_or_replace"));

        starRocksAssert.executeResourceGroupDdlSql(createRg1IfNotExistsSql);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        Assertions.assertEquals(rows.size(), 1);
        actual = rowsToString(rows);
        Assertions.assertTrue(actual.contains("rg1_or_replace"));

        starRocksAssert.executeResourceGroupDdlSql("DROP resource group rg1");
        assertResourceGroupNotExist("rg1");

        starRocksAssert.executeResourceGroupDdlSql(createRg1OrReplaceSql);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        Assertions.assertEquals(rows.size(), 1);
        actual = rowsToString(rows);
        Assertions.assertTrue(actual.contains("rg1_or_replace"));

        starRocksAssert.executeResourceGroupDdlSql(createRg1IfNotExistsOrReplaceSql);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        Assertions.assertEquals(rows.size(), 1);
        actual = rowsToString(rows);
        System.out.println(actual);
        Assertions.assertTrue(actual.contains("rg1_if_not_exists_or_replace"));

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
    }

    @Test
    public void testCreateDuplicateResourceGroupFail() throws Exception {
        starRocksAssert.executeResourceGroupDdlSql(createRg1Sql);
        try {
            starRocksAssert.executeResourceGroupDdlSql(createRg1Sql);
            Assertions.fail("should throw error");
        } catch (Exception ignored) {
        }
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
    }

    @Test
    public void testCreateDResourceGroupWithIllegalProperty() throws Exception {
        String unknownPropertySql = "create resource group rg_unknown\n" +
                "to\n" +
                "    (user='rg1_user3', source_ip='192.168.4.1/24'),\n" +
                "    (user='rg1_user4')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '10',\n" +
                "    'mem_limit' = '20%',\n" +
                "    'concurrency_limit' = '11',\n" +
                "    'type' = 'normal', \n" +
                "    'unknown' = 'unknown'" +
                ");";
        Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(unknownPropertySql), "Unknown property: unknown");

        String illegalTypeSql = "create resource group rg_unknown\n" +
                "to\n" +
                "    (user='rg1_user3', source_ip='192.168.4.1/24'),\n" +
                "    (user='rg1_user4')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '10',\n" +
                "    'mem_limit' = '20%',\n" +
                "    'concurrency_limit' = '11',\n" +
                "    'type' = 'illegal-type'" +
                ");";
        Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(illegalTypeSql), "Only support 'normal', 'mv' and 'short_query' type");

        String illegalDefaultTypeSql = "create resource group rg_unknown\n" +
                "to\n" +
                "    (user='rg1_user3', source_ip='192.168.4.1/24'),\n" +
                "    (user='rg1_user4')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '10',\n" +
                "    'mem_limit' = '20%',\n" +
                "    'concurrency_limit' = '11',\n" +
                "    'type' = 'default'" +
                ");";
        Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(illegalDefaultTypeSql), "Only support 'normal', 'mv' and 'short_query' type");
    }

    @Test
    public void testQueryType() throws Exception {
        String sql1 = "create resource group rg_insert\n" +
                "to (user='rg_user3', query_type in ('mv')) with ('cpu_core_limit' = '10', 'mem_limit' = '20%')";
        try {
            starRocksAssert.executeResourceGroupDdlSql(sql1);
            Assertions.fail("should throw error");
        } catch (Exception e) {
            Assertions.assertEquals("Getting analyzing error. Detail message: Unsupported query_type: 'mv'.",
                    e.getMessage());
        }

    }

    @Test
    public void testAlterResourceGroupDropManyClassifiers() throws Exception {
        createResourceGroups();
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String rgName = rows.get(2).get(0);
        List<String> classifierIds = rows.stream().filter(row -> row.get(0).equals(rgName))
                .map(row -> getClassifierId(row.get(row.size() - 1))).collect(Collectors.toList());
        rows = rows.stream().peek(row -> {
            if (row.get(0).equals(rgName)) {
                row.set(row.size() - 1, "(weight=0.0)");
            }
        }).distinct().collect(Collectors.toList());
        String ids = String.join(",", classifierIds);
        String alterSql = String.format("ALTER RESOURCE GROUP %s DROP (%s)", rgName, ids);
        starRocksAssert.executeResourceGroupDdlSql(alterSql);
        String expect = rowsToString(rows);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String actual = rowsToString(rows);
        Assertions.assertEquals(expect, actual);
        dropResourceGroups();
    }

    @Test
    public void testAlterResourceGroupDropOneClassifier() throws Exception {
        createResourceGroups();
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        Random rand = new Random();
        Map<String, Integer> classifierCount = new HashMap<>();
        rows.forEach(row -> classifierCount
                .compute(row.get(0), (rg, countClassifier) -> countClassifier == null ? 1 : countClassifier + 1));
        Iterator<List<String>> it = rows.iterator();
        while (it.hasNext()) {
            List<String> row = it.next();
            if (rand.nextBoolean()) {
                continue;
            }
            String rgName = row.get(0);
            if (ResourceGroup.BUILTIN_WG_NAMES.contains(rgName)) {
                continue;
            }
            String classifier = row.get(row.size() - 1);
            String id = getClassifierId(classifier);
            Integer count = classifierCount.computeIfPresent(rgName, (rg, countClassifier) -> countClassifier - 1);
            if (count != 0) {
                // not last classifier of the rg, remove it
                it.remove();
            } else {
                row.set(row.size() - 1, "(weight=0.0)");
            }
            String alterSql = String.format("ALTER RESOURCE GROUP %s DROP (%s)", rgName, id);
            starRocksAssert.executeResourceGroupDdlSql(alterSql);
        }
        String expect = rowsToString(rows);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String actual = rowsToString(rows);
        Assertions.assertEquals(expect, actual);
        dropResourceGroups();
    }

    @Test
    public void testAlterResourceGroupDropAllClassifier() throws Exception {
        createResourceGroups();
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String rgName = "rg2";
        rows = rows.stream().peek(row -> {
            if (row.get(0).equals(rgName)) {
                row.set(row.size() - 1, "(weight=0.0)");
            }
        }).distinct().collect(Collectors.toList());
        String alterSql = String.format("ALTER RESOURCE GROUP %s DROP ALL", rgName);
        starRocksAssert.executeResourceGroupDdlSql(alterSql);
        String expect = rowsToString(rows);
        rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String actual = rowsToString(rows);
        Assertions.assertEquals(expect, actual);
        dropResourceGroups();
    }

    @Test
    public void testAlterResourceGroupAddOneClassifier() throws Exception {
        createResourceGroups();
        String sql = "" +
                "ALTER RESOURCE GROUP rg1 \n" +
                "ADD \n" +
                "   (user='rg1_user5', role='rg1_role5', source_ip='192.168.4.1/16')";

        starRocksAssert.executeResourceGroupDdlSql(sql);
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        String result = rowsToString(rows);
        Assertions.assertTrue(result.contains("rg1_user5"));
        dropResourceGroups();
    }

    @Test
    public void testAlterResourceGroupAddManyClassifiers() throws Exception {
        createResourceGroups();
        String sql = "" +
                "ALTER resource group rg2 \n" +
                "ADD \n" +
                "   (user='rg2_user4', role='rg2_role4', source_ip='192.168.4.1/16'),\n" +
                "   (role='rg2_role5', source_ip='192.168.5.1/16'),\n" +
                "   (source_ip='192.169.6.1/16');\n";

        starRocksAssert.executeResourceGroupDdlSql(sql);
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg2");
        String result = rowsToString(rows);
        Assertions.assertTrue(result.contains("rg2_user4"));
        Assertions.assertTrue(result.contains("rg2_role5"));
        Assertions.assertTrue(result.contains("192.169.6.1/16"));
        dropResourceGroups();
    }

    @Test
    public void testShowUnknownOrNotExistWorkGroup() throws Exception {
        starRocksAssert.executeResourceGroupDdlSql(createRg1Sql);

        starRocksAssert.executeResourceGroupDdlSql("ALTER RESOURCE GROUP rg1 DROP ALL;");
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg1");
        String actual = rowsToString(rows);
        String expect = "rg1|10|0|20.0%|8|0|0|0|11|100%|NORMAL|(weight=0.0)";
        Assertions.assertEquals(expect, actual);

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        assertResourceGroupNotExist("rg1");
    }

    @Test
    public void testChooseResourceGroup() throws Exception {
        createResourceGroups();
        String qualifiedUser = "rg1_user1";
        String remoteIp = "192.168.2.4";
        starRocksAssert.getCtx().setQualifiedUser(qualifiedUser);
        starRocksAssert.getCtx().setCurrentUserIdentity(new UserIdentity(qualifiedUser, "%"));
        starRocksAssert.getCtx().setCurrentRoleIds(
                starRocksAssert.getCtx().getGlobalStateMgr().getAuthorizationMgr().getRoleIdsByUser(
                        new UserIdentity(qualifiedUser, "%")
                )
        );
        starRocksAssert.getCtx().setRemoteIP(remoteIp);
        TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                starRocksAssert.getCtx(),
                ResourceGroupClassifier.QueryType.SELECT,
                null);
        Assertions.assertEquals("rg1", wg.getName());
        dropResourceGroups();
    }

    @Test
    public void testChooseInsertResourceGroup() throws Exception {
        createResourceGroups();
        String qualifiedUser = "rg1_user1";
        String remoteIp = "192.168.6.4";
        starRocksAssert.getCtx().setQualifiedUser(qualifiedUser);
        starRocksAssert.getCtx().setCurrentUserIdentity(new UserIdentity(qualifiedUser, "%"));
        starRocksAssert.getCtx().setRemoteIP(remoteIp);
        TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                starRocksAssert.getCtx(),
                ResourceGroupClassifier.QueryType.INSERT,
                null);
        Assertions.assertEquals(wg.getName(), "rg6");
        dropResourceGroups();
    }

    @Test
    public void testChooseResourceGroupWithDb() throws Exception {
        createResourceGroups();
        String qualifiedUser = "rg1_user1";
        String remoteIp = "192.168.2.4";
        starRocksAssert.getCtx().setQualifiedUser(qualifiedUser);
        starRocksAssert.getCtx().setCurrentUserIdentity(new UserIdentity(qualifiedUser, "%"));
        starRocksAssert.getCtx().setRemoteIP(remoteIp);
        {
            long dbId = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("db1").getId();
            Set<Long> dbIds = ImmutableSet.of(dbId);
            TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                    starRocksAssert.getCtx(),
                    ResourceGroupClassifier.QueryType.SELECT,
                    dbIds);
            Assertions.assertEquals("rg5", wg.getName());
        }
        {
            long dbId = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("db2").getId();
            Set<Long> dbIds = ImmutableSet.of(dbId);
            TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                    starRocksAssert.getCtx(),
                    ResourceGroupClassifier.QueryType.SELECT,
                    dbIds);
            Assertions.assertNotNull(wg);
            Assertions.assertEquals("rg1", wg.getName());
        }
        {
            Set<Long> dbIds = ImmutableSet.of(
                    GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("db1").getId(),
                    GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("db2").getId());
            TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                    starRocksAssert.getCtx(),
                    ResourceGroupClassifier.QueryType.SELECT,
                    dbIds);
            Assertions.assertEquals("rg1", wg.getName());
        }
        dropResourceGroups();
    }

    @Test
    public void testShowVisibleResourceGroups() throws Exception {
        createResourceGroups();
        String qualifiedUser = "rg1_user1";
        String remoteIp = "192.168.2.4";
        starRocksAssert.getCtx().setQualifiedUser(qualifiedUser);
        starRocksAssert.getCtx().setCurrentUserIdentity(new UserIdentity(qualifiedUser, "%"));
        starRocksAssert.getCtx().setRemoteIP(remoteIp);
        List<List<String>> rows = GlobalStateMgr.getCurrentState().getResourceGroupMgr().showAllResourceGroups(
                starRocksAssert.getCtx(), true, false);
        String result = rowsToString(rows);
        String expect = "" +
                "rg5|25|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=10.0, db='db1')\n" +
                "rg1|10|0|20.0%|8|0|0|0|11|100%|NORMAL|(weight=4.475, user=rg1_user1, role=rg1_role1, query_type in (SELECT), source_ip=192.168.2.1/24)\n" +
                "rg3|32|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=1.1, query_type in (SELECT))";
        Assertions.assertEquals(expect, result);
        dropResourceGroups();
    }

    @Test
    public void testAlterResourceGroupAlterProperties() throws Exception {
        createResourceGroups();
        String alterRg1Sql = "" +
                "ALTER resource group rg1 \n" +
                "WITH (\n" +
                "   'cpu_core_limit'='21'\n" +
                ")";

        String alterRg2Sql = "" +
                "ALTER resource group rg2 \n" +
                "WITH (\n" +
                "   'mem_limit'='37%'\n" +
                ")";

        String alterRg3Sql = "" +
                "ALTER resource group rg3 \n" +
                "WITH (\n" +
                "   'concurrency_limit'='23'\n" +
                ")";

        String alterRg4Sql = "" +
                "ALTER resource group rg4 \n" +
                "WITH (\n" +
                "   'mem_limit'='41%',\n" +
                "   'concurrency_limit'='23',\n" +
                "   'big_query_mem_limit'='1024',\n" +
                "   'big_query_scan_rows_limit'='1024',\n" +
                "   'big_query_cpu_second_limit'='1024',\n" +
                "   'cpu_core_limit'='13'\n" +
                ")";

        String alterRg1Sql2 = "" +
                "ALTER resource group rg1 \n" +
                "WITH (\n" +
                "   'max_cpu_cores'='4'\n" +
                ")";

        String alterRg3Sql2 = "" +
                "ALTER resource group rg3 \n" +
                "WITH (\n" +
                "   'max_cpu_cores'='3'\n" +
                ")";
        String[] sqls =
                new String[] {alterRg1Sql, alterRg2Sql, alterRg2Sql, alterRg3Sql, alterRg4Sql, alterRg1Sql2, alterRg3Sql2};
        for (String sql : sqls) {
            starRocksAssert.executeResourceGroupDdlSql(sql);
        }
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String result = rowsToString(rows);
        String expect = "default_mv_wg|1|0|80.0%|null|0|0|0|null|80%|NORMAL|(weight=0.0)\n" +
                "default_wg|32|0|100.0%|null|0|0|0|null|100%|NORMAL|(weight=0.0)\n" +
                "rg1|21|0|20.0%|4|0|0|0|11|100%|NORMAL|(weight=4.475, user=rg1_user1, role=rg1_role1, query_type in (SELECT), source_ip=192.168.2.1/24)\n" +
                "rg1|21|0|20.0%|4|0|0|0|11|100%|NORMAL|(weight=3.475, user=rg1_user2, query_type in (SELECT), source_ip=192.168.3.1/24)\n" +
                "rg1|21|0|20.0%|4|0|0|0|11|100%|NORMAL|(weight=2.375, user=rg1_user3, source_ip=192.168.4.1/24)\n" +
                "rg1|21|0|20.0%|4|0|0|0|11|100%|NORMAL|(weight=1.0, user=rg1_user4)\n" +
                "rg2|30|0|37.0%|null|0|0|0|20|100%|NORMAL|(weight=3.475, role=rg2_role1, query_type in (SELECT), source_ip=192.168.5.1/24)\n" +
                "rg2|30|0|37.0%|null|0|0|0|20|100%|NORMAL|(weight=2.375, role=rg2_role2, source_ip=192.168.6.1/24)\n" +
                "rg2|30|0|37.0%|null|0|0|0|20|100%|NORMAL|(weight=1.0, role=rg2_role3)\n" +
                "rg3|32|0|80.0%|3|0|0|0|23|100%|NORMAL|(weight=2.475, query_type in (SELECT), source_ip=192.168.6.1/24)\n" +
                "rg3|32|0|80.0%|3|0|0|0|23|100%|NORMAL|(weight=1.1, query_type in (SELECT))\n" +
                "rg4|13|0|41.0%|null|1024|1024|1024|23|100%|NORMAL|(weight=1.375, source_ip=192.168.7.1/24)\n" +
                "rg5|25|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=10.0, db='db1')\n" +
                "rg6|32|0|80.0%|null|0|0|0|10|100%|NORMAL|(weight=2.475, query_type in (INSERT), source_ip=192.168.6.1/24)\n" +
                "rg7|32|0|80.0%|null|0|0|0|10|30%|NORMAL|(weight=2.475, query_type in (SELECT), source_ip=192.168.6.1/24)\n" +
                "rt_rg1|25|25|80.0%|null|0|0|0|10|100%|SHORT_QUERY|(weight=1.0, user=rt_rg_user)";
        Assertions.assertEquals(expect, result);
        dropResourceGroups();
    }

    @Test
    public void testIllegalClassifier() {
        // case[0] is the DDL SQL.
        // case[1] is the expected error message.
        String[][] cases = {
                {
                        "create resource group rg1\n" +
                                "to\n" +
                                "     (`unsupported-key`='value')\n" +
                                "with (\n" +
                                "    'cpu_core_limit' = '25',\n" +
                                "    'mem_limit' = '80%',\n" +
                                "    'concurrency_limit' = '10',\n" +
                                "    'type' = 'normal'\n" +
                                ");",
                        "Unsupported classifier specifier"
                },
                {
                        "create resource group rg1\n" +
                                "to\n" +
                                "     (`unsupported-key` in ('select'))\n" +
                                "with (\n" +
                                "    'cpu_core_limit' = '25',\n" +
                                "    'mem_limit' = '80%',\n" +
                                "    'concurrency_limit' = '10',\n" +
                                "    'type' = 'normal'\n" +
                                ");",
                        "Unsupported classifier specifier"
                },
                {
                        "create resource group rg1\n" +
                                "to\n" +
                                "     (`query_type` in ('unsupported-query-type'))\n" +
                                "with (\n" +
                                "    'cpu_core_limit' = '25',\n" +
                                "    'mem_limit' = '80%',\n" +
                                "    'concurrency_limit' = '10',\n" +
                                "    'type' = 'normal'\n" +
                                ");",
                        "Unsupported query_type"
                },
        };

        for (String[] c : cases) {
            Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(c[0]), c[1]);
        }
    }

    @Test
    public void testCreateShortQueryResourceGroup() throws Exception {
        String createRtRg1ReplaceSql = "create resource group if not exists or replace rg1\n" +
                "to\n" +
                "     (`db`='db1')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '25',\n" +
                "    'mem_limit' = '80%',\n" +
                "    'concurrency_limit' = '10',\n" +
                "    'type' = 'short_query'\n" +
                ");";

        String createNormalRg1ReplaceSql = "create resource group if not exists or replace rg1\n" +
                "to\n" +
                "     (`db`='db1')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '25',\n" +
                "    'mem_limit' = '80%',\n" +
                "    'concurrency_limit' = '10',\n" +
                "    'type' = 'normal'\n" +
                ");";

        String createRtRg2ReplaceSql = "create resource group if not exists or replace rg2\n" +
                "to\n" +
                "     (`db`='db1')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '25',\n" +
                "    'mem_limit' = '80%',\n" +
                "    'concurrency_limit' = '10',\n" +
                "    'type' = 'short_query'\n" +
                ");";

        String createNormalRg2ReplaceSql = "create resource group if not exists or replace rg2\n" +
                "to\n" +
                "     (`db`='db1')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '25',\n" +
                "    'mem_limit' = '80%',\n" +
                "    'concurrency_limit' = '10',\n" +
                "    'type' = 'normal'\n" +
                ");";

        String alterRg1ToNormalTypeSql = "ALTER resource group rg1\n" +
                "WITH ('type' = 'normal')";

        // Create realtime rg1.
        starRocksAssert.executeResourceGroupDdlSql(createRtRg1ReplaceSql);

        // Fail to modify type.
        Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(alterRg1ToNormalTypeSql), "type of ResourceGroup is immutable");

        // Create normal rg2 and fail to replace it with realtime rg2.
        starRocksAssert.executeResourceGroupDdlSql(createNormalRg2ReplaceSql);
        Assertions.assertThrows(DdlException.class, () -> starRocksAssert.executeResourceGroupDdlSql(createRtRg2ReplaceSql), "There can be only one short_query RESOURCE_GROUP (rg1)");

        // Replace realtime rg1 with normal rg1, and create realtime rg2.
        starRocksAssert.executeResourceGroupDdlSql(createNormalRg1ReplaceSql);
        starRocksAssert.executeResourceGroupDdlSql(createRtRg2ReplaceSql);

        // Replace realtime rg2 with normal rg2, and create realtime rg1.
        starRocksAssert.executeResourceGroupDdlSql(createNormalRg2ReplaceSql);
        starRocksAssert.executeResourceGroupDdlSql(createRtRg1ReplaceSql);

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg2");
    }

    @Test
    public void testChooseShortQueryResourceGroup() throws Exception {
        createResourceGroups();

        // Prefer the short query group regardless its classifier weight is the lowest.
        String qualifiedUser = "rt_rg_user";
        long dbId = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("db1").getId();
        Set<Long> dbs = ImmutableSet.of(dbId);
        starRocksAssert.getCtx().setQualifiedUser(qualifiedUser);
        starRocksAssert.getCtx().setCurrentUserIdentity(new UserIdentity(qualifiedUser, "%"));
        TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                starRocksAssert.getCtx(),
                ResourceGroupClassifier.QueryType.SELECT,
                dbs);
        Assertions.assertEquals("rt_rg1", wg.getName());

        dropResourceGroups();
    }

    @Test
    public void testAlterMvRgAddClassifier() throws Exception {
        starRocksAssert.executeResourceGroupDdlSql(createMVRg);
        String sql = "" +
                "ALTER RESOURCE GROUP mv_rg \n" +
                "ADD \n" +
                "   (user='rg1_user5', role='rg1_role5', source_ip='192.168.4.1/16')";

        Assertions.assertThrows(DdlException.class, () -> starRocksAssert.executeResourceGroupDdlSql(sql), "MV Resource Group not support classifiers.");

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP mv_rg;");
    }

    @Test
    public void testCreateMVRgWithClassifier() throws Exception {
        String createSql = "create resource group if not exists mv_rg2" +
                "   to" +
                "   (user='rg1_or_replace', role='rg1_role1', query_type in ('select'), source_ip='192.168.2.1/24')" +
                "   with (" +
                "   'cpu_core_limit' = '10'," +
                "   'mem_limit' = '20%'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'mv'" +
                "   );";

        Assertions.assertThrows(DdlException.class, () -> starRocksAssert.executeResourceGroupDdlSql(createSql), "MV Resource Group not support classifiers.");
    }

    @Test
    public void testCreateNormalRgWithoutClassifier() throws Exception {
        String createSql = "create resource group if not exists rg2" +
                "   with (" +
                "   'cpu_core_limit' = '10'," +
                "   'mem_limit' = '20%'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        Assertions.assertThrows(DdlException.class, () -> starRocksAssert.executeResourceGroupDdlSql(createSql), "This type Resource Group need define classifiers.");
    }

    @Test
    public void testValidateMaxCpuCores() throws Exception {
        final int numCores = 32;

        String createSQLTemplate = "create resource group rg_valid_max_cpu_cores\n" +
                "to\n" +
                "    (user='rg1_if_not_exists')\n" +
                "   with (" +
                "   'cpu_core_limit' = '%d'," +
                "   'mem_limit' = '20%%'," +
                "   'max_cpu_cores' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        String alterSQLTemplate = "ALTER resource group rg_valid_max_cpu_cores \n" +
                "WITH (\n" +
                "   'max_cpu_cores'='%d'\n" +
                ")";

        {
            String sql = String.format(createSQLTemplate, numCores + 5);
            Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(sql), "max_cpu_cores should range from 0 to 16");
        }

        {
            String sql = "create resource group rg_valid_max_cpu_cores\n" +
                    "to\n" +
                    "    (user='rg1_if_not_exists')\n" +
                    "   with (" +
                    "   'cpu_core_limit' = 'invalid-format'," +
                    "   'mem_limit' = '20%%'," +
                    "   'max_cpu_cores' = '17'," +
                    "   'concurrency_limit' = '11'," +
                    "   'type' = 'normal'" +
                    "   );";
            Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(sql));
        }

        {
            String sql = String.format(createSQLTemplate, numCores);
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows =
                    starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg_valid_max_cpu_cores");
            String actual = rowsToString(rows);
            String expect = "rg_valid_max_cpu_cores|32|0|20.0%|17|0|0|0|11|100%|NORMAL|(weight=1.0, user=rg1_if_not_exists)";
            Assertions.assertEquals(expect, actual);
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg_valid_max_cpu_cores");
        }

        {
            String sql = String.format(createSQLTemplate, numCores - 1);
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows =
                    starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg_valid_max_cpu_cores");
            String actual = rowsToString(rows);
            String expect = "rg_valid_max_cpu_cores|31|0|20.0%|17|0|0|0|11|100%|NORMAL|(weight=1.0, user=rg1_if_not_exists)";
            Assertions.assertEquals(expect, actual);
        }

        {
            String sql = String.format(alterSQLTemplate, numCores + 10);
            Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(sql), "max_cpu_cores should range from 0 to 16");
        }

        {
            String sql = "ALTER resource group rg_valid_max_cpu_cores \n" +
                    "WITH (\n" +
                    "   'max_cpu_cores'='invalid-format'\n" +
                    ")";
            Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql(sql));
        }

        {
            String sql = String.format(alterSQLTemplate, numCores);
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows =
                    starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg_valid_max_cpu_cores");
            String actual = rowsToString(rows);
            String expect = "rg_valid_max_cpu_cores|31|0|20.0%|32|0|0|0|11|100%|NORMAL|(weight=1.0, user=rg1_if_not_exists)";
            Assertions.assertEquals(expect, actual);
        }

        {
            String sql = String.format(alterSQLTemplate, numCores - 2);
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows =
                    starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg_valid_max_cpu_cores");
            String actual = rowsToString(rows);
            String expect = "rg_valid_max_cpu_cores|31|0|20.0%|30|0|0|0|11|100%|NORMAL|(weight=1.0, user=rg1_if_not_exists)";
            Assertions.assertEquals(expect, actual);
        }

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg_valid_max_cpu_cores");
    }

    @Test
    public void testChooseResourceGroupWithPlanCost() throws Exception {
        String createRg1SQL = "create resource group rg1_plan_cost\n" +
                "to (\n" +
                "   user='rg1_user1'," +
                "   plan_cpu_cost_range='[0, 1000)'," +
                "   plan_mem_cost_range='[100, 200)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";
        String createRg2SQL = "create resource group rg2_plan_cost\n" +
                "to (\n" +
                "   user='rg1_user1'," +
                "   plan_cpu_cost_range='[0, 1000)'," +
                "   plan_mem_cost_range='[151, 200)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";
        String createRg3SQL = "create resource group rg3_plan_cost\n" +
                "to (\n" +
                "   user='rg1_user1'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";
        starRocksAssert.executeResourceGroupDdlSql(createRg1SQL);
        starRocksAssert.executeResourceGroupDdlSql(createRg2SQL);
        starRocksAssert.executeResourceGroupDdlSql(createRg3SQL);

        final String qualifiedUser = "rg1_user1";
        final String remoteIp = "192.168.2.4";

        starRocksAssert.getCtx().setQualifiedUser(qualifiedUser);
        starRocksAssert.getCtx().setCurrentUserIdentity(new UserIdentity(qualifiedUser, "%"));
        starRocksAssert.getCtx().setCurrentRoleIds(
                starRocksAssert.getCtx().getGlobalStateMgr().getAuthorizationMgr().getRoleIdsByUser(
                        new UserIdentity(qualifiedUser, "%")
                )
        );
        starRocksAssert.getCtx().setRemoteIP(remoteIp);

        {
            starRocksAssert.getCtx().getAuditEventBuilder().setPlanCpuCosts(100);
            starRocksAssert.getCtx().getAuditEventBuilder().setPlanMemCosts(150);

            TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                    starRocksAssert.getCtx(),
                    ResourceGroupClassifier.QueryType.SELECT,
                    null);
            Assertions.assertEquals("rg1_plan_cost", wg.getName());
        }

        {
            starRocksAssert.getCtx().getAuditEventBuilder().setPlanCpuCosts(10000);
            starRocksAssert.getCtx().getAuditEventBuilder().setPlanMemCosts(150);

            TWorkGroup wg = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                    starRocksAssert.getCtx(),
                    ResourceGroupClassifier.QueryType.SELECT,
                    null);
            Assertions.assertEquals("rg3_plan_cost", wg.getName());
        }

        starRocksAssert.executeResourceGroupDdlSql("drop resource group rg1_plan_cost");
        starRocksAssert.executeResourceGroupDdlSql("drop resource group rg2_plan_cost");
        starRocksAssert.executeResourceGroupDdlSql("drop resource group rg3_plan_cost");
    }

    @Test
    public void testCreateWithLegalPlanCostRange() throws Exception {
        String createSQLTemplate = "create resource group rg_valid_plan_cost_range\n" +
                "to (\n" +
                "   user='rg1_if_not_exists'," +
                "   plan_cpu_cost_range='%s'," +
                "   plan_mem_cost_range='%s'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        class TestCase {
            final String planCpuCostRange;
            final String PlanMemCostRange;
            final String expectedOutput;

            public TestCase(String planCpuCostRange, String planMemCostRange, String expectedOutput) {
                this.planCpuCostRange = planCpuCostRange;
                PlanMemCostRange = planMemCostRange;
                this.expectedOutput = expectedOutput;
            }
        }

        List<TestCase> testCases = ImmutableList.of(
                new TestCase("[1.12345678901234567,10.2)", "[2, 100.2)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_if_not_exists, plan_cpu_cost_range=[1.1234567890123457, 10.2), plan_mem_cost_range=[2.0, 100.2))"),
                new TestCase("[1.1,10.2)", "[2, 100.2)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_if_not_exists, plan_cpu_cost_range=[1.1, 10.2), plan_mem_cost_range=[2.0, 100.2))"),

                new TestCase("[-1,10)", "[2, 100)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_if_not_exists, plan_cpu_cost_range=[-1.0, 10.0), plan_mem_cost_range=[2.0, 100.0))"),
                new TestCase("[0, 10)", "[0, 100)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_if_not_exists, plan_cpu_cost_range=[0.0, 10.0), plan_mem_cost_range=[0.0, 100.0))"),
                new TestCase(" [ 0,  10) ", "  [ 0,  100  )  ",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_if_not_exists, plan_cpu_cost_range=[0.0, 10.0), plan_mem_cost_range=[0.0, 100.0))")
        );
        for (TestCase c : testCases) {
            String createSQL = String.format(createSQLTemplate, c.planCpuCostRange, c.PlanMemCostRange);
            starRocksAssert.executeResourceGroupDdlSql(createSQL);

            List<List<String>> rows =
                    starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg_valid_plan_cost_range");
            String actual = rowsToString(rows);
            Assertions.assertEquals(c.expectedOutput, actual);

            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg_valid_plan_cost_range");
        }
    }

    @Test
    public void testAlterWithLegalPlanCostRange() throws Exception {
        String createSQL = "create resource group rg_valid_plan_cost_range\n" +
                "to (\n" +
                "   user='rg1_user'," +
                "   plan_cpu_cost_range='[100, 1000)'," +
                "   plan_mem_cost_range='[0, 100)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        String alterTemplate = "ALTER RESOURCE GROUP rg_valid_plan_cost_range \n" +
                "ADD \n" +
                "   (user='rg1_user', plan_cpu_cost_range='%s', plan_mem_cost_range='%s')";

        class TestCase {
            final String planCpuCostRange;
            final String PlanMemCostRange;
            final String expectedOutput;

            public TestCase(String planCpuCostRange, String planMemCostRange, String expectedOutput) {
                this.planCpuCostRange = planCpuCostRange;
                PlanMemCostRange = planMemCostRange;
                this.expectedOutput = expectedOutput;
            }
        }

        List<TestCase> testCases = ImmutableList.of(
                new TestCase("[1.12345678901234567,10.2)", "[2, 100.2)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[100.0, 1000.0), plan_mem_cost_range=[0.0, 100.0))\n" +
                                "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[1.1234567890123457, 10.2), plan_mem_cost_range=[2.0, 100.2))"),
                new TestCase("[1.1,10.2)", "[2, 100.2)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[100.0, 1000.0), plan_mem_cost_range=[0.0, 100.0))\n" +
                                "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[1.1, 10.2), plan_mem_cost_range=[2.0, 100.2))"),

                new TestCase("[-1,10)", "[2, 100)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[100.0, 1000.0), plan_mem_cost_range=[0.0, 100.0))\n" +
                                "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[-1.0, 10.0), plan_mem_cost_range=[2.0, 100.0))"),
                new TestCase("[0, 10)", "[0, 100)",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[100.0, 1000.0), plan_mem_cost_range=[0.0, 100.0))\n" +
                                "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[0.0, 10.0), plan_mem_cost_range=[0.0, 100.0))"),
                new TestCase(" [ 0,  10) ", "  [ 0,  100  )  ",
                        "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[100.0, 1000.0), plan_mem_cost_range=[0.0, 100.0))\n" +
                                "rg_valid_plan_cost_range|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[0.0, 10.0), plan_mem_cost_range=[0.0, 100.0))")
        );
        for (TestCase c : testCases) {
            starRocksAssert.executeResourceGroupDdlSql(createSQL);

            String alterSQL = String.format(alterTemplate, c.planCpuCostRange, c.PlanMemCostRange);
            starRocksAssert.executeResourceGroupDdlSql(alterSQL);

            List<List<String>> rows =
                    starRocksAssert.executeResourceGroupShowSql("show verbose resource group rg_valid_plan_cost_range");
            String actual = rowsToString(rows);
            Assertions.assertEquals(c.expectedOutput, actual);

            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg_valid_plan_cost_range");
        }

    }

    @Test
    public void testCreateWithIllegalPlanCostRange() {
        String createSQLTemplate = "create resource group rg_valid_plan_cost_range\n" +
                "to (\n" +
                "   user='rg1_if_not_exists'," +
                "   plan_cpu_cost_range='%s'," +
                "   plan_mem_cost_range='%s'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        class TestCase {
            final String planCpuCostRange;
            final String PlanMemCostRange;
            final String expectedErrMsg;

            public TestCase(String planCpuCostRange, String planMemCostRange) {
                this.planCpuCostRange = planCpuCostRange;
                PlanMemCostRange = planMemCostRange;
                this.expectedErrMsg = ResourceGroupClassifier.CostRange.FORMAT_STR_RANGE_MESSAGE;
            }
        }

        List<TestCase> testCases = ImmutableList.of(
                new TestCase("[1000,infinity)", "[2, 100)"),
                new TestCase("[-infinity,1000)", "[2, 100)"),

                new TestCase("a [1, 10)", "[2, 100)"),
                new TestCase("[1, 10)", "[2, 100) b"),

                new TestCase("[1, 10]", "[2, 100)"),

                new TestCase("[1,1 0)", "[2, 100)"),
                new TestCase("[1,1.1.0)", "[2, 100)"),
                new TestCase("[2, 100)", "[-1-2, 1.0)"),
                new TestCase("[- 1, 1.0)", "[2, 100)"),
                new TestCase("[I nfinity, 10)", "[2, 100)"),

                new TestCase("[1000,10)", "[2, 100)"),
                new TestCase("[1000,-1)", "[2, 100)"),

                new TestCase("[abc, 1000)", "[2, 100)")
        );
        for (TestCase c : testCases) {
            String sql = String.format(createSQLTemplate, c.planCpuCostRange, c.PlanMemCostRange);
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    c.expectedErrMsg);
        }
    }

    @Test
    public void testAlterWithIllegalPlanCostRange() throws Exception {
        String createSQL = "create resource group rg_valid_plan_cost_range\n" +
                "to (\n" +
                "   user='rg1_user'," +
                "   plan_cpu_cost_range='[100, 1000)'," +
                "   plan_mem_cost_range='[0, 100)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        String alterTemplate = "ALTER RESOURCE GROUP rg_valid_plan_cost_range \n" +
                "ADD \n" +
                "   (user='rg1_user', plan_cpu_cost_range='%s', plan_mem_cost_range='%s')";

        class TestCase {
            final String planCpuCostRange;
            final String PlanMemCostRange;
            final String expectedErrMsg;

            public TestCase(String planCpuCostRange, String planMemCostRange) {
                this.planCpuCostRange = planCpuCostRange;
                PlanMemCostRange = planMemCostRange;
                this.expectedErrMsg = ResourceGroupClassifier.CostRange.FORMAT_STR_RANGE_MESSAGE;
            }
        }

        List<TestCase> testCases = ImmutableList.of(
                new TestCase("[1000,Infinity)", "[2, 100)"),
                new TestCase("[1000,NaN)", "[2, 100)"),
                new TestCase("[-infinity,1000)", "[2, 100)"),

                new TestCase("[1, 10]", "[2, 100)"),

                new TestCase("[1,1 0)", "[2, 100)"),
                new TestCase("[1,1.1.0)", "[2, 100)"),
                new TestCase("[-1-2, 1.0)", "[2, 100)"),
                new TestCase("[- 1, 1.0)", "[2, 100)"),
                new TestCase("[I nfinity, 10)", "[2, 100)"),

                new TestCase("[1000,10)", "[2, 100)"),
                new TestCase("[1000,-1)", "[2, 100)"),

                new TestCase("[abc, 1000)", "[2, 100)")
        );
        for (TestCase c : testCases) {
            starRocksAssert.executeResourceGroupDdlSql(createSQL);

            String alterSQL = String.format(alterTemplate, c.planCpuCostRange, c.PlanMemCostRange);
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(alterSQL),
                    c.expectedErrMsg);

            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg_valid_plan_cost_range");
        }
    }

    @Test
    public void testSerializeAndDeserialize() throws Exception {
        String createSQL1 = "create resource group rg1\n" +
                "to (\n" +
                "   user='rg1_user'," +
                "   plan_cpu_cost_range='[1, 2)'," +
                "   plan_mem_cost_range='[-100, 1000)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";
        String createSQL2 = "create resource group rg2\n" +
                "to (\n" +
                "   user='rg1_user'," +
                "   plan_mem_cost_range='[0, 2000)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '30%'," +
                "   'cpu_core_limit' = '32'," +
                "   'concurrency_limit' = '31'," +
                "   'type' = 'normal'" +
                "   );";
        String showResult = "default_mv_wg|1|0|80.0%|null|0|0|0|null|80%|NORMAL|(weight=0.0)\n" +
                "default_wg|32|0|100.0%|null|0|0|0|null|100%|NORMAL|(weight=0.0)\n" +
                "rg1|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=3.0, user=rg1_user, plan_cpu_cost_range=[1.0, 2.0), plan_mem_cost_range=[-100.0, 1000.0))\n" +
                "rg2|32|0|30.0%|null|0|0|0|31|100%|NORMAL|(weight=2.0, user=rg1_user, plan_mem_cost_range=[0.0, 2000.0))";

        starRocksAssert.executeResourceGroupDdlSql(createSQL1);
        starRocksAssert.executeResourceGroupDdlSql(createSQL2);
        {
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
            String actual = rowsToString(rows);
            Assertions.assertEquals(showResult, actual);
        }

        // 1. Test serialize and deserialize ResourceGroupMgr.
        try (ByteArrayOutputStream bufferOutput = new ByteArrayOutputStream()) {
            try (DataOutputStream outputStream = new DataOutputStream(bufferOutput)) {
                GlobalStateMgr.getCurrentState().getResourceGroupMgr().write(outputStream);
            }

            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg2");
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
            assertThat(rows.size()).isEqualTo(2);

            try (ByteArrayInputStream bufferInput = new ByteArrayInputStream(bufferOutput.toByteArray());
                    DataInputStream inputStream = new DataInputStream(bufferInput)) {
                GlobalStateMgr.getCurrentState().getResourceGroupMgr().readFields(inputStream);
            }
        }
        {
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
            String actual = rowsToString(rows);
            Assertions.assertEquals(showResult, actual);
        }

        // 2. Test serialize and deserialize ResourceGroupOpEntry.
        ResourceGroup rg1 = GlobalStateMgr.getCurrentState().getResourceGroupMgr().getResourceGroup("rg1");
        ResourceGroup rg2 = GlobalStateMgr.getCurrentState().getResourceGroupMgr().getResourceGroup("rg2");
        List<ResourceGroup> rgs = ImmutableList.of(rg1, rg2);
        for (ResourceGroup rg : rgs) {
            ResourceGroupOpEntry opEntryRg = new ResourceGroupOpEntry(TWorkGroupOpType.WORKGROUP_OP_ALTER, rg);
            try (ByteArrayOutputStream bufferOutput = new ByteArrayOutputStream()) {
                try (DataOutputStream outputStream = new DataOutputStream(bufferOutput)) {
                    opEntryRg.write(outputStream);
                }

                try (ByteArrayInputStream bufferInput = new ByteArrayInputStream(bufferOutput.toByteArray());
                        DataInputStream inputStream = new DataInputStream(bufferInput)) {
                    ResourceGroupOpEntry opEntryRgRead = ResourceGroupOpEntry.read(inputStream);
                    assertThat(opEntryRgRead).usingRecursiveComparison().isEqualTo(opEntryRg);
                }
            }
        }

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg2");
    }

    @Test
    public void testClassifierOnlyWithPlanCost() throws Exception {
        String createSQL1 = "create resource group rg1\n" +
                "to (\n" +
                "   plan_cpu_cost_range='[11, 12)'," +
                "   plan_mem_cost_range='[-100, 11000)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";
        String createSQL2 = "create resource group rg2\n" +
                "to (\n" +
                "   plan_cpu_cost_range='[21, 22)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '16'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";
        String createSQL3 = "create resource group rg3\n" +
                "to (\n" +
                "   plan_mem_cost_range='[-100, 31000)'" +
                ")\n" +
                "   with (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_core_limit' = '17'," +
                "   'concurrency_limit' = '11'," +
                "   'type' = 'normal'" +
                "   );";

        starRocksAssert.executeResourceGroupDdlSql(createSQL1);
        starRocksAssert.executeResourceGroupDdlSql(createSQL2);
        starRocksAssert.executeResourceGroupDdlSql(createSQL3);

        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        String actual = rowsToString(rows);
        String expected =
                "default_mv_wg|1|0|80.0%|null|0|0|0|null|80%|NORMAL|(weight=0.0)\n" +
                        "default_wg|32|0|100.0%|null|0|0|0|null|100%|NORMAL|(weight=0.0)\n" +
                        "rg1|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=2.0, plan_cpu_cost_range=[11.0, 12.0), plan_mem_cost_range=[-100.0, 11000.0))\n" +
                        "rg2|16|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=1.0, plan_cpu_cost_range=[21.0, 22.0))\n" +
                        "rg3|17|0|20.0%|null|0|0|0|11|100%|NORMAL|(weight=1.0, plan_mem_cost_range=[-100.0, 31000.0))";
        Assertions.assertEquals(expected, actual);

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg2");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg3");
    }

    @Test
    public void testEmptyClassifier() {
        Assertions.assertThrows(
                SemanticException.class, () -> ResourceGroupAnalyzer.convertPredicateToClassifier(Collections.emptyList()), "Getting analyzing error. Detail message: At least one of ('user', 'role', 'query_type', 'db', " +
                        "'source_ip', 'plan_cpu_cost_range', 'plan_mem_cost_range') should be given");
    }

    @Test
    public void testDropResourceGroupIfExists() throws Exception {
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP IF EXISTS rg2");
        assertResourceGroupNotExist("rg2");
    }

    @Test
    public void testSourceIP() throws Exception {
        String createSQL = "create resource group rg1\n" +
                "to\n" +
                "    (source_ip='192.168.2.1/32')\n" +
                "with (\n" +
                "    'cpu_core_limit' = '10',\n" +
                "    'max_cpu_cores' = '8',\n" +
                "    'mem_limit' = '20%'\n" +
                ");";
        starRocksAssert.executeResourceGroupDdlSql(createSQL);
        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show verbose resource groups all");
        assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|null|0|0|0|null|80%|NORMAL|(weight=0.0)\n" +
                "default_wg|32|0|100.0%|null|0|0|0|null|100%|NORMAL|(weight=0.0)\n" +
                "rg1|10|0|20.0%|8|0|0|0|null|100%|NORMAL|(weight=1.5, source_ip=192.168.2.1/32)");

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
    }

    @Test
    public void testGetDedicateCpuCores() throws Exception {
        starRocksAssert.executeResourceGroupDdlSql("CREATE RESOURCE GROUP rg1\n" +
                "TO (user='rg1_user')\n" +
                "WITH (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_weight' = '17'" +
                ");");
        starRocksAssert.executeResourceGroupDdlSql("CREATE RESOURCE GROUP rg2\n" +
                "TO (user='rg2_user')\n" +
                "WITH (" +
                "   'mem_limit' = '20%'," +
                "   'exclusive_cpu_cores' = '16'" +
                ");");
        starRocksAssert.executeResourceGroupDdlSql("CREATE RESOURCE GROUP rt_rg1\n" +
                "TO (user='rt_rg1_user')\n" +
                "WITH (" +
                "   'mem_limit' = '20%'," +
                "   'cpu_weight' = '15'," +
                "   'type' = 'short_query'" +
                ");");

        List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
        assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                "rg1|17|0|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)\n" +
                "rg2|0|16|20.0%|0|0|0|null|100%|(weight=1.0, user=rg2_user)\n" +
                "rt_rg1|15|15|20.0%|0|0|0|null|100%|(weight=1.0, user=rt_rg1_user)");

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg2");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rt_rg1");
    }

    @Test
    public void testValidateCpuParametersForCreate() throws Exception {
        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '17'," +
                    "   'exclusive_cpu_cores' = '17'" +
                    ");";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' and 'exclusive_cpu_cores' cannot be present and positive at the same time");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '0'," +
                    "   'exclusive_cpu_cores' = '0'" +
                    ");";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' or 'exclusive_cpu_cores' must be positive");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'" +
                    ");";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' or 'exclusive_cpu_cores' must be positive");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '32'" +
                    ");";
            Assertions.assertThrows(
                    SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "exclusive_cpu_cores cannot exceed the minimum number of CPU cores available on the backends minus one [31]");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '17'," +
                    "   'exclusive_cpu_cores' = '0'" +
                    ");";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|17|0|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '17'" +
                    ");";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|17|0|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '0'," +
                    "   'exclusive_cpu_cores' = '17'" +
                    ");";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|0|17|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '17'" +
                    ");";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|0|17|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        }
    }

    @Test
    public void testValidateCpuParametersForAlter() throws Exception {
        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '17'" +
                    ");";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|17|0|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'cpu_weight' = '16'," +
                    "   'exclusive_cpu_cores' = '15'" +
                    ")";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' and 'exclusive_cpu_cores' cannot be present and positive at the same time");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'exclusive_cpu_cores' = '15'" +
                    ")";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' and 'exclusive_cpu_cores' cannot be present and positive at the same time");
        }

        {

            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'cpu_weight' = '0'," +
                    "   'exclusive_cpu_cores' = '0'" +
                    ")";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' or 'exclusive_cpu_cores' must be positive");
        }

        {

            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'cpu_weight' = '0'" +
                    ")";
            Assertions.assertThrows(SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "property 'cpu_weight' or 'exclusive_cpu_cores' must be positive");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'cpu_weight' = '0'," +
                    "   'exclusive_cpu_cores' = '32'" +
                    ")";
            Assertions.assertThrows(
                    SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "exclusive_cpu_cores cannot exceed the minimum number of CPU cores available on the backends minus one [31]");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'cpu_weight' = '0'," +
                    "   'exclusive_cpu_cores' = '16'" +
                    ")";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|0|16|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'cpu_weight' = '15'," +
                    "   'exclusive_cpu_cores' = '0'" +
                    ")";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|15|0|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");
        }

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
    }

    @Test
    public void testValidateExclusiveCpuCoresWithShortQuery() throws Exception {
        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '17'," +
                    "   'type' = 'short_query'" +
                    ");";
            Assertions.assertThrows(
                    SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "'short_query' ResourceGroup cannot set 'exclusive_cpu_cores', since it use 'cpu_weight' as 'exclusive_cpu_cores'");
        }

        {
            starRocksAssert.executeResourceGroupDdlSql("CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'cpu_weight' = '17'," +
                    "   'type' = 'short_query'" +
                    ");");
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|17|17|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)");

            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'exclusive_cpu_cores' = '16'" +
                    ")";
            Assertions.assertThrows(
                    SemanticException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "'short_query' ResourceGroup cannot set 'exclusive_cpu_cores', since it use 'cpu_weight' as 'exclusive_cpu_cores'");

            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        }
    }

    @Test
    public void testValidateSumExclusiveCpuCores() throws Exception {
        {
            starRocksAssert.executeResourceGroupDdlSql("CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '16'" +
                    ");");
            starRocksAssert.executeResourceGroupDdlSql("CREATE RESOURCE GROUP rg2\n" +
                    "TO (user='rg2_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '15'" +
                    ");");
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|0|16|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)\n" +
                    "rg2|0|15|20.0%|0|0|0|null|100%|(weight=1.0, user=rg2_user)");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'exclusive_cpu_cores' = '17'" +
                    ")";
            Assertions.assertThrows(
                    DdlException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "the sum of exclusive_cpu_cores across all resource groups cannot exceed the minimum number of CPU cores " +
                            "available on the backends minus one [31]");
        }

        {
            String sql = "ALTER resource group rg1 \n" +
                    "WITH (\n" +
                    "   'exclusive_cpu_cores' = '14'" +
                    ")";
            starRocksAssert.executeResourceGroupDdlSql(sql);
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|0|14|20.0%|0|0|0|null|100%|(weight=1.0, user=rg1_user)\n" +
                    "rg2|0|15|20.0%|0|0|0|null|100%|(weight=1.0, user=rg2_user)");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg3\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '3'" +
                    ");";
            Assertions.assertThrows(
                    DdlException.class,
                    () -> starRocksAssert.executeResourceGroupDdlSql(sql),
                    "the sum of exclusive_cpu_cores across all resource groups cannot exceed the minimum number of CPU cores " +
                            "available on the backends minus one [31]");
        }

        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg2");
    }

    @Test
    public void testCreateBuiltinGroup() throws Exception {
        Assertions.assertThrows(DdlException.class, () -> starRocksAssert.executeResourceGroupDdlSql(
                        "CREATE RESOURCE GROUP default_wg WITH ( 'cpu_weight' = '14', 'mem_limit' = '0.1' )"), "RESOURCE_GROUP(default_wg) already exists");
        Assertions.assertThrows(DdlException.class, () -> starRocksAssert.executeResourceGroupDdlSql(
                        "CREATE RESOURCE GROUP default_mv_wg WITH ( 'cpu_weight' = '14', 'mem_limit' = '0.1' )"), "RESOURCE_GROUP(default_mv_wg) already exists");
    }

    @Test
    public void testDropBuiltinGroup() {
        Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP default_wg"), "cannot drop builtin resource group [default_wg]");
        Assertions.assertThrows(SemanticException.class, () -> starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP default_mv_wg"), "cannot drop builtin resource group [default_mv_wg]");
    }

    @Test
    public void testAlterClassifierBuiltinGroup() {
        Assertions.assertThrows(SemanticException.class,
                () -> starRocksAssert.executeResourceGroupDdlSql("ALTER RESOURCE GROUP default_wg ADD (user='rg1')"),
                "cannot alter classifiers of builtin resource group [default_wg]");
        Assertions.assertThrows(SemanticException.class,
                () -> starRocksAssert.executeResourceGroupDdlSql("ALTER RESOURCE GROUP default_mv_wg ADD (user='rg1')"),
                "cannot alter classifiers of builtin resource group [default_mv_wg]");
    }

    @Test
    public void testAlterPropertyBuiltinGroup() throws Exception {
        {
            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)");
        }

        {
            String sql1 = "ALTER resource group default_wg \n" +
                    "WITH ( 'cpu_weight' = '14' )";
            String sql2 = "ALTER resource group default_mv_wg \n" +
                    "WITH ( 'cpu_weight' = '12' )";
            starRocksAssert.executeResourceGroupDdlSql(sql1);
            starRocksAssert.executeResourceGroupDdlSql(sql2);

            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|12|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|14|0|100.0%|0|0|0|null|100%|(weight=0.0)");
        }

        {
            String sql1 = "ALTER resource group default_wg \n" +
                    "WITH ( 'cpu_weight' = '32' )";
            String sql2 = "ALTER resource group default_mv_wg \n" +
                    "WITH ( 'cpu_weight' = '1' )";
            starRocksAssert.executeResourceGroupDdlSql(sql1);
            starRocksAssert.executeResourceGroupDdlSql(sql2);

            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)");
        }
    }

    @Test
    public void testInvalidNumberFormat() {
        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = 'a'" +
                    ");";
            assertThatThrownBy(() -> starRocksAssert.executeResourceGroupDdlSql(sql))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining(
                            "The value type of the property `exclusive_cpu_cores` must be a valid numeric type, but it is set to `a`");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = ''" +
                    ");";
            assertThatThrownBy(() -> starRocksAssert.executeResourceGroupDdlSql(sql))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining(
                            "The value type of the property `exclusive_cpu_cores` must be a valid numeric type, but it is set to ``");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%%%'," +
                    "   'exclusive_cpu_cores' = '1'" +
                    ");";
            assertThatThrownBy(() -> starRocksAssert.executeResourceGroupDdlSql(sql))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining(
                            "The value type of the property `mem_limit` must be a valid numeric type, but it is set to `20%%%`");
        }
    }

    @Test
    public void testValidBigQueryCpuSecondLimit() throws Exception {
        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '1'," +
                    "   'big_query_cpu_second_limit' = '9223372037'" +
                    ");";
            assertThatThrownBy(() -> starRocksAssert.executeResourceGroupDdlSql(sql))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining(
                            "The range of `big_query_cpu_second_limit` should be (0, 9223372036]");
        }

        {
            String sql = "CREATE RESOURCE GROUP rg1\n" +
                    "TO (user='rg1_user')\n" +
                    "WITH (" +
                    "   'mem_limit' = '20%'," +
                    "   'exclusive_cpu_cores' = '1'," +
                    "   'big_query_cpu_second_limit' = '9223372036'" +
                    ");";
            starRocksAssert.executeResourceGroupDdlSql(sql);

            List<List<String>> rows = starRocksAssert.executeResourceGroupShowSql("show resource groups all");
            assertThat(rowsToString(rows)).isEqualTo("default_mv_wg|1|0|80.0%|0|0|0|null|80%|(weight=0.0)\n" +
                    "default_wg|32|0|100.0%|0|0|0|null|100%|(weight=0.0)\n" +
                    "rg1|0|1|20.0%|9223372036|0|0|null|100%|(weight=1.0, user=rg1_user)");
            starRocksAssert.executeResourceGroupDdlSql("DROP RESOURCE GROUP rg1");
        }
    }
}
