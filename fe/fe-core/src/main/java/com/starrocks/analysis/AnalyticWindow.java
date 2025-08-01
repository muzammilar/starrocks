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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/analysis/AnalyticWindow.java

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

package com.starrocks.analysis;

import com.google.common.base.Preconditions;
import com.starrocks.common.AnalysisException;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.thrift.TAnalyticWindow;
import com.starrocks.thrift.TAnalyticWindowBoundary;
import com.starrocks.thrift.TAnalyticWindowBoundaryType;
import com.starrocks.thrift.TAnalyticWindowType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Windowing clause of an analytic expr
 * Both left and right boundaries are always non-null after analyze().
 */
public class AnalyticWindow implements ParseNode {
    // default window used when an analytic expr was given an order by but no window
    public static final AnalyticWindow DEFAULT_WINDOW = new AnalyticWindow(Type.RANGE,
            new Boundary(BoundaryType.UNBOUNDED_PRECEDING, null),
            new Boundary(BoundaryType.CURRENT_ROW, null));

    public static final AnalyticWindow DEFAULT_ROWS_WINDOW = new AnalyticWindow(AnalyticWindow.Type.ROWS,
            new AnalyticWindow.Boundary(AnalyticWindow.BoundaryType.UNBOUNDED_PRECEDING, null),
            new AnalyticWindow.Boundary(AnalyticWindow.BoundaryType.CURRENT_ROW, null));

    public static final AnalyticWindow DEFAULT_UNBOUNDED_WINDOW = new AnalyticWindow(AnalyticWindow.Type.ROWS,
            new AnalyticWindow.Boundary(AnalyticWindow.BoundaryType.UNBOUNDED_PRECEDING, null),
            new AnalyticWindow.Boundary(AnalyticWindow.BoundaryType.UNBOUNDED_FOLLOWING, null));

    public enum Type {
        ROWS("ROWS"),
        RANGE("RANGE");

        private final String description_;

        private Type(String d) {
            description_ = d;
        }

        @Override
        public String toString() {
            return description_;
        }

        public TAnalyticWindowType toThrift() {
            return this == ROWS ? TAnalyticWindowType.ROWS : TAnalyticWindowType.RANGE;
        }
    }

    public enum BoundaryType {
        UNBOUNDED_PRECEDING("UNBOUNDED PRECEDING"),
        UNBOUNDED_FOLLOWING("UNBOUNDED FOLLOWING"),
        CURRENT_ROW("CURRENT ROW"),
        PRECEDING("PRECEDING"),
        FOLLOWING("FOLLOWING");

        private final String description;

        private BoundaryType(String d) {
            description = d;
        }

        @Override
        public String toString() {
            return description;
        }

        public TAnalyticWindowBoundaryType toThrift() {
            Preconditions.checkState(!isAbsolutePos());

            if (this == CURRENT_ROW) {
                return TAnalyticWindowBoundaryType.CURRENT_ROW;
            } else if (this == PRECEDING) {
                return TAnalyticWindowBoundaryType.PRECEDING;
            } else if (this == FOLLOWING) {
                return TAnalyticWindowBoundaryType.FOLLOWING;
            }

            return null;
        }

        public boolean isAbsolutePos() {
            return this == UNBOUNDED_PRECEDING || this == UNBOUNDED_FOLLOWING;
        }

        public boolean isOffset() {
            return this == PRECEDING || this == FOLLOWING;
        }

        public boolean isPreceding() {
            return this == UNBOUNDED_PRECEDING || this == PRECEDING;
        }

        public boolean isFollowing() {
            return this == UNBOUNDED_FOLLOWING || this == FOLLOWING;
        }

        public BoundaryType converse() {
            switch (this) {
                case UNBOUNDED_PRECEDING:
                    return UNBOUNDED_FOLLOWING;

                case UNBOUNDED_FOLLOWING:
                    return UNBOUNDED_PRECEDING;

                case PRECEDING:
                    return FOLLOWING;

                case FOLLOWING:
                    return PRECEDING;

                default:
                    return CURRENT_ROW;
            }
        }
    }

    public static class Boundary implements ParseNode {

        private final NodePosition pos;
        private BoundaryType type;

        // Offset expr. Only set for PRECEDING/FOLLOWING. Needed for toSql().
        private final Expr expr;

        // The offset value. Set during analysis after evaluating expr_. Integral valued
        // for ROWS windows.
        private BigDecimal offsetValue;

        public BoundaryType getType() {
            return type;
        }

        public Expr getExpr() {
            return expr;
        }

        public Boundary(BoundaryType type, Expr e) {
            this(type, e, null);
        }

        // c'tor used by clone()
        public Boundary(BoundaryType type, Expr e, BigDecimal offsetValue) {
            this(type, e, offsetValue, NodePosition.ZERO);
        }

        public Boundary(BoundaryType type, Expr e, BigDecimal offsetValue, NodePosition pos) {
            Preconditions.checkState(
                    (type.isOffset() && e != null)
                            || (!type.isOffset() && e == null));
            this.pos = pos;
            this.type = type;
            this.expr = e;
            this.offsetValue = offsetValue;
        }

