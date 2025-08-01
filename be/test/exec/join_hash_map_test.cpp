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

#include "exec/join/join_hash_map.h"

#include <gtest/gtest.h>

#include "runtime/descriptor_helper.h"
#include "runtime/exec_env.h"
#include "runtime/mem_tracker.h"
#include "testutil/assert.h"

namespace starrocks {
class JoinHashMapTest : public ::testing::Test {
protected:
    void SetUp() override {
        config::vector_chunk_size = 4096;
        _object_pool = std::make_shared<ObjectPool>();
        _mem_pool = std::make_shared<MemPool>();
        _runtime_profile = create_runtime_profile();
        _runtime_state = create_runtime_state();
        _int_type = TypeDescriptor::from_logical_type(TYPE_INT);
        _tinyint_type = TypeDescriptor::from_logical_type(TYPE_TINYINT);
        _varchar_type = TypeDescriptor::from_logical_type(TYPE_VARCHAR);
    }
    void TearDown() override {}

    HashTableParam create_table_param(TJoinOp::type join_type, size_t output_col_cnt);
    HashTableParam create_table_param_int(TJoinOp::type join_type, size_t output_col_cnt);
    static ColumnPtr create_int32_column(uint32_t row_count, uint32_t start_value);
    ColumnPtr create_binary_column(uint32_t row_count, uint32_t start_value);
    static ColumnPtr create_int32_nullable_column(uint32_t row_count, uint32_t start_value);
    static void check_int32_column(const Column& column, uint32_t row_count, uint32_t start_value);
    static void check_probe_index_column(const Column& column, const std::vector<uint32_t>& idxs);
    static void check_binary_column(const ColumnPtr& column, uint32_t row_count, uint32_t start_value);
    static void check_null_column(const ColumnPtr& column, uint32_t row_count);
    static void check_int32_nullable_column(const Column& column, uint32_t row_count, uint32_t start_value);
    ChunkPtr create_int32_probe_chunk(uint32_t count, uint32_t start_value, bool nullable);
    ChunkPtr create_binary_probe_chunk(uint32_t count, uint32_t start_value, bool nullable);
    ChunkPtr create_int32_build_chunk(uint32_t count, uint32_t start_value, bool nullable);
    ChunkPtr create_binary_build_chunk(uint32_t count, bool nullable);
    static TSlotDescriptor create_slot_descriptor(const std::string& column_name, LogicalType column_type,
                                                  int32_t column_pos, bool nullable);
    static void add_tuple_descriptor(TDescriptorTableBuilder* table_desc_builder, LogicalType column_type,
                                     bool nullable, size_t column_count = 3);
    static std::shared_ptr<RuntimeProfile> create_runtime_profile();
    std::shared_ptr<RowDescriptor> create_row_desc(TDescriptorTableBuilder* table_desc_builder);
    std::shared_ptr<RowDescriptor> create_probe_desc(TDescriptorTableBuilder* probe_desc_builder);
    std::shared_ptr<RowDescriptor> create_build_desc(TDescriptorTableBuilder* build_desc_builder);
    static std::shared_ptr<RuntimeState> create_runtime_state();

    static void check_probe_index(const Buffer<uint32_t>& probe_index, uint32_t step, uint32_t match_count,
                                  uint32_t probe_row_count);
    static void check_build_index(const Buffer<uint32_t>& build_index, uint32_t step, uint32_t match_count,
                                  uint32_t probe_row_count);
    static void check_match_index(const Buffer<uint32_t>& probe_match_index, uint32_t start, int32_t count,
                                  uint32_t match_count);
    static void check_probe_state(const JoinHashTableItems& table_items, const HashTableProbeState& probe_state,
                                  JoinMatchFlag match_flag, uint32_t step, uint32_t match_count,
                                  uint32_t probe_row_count);
    static void check_build_index(const Buffer<uint32_t>& first, const Buffer<uint32_t>& next, uint32_t row_count);
    static void check_build_index(const Buffer<uint8_t>& nulls, const Buffer<uint32_t>& first,
                                  const Buffer<uint32_t>& next, uint32_t row_count);
    static void check_build_slice(const Buffer<Slice>& slices, uint32_t row_count);
    static void check_build_slice(const Buffer<uint8_t>& nulls, const Buffer<Slice>& slices, uint32_t row_count);
    static void check_build_column(const ColumnPtr& build_column, uint32_t row_count);
    static void check_build_column(const Buffer<uint8_t>& nulls, const ColumnPtr& build_column, uint32_t row_count);

    // used for probe from ht
    static void prepare_table_items(JoinHashTableItems* table_items, TJoinOp::type join_type, bool with_other_conjunct,
                                    uint32_t batch_count);

    // used for build func
    void prepare_table_items(JoinHashTableItems* table_items, uint32_t row_count);

    void prepare_probe_state(HashTableProbeState* probe_state, uint32_t probe_row_count);
    static void prepare_build_data(Buffer<int32_t>* build_data, uint32_t batch_count);
    static void prepare_probe_data(Buffer<int32_t>* probe_data, uint32_t probe_row_count);

    static bool is_check_cur_row_match_count(TJoinOp::type join_type, bool with_other_conjunct);

    // flag: 0(all 0), 1(all 1), 2(half 0), 3(one third 0)
    static Buffer<uint8_t> create_bools(uint32_t count, int32_t flag);
    static ColumnPtr create_column(LogicalType LT);
    static ColumnPtr create_column(LogicalType LT, uint32_t start, uint32_t count);
    static ColumnPtr create_nullable_column(LogicalType LT);
    static ColumnPtr create_nullable_column(LogicalType LT, const Buffer<uint8_t>& nulls, uint32_t start,
                                            uint32_t count);
    void check_empty_hash_map(TJoinOp::type join_type, int num_probe_rows, int32_t expect_num_rows,
                              int32_t expect_num_colums);

    void sort_results_from_coroutine(Buffer<uint32_t>& pid, Buffer<uint32_t>& bid, int size) {
        std::vector<std::pair<int, int>> zipped;
        for (auto i = 0; i < size; i++) {
            zipped.push_back(std::make_pair(pid[i], bid[i]));
        }
        std::sort(std::begin(zipped), std::end(zipped), [&](const auto& a, const auto& b) {
            if (a.first == b.first) return a.second > b.second;
            return a.first < b.first;
        });
        for (auto i = 0; i < size; i++) {
            pid[i] = zipped[i].first;
            bid[i] = zipped[i].second;
        }
    }

    static void check_probe_output_slot_ids(const JoinHashTableItems& table_items,
                                            const std::vector<SlotId>& check_slot_ids);
    static void check_build_output_slot_ids(const JoinHashTableItems& table_items,
                                            const std::vector<SlotId>& check_slot_ids);
    static void check_lazy_probe_output_slot_ids(const JoinHashTableItems& table_items,
                                                 const std::vector<SlotId>& check_slot_ids);
    static void check_lazy_build_output_slot_ids(const JoinHashTableItems& table_items,
                                                 const std::vector<SlotId>& check_slot_ids);
    static void check_not_output_slot_ids(const JoinHashTableItems& table_items,
                                          const std::vector<SlotId>& check_slot_ids);

    std::shared_ptr<ObjectPool> _object_pool = nullptr;
    std::shared_ptr<MemPool> _mem_pool = nullptr;
    std::shared_ptr<RuntimeProfile> _runtime_profile = nullptr;
    std::shared_ptr<RuntimeState> _runtime_state = nullptr;
    TypeDescriptor _int_type;
    TypeDescriptor _tinyint_type;
    TypeDescriptor _varchar_type;
    std::shared_ptr<RowDescriptor> _probe_desc;
    std::shared_ptr<RowDescriptor> _build_desc;
};

void JoinHashMapTest::check_probe_output_slot_ids(const JoinHashTableItems& table_items,
                                                  const std::vector<SlotId>& check_slot_ids) {
    ASSERT_EQ(table_items.output_probe_column_count, check_slot_ids.size());
    std::vector<SlotId> slot_ids;
    for (const auto& slot : table_items.probe_slots) {
        if (slot.need_output) {
            slot_ids.emplace_back(slot.slot->id());
        }
    }
    std::sort(slot_ids.begin(), slot_ids.end());
    ASSERT_EQ(slot_ids, check_slot_ids);
}

void JoinHashMapTest::check_build_output_slot_ids(const JoinHashTableItems& table_items,
                                                  const std::vector<SlotId>& check_slot_ids) {
    ASSERT_EQ(table_items.output_build_column_count, check_slot_ids.size());
    std::vector<SlotId> slot_ids;
    for (const auto& slot : table_items.build_slots) {
        if (slot.need_output) {
            slot_ids.emplace_back(slot.slot->id());
        }
    }
    std::sort(slot_ids.begin(), slot_ids.end());
    ASSERT_EQ(slot_ids, check_slot_ids);
}

void JoinHashMapTest::check_lazy_probe_output_slot_ids(const JoinHashTableItems& table_items,
                                                       const std::vector<SlotId>& check_slot_ids) {
    ASSERT_EQ(table_items.lazy_output_probe_column_count, check_slot_ids.size());
    std::vector<SlotId> slot_ids;
    for (const auto& slot : table_items.probe_slots) {
        if (slot.need_lazy_materialize) {
            slot_ids.emplace_back(slot.slot->id());
        }
    }
    std::sort(slot_ids.begin(), slot_ids.end());
    ASSERT_EQ(slot_ids, check_slot_ids);
}

void JoinHashMapTest::check_lazy_build_output_slot_ids(const JoinHashTableItems& table_items,
                                                       const std::vector<SlotId>& check_slot_ids) {
    ASSERT_EQ(table_items.lazy_output_build_column_count, check_slot_ids.size());
    std::vector<SlotId> slot_ids;
    for (const auto& slot : table_items.build_slots) {
        if (slot.need_lazy_materialize) {
            slot_ids.emplace_back(slot.slot->id());
        }
    }
    std::sort(slot_ids.begin(), slot_ids.end());
    ASSERT_EQ(slot_ids, check_slot_ids);
}

void JoinHashMapTest::check_not_output_slot_ids(const JoinHashTableItems& table_items,
                                                const std::vector<SlotId>& check_slot_ids) {
    std::vector<SlotId> slot_ids;
    for (const auto& slot : table_items.probe_slots) {
        if (!slot.need_output && !slot.need_lazy_materialize) {
            slot_ids.emplace_back(slot.slot->id());
        }
    }
    for (const auto& slot : table_items.build_slots) {
        if (!slot.need_output && !slot.need_lazy_materialize) {
            slot_ids.emplace_back(slot.slot->id());
        }
    }
    std::sort(slot_ids.begin(), slot_ids.end());
    ASSERT_EQ(slot_ids, check_slot_ids);
}

HashTableParam JoinHashMapTest::create_table_param(TJoinOp::type join_type, size_t output_col_cnt) {
    HashTableParam param;
    param.with_other_conjunct = false;
    param.join_type = join_type;
    param.search_ht_timer = ADD_TIMER(_runtime_profile, "SearchHashTableTime");
    param.output_build_column_timer = ADD_TIMER(_runtime_profile, "OutputBuildColumnTime");
    param.output_probe_column_timer = ADD_TIMER(_runtime_profile, "OutputProbeColumnTime");
    for (size_t i = 0; i < output_col_cnt; i++) {
        param.probe_output_slots.emplace(i);
        param.build_output_slots.emplace(i);
    }
    return param;
}

HashTableParam JoinHashMapTest::create_table_param_int(TJoinOp::type join_type, size_t output_col_cnt) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, output_col_cnt);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, output_col_cnt);
    _probe_desc = create_probe_desc(&row_desc_builder);
    _build_desc = create_build_desc(&row_desc_builder);

    HashTableParam param;
    param.with_other_conjunct = false;
    param.join_type = join_type;
    param.search_ht_timer = ADD_TIMER(_runtime_profile, "SearchHashTableTime");
    param.output_build_column_timer = ADD_TIMER(_runtime_profile, "OutputBuildColumnTime");
    param.output_probe_column_timer = ADD_TIMER(_runtime_profile, "OutputProbeColumnTime");
    for (size_t i = 0; i < output_col_cnt; i++) {
        param.probe_output_slots.emplace(i);
        param.build_output_slots.emplace(i);
    }
    param.build_row_desc = _build_desc.get();
    param.probe_row_desc = _probe_desc.get();
    param.probe_output_slots = {1};
    param.build_output_slots = {4};
    param.predicate_slots = {2, 5};
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    return param;
}

Buffer<uint8_t> JoinHashMapTest::create_bools(uint32_t count, int32_t flag) {
    Buffer<uint8_t> nulls(count);

    if (flag == 0) {
        // all 0
        for (uint32_t i = 0; i < count; i++) {
            nulls[i] = 0;
        }
        return nulls;
    }

    if (flag == 1) {
        // all 1
        for (uint32_t i = 0; i < count; i++) {
            nulls[i] = 1;
        }
        return nulls;
    }

    if (flag == 2) {
        // half 0
        for (uint32_t i = 0; i < count; i++) {
            nulls[i] = static_cast<uint8_t>(i % 2 == 0);
        }
        return nulls;
    }

    if (flag == 3) {
        // one third 0
        for (uint32_t i = 0; i < count; i++) {
            nulls[i] = static_cast<uint8_t>(i % 3 == 0);
        }
    }

    if (flag == 4) {
        for (uint32_t i = 0; i < count; i++) {
            nulls[i] = static_cast<uint8_t>((i % 3 == 0) || (i % 2 == 0));
        }
    }

    return nulls;
}

ColumnPtr JoinHashMapTest::create_column(LogicalType LT) {
    if (LT == LogicalType::TYPE_INT) {
        return FixedLengthColumn<int32_t>::create();
    }

    if (LT == LogicalType::TYPE_VARCHAR) {
        return BinaryColumn::create();
    }

    return nullptr;
}

ColumnPtr JoinHashMapTest::create_column(LogicalType LT, uint32_t start, uint32_t count) {
    if (LT == LogicalType::TYPE_INT) {
        auto column = FixedLengthColumn<int32_t>::create();
        for (auto i = 0; i < count; i++) {
            column->append_datum(Datum(static_cast<int32_t>(start + i)));
        }
        return column;
    }

    if (LT == LogicalType::TYPE_VARCHAR) {
        auto column = BinaryColumn::create();
        for (auto i = 0; i < count; i++) {
            column->append_string(std::to_string(start + i));
        }
        return column;
    }

    return nullptr;
}

ColumnPtr JoinHashMapTest::create_nullable_column(LogicalType LT) {
    if (LT == LogicalType::TYPE_INT) {
        auto data_column = FixedLengthColumn<int32_t>::create();
        auto null_column = FixedLengthColumn<uint8_t>::create();
        return NullableColumn::create(std::move(data_column), std::move(null_column));
    }

    if (LT == LogicalType::TYPE_VARCHAR) {
        auto data_column = BinaryColumn::create();
        auto null_column = FixedLengthColumn<uint8_t>::create();
        return NullableColumn::create(std::move(data_column), std::move(null_column));
    }

    return nullptr;
}

ColumnPtr JoinHashMapTest::create_nullable_column(LogicalType LT, const Buffer<uint8_t>& nulls, uint32_t start,
                                                  uint32_t count) {
    if (LT == LogicalType::TYPE_INT) {
        auto data_column = FixedLengthColumn<int32_t>::create();
        auto null_column = FixedLengthColumn<uint8_t>::create();
        for (auto i = 0; i < count; i++) {
            null_column->append_datum(Datum(static_cast<uint8_t>(nulls[i])));
            if (nulls[i] == 0) {
                data_column->append_datum(Datum(static_cast<int32_t>(start + i)));
            } else {
                data_column->append_default();
            }
        }
        return NullableColumn::create(std::move(data_column), std::move(null_column));
    }

    if (LT == LogicalType::TYPE_VARCHAR) {
        auto data_column = BinaryColumn::create();
        auto null_column = FixedLengthColumn<uint8_t>::create();
        for (auto i = 0; i < count; i++) {
            null_column->append_datum(Datum(static_cast<uint8_t>(nulls[i])));
            if (nulls[i] == 0) {
                data_column->append_string(std::to_string(start + i));
            } else {
                data_column->append_default();
            }
        }
        return NullableColumn::create(std::move(data_column), std::move(null_column));
    }

    return nullptr;
}

