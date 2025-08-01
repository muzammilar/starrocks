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

#include "exec/tablet_info.h"

#include "column/binary_column.h"
#include "column/chunk.h"
#include "exprs/expr.h"
#include "runtime/mem_pool.h"
#include "storage/tablet_schema.h"
#include "types/constexpr.h"
#include "util/string_parser.hpp"

namespace starrocks {

// NOTE: This value should keep the same with the value in FE's `STARROCKS_DEFAULT_PARTITION_VALUE` constant.
static const std::string STARROCKS_DEFAULT_PARTITION_VALUE = "__STARROCKS_DEFAULT_PARTITION__";

struct VectorCompare {
    bool operator()(const std::vector<std::string>& a, const std::vector<std::string>& b) const {
        if (a.size() != b.size()) {
            return a.size() < b.size();
        }

        for (size_t i = 0; i < a.size(); ++i) {
            if (a[i] != b[i]) {
                return a[i] < b[i];
            }
        }

        return false;
    }
};

std::string ChunkRow::debug_string() {
    std::stringstream os;
    os << "index " << index << " [";
    if (columns && columns->size() > 0) {
        for (size_t col = 0; col < columns->size() - 1; ++col) {
            os << (*columns)[col]->debug_item(index);
            os << ", ";
        }
        os << (*columns)[columns->size() - 1]->debug_item(index);
    }
    os << "]";
    return os.str();
}

void OlapTableColumnParam::to_protobuf(POlapTableColumnParam* pcolumn) const {
    pcolumn->set_short_key_column_count(short_key_column_count);
    for (auto uid : sort_key_uid) {
        pcolumn->add_sort_key_uid(uid);
    }
    for (auto& column : columns) {
        column->to_schema_pb(pcolumn->add_columns_desc());
    }
}

void OlapTableIndexSchema::to_protobuf(POlapTableIndexSchema* pindex) const {
    pindex->set_id(index_id);
    pindex->set_schema_hash(schema_hash);
    pindex->set_schema_id(schema_id);
    pindex->set_is_shadow(is_shadow);
    for (auto slot : slots) {
        pindex->add_columns(slot->col_name());
    }
    if (column_param != nullptr) {
        column_param->to_protobuf(pindex->mutable_column_param());
    }
    for (auto& [name, value] : column_to_expr_value) {
        pindex->mutable_column_to_expr_value()->insert({name, value});
    }
}

Status OlapTableSchemaParam::init(const POlapTableSchemaParam& pschema) {
    _db_id = pschema.db_id();
    _table_id = pschema.table_id();
    _version = pschema.version();
    std::map<std::string, SlotDescriptor*> slots_map;
    _tuple_desc = _obj_pool.add(new TupleDescriptor(pschema.tuple_desc()));
    for (auto& p_slot_desc : pschema.slot_descs()) {
        auto slot_desc = _obj_pool.add(new SlotDescriptor(p_slot_desc));
        _tuple_desc->add_slot(slot_desc);
        slots_map.emplace(slot_desc->col_name(), slot_desc);
    }
    for (auto& p_index : pschema.indexes()) {
        auto index = _obj_pool.add(new OlapTableIndexSchema());
        index->index_id = p_index.id();
        index->schema_hash = p_index.schema_hash();
        for (auto& col : p_index.columns()) {
            auto it = slots_map.find(col);
            if (it != std::end(slots_map)) {
                index->slots.emplace_back(it->second);
            }
        }

        if (p_index.has_column_param()) {
            auto col_param = _obj_pool.add(new OlapTableColumnParam());
            for (auto& pcolumn_desc : p_index.column_param().columns_desc()) {
                TabletColumn* tc = _obj_pool.add(new TabletColumn());
                tc->init_from_pb(pcolumn_desc);
                col_param->columns.emplace_back(tc);
            }
            for (auto& uid : p_index.column_param().sort_key_uid()) {
                col_param->sort_key_uid.emplace_back(uid);
            }
            col_param->short_key_column_count = p_index.column_param().short_key_column_count();
            index->column_param = col_param;
        }
        if (p_index.has_schema_id() && p_index.schema_id() > 0) {
            //                         ^^^^^^^^^^^^^^^^^^^^^^^ Older version FE may incorrectly set the schema id to 0
            index->schema_id = p_index.schema_id();
        } else {
            index->schema_id = p_index.id();
        }

        for (auto& entry : p_index.column_to_expr_value()) {
            index->column_to_expr_value.insert({entry.first, entry.second});
        }

        if (p_index.has_is_shadow()) {
            index->is_shadow = p_index.is_shadow();
            if (index->is_shadow) {
                _shadow_indexes++;
            }
        }

        _indexes.emplace_back(index);
    }

    std::sort(_indexes.begin(), _indexes.end(), [](const OlapTableIndexSchema* lhs, const OlapTableIndexSchema* rhs) {
        return lhs->index_id < rhs->index_id;
    });
    return Status::OK();
}

Status OlapTableSchemaParam::init(const TOlapTableSchemaParam& tschema, RuntimeState* state) {
    _db_id = tschema.db_id;
    _table_id = tschema.table_id;
    _version = tschema.version;
    std::map<std::string, SlotDescriptor*> slots_map;
    _tuple_desc = _obj_pool.add(new TupleDescriptor(tschema.tuple_desc));
    for (auto& t_slot_desc : tschema.slot_descs) {
        auto slot_desc = _obj_pool.add(new SlotDescriptor(t_slot_desc));
        _tuple_desc->add_slot(slot_desc);
        slots_map.emplace(slot_desc->col_name(), slot_desc);
    }
    for (auto& t_index : tschema.indexes) {
        auto index = _obj_pool.add(new OlapTableIndexSchema());
        index->index_id = t_index.id;
        index->schema_hash = t_index.schema_hash;
        for (auto& col : t_index.columns) {
            auto it = slots_map.find(col);
            if (it != std::end(slots_map)) {
                index->slots.emplace_back(it->second);
            }
        }

        if (t_index.__isset.column_param) {
            auto col_param = _obj_pool.add(new OlapTableColumnParam());
            for (auto& tcolumn_desc : t_index.column_param.columns) {
                TabletColumn* tc = _obj_pool.add(new TabletColumn());
                tc->init_from_thrift(tcolumn_desc);
                col_param->columns.emplace_back(tc);
            }
            for (auto& uid : t_index.column_param.sort_key_uid) {
                col_param->sort_key_uid.emplace_back(uid);
            }
            col_param->short_key_column_count = t_index.column_param.short_key_column_count;
            index->column_param = col_param;
        }
        if (t_index.__isset.where_clause) {
            RETURN_IF_ERROR(Expr::create_expr_tree(&_obj_pool, t_index.where_clause, &index->where_clause, state));
        }
        if (t_index.__isset.schema_id) {
            index->schema_id = t_index.schema_id;
        } else {
            // schema id is same with index id in previous version, for compatibility
            index->schema_id = t_index.id;
        }

        if (t_index.__isset.column_to_expr_value) {
            for (auto& entry : t_index.column_to_expr_value) {
                index->column_to_expr_value.insert({entry.first, entry.second});
            }
        }
        if (t_index.__isset.is_shadow) {
            index->is_shadow = t_index.is_shadow;
            if (index->is_shadow) {
                _shadow_indexes++;
            }
        }
        _indexes.emplace_back(index);
    }

    std::sort(_indexes.begin(), _indexes.end(), [](const OlapTableIndexSchema* lhs, const OlapTableIndexSchema* rhs) {
        return lhs->index_id < rhs->index_id;
    });
    return Status::OK();
}

void OlapTableSchemaParam::to_protobuf(POlapTableSchemaParam* pschema) const {
    pschema->set_db_id(_db_id);
    pschema->set_table_id(_table_id);
    pschema->set_version(_version);
    _tuple_desc->to_protobuf(pschema->mutable_tuple_desc());
    for (auto slot : _tuple_desc->slots()) {
        slot->to_protobuf(pschema->add_slot_descs());
    }
    for (auto index : _indexes) {
        index->to_protobuf(pschema->add_indexes());
    }
}

std::string OlapTableSchemaParam::debug_string() const {
    std::stringstream ss;
    ss << "tuple_desc=" << _tuple_desc->debug_string();
    return ss.str();
}

OlapTablePartitionParam::OlapTablePartitionParam(std::shared_ptr<OlapTableSchemaParam> schema,
                                                 const TOlapTablePartitionParam& t_param)
        : _schema(std::move(schema)), _t_param(t_param) {}

OlapTablePartitionParam::~OlapTablePartitionParam() = default;

Status OlapTablePartitionParam::init(RuntimeState* state) {
    std::map<std::string, SlotDescriptor*> slots_map;
    for (auto slot_desc : _schema->tuple_desc()->slots()) {
        slots_map.emplace(slot_desc->col_name(), slot_desc);
    }

    for (auto& part_col : _t_param.partition_columns) {
        auto it = slots_map.find(part_col);
        if (it == std::end(slots_map)) {
            std::stringstream ss;
            ss << "partition column not found, column=" << part_col;
            LOG(WARNING) << ss.str();
            return Status::InternalError(ss.str());
        }
        _partition_slot_descs.push_back(it->second);
    }
    _partition_columns.resize(_partition_slot_descs.size());

    if (_t_param.__isset.distributed_columns) {
        for (auto& col : _t_param.distributed_columns) {
            auto it = slots_map.find(col);
            if (it == std::end(slots_map)) {
                std::stringstream ss;
                ss << "distributed column not found, columns=" << col;
                return Status::InternalError(ss.str());
            }
            _distributed_slot_descs.emplace_back(it->second);
        }
    }
    _distributed_columns.resize(_distributed_slot_descs.size());

    if (_t_param.__isset.partition_exprs && _t_param.partition_exprs.size() > 0) {
        if (state == nullptr) {
            return Status::InternalError("state is null when partition_exprs is not empty");
        }
        RETURN_IF_ERROR(Expr::create_expr_trees(&_obj_pool, _t_param.partition_exprs, &_partitions_expr_ctxs, state));
    }

    // initial partitions
    for (auto& t_part : _t_param.partitions) {
        OlapTablePartition* part = _obj_pool.add(new OlapTablePartition());
        part->id = t_part.id;
        auto num_indexes = _schema->indexes().size();
        if (t_part.indexes.size() != num_indexes) {
            std::stringstream ss;
            ss << "number of partition's index is not equal with schema's"
               << ", num_part_indexes=" << t_part.indexes.size() << ", num_schema_indexes=" << num_indexes;
            LOG(WARNING) << ss.str();
            return Status::InternalError(ss.str());
        }
        part->indexes = t_part.indexes;
        std::sort(part->indexes.begin(), part->indexes.end(),
                  [](const OlapTableIndexTablets& lhs, const OlapTableIndexTablets& rhs) {
                      return lhs.index_id < rhs.index_id;
                  });

        // If virtual buckets is not set, set its value with tablets.
        // This may happen during cluster upgrading, when BE is upgraded to the new version but FE is still on the old version.
        for (auto& index : part->indexes) {
            if (!index.__isset.virtual_buckets) {
                index.__set_virtual_buckets(index.tablets);
            }
        }

        // check index
        for (int j = 0; j < num_indexes; ++j) {
            const auto& index_tablets = part->indexes[j];
            const auto& index_schema = _schema->indexes()[j];
            if (index_tablets.index_id != index_schema->index_id) {
                std::stringstream ss;
                ss << "partition's index is not equal with schema's"
                   << ", part_index=" << index_tablets.index_id << ", schema_index=" << index_schema->index_id;
                LOG(WARNING) << ss.str();
                return Status::InternalError(ss.str());
            }
        }
        _partitions.emplace(part->id, part);

        if (t_part.is_shadow_partition) {
            VLOG(2) << "add shadow partition:" << part->id;
            continue;
        }

        if (t_part.__isset.start_keys) {
            RETURN_IF_ERROR_WITH_WARN(_create_partition_keys(t_part.start_keys, &part->start_key), "start_keys");
        }

        if (t_part.__isset.end_keys) {
            RETURN_IF_ERROR_WITH_WARN(_create_partition_keys(t_part.end_keys, &part->end_key), "end_keys");
        }

        if (t_part.__isset.in_keys) {
            part->in_keys.resize(t_part.in_keys.size());
            for (int i = 0; i < t_part.in_keys.size(); i++) {
                RETURN_IF_ERROR_WITH_WARN(_create_partition_keys(t_part.in_keys[i], &part->in_keys[i]), "in_keys");
            }
        }

        if (t_part.__isset.in_keys) {
            for (auto& in_key : part->in_keys) {
                _partitions_map[&in_key].push_back(part->id);
            }
        } else {
            _partitions_map[&part->end_key].push_back(part->id);
            VLOG(2) << "add partition:" << part->id << " start " << part->start_key.debug_string() << " end "
                    << part->end_key.debug_string();
        }
    }

    return Status::OK();
}

Status OlapTablePartitionParam::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(Expr::prepare(_partitions_expr_ctxs, state));
    return Status::OK();
}

