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

#include "column/vectorized_fwd.h"
#include "common/status.h"
#include "exprs/in_const_predicate.hpp"
#include "formats/parquet/metadata.h"
#include "formats/parquet/schema.h"
#include "runtime/types.h"

namespace starrocks::parquet {

class StatisticsHelper {
public:
    enum StatSupportedFilter { FILTER_IN, IS_NULL, IS_NOT_NULL };

    static Status decode_value_into_column(const ColumnPtr& column, const std::vector<std::string>& values,
                                           const TypeDescriptor& type, const ParquetField* field,
                                           const std::string& timezone);

    static bool can_be_used_for_statistics_filter(ExprContext* ctx, StatSupportedFilter& filter_type);

    static Status in_filter_on_min_max_stat(const std::vector<std::string>& min_values,
                                            const std::vector<std::string>& max_values, ExprContext* ctx,
                                            const ParquetField* field, const std::string& timezone, Filter& selected);

    // get min/max value from row group stats
    static Status get_min_max_value(const FileMetaData* file_meta_data, const TypeDescriptor& type,
                                    const tparquet::ColumnMetaData* column_meta, const ParquetField* field,
                                    std::vector<std::string>& min_values, std::vector<std::string>& max_values);

    static Status get_has_nulls(const tparquet::ColumnMetaData* column_meta, std::vector<bool>& has_nulls);

    static bool has_correct_min_max_stats(const FileMetaData* file_metadata,
                                          const tparquet::ColumnMetaData& column_meta, const SortOrder& sort_order);
};

} // namespace starrocks::parquet
