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

#pragma once

#include "column/fixed_length_column.h"
#include "column/vectorized_fwd.h"
#include "common/logging.h"

namespace starrocks {

using NullData = FixedLengthColumn<uint8_t>::Container;
using NullColumn = FixedLengthColumn<uint8_t>;
using NullColumnPtr = NullColumn::Ptr;
using NullColumns = std::vector<NullColumnPtr>;

using NullValueType = NullColumn::ValueType;
static constexpr NullValueType DATUM_NULL = NullValueType(1);
static constexpr NullValueType DATUM_NOT_NULL = NullValueType(0);

class NullableColumn : public CowFactory<ColumnFactory<Column, NullableColumn>, NullableColumn> {
    friend class CowFactory<ColumnFactory<Column, NullableColumn>, NullableColumn>;

public:
    using ValueType = void;

    inline static ColumnPtr wrap_if_necessary(ColumnPtr column) {
        if (column->is_nullable()) {
            return column;
        }
        auto null = NullColumn::create(column->size(), 0);
        return NullableColumn::create(column->as_mutable_ptr(), null->as_mutable_ptr());
    }
    NullableColumn() = default;

    NullableColumn(MutableColumnPtr&& data_column, MutableColumnPtr&& null_column);
    NullableColumn(const NullableColumn& rhs)
            : _data_column(rhs._data_column->clone()),
              _null_column(NullColumn::static_pointer_cast(rhs._null_column->clone())),
              _has_null(rhs._has_null) {}

    NullableColumn(NullableColumn&& rhs) noexcept
            : _data_column(std::move(rhs._data_column)),
              _null_column(std::move(rhs._null_column)),
              _has_null(rhs._has_null) {}

    NullableColumn& operator=(const NullableColumn& rhs) {
        NullableColumn tmp(rhs);
        this->swap_column(tmp);
        return *this;
    }

    NullableColumn& operator=(NullableColumn&& rhs) noexcept {
        NullableColumn tmp(std::move(rhs));
        this->swap_column(tmp);
        return *this;
    }

    using Base = CowFactory<ColumnFactory<Column, NullableColumn>, NullableColumn>;
    static Ptr create(const ColumnPtr& data_column, const ColumnPtr& null_column) {
        return NullableColumn::create(data_column->as_mutable_ptr(), null_column->as_mutable_ptr());
    }

    template <typename... Args, typename = std::enable_if_t<IsMutableColumns<Args...>::value>>
    static MutablePtr create(Args&&... args) {
        return Base::create(std::forward<Args>(args)...);
    }

    ~NullableColumn() override = default;

    bool has_null() const override { return _has_null; }

    void set_has_null(bool has_null) { _has_null = _has_null | has_null; }

    // Update null element to default value
    void fill_null_with_default();

    void fill_default(const Filter& filter) override {}

    void update_has_null();

    bool is_nullable() const override { return true; }
    bool is_json() const override { return _data_column->is_json(); }
    bool is_array() const override { return _data_column->is_array(); }
    bool is_array_view() const override { return _data_column->is_array_view(); }

    bool is_null(size_t index) const override {
        DCHECK_EQ(_null_column->size(), _data_column->size());
        return _has_null && immutable_null_column_data()[index];
    }

    const uint8_t* raw_data() const override { return _data_column->raw_data(); }

    uint8_t* mutable_raw_data() override { return reinterpret_cast<uint8_t*>(_data_column->mutable_raw_data()); }

    size_t size() const override {
        DCHECK_EQ(_data_column->size(), _null_column->size());
        return _data_column->size();
    }

    size_t capacity() const override { return _data_column->capacity(); }

    size_t type_size() const override { return _data_column->type_size() + _null_column->type_size(); }

    size_t byte_size() const override { return byte_size(0, size()); }

    size_t byte_size(size_t from, size_t size) const override {
        DCHECK_LE(from + size, this->size()) << "Range error";
        return _data_column->byte_size(from, size) + _null_column->byte_size(from, size);
    }

    size_t byte_size(size_t idx) const override { return _data_column->byte_size(idx) + sizeof(bool); }

    void reserve(size_t n) override {
        _data_column->reserve(n);
        _null_column->reserve(n);
    }