Status OlapTablePartitionParam::open(RuntimeState* state) {
    RETURN_IF_ERROR(Expr::open(_partitions_expr_ctxs, state));
    return Status::OK();
}

void OlapTablePartitionParam::close(RuntimeState* state) {
    Expr::close(_partitions_expr_ctxs, state);
}

Status OlapTablePartitionParam::_create_partition_keys(const std::vector<TExprNode>& t_exprs, ChunkRow* part_key) {
    if (t_exprs.size() != _partition_columns.size()) {
        return Status::InternalError(fmt::format("partition expr size {} not equal partition column size {}",
                                                 t_exprs.size(), _partition_columns.size()));
    }
    DCHECK_EQ(_partition_slot_descs.size(), _partition_columns.size());

    for (int i = 0; i < t_exprs.size(); i++) {
        const TExprNode& t_expr = t_exprs[i];
        const auto& type_desc = TypeDescriptor::from_thrift(t_expr.type);
        const auto type = type_desc.type;
        bool is_nullable = _partition_slot_descs[i]->is_nullable();
        if (_partition_columns[i] == nullptr) {
            _partition_columns[i] = ColumnHelper::create_column(type_desc, is_nullable);
        }
        if (is_nullable) {
            auto column = ColumnHelper::as_raw_column<NullableColumn>(_partition_columns[i]);
            // handle null partition value
            if (t_expr.node_type == TExprNodeType::NULL_LITERAL) {
                DCHECK(t_expr.is_nullable);
                DCHECK(is_nullable);
                column->append_nulls(1);
                continue;
            } else {
                // append not null value
                column->mutable_null_column()->append(0);
            }
        }

        // unwrap nullable column since partition column can be nullable
        auto* partition_data_column = ColumnHelper::get_data_column(_partition_columns[i].get());
        switch (type) {
        case TYPE_DATE: {
            DateValue v;
            if (v.from_string(t_expr.date_literal.value.c_str(), t_expr.date_literal.value.size())) {
                auto* column = down_cast<DateColumn*>(partition_data_column);
                column->get_data().emplace_back(v);
            } else {
                std::stringstream ss;
                ss << "invalid date literal in partition column, date=" << t_expr.date_literal;
                return Status::InternalError(ss.str());
            }
            break;
        }
        case TYPE_DATETIME: {
            TimestampValue v;
            if (v.from_string(t_expr.date_literal.value.c_str(), t_expr.date_literal.value.size())) {
                auto* column = down_cast<TimestampColumn*>(partition_data_column);
                column->get_data().emplace_back(v);
            } else {
                std::stringstream ss;
                ss << "invalid date literal in partition column, date=" << t_expr.date_literal;
                return Status::InternalError(ss.str());
            }
            break;
        }
        case TYPE_TINYINT: {
            auto* column = down_cast<Int8Column*>(partition_data_column);
            column->get_data().emplace_back(t_expr.int_literal.value);
            break;
        }
        case TYPE_SMALLINT: {
            auto* column = down_cast<Int16Column*>(partition_data_column);
            column->get_data().emplace_back(t_expr.int_literal.value);
            break;
        }
        case TYPE_INT: {
            auto* column = down_cast<Int32Column*>(partition_data_column);
            column->get_data().emplace_back(t_expr.int_literal.value);
            break;
        }
        case TYPE_BIGINT: {
            auto* column = down_cast<Int64Column*>(partition_data_column);
            column->get_data().emplace_back(t_expr.int_literal.value);
            break;
        }
        case TYPE_LARGEINT: {
            StringParser::ParseResult parse_result = StringParser::PARSE_SUCCESS;
            auto val = StringParser::string_to_int<__int128>(t_expr.large_int_literal.value.c_str(),
                                                             t_expr.large_int_literal.value.size(), &parse_result);
            if (parse_result != StringParser::PARSE_SUCCESS) {
                val = MAX_INT128;
            }
            auto* column = down_cast<Int128Column*>(partition_data_column);
            column->get_data().emplace_back(val);
            break;
        }
        case TYPE_VARCHAR: {
            int len = t_expr.string_literal.value.size();
            const char* str_val = t_expr.string_literal.value.c_str();
            Slice value(str_val, len);
            auto* column = down_cast<BinaryColumn*>(partition_data_column);
            column->append(value);
            break;
        }
        case TYPE_BOOLEAN: {
            auto* column = down_cast<BooleanColumn*>(partition_data_column);
            column->get_data().emplace_back(t_expr.bool_literal.value);
            break;
        }
        default: {
            std::stringstream ss;
            ss << "unsupported partition column node type, type=" << t_expr.node_type << ", logic type=" << type;
            LOG(WARNING) << ss.str();
            return Status::InternalError(ss.str());
        }
        }
    }

    part_key->columns = &_partition_columns;
    part_key->index = _partition_columns[0]->size() - 1;
    VLOG(3) << "create partition key:" << part_key->debug_string();
    return Status::OK();
}

