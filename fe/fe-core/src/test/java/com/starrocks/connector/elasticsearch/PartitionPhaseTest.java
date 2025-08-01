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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/external/elasticsearch/PartitionPhaseTest.java

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

package com.starrocks.connector.elasticsearch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.EsTable;
import com.starrocks.catalog.Type;
import com.starrocks.common.ExceptionChecker;
import mockit.Expectations;
import mockit.Injectable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PartitionPhaseTest extends EsTestCase {

    @Test
    public void testWorkFlow(@Injectable EsRestClient client) throws Exception {
        final EsShardPartitions[] esShardPartitions = {null};
        ExceptionChecker.expectThrowsNoException(() ->
                esShardPartitions[0] = EsShardPartitions.findShardPartitions("doe",
                        loadJsonFromFile("data/es/test_search_shards.json")));
        assertNotNull(esShardPartitions[0]);
        ObjectMapper mapper = new ObjectMapper();
        JsonParser jsonParser =
                mapper.getJsonFactory().createJsonParser(loadJsonFromFile("data/es/test_nodes_http.json"));
        Map<String, Map<String, Object>> nodesData =
                (Map<String, Map<String, Object>>) mapper.readValue(jsonParser, Map.class).get("nodes");
        Map<String, EsNodeInfo> nodesMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : nodesData.entrySet()) {
            EsNodeInfo node = new EsNodeInfo(entry.getKey(), entry.getValue());
            if (node.hasHttp()) {
                nodesMap.put(node.getId(), node);
            }
        }

        new Expectations(client) {
            {
                client.getHttpNodes();
                minTimes = 0;
                result = nodesMap;

                client.searchShards("doe");
                minTimes = 0;
                result = esShardPartitions[0];
            }
        };
        List<Column> columns = new ArrayList<>();
        Column k1 = new Column("k1", Type.BIGINT);
        columns.add(k1);
        EsTable esTableBefore7X = fakeEsTable("doe", "doe", "doc", columns);
        SearchContext context = new SearchContext(esTableBefore7X);
        PartitionPhase partitionPhase = new PartitionPhase(client);
        ExceptionChecker.expectThrowsNoException(() -> partitionPhase.execute(context));
        ExceptionChecker.expectThrowsNoException(() -> partitionPhase.postProcess(context));
        assertNotNull(context.tablePartitions());
    }
}