ColumnPtr JoinHashMapTest::create_int32_column(uint32_t row_count, uint32_t start_value) {
    const auto& int_type_desc = TypeDescriptor(LogicalType::TYPE_INT);
    MutableColumnPtr column = ColumnHelper::create_column(int_type_desc, false);
    for (int32_t i = 0; i < row_count; i++) {
        column->append_datum(Datum(static_cast<int32_t>(start_value) + i));
    }
    return column;
}

ColumnPtr JoinHashMapTest::create_binary_column(uint32_t row_count, uint32_t start_value) {
    const auto& varchar_type_desc = TypeDescriptor::create_varchar_type(TypeDescriptor::MAX_VARCHAR_LENGTH);
    MutableColumnPtr column = ColumnHelper::create_column(varchar_type_desc, false);
    auto* binary_column = ColumnHelper::as_raw_column<BinaryColumn>(column);
    for (int32_t i = 0; i < row_count; i++) {
        std::string str = std::to_string(start_value + i);
        Slice slice;
        slice.data = reinterpret_cast<char*>(_mem_pool->allocate(str.size()));
        slice.size = str.size();
        memcpy(slice.data, str.data(), str.size());
        binary_column->append(slice);
    }
    return column;
}

void JoinHashMapTest::check_probe_index(const Buffer<uint32_t>& probe_index, uint32_t step, uint32_t match_count,
                                        uint32_t probe_row_count) {
    uint32_t check_count;
    if (config::vector_chunk_size * (step + 1) <= probe_row_count * match_count) {
        check_count = config::vector_chunk_size;
    } else {
        check_count = probe_row_count * match_count - config::vector_chunk_size * step;
    }

    uint32_t start = config::vector_chunk_size * step;
    for (auto i = 0; i < check_count; i++) {
        ASSERT_EQ(probe_index[i], (start + i) / match_count);
    }
}

void JoinHashMapTest::check_build_slice(const Buffer<Slice>& slices, uint32_t row_count) {
    ASSERT_EQ(slices.size(), row_count + 1);
    ASSERT_EQ(slices[0], Slice());

    for (size_t i = 0; i < row_count; i++) {
        Buffer<uint8_t> buffer(1024);
        uint32_t offset = 0;

        // serialize int
        int32_t index = i;
        memcpy(buffer.data() + offset, &index, sizeof(int32_t));
        offset += sizeof(int32_t);

        // serialize varchar
        std::string str = std::to_string(index);
        uint32_t len = str.length();
        memcpy(buffer.data() + offset, &len, sizeof(uint32_t));
        offset += sizeof(uint32_t);
        memcpy(buffer.data() + offset, str.data(), len);
        offset += len;

        // check
        ASSERT_EQ(slices[index + 1], Slice(buffer.data(), offset));
    }
}

void JoinHashMapTest::check_build_slice(const Buffer<uint8_t>& nulls, const Buffer<Slice>& slices, uint32_t row_count) {
    ASSERT_EQ(slices.size(), row_count + 1);
    ASSERT_EQ(slices[0], Slice());

    for (size_t i = 0; i < row_count; i++) {
        Buffer<uint8_t> buffer(1024);
        uint32_t offset = 0;

        if (nulls[i] == 0) {
            // serialize int
            int32_t index = i;
            memcpy(buffer.data() + offset, &index, sizeof(int32_t));
            offset += sizeof(int32_t);

            // serialize varchar
            std::string str = std::to_string(index);
            uint32_t len = str.length();
            memcpy(buffer.data() + offset, &len, sizeof(uint32_t));
            offset += sizeof(uint32_t);
            memcpy(buffer.data() + offset, str.data(), len);
            offset += len;

            // check
            ASSERT_EQ(slices[index + 1], Slice(buffer.data(), offset));
        }
    }
}

void JoinHashMapTest::check_build_column(const Buffer<uint8_t>& nulls, const ColumnPtr& build_column,
                                         uint32_t row_count) {
    auto* column = ColumnHelper::as_raw_column<Int64Column>(build_column);
    const auto& data = column->get_data();

    ASSERT_EQ(column->size(), row_count + 1);
    ASSERT_EQ(data[0], 0);

    for (size_t i = 0; i < row_count; i++) {
        if (nulls[i] == 0) {
            int32_t index = i;
            int64_t check_value = 0;
            auto* ptr = reinterpret_cast<uint8_t*>(&check_value);
            memcpy(ptr, &index, 4);
            memcpy(ptr + 4, &index, 4);
            ASSERT_EQ(check_value, data[i + 1]);
        }
    }
}

void JoinHashMapTest::check_build_column(const ColumnPtr& build_column, uint32_t row_count) {
    auto* column = ColumnHelper::as_raw_column<Int64Column>(build_column);
    const auto& data = column->get_data();

    ASSERT_EQ(column->size(), row_count + 1);
    ASSERT_EQ(data[0], 0);

    for (size_t i = 0; i < row_count; i++) {
        int32_t index = i;
        int64_t check_value = 0;
        auto* ptr = reinterpret_cast<uint8_t*>(&check_value);
        memcpy(ptr, &index, 4);
        memcpy(ptr + 4, &index, 4);
        ASSERT_EQ(check_value, data[i + 1]);
    }
}

void JoinHashMapTest::check_build_index(const Buffer<uint32_t>& first, const Buffer<uint32_t>& next,
                                        uint32_t row_count) {
    ASSERT_EQ(first.size(), JoinHashMapHelper::calc_bucket_size(row_count));
    ASSERT_EQ(next.size(), row_count + 1);
    ASSERT_EQ(next[0], 0);

    Buffer<uint32_t> check_index(row_count + 1, 0);

    for (unsigned int item : first) {
        if (item == 0) {
            continue;
        }
        auto index = item;
        while (index != 0) {
            check_index[index]++;
            index = next[index];
        }
    }

    ASSERT_EQ(check_index[0], 0);
    for (auto i = 1; i < check_index.size(); i++) {
        ASSERT_EQ(check_index[i], 1);
    }
}

void JoinHashMapTest::check_build_index(const Buffer<uint8_t>& nulls, const Buffer<uint32_t>& first,
                                        const Buffer<uint32_t>& next, uint32_t row_count) {
    ASSERT_EQ(first.size(), JoinHashMapHelper::calc_bucket_size(row_count));
    ASSERT_EQ(next.size(), row_count + 1);
    ASSERT_EQ(next[0], 0);

    Buffer<uint32_t> check_index(row_count + 1, 0);

    for (unsigned int item : first) {
        if (item == 0) {
            continue;
        }
        auto index = item;
        while (index != 0) {
            check_index[index]++;
            index = next[index];
        }
    }

    ASSERT_EQ(check_index[0], 0);
    for (auto i = 0; i < row_count; i++) {
        if (nulls[i] == 1) {
            ASSERT_EQ(check_index[i + 1], 0);
        } else {
            ASSERT_EQ(check_index[i + 1], 1);
        }
    }
}

void JoinHashMapTest::check_build_index(const Buffer<uint32_t>& build_index, uint32_t step, uint32_t match_count,
                                        uint32_t probe_row_count) {
    uint32_t check_count = 0;
    if (config::vector_chunk_size * (step + 1) <= probe_row_count * match_count) {
        check_count = config::vector_chunk_size;
    } else {
        check_count = probe_row_count * match_count - config::vector_chunk_size * step;
    }

    uint32_t start = config::vector_chunk_size * step;
    for (auto i = 0; i < check_count; i++) {
        uint32_t quo = (start + i) / match_count;
        uint32_t rem = (start + i) % match_count;
        ASSERT_EQ(build_index[i], 1 + quo + config::vector_chunk_size * (match_count - 1 - rem));
    }
}

void JoinHashMapTest::check_match_index(const Buffer<uint32_t>& probe_match_index, uint32_t start, int32_t count,
                                        uint32_t match_count) {
    for (uint32_t i = 0; i < count / match_count; i++) {
        ASSERT_EQ(probe_match_index[i], match_count);
    }
    ASSERT_EQ(probe_match_index[count / match_count], count % match_count + 1);
}

void JoinHashMapTest::check_probe_state(const JoinHashTableItems& table_items, const HashTableProbeState& probe_state,
                                        JoinMatchFlag match_flag, uint32_t step, uint32_t match_count,
                                        uint32_t probe_row_count) {
    ASSERT_EQ(probe_state.match_flag, match_flag);
    ASSERT_EQ(probe_state.has_remain, (step + 1) * config::vector_chunk_size < probe_row_count * match_count);
    if (probe_row_count * match_count > (step + 1) * config::vector_chunk_size) {
        ASSERT_EQ(probe_state.count, config::vector_chunk_size);
        if (is_check_cur_row_match_count(table_items.join_type, table_items.with_other_conjunct)) {
            ASSERT_EQ(probe_state.cur_row_match_count, ((step + 1) * config::vector_chunk_size + 1) % match_count);
        } else {
            ASSERT_EQ(probe_state.cur_row_match_count, 0);
        }
        ASSERT_EQ(probe_state.cur_probe_index, (step + 1) * config::vector_chunk_size / match_count);
    } else {
        ASSERT_EQ(probe_state.count, probe_row_count * match_count - step * config::vector_chunk_size);
        ASSERT_EQ(probe_state.cur_row_match_count, 0);
        ASSERT_EQ(probe_state.cur_probe_index, 0);
    }
    check_probe_index(probe_state.probe_index, step, match_count, probe_row_count);
    check_build_index(probe_state.build_index, step, match_count, probe_row_count);
}

void JoinHashMapTest::prepare_build_data(Buffer<int32_t>* build_data, uint32_t batch_count) {
    build_data->resize(1 + batch_count * config::vector_chunk_size, 0);
    for (size_t i = 0; i < config::vector_chunk_size; i++) {
        for (size_t j = 0; j < batch_count; j++) {
            (*build_data)[1 + j * config::vector_chunk_size + i] = i;
        }
    }
}

void JoinHashMapTest::prepare_probe_data(Buffer<int32_t>* probe_data, uint32_t probe_row_count) {
    probe_data->resize(probe_row_count);
    for (size_t i = 0; i < probe_row_count; i++) {
        (*probe_data)[i] = i;
    }
}

bool JoinHashMapTest::is_check_cur_row_match_count(TJoinOp::type join_type, bool with_other_conjunct) {
    return join_type == TJoinOp::LEFT_OUTER_JOIN && with_other_conjunct;
}

void JoinHashMapTest::prepare_table_items(JoinHashTableItems* table_items, TJoinOp::type join_type,
                                          bool with_other_conjunct, uint32_t batch_count) {
    table_items->join_type = join_type;
    table_items->with_other_conjunct = with_other_conjunct;
    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);
    table_items->join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});
    table_items->next.resize(1 + batch_count * config::vector_chunk_size, 0);
    for (size_t i = 0; i < config::vector_chunk_size; i++) {
        (table_items->next)[1 + i] = 0;
        for (size_t j = 1; j < batch_count; j++) {
            (table_items->next)[1 + j * config::vector_chunk_size + i] = 1 + (j - 1) * config::vector_chunk_size + i;
        }
    }
}

void JoinHashMapTest::prepare_table_items(JoinHashTableItems* table_items, uint32_t row_count) {
    table_items->bucket_size = JoinHashMapHelper::calc_bucket_size(row_count);
    table_items->first.resize(table_items->bucket_size);
    table_items->row_count = row_count;
    table_items->next.resize(row_count + 1);
    table_items->build_pool = std::make_unique<MemPool>();
}

void JoinHashMapTest::prepare_probe_state(HashTableProbeState* probe_state, uint32_t probe_row_count) {
    probe_state->probe_row_count = probe_row_count;
    probe_state->cur_probe_index = 0;
    probe_state->probe_pool = std::make_unique<MemPool>();
    probe_state->search_ht_timer = ADD_TIMER(_runtime_profile, "SearchHashTableTime");
    probe_state->output_probe_column_timer = ADD_TIMER(_runtime_profile, "OutputProbeColumnTime");
    probe_state->build_index.resize(config::vector_chunk_size + 8);
    probe_state->probe_index.resize(config::vector_chunk_size + 8);
    probe_state->next.resize(config::vector_chunk_size);
    probe_state->probe_match_index.resize(config::vector_chunk_size);
    probe_state->build_match_index.resize(config::vector_chunk_size);
    probe_state->probe_match_filter.resize(config::vector_chunk_size);
    probe_state->buckets.resize(config::vector_chunk_size);
    probe_state->is_nulls.resize(config::vector_chunk_size);

    for (size_t i = 0; i < probe_row_count; i++) {
        probe_state->next[i] = 1 + 2 * config::vector_chunk_size + i;
    }
}

void JoinHashMapTest::check_int32_column(const Column& column, uint32_t row_count, uint32_t start_value) {
    ASSERT_EQ(column.size(), row_count);
    const auto& int_32_column = reinterpret_cast<const Int32Column&>(column);
    const auto& data = int_32_column.get_data();

    for (uint32_t i = 0; i < row_count; i++) {
        ASSERT_EQ(data[i], start_value + i);
    }
}

void JoinHashMapTest::check_probe_index_column(const Column& column, const std::vector<uint32_t>& idxs) {
    const auto& uint_32_column = reinterpret_cast<const UInt32Column&>(column);
    ASSERT_EQ(column.size(), idxs.size());
    for (size_t i = 0; i < idxs.size(); i++) {
        ASSERT_EQ(uint_32_column.get(i).get_uint32(), idxs[i]);
    }
}

ColumnPtr JoinHashMapTest::create_int32_nullable_column(uint32_t row_count, uint32_t start_value) {
    const auto& int_type_desc = TypeDescriptor(LogicalType::TYPE_INT);
    MutableColumnPtr data_column = ColumnHelper::create_column(int_type_desc, false);
    NullColumn::MutablePtr null_column = NullColumn::create();
    for (int32_t i = 0; i < row_count; i++) {
        if ((start_value + i) % 2 == 0) {
            data_column->append_datum(static_cast<int32_t>(start_value + i));
            null_column->append(0);
        } else {
            data_column->append_default();
            null_column->append(1);
        }
    }
    return NullableColumn::create(std::move(data_column), std::move(null_column));
}

void JoinHashMapTest::check_binary_column(const ColumnPtr& column, uint32_t row_count, uint32_t start_value) {
    auto* binary_column = ColumnHelper::as_raw_column<BinaryColumn>(column);
    auto& data = binary_column->get_data();

    for (uint32_t i = 0; i < row_count; i++) {
        std::string str = std::to_string(start_value + i);
        Slice check_slice;
        check_slice.data = str.data();
        check_slice.size = str.size();
        ASSERT_TRUE(check_slice == data[i]);
    }
}

void JoinHashMapTest::check_null_column(const ColumnPtr& column, uint32_t row_count) {
    ASSERT_EQ(column->size(), row_count);
    for (size_t i = 0; i < row_count; i++) {
        ASSERT_TRUE(column->is_null(i));
    }
}