    void resize(size_t n) override {
        _data_column->resize(n);
        _null_column->resize(n);
    }

    void resize_uninitialized(size_t n) override {
        _data_column->resize_uninitialized(n);
        _null_column->resize_uninitialized(n);
    }

    void assign(size_t n, size_t idx) override {
        _data_column->assign(n, idx);
        _null_column->assign(n, idx);
    }

    void remove_first_n_values(size_t count) override;

    void append_datum(const Datum& datum) override;

    void append(const Column& src, size_t offset, size_t count) override;

    void append_selective(const Column& src, const uint32_t* indexes, uint32_t from, uint32_t size) override;

    void append_value_multiple_times(const Column& src, uint32_t index, uint32_t size) override;

    bool append_nulls(size_t count) override;

    StatusOr<ColumnPtr> upgrade_if_overflow() override;

    StatusOr<ColumnPtr> downgrade() override;

    bool has_large_column() const override { return _data_column->has_large_column(); }

    bool append_strings(const Slice* data, size_t size) override;

    bool append_strings_overflow(const Slice* data, size_t size, size_t max_length) override;

    bool append_continuous_strings(const Slice* data, size_t size) override;

    bool append_continuous_fixed_length_strings(const char* data, size_t size, int fixed_length) override;

    size_t append_numbers(const void* buff, size_t length) override;

    void append_value_multiple_times(const void* value, size_t count) override;

    void append_default() override { append_nulls(1); }

    void append_default_not_null_value() {
        _data_column->append_default();
        _null_column->append(0);
    }

    void append_default(size_t count) override { append_nulls(count); }

    void update_rows(const Column& src, const uint32_t* indexes) override;

    uint32_t max_one_element_serialize_size() const override {
        return sizeof(bool) + _data_column->max_one_element_serialize_size();
    }

    uint32_t serialize(size_t idx, uint8_t* pos) const override;

    uint32_t serialize_default(uint8_t* pos) const override;

    void serialize_batch(uint8_t* dst, Buffer<uint32_t>& slice_sizes, size_t chunk_size,
                         uint32_t max_one_row_size) const override;

    const uint8_t* deserialize_and_append(const uint8_t* pos) override;

    void deserialize_and_append_batch(Buffer<Slice>& srcs, size_t chunk_size) override;

    uint32_t serialize_size(size_t idx) const override {
        if (immutable_null_column_data()[idx]) {
            return sizeof(uint8_t);
        }
        return sizeof(uint8_t) + _data_column->serialize_size(idx);
    }

    MutableColumnPtr clone_empty() const override {
        return create(_data_column->clone_empty(), _null_column->clone_empty());
    }

    size_t serialize_batch_at_interval(uint8_t* dst, size_t byte_offset, size_t byte_interval, size_t start,
                                       size_t count) const override;

    size_t filter_range(const Filter& filter, size_t from, size_t to) override;

    int compare_at(size_t left, size_t right, const Column& rhs, int nan_direction_hint) const override;

    int equals(size_t left, const Column& rhs, size_t right, bool safe_eq = true) const override;

    void fnv_hash(uint32_t* hash, uint32_t from, uint32_t to) const override;
    void fnv_hash_with_selection(uint32_t* seed, uint8_t* selection, uint16_t from, uint16_t to) const override;
    void fnv_hash_selective(uint32_t* hash, uint16_t* sel, uint16_t sel_size) const override;
    void crc32_hash(uint32_t* hash, uint32_t from, uint32_t to) const override;
    void crc32_hash_with_selection(uint32_t* seed, uint8_t* selection, uint16_t from, uint16_t to) const override;
    void crc32_hash_selective(uint32_t* hash, uint16_t* sel, uint16_t sel_size) const override;

    void murmur_hash3_x86_32(uint32_t* hash, uint32_t from, uint32_t to) const override;

    int64_t xor_checksum(uint32_t from, uint32_t to) const override;

    void put_mysql_row_buffer(MysqlRowBuffer* buf, size_t idx, bool is_binary_protocol = false) const override;

    std::string get_name() const override { return "nullable-" + _data_column->get_name(); }

    const ColumnPtr& data_column() const { return _data_column; }
    ColumnPtr& data_column() { return _data_column; }
    MutableColumnPtr data_column_mutable_ptr() { return _data_column->as_mutable_ptr(); }

