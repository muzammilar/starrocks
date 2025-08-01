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

package com.starrocks.common.lock;

import com.google.common.collect.Lists;
import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.common.util.concurrent.lock.LockManager;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.server.GlobalStateMgr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;

import static com.starrocks.common.lock.LockTestUtils.assertDeadLock;
import static com.starrocks.common.lock.LockTestUtils.assertLockSuccess;
import static com.starrocks.common.lock.LockTestUtils.assertLockWait;

public class DeadLockTest {
    @BeforeEach
    public void setUp() {
        GlobalStateMgr.getCurrentState().setLockManager(new LockManager());
        Config.slow_lock_threshold_ms = 0;
        Config.lock_manager_enable_resolve_deadlock = true;
    }

    @AfterEach
    public void tearDown() {
        Config.slow_lock_threshold_ms = 3000;
        Config.lock_manager_enable_resolve_deadlock = false;
    }

    /**
     * DeadLock:
     * |     locker1                |    locker2               |
     * |-------------------------------------------------------|
     * |   acquire S lock on 1      |                          |
     * |                            |   acquire S lock on 1    |
     * |   request X lock on 1      |                          |
     * |                            |   request X lock on 1    |
     * |-------------------------------------------------------|
     */

    @Test
    public void test1() {
        long rid = 1L;
        TestLocker testLocker1 = new TestLocker();
        Future<LockResult> testLockerFuture1 = testLocker1.lock(rid, LockType.READ);
        assertLockSuccess(testLockerFuture1);

        TestLocker testLocker2 = new TestLocker();
        Future<LockResult> testLockerFuture2 = testLocker2.lock(rid, LockType.READ);
        assertLockSuccess(testLockerFuture2);

        Future<LockResult> testLockerFuture3 = testLocker1.lock(rid, LockType.WRITE);
        assertLockWait(testLockerFuture3);

        Future<LockResult> testLockerFuture4 = testLocker2.lock(rid, LockType.WRITE);

        assertDeadLock(Lists.newArrayList(testLocker1, testLocker2),
                Lists.newArrayList(new Pair<>(rid, LockType.READ), new Pair<>(rid, LockType.READ)),
                Lists.newArrayList(testLockerFuture3, testLockerFuture4));
    }

    /**
     * DeadLock:
     * |     locker1                |    locker2               |
     * |-------------------------------------------------------|
     * |   acquire X lock on 1      |                          |
     * |                            |   acquire X lock on 2    |
     * |   request S lock on 2      |                          |
     * |                            |   request S lock on 1    |
     * |-------------------------------------------------------|
     */

    @Test
    public void test2() throws InterruptedException {
        long rid1 = 1L;
        TestLocker testLocker1 = new TestLocker();
        Future<LockResult> testLockerFuture1 = testLocker1.lock(rid1, LockType.WRITE);
        assertLockSuccess(testLockerFuture1);

        long rid2 = 2L;
        TestLocker testLocker2 = new TestLocker();
        Future<LockResult> testLockerFuture2 = testLocker2.lock(rid2, LockType.WRITE);
        assertLockSuccess(testLockerFuture2);

        Future<LockResult> testLockerFuture3 = testLocker1.lock(rid2, LockType.READ);
        assertLockWait(testLockerFuture3);

        Future<LockResult> testLockerFuture4 = testLocker2.lock(rid1, LockType.READ);

        assertDeadLock(Lists.newArrayList(testLocker1, testLocker2),
                Lists.newArrayList(new Pair<>(rid1, LockType.WRITE), new Pair<>(rid2, LockType.WRITE)),
                Lists.newArrayList(testLockerFuture3, testLockerFuture4));
        LockManager lockManager = GlobalStateMgr.getCurrentState().getLockManager();
        System.out.println(lockManager.dumpLockManager());
    }

    /**
     * DeadLock:
     * |     locker1                |    locker2               |
     * |-------------------------------------------------------|
     * |   acquire S lock on 1      |                          |
     * |                            |   acquire S lock on 2    |
     * |   request X lock on 2      |                          |
     * |                            |   request X lock on 1    |
     * |-------------------------------------------------------|
     */