Status OlapTablePartitionParam::add_partitions(const std::vector<TOlapTablePartition>& partitions) {
    for (auto& t_part : partitions) {
        if (_partitions.count(t_part.id) != 0) {
            continue;
        }

        OlapTablePartition* part = _obj_pool.add(new OlapTablePartition());
        part->id = t_part.id;
        if (t_part.__isset.start_keys) {
            RETURN_IF_ERROR_WITH_WARN(_create_partition_keys(t_part.start_keys, &part->start_key), "start_keys");
        }
        if (t_part.__isset.end_keys) {
            RETURN_IF_ERROR_WITH_WARN(_create_partition_keys(t_part.end_keys, &part->end_key), "end keys");
        }

        if (t_part.__isset.in_keys) {
            part->in_keys.resize(t_part.in_keys.size());
            for (int i = 0; i < t_part.in_keys.size(); i++) {
                RETURN_IF_ERROR_WITH_WARN(_create_partition_keys(t_part.in_keys[i], &part->in_keys[i]), "in_keys");
            }
        }

        auto num_indexes = _schema->indexes().size();
        if (t_part.indexes.size() != num_indexes - _schema->shadow_index_size()) {
            std::stringstream ss;
            ss << "number of partition's index is not equal with schema's"
               << ", num_part_indexes=" << t_part.indexes.size() << ", num_schema_indexes=" << num_indexes
               << ", num_shadow_indexes=" << _schema->shadow_index_size();
            LOG(WARNING) << ss.str();
            return Status::InternalError(ss.str());
        }
        part->indexes = t_part.indexes;
        std::sort(part->indexes.begin(), part->indexes.end(),
                  [](const OlapTableIndexTablets& lhs, const OlapTableIndexTablets& rhs) {
                      return lhs.index_id < rhs.index_id;
                  });

        // If virtual buckets is not set, set its value with tablets.
        // This may happen during cluster upgrading, when BE is upgraded to the new version but FE is still on the old version.
        for (auto& index : part->indexes) {
            if (!index.__isset.virtual_buckets) {
                index.__set_virtual_buckets(index.tablets);
            }
        }

        // check index
        // If an add_partition operation is executed during the ALTER process, the ALTER operation will be canceled first.
        // Therefore, the latest indexes will not include shadow indexes.
        // However, the schema's index may still contain shadow indexes, so these shadow indexes need to be ignored.
        int j = 0;
        for (int i = 0; i < num_indexes; ++i) {
            if (_schema->indexes()[i]->is_shadow) {
                continue;
            }
            if (part->indexes[j].index_id != _schema->indexes()[i]->index_id) {
                std::stringstream ss;
                ss << "partition's index is not equal with schema's"
                   << ", part_index=" << part->indexes[j].index_id
                   << ", schema_index=" << _schema->indexes()[i]->index_id;
                LOG(WARNING) << ss.str();
                return Status::InternalError(ss.str());
            }
            j++;
        }

        _partitions.emplace(part->id, part);
        if (t_part.__isset.in_keys) {
            for (auto& in_key : part->in_keys) {
                _partitions_map[&in_key].push_back(part->id);
                VLOG(2) << "add automatic partition:" << part->id << ", in_key:" << in_key.debug_string();
            }
        } else {
            _partitions_map[&part->end_key].push_back(part->id);
            VLOG(2) << "add automatic partition:" << part->id << " start " << part->start_key.debug_string() << " end "
                    << part->end_key.debug_string();
        }
    }

    return Status::OK();
}

