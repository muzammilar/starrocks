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

package com.starrocks.sql.ast.integration;

import com.starrocks.sql.ast.AstVisitor;
import com.starrocks.sql.ast.DdlStmt;
import com.starrocks.sql.parser.NodePosition;

import java.util.Map;
import javax.annotation.Nonnull;

// clause which is used to alter security integration
public class AlterSecurityIntegrationStatement extends DdlStmt {
    private final String name;
    private final Map<String, String> properties;

    public AlterSecurityIntegrationStatement(String name, Map<String, String> properties) {
        this(name, properties, NodePosition.ZERO);
    }

    public AlterSecurityIntegrationStatement(String name, Map<String, String> properties, NodePosition pos) {
        super(pos);
        this.name = name;
        this.properties = properties;
    }

    public String getName() {
        return name;
    }

    @Nonnull
    public Map<String, String> getProperties() {
        return this.properties;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitAlterSecurityIntegrationStatement(this, context);
    }
}
