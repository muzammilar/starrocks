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

package com.starrocks.clone;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.DataProperty;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DiskInfo;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.RandomDistributionInfo;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.clone.TabletSchedCtx.Priority;
import com.starrocks.clone.TabletSchedCtx.Type;
import com.starrocks.clone.TabletScheduler.PathSlot;
import com.starrocks.common.Config;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTask;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletSchedule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

public class TabletSchedCtxTest {
    private static long DB_ID = 1;
    private static int TB_ID = 2;
    private static int PART_ID = 3;
    private static int INDEX_ID = 4;
    private static int SCHEMA_HASH = 5;
    private static int PH_PART_ID = 6;

    private static String TB_NAME = "test";
    private static List<Column> TB_BASE_SCHEMA = Lists.newArrayList(new Column("k1", ScalarType
            .createType(PrimitiveType.TINYINT), true, null, "", "key1"));

    private static int TABLET_ID_1 = 50000;
    private static int TABLET_ID_2 = 51000;

    private Backend be1;
    private Backend be2;

    private TabletSchedulerStat stat = new TabletSchedulerStat();
    private ClusterLoadStatistic clusterLoadStatistic;
    private TabletScheduler tabletScheduler;

    @BeforeEach
    public void setUp() {
        // be1
        be1 = new Backend(10001, "192.168.0.1", 9051);
        Map<String, DiskInfo> disks = Maps.newHashMap();
        DiskInfo diskInfo1 = new DiskInfo("/path1");
        diskInfo1.setTotalCapacityB(1000000);
        diskInfo1.setAvailableCapacityB(500000);
        diskInfo1.setDataUsedCapacityB(480000);
        disks.put(diskInfo1.getRootPath(), diskInfo1);

        be1.setDisks(ImmutableMap.copyOf(disks));
        be1.setAlive(true);

        // be2
        be2 = new Backend(10002, "192.168.0.2", 9052);
        disks = Maps.newHashMap();
        diskInfo1 = new DiskInfo("/path1");
        diskInfo1.setTotalCapacityB(2000000);
        diskInfo1.setAvailableCapacityB(1900000);
        diskInfo1.setDataUsedCapacityB(480000);
        disks.put(diskInfo1.getRootPath(), diskInfo1);

        be2.setDisks(ImmutableMap.copyOf(disks));
        be2.setAlive(true);

        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().addBackend(be1);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().addBackend(be2);

        // tablet with single replica
        LocalTablet tablet = new LocalTablet(TABLET_ID_1);
        TabletMeta tabletMeta = new TabletMeta(DB_ID, TB_ID, PART_ID, INDEX_ID, TStorageMedium.HDD);
        GlobalStateMgr.getCurrentState().getTabletInvertedIndex().addTablet(TABLET_ID_1, tabletMeta);
        GlobalStateMgr.getCurrentState().getTabletInvertedIndex().
                addReplica(TABLET_ID_1, new Replica(50001, be1.getId(), 0, Replica.ReplicaState.NORMAL));

        // mock catalog
        MaterializedIndex baseIndex = new MaterializedIndex(TB_ID, MaterializedIndex.IndexState.NORMAL);
        DistributionInfo distributionInfo = new RandomDistributionInfo(32);
        Partition partition = new Partition(PART_ID, PH_PART_ID, TB_NAME, baseIndex, distributionInfo);
        baseIndex.addTablet(tablet, tabletMeta);
        PartitionInfo partitionInfo = new SinglePartitionInfo();
        partitionInfo.setReplicationNum(PART_ID, (short) 3);
        partitionInfo.setIsInMemory(PART_ID, false);
        DataProperty dataProperty = new DataProperty(TStorageMedium.HDD);
        partitionInfo.setDataProperty(PART_ID, dataProperty);
        OlapTable olapTable =
                new OlapTable(TB_ID, TB_NAME, TB_BASE_SCHEMA, KeysType.AGG_KEYS, new SinglePartitionInfo(),
                        new RandomDistributionInfo(32));
        olapTable.setIndexMeta(INDEX_ID, TB_NAME, TB_BASE_SCHEMA, 0, SCHEMA_HASH, (short) 1, TStorageType.COLUMN,
                KeysType.AGG_KEYS);
        olapTable.addPartition(partition);
        Database db = new Database(DB_ID, "");
        db.registerTableUnlocked(olapTable);
        GlobalStateMgr.getCurrentState().getLocalMetastore().getIdToDb().put(DB_ID, db);

        // prepare clusterLoadStatistic
        clusterLoadStatistic = new ClusterLoadStatistic(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                GlobalStateMgr.getCurrentState().getTabletInvertedIndex());
        clusterLoadStatistic.init();

        // mock tabletScheduler
        tabletScheduler = new TabletScheduler(stat);
        tabletScheduler.setClusterLoadStatistic(clusterLoadStatistic);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackends().forEach(be -> {
            List<Long> pathHashes =
                    be.getDisks().values().stream().map(DiskInfo::getPathHash).collect(Collectors.toList());
            TabletScheduler.PathSlot slot = new TabletScheduler.PathSlot(pathHashes, Config.tablet_sched_slot_num_per_path);
            tabletScheduler.getBackendsWorkingSlots().put(be.getId(), slot);
        });
    }