Status OlapTablePartitionParam::remove_partitions(const std::vector<int64_t>& partition_ids) {
    for (auto& id : partition_ids) {
        auto it = _partitions.find(id);
        if (it == _partitions.end()) {
            continue;
        }
        auto part = it->second;
        if (part->in_keys.empty()) {
            auto& part_ids = _partitions_map[&part->end_key];
            part_ids.erase(std::remove(part_ids.begin(), part_ids.end(), id), part_ids.end());
            if (part_ids.empty()) {
                _partitions_map.erase(&part->end_key);
            }
        } else {
            for (auto& in_key : part->in_keys) {
                auto& part_ids = _partitions_map[&in_key];
                part_ids.erase(std::remove(part_ids.begin(), part_ids.end(), id), part_ids.end());
                if (part_ids.empty()) {
                    _partitions_map.erase(&in_key);
                }
            }
        }

        _partitions.erase(it);
    }
    LOG_IF(INFO, _partitions.empty()) << "Empty partitions for db:" << db_id() << ", table_id:" << table_id();

    return Status::OK();
}

Status OlapTablePartitionParam::_find_tablets_with_list_partition(
        Chunk* chunk, const Columns& partition_columns, const std::vector<uint32_t>& hashes,
        std::vector<OlapTablePartition*>* partitions, std::vector<uint8_t>* selection,
        std::vector<int>* invalid_row_indexs, std::vector<std::vector<std::string>>* partition_not_exist_row_values) {
    size_t num_rows = chunk->num_rows();
    ChunkRow row(&partition_columns, 0);

    std::vector<const Column*> partition_data_columns;
    partition_data_columns.reserve(partition_columns.size());
    for (auto& column : *(row.columns)) {
        partition_data_columns.emplace_back(ColumnHelper::get_data_column(column.get()));
    }

    int partition_column_size = partition_columns.size();
    std::set<std::vector<std::string>, VectorCompare> partition_columns_set;
    for (size_t i = 0; i < num_rows; ++i) {
        OlapTablePartition* part = nullptr;
        if (!((*selection)[i])) {
            continue;
        }
        row.index = i;
        // list partition
        auto it = _partitions_map.find(&row);
        if (it != _partitions_map.end() && (part = _partitions[it->second[hashes[i] % it->second.size()]]) != nullptr &&
            _part_contains(part, &row)) {
            (*partitions)[i] = part;
        } else {
            if (partition_not_exist_row_values) {
                auto partition_value_items = std::make_unique<std::vector<std::string>>();
                for (int j = 0; j < partition_column_size; ++j) {
                    auto& raw_column = (*(row.columns))[j];
                    if (raw_column->is_null(i)) {
                        partition_value_items->emplace_back(STARROCKS_DEFAULT_PARTITION_VALUE);
                    } else {
                        partition_value_items->emplace_back(partition_data_columns[j]->raw_item_value(i));
                    }
                }
                auto r = partition_columns_set.insert(*partition_value_items);
                if (r.second) {
                    (*partition_not_exist_row_values).emplace_back(*partition_value_items);
                }
            } else {
                VLOG(3) << "partition not exist chunk row:" << chunk->debug_row(i) << " partition row "
                        << row.debug_string();
                (*partitions)[i] = nullptr;
                (*selection)[i] = 0;
                if (invalid_row_indexs != nullptr) {
                    invalid_row_indexs->emplace_back(i);
                }
            }
        }
    }
    return Status::OK();
}

