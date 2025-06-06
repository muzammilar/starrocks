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

package com.starrocks.catalog;

import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.common.FeConstants;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.transaction.TransactionType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PhysicalPartitionImplTest {
    private FakeGlobalStateMgr fakeGlobalStateMgr;

    private GlobalStateMgr globalStateMgr;

    @Before
    public void setUp() {
        fakeGlobalStateMgr = new FakeGlobalStateMgr();
        globalStateMgr = Deencapsulation.newInstance(GlobalStateMgr.class);

        FakeGlobalStateMgr.setGlobalStateMgr(globalStateMgr);
        FakeGlobalStateMgr.setMetaVersion(FeConstants.META_VERSION);
    }

    @Test
    public void testPhysicalPartition() throws Exception {
        PhysicalPartition p = new PhysicalPartition(1, "", 1, new MaterializedIndex());
        Assert.assertEquals(1, p.getId());
        Assert.assertEquals(1, p.getParentId());
        Assert.assertFalse(p.isImmutable());
        p.setImmutable(true);
        Assert.assertTrue(p.isImmutable());
        Assert.assertEquals(1, p.getVisibleVersion());
        Assert.assertFalse(p.isFirstLoad());

        p.hashCode();

        p.updateVersionForRestore(2);
        Assert.assertEquals(2, p.getVisibleVersion());
        Assert.assertTrue(p.isFirstLoad());

        p.updateVisibleVersion(3);
        Assert.assertEquals(3, p.getVisibleVersion());

        p.updateVisibleVersion(4, System.currentTimeMillis(), 1);
        Assert.assertEquals(4, p.getVisibleVersion());
        Assert.assertEquals(1, p.getVisibleTxnId());

        Assert.assertTrue(p.getVisibleVersionTime() <= System.currentTimeMillis());

        p.setNextVersion(6);
        Assert.assertEquals(6, p.getNextVersion());
        Assert.assertEquals(5, p.getCommittedVersion());

        p.setDataVersion(5);
        Assert.assertEquals(5, p.getDataVersion());

        p.setNextDataVersion(6);
        Assert.assertEquals(6, p.getNextDataVersion());
        Assert.assertEquals(5, p.getCommittedDataVersion());

        p.createRollupIndex(new MaterializedIndex(1));
        p.createRollupIndex(new MaterializedIndex(2, IndexState.SHADOW));

        Assert.assertEquals(0, p.getBaseIndex().getId());
        Assert.assertNotNull(p.getIndex(0));
        Assert.assertNotNull(p.getIndex(1));
        Assert.assertNotNull(p.getIndex(2));
        Assert.assertNull(p.getIndex(3));

        Assert.assertEquals(3, p.getMaterializedIndices(IndexExtState.ALL).size());
        Assert.assertEquals(2, p.getMaterializedIndices(IndexExtState.VISIBLE).size());
        Assert.assertEquals(1, p.getMaterializedIndices(IndexExtState.SHADOW).size());

        Assert.assertTrue(p.hasMaterializedView());
        Assert.assertTrue(p.hasStorageData());
        Assert.assertEquals(0, p.storageDataSize());
        Assert.assertEquals(0, p.storageRowCount());
        Assert.assertEquals(0, p.storageReplicaCount());
        Assert.assertEquals(0, p.getTabletMaxDataSize());

        p.toString();

        Assert.assertFalse(p.visualiseShadowIndex(1, false));
        Assert.assertTrue(p.visualiseShadowIndex(2, false));

        p.createRollupIndex(new MaterializedIndex(3, IndexState.SHADOW));
        Assert.assertTrue(p.visualiseShadowIndex(3, true));

        Assert.assertTrue(p.equals(p));
        Assert.assertFalse(p.equals(new Partition(0, 11, "", null, null)));

        PhysicalPartition p2 = new PhysicalPartition(1, "", 1, new MaterializedIndex());
        Assert.assertFalse(p.equals(p2));
        p2.setBaseIndex(new MaterializedIndex(1));

        p.createRollupIndex(new MaterializedIndex(4, IndexState.SHADOW));
        p.deleteRollupIndex(0);
        p.deleteRollupIndex(1);
        p.deleteRollupIndex(2);
        p.deleteRollupIndex(4);

        Assert.assertFalse(p.equals(p2));

        p.setIdForRestore(3);
        Assert.assertEquals(3, p.getId());
        Assert.assertEquals(1, p.getBeforeRestoreId());

        p.setParentId(3);
        Assert.assertEquals(3, p.getParentId());

        p.setMinRetainVersion(1);
        Assert.assertEquals(1, p.getMinRetainVersion());
        p.setLastVacuumTime(1);
        Assert.assertEquals(1, p.getLastVacuumTime());

        p.setMinRetainVersion(3);
        p.setMetadataSwitchVersion(1);
        Assert.assertEquals(1, p.getMinRetainVersion());
        p.setMetadataSwitchVersion(0);
        Assert.assertEquals(3, p.getMinRetainVersion());

        p.setDataVersion(0);
        p.setNextDataVersion(0);
        p.setVersionEpoch(0);
        p.setVersionTxnType(null);
        p.gsonPostProcess();
        Assert.assertEquals(p.getDataVersion(), p.getVisibleVersion());
        Assert.assertEquals(p.getNextDataVersion(), p.getNextVersion());
        Assert.assertTrue(p.getVersionEpoch() > 0);
        Assert.assertEquals(p.getVersionTxnType(), TransactionType.TXN_NORMAL);
    }

    @Test
    public void testPhysicalPartitionEqual() throws Exception {
        PhysicalPartition p1 = new PhysicalPartition(1, "", 1, new MaterializedIndex());
        PhysicalPartition p2 = new PhysicalPartition(1, "", 1, new MaterializedIndex());
        Assert.assertTrue(p1.equals(p2));

        p1.createRollupIndex(new MaterializedIndex());
        p2.createRollupIndex(new MaterializedIndex());
        Assert.assertTrue(p1.equals(p2));

        p1.createRollupIndex(new MaterializedIndex(1));
        p2.createRollupIndex(new MaterializedIndex(2));
        Assert.assertFalse(p1.equals(p2));
    }
}