    @Test
    public void test3() {
        long rid1 = 1L;
        TestLocker testLocker1 = new TestLocker();
        Future<LockResult> testLockerFuture1 = testLocker1.lock(rid1, LockType.READ);
        assertLockSuccess(testLockerFuture1);

        long rid2 = 2L;
        TestLocker testLocker2 = new TestLocker();
        Future<LockResult> testLockerFuture2 = testLocker2.lock(rid2, LockType.READ);
        assertLockSuccess(testLockerFuture2);

        Future<LockResult> testLockerFuture3 = testLocker1.lock(rid2, LockType.WRITE);
        assertLockWait(testLockerFuture3);

        Future<LockResult> testLockerFuture4 = testLocker2.lock(rid1, LockType.WRITE);

        assertDeadLock(Lists.newArrayList(testLocker1, testLocker2),
                Lists.newArrayList(new Pair<>(rid1, LockType.READ), new Pair<>(rid2, LockType.READ)),
                Lists.newArrayList(testLockerFuture3, testLockerFuture4));
        LockManager lockManager = GlobalStateMgr.getCurrentState().getLockManager();
        System.out.println(lockManager.dumpLockManager());
    }


    /**
     * |----------------------------|--------------------------|--------------------------|
     * |           locker1          |         locker2          |         locker3          |
     * |-------------------------------------------------------|--------------------------|
     * |   acquire X lock on 1      |                          |                          |
     * |                            |   acquire X lock on 2    |                          |
     * |                            |                          |   acquire X lock on 3    |
     * |   acquire S lock on 2      |                          |                          |
     * |                            |   acquire S lock on 3    |                          |
     * |                            |                          |   acquire S lock on 1    |
     * |-------------------------------------------------------|--------------------------|
     */

    @Test
    public void test4() {
        long rid1 = 1L;
        long rid2 = 2L;
        long rid3 = 3L;

        TestLocker testLocker1 = new TestLocker();
        TestLocker testLocker2 = new TestLocker();
        TestLocker testLocker3 = new TestLocker();

        assertLockSuccess(testLocker1.lock(rid1, LockType.WRITE));
        assertLockSuccess(testLocker2.lock(rid2, LockType.WRITE));
        assertLockSuccess(testLocker3.lock(rid3, LockType.WRITE));

        Future<LockResult> f1 = testLocker1.lock(rid2, LockType.READ);
        assertLockWait(f1);
        Future<LockResult> f2 = testLocker2.lock(rid3, LockType.READ);
        assertLockWait(f2);
        Future<LockResult> f3 = testLocker3.lock(rid1, LockType.READ);

        assertDeadLock(Lists.newArrayList(testLocker1, testLocker2, testLocker3),
                Lists.newArrayList(new Pair<>(rid1, LockType.WRITE),
                        new Pair<>(rid2, LockType.WRITE),
                        new Pair<>(rid3, LockType.WRITE)),
                Lists.newArrayList(f1, f2, f3));
    }

    /**
     * Same as test4, Test when the  deadlock is not unlocked, a new lock request occurs
     * and the deadlock subgraph can be detected
     */

    @Test
    public void test5() {
        long rid1 = 1L;
        long rid2 = 2L;
        long rid3 = 3L;

        TestLocker testLocker1 = new TestLocker();
        TestLocker testLocker2 = new TestLocker();
        TestLocker testLocker3 = new TestLocker();

        assertLockSuccess(testLocker1.lock(rid1, LockType.WRITE));
        assertLockSuccess(testLocker2.lock(rid2, LockType.WRITE));
        assertLockSuccess(testLocker3.lock(rid3, LockType.WRITE));

        Config.lock_manager_enable_resolve_deadlock = false;
        Future<LockResult> f1 = testLocker1.lock(rid2, LockType.READ);
        assertLockWait(f1);
        Future<LockResult> f2 = testLocker2.lock(rid3, LockType.READ);
        assertLockWait(f2);
        Future<LockResult> f3 = testLocker3.lock(rid1, LockType.READ);

        Config.lock_manager_enable_resolve_deadlock = true;
        TestLocker testLocker4 = new TestLocker();
        testLocker4.lock(rid1, LockType.READ);
        testLocker4.release(rid1, LockType.READ);

        assertDeadLock(Lists.newArrayList(testLocker1, testLocker2, testLocker3),
                Lists.newArrayList(new Pair<>(rid1, LockType.WRITE),
                        new Pair<>(rid2, LockType.WRITE),
                        new Pair<>(rid3, LockType.WRITE)),
                Lists.newArrayList(f1, f2, f3));
    }
}
