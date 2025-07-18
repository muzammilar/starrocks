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

package com.starrocks.qe.scheduler.slot;

import com.google.api.client.util.Lists;
import com.google.common.collect.ImmutableList;
import com.starrocks.common.Config;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.metric.MetricRepo;
import com.starrocks.qe.GlobalVariable;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.BackendResourceStat;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class SlotSelectionStrategyV2Test {
    private static final int NUM_CORES = 16;

    private boolean prevEnableQueryQueueV2 = false;

    @BeforeAll
    public static void beforeClass() {
        MetricRepo.init();
    }

    @BeforeEach
    public void before() {
        prevEnableQueryQueueV2 = Config.enable_query_queue_v2;
        Config.enable_query_queue_v2 = true;

        BackendResourceStat.getInstance().setNumHardwareCoresOfBe(1, NUM_CORES);
    }

    @AfterEach
    public void after() {
        Config.enable_query_queue_v2 = prevEnableQueryQueueV2;

        BackendResourceStat.getInstance().reset();
    }

    private SlotSelectionStrategyV2 createSlotSelectionStrategy() {
        return new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
    }

    @Test
    public void testSmallSlot() {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot largeSlot = generateSlot(opts.v2().getTotalSlots() - 1);
        List<LogicalSlot> smallSlots = IntStream.range(0, NUM_CORES + 2)
                .mapToObj(i -> generateSlot(1))
                .collect(Collectors.toList());

        // 1. Require and allocate the large slot with `totalSlots - 1` slots.
        slotTracker.requireSlot(largeSlot);
        List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).containsExactly(largeSlot);
        slotTracker.allocateSlot(largeSlot);

        // 2. Require `NUM_CORES + 2` small slots.
        for (LogicalSlot smallSlot : smallSlots) {
            slotTracker.requireSlot(smallSlot);
        }

        // 3. Could peak and allocate `NUM_CORES + 1` small slots:
        // - `NUM_CORES` small slot are allocated as small slots.
        // - 1 small slot is allocated as a non-small slot.
        List<LogicalSlot> peakSmallSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSmallSlots).hasSize(NUM_CORES + 1);
        for (LogicalSlot peakSmallSlot : peakSmallSlots) {
            slotTracker.allocateSlot(peakSmallSlot);
        }

        // 4. Release the large slot and then the rest one small slot could be peaked.
        peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).isEmpty();

        slotTracker.releaseSlot(largeSlot.getSlotId());
        peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).hasSize(1);
        slotTracker.allocateSlot(peakSlots.get(0));
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(smallSlots.size());

        // 5. Require `10+numAvailableSlots` small slots.
        final int numAvailableSlots = opts.v2().getTotalSlots() + opts.v2().getTotalSmallSlots() - smallSlots.size();
        List<LogicalSlot> smallSlots2 =
                IntStream.range(0, 10 + numAvailableSlots)
                        .mapToObj(i -> generateSlot(1))
                        .collect(Collectors.toList());
        smallSlots2.forEach(slotTracker::requireSlot);

        // 6. Could peak and allocate `numAvailableSlots` small slots.
        peakSmallSlots = strategy.peakSlotsToAllocate(slotTracker);
        peakSmallSlots.forEach(slotTracker::allocateSlot);
        assertThat(peakSmallSlots).hasSize(numAvailableSlots);
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(opts.v2().getTotalSlots() + opts.v2().getTotalSmallSlots());

        // 7. Release 10 small slots and then the rest small slots could be peaked and allocated.
        smallSlots.stream().limit(10).forEach(slot -> slotTracker.releaseSlot(slot.getSlotId()));
        peakSmallSlots = strategy.peakSlotsToAllocate(slotTracker);
        peakSmallSlots.forEach(slotTracker::allocateSlot);
        assertThat(peakSmallSlots).hasSize(10);
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(opts.v2().getTotalSlots() + opts.v2().getTotalSmallSlots());
    }

    @Test
    public void testHeadLineBlocking1() {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2);
        LogicalSlot slot3 = generateSlot(2);

        // 1. Require and allocate slot1.
        slotTracker.requireSlot(slot1);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        slotTracker.allocateSlot(slot1);

        // 2. Require slot2.
        slotTracker.requireSlot(slot2);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 3. Require enough small slots to make its priority lower.
        {
            List<LogicalSlot> smallSlots = IntStream.range(0, 10)
                    .mapToObj(i -> generateSlot(2))
                    .collect(Collectors.toList());
            smallSlots.forEach(slotTracker::requireSlot);
            for (int numPeakedSmallSlots = 0; numPeakedSmallSlots < 10; ) {
                List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
                numPeakedSmallSlots += peakSlots.size();
                peakSlots.forEach(slotTracker::allocateSlot);
                peakSlots.forEach(slot -> assertThat(slotTracker.releaseSlot(slot.getSlotId())).isSameAs(slot));
            }
        }

        // Try peak the only rest slot2, but it is blocked by slot1.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 4. slot3 cannot be peaked because it is blocked by slot2.
        slotTracker.requireSlot(slot3);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 5. slot3 can be peaked after releasing the pending slot2.
        assertThat(slotTracker.releaseSlot(slot2.getSlotId())).isSameAs(slot2);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot3);
        slotTracker.allocateSlot(slot3);
        assertThat(slotTracker.releaseSlot(slot3.getSlotId())).isSameAs(slot3);
    }

    @Test
    public void testHeadLineBlocking2() {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2);
        LogicalSlot slot3 = generateSlot(2);

        // 1. Require and allocate slot1.
        slotTracker.requireSlot(slot1);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        slotTracker.allocateSlot(slot1);

        // 2. Require slot2.
        slotTracker.requireSlot(slot2);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 3. Require enough small slots to make its priority lower.
        {
            List<LogicalSlot> smallSlots = IntStream.range(0, 10)
                    .mapToObj(i -> generateSlot(2))
                    .collect(Collectors.toList());
            smallSlots.forEach(slotTracker::requireSlot);
            for (int numPeakedSmallSlots = 0; numPeakedSmallSlots < 10; ) {
                List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
                numPeakedSmallSlots += peakSlots.size();
                peakSlots.forEach(slotTracker::allocateSlot);
                peakSlots.forEach(slot -> assertThat(slotTracker.releaseSlot(slot.getSlotId())).isSameAs(slot));
            }
        }

        // Try peak the only rest slot2, but it is blocked by slot1.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 4. slot3 cannot be peaked because it is blocked by slot2.
        for (int i = 0; i < 10; i++) {
            slotTracker.requireSlot(slot3);
            // if current concurrency is zero, always peak one
            assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();
            assertThat(slotTracker.releaseSlot(slot3.getSlotId())).isSameAs(slot3);
        }
        slotTracker.requireSlot(slot3);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 5. slot2 and slot3 can be peaked after releasing slot1.
        slotTracker.releaseSlot(slot1.getSlotId());
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot2, slot3);
    }

    @Test
    public void testUpdateOptionsPeriodicallyAtAllocating() throws InterruptedException {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2 - 1);
        LogicalSlot slot3 = generateSlot(2);

        // 1. Require slot1, slot2, slot3.
        assertThat(slotTracker.requireSlot(slot1)).isTrue();
        assertThat(slotTracker.requireSlot(slot2)).isTrue();
        assertThat(slotTracker.requireSlot(slot3)).isTrue();

        // 2. Peak slot2 and slot3.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot3, slot2);

        // Make options changed.
        BackendResourceStat.getInstance().setNumHardwareCoresOfBe(1, NUM_CORES * 2);
        Thread.sleep(1200);
        // 3. Allocate slot2 and slot3.
        slotTracker.allocateSlot(slot2);
        slotTracker.allocateSlot(slot3);
        assertThat(slot2.getState()).isEqualTo(LogicalSlot.State.ALLOCATED);
        assertThat(slot3.getState()).isEqualTo(LogicalSlot.State.ALLOCATED);
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(slot2.getNumPhysicalSlots() + slot3.getNumPhysicalSlots());

        // 4. Peak slot1.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        slotTracker.allocateSlot(slot1);
        assertThat(slot1.getState()).isEqualTo(LogicalSlot.State.ALLOCATED);
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(
                slot2.getNumPhysicalSlots() + slot3.getNumPhysicalSlots() + slot1.getNumPhysicalSlots());
    }

    @Test
    public void testUpdateOptionsPeriodicallyAtReleasing() throws InterruptedException {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2 - 1);
        LogicalSlot slot3 = generateSlot(2);

        // 1. Require slot1, slot2, slot3.
        assertThat(slotTracker.requireSlot(slot1)).isTrue();
        assertThat(slotTracker.requireSlot(slot2)).isTrue();
        assertThat(slotTracker.requireSlot(slot3)).isTrue();

        // 2. Peak, allocate slot2 and slot3.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot3, slot2);
        slotTracker.allocateSlot(slot2);
        slotTracker.allocateSlot(slot3);
        assertThat(slot2.getState()).isEqualTo(LogicalSlot.State.ALLOCATED);
        assertThat(slot3.getState()).isEqualTo(LogicalSlot.State.ALLOCATED);
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(slot2.getNumPhysicalSlots() + slot3.getNumPhysicalSlots());

        // Make options changed.
        BackendResourceStat.getInstance().setNumHardwareCoresOfBe(1, NUM_CORES * 2);
        Thread.sleep(1200);
        // 3. Release slot2 and slot3.
        assertThat(slotTracker.releaseSlot(slot2.getSlotId())).isSameAs(slot2);
        assertThat(slotTracker.releaseSlot(slot3.getSlotId())).isSameAs(slot3);

        // 4. Peak slot1.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        slotTracker.allocateSlot(slot1);
        assertThat(slot1.getState()).isEqualTo(LogicalSlot.State.ALLOCATED);
        assertThat(slotTracker.getNumAllocatedSlots()).isEqualTo(slot1.getNumPhysicalSlots());
    }

    @Test
    public void testUpdateOptionsChangeQueueStrategyOnline() throws InterruptedException {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2 - 1);
        LogicalSlot slot3 = generateSlot(2);

        // 1. Require slot1, slot2, slot3.
        assertThat(slotTracker.requireSlot(slot1)).isTrue();
        assertThat(slotTracker.requireSlot(slot2)).isTrue();
        assertThat(slotTracker.requireSlot(slot3)).isTrue();
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot3, slot2);

        // update query queue strategy
        Config.query_queue_v2_schedule_strategy = QueryQueueOptions.SchedulePolicy.SJF.name();
        Thread.sleep(1200);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot3, slot2);
        assertThat(strategy.getCurrentOptions()).isNotEqualTo(opts);
        assertThat(strategy.getCurrentOptions().hashCode()).isNotEqualTo(opts.hashCode());
        assertThat(strategy.getCurrentOptions().getPolicy()).isEqualTo(QueryQueueOptions.SchedulePolicy.SJF);
        opts = strategy.getCurrentOptions();
        slotTracker.allocateSlot(slot2);
        slotTracker.allocateSlot(slot3);
        assertThat(slotTracker.releaseSlot(slot2.getSlotId())).isSameAs(slot2);
        assertThat(slotTracker.releaseSlot(slot3.getSlotId())).isSameAs(slot3);

        // change it back
        Config.query_queue_v2_schedule_strategy = QueryQueueOptions.SchedulePolicy.SWRR.name();
        Thread.sleep(1200);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        assertThat(strategy.getCurrentOptions()).isNotEqualTo(opts);
        assertThat(strategy.getCurrentOptions().hashCode()).isNotEqualTo(opts.hashCode());
        assertThat(strategy.getCurrentOptions().getPolicy()).isEqualTo(QueryQueueOptions.SchedulePolicy.SWRR);
    }

    private static LogicalSlot generateSlot(int numSlots) {
        return new LogicalSlot(UUIDUtil.genTUniqueId(), "fe", WarehouseManager.DEFAULT_WAREHOUSE_ID,
                LogicalSlot.ABSENT_GROUP_ID, numSlots, 0, 0, 0, 0, 0);
    }

    @Test
    public void testHistorySlotsQueue() {
        Config.max_query_queue_history_slots_number = 10;
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2);
        LogicalSlot slot3 = generateSlot(2);

        assertThat(slotTracker.getSlots().isEmpty());
        assertThat(slotTracker.getHistorySlots().isEmpty());

        // 1. Require and allocate slot1.
        slotTracker.requireSlot(slot1);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        slotTracker.allocateSlot(slot1);

        // 2. Require slot2.
        slotTracker.requireSlot(slot2);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 3. Require enough small slots to make its priority lower.
        {
            List<LogicalSlot> smallSlots = IntStream.range(0, 10)
                    .mapToObj(i -> generateSlot(2))
                    .collect(Collectors.toList());
            smallSlots.forEach(slotTracker::requireSlot);
            for (int numPeakedSmallSlots = 0; numPeakedSmallSlots < 10; ) {
                List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
                numPeakedSmallSlots += peakSlots.size();
                peakSlots.forEach(slotTracker::allocateSlot);
                peakSlots.forEach(slot -> assertThat(slotTracker.releaseSlot(slot.getSlotId())).isSameAs(slot));
            }
        }
        assertThat(!slotTracker.getHistorySlots().isEmpty());
        List<LogicalSlot> historySlots = Lists.newArrayList(slotTracker.getHistorySlots());
        assertThat(historySlots.size() == 10);
        assertThat(historySlots.stream().allMatch(slot -> slot.getExtraMessage().isEmpty()));

        Collection<LogicalSlot> slots = slotTracker.getSlots();
        assertThat(slots.size() == historySlots.size() + slotTracker.getSlots().size());

        // Try peak the only rest slot2, but it is blocked by slot1.
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 4. slot3 cannot be peaked because it is blocked by slot2.
        slotTracker.requireSlot(slot3);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 5. slot3 can be peaked after releasing the pending slot2.
        assertThat(slotTracker.releaseSlot(slot2.getSlotId())).isSameAs(slot2);
        assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot3);
        slotTracker.allocateSlot(slot3);
        assertThat(slotTracker.releaseSlot(slot3.getSlotId())).isSameAs(slot3);

        historySlots = Lists.newArrayList(slotTracker.getHistorySlots());
        assertThat(historySlots.size() == 10);
        slots = slotTracker.getSlots();
        assertThat(slots.size() == historySlots.size() + slotTracker.getSlots().size());

        Config.max_query_queue_history_slots_number = 0;
    }

    @Test
    public void testConcurrencyLimit1() {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        int oldVal = GlobalVariable.getQueryQueueConcurrencyLimit();
        GlobalVariable.setQueryQueueConcurrencyLimit(10);

        LogicalSlot slot1 = generateSlot(opts.v2().getTotalSlots() / 2 + 1);
        LogicalSlot slot2 = generateSlot(opts.v2().getTotalSlots() / 2);
        LogicalSlot slot3 = generateSlot(2);

        // 1. Require and allocate slot1.
        slotTracker.requireSlot(slot1);
        Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot1);
        slotTracker.allocateSlot(slot1);

        // 2. Require slot2.
        slotTracker.requireSlot(slot2);
        Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 3. Require enough small slots to make its priority lower.
        Assertions.assertThat(slotTracker.getCurrentCurrency()).isEqualTo(1);
        {
            List<LogicalSlot> smallSlots = IntStream.range(0, 10)
                    .mapToObj(i -> generateSlot(2))
                    .collect(Collectors.toList());
            smallSlots.forEach(slotTracker::requireSlot);

            int concurrency = slotTracker.getCurrentCurrency();
            int numPeakedSmallSlots = 0;
            List<LogicalSlot> runningSmallSlots = com.google.common.collect.Lists.newArrayList();
            while (concurrency < 9) {
                List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
                Assertions.assertThat(peakSlots.isEmpty()).isFalse();
                numPeakedSmallSlots += peakSlots.size();
                peakSlots.forEach(slotTracker::allocateSlot);
                concurrency = slotTracker.getCurrentCurrency();
                runningSmallSlots.addAll(peakSlots);
            }
            Assertions.assertThat(numPeakedSmallSlots == 10);
            // since concurrency is 10, all small slots are blocked.
            Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();
            // release all running slots
            runningSmallSlots.forEach(slot -> Assertions.assertThat(slotTracker.releaseSlot(slot.getSlotId())).isSameAs(slot));
        }
        Assertions.assertThat(slotTracker.getCurrentCurrency()).isEqualTo(1);

        // Try peak the only rest slot2, but it is blocked by slot1.
        Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 4. slot3 cannot be peaked because it is blocked by slot2.
        for (int i = 0; i < 10; i++) {
            slotTracker.requireSlot(slot3);
            // if current concurrency is zero, always peak one
            Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();
            Assertions.assertThat(slotTracker.releaseSlot(slot3.getSlotId())).isSameAs(slot3);
        }
        slotTracker.requireSlot(slot3);
        Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).isEmpty();

        // 5. slot2 and slot3 can be peaked after releasing slot1.
        slotTracker.releaseSlot(slot1.getSlotId());
        Assertions.assertThat(strategy.peakSlotsToAllocate(slotTracker)).containsExactly(slot2, slot3);

        // reset concurrency limit
        GlobalVariable.setQueryQueueConcurrencyLimit(oldVal);
    }

    @Test
    public void testConcurrencyLimitWithLargeSlots() {
        QueryQueueOptions opts = QueryQueueOptions.createFromEnv(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        GlobalVariable.setQueryQueueConcurrencyLimit(1);
        LogicalSlot largeSlot1 = generateSlot(opts.v2().getTotalSlots() - 1);
        LogicalSlot largeSlot2 = generateSlot(opts.v2().getTotalSlots() - 1);

        slotTracker.requireSlot(largeSlot1);
        List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).containsExactly(largeSlot1);
        slotTracker.allocateSlot(largeSlot1);

        slotTracker.requireSlot(largeSlot2);
        peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).isEmpty();

        slotTracker.releaseSlot(largeSlot1.getSlotId());

        peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).hasSize(1);
        assertThat(peakSlots).containsExactly(largeSlot2);
        slotTracker.releaseSlot(largeSlot2.getSlotId());
        GlobalVariable.setQueryQueueConcurrencyLimit(-1);
    }

    @Test
    public void testConcurrencyLimitWithSmallSlots() {
        SlotSelectionStrategyV2 strategy = new SlotSelectionStrategyV2(WarehouseManager.DEFAULT_WAREHOUSE_ID);
        SlotTracker slotTracker = new SlotTracker(ImmutableList.of(strategy));

        GlobalVariable.setQueryQueueConcurrencyLimit(1);
        LogicalSlot smallSlot1 = generateSlot(1);
        LogicalSlot smallSlot2 = generateSlot(1);

        slotTracker.requireSlot(smallSlot1);
        List<LogicalSlot> peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).containsExactly(smallSlot1);
        slotTracker.allocateSlot(smallSlot1);

        slotTracker.requireSlot(smallSlot2);
        peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).isEmpty();

        slotTracker.releaseSlot(smallSlot1.getSlotId());

        peakSlots = strategy.peakSlotsToAllocate(slotTracker);
        assertThat(peakSlots).hasSize(1);
        assertThat(peakSlots).containsExactly(smallSlot2);
        slotTracker.releaseSlot(smallSlot2.getSlotId());
        GlobalVariable.setQueryQueueConcurrencyLimit(-1);
    }
}
