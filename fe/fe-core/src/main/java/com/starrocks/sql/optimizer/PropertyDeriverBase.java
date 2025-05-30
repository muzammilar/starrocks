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


package com.starrocks.sql.optimizer;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.base.DistributionCol;
import com.starrocks.sql.optimizer.base.DistributionProperty;
import com.starrocks.sql.optimizer.base.DistributionSpec;
import com.starrocks.sql.optimizer.base.HashDistributionDesc;
import com.starrocks.sql.optimizer.base.HashDistributionSpec;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.physical.PhysicalSetOperation;
import com.starrocks.sql.optimizer.operator.physical.PhysicalUnionOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.starrocks.sql.optimizer.base.HashDistributionDesc.SourceType.SHUFFLE_JOIN;

public abstract class PropertyDeriverBase<R, C> extends OperatorVisitor<R, C> {

    public abstract R visitOperator(Operator node, C context);


    // Compute the required properties of shuffle join for children, adjust shuffle columns orders for
    // respect the required properties from parent.
    protected static List<PhysicalPropertySet> computeShuffleJoinRequiredProperties(
            PhysicalPropertySet requiredFromParent, List<DistributionCol> leftShuffleColumns,
            List<DistributionCol> rightShuffleColumns) {
        Optional<HashDistributionDesc> requiredShuffleDescOptional =
                getShuffleJoinHashDistributionDesc(requiredFromParent);
        if (!requiredShuffleDescOptional.isPresent()) {
            // required property is not SHUFFLE_JOIN
            return createShuffleJoinRequiredProperties(leftShuffleColumns, rightShuffleColumns);
        } else {
            // required property type is SHUFFLE_JOIN, adjust the required property shuffle columns based on the column
            // order required by parent
            List<DistributionCol> requiredColumns = requiredShuffleDescOptional.get().getDistributionCols();
            boolean adjustBasedOnLeft = CollectionUtils.isEqualCollection(requiredColumns, leftShuffleColumns);
            boolean adjustBasedOnRight = CollectionUtils.isEqualCollection(requiredColumns, rightShuffleColumns);

            if (adjustBasedOnLeft || adjustBasedOnRight) {
                List<DistributionCol> requiredLeft = Lists.newArrayList();
                List<DistributionCol> requiredRight = Lists.newArrayList();

                for (DistributionCol cid : requiredColumns) {
                    int idx = adjustBasedOnLeft ? leftShuffleColumns.indexOf(cid) : rightShuffleColumns.indexOf(cid);
                    requiredLeft.add(leftShuffleColumns.get(idx));
                    requiredRight.add(rightShuffleColumns.get(idx));
                }
                return createShuffleJoinRequiredProperties(requiredLeft, requiredRight);
            } else {
                return createShuffleJoinRequiredProperties(leftShuffleColumns, rightShuffleColumns);
            }
        }
    }

    public static List<PhysicalPropertySet> computeShuffleSetRequiredProperties(PhysicalSetOperation node) {
        Preconditions.checkArgument(
                !((node instanceof PhysicalUnionOperator) && ((PhysicalUnionOperator) node).isUnionAll()));
        List<PhysicalPropertySet> requiredProperties =
                Lists.newArrayListWithCapacity(node.getChildOutputColumns().size());
        for (int i = 0; i < node.getChildOutputColumns().size(); ++i) {
            List<Integer> columns = node.getChildOutputColumns().get(i).stream().map(ColumnRefOperator::getId)
                    .collect(Collectors.toList());
            HashDistributionSpec distribution = DistributionSpec.createHashDistributionSpec(
                    new HashDistributionDesc(columns, SHUFFLE_JOIN));

            PhysicalPropertySet requiredPropertySet =
                    new PhysicalPropertySet(DistributionProperty.createProperty(distribution));
            requiredProperties.add(requiredPropertySet);
        }
        return requiredProperties;
    }



    protected static Optional<HashDistributionDesc> getShuffleJoinHashDistributionDesc(
            PhysicalPropertySet requiredPropertySet) {
        if (!requiredPropertySet.getDistributionProperty().isShuffle()) {
            return Optional.empty();
        }
        HashDistributionDesc requireDistributionDesc =
                ((HashDistributionSpec) requiredPropertySet.getDistributionProperty()
                        .getSpec()).getHashDistributionDesc();
        if (SHUFFLE_JOIN != requireDistributionDesc.getSourceType()) {
            return Optional.empty();
        }

        return Optional.of(requireDistributionDesc);
    }

    private static List<PhysicalPropertySet> createShuffleJoinRequiredProperties(List<DistributionCol> leftColumns,
                                                                                 List<DistributionCol> rightColumns) {
        HashDistributionSpec leftDistribution = DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(leftColumns, SHUFFLE_JOIN));
        HashDistributionSpec rightDistribution = DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(rightColumns, SHUFFLE_JOIN));

        PhysicalPropertySet leftRequiredPropertySet =
                new PhysicalPropertySet(DistributionProperty.createProperty(leftDistribution));
        PhysicalPropertySet rightRequiredPropertySet =
                new PhysicalPropertySet(DistributionProperty.createProperty(rightDistribution));

        return Lists.newArrayList(leftRequiredPropertySet, rightRequiredPropertySet);
    }

    protected PhysicalPropertySet createPropertySetByDistribution(DistributionSpec distributionSpec) {
        DistributionProperty distributionProperty = DistributionProperty.createProperty(distributionSpec);
        return new PhysicalPropertySet(distributionProperty);
    }

    protected DistributionProperty createShuffleAggProperty(List<DistributionCol> partitionColumns) {
        return DistributionProperty.createProperty(DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(partitionColumns, HashDistributionDesc.SourceType.SHUFFLE_AGG)));
    }

    protected PhysicalPropertySet createShuffleAggPropertySet(List<DistributionCol> partitions) {
        HashDistributionDesc desc = new HashDistributionDesc(partitions, HashDistributionDesc.SourceType.SHUFFLE_AGG);
        DistributionProperty property = DistributionProperty.createProperty(DistributionSpec.createHashDistributionSpec(desc));
        return new PhysicalPropertySet(property);
    }

    protected PhysicalPropertySet createGatherPropertySet() {
        DistributionProperty distributionProperty =
                DistributionProperty.createProperty(DistributionSpec.createGatherDistributionSpec());
        return new PhysicalPropertySet(distributionProperty);
    }

    protected List<DistributionCol> enforceNullStrict(List<DistributionCol> cols) {
        return cols.stream().map(DistributionCol::getNullStrictCol).collect(Collectors.toList());
    }
}
