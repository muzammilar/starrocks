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

import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.proc.BaseProcResult;
import com.starrocks.persist.gson.GsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class JDBCResourceTest {

    @BeforeEach
    public void setUp() {
        FeConstants.runningUnitTest = true;
    }

    private Map<String, String> getMockConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put("user", "user");
        configs.put("password", "password");
        configs.put("driver_url", "driver_url");
        configs.put("driver_class", "a.b.c");
        configs.put("jdbc_uri", "jdbc:postgresql://host1:port1,host2:port2/");
        return configs;
    }

    @Test
    public void testSerialization() throws Exception {
        JDBCResource resource0 = new JDBCResource("jdbc_resource_test", getMockConfigs());

        String json = GsonUtils.GSON.toJson(resource0);
        Resource resource1 = GsonUtils.GSON.fromJson(json, Resource.class);

        Assertions.assertTrue(resource1 instanceof JDBCResource);
        Assertions.assertEquals(resource0.getName(), resource1.getName());
        Assertions.assertEquals(resource0.getProperty(JDBCResource.DRIVER_URL),
                ((JDBCResource) resource1).getProperty(JDBCResource.DRIVER_URL));
        Assertions.assertEquals(resource0.getProperty(JDBCResource.DRIVER_CLASS),
                ((JDBCResource) resource1).getProperty(JDBCResource.DRIVER_CLASS));
        Assertions.assertEquals(resource0.getProperty(JDBCResource.URI),
                ((JDBCResource) resource1).getProperty(JDBCResource.URI));
        Assertions.assertEquals(resource0.getProperty(JDBCResource.USER),
                ((JDBCResource) resource1).getProperty(JDBCResource.USER));
        Assertions.assertEquals(resource0.getProperty(JDBCResource.PASSWORD),
                ((JDBCResource) resource1).getProperty(JDBCResource.PASSWORD));
    }

    @Test
    public void testWithoutDriverURL() {
        assertThrows(DdlException.class, () -> {
            Map<String, String> configs = getMockConfigs();
            configs.remove(JDBCResource.DRIVER_URL);
            JDBCResource resource = new JDBCResource("jdbc_resource_test");
            resource.setProperties(configs);
        });
    }

    @Test
    public void testWithoutDriverClass() {
        assertThrows(DdlException.class, () -> {
            Map<String, String> configs = getMockConfigs();
            configs.remove(JDBCResource.DRIVER_CLASS);
            JDBCResource resource = new JDBCResource("jdbc_resource_test");
            resource.setProperties(configs);
        });
    }

    @Test
    public void testWithoutURI() {
        assertThrows(DdlException.class, () -> {
            Map<String, String> configs = getMockConfigs();
            configs.remove(JDBCResource.URI);
            JDBCResource resource = new JDBCResource("jdbc_resource_test");
            resource.setProperties(configs);
        });
    }

    @Test
    public void testWithoutUser() {
        assertThrows(DdlException.class, () -> {
            Map<String, String> configs = getMockConfigs();
            configs.remove(JDBCResource.USER);
            JDBCResource resource = new JDBCResource("jdbc_resource_test");
            resource.setProperties(configs);
        });
    }

    @Test
    public void testWithoutPassword() {
        assertThrows(DdlException.class, () -> {
            Map<String, String> configs = getMockConfigs();
            configs.remove(JDBCResource.PASSWORD);
            JDBCResource resource = new JDBCResource("jdbc_resource_test");
            resource.setProperties(configs);
        });
    }

    @Test
    public void testWithUnknownProperty() {
        assertThrows(DdlException.class, () -> {
            Map<String, String> configs = getMockConfigs();
            configs.put("xxx", "xxx");
            JDBCResource resource = new JDBCResource("jdbc_resource_test");
            resource.setProperties(configs);
        });
    }

    @Test
    public void testGetProcNodeData() throws Exception {
        Map<String, String> configs = getMockConfigs();
        JDBCResource resource = new JDBCResource("jdbc_resource_test");
        resource.setProperties(configs);

        BaseProcResult result = new BaseProcResult();
        resource.getProcNodeData(result);
        Assertions.assertEquals(4, result.getRows().size()); // do not show password
    }
}