    @Test
    public void testSingleReplicaRecover() throws SchedException {
        // drop be1 and TABLET_ID_1 missing
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().dropBackend(be1);
        clusterLoadStatistic = new ClusterLoadStatistic(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                GlobalStateMgr.getCurrentState().getTabletInvertedIndex());
        clusterLoadStatistic.init();
        tabletScheduler.setClusterLoadStatistic(clusterLoadStatistic);

        LocalTablet missedTablet = new LocalTablet(TABLET_ID_1,
                GlobalStateMgr.getCurrentState().getTabletInvertedIndex().getReplicasByTabletId(TABLET_ID_1));
        TabletSchedCtx ctx = new TabletSchedCtx(Type.REPAIR, DB_ID, TB_ID, PH_PART_ID, INDEX_ID,
                TABLET_ID_1, System.currentTimeMillis(), GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo());
        ctx.setTablet(missedTablet);
        ctx.setStorageMedium(TStorageMedium.HDD);

        AgentBatchTask agentBatchTask = new AgentBatchTask();
        Config.recover_with_empty_tablet = false;
        SchedException schedException = Assertions.assertThrows(SchedException.class, () -> tabletScheduler
                .handleTabletByTypeAndStatus(LocalTablet.TabletHealthStatus.REPLICA_MISSING, ctx, agentBatchTask));
        Assertions.assertEquals("unable to find source replica", schedException.getMessage());

        Config.recover_with_empty_tablet = true;
        tabletScheduler.handleTabletByTypeAndStatus(LocalTablet.TabletHealthStatus.REPLICA_MISSING, ctx, agentBatchTask);
        Assertions.assertEquals(1, agentBatchTask.getTaskNum());

        AgentTask recoverTask = agentBatchTask.getAllTasks().get(0);
        Assertions.assertEquals(be2.getId(), recoverTask.getBackendId());
        Assertions.assertEquals(TABLET_ID_1, recoverTask.getTabletId());

        TTabletSchedule res = ctx.toTabletScheduleThrift();
        Assertions.assertNotNull(res);
        Assertions.assertEquals(TABLET_ID_1, res.getTablet_id());
    }

    @Test
    public void testPriorityCompare() {
        // equal priority, but info3's last visit time is earlier than info2 and info1, so info1 should ranks ahead
        PriorityQueue<TabletSchedCtx> pendingTablets = new PriorityQueue<>();
        TabletSchedCtx ctx1 = new TabletSchedCtx(Type.REPAIR,
                1, 2, 3, 4, 1000, System.currentTimeMillis());
        ctx1.setOrigPriority(Priority.NORMAL);
        ctx1.setLastVisitedTime(2);

        TabletSchedCtx ctx2 = new TabletSchedCtx(Type.REPAIR,
                1, 2, 3, 4, 1001, System.currentTimeMillis());
        ctx2.setOrigPriority(Priority.NORMAL);
        ctx2.setLastVisitedTime(3);

        TabletSchedCtx ctx3 = new TabletSchedCtx(Type.REPAIR,
                1, 2, 3, 4, 1001, System.currentTimeMillis());
        ctx3.setOrigPriority(Priority.NORMAL);
        ctx3.setLastVisitedTime(1);

        pendingTablets.add(ctx1);
        pendingTablets.add(ctx2);
        pendingTablets.add(ctx3);

        TabletSchedCtx expectedCtx = pendingTablets.poll();
        Assertions.assertNotNull(expectedCtx);
        Assertions.assertEquals(ctx3.getTabletId(), expectedCtx.getTabletId());

        // priority is not equal, info2 is HIGH, should ranks ahead
        pendingTablets.clear();
        ctx1.setOrigPriority(Priority.NORMAL);
        ctx2.setOrigPriority(Priority.HIGH);
        ctx1.setLastVisitedTime(2);
        ctx2.setLastVisitedTime(2);
        pendingTablets.add(ctx2);
        pendingTablets.add(ctx1);
        expectedCtx = pendingTablets.poll();
        Assertions.assertNotNull(expectedCtx);
        Assertions.assertEquals(ctx2.getTabletId(), expectedCtx.getTabletId());

        // add info2 back to priority queue, and it should ranks ahead still.
        pendingTablets.add(ctx2);
        expectedCtx = pendingTablets.poll();
        Assertions.assertNotNull(expectedCtx);
        Assertions.assertEquals(ctx2.getTabletId(), expectedCtx.getTabletId());
    }