void JoinHashMapTest::check_int32_nullable_column(const Column& column, uint32_t row_count, uint32_t start_value) {
    const auto& nullable_column = reinterpret_cast<const NullableColumn&>(column);
    const auto& data_column = nullable_column.data_column();
    const auto& data = reinterpret_cast<const Int32Column&>(*data_column).get_data();
    const auto& null_column = nullable_column.null_column();
    auto& null_data = null_column->get_data();

    uint32_t index = 0;
    for (uint32_t i = 0; i < row_count; i++) {
        if ((start_value + i) % 2 == 0) {
            ASSERT_EQ(data[index], start_value + i);
            ASSERT_EQ(null_data[index], 0);
            index++;
        }
    }
    ASSERT_EQ(index, data.size());
    ASSERT_EQ(index, null_data.size());
}

ChunkPtr JoinHashMapTest::create_int32_probe_chunk(uint32_t count, uint32_t start_value, bool nullable) {
    auto chunk = std::make_shared<Chunk>();
    if (!nullable) {
        chunk->append_column(create_int32_column(count, start_value), 0);
        chunk->append_column(create_int32_column(count, start_value + 10), 1);
        chunk->append_column(create_int32_column(count, start_value + 20), 2);
    } else {
        chunk->append_column(create_int32_nullable_column(count, start_value), 0);
        chunk->append_column(create_int32_nullable_column(count, start_value + 10), 1);
        chunk->append_column(create_int32_nullable_column(count, start_value + 20), 2);
    }
    return chunk;
}

ChunkPtr JoinHashMapTest::create_binary_probe_chunk(uint32_t count, uint32_t start_value, bool nullable) {
    auto chunk = std::make_shared<Chunk>();
    if (!nullable) {
        chunk->append_column(create_binary_column(count, start_value), 0);
        chunk->append_column(create_binary_column(count, start_value + 10), 1);
        chunk->append_column(create_binary_column(count, start_value + 20), 2);
    } else {
        //TODO:
    }
    return chunk;
}

ChunkPtr JoinHashMapTest::create_int32_build_chunk(uint32_t count, uint32_t start_value, bool nullable) {
    auto chunk = std::make_shared<Chunk>();
    if (!nullable) {
        chunk->append_column(create_int32_column(count, start_value), 3);
        chunk->append_column(create_int32_column(count, start_value + 10), 4);
        chunk->append_column(create_int32_column(count, start_value + 20), 5);
    } else {
        chunk->append_column(create_int32_nullable_column(count, start_value), 3);
        chunk->append_column(create_int32_nullable_column(count, start_value + 10), 4);
        chunk->append_column(create_int32_nullable_column(count, start_value + 20), 5);
    }
    return chunk;
}

ChunkPtr JoinHashMapTest::create_binary_build_chunk(uint32_t count, bool nullable) {
    auto chunk = std::make_shared<Chunk>();
    if (!nullable) {
        chunk->append_column(create_binary_column(count, 0), 3);
        chunk->append_column(create_binary_column(count, 10), 4);
        chunk->append_column(create_binary_column(count, 20), 5);
    } else {
        //TODO: implement
    }
    return chunk;
}

// Check probe chunk's result for empty hash table with different join type.
void JoinHashMapTest::check_empty_hash_map(TJoinOp::type join_type, int num_probe_rows, int32_t expect_num_rows,
                                           int32_t expect_num_colums) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(join_type, 6);
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    JoinHashTable hash_table;
    hash_table.create(param);

    ASSERT_TRUE(hash_table.build(_runtime_state.get()).ok());

    auto probe_chunk = create_int32_probe_chunk(num_probe_rows, 1, false);
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    Columns probe_key_columns;
    bool eos = false;
    ASSERT_TRUE(hash_table.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos).ok());

    ASSERT_EQ(result_chunk->num_rows(), expect_num_rows);
    ASSERT_EQ(result_chunk->num_columns(), expect_num_colums);
    if (expect_num_rows > 0 && expect_num_colums > 0) {
        // check probe's output column
        for (int i = 0; i < 3; i++) {
            if (result_chunk->columns()[i]->is_nullable()) {
                check_int32_column(*ColumnHelper::get_data_column(result_chunk->columns()[i].get()), num_probe_rows,
                                   i * 10 + 1);
            } else {
                check_int32_column(*result_chunk->columns()[i], num_probe_rows, i * 10 + 1);
            }
        }
        if (expect_num_colums > 3) {
            // check build's output column
            for (int i = 3; i < 6; i++) {
                check_null_column(result_chunk->columns()[i], num_probe_rows);
            }
        }
    }
}

TSlotDescriptor JoinHashMapTest::create_slot_descriptor(const std::string& column_name, LogicalType column_type,
                                                        int32_t column_pos, bool nullable) {
    TSlotDescriptorBuilder slot_desc_builder;
    if (column_type == LogicalType::TYPE_VARCHAR) {
        return slot_desc_builder.string_type(255)
                .column_name(column_name)
                .column_pos(column_pos)
                .nullable(nullable)
                .build();
    }
    return slot_desc_builder.type(column_type)
            .column_name(column_name)
            .column_pos(column_pos)
            .nullable(nullable)
            .build();
}

void JoinHashMapTest::add_tuple_descriptor(TDescriptorTableBuilder* table_desc_builder, LogicalType column_type,
                                           bool nullable, size_t column_count) {
    TTupleDescriptorBuilder tuple_desc_builder;
    for (size_t i = 0; i < column_count; i++) {
        auto slot = create_slot_descriptor("c" + std::to_string(i), column_type, i, nullable);
        tuple_desc_builder.add_slot(slot);
    }
    tuple_desc_builder.build(table_desc_builder);
}

std::shared_ptr<RuntimeProfile> JoinHashMapTest::create_runtime_profile() {
    auto profile = std::make_shared<RuntimeProfile>("test");
    profile->set_metadata(1);
    return profile;
}

std::shared_ptr<RowDescriptor> JoinHashMapTest::create_row_desc(TDescriptorTableBuilder* table_desc_builder) {
    std::vector<TTupleId> row_tuples = std::vector<TTupleId>{0, 1};
    DescriptorTbl* tbl = nullptr;
    CHECK(DescriptorTbl::create(_runtime_state.get(), _object_pool.get(), table_desc_builder->desc_tbl(), &tbl,
                                config::vector_chunk_size)
                  .ok());

    return std::make_shared<RowDescriptor>(*tbl, row_tuples);
}

std::shared_ptr<RowDescriptor> JoinHashMapTest::create_probe_desc(TDescriptorTableBuilder* probe_desc_builder) {
    std::vector<TTupleId> row_tuples = std::vector<TTupleId>{0};
    DescriptorTbl* tbl = nullptr;
    CHECK(DescriptorTbl::create(_runtime_state.get(), _object_pool.get(), probe_desc_builder->desc_tbl(), &tbl,
                                config::vector_chunk_size)
                  .ok());

    return std::make_shared<RowDescriptor>(*tbl, row_tuples);
}

std::shared_ptr<RowDescriptor> JoinHashMapTest::create_build_desc(TDescriptorTableBuilder* build_desc_builder) {
    std::vector<TTupleId> row_tuples = std::vector<TTupleId>{1};
    DescriptorTbl* tbl = nullptr;
    CHECK(DescriptorTbl::create(_runtime_state.get(), _object_pool.get(), build_desc_builder->desc_tbl(), &tbl,
                                config::vector_chunk_size)
                  .ok());

    return std::make_shared<RowDescriptor>(*tbl, row_tuples);
}

