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

import com.starrocks.common.AnalysisException;
import com.starrocks.sql.ast.ModifyBackendClause;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class ModifyBackendClauseTest {
    
    @Test
    public void testCreateClause() {
        ModifyBackendClause clause1 = new ModifyBackendClause("originalHost-test", "sandbox");
        Assertions.assertEquals("originalHost-test", clause1.getSrcHost());
        Assertions.assertEquals("sandbox", clause1.getDestHost());
    }

    @Test
    public void testNormal() throws AnalysisException {
        ModifyBackendClause clause = new ModifyBackendClause("", "");
        Assertions.assertTrue(clause.getHostPortPairs().size() == 0);
    }
}