    @Test
    public void testChooseDestReplicaForVersionIncomplete() {
        TabletMeta tabletMeta = new TabletMeta(DB_ID, TB_ID, PART_ID, INDEX_ID, TStorageMedium.HDD);
        GlobalStateMgr.getCurrentState().getTabletInvertedIndex().addTablet(TABLET_ID_2, tabletMeta);
        Replica replica1 = new Replica(50011, be1.getId(), 0, Replica.ReplicaState.NORMAL);
        Replica replica2 = new Replica(50012, be2.getId(), 0, Replica.ReplicaState.NORMAL);
        GlobalStateMgr.getCurrentState().getTabletInvertedIndex().addReplica(TABLET_ID_2, replica1);
        GlobalStateMgr.getCurrentState().getTabletInvertedIndex().addReplica(TABLET_ID_2, replica2);

        replica1.updateVersionInfo(101, 108, 101);
        replica2.updateVersionInfo(100, 120, 100);
        replica1.setPathHash(Long.valueOf(100));
        replica2.setPathHash(Long.valueOf(101));

        List<Replica> replicas = new ArrayList<>();
        replicas.add(replica1);
        replicas.add(replica2);
        LocalTablet repairTablet = new LocalTablet(TABLET_ID_2, replicas);

        TabletSchedCtx ctx =
                new TabletSchedCtx(Type.REPAIR, DB_ID, TB_ID, PART_ID, INDEX_ID,
                        TABLET_ID_2, System.currentTimeMillis(), GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo());
        ctx.setTablet(repairTablet);
        ctx.setStorageMedium(TStorageMedium.HDD);
        ctx.setVersionInfo(101, 108, 12332, 12333);

        Map<Long, PathSlot> backendsWorkingSlots = Maps.newConcurrentMap();
        List<Long> pathHashes = new ArrayList<>();
        pathHashes.add(Long.valueOf(100));
        PathSlot slot1 = new PathSlot(pathHashes, 1000);
        backendsWorkingSlots.put(be1.getId(), slot1);
        pathHashes.clear();
        pathHashes.add(Long.valueOf(101));
        PathSlot slot2 = new PathSlot(pathHashes, 1000);
        backendsWorkingSlots.put(be2.getId(), slot2);
        try {
            ctx.chooseDestReplicaForVersionIncomplete(backendsWorkingSlots);
        } catch (Exception e) {
            Assertions.assertTrue(false);
        }
        Assertions.assertEquals(be2.getId(), ctx.getDestBackendId());

        replica2.updateVersionInfo(101, 120, 101);
        try {
            ctx.chooseDestReplicaForVersionIncomplete(backendsWorkingSlots);
        } catch (Exception e) {
            Assertions.assertTrue(false);
        }
        Assertions.assertEquals(be1.getId(), ctx.getDestBackendId());
    }

    @Test
    public void testGetBrief() {
        TabletSchedCtx ctx = new TabletSchedCtx(Type.REPAIR, 1, 2, 3, 4, 1000, System.currentTimeMillis());
        ctx.setOrigPriority(Priority.NORMAL);
        ctx.setTabletStatus(LocalTablet.TabletHealthStatus.VERSION_INCOMPLETE);
        List<String> results = ctx.getBrief();
        Assertions.assertEquals(25, results.size());
        Assertions.assertEquals("1000", results.get(0));
        Assertions.assertEquals("REPAIR", results.get(1));
        Assertions.assertEquals("VERSION_INCOMPLETE", results.get(3));

        ctx = new TabletSchedCtx(Type.BALANCE, 1, 2, 3, 4, 1001, System.currentTimeMillis());
        ctx.setOrigPriority(Priority.NORMAL);
        ctx.setBalanceType(BalanceStat.BalanceType.CLUSTER_TABLET);
        results = ctx.getBrief();
        Assertions.assertEquals("1001", results.get(0));
        Assertions.assertEquals("BALANCE", results.get(1));
        Assertions.assertEquals("CLUSTER_TABLET", results.get(3));
    }
}