std::shared_ptr<RuntimeState> JoinHashMapTest::create_runtime_state() {
    TUniqueId fragment_id;
    TQueryOptions query_options;
    query_options.batch_size = config::vector_chunk_size;
    TQueryGlobals query_globals;
    auto runtime_state = std::make_shared<RuntimeState>(fragment_id, query_options, query_globals, nullptr);
    runtime_state->init_instance_mem_tracker();
    return runtime_state;
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, JoinKeyHash) {
    const uint32_t num_buckets = 1 << 16;
    const uint32_t log_num_buckets = 16;

    auto get_hash_statistics = [&](const std::vector<uint32_t>& count_per_bucket) {
        uint32_t min_count = *std::ranges::min_element(count_per_bucket);
        uint32_t max_count = *std::ranges::max_element(count_per_bucket);

        return std::make_tuple(min_count, max_count);
    };

    {
        std::vector<uint32_t> count_per_bucket(num_buckets);
        for (int32_t i = 0; i < num_buckets * 3 * 5; i += 3) {
            auto bucket = JoinKeyHash<int32_t>()(i, num_buckets, log_num_buckets);
            count_per_bucket[bucket]++;
        }
        auto [min_count, max_count] = get_hash_statistics(count_per_bucket);
        // crc32: 0, 11
        ASSERT_EQ(0, min_count);
        ASSERT_EQ(11, max_count);
    }

    {
        std::vector<uint32_t> count_per_bucket(num_buckets);
        for (int32_t i = 0; i < num_buckets * 7 * 5; i += 7) {
            auto bucket = JoinKeyHash<int32_t>()(i, num_buckets, log_num_buckets);
            count_per_bucket[bucket]++;
        }
        auto [min_count, max_count] = get_hash_statistics(count_per_bucket);
        // crc32: 0, 14
        ASSERT_EQ(0, min_count);
        ASSERT_EQ(14, max_count);
    }

    {
        std::vector<uint32_t> count_per_bucket(num_buckets);
        for (int32_t i = 0; i < num_buckets * 5; i++) {
            auto bucket = JoinKeyHash<int32_t>()(i, num_buckets, log_num_buckets);
            count_per_bucket[bucket]++;
        }
        auto [min_count, max_count] = get_hash_statistics(count_per_bucket);
        // crc32: 4, 6
        ASSERT_EQ(4, min_count);
        ASSERT_EQ(6, max_count);
    }

    {
        std::vector<uint32_t> count_per_bucket(num_buckets);
        for (int32_t i = 0; i < num_buckets * 3 * 5; i += 3) {
            auto bucket = JoinKeyHash<int64_t>()(i, num_buckets, log_num_buckets);
            count_per_bucket[bucket]++;
        }
        auto [min_count, max_count] = get_hash_statistics(count_per_bucket);
        // crc32: 0, 12
        ASSERT_EQ(3, min_count);
        ASSERT_EQ(7, max_count);
    }

    {
        std::vector<uint32_t> count_per_bucket(num_buckets);
        for (int32_t i = 0; i < num_buckets * 7 * 5; i += 7) {
            auto bucket = JoinKeyHash<int64_t>()(i, num_buckets, log_num_buckets);
            count_per_bucket[bucket]++;
        }
        auto [min_count, max_count] = get_hash_statistics(count_per_bucket);
        // crc32: 0, 15
        ASSERT_EQ(4, min_count);
        ASSERT_EQ(7, max_count);
    }

    {
        std::vector<uint32_t> count_per_bucket(num_buckets);
        for (int32_t i = 0; i < num_buckets * 5; i++) {
            auto bucket = JoinKeyHash<int64_t>()(i, num_buckets, log_num_buckets);
            count_per_bucket[bucket]++;
        }
        auto [min_count, max_count] = get_hash_statistics(count_per_bucket);
        // crc32: 5, 5
        ASSERT_EQ(4, min_count);
        ASSERT_EQ(6, max_count);
    }

    auto v3 = JoinKeyHash<Slice>()(Slice{"abcd", 4}, num_buckets, log_num_buckets);
    ASSERT_EQ(v3, 11538);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, CalcBucketNum) {
    const uint32_t num_buckets = 1 << 2;
    const uint32_t log_num_buckets = 2;
    uint32_t bucket_num = JoinHashMapHelper::calc_bucket_num(1, num_buckets, log_num_buckets);
    ASSERT_EQ(2, bucket_num);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, CalcBucketNums) {
    const uint32_t num_buckets = 1 << 2;
    const uint32_t log_num_buckets = 2;

    Buffer<int32_t> data{1, 2, 3, 4};
    Buffer<uint32_t> buckets{0, 0, 0, 0};
    Buffer<uint32_t> check_buckets{2, 0, 3, 1};

    JoinHashMapHelper::calc_bucket_nums<int32_t>(data, num_buckets, log_num_buckets, &buckets, 0, 4);
    for (size_t i = 0; i < buckets.size(); i++) {
        ASSERT_EQ(buckets[i], check_buckets[i]);
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, GetHashKey) {
    auto c1 = JoinHashMapTest::create_int32_column(2, 0);
    auto c2 = JoinHashMapTest::create_int32_column(2, 2);
    Columns columns{c1, c2};
    Buffer<uint8_t> buffer(1024);

    auto slice = JoinHashMapHelper::get_hash_key(columns, 0, buffer.data());
    ASSERT_EQ(slice.size, 8);
    const auto* ptr = reinterpret_cast<const int32_t*>(slice.data);
    ASSERT_EQ(ptr[0], 0);
    ASSERT_EQ(ptr[1], 2);
    ASSERT_EQ(ptr[2], 0);
    ASSERT_EQ(ptr[3], 0);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, CompileFixedSizeKeyColumn) {
    auto type = TypeDescriptor::from_logical_type(LogicalType::TYPE_BIGINT);
    auto data_column = ColumnHelper::create_column(type, false);
    data_column->resize(2);

    auto c1 = JoinHashMapTest::create_int32_column(2, 0);
    auto c2 = JoinHashMapTest::create_int32_column(2, 2);
    Columns columns{c1, c2};

    JoinHashMapHelper::serialize_fixed_size_key_column<LogicalType::TYPE_BIGINT>(columns, data_column.get(), 0, 2);

    auto* c3 = ColumnHelper::as_raw_column<Int64Column>(data_column);
    ASSERT_EQ(c3->get_data()[0], 8589934592l);
    ASSERT_EQ(c3->get_data()[1], 12884901889l);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeNullOutput) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    table_items.probe_column_count = 3;

    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    auto row_desc = create_row_desc(&row_desc_builder);
    vector<HashTableSlotDescriptor> hash_table_slot_vec;
    for (auto& slot : row_desc->tuple_descriptors()[0]->slots()) {
        HashTableSlotDescriptor hash_table_slot{};
        hash_table_slot.slot = slot;
        hash_table_slot.need_output = true;
        hash_table_slot_vec.emplace_back(hash_table_slot);
    }
    table_items.probe_slots = hash_table_slot_vec;

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);

    auto chunk = std::make_shared<Chunk>();
    join_hash_map->_probe_null_output<false>(&chunk, 2);

    ASSERT_EQ(chunk->num_columns(), 3);

    for (size_t i = 0; i < chunk->num_columns(); i++) {
        auto null_column = ColumnHelper::as_raw_column<NullableColumn>(chunk->columns()[i])->null_column();
        for (size_t j = 0; j < 2; j++) {
            ASSERT_EQ(null_column->get_data()[j], 1);
        }
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildDefaultOutput) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    table_items.build_column_count = 3;

    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    auto row_desc = create_row_desc(&row_desc_builder);

    vector<HashTableSlotDescriptor> hash_table_slot_vec;
    for (auto& slot : row_desc->tuple_descriptors()[0]->slots()) {
        HashTableSlotDescriptor hash_table_slot{};
        hash_table_slot.slot = slot;
        hash_table_slot.need_output = true;
        hash_table_slot_vec.emplace_back(hash_table_slot);
    }
    table_items.build_slots = hash_table_slot_vec;

    auto chunk = std::make_shared<Chunk>();
    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
    join_hash_map->_build_default_output(&chunk, 2);

    ASSERT_EQ(chunk->num_columns(), 3);

    for (size_t i = 0; i < chunk->num_columns(); i++) {
        auto null_column = ColumnHelper::as_raw_column<NullableColumn>(chunk->columns()[i])->null_column();
        for (size_t j = 0; j < 2; j++) {
            ASSERT_EQ(null_column->get_data()[j], 1);
        }
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, JoinBuildProbeFunc) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    auto type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);
    auto build_column = ColumnHelper::create_column(type, false);
    build_column->append_default();
    build_column->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    auto probe_column = JoinHashMapTest::create_int32_column(10, 0);
    table_items.first.resize(16, 0);
    table_items.key_columns.emplace_back(std::move(build_column));
    table_items.bucket_size = 16;
    table_items.row_count = 10;
    table_items.next.resize(11);
    probe_state.probe_row_count = 10;
    probe_state.buckets.resize(config::vector_chunk_size);
    probe_state.next.resize(config::vector_chunk_size, 0);
    Columns probe_columns{probe_column};
    probe_state.key_columns = &probe_columns;

    using BuildKeyConstructor = BuildKeyConstructorForOneKey<LogicalType::TYPE_INT>;
    using ProbeKeyConstructor = ProbeKeyConstructorForOneKey<LogicalType::TYPE_INT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_INT>;

    BuildKeyConstructor::prepare(nullptr, &table_items);
    BuildKeyConstructor::build_key(nullptr, &table_items);
    JoinHashMapMethod::build_prepare(nullptr, &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    ProbeKeyConstructor::prepare(nullptr, &probe_state);
    ProbeKeyConstructor::build_key(table_items, &probe_state);
    JoinHashMapMethod::lookup_init(table_items, &probe_state, ProbeKeyConstructor().get_key_data(probe_state),
                                   probe_state.null_array);

    for (size_t i = 0; i < 10; i++) {
        size_t found_count = 0;
        size_t probe_index = probe_state.next[i];
        auto data = ColumnHelper::as_raw_column<Int32Column>(table_items.key_columns[0])->get_data();
        while (probe_index != 0) {
            if (i == data[probe_index]) {
                found_count++;
            }
            probe_index = table_items.next[probe_index];
        }
        ASSERT_EQ(found_count, 1);
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, JoinBuildProbeFuncNullable) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    auto type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);
    auto build_column = ColumnHelper::create_column(type, true);
    build_column->append_default();
    build_column->append(*JoinHashMapTest::create_int32_nullable_column(10, 0), 0, 10);
    auto probe_column = JoinHashMapTest::create_int32_nullable_column(10, 0);
    table_items.first.resize(16, 0);
    table_items.key_columns.emplace_back(std::move(build_column));
    table_items.bucket_size = 16;
    table_items.row_count = 10;
    table_items.next.resize(11);
    probe_state.probe_row_count = 10;
    probe_state.buckets.resize(config::vector_chunk_size);
    probe_state.next.resize(config::vector_chunk_size, 0);
    Columns probe_columns{probe_column};
    probe_state.key_columns = &probe_columns;

    using BuildKeyConstructor = BuildKeyConstructorForOneKey<LogicalType::TYPE_INT>;
    using ProbeKeyConstructor = ProbeKeyConstructorForOneKey<LogicalType::TYPE_INT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_INT>;

    BuildKeyConstructor::prepare(nullptr, &table_items);
    BuildKeyConstructor::build_key(nullptr, &table_items);
    JoinHashMapMethod::build_prepare(nullptr, &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    ProbeKeyConstructor::prepare(nullptr, &probe_state);
    ProbeKeyConstructor::build_key(table_items, &probe_state);
    JoinHashMapMethod::lookup_init(table_items, &probe_state, ProbeKeyConstructor().get_key_data(probe_state),
                                   probe_state.null_array);

    for (size_t i = 0; i < 10; i++) {
        size_t found_count = 0;
        size_t probe_index = probe_state.next[i];
        auto data_column = ColumnHelper::as_raw_column<NullableColumn>(table_items.key_columns[0])->data_column();
        auto data = ColumnHelper::as_raw_column<Int32Column>(data_column)->get_data();
        while (probe_index != 0) {
            if (i == data[probe_index]) {
                found_count++;
            }
            probe_index = table_items.next[probe_index];
        }
        if (i % 2 == 1) {
            ASSERT_EQ(found_count, 0);
        } else {
            ASSERT_EQ(found_count, 1);
        }
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, DirectMappingJoinBuildProbeFunc) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_TINYINT, false, 1);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_TINYINT, false, 1);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(TJoinOp::INNER_JOIN, 2);
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.join_keys.emplace_back(JoinKeyDesc{&_tinyint_type, false, nullptr});

    JoinHashTable ht;

    // build chunk
    auto build_chunk = std::make_shared<Chunk>();
    auto build_column = Int8Column::create();
    down_cast<Int8Column*>(build_column.get())->append({-5, -3, -1, 0, 1, 3, 5});
    build_chunk->append_column(std::move(build_column), 1);

    // probe chunk
    auto probe_chunk = std::make_shared<Chunk>();
    auto probe_column = Int8Column::create();
    down_cast<Int8Column*>(probe_column.get())->append({-8, -5, 0, 1, 2, 3, 4, 5});
    probe_chunk->append_column(probe_column->clone(), 0);
    Columns probe_key_columns = {probe_column->clone()};

    // result chunk
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    bool eos = false;

    // build and probe
    ht.create(param);
    Columns key_columns{build_chunk->columns()[0]};
    ht.append_chunk(build_chunk, key_columns);
    (void)ht.build(_runtime_state.get());
    (void)ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos);

    // check
    ASSERT_EQ(result_chunk->columns().size(), 2);
    auto result_data = down_cast<Int8Column*>(result_chunk->get_column_by_slot_id(1).get())->get_data();
    std::sort(result_data.begin(), result_data.end());
    Buffer<int8_t> check_data = {-5, 0, 1, 3, 5};
    ASSERT_TRUE(result_data == check_data);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, DirectMappingJoinBuildProbeFuncNullable) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_TINYINT, true, 1);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_TINYINT, true, 1);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(TJoinOp::INNER_JOIN, 2);
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.join_keys.emplace_back(JoinKeyDesc{&_tinyint_type, false, nullptr});

    JoinHashTable ht;

    // build chunk
    auto build_chunk = std::make_shared<Chunk>();
    auto build_data_column = FixedLengthColumn<int8_t>::create();
    auto build_null_column = NullColumn::create();
    build_data_column->append({-5, 0, 0, 0, 1, 3, 5});
    build_null_column->append({0, 1, 0, 1, 0, 0, 0});
    auto build_column = NullableColumn::create(std::move(build_data_column), std::move(build_null_column));
    build_chunk->append_column(std::move(build_column), 1);

    // probe chunk
    auto probe_chunk = std::make_shared<Chunk>();
    auto probe_data_column = FixedLengthColumn<int8_t>::create();
    auto probe_null_column = NullColumn::create();
    probe_data_column->append({-5, 0, 0, 0, 3, 0, 5, 0});
    probe_null_column->append({0, 1, 0, 1, 0, 1, 0, 1});
    ColumnPtr probe_column = NullableColumn::create(std::move(probe_data_column), std::move(probe_null_column));
    probe_chunk->append_column(probe_column, 0);
    Columns probe_key_columns = {probe_column};

    // result chunk
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    bool eos = false;

    // build and probe
    ht.create(param);
    Columns key_columns{build_chunk->columns()[0]};
    ht.append_chunk(build_chunk, key_columns);
    (void)ht.build(_runtime_state.get());
    (void)ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos);

    // check
    ASSERT_EQ(result_chunk->columns().size(), 2);
    auto* result_column = down_cast<NullableColumn*>(result_chunk->get_column_by_slot_id(1).get());
    auto* result_data_column = down_cast<Int8Column*>(result_column->data_column().get());
    auto* result_null_column = down_cast<UInt8Column*>(result_column->null_column().get());
    auto result_data = result_data_column->get_data();
    auto result_null = result_null_column->get_data();
    std::sort(result_data.begin(), result_data.end());
    std::sort(result_null.begin(), result_null.end());
    Buffer<int8_t> check_data = {-5, 0, 3, 5};
    Buffer<uint8_t> check_null = {0, 0, 0, 0};
    ASSERT_TRUE(result_data == check_data);
    ASSERT_TRUE(result_null == check_null);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, FixedSizeJoinBuildProbeFunc) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    auto build_column1 = ColumnHelper::create_column(_int_type, false);
    build_column1->append_default();
    build_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);

    auto build_column2 = ColumnHelper::create_column(_int_type, false);
    build_column2->append_default();
    build_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);

    auto probe_column1 = JoinHashMapTest::create_int32_column(10, 0);
    auto probe_column2 = JoinHashMapTest::create_int32_column(10, 100);

    table_items.first.resize(16, 0);
    table_items.key_columns.emplace_back(std::move(build_column1));
    table_items.key_columns.emplace_back(std::move(build_column2));
    table_items.bucket_size = 16;
    table_items.row_count = 10;
    table_items.next.resize(11);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    probe_state.probe_row_count = 10;
    probe_state.buckets.resize(config::vector_chunk_size);
    probe_state.next.resize(config::vector_chunk_size, 0);
    Columns probe_columns{probe_column1, probe_column2};
    probe_state.key_columns = &probe_columns;

    using BuildKeyConstructor = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using ProbeKeyConstructor = ProbeKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_BIGINT>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    ProbeKeyConstructor::prepare(_runtime_state.get(), &probe_state);
    ProbeKeyConstructor::build_key(table_items, &probe_state);
    JoinHashMapMethod::lookup_init(table_items, &probe_state, ProbeKeyConstructor().get_key_data(probe_state),
                                   probe_state.null_array);

    for (size_t i = 0; i < 10; i++) {
        size_t found_count = 0;
        size_t probe_index = probe_state.next[i];
        auto* data_column = ColumnHelper::as_raw_column<Int64Column>(table_items.build_key_column);
        auto data = data_column->get_data();
        while (probe_index != 0) {
            if ((100 + i) * (1ul << 32u) + i == data[probe_index]) {
                found_count++;
            }
            probe_index = table_items.next[probe_index];
        }
        ASSERT_EQ(found_count, 1);
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, FixedSizeJoinBuildProbeFuncNullable) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    auto build_column1 = ColumnHelper::create_column(_int_type, true);
    build_column1->append_default();
    build_column1->append(*JoinHashMapTest::create_int32_nullable_column(10, 0), 0, 10);

    auto build_column2 = ColumnHelper::create_column(_int_type, true);
    build_column2->append_default();
    build_column2->append(*JoinHashMapTest::create_int32_nullable_column(10, 100), 0, 10);

    auto probe_column1 = JoinHashMapTest::create_int32_nullable_column(10, 0);
    auto probe_column2 = JoinHashMapTest::create_int32_nullable_column(10, 100);

    table_items.first.resize(16, 0);
    table_items.key_columns.emplace_back(std::move(build_column1));
    table_items.key_columns.emplace_back(std::move(build_column2));
    table_items.bucket_size = 16;
    table_items.row_count = 10;
    table_items.next.resize(11);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    probe_state.probe_row_count = 10;
    probe_state.buckets.resize(config::vector_chunk_size);
    probe_state.next.resize(config::vector_chunk_size, 0);
    Columns probe_columns{probe_column1, probe_column2};
    probe_state.key_columns = &probe_columns;

    using BuildKeyConstructor = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using ProbeKeyConstructor = ProbeKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_BIGINT>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    ProbeKeyConstructor::prepare(_runtime_state.get(), &probe_state);
    ProbeKeyConstructor::build_key(table_items, &probe_state);
    JoinHashMapMethod::lookup_init(table_items, &probe_state, ProbeKeyConstructor().get_key_data(probe_state),
                                   probe_state.null_array);

    for (size_t i = 0; i < 10; i++) {
        size_t found_count = 0;
        size_t probe_index = probe_state.next[i];
        auto* data_column = ColumnHelper::as_raw_column<Int64Column>(table_items.build_key_column);
        auto data = data_column->get_data();
        while (probe_index != 0) {
            if ((100 + i) * (1ul << 32ul) + i == data[probe_index]) {
                found_count++;
            }
            probe_index = table_items.next[probe_index];
        }
        if (i % 2 == 0) {
            ASSERT_EQ(found_count, 1);
        } else {
            ASSERT_EQ(found_count, 0);
        }
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, SerializedJoinBuildProbeFunc) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    auto build_column1 = ColumnHelper::create_column(_int_type, true);
    build_column1->append_default();
    build_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);

    auto build_column2 = ColumnHelper::create_column(_int_type, true);
    build_column2->append_default();
    build_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);

    auto probe_column1 = JoinHashMapTest::create_int32_column(10, 0);
    auto probe_column2 = JoinHashMapTest::create_int32_column(10, 100);

    table_items.first.resize(16, 0);
    table_items.key_columns.emplace_back(std::move(build_column1));
    table_items.key_columns.emplace_back(std::move(build_column2));
    table_items.bucket_size = 16;
    table_items.row_count = 10;
    table_items.next.resize(11);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    table_items.build_pool = std::make_unique<MemPool>();
    probe_state.probe_pool = std::make_unique<MemPool>();
    probe_state.probe_row_count = 10;
    probe_state.buckets.resize(config::vector_chunk_size);
    probe_state.next.resize(config::vector_chunk_size, 0);
    Columns probe_columns{probe_column1, probe_column2};
    probe_state.key_columns = &probe_columns;
    Buffer<uint8_t> buffer(1024);

    using BuildKeyConstructor = BuildKeyConstructorForSerialized;
    using ProbeKeyConstructor = ProbeKeyConstructorForSerialized;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_VARCHAR>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    ProbeKeyConstructor::prepare(_runtime_state.get(), &probe_state);
    ProbeKeyConstructor::build_key(table_items, &probe_state);
    JoinHashMapMethod::lookup_init(table_items, &probe_state, ProbeKeyConstructor().get_key_data(probe_state),
                                   probe_state.null_array);

    for (size_t i = 0; i < 10; i++) {
        size_t found_count = 0;
        size_t probe_index = probe_state.next[i];
        auto data = table_items.build_slice;
        while (probe_index != 0) {
            if (JoinHashMapHelper::get_hash_key(*probe_state.key_columns, i, buffer.data()) == data[probe_index]) {
                found_count++;
            }
            probe_index = table_items.next[probe_index];
        }
        ASSERT_EQ(found_count, 1);
    }
    table_items.build_pool.reset();
    probe_state.probe_pool.reset();
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, SerializedJoinBuildProbeFuncNullable) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    auto build_column1 = ColumnHelper::create_column(_int_type, true);
    build_column1->append_default();
    build_column1->append(*JoinHashMapTest::create_int32_nullable_column(10, 0), 0, 10);

    auto build_column2 = ColumnHelper::create_column(_int_type, true);
    build_column2->append_default();
    build_column2->append(*JoinHashMapTest::create_int32_nullable_column(10, 100), 0, 10);

    auto probe_column1 = JoinHashMapTest::create_int32_nullable_column(10, 0);
    auto probe_column2 = JoinHashMapTest::create_int32_nullable_column(10, 100);

    table_items.first.resize(16, 0);
    table_items.key_columns.emplace_back(std::move(build_column1));
    table_items.key_columns.emplace_back(std::move(build_column2));
    table_items.bucket_size = 16;
    table_items.row_count = 10;
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    table_items.next.resize(11);
    table_items.build_pool = std::make_unique<MemPool>();
    probe_state.probe_pool = std::make_unique<MemPool>();
    probe_state.probe_row_count = 10;
    probe_state.buckets.resize(config::vector_chunk_size);
    probe_state.next.resize(config::vector_chunk_size, 0);
    Columns probe_columns{probe_column1, probe_column2};
    probe_state.key_columns = &probe_columns;
    Buffer<uint8_t> buffer(1024);

    using BuildKeyConstructor = BuildKeyConstructorForSerialized;
    using ProbeKeyConstructor = ProbeKeyConstructorForSerialized;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_VARCHAR>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    ProbeKeyConstructor::prepare(_runtime_state.get(), &probe_state);
    ProbeKeyConstructor::build_key(table_items, &probe_state);
    JoinHashMapMethod::lookup_init(table_items, &probe_state, ProbeKeyConstructor().get_key_data(probe_state),
                                   probe_state.null_array);

    Columns probe_data_columns;
    probe_data_columns.emplace_back(
            ColumnHelper::as_raw_column<NullableColumn>((*probe_state.key_columns)[0])->data_column());
    probe_data_columns.emplace_back(
            ColumnHelper::as_raw_column<NullableColumn>((*probe_state.key_columns)[1])->data_column());

    for (size_t i = 0; i < 10; i++) {
        size_t found_count = 0;
        size_t probe_index = probe_state.next[i];
        auto data = table_items.build_slice;
        while (probe_index != 0) {
            auto probe_slice = JoinHashMapHelper::get_hash_key(probe_data_columns, i, buffer.data());
            if (probe_slice == data[probe_index]) {
                found_count++;
            }
            probe_index = table_items.next[probe_index];
        }
        if (i % 2 == 0) {
            ASSERT_EQ(found_count, 1);
        } else {
            ASSERT_EQ(found_count, 0);
        }
    }
    table_items.build_pool.reset();
    probe_state.probe_pool.reset();
}

