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

#include "exec/schema_scanner/sys_fe_locks.h"

#include "exec/schema_scanner/schema_helper.h"
#include "gen_cpp/FrontendService_types.h"
#include "runtime/runtime_state.h"
#include "types/logical_type.h"

namespace starrocks {

SchemaScanner::ColumnDesc SysFeLocks::_s_columns[] = {
        {"lock_type", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), true},
        {"lock_object", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), true},
        {"lock_mode", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), true},
        {"start_time", TypeDescriptor::from_logical_type(TYPE_DATETIME), sizeof(DateTimeValue), true},
        {"hold_time_ms", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(long), true},
        {"thread_info", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), true},
        {"granted", TypeDescriptor::from_logical_type(TYPE_BOOLEAN), sizeof(bool), true},
        {"waiter_list", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), true},

};

SysFeLocks::SysFeLocks() : SchemaScanner(_s_columns, sizeof(_s_columns) / sizeof(SchemaScanner::ColumnDesc)) {}

SysFeLocks::~SysFeLocks() = default;

Status SysFeLocks::start(RuntimeState* state) {
    RETURN_IF(!_is_init, Status::InternalError("used before initialized."));
    RETURN_IF(!_param->ip || !_param->port, Status::InternalError("IP or port not exists"));

    RETURN_IF_ERROR(SchemaScanner::start(state));
    RETURN_IF_ERROR(SchemaScanner::init_schema_scanner_state(state));

    TAuthInfo auth = build_auth_info();
    TFeLocksReq request;
    request.__set_auth_info(auth);
    return SchemaHelper::list_fe_locks(_ss_state, request, &_result);
}

Status SysFeLocks::_fill_chunk(ChunkPtr* chunk) {
    auto& slot_id_map = (*chunk)->get_slot_id_to_index_map();
    const TFeLocksItem& info = _result.items[_index];
    auto start_time = TimestampValue::create_from_unixtime(info.start_time / 1000, _runtime_state->timezone_obj());
    DatumArray datum_array{
            // clang-format: off
            Slice(info.lock_type), Slice(info.lock_object), Slice(info.lock_mode), start_time,

            info.hold_time_ms,     Slice(info.thread_info), info.granted,          Slice(info.waiter_list),
            // clang-format: on
    };
    for (const auto& [slot_id, index] : slot_id_map) {
        Column* column = (*chunk)->get_column_by_slot_id(slot_id).get();
        column->append_datum(datum_array[slot_id - 1]);
    }
    _index++;
    return {};
}

Status SysFeLocks::get_next(ChunkPtr* chunk, bool* eos) {
    RETURN_IF(!_is_init, Status::InternalError("Used before initialized."));
    RETURN_IF((nullptr == chunk || nullptr == eos), Status::InternalError("input pointer is nullptr."));

    if (_index >= _result.items.size()) {
        *eos = true;
        return Status::OK();
    }
    *eos = false;
    return _fill_chunk(chunk);
}

} // namespace starrocks