        public String toSql() {
            StringBuilder sb = new StringBuilder();

            if (expr != null) {
                sb.append(expr.toSql()).append(" ");
            }

            sb.append(type.toString());
            return sb.toString();
        }

        @Override
        public NodePosition getPos() {
            return pos;
        }

        public TAnalyticWindowBoundary toThrift(Type windowType) {
            TAnalyticWindowBoundary result = new TAnalyticWindowBoundary(type.toThrift());

            if (type.isOffset() && windowType == Type.ROWS) {
                result.setRows_offset_value(offsetValue.longValue());
            }

            // TODO: range windows need range_offset_predicate
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }

            if (obj.getClass() != this.getClass()) {
                return false;
            }

            Boundary o = (Boundary) obj;
            boolean exprEqual = (expr == null) == (o.expr == null);

            if (exprEqual && expr != null) {
                exprEqual = expr.equals(o.expr);
            }

            return type == o.type && exprEqual;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, expr, offsetValue);
        }

        public Boundary converse() {
            Boundary result = new Boundary(type.converse(),
                    (expr != null) ? expr.clone() : null);
            result.offsetValue = offsetValue;
            return result;
        }

        @Override
        public Boundary clone() {
            return new Boundary(type, expr != null ? expr.clone() : null, offsetValue);
        }

        public void setOffsetValue(BigDecimal offsetValue) {
            this.offsetValue = offsetValue;
        }
    }

    private final NodePosition pos;

    private final Type type_;
    private final Boundary leftBoundary_;
    private Boundary rightBoundary_;  // may be null before analyze()
    private String toSqlString_;  // cached after analysis

    public Type getType() {
        return type_;
    }

    public Boundary getLeftBoundary() {
        return leftBoundary_;
    }

    public Boundary getRightBoundary() {
        return rightBoundary_;
    }

    public Boundary setRightBoundary(Boundary b) {
        return rightBoundary_ = b;
    }

    public AnalyticWindow(Type type, Boundary b) {
        this(type, b, NodePosition.ZERO);
    }

    public AnalyticWindow(Type type, Boundary b, NodePosition pos) {
        this.pos = pos;
        type_ = type;
        Preconditions.checkNotNull(b);
        leftBoundary_ = b;
        rightBoundary_ = null;
    }

    public AnalyticWindow(Type type, Boundary l, Boundary r) {
        this(type, l, r, NodePosition.ZERO);
    }

    public AnalyticWindow(Type type, Boundary l, Boundary r, NodePosition pos) {
        this.pos = pos;
        type_ = type;
        Preconditions.checkNotNull(l);
        leftBoundary_ = l;
        Preconditions.checkNotNull(r);
        rightBoundary_ = r;
    }

    /**
     * Clone c'tor
     */
    private AnalyticWindow(AnalyticWindow other) {
        pos = other.pos;
        type_ = other.type_;
        Preconditions.checkNotNull(other.leftBoundary_);
        leftBoundary_ = other.leftBoundary_.clone();

        if (other.rightBoundary_ != null) {
            rightBoundary_ = other.rightBoundary_.clone();
        }

        toSqlString_ = other.toSqlString_;  // safe to share
    }

    public AnalyticWindow reverse() {
        Boundary newRightBoundary = leftBoundary_.converse();
        Boundary newLeftBoundary = null;

        if (rightBoundary_ == null) {
            newLeftBoundary = new Boundary(leftBoundary_.getType(), null);
        } else {
            newLeftBoundary = rightBoundary_.converse();
        }

        return new AnalyticWindow(type_, newLeftBoundary, newRightBoundary, pos);
    }

    public String toSql() {
        if (toSqlString_ != null) {
            return toSqlString_;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(type_.toString()).append(" ");

        if (rightBoundary_ == null) {
            sb.append(leftBoundary_.toSql());
        } else {
            sb.append("BETWEEN ").append(leftBoundary_.toSql()).append(" AND ");
            sb.append(rightBoundary_.toSql());
        }

        return sb.toString();
    }

    @Override
    public NodePosition getPos() {
        return pos;
    }

    public TAnalyticWindow toThrift() {
        TAnalyticWindow result = new TAnalyticWindow(type_.toThrift());

        if (leftBoundary_.getType() != BoundaryType.UNBOUNDED_PRECEDING) {
            result.setWindow_start(leftBoundary_.toThrift(type_));
        }

        Preconditions.checkNotNull(rightBoundary_);

        if (rightBoundary_.getType() != BoundaryType.UNBOUNDED_FOLLOWING) {
            result.setWindow_end(rightBoundary_.toThrift(type_));
        }

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (obj.getClass() != this.getClass()) {
            return false;
        }

        AnalyticWindow o = (AnalyticWindow) obj;
        boolean rightBoundaryEqual =
                (rightBoundary_ == null) == (o.rightBoundary_ == null);

        if (rightBoundaryEqual && rightBoundary_ != null) {
            rightBoundaryEqual = rightBoundary_.equals(o.rightBoundary_);
        }

        return type_ == o.type_
                && leftBoundary_.equals(o.leftBoundary_)
                && rightBoundaryEqual;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type_, leftBoundary_, rightBoundary_);
    }

    @Override
    public AnalyticWindow clone() {
        return new AnalyticWindow(this);
    }
}