#define DO_TEST_PROBE(FUNC, FIRST, INIT)                                                                       \
    for (auto& group : {2, 1, 0}) {                                                                            \
        probe_state.probe_index.assign(4096 + 8, 0);                                                           \
        probe_state.build_index.assign(4096 + 8, 0);                                                           \
        probe_state.probe_match_index.assign(4096 + 8, 0);                                                     \
        if (group == 0) {                                                                                      \
            join_hash_map->FUNC<FIRST, false>(_runtime_state.get(), build_data, probe_data);                   \
        } else {                                                                                               \
            probe_state.handles.clear();                                                                       \
            for (int i = 0; i < group; ++i) {                                                                  \
                probe_state.handles.insert(join_hash_map->FUNC(_runtime_state.get(), build_data, probe_data)); \
            }                                                                                                  \
            probe_state.active_coroutines = group;                                                             \
            join_hash_map->_probe_coroutine<FIRST>(_runtime_state.get(), build_data, probe_data);              \
            sort_results_from_coroutine(probe_state.probe_index, probe_state.build_index, probe_state.count);  \
        }

#define DO_TEST_PROBE_MID(FUNC)                                                                           \
    if (group == 0) {                                                                                     \
        join_hash_map->FUNC<false, false>(_runtime_state.get(), build_data, probe_data);                  \
    } else {                                                                                              \
        join_hash_map->_probe_coroutine<false>(_runtime_state.get(), build_data, probe_data);             \
        sort_results_from_coroutine(probe_state.probe_index, probe_state.build_index, probe_state.count); \
    }

#define DO_TEST_PROBE_END()                                                              \
    probe_state.has_remain = false;                                                      \
    probe_state.cur_probe_index = 0;                                                     \
    probe_state.cur_row_match_count = 0;                                                 \
    for (auto it = probe_state.handles.begin(); it != probe_state.handles.end(); it++) { \
        it->destroy();                                                                   \
    }                                                                                    \
    probe_state.handles.clear();                                                         \
    }

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtFirstOneToOneAllMatch) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    table_items.next.resize(8193);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    prepare_probe_state(&probe_state, 4096);
    probe_state.next.resize(config::vector_chunk_size);

    Buffer<int32_t> build_data(8193);
    Buffer<int32_t> probe_data(4096);

    table_items.next[0] = 0;
    for (size_t i = 0; i < 4096; i++) {
        build_data[1 + i] = i;
        build_data[1 + 4096 + i] = i;
        table_items.next[1 + i] = 0;
        table_items.next[1 + 4096 + i] = 1 + i;
    }

    for (size_t i = 0; i < 4096; i++) {
        probe_data[i] = i;
        probe_state.next[i] = 1 + i;
    }

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);

    DO_TEST_PROBE(_probe_from_ht, true, false)
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::ALL_MATCH_ONE);
    ASSERT_FALSE(probe_state.has_remain);
    ASSERT_EQ(probe_state.cur_probe_index, 0);
    ASSERT_EQ(probe_state.count, 4096);
    ASSERT_EQ(probe_state.cur_row_match_count, 0);
    for (uint32_t i = 0; i < 4096; i++) {
        ASSERT_EQ(probe_state.probe_index[i], i);
        ASSERT_EQ(probe_state.build_index[i], i + 1);
    }
    DO_TEST_PROBE_END()
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtFirstOneToOneMostMatch) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    table_items.next.resize(8193);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    prepare_probe_state(&probe_state, 4096);

    Buffer<int32_t> build_data(8193);
    Buffer<int32_t> probe_data(4096);

    table_items.next[0] = 0;
    for (size_t i = 0; i < 4096; i++) {
        if (i % 4 == 0) {
            build_data[1 + i] = 100000;
        } else {
            build_data[1 + i] = i;
        }
        build_data[4096 + 1 + i] = i;
        table_items.next[1 + i] = 0;
        table_items.next[4096 + 1 + i] = 1 + i;
    }

    for (size_t i = 0; i < 4096; i++) {
        probe_data[i] = i;
        probe_state.next[i] = 1 + i;
    }

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);

    DO_TEST_PROBE(_probe_from_ht, true, false)
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::MOST_MATCH_ONE);
    ASSERT_FALSE(probe_state.has_remain);
    ASSERT_EQ(probe_state.cur_probe_index, 0);
    ASSERT_EQ(probe_state.count, 3072);
    ASSERT_EQ(probe_state.cur_row_match_count, 0);
    size_t cur_index = 0;
    for (uint32_t i = 0; i < 4096; i++) {
        if (i % 4 == 0) {
            continue;
        }
        ASSERT_EQ(probe_state.probe_index[cur_index], i);
        ASSERT_EQ(probe_state.build_index[cur_index], i + 1);
        cur_index++;
    }
    DO_TEST_PROBE_END()
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtFirstOneToMany) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    table_items.row_count = 8193;
    table_items.next.resize(8193);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    prepare_probe_state(&probe_state, 3000);

    Buffer<int32_t> build_data(8193);
    Buffer<int32_t> probe_data(3000);

    table_items.next[0] = 0;
    for (size_t i = 0; i < 4096; i++) {
        build_data[1 + i] = i;
        build_data[4096 + 1 + i] = i;
        table_items.next[1 + i] = 0;
        table_items.next[4096 + 1 + i] = 1 + i;
    }
    table_items.used_buckets = 4097;

    for (size_t i = 0; i < 3000; i++) {
        probe_data[i] = i;
        probe_state.next[i] = 4096 + 1 + i;
    }

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
    DO_TEST_PROBE(_probe_from_ht, true, false)
    std::vector<std::pair<uint32_t, uint32_t>> results;
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
    ASSERT_TRUE(probe_state.has_remain);
    if (group == 0) {
        ASSERT_EQ(probe_state.cur_probe_index, 2048);
        ASSERT_EQ(probe_state.cur_row_match_count, 1);
    } else {
        ASSERT_GE(probe_state.cur_probe_index, 2048);
    }
    ASSERT_EQ(probe_state.count, 4096);
    for (uint32_t i = 0; i < probe_state.count; i += 1) {
        results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
    }

    DO_TEST_PROBE_MID(_probe_from_ht)
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
    ASSERT_FALSE(probe_state.has_remain);
    ASSERT_EQ(probe_state.cur_probe_index, 0);
    ASSERT_EQ(probe_state.count, 1904);
    ASSERT_EQ(probe_state.cur_row_match_count, 0);
    for (uint32_t i = 0; i < probe_state.count; i += 1) {
        results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
    }
    std::sort(results.begin(), results.end(), [&](auto& a, auto& b) {
        if (a.first == b.first) return a.second > b.second;
        return a.first < b.first;
    });
    for (uint32_t i = 0; i < 3000; i += 1) {
        ASSERT_EQ(results[2 * i].first, i);
        ASSERT_EQ(results[2 * i].second, i + 1 + 4096);

        ASSERT_EQ(results[2 * i + 1].first, i);
        ASSERT_EQ(results[2 * i + 1].second, i + 1);
    }
    DO_TEST_PROBE_END()
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtForLeftJoinFoundEmpty) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    table_items.next.resize(8193);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    prepare_probe_state(&probe_state, 3000);

    Buffer<int32_t> build_data(8193);
    Buffer<int32_t> probe_data(3000);

    table_items.next[0] = 0;
    for (size_t i = 0; i < 4096; i++) {
        build_data[1 + i] = i;
        build_data[1 + 4096 + i] = i;
        table_items.next[1 + i] = 0;
        table_items.next[1 + 4096 + i] = 1 + i;
    }
    for (size_t i = 2; i < 4096; i++) {
        table_items.next[i] = 1;
    }

    for (size_t i = 0; i < 3000; i++) {
        probe_data[i] = i;
        probe_state.next[i] = 4096 + 1 + i;
    }

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
    DO_TEST_PROBE(_probe_from_ht_for_left_outer_join, true, false)
    std::vector<std::pair<uint32_t, uint32_t>> results;
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
    ASSERT_TRUE(probe_state.has_remain);
    if (group == 0) {
        ASSERT_EQ(probe_state.cur_probe_index, 2048);
        ASSERT_EQ(probe_state.cur_row_match_count, 1);
    } else {
        ASSERT_GE(probe_state.cur_probe_index, 2048);
    }
    ASSERT_EQ(probe_state.count, 4096);
    for (uint32_t i = 0; i < probe_state.count; i += 1) {
        results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
    }

    DO_TEST_PROBE_MID(_probe_from_ht_for_left_outer_join)
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
    ASSERT_FALSE(probe_state.has_remain);
    ASSERT_EQ(probe_state.cur_probe_index, 0);
    ASSERT_EQ(probe_state.count, 1904);
    ASSERT_EQ(probe_state.cur_row_match_count, 0);
    for (uint32_t i = 0; i < probe_state.count; i += 1) {
        results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
    }
    std::sort(results.begin(), results.end(), [&](auto& a, auto& b) {
        if (a.first == b.first) return a.second > b.second;
        return a.first < b.first;
    });
    for (uint32_t i = 0; i < 3000; i += 1) {
        ASSERT_EQ(results[2 * i].first, i);
        ASSERT_EQ(results[2 * i].second, i + 1 + 4096);

        ASSERT_EQ(results[2 * i + 1].first, i);
        ASSERT_EQ(results[2 * i + 1].second, i + 1);
    }
    DO_TEST_PROBE_END()
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtForLeftJoinNextEmpty) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    Buffer<int32_t> build_data;
    Buffer<int32_t> probe_data;

    uint32_t match_count = 3;
    uint32_t probe_row_count = 3000;

    this->prepare_table_items(&table_items, TJoinOp::LEFT_OUTER_JOIN, true, match_count);
    this->prepare_build_data(&build_data, match_count);
    this->prepare_probe_state(&probe_state, probe_row_count);
    this->prepare_probe_data(&probe_data, probe_row_count);

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
    join_hash_map->_probe_from_ht_for_left_outer_left_anti_full_outer_join_with_other_conjunct<true, false>(
            _runtime_state.get(), build_data, probe_data);
    this->check_probe_state(table_items, probe_state, JoinMatchFlag::NORMAL, 0, match_count, probe_row_count);
    this->check_match_index(probe_state.probe_match_index, 0, config::vector_chunk_size, match_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtForLeftJoinNextEmptyMore) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    Buffer<int32_t> build_data;
    Buffer<int32_t> probe_data;

    uint32_t match_count = 3;
    uint32_t probe_row_count = 2000;

    this->prepare_table_items(&table_items, TJoinOp::LEFT_OUTER_JOIN, true, match_count);
    this->prepare_build_data(&build_data, match_count);
    this->prepare_probe_state(&probe_state, probe_row_count);
    this->prepare_probe_data(&probe_data, probe_row_count);

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
    join_hash_map->_probe_from_ht_for_left_outer_left_anti_full_outer_join_with_other_conjunct<true, false>(
            _runtime_state.get(), build_data, probe_data);
    std::vector<std::pair<uint32_t, uint32_t>> results;
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
    ASSERT_EQ(probe_state.count, 4096);
    for (uint32_t i = 0; i < probe_state.count; i += 1) {
        results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
    }

    join_hash_map->_probe_from_ht_for_left_outer_left_anti_full_outer_join_with_other_conjunct<false, false>(
            _runtime_state.get(), build_data, probe_data);
    ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
    ASSERT_FALSE(probe_state.has_remain);
    ASSERT_EQ(probe_state.count, 1904);
    for (uint32_t i = 0; i < probe_state.count; i += 1) {
        results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
    }
    std::sort(results.begin(), results.end(), [&](auto& a, auto& b) {
        if (a.first == b.first) return a.second > b.second;
        return a.first < b.first;
    });

    for (uint32_t i = 0; i < 2000; i += 1) {
        ASSERT_EQ(results[3 * i].first, i);
        ASSERT_EQ(results[3 * i].second, i + 1 + 4096 + 4096);

        ASSERT_EQ(results[3 * i + 1].first, i);
        ASSERT_EQ(results[3 * i + 1].second, i + 1 + 4096);

        ASSERT_EQ(results[3 * i + 2].first, i);
        ASSERT_EQ(results[3 * i + 2].second, i + 1);
        ASSERT_EQ(match_count, probe_state.probe_match_index[i]);
    }
}

// Test case for right semi join with other conjunct.
// - One probe row match three build row.
// - All match.
// - The build rows for one probe row, exist in different chunk
// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, ProbeFromHtForRightXXXJoinWithOtherConjunct) {
    for (auto& join_type : {TJoinOp::RIGHT_SEMI_JOIN, TJoinOp::RIGHT_OUTER_JOIN, TJoinOp::RIGHT_ANTI_JOIN}) {
        JoinHashTableItems table_items;
        HashTableProbeState probe_state;
        Buffer<int32_t> build_data;
        Buffer<int32_t> probe_data;

        uint32_t match_count = 3;
        uint32_t probe_row_count = 2000;

        table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
        this->prepare_table_items(&table_items, join_type, true, match_count);
        this->prepare_build_data(&build_data, match_count);
        this->prepare_probe_state(&probe_state, probe_row_count);
        this->prepare_probe_data(&probe_data, probe_row_count);

        // first probe
        auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
        join_hash_map->_probe_from_ht_for_right_outer_right_semi_right_anti_join_with_other_conjunct<true, false>(
                _runtime_state.get(), build_data, probe_data);
        this->check_probe_state(table_items, probe_state, JoinMatchFlag::NORMAL, 0, match_count, probe_row_count);

        // second probe
        join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
        join_hash_map->_probe_from_ht_for_right_outer_right_semi_right_anti_join_with_other_conjunct<false, false>(
                _runtime_state.get(), build_data, probe_data);
        this->check_probe_state(table_items, probe_state, JoinMatchFlag::NORMAL, 1, match_count, probe_row_count);
    }
}