    const NullColumnPtr& null_column() const { return _null_column; }
    NullColumnPtr& null_column() { return _null_column; }
    NullColumn::MutablePtr null_column_mutable_ptr() {
        return NullColumn::static_pointer_cast(_null_column->as_mutable_ptr());
    }

    const Column& data_column_ref() const { return *_data_column; }
    Column& data_column_ref() { return *_data_column; }
    NullColumn& null_column_ref() { return *_null_column; }
    const NullColumn& null_column_ref() const { return *_null_column; }

    NullData& null_column_data() { return _null_column->get_data(); }
    const NullData& null_column_data() const { return _null_column->get_data(); }
    const NullData& immutable_null_column_data() const { return _null_column->get_data(); }

    const Column* immutable_data_column() const { return _data_column.get(); }

    Column* mutable_data_column() { return _data_column.get(); }
    // TODO(COW): remove const_cast
    NullColumn* mutable_null_column() const { return const_cast<NullColumn*>(_null_column.get()); }
    const NullColumn* immutable_null_column() const { return _null_column.get(); }

    size_t null_count() const;
    size_t null_count(size_t offset, size_t count) const;

    Datum get(size_t n) const override {
        if (_has_null && (immutable_null_column_data()[n])) {
            return {};
        } else {
            return _data_column->get(n);
        }
    }

    bool set_null(size_t idx) override {
        null_column_data()[idx] = 1;
        _has_null = true;
        return true;
    }
    StatusOr<ColumnPtr> replicate(const Buffer<uint32_t>& offsets) override;

    size_t memory_usage() const override {
        return _data_column->memory_usage() + _null_column->memory_usage() + sizeof(bool);
    }

    size_t container_memory_usage() const override {
        return _data_column->container_memory_usage() + _null_column->container_memory_usage();
    }

    size_t reference_memory_usage(size_t from, size_t size) const override {
        DCHECK_LE(from + size, this->size()) << "Range error";
        return _data_column->reference_memory_usage(from, size) + _null_column->reference_memory_usage(from, size);
    }

    void swap_column(Column& rhs) override {
        auto& r = down_cast<NullableColumn&>(rhs);
        _data_column->swap_column(*r._data_column);
        _null_column->swap_column(*r._null_column);
        std::swap(_delete_state, r._delete_state);
        std::swap(_has_null, r._has_null);
    }

    void swap_by_data_column(ColumnPtr& src) {
        reset_column();
        _data_column = std::move(src);
        null_column_data().insert(null_column_data().end(), _data_column->size(), 0);
        update_has_null();
    }

    void swap_null_column(Column& rhs) {
        auto& r = down_cast<NullableColumn&>(rhs);
        _null_column->swap_column(*r._null_column);
        std::swap(_has_null, r._has_null);
    }

    void reset_column() override {
        Column::reset_column();
        _data_column->reset_column();
        _null_column->reset_column();
        _has_null = false;
    }

    std::string debug_item(size_t idx) const override {
        DCHECK_EQ(_null_column->size(), _data_column->size());
        std::stringstream ss;
        if (immutable_null_column_data()[idx]) {
            ss << "NULL";
        } else {
            ss << _data_column->debug_item(idx);
        }
        return ss.str();
    }

    std::string debug_string() const override {
        DCHECK_EQ(_null_column->size(), _data_column->size());
        std::stringstream ss;
        ss << "[";
        size_t size = _data_column->size();
        for (size_t i = 0; i + 1 < size; ++i) {
            ss << debug_item(i) << ", ";
        }
        if (size > 0) {
            ss << debug_item(size - 1);
        }
        ss << "]";
        return ss.str();
    }

    Status capacity_limit_reached() const override {
        RETURN_IF_ERROR(_data_column->capacity_limit_reached());
        return _null_column->capacity_limit_reached();
    }

    void check_or_die() const override;

    void mutate_each_subcolumn() override {
        // data
        _data_column = (std::move(*_data_column)).mutate();
        // _null_column
        _null_column = NullColumn::static_pointer_cast((std::move(*_null_column)).mutate());
    }

protected:
    ColumnPtr _data_column;
    NullColumnPtr _null_column;
    mutable bool _has_null;
};

} // namespace starrocks
