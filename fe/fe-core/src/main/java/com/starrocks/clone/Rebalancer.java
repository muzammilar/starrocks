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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/clone/Rebalancer.java

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

import com.google.common.collect.Lists;
import com.starrocks.clone.TabletScheduler.PathSlot;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.thrift.TStorageMedium;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Rebalancer is responsible for
 * 1. selectAlternativeTablets: selecting alternative tablets by one rebalance strategy,
 * and return them to tablet scheduler(maybe contains the concrete moves, or maybe not).
 * 2. createBalanceTask: given a tablet, try to create a clone task for this tablet.
 * 3. getToDeleteReplicaId: if the rebalance strategy wants to delete the specified replica,
 * override this func to let TabletScheduler know in handling redundant replica.
 * NOTICE:
 * 1. Adding the selected tablets by TabletScheduler may not succeed at all. And the move may be failed in some other places.
 * So the thing you need to know is, Rebalancer cannot know when the move is failed.
 * 2. If you want to make sure the move is succeed, you can assume that it's succeed when getToDeleteReplicaId called.
 */
public abstract class Rebalancer {
    // When Rebalancer init, the loadStatistic is usually empty. So it's no need to be an arg.
    // Only use updateLoadStatistic() to load stats.
    protected AtomicReference<ClusterLoadStatistic> loadStatistic = new AtomicReference<>(null);

    public List<TabletSchedCtx> selectAlternativeTablets() {
        List<TabletSchedCtx> alternativeTablets = Lists.newArrayList();
        ClusterLoadStatistic localLoadStat = getClusterLoadStatistic();
        if (localLoadStat != null) {
            for (TStorageMedium medium : TStorageMedium.values()) {
                alternativeTablets.addAll(selectAlternativeTabletsForCluster(localLoadStat, medium));
            }
        }
        return alternativeTablets;
    }

    // The return TabletSchedCtx should have the tablet id at least. {srcReplica, destBe} can be complete here or
    // later(when createBalanceTask called).
    protected abstract List<TabletSchedCtx> selectAlternativeTabletsForCluster(
            ClusterLoadStatistic clusterStat, TStorageMedium medium);

    public void createBalanceTask(TabletSchedCtx tabletCtx, Map<Long, PathSlot> backendsWorkingSlots,
                                  AgentBatchTask batchTask) throws SchedException {
        completeSchedCtx(tabletCtx, backendsWorkingSlots);
        batchTask.addTask(tabletCtx.createCloneReplicaAndTask());
    }

    // Before createCloneReplicaAndTask, we need to complete the TabletSchedCtx.
    // 1. If you generate {tabletId, srcReplica, destBe} in selectAlternativeTablets(), it may be invalid at
    // this point(it may have a long interval between selectAlternativeTablets & createBalanceTask).
    // You should check the moves' validation.
    // 2. If you want to generate {srcReplica, destBe} here, just do it.
    // 3. You should check the path slots of src & dest.
    protected abstract void completeSchedCtx(TabletSchedCtx tabletCtx, Map<Long, PathSlot> backendsWorkingSlots)
            throws SchedException;

    public Long getToDeleteReplicaId(Long tabletId) {
        return -1L;
    }

    public ClusterLoadStatistic getClusterLoadStatistic() {
        return loadStatistic.get();
    }

    public void updateLoadStatistic(ClusterLoadStatistic loadStatistic) {
        this.loadStatistic.set(loadStatistic);
    }
}