TEST_F(JoinHashMapTest, ProbeFromHtForRightXXXJoinWithOtherConjunctMore) {
    for (auto& join_type : {TJoinOp::RIGHT_SEMI_JOIN, TJoinOp::RIGHT_OUTER_JOIN, TJoinOp::RIGHT_ANTI_JOIN}) {
        JoinHashTableItems table_items;
        HashTableProbeState probe_state;
        Buffer<int32_t> build_data;
        Buffer<int32_t> probe_data;

        uint32_t match_count = 3;
        uint32_t probe_row_count = 2000;

        this->prepare_table_items(&table_items, join_type, true, match_count);
        this->prepare_build_data(&build_data, match_count);
        this->prepare_probe_state(&probe_state, probe_row_count);
        this->prepare_probe_data(&probe_data, probe_row_count);

        auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
        join_hash_map->_probe_from_ht_for_right_outer_right_semi_right_anti_join_with_other_conjunct<true, false>(
                _runtime_state.get(), build_data, probe_data);
        std::vector<std::pair<uint32_t, uint32_t>> results;
        ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
        ASSERT_EQ(probe_state.count, 4096);
        for (uint32_t i = 0; i < probe_state.count; i += 1) {
            results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
        }

        join_hash_map->_probe_from_ht_for_right_outer_right_semi_right_anti_join_with_other_conjunct<false, false>(
                _runtime_state.get(), build_data, probe_data);
        ASSERT_EQ(probe_state.match_flag, JoinMatchFlag::NORMAL);
        ASSERT_FALSE(probe_state.has_remain);
        ASSERT_EQ(probe_state.count, 1904);
        for (uint32_t i = 0; i < probe_state.count; i += 1) {
            results.push_back(std::make_pair(probe_state.probe_index[i], probe_state.build_index[i]));
        }
        std::sort(results.begin(), results.end(), [&](auto& a, auto& b) {
            if (a.first == b.first) return a.second > b.second;
            return a.first < b.first;
        });

        for (uint32_t i = 0; i < 2000; i += 1) {
            ASSERT_EQ(results[3 * i].first, i);
            ASSERT_EQ(results[3 * i].second, i + 1 + 4096 + 4096);

            ASSERT_EQ(results[3 * i + 1].first, i);
            ASSERT_EQ(results[3 * i + 1].second, i + 1 + 4096);

            ASSERT_EQ(results[3 * i + 2].first, i);
            ASSERT_EQ(results[3 * i + 2].second, i + 1);
        }
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, OneKeyJoinHashTable) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(TJoinOp::INNER_JOIN, 6);
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    JoinHashTable hash_table;
    hash_table.create(param);

    auto build_chunk = create_int32_build_chunk(10, 0, false);
    auto probe_chunk = create_int32_probe_chunk(5, 1, false);
    Columns probe_key_columns;
    probe_key_columns.emplace_back(probe_chunk->columns()[0]);

    Columns build_keys_column{build_chunk->columns()[0]};
    hash_table.append_chunk(build_chunk, build_keys_column);
    (void)hash_table.build(_runtime_state.get());

    ChunkPtr result_chunk = std::make_shared<Chunk>();
    bool eos = false;

    (void)hash_table.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos);

    ASSERT_EQ(result_chunk->num_columns(), 6);

    ColumnPtr column1 = result_chunk->get_column_by_slot_id(0);
    check_int32_column(*column1, 5, 1);
    ColumnPtr column2 = result_chunk->get_column_by_slot_id(1);
    check_int32_column(*column2, 5, 11);
    ColumnPtr column3 = result_chunk->get_column_by_slot_id(2);
    check_int32_column(*column3, 5, 21);
    ColumnPtr column4 = result_chunk->get_column_by_slot_id(3);
    check_int32_column(*column4, 5, 1);
    ColumnPtr column5 = result_chunk->get_column_by_slot_id(4);
    check_int32_column(*column5, 5, 11);
    ColumnPtr column6 = result_chunk->get_column_by_slot_id(5);
    check_int32_column(*column6, 5, 21);

    hash_table.close();
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, OneNullableKeyJoinHashTable) {
    config::vector_chunk_size = 4096;

    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, true);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, true);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(TJoinOp::INNER_JOIN, 6);
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    JoinHashTable hash_table;
    hash_table.create(param);

    auto build_chunk = create_int32_build_chunk(10, 0, true);
    auto probe_chunk = create_int32_probe_chunk(5, 1, true);
    Columns probe_key_columns;
    probe_key_columns.emplace_back(probe_chunk->columns()[0]);

    Columns build_key_columns;
    build_key_columns.emplace_back(build_chunk->columns()[0]);
    hash_table.append_chunk(build_chunk, build_key_columns);
    (void)hash_table.build(_runtime_state.get());

    ChunkPtr result_chunk = std::make_shared<Chunk>();
    bool eos = false;

    (void)hash_table.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos);

    ASSERT_EQ(result_chunk->num_columns(), 6);

    ColumnPtr column1 = result_chunk->get_column_by_slot_id(0);
    check_int32_nullable_column(*column1, 5, 1);
    ColumnPtr column2 = result_chunk->get_column_by_slot_id(1);
    check_int32_nullable_column(*column2, 5, 11);
    ColumnPtr column3 = result_chunk->get_column_by_slot_id(2);
    check_int32_nullable_column(*column3, 5, 21);
    ColumnPtr column4 = result_chunk->get_column_by_slot_id(3);
    check_int32_nullable_column(*column4, 5, 1);
    ColumnPtr column5 = result_chunk->get_column_by_slot_id(4);
    check_int32_nullable_column(*column5, 5, 11);
    ColumnPtr column6 = result_chunk->get_column_by_slot_id(5);
    check_int32_nullable_column(*column6, 5, 21);

    hash_table.close();
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, FixedSizeJoinHashTable) {
    config::vector_chunk_size = 4096;

    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(TJoinOp::INNER_JOIN, 6);
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    param.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    JoinHashTable hash_table;
    hash_table.create(param);

    auto build_chunk = create_int32_build_chunk(10, 0, false);
    auto probe_chunk = create_int32_probe_chunk(5, 1, false);
    Columns probe_key_columns;
    probe_key_columns.emplace_back(probe_chunk->columns()[0]);
    probe_key_columns.emplace_back(probe_chunk->columns()[1]);

    Columns build_key_columns{build_chunk->columns()[0], build_chunk->columns()[1]};
    hash_table.append_chunk(build_chunk, build_key_columns);
    (void)hash_table.build(_runtime_state.get());

    ChunkPtr result_chunk = std::make_shared<Chunk>();
    bool eos = false;

    (void)hash_table.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos);

    ASSERT_EQ(result_chunk->num_columns(), 6);

    ColumnPtr column1 = result_chunk->get_column_by_slot_id(0);
    check_int32_column(*column1, 5, 1);
    ColumnPtr column2 = result_chunk->get_column_by_slot_id(1);
    check_int32_column(*column2, 5, 11);
    ColumnPtr column3 = result_chunk->get_column_by_slot_id(2);
    check_int32_column(*column3, 5, 21);
    ColumnPtr column4 = result_chunk->get_column_by_slot_id(3);
    check_int32_column(*column4, 5, 1);
    ColumnPtr column5 = result_chunk->get_column_by_slot_id(4);
    check_int32_column(*column5, 5, 11);
    ColumnPtr column6 = result_chunk->get_column_by_slot_id(5);
    check_int32_column(*column6, 5, 21);

    hash_table.close();
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, SerializeJoinHashTable) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_VARCHAR, false);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_VARCHAR, false);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    HashTableParam param = create_table_param(TJoinOp::INNER_JOIN, 6);
    param.join_keys.emplace_back(JoinKeyDesc{&_varchar_type, false, nullptr});
    param.join_keys.emplace_back(JoinKeyDesc{&_varchar_type, false, nullptr});
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    JoinHashTable hash_table;
    hash_table.create(param);

    auto build_chunk = create_binary_build_chunk(10, false);
    auto probe_chunk = create_binary_probe_chunk(5, 1, false);
    Columns probe_key_columns;
    probe_key_columns.emplace_back(probe_chunk->columns()[0]);
    probe_key_columns.emplace_back(probe_chunk->columns()[1]);

    Columns build_key_columns{build_chunk->columns()[0], build_chunk->columns()[1]};
    hash_table.append_chunk(build_chunk, build_key_columns);
    (void)hash_table.build(_runtime_state.get());

    ChunkPtr result_chunk = std::make_shared<Chunk>();
    bool eos = false;

    (void)hash_table.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos);

    ASSERT_EQ(result_chunk->num_columns(), 6);

    ColumnPtr column1 = result_chunk->get_column_by_slot_id(0);
    check_binary_column(column1, 5, 1);
    ColumnPtr column2 = result_chunk->get_column_by_slot_id(1);
    check_binary_column(column2, 5, 11);
    ColumnPtr column3 = result_chunk->get_column_by_slot_id(2);
    check_binary_column(column3, 5, 21);
    ColumnPtr column4 = result_chunk->get_column_by_slot_id(3);
    check_binary_column(column4, 5, 1);
    ColumnPtr column5 = result_chunk->get_column_by_slot_id(4);
    check_binary_column(column5, 5, 11);
    ColumnPtr column6 = result_chunk->get_column_by_slot_id(5);
    check_binary_column(column6, 5, 21);

    hash_table.close();
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildKeyConstructorForSerializedFixedSizeForNotNullableColumn) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    uint32_t build_row_count = 9000;
    uint32_t probe_row_count = 10;

    prepare_table_items(&table_items, build_row_count);
    prepare_probe_state(&probe_state, probe_row_count);

    // Add int column
    auto column_1 = create_column(TYPE_INT);
    column_1->append_default();
    column_1->append(*create_column(TYPE_INT, 0, build_row_count));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Add int column
    auto column_2 = create_column(TYPE_INT);
    column_2->append_default();
    column_2->append(*create_column(TYPE_INT, 0, build_row_count), 0, build_row_count);
    table_items.key_columns.emplace_back(std::move(column_2));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Construct Hash Table
    using BuildKeyConstructor = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_BIGINT>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    // Check
    check_build_index(table_items.first, table_items.next, build_row_count);
    check_build_column(table_items.build_key_column, build_row_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildKeyConstructorForSerializedFixedSizeForNullableColumn) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    uint32_t build_row_count = 9000;
    uint32_t probe_row_count = 10;

    prepare_table_items(&table_items, build_row_count);
    prepare_probe_state(&probe_state, probe_row_count);

    // Add int column
    auto nulls_1 = create_bools(build_row_count, 0);
    auto column_1 = create_nullable_column(TYPE_INT);
    column_1->append_datum(0);
    column_1->append(*create_nullable_column(TYPE_INT, nulls_1, 0, build_row_count));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Add int column
    auto nulls_2 = create_bools(build_row_count, 0);
    auto column_2 = create_nullable_column(TYPE_INT);
    column_2->append_datum(0);
    column_2->append(*create_nullable_column(TYPE_INT, nulls_2, 0, build_row_count), 0, build_row_count);
    table_items.key_columns.emplace_back(std::move(column_2));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Construct Hash Table
    using BuildKeyConstructor = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_BIGINT>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    // Check
    check_build_index(table_items.first, table_items.next, build_row_count);
    check_build_column(table_items.build_key_column, build_row_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildKeyConstructorForSerializedFixedSizeForPartialNullableColumn) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    uint32_t build_row_count = 9000;
    uint32_t probe_row_count = 10;

    prepare_table_items(&table_items, build_row_count);
    prepare_probe_state(&probe_state, probe_row_count);

    // Add int column
    auto nulls_1 = create_bools(build_row_count, 3);
    auto column_1 = create_nullable_column(TYPE_INT);
    column_1->append_datum(0);
    column_1->append(*create_nullable_column(TYPE_INT, nulls_1, 0, build_row_count));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Add int column
    auto nulls_2 = create_bools(build_row_count, 2);
    auto column_2 = create_nullable_column(TYPE_INT);
    column_2->append_datum(0);
    column_2->append(*create_nullable_column(TYPE_INT, nulls_2, 0, build_row_count), 0, build_row_count);
    table_items.key_columns.emplace_back(std::move(column_2));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Construct Hash Table
    using BuildKeyConstructor = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_BIGINT>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    // Check
    auto nulls = create_bools(build_row_count, 4);
    check_build_index(nulls, table_items.first, table_items.next, build_row_count);
    check_build_column(nulls, table_items.build_key_column, build_row_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildKeyConstructorForSerializedForNotNullableColumn) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    uint32_t build_row_count = 9000;
    uint32_t probe_row_count = 10;

    prepare_table_items(&table_items, build_row_count);
    prepare_probe_state(&probe_state, probe_row_count);

    // Add int column
    auto column_1 = create_column(TYPE_INT);
    column_1->append_default();
    column_1->append(*create_column(TYPE_INT, 0, build_row_count));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Add binary column
    auto column_2 = create_column(TYPE_VARCHAR);
    column_2->append_default();
    column_2->append(*create_column(TYPE_VARCHAR, 0, build_row_count), 0, build_row_count);
    table_items.key_columns.emplace_back(std::move(column_2));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Construct Hash Table
    using BuildKeyConstructor = BuildKeyConstructorForSerialized;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_VARCHAR>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    // Check
    check_build_index(table_items.first, table_items.next, build_row_count);
    check_build_slice(table_items.build_slice, build_row_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildKeyConstructorForSerializedForNullableColumn) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    uint32_t build_row_count = 9000;
    uint32_t probe_row_count = 10;

    prepare_table_items(&table_items, build_row_count);
    prepare_probe_state(&probe_state, probe_row_count);

    // Add int column
    auto nulls_1 = create_bools(build_row_count, 0);
    auto column_1 = create_nullable_column(TYPE_INT);
    column_1->append_datum(0);
    column_1->append(*create_nullable_column(TYPE_INT, nulls_1, 0, build_row_count));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Add binary column
    auto nulls_2 = create_bools(build_row_count, 0);
    auto column_2 = create_nullable_column(TYPE_VARCHAR);
    column_2->append_datum(Slice());
    column_2->append(*create_nullable_column(TYPE_VARCHAR, nulls_2, 0, build_row_count), 0, build_row_count);
    table_items.key_columns.emplace_back(std::move(column_2));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Construct Hash Table
    using BuildKeyConstructor = BuildKeyConstructorForSerialized;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_VARCHAR>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    // Check
    check_build_index(table_items.first, table_items.next, build_row_count);
    check_build_slice(table_items.build_slice, build_row_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, BuildKeyConstructorForSerializedForPartialNullColumn) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;
    uint32_t build_row_count = 9000;
    uint32_t probe_row_count = 10;

    prepare_table_items(&table_items, build_row_count);
    prepare_probe_state(&probe_state, probe_row_count);

    // Add int column
    auto nulls_1 = create_bools(build_row_count, 3);
    auto column_1 = create_nullable_column(TYPE_INT);
    column_1->append_datum(0);
    column_1->append(*create_nullable_column(TYPE_INT, nulls_1, 0, build_row_count));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Add binary column
    auto nulls_2 = create_bools(build_row_count, 2);
    auto column_2 = create_nullable_column(TYPE_VARCHAR);
    column_2->append_datum(Slice());
    column_2->append(*create_nullable_column(TYPE_VARCHAR, nulls_2, 0, build_row_count), 0, build_row_count);
    table_items.key_columns.emplace_back(std::move(column_2));
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});

    // Construct Hash Table
    using BuildKeyConstructor = BuildKeyConstructorForSerialized;
    using JoinHashMapMethod = BucketChainedJoinHashMap<LogicalType::TYPE_VARCHAR>;

    BuildKeyConstructor::prepare(_runtime_state.get(), &table_items);
    BuildKeyConstructor::build_key(_runtime_state.get(), &table_items);
    JoinHashMapMethod::build_prepare(_runtime_state.get(), &table_items);
    JoinHashMapMethod::construct_hash_table(&table_items, BuildKeyConstructor::get_key_data(table_items),
                                            BuildKeyConstructor::get_is_nulls(table_items));

    // Check
    auto nulls = create_bools(build_row_count, 4);
    check_build_index(nulls, table_items.first, table_items.next, build_row_count);
    check_build_slice(nulls, table_items.build_slice, build_row_count);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, EmptyHashMapTest) {
    check_empty_hash_map(TJoinOp::LEFT_OUTER_JOIN, 5, 5, 6);
    check_empty_hash_map(TJoinOp::FULL_OUTER_JOIN, 5, 5, 6);
    check_empty_hash_map(TJoinOp::LEFT_ANTI_JOIN, 5, 5, 6);
    check_empty_hash_map(TJoinOp::NULL_AWARE_LEFT_ANTI_JOIN, 5, 5, 6);
    check_empty_hash_map(TJoinOp::INNER_JOIN, 5, 0, 0);
    check_empty_hash_map(TJoinOp::LEFT_SEMI_JOIN, 5, 0, 0);
    check_empty_hash_map(TJoinOp::RIGHT_SEMI_JOIN, 5, 0, 0);
    check_empty_hash_map(TJoinOp::RIGHT_OUTER_JOIN, 5, 0, 0);
    check_empty_hash_map(TJoinOp::RIGHT_ANTI_JOIN, 5, 0, 0);
    check_empty_hash_map(TJoinOp::CROSS_JOIN, 5, 0, 0);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, EmptyHashMapTestLazyFilter) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = true;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.probe_output_slots = {1};
    param.build_output_slots = {4};
    param.predicate_slots = {2, 5};
    param.join_type = TJoinOp::LEFT_OUTER_JOIN;

    ht.create(param);
    ASSERT_TRUE(ht.build(_runtime_state.get()).ok());

    size_t num_probe_rows = 5;
    auto probe_chunk = create_int32_probe_chunk(num_probe_rows, 1, false);
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    Columns probe_key_columns;
    bool eos = false;
    ASSERT_TRUE(ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos).ok());
    ASSERT_EQ(result_chunk->num_columns(), 3);
    auto c2 = result_chunk->get_column_by_slot_id(2);
    check_int32_column(*c2, 5, 21);
    auto c5 = result_chunk->get_column_by_slot_id(5);
    check_null_column(c5, 5);
    auto probe_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_PROBE_INDEX_SLOT_ID);
    check_probe_index_column(*probe_index_col, {0, 1, 2, 3, 4});

    ASSERT_EQ(result_chunk->filter({1, 0, 1, 0, 1}, true), 3);
    ASSERT_TRUE(ht.lazy_output<false>(_runtime_state.get(), &probe_chunk, &result_chunk).ok());
    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 3);

    auto c1 = result_chunk->get_column_by_slot_id(1);
    c2 = result_chunk->get_column_by_slot_id(2);
    auto c4 = result_chunk->get_column_by_slot_id(4);
    c5 = result_chunk->get_column_by_slot_id(5);

    for (size_t i = 0; i < 3; i++) {
        ASSERT_EQ(c1->get(i).get_int32(), 10 + i * 2 + 1);
        ASSERT_EQ(c2->get(i).get_int32(), 20 + i * 2 + 1);
        ASSERT_TRUE(c4->get(i).is_null());
        ASSERT_TRUE(c5->get(i).is_null());
    }
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, EmptyHashMapTestLazyOutputAll) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = true;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.probe_output_slots = {1};
    param.build_output_slots = {4};
    param.predicate_slots = {2, 5};
    param.join_type = TJoinOp::LEFT_OUTER_JOIN;

    ht.create(param);
    ASSERT_TRUE(ht.build(_runtime_state.get()).ok());

    size_t num_probe_rows = 5;
    auto probe_chunk = create_int32_probe_chunk(num_probe_rows, 1, false);
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    Columns probe_key_columns;
    bool eos = false;
    ASSERT_TRUE(ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos).ok());
    ASSERT_EQ(result_chunk->num_columns(), 3);
    auto c2 = result_chunk->get_column_by_slot_id(2);
    check_int32_column(*c2, 5, 21);
    auto c5 = result_chunk->get_column_by_slot_id(5);
    check_null_column(c5, 5);
    auto probe_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_PROBE_INDEX_SLOT_ID);
    check_probe_index_column(*probe_index_col, {0, 1, 2, 3, 4});

    ASSERT_EQ(result_chunk->filter({1, 1, 1, 1, 1}, true), 5);
    ASSERT_TRUE(ht.lazy_output<false>(_runtime_state.get(), &probe_chunk, &result_chunk).ok());
    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 5);

    auto c1 = result_chunk->get_column_by_slot_id(1);
    c2 = result_chunk->get_column_by_slot_id(2);
    auto c4 = result_chunk->get_column_by_slot_id(4);
    c5 = result_chunk->get_column_by_slot_id(5);

    for (size_t i = 0; i < 5; i++) {
        ASSERT_EQ(c1->get(i).get_int32(), 10 + i + 1);
        ASSERT_EQ(c2->get(i).get_int32(), 20 + i + 1);
        ASSERT_TRUE(c4->get(i).is_null());
        ASSERT_TRUE(c5->get(i).is_null());
    }
}