Status OlapTablePartitionParam::_find_tablets_with_range_partition(
        Chunk* chunk, const Columns& partition_columns, const std::vector<uint32_t>& hashes,
        std::vector<OlapTablePartition*>* partitions, std::vector<uint8_t>* selection,
        std::vector<int>* invalid_row_indexs, std::vector<std::vector<std::string>>* partition_not_exist_row_values) {
    size_t num_rows = chunk->num_rows();
    ChunkRow row(&partition_columns, 0);

    std::set<std::vector<std::string>, VectorCompare> partition_columns_set;
    for (size_t i = 0; i < num_rows; ++i) {
        OlapTablePartition* part = nullptr;
        if (!((*selection)[i])) {
            continue;
        }
        row.index = i;
        // range partition
        auto it = _partitions_map.upper_bound(&row);
        if (it != _partitions_map.end() && (part = _partitions[it->second[hashes[i] % it->second.size()]]) != nullptr &&
            _part_contains(part, &row)) {
            (*partitions)[i] = part;
        } else {
            if (partition_not_exist_row_values) {
                // only support single column partition for range partition now
                if (partition_columns.size() != 1) {
                    return Status::InternalError("automatic partition only support single column partition.");
                }
                auto partition_value_items = std::make_unique<std::vector<std::string>>();
                for (auto& column : *row.columns) {
                    VLOG(3) << "partition not exist chunk row:" << chunk->debug_row(i) << " partition row "
                            << row.debug_string();
                    partition_value_items->emplace_back(column->raw_item_value(i));
                }
                auto r = partition_columns_set.insert(*partition_value_items);
                if (r.second) {
                    (*partition_not_exist_row_values).emplace_back(*partition_value_items);
                }
            } else {
                VLOG(3) << "partition not exist chunk row:" << chunk->debug_row(i) << " partition row "
                        << row.debug_string();
                (*partitions)[i] = nullptr;
                (*selection)[i] = 0;
                if (invalid_row_indexs != nullptr) {
                    invalid_row_indexs->emplace_back(i);
                }
            }
        }
    }
    return Status::OK();
}

