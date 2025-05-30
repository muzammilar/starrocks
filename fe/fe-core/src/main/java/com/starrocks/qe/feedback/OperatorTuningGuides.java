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

package com.starrocks.qe.feedback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.qe.feedback.guide.TuningGuide;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class OperatorTuningGuides {

    private final long originalTimeCost;

    private final UUID originalQueryId;

    private final Map<Integer, OperatorGuideInfo> operatorIdToTuningGuideInfo;

    private final Cache<UUID, Long> optimizedRecords;

    private static class OperatorGuideInfo {
        private final List<TuningGuide> tuningGuides = Lists.newArrayList();
        private final int nodeId;

        public OperatorGuideInfo(int nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            OperatorGuideInfo that = (OperatorGuideInfo) o;
            return nodeId == that.nodeId && Objects.equals(tuningGuides, that.tuningGuides);
        }

        @Override
        public int hashCode() {
            return Objects.hash(nodeId);
        }
    }

    public OperatorTuningGuides(UUID originalQueryId, long originalTimeCost) {
        this.originalQueryId = originalQueryId;
        this.originalTimeCost = originalTimeCost;
        this.operatorIdToTuningGuideInfo = Maps.newHashMap();
        this.optimizedRecords = Caffeine.newBuilder().maximumSize(50).build();
    }

    public void addTuningGuide(int nodeId, int operatorId, TuningGuide tuningGuide) {
        OperatorGuideInfo operatorGuideInfo =
                operatorIdToTuningGuideInfo.computeIfAbsent(operatorId, e -> new OperatorGuideInfo(nodeId));
        operatorGuideInfo.tuningGuides.add(tuningGuide);
    }

    public List<TuningGuide> getTuningGuides(int operatorId) {
        OperatorGuideInfo guide = operatorIdToTuningGuideInfo.get(operatorId);
        if (guide == null) {
            return null;
        }
        return guide.tuningGuides;
    }

    public boolean isEmpty() {
        return operatorIdToTuningGuideInfo.isEmpty();
    }

    public long optimizedQueryCount() {
        return optimizedRecords.estimatedSize();
    }

    public void addOptimizedRecord(UUID queryId, long timeCost) {
        optimizedRecords.put(queryId, timeCost);
    }

    public boolean isUseful() {
        Map<UUID, Long> records = optimizedRecords.asMap();
        long accumulatedTime = 0L;
        if (records.isEmpty()) {
            return true;
        }
        int size = 0;
        for (Map.Entry<UUID, Long> entry : records.entrySet()) {
            accumulatedTime += entry.getValue();
            size++;
        }

        return accumulatedTime < originalTimeCost * size;
    }

    public long getAvgTunedTimeCost() {
        Map<UUID, Long> records = optimizedRecords.asMap();
        long accumulatedTime = 0L;
        int size = 0;
        for (Map.Entry<UUID, Long> entry : records.entrySet()) {
            accumulatedTime += entry.getValue();
            size++;
        }
        if (size == 0L) {
            return 0L;
        }
        return accumulatedTime / size;
    }

    public UUID getOriginalQueryId() {
        return originalQueryId;
    }

    public long getOriginalTimeCost() {
        return originalTimeCost;
    }

    public String getFullTuneGuidesInfo() {
        return getTuneGuidesInfo(true);
    }

    public String getTuneGuidesInfo(boolean isFull) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, OperatorGuideInfo> entry : operatorIdToTuningGuideInfo.entrySet()) {
            sb.append("PlanNode ").append(entry.getValue().nodeId).append(":").append("\n");
            for (TuningGuide guide : entry.getValue().tuningGuides) {
                sb.append(guide.getClass().getSimpleName()).append("\n");
                if (isFull) {
                    sb.append(guide.getDescription()).append("\n");
                    sb.append(guide.getAdvice()).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return Objects.equals(operatorIdToTuningGuideInfo, ((OperatorTuningGuides) o).operatorIdToTuningGuideInfo);
    }

    @Override
    public int hashCode() {
        return 1;
    }

    public static class OptimizedRecord {

        private final OperatorTuningGuides operatorTuningGuides;

        private final List<TuningGuide> usedGuides;

        public OptimizedRecord(OperatorTuningGuides operatorTuningGuides, List<TuningGuide> usedGuides) {
            this.operatorTuningGuides = operatorTuningGuides;
            this.usedGuides = usedGuides;
        }

        public OperatorTuningGuides getOperatorTuningGuides() {
            return operatorTuningGuides;
        }

        public String getExplainString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Plan had been tuned by Plan Advisor.").append("\n");
            sb.append("Original query id:").append(operatorTuningGuides.originalQueryId).append("\n");
            sb.append("Original time cost: ").append(operatorTuningGuides.originalTimeCost).append(" ms").append("\n");

            int idx = 0;
            for (TuningGuide guide : usedGuides) {
                sb.append(++idx).append(": ").append(guide.getClass().getSimpleName()).append("\n");
                sb.append(guide.getDescription()).append("\n");
                sb.append(guide.getAdvice()).append("\n");
                sb.append("\n");
            }
            return sb.toString();
        }
    }
}