// NOLINTNEXTLINE
//
// build table
// 0, 10, 20
// 1, 11, 21
// 2, 12, 22
// 3, 13, 23
// 4, 14, 24
//
// probe table
// 0, 10, 20
// 1, 11, 21
// 2, 12, 22
// 3, 13, 23
// 4, 14, 24
TEST_F(JoinHashMapTest, NormalHashMapTestLazyOutputAll) {
    size_t num_probe_rows = 5;
    size_t num_build_rows = 5;
    bool eos = false;
    ColumnPtr c1, c2, c4, c5, probe_index_col, build_index_col;

    // prepare data
    auto build_chunk = create_int32_build_chunk(num_build_rows, 0, false);
    Columns build_key_columns{build_chunk->columns()[0]};
    auto probe_chunk = create_int32_probe_chunk(num_probe_rows, 0, false);
    Columns probe_key_columns = {probe_chunk->columns()[0]};
    ChunkPtr result_chunk = std::make_shared<Chunk>();

    // create param
    auto param = create_table_param_int(TJoinOp::INNER_JOIN, 3);
    param.enable_late_materialization = true;

    // create hash table
    JoinHashTable ht;
    ht.create(param);

    // append build chunk
    ht.append_chunk(build_chunk, build_key_columns);
    ASSERT_OK(ht.build(_runtime_state.get()));

    // probe
    ASSERT_OK(ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos));

    ASSERT_EQ(result_chunk->num_columns(), 4);
    c2 = result_chunk->get_column_by_slot_id(2);
    c5 = result_chunk->get_column_by_slot_id(5);
    probe_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_PROBE_INDEX_SLOT_ID);
    build_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_BUILD_INDEX_SLOT_ID);
    ASSERT_EQ(c2->debug_string(), "[20, 21, 22, 23, 24]");
    ASSERT_EQ(c5->debug_string(), "[20, 21, 22, 23, 24]");
    ASSERT_EQ(probe_index_col->debug_string(), "[0, 1, 2, 3, 4]");
    ASSERT_EQ(build_index_col->debug_string(), "[1, 2, 3, 4, 5]");

    // filter
    ASSERT_EQ(result_chunk->filter({1, 1, 1, 1, 1}, true), 5);

    // lazy output
    ASSERT_OK(ht.lazy_output<false>(_runtime_state.get(), &probe_chunk, &result_chunk));
    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 5);

    c1 = result_chunk->get_column_by_slot_id(1);
    c2 = result_chunk->get_column_by_slot_id(2);
    c4 = result_chunk->get_column_by_slot_id(4);
    c5 = result_chunk->get_column_by_slot_id(5);

    ASSERT_EQ(c1->debug_string(), "[10, 11, 12, 13, 14]");
    ASSERT_EQ(c2->debug_string(), "[20, 21, 22, 23, 24]");
    ASSERT_EQ(c4->debug_string(), "[10, 11, 12, 13, 14]");
    ASSERT_EQ(c5->debug_string(), "[20, 21, 22, 23, 24]");
}

// NOLINTNEXTLINE
//
// build table
// 0, 10, 20
// 1, 11, 21
// 2, 12, 22
// 3, 13, 23
// 4, 14, 24
//
// probe table
// 0, 10, 20
// 1, 11, 21
// 2, 12, 22
// 3, 13, 23
// 4, 14, 24
TEST_F(JoinHashMapTest, NormalHashMapTestLazyOutputPart) {
    size_t num_probe_rows = 4;
    size_t num_build_rows = 5;
    bool eos = false;
    ColumnPtr c1, c2, c4, c5, probe_index_col, build_index_col;

    // prepare data
    auto build_chunk = create_int32_build_chunk(num_build_rows, 0, false);
    Columns key_columns{build_chunk->columns()[0]};
    auto probe_chunk = create_int32_probe_chunk(num_probe_rows, 0, false);
    Columns probe_key_columns = {probe_chunk->columns()[0]};
    ChunkPtr result_chunk = std::make_shared<Chunk>();

    // create param
    auto param = create_table_param_int(TJoinOp::INNER_JOIN, 3);
    param.enable_late_materialization = true;

    // create hash table
    JoinHashTable ht;
    ht.create(param);

    // append build chunk
    ht.append_chunk(build_chunk, key_columns);

    // build hash table
    ASSERT_TRUE(ht.build(_runtime_state.get()).ok());

    // probe
    ASSERT_OK(ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos));

    ASSERT_EQ(result_chunk->num_columns(), 4);
    c2 = result_chunk->get_column_by_slot_id(2);
    c5 = result_chunk->get_column_by_slot_id(5);
    probe_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_PROBE_INDEX_SLOT_ID);
    build_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_BUILD_INDEX_SLOT_ID);
    ASSERT_EQ(c2->debug_string(), "[20, 21, 22, 23]");
    ASSERT_EQ(c5->debug_string(), "[20, 21, 22, 23]");
    ASSERT_EQ(probe_index_col->debug_string(), "[0, 1, 2, 3]");
    ASSERT_EQ(build_index_col->debug_string(), "[1, 2, 3, 4]");

    // filter
    ASSERT_EQ(result_chunk->filter({1, 0, 1, 0}, true), 2);

    // lazy output
    ASSERT_TRUE(ht.lazy_output<false>(_runtime_state.get(), &probe_chunk, &result_chunk).ok());

    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 2);
    c1 = result_chunk->get_column_by_slot_id(1);
    c2 = result_chunk->get_column_by_slot_id(2);
    c4 = result_chunk->get_column_by_slot_id(4);
    c5 = result_chunk->get_column_by_slot_id(5);
    ASSERT_EQ(c1->debug_string(), "[10, 12]");
    ASSERT_EQ(c2->debug_string(), "[20, 22]");
    ASSERT_EQ(c4->debug_string(), "[10, 12]");
    ASSERT_EQ(c5->debug_string(), "[20, 22]");
}

// NOLINTNEXTLINE
//
// build table
// 1, 11, 21
// 2, 12, 22
// 3, 13, 23
// 4, 14, 24
// 5, 15, 25
//
// probe table
// 0, 10, 20
// 1, 11, 21
// 2, 12, 22
// 3, 13, 23
TEST_F(JoinHashMapTest, NormalHashMapTestLazyOutputPartRemain) {
    size_t num_probe_rows = 4;
    size_t num_build_rows = 5;
    bool eos = false;
    ColumnPtr c1, c2, c4, c5, probe_index_col, build_index_col;

    // prepare data
    ChunkPtr build_chunk = create_int32_build_chunk(num_build_rows, 1, false);
    Columns build_key_columns = {build_chunk->columns()[0]};
    ChunkPtr probe_chunk = create_int32_probe_chunk(num_probe_rows, 0, false);
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    Columns probe_key_columns = {probe_chunk->columns()[0]};

    // create param
    auto param = create_table_param_int(TJoinOp::RIGHT_OUTER_JOIN, 3);
    param.enable_late_materialization = true;

    // create hash table
    JoinHashTable ht;
    ht.create(param);

    // append build chunk
    ht.append_chunk(build_chunk, build_key_columns);

    // build hash table
    ASSERT_OK(ht.build(_runtime_state.get()));

    // probe
    ASSERT_OK(ht.probe(_runtime_state.get(), probe_key_columns, &probe_chunk, &result_chunk, &eos));

    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 3);
    c2 = result_chunk->get_column_by_slot_id(2);
    c5 = result_chunk->get_column_by_slot_id(5);
    probe_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_PROBE_INDEX_SLOT_ID);
    build_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_BUILD_INDEX_SLOT_ID);
    ASSERT_EQ(c2->debug_string(), "[21, 22, 23]");
    ASSERT_EQ(c5->debug_string(), "[21, 22, 23]");
    ASSERT_EQ(probe_index_col->debug_string(), "[1, 2, 3]");
    ASSERT_EQ(build_index_col->debug_string(), "[1, 2, 3]");

    // filter
    ASSERT_EQ(result_chunk->filter({1, 0, 1}, true), 2);

    // lazy output
    ASSERT_OK(ht.lazy_output<false>(_runtime_state.get(), &probe_chunk, &result_chunk));
    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 2);

    c1 = result_chunk->get_column_by_slot_id(1);
    c2 = result_chunk->get_column_by_slot_id(2);
    c4 = result_chunk->get_column_by_slot_id(4);
    c5 = result_chunk->get_column_by_slot_id(5);
    ASSERT_EQ(c1->debug_string(), "[11, 13]");
    ASSERT_EQ(c2->debug_string(), "[21, 23]");
    ASSERT_EQ(c4->debug_string(), "[11, 13]");
    ASSERT_EQ(c5->debug_string(), "[21, 23]");

    // probe remain
    result_chunk = std::make_shared<Chunk>();
    ASSERT_OK(ht.probe_remain(_runtime_state.get(), &result_chunk, &eos));

    ASSERT_EQ(result_chunk->num_columns(), 3);
    ASSERT_EQ(result_chunk->num_rows(), 2);
    c2 = result_chunk->get_column_by_slot_id(2);
    c5 = result_chunk->get_column_by_slot_id(5);
    build_index_col = result_chunk->get_column_by_slot_id(Chunk::HASH_JOIN_BUILD_INDEX_SLOT_ID);
    ASSERT_EQ(c2->debug_string(), "[NULL, NULL]");
    ASSERT_EQ(c5->debug_string(), "[24, 25]");
    ASSERT_EQ(build_index_col->debug_string(), "[4, 5]");

    // filter
    ASSERT_EQ(result_chunk->filter({1, 0}, true), 1);

    // lazy output
    ASSERT_OK(ht.lazy_output<true>(_runtime_state.get(), &probe_chunk, &result_chunk));

    ASSERT_EQ(result_chunk->num_columns(), 4);
    ASSERT_EQ(result_chunk->num_rows(), 1);
    c1 = result_chunk->get_column_by_slot_id(1);
    c2 = result_chunk->get_column_by_slot_id(2);
    c4 = result_chunk->get_column_by_slot_id(4);
    c5 = result_chunk->get_column_by_slot_id(5);
    ASSERT_EQ(c1->debug_string(), "[NULL]");
    ASSERT_EQ(c2->debug_string(), "[NULL]");
    ASSERT_EQ(c4->debug_string(), "[14]");
    ASSERT_EQ(c5->debug_string(), "[24]");
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, NullAwareAntiJoinTest) {
    JoinHashTableItems table_items;
    HashTableProbeState probe_state;

    uint32_t build_row_count = 4;
    uint32_t probe_row_count = 3;

    table_items.first.resize(build_row_count + 1, 0);
    table_items.next.resize(build_row_count + 1);
    table_items.join_keys.emplace_back(JoinKeyDesc{&_int_type, false, nullptr});
    auto build_col_nulls = create_bools(build_row_count + 1, 3);
    auto column_1 = create_nullable_column(TYPE_INT);
    column_1->append(*create_nullable_column(TYPE_INT, build_col_nulls, 0, build_row_count + 1));
    table_items.key_columns.emplace_back(std::move(column_1));
    table_items.join_type = TJoinOp::NULL_AWARE_LEFT_ANTI_JOIN;
    table_items.row_count = build_row_count;

    auto probe_col_nulls = create_bools(build_row_count, 3);

    probe_state.null_array = &probe_col_nulls;
    prepare_probe_state(&probe_state, probe_row_count);
    for (size_t i = 0; i < probe_row_count; i++) {
        probe_state.next[i] = 0;
    }

    Buffer<int32_t> build_data;
    Buffer<int32_t> probe_data;
    this->prepare_probe_data(&build_data, build_row_count);
    this->prepare_probe_data(&probe_data, probe_row_count);

    auto join_hash_map = std::make_unique<JoinHashMapForOneKey(TYPE_INT)>(&table_items, &probe_state);
    join_hash_map->_probe_from_ht_for_null_aware_anti_join_with_other_conjunct<true, false>(_runtime_state.get(),
                                                                                            build_data, probe_data);

    // null in probe table match all build table rows
    ASSERT_EQ(probe_state.probe_match_index[0], build_row_count);
    // value in probe table not hit hash table match all null value rows in build table
    ASSERT_EQ(probe_state.probe_match_index[1], 1);
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, TestOutputSlotsEmpty) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = false;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    ht.create(param);

    ASSERT_EQ(ht.get_probe_column_count(), 3);
    ASSERT_EQ(ht.get_build_column_count(), 3);
    check_probe_output_slot_ids(*ht.table_items(), {0, 1, 2});
    check_build_output_slot_ids(*ht.table_items(), {3, 4, 5});
    check_lazy_probe_output_slot_ids(*ht.table_items(), {});
    check_lazy_build_output_slot_ids(*ht.table_items(), {});
    check_not_output_slot_ids(*ht.table_items(), {});
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, TestOutputSlotsNormal) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = false;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.probe_output_slots = {1};
    param.build_output_slots = {4};
    param.predicate_slots = {2, 5};

    ht.create(param);

    ASSERT_EQ(ht.get_probe_column_count(), 3);
    ASSERT_EQ(ht.get_build_column_count(), 3);
    check_probe_output_slot_ids(*ht.table_items(), {1, 2});
    check_build_output_slot_ids(*ht.table_items(), {4, 5});
    check_lazy_probe_output_slot_ids(*ht.table_items(), {});
    check_lazy_build_output_slot_ids(*ht.table_items(), {});
    check_not_output_slot_ids(*ht.table_items(), {0, 3});
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, TestLazyOutputSlotsEmpty) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = true;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();

    ht.create(param);

    ASSERT_EQ(ht.get_probe_column_count(), 3);
    ASSERT_EQ(ht.get_build_column_count(), 3);
    check_probe_output_slot_ids(*ht.table_items(), {0, 1, 2});
    check_build_output_slot_ids(*ht.table_items(), {3, 4, 5});
    check_lazy_probe_output_slot_ids(*ht.table_items(), {});
    check_lazy_build_output_slot_ids(*ht.table_items(), {});
    check_not_output_slot_ids(*ht.table_items(), {});
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, TestLazyPredicateSlotsEmpty) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = true;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.probe_output_slots = {1};
    param.build_output_slots = {4};
    param.predicate_slots = {};

    ht.create(param);

    ASSERT_EQ(ht.get_probe_column_count(), 3);
    ASSERT_EQ(ht.get_build_column_count(), 3);
    check_probe_output_slot_ids(*ht.table_items(), {0, 1, 2});
    check_build_output_slot_ids(*ht.table_items(), {3, 4, 5});
    check_lazy_probe_output_slot_ids(*ht.table_items(), {});
    check_lazy_build_output_slot_ids(*ht.table_items(), {});
    check_not_output_slot_ids(*ht.table_items(), {});
}