Status OlapTablePartitionParam::find_tablets(Chunk* chunk, std::vector<OlapTablePartition*>* partitions,
                                             std::vector<uint32_t>* hashes, std::vector<uint8_t>* selection,
                                             std::vector<int>* invalid_row_indexs, int64_t txn_id,
                                             std::vector<std::vector<std::string>>* partition_not_exist_row_values) {
    size_t num_rows = chunk->num_rows();
    partitions->resize(num_rows);

    _compute_hashes(chunk, hashes);

    if (!_partition_columns.empty()) {
        Columns partition_columns(_partition_slot_descs.size());
        if (!_partitions_expr_ctxs.empty()) {
            for (size_t i = 0; i < partition_columns.size(); ++i) {
                ASSIGN_OR_RETURN(partition_columns[i], _partitions_expr_ctxs[i]->evaluate(chunk));
                partition_columns[i] = ColumnHelper::unfold_const_column(_partition_slot_descs[i]->type(), num_rows,
                                                                         partition_columns[i]);
            }
        } else {
            for (size_t i = 0; i < partition_columns.size(); ++i) {
                partition_columns[i] = chunk->get_column_by_slot_id(_partition_slot_descs[i]->id());
                DCHECK(partition_columns[i] != nullptr);
            }
        }

        bool is_list_partition = _t_param.partitions[0].__isset.in_keys;
        if (is_list_partition) {
            return _find_tablets_with_list_partition(chunk, partition_columns, *hashes, partitions, selection,
                                                     invalid_row_indexs, partition_not_exist_row_values);
        } else {
            return _find_tablets_with_range_partition(chunk, partition_columns, *hashes, partitions, selection,
                                                      invalid_row_indexs, partition_not_exist_row_values);
        }
    } else {
        if (_partitions_map.empty()) {
            return Status::InternalError("no physical partitions");
        }
        auto& part_ids = _partitions_map.begin()->second;
        for (size_t i = 0; i < num_rows; ++i) {
            if ((*selection)[i]) {
                if (_partitions.empty()) {
                    // Don't know the reason yet, just defensive coding not crashing the process and possibly for further investigation
                    LOG(WARNING) << "empty partition for selection[i=" << i << "]=" << (*selection)[i]
                                 << ", db=" << db_id() << ", table_id=" << table_id();
                    return Status::InternalError(
                            fmt::format("empty partitions for db={}, table={}", db_id(), table_id()));
                }
                (*partitions)[i] = _partitions[part_ids[(*hashes)[i] % _partitions.size()]];
            }
        }
    }
    return Status::OK();
}

void OlapTablePartitionParam::_compute_hashes(const Chunk* chunk, std::vector<uint32_t>* hashes) {
    size_t num_rows = chunk->num_rows();
    hashes->assign(num_rows, 0);

    for (size_t i = 0; i < _distributed_slot_descs.size(); ++i) {
        _distributed_columns[i] = chunk->get_column_by_slot_id(_distributed_slot_descs[i]->id()).get();
        _distributed_columns[i]->crc32_hash(&(*hashes)[0], 0, num_rows);
    }

    // if no distributed columns, use random distribution
    if (_distributed_slot_descs.size() == 0) {
        uint32_t r = _rand.Next();
        for (auto i = 0; i < num_rows; ++i) {
            (*hashes)[i] = r++;
        }
    }
}

Status OlapTablePartitionParam::test_add_partitions(OlapTablePartition* partition) {
    _partitions[partition->id] = partition;
    std::vector<int64_t> part_ids{partition->id};
    if (partition->in_keys.empty()) {
        _partitions_map[&(partition->end_key)] = part_ids;
    } else {
        for (auto& in_key : partition->in_keys) {
            _partitions_map[&in_key] = part_ids;
        }
    }
    return Status::OK();
}

} // namespace starrocks
