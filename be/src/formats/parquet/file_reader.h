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

#include <cstdint>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "cache/block_cache/block_cache.h"
#include "column/vectorized_fwd.h"
#include "common/status.h"
#include "common/statusor.h"
#include "formats/parquet/group_reader.h"
#include "formats/parquet/meta_helper.h"
#include "formats/parquet/metadata.h"
#include "gen_cpp/parquet_types.h"
#include "io/shared_buffered_input_stream.h"
#include "runtime/runtime_state.h"
#include "storage/runtime_range_pruner.hpp"

namespace tparquet {
class ColumnMetaData;
class ColumnOrder;
class RowGroup;
} // namespace tparquet

namespace starrocks {
class RandomAccessFile;
struct HdfsScannerContext;
class BlockCache;
class SlotDescriptor;

namespace io {
class SharedBufferedInputStream;
} // namespace io
namespace parquet {
struct ParquetField;
} // namespace parquet
struct TypeDescriptor;
class ObjectCache;

} // namespace starrocks

namespace starrocks::parquet {

struct SplitContext : public HdfsSplitContext {
    FileMetaDataPtr file_metadata;
    SkipRowsContextPtr skip_rows_ctx;

    HdfsSplitContextPtr clone() override {
        auto ctx = std::make_unique<SplitContext>();
        ctx->file_metadata = file_metadata;
        ctx->skip_rows_ctx = skip_rows_ctx;
        return ctx;
    }
};

class FileReader {
public:
    FileReader(int chunk_size, RandomAccessFile* file, size_t file_size,
               const DataCacheOptions& datacache_options = DataCacheOptions(),
               io::SharedBufferedInputStream* sb_stream = nullptr, SkipRowsContextPtr skipRowsContext = nullptr);
    ~FileReader();

    Status init(HdfsScannerContext* scanner_ctx);

    Status get_next(ChunkPtr* chunk);

    const FileMetaData* get_file_metadata();

    Status collect_scan_io_ranges(std::vector<io::SharedBufferedInputStream::IORange>* io_ranges);

    size_t row_group_size() const { return _row_group_size; }

    const std::vector<std::shared_ptr<GroupReader>>& group_readers() const { return _row_group_readers; }

private:
    int _chunk_size;

    std::shared_ptr<MetaHelper> _build_meta_helper();

    void _prepare_read_columns(std::unordered_set<std::string>& existed_column_names);

    Status _init_group_readers();

    // filter row group by conjuncts
    bool _filter_group(const GroupReaderPtr& group_reader);
    StatusOr<bool> _update_rf_and_filter_group(const GroupReaderPtr& group_reader);

    // get row group to read
    // if scan range contain the first byte in the row group, will be read
    // TODO: later modify the larger block should be read
    bool _select_row_group(const tparquet::RowGroup& row_group);

    // only scan partition column + not exist column
    Status _exec_no_materialized_column_scan(ChunkPtr* chunk);

    Status _build_split_tasks();

    Status _collect_row_group_io(std::shared_ptr<GroupReader>& group_reader);

    RandomAccessFile* _file = nullptr;
    uint64_t _file_size = 0;
    const DataCacheOptions _datacache_options;

    std::vector<std::shared_ptr<GroupReader>> _row_group_readers;
    size_t _cur_row_group_idx = 0;
    size_t _row_group_size = 0;

    size_t _total_row_count = 0;
    size_t _scan_row_count = 0;
    bool _no_materialized_column_scan = false;

    StoragePageCache* _cache = nullptr;
    FileMetaDataPtr _file_metadata = nullptr;

    // not exist column conjuncts eval false, file can be skipped
    bool _is_file_filtered = false;
    HdfsScannerContext* _scanner_ctx = nullptr;
    io::SharedBufferedInputStream* _sb_stream = nullptr;
    GroupReaderParam _group_reader_param;
    std::shared_ptr<MetaHelper> _meta_helper = nullptr;
    SkipRowsContextPtr _skip_rows_ctx = nullptr;
    std::shared_ptr<RuntimeScanRangePruner> _rf_scan_range_pruner;
};

} // namespace starrocks::parquet