// NOLINTNEXTLINE
TEST_F(JoinHashMapTest, TestLazyPredicateSlotsNormal) {
    TDescriptorTableBuilder row_desc_builder;
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);
    add_tuple_descriptor(&row_desc_builder, LogicalType::TYPE_INT, false, 3);

    auto probe_row_desc = create_probe_desc(&row_desc_builder);
    auto build_row_desc = create_build_desc(&row_desc_builder);

    JoinHashTable ht;

    HashTableParam param;
    param.enable_late_materialization = true;
    param.probe_row_desc = probe_row_desc.get();
    param.build_row_desc = build_row_desc.get();
    param.probe_output_slots = {1};
    param.build_output_slots = {4};
    param.predicate_slots = {2, 5};

    ht.create(param);

    ASSERT_EQ(ht.get_probe_column_count(), 3);
    ASSERT_EQ(ht.get_build_column_count(), 3);
    check_probe_output_slot_ids(*ht.table_items(), {2});
    check_build_output_slot_ids(*ht.table_items(), {5});
    check_lazy_probe_output_slot_ids(*ht.table_items(), {1});
    check_lazy_build_output_slot_ids(*ht.table_items(), {4});
    check_not_output_slot_ids(*ht.table_items(), {0, 3});
}

TEST_F(JoinHashMapTest, TestBuildKeyConstructorForOneKeyNonNullable) {
    using BuildKeyBuilder = BuildKeyConstructorForOneKey<LogicalType::TYPE_INT>;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);

    JoinHashTableItems table_items;
    table_items.row_count = 10;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    auto build_column = ColumnHelper::create_column(int_type, false);
    build_column->append_datum(Datum(0));
    build_column->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    table_items.key_columns.emplace_back(std::move(build_column));

    BuildKeyBuilder::prepare(nullptr, &table_items);
    BuildKeyBuilder::build_key(nullptr, &table_items);

    const auto& keys = BuildKeyBuilder::get_key_data(table_items);
    ASSERT_EQ(keys.size(), 11);
    for (uint32_t i = 0; i < 10; ++i) {
        ASSERT_EQ(keys[1 + i], i);
    }

    const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
    ASSERT_EQ(is_nulls, nullptr);
}

TEST_F(JoinHashMapTest, TestBuildKeyConstructorForOneKeyNullable) {
    using BuildKeyBuilder = BuildKeyConstructorForOneKey<LogicalType::TYPE_INT>;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);

    JoinHashTableItems table_items;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    auto build_column = ColumnHelper::create_column(int_type, true);
    build_column->append_datum(Datum(0));
    build_column->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    table_items.key_columns.emplace_back(build_column);

    BuildKeyBuilder::prepare(nullptr, &table_items);

    {
        table_items.row_count = 10;
        BuildKeyBuilder::build_key(nullptr, &table_items);

        const auto& keys = BuildKeyBuilder::get_key_data(table_items);
        ASSERT_EQ(keys.size(), 11);
        for (uint32_t i = 0; i < 10; ++i) {
            ASSERT_EQ(keys[1 + i], i);
        }

        const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
        ASSERT_EQ(is_nulls, nullptr);
    }

    {
        build_column->append_nulls(3);
        table_items.row_count = 13;
        BuildKeyBuilder::build_key(nullptr, &table_items);

        const auto& keys = BuildKeyBuilder::get_key_data(table_items);
        ASSERT_EQ(keys.size(), 14);
        for (uint32_t i = 0; i < 10; ++i) {
            ASSERT_EQ(keys[1 + i], i);
        }

        const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
        for (uint32_t i = 0; i < 13; ++i) {
            ASSERT_EQ((*is_nulls)[1 + i] != 0, i >= 10);
        }
    }
}

TEST_F(JoinHashMapTest, TestBuildKeyConstructorForSerializedFixedSizeNonNullable) {
    using BuildKeyBuilder = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);

    JoinHashTableItems table_items;
    table_items.row_count = 10;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    auto build_column1 = ColumnHelper::create_column(int_type, false);
    build_column1->append_datum(Datum(0));
    build_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    table_items.key_columns.emplace_back(build_column1);
    auto build_column2 = ColumnHelper::create_column(int_type, false);
    build_column2->append_datum(Datum(0));
    build_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);
    table_items.key_columns.emplace_back(build_column2);

    BuildKeyBuilder::prepare(nullptr, &table_items);
    BuildKeyBuilder::build_key(nullptr, &table_items);

    const auto& keys = BuildKeyBuilder::get_key_data(table_items);
    ASSERT_EQ(keys.size(), 11);
    for (uint64_t i = 0; i < 10; ++i) {
        const uint64_t expected_value = ((100 + i) << 32) | i;
        ASSERT_EQ(keys[1 + i], expected_value);
    }

    const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
    ASSERT_EQ(is_nulls, nullptr);
}

TEST_F(JoinHashMapTest, TestBuildKeyConstructorForSerializedFixedSizeNullable) {
    using BuildKeyBuilder = BuildKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);

    JoinHashTableItems table_items;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    auto build_column1 = ColumnHelper::create_column(int_type, true);
    build_column1->append_datum(Datum(0));
    build_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    table_items.key_columns.emplace_back(build_column1);
    auto build_column2 = ColumnHelper::create_column(int_type, true);
    build_column2->append_datum(Datum(0));
    build_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);
    table_items.key_columns.emplace_back(build_column2);

    {
        table_items.row_count = 10;
        BuildKeyBuilder::prepare(nullptr, &table_items);
        BuildKeyBuilder::build_key(nullptr, &table_items);

        const auto& keys = BuildKeyBuilder::get_key_data(table_items);
        ASSERT_EQ(keys.size(), 11);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            ASSERT_EQ(keys[1 + i], expected_value);
        }

        const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
        ASSERT_EQ(is_nulls, nullptr);
    }

    {
        build_column1->append_nulls(3);
        build_column2->append_datum(Datum(1));
        build_column2->append_nulls(2);
        table_items.row_count = 13;
        BuildKeyBuilder::prepare(nullptr, &table_items);
        BuildKeyBuilder::build_key(nullptr, &table_items);

        const auto& keys = BuildKeyBuilder::get_key_data(table_items);
        ASSERT_EQ(keys.size(), 14);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            ASSERT_EQ(keys[1 + i], expected_value);
        }

        const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
        for (uint32_t i = 0; i < 13; ++i) {
            ASSERT_EQ((*is_nulls)[1 + i] != 0, i >= 10);
        }
    }
}

TEST_F(JoinHashMapTest, TestProbeKeyConstructorForSerializedFixedSizeNullable) {
    using ProbeKeyBuilder = ProbeKeyConstructorForSerializedFixedSize<LogicalType::TYPE_BIGINT>;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);
    JoinHashTableItems table_items;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    HashTableProbeState probe_state;

    auto probe_column1 = ColumnHelper::create_column(int_type, true);
    probe_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    auto probe_column2 = ColumnHelper::create_column(int_type, true);
    probe_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);

    Columns probe_columns{probe_column1, probe_column2};
    probe_state.key_columns = &probe_columns;

    ProbeKeyBuilder::prepare(_runtime_state.get(), &probe_state);

    {
        probe_state.probe_row_count = 10;
        ProbeKeyBuilder::build_key(table_items, &probe_state);

        const auto& keys = ProbeKeyBuilder::get_key_data(probe_state);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            ASSERT_EQ(keys[i], expected_value);
        }

        const auto* is_nulls = probe_state.null_array;
        ASSERT_EQ(is_nulls, nullptr);
    }

    {
        probe_column1->append_nulls(3);
        probe_column2->append_datum(Datum(1));
        probe_column2->append_nulls(2);
        probe_state.probe_row_count = 13;
        ProbeKeyBuilder::build_key(table_items, &probe_state);

        const auto& keys = ProbeKeyBuilder::get_key_data(probe_state);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            ASSERT_EQ(keys[i], expected_value);
        }

        const auto* is_nulls = probe_state.null_array;
        for (uint32_t i = 0; i < 13; ++i) {
            ASSERT_EQ((*is_nulls)[i] != 0, i >= 10);
        }
    }
}

TEST_F(JoinHashMapTest, TestBuildKeyConstructorForSerializedNullable) {
    using BuildKeyBuilder = BuildKeyConstructorForSerialized;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);

    JoinHashTableItems table_items;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    auto build_column1 = ColumnHelper::create_column(int_type, true);
    build_column1->append_datum(Datum(0));
    build_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    table_items.key_columns.emplace_back(build_column1);
    auto build_column2 = ColumnHelper::create_column(int_type, true);
    build_column2->append_datum(Datum(0));
    build_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);
    table_items.key_columns.emplace_back(build_column2);

    {
        table_items.row_count = 10;
        BuildKeyBuilder::prepare(_runtime_state.get(), &table_items);
        BuildKeyBuilder::build_key(_runtime_state.get(), &table_items);

        const auto& keys = BuildKeyBuilder::get_key_data(table_items);
        ASSERT_EQ(keys.size(), 11);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            Slice expected_slice(reinterpret_cast<const char*>(&expected_value), sizeof(expected_value));
            ASSERT_EQ(keys[1 + i], expected_slice);
        }

        const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
        ASSERT_EQ(is_nulls, nullptr);
    }

    {
        build_column1->append_nulls(3);
        build_column2->append_datum(Datum(1));
        build_column2->append_nulls(2);
        table_items.row_count = 13;
        BuildKeyBuilder::prepare(_runtime_state.get(), &table_items);
        BuildKeyBuilder::build_key(_runtime_state.get(), &table_items);

        const auto& keys = BuildKeyBuilder::get_key_data(table_items);
        ASSERT_EQ(keys.size(), 14);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            Slice expected_slice(reinterpret_cast<const char*>(&expected_value), sizeof(expected_value));
            ASSERT_EQ(keys[1 + i], expected_slice);
        }

        const auto* is_nulls = BuildKeyBuilder::get_is_nulls(table_items);
        for (uint32_t i = 0; i < 13; ++i) {
            ASSERT_EQ((*is_nulls)[1 + i] != 0, i >= 10);
        }
    }
}

TEST_F(JoinHashMapTest, TestProbeKeyConstructorForSerializedNullable) {
    using ProbeKeyBuilder = ProbeKeyConstructorForSerialized;

    const auto int_type = TypeDescriptor::from_logical_type(LogicalType::TYPE_INT);
    JoinHashTableItems table_items;
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});
    table_items.join_keys.emplace_back(JoinKeyDesc{&int_type, false, nullptr});

    HashTableProbeState probe_state;

    auto probe_column1 = ColumnHelper::create_column(int_type, true);
    probe_column1->append(*JoinHashMapTest::create_int32_column(10, 0), 0, 10);
    auto probe_column2 = ColumnHelper::create_column(int_type, true);
    probe_column2->append(*JoinHashMapTest::create_int32_column(10, 100), 0, 10);

    Columns probe_columns{probe_column1, probe_column2};
    probe_state.key_columns = &probe_columns;

    ProbeKeyBuilder::prepare(_runtime_state.get(), &probe_state);

    {
        probe_state.probe_row_count = 10;
        ProbeKeyBuilder::build_key(table_items, &probe_state);

        const auto& keys = ProbeKeyBuilder::get_key_data(probe_state);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            Slice expected_slice(reinterpret_cast<const char*>(&expected_value), sizeof(expected_value));
            ASSERT_EQ(keys[i], expected_slice);
        }

        const auto* is_nulls = probe_state.null_array;
        ASSERT_EQ(is_nulls, nullptr);
    }

    {
        probe_column1->append_nulls(3);
        probe_column2->append_datum(Datum(1));
        probe_column2->append_nulls(2);
        probe_state.probe_row_count = 13;
        ProbeKeyBuilder::build_key(table_items, &probe_state);

        const auto& keys = ProbeKeyBuilder::get_key_data(probe_state);
        for (uint64_t i = 0; i < 10; ++i) {
            const uint64_t expected_value = ((100 + i) << 32) | i;
            Slice expected_slice(reinterpret_cast<const char*>(&expected_value), sizeof(expected_value));
            ASSERT_EQ(keys[i], expected_slice);
        }

        const auto* is_nulls = probe_state.null_array;
        for (uint32_t i = 0; i < 13; ++i) {
            ASSERT_EQ((*is_nulls)[i] != 0, i >= 10);
        }
    }
}

} // namespace starrocks
