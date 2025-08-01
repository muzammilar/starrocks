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
//
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/gensrc/thrift/PlanNodes.thrift

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

namespace cpp starrocks
namespace java com.starrocks.thrift

include "Exprs.thrift"
include "Types.thrift"
include "Opcodes.thrift"
include "Descriptors.thrift"
include "Partitions.thrift"
include "RuntimeFilter.thrift"
include "CloudConfiguration.thrift"
include "DataCache.thrift"

enum TPlanNodeType {
  OLAP_SCAN_NODE,
  MYSQL_SCAN_NODE,
  CSV_SCAN_NODE,
  SCHEMA_SCAN_NODE,
  HASH_JOIN_NODE,
  MERGE_JOIN_NODE,
  AGGREGATION_NODE,
  PRE_AGGREGATION_NODE,
  SORT_NODE,
  EXCHANGE_NODE,
  MERGE_NODE,
  SELECT_NODE,
  CROSS_JOIN_NODE,
  META_SCAN_NODE,
  ANALYTIC_EVAL_NODE,
  OLAP_REWRITE_NODE,
  KUDU_SCAN_NODE,
  FILE_SCAN_NODE,
  EMPTY_SET_NODE,
  UNION_NODE,
  ES_SCAN_NODE,
  ES_HTTP_SCAN_NODE,
  REPEAT_NODE,
  ASSERT_NUM_ROWS_NODE,
  INTERSECT_NODE,
  EXCEPT_NODE,
  ADAPTER_NODE,
  HDFS_SCAN_NODE,
  PROJECT_NODE,
  TABLE_FUNCTION_NODE,
  DECODE_NODE,
  JDBC_SCAN_NODE,
  LAKE_SCAN_NODE,
  NESTLOOP_JOIN_NODE,

  STREAM_SCAN_NODE,
  STREAM_JOIN_NODE,
  STREAM_AGG_NODE,
  LAKE_META_SCAN_NODE,
  CAPTURE_VERSION_NODE,
}

// phases of an execution node
enum TExecNodePhase {
  PREPARE,
  OPEN,
  GETNEXT,
  CLOSE,
  INVALID
}

// what to do when hitting a debug point (TQueryOptions.DEBUG_ACTION)
enum TDebugAction {
  WAIT,
  FAIL
}

struct TKeyRange {
  1: required i64 begin_key
  2: required i64 end_key
  3: required Types.TPrimitiveType column_type
  4: required string column_name
}

// The information contained in subclasses of ScanNode captured in two separate
// Thrift structs:
// - TScanRange: the data range that's covered by the scan (which varies with the
//   particular partition of the plan fragment of which the scan node is a part)
// - T<subclass>: all other operational parameters that are the same across
//   all plan fragments

struct TInternalScanRange {
  1: required list<Types.TNetworkAddress> hosts
  2: required string schema_hash
  3: required string version
  4: required string version_hash // Deprecated
  5: required Types.TTabletId tablet_id
  6: required string db_name
  7: optional list<TKeyRange> partition_column_ranges
  8: optional string index_name
  9: optional string table_name
  10: optional i64 partition_id
  11: optional i64 row_count
  // Allow this query to cache remote data on local disks or not.
  // Only the cloud native tablet will respect this field.
  12: optional bool fill_data_cache = true;
  // used for per-bucket compute optimize
  13: optional i32 bucket_sequence
  // skip page cache when access page data
  14: optional bool skip_page_cache = false;
  // skip local disk data cache when access page data
  15: optional bool skip_disk_cache = false;
  16: optional i64 gtid
}

enum TFileFormatType {
    FORMAT_UNKNOWN = -1,
    FORMAT_CSV_PLAIN = 0,
    FORMAT_CSV_GZ = 1,
    FORMAT_CSV_LZO = 2, // Deprecated
    FORMAT_CSV_BZ2 = 3,
    FORMAT_CSV_LZ4_FRAME = 4,
    FORMAT_CSV_LZOP = 5, // Deprecated
    FORMAT_PARQUET = 6,
    FORMAT_CSV_DEFLATE = 7,
    FORMAT_ORC = 8,
    FORMAT_JSON = 9,
    FORMAT_CSV_ZSTD = 10,
    FORMAT_AVRO = 11,
}

// One broker range information.
struct TBrokerRangeDesc {
    1: required Types.TFileType file_type
    2: required TFileFormatType format_type
    3: required bool splittable;
    // Path of this range
    4: required string path
    // Offset of this file start
    5: required i64 start_offset;
    // Size of this range, if size = -1, this means that will read to then end of file
    6: required i64 size
    // used to get stream for this load
    7: optional Types.TUniqueId load_id
    // total size of the file
    8: optional i64 file_size
    // number of columns from file
    9: optional i32 num_of_columns_from_file
    // columns parsed from file path should be after the columns read from file
    10: optional list<string> columns_from_path
    //  it's usefull when format_type == FORMAT_JSON
    11: optional bool strip_outer_array
    12: optional string jsonpaths
    13: optional string json_root
    14: optional Types.TCompressionType compression_type
}

enum TObjectStoreType {
  HDFS,
  S3,
  KS3,
  OSS,
  COS,
  OBS,
  TOS,
  UNIVERSAL_FS
}

struct THdfsProperty {
  1: required string key
  2: required string value
}

struct THdfsProperties {
  1: optional list<THdfsProperty> properties
  2: optional TObjectStoreType object_store_type
  3: optional string object_store_path
  4: optional string access_key
  5: optional string secret_key
  6: optional string end_point
  7: optional bool disable_cache
  8: optional bool ssl_enable
  9: optional i32 max_connection
  10: optional string region
  11: optional string hdfs_username
  12: optional CloudConfiguration.TCloudConfiguration cloud_configuration
}

enum TFileScanType {
    // broker load, stream load, except insert from files
    LOAD,
    FILES_INSERT,
    FILES_QUERY
}

struct TBrokerScanRangeParams {
    1: required i8 column_separator;
    2: required i8 row_delimiter;

    // We construct one line in file to a tuple. And each field of line
    // correspond to a slot in this tuple.
    // src_tuple_id is the tuple id of the input file
    3: required Types.TTupleId src_tuple_id
    // src_slot_ids is the slot_ids of the input file
    // we use this id to find the slot descriptor
    4: required list<Types.TSlotId> src_slot_ids

    // dest_tuple_id is the tuple id that need by scan node
    5: required Types.TTupleId dest_tuple_id
    // This is expr that convert the content read from file
    // the format that need by the compute layer.
    6: optional map<Types.TSlotId, Exprs.TExpr> expr_of_dest_slot

    // properties need to access broker.
    7: optional map<string, string> properties;

    // If partition_ids is set, data that doesn't in this partition will be filtered.
    8: optional list<i64> partition_ids

    // This is the mapping of dest slot id and src slot id in load expr
    // It excludes the slot id which has the transform expr
    9: optional map<Types.TSlotId, Types.TSlotId> dest_sid_to_src_sid_without_trans
    // strictMode is a boolean
    // if strict mode is true, the incorrect data (the result of cast is null) will not be loaded
    10: optional bool strict_mode
    // If multi_column_separator is set, column_separator becomes ignore.
    11: optional string multi_column_separator;
    // If multi_row_delimiter is set, row_delimiter will ignore.
    12: optional string multi_row_delimiter;
    // If non_blocking_read is set, stream_load_pipe will not block while performing read io
    13: optional bool non_blocking_read; // deprecated
    // If use_broker is set, we will read hdfs thourgh broker
    // If use_broker is not set, we will read through libhdfs/S3 directly
    14: optional bool use_broker
    // hdfs_read_buffer_size_kb for reading through lib hdfs directly
    15: optional i32 hdfs_read_buffer_size_kb = 0
    // properties from hdfs-site.xml, core-site.xml and load_properties
    16: optional THdfsProperties hdfs_properties
    // used for channel stream load only
    20: optional string db_name
    21: optional string table_name
    22: optional string label
    23: optional i64 txn_id
    // number of lines at the start of the file to skip
    24: optional i64 skip_header
    // specifies whether to remove white space from fields
    25: optional bool trim_space
    // enclose character
    26: optional i8 enclose
    // escape character
    27: optional i8 escape
    // confluent schema registry url for pb import
    28: optional string confluent_schema_registry_url
    29: optional i64 json_file_size_limit;
    30: optional i64 schema_sample_file_count
    31: optional i64 schema_sample_file_row_count
    32: optional bool flexible_column_mapping
    33: optional TFileScanType file_scan_type
}

// Broker scan range
struct TBrokerScanRange {
    1: required list<TBrokerRangeDesc> ranges
    2: required TBrokerScanRangeParams params
    3: required list<Types.TNetworkAddress> broker_addresses
    // used for channel stream load only
    4: optional i32 channel_id
    // available when this is a stream load in batch write mode
    5: optional bool enable_batch_write
    6: optional i32 batch_write_interval_ms
    7: optional map<string, string> batch_write_parameters;
}

// Es scan range
struct TEsScanRange {
  1: required list<Types.TNetworkAddress> es_hosts  //  es hosts is used by be scan node to connect to es
  // has to set index and type here, could not set it in scannode
  // because on scan node maybe scan an es alias then it contains one or more indices
  2: required string index
  3: optional string type
  4: required i32 shard_id
}

enum TIcebergFileContent {
    DATA,
    POSITION_DELETES,
    EQUALITY_DELETES,
}

struct TIcebergDeleteFile {
    1: optional string full_path
    2: optional Descriptors.THdfsFileFormat file_format
    3: optional TIcebergFileContent file_content
    4: optional i64 length
}

struct TPaimonDeletionFile {
    1: optional string path
    2: optional i64 offset
    3: optional i64 length
}

// refer to https://github.com/delta-io/delta/blob/master/PROTOCOL.md#deletion-vector-descriptor-schema
struct TDeletionVectorDescriptor {
  1: optional string storageType
  2: optional string pathOrInlineDv
  3: optional i64 offset
  4: optional i64 sizeInBytes
  5: optional i64 cardinality
}

// Hdfs scan range
struct THdfsScanRange {
    // File name (not the full path).  The path is assumed to be relative to the
    // 'location' of the THdfsPartition referenced by partition_id.
    1: optional string relative_path

    // starting offset
    2: optional i64 offset

    // length of scan range
    3: optional i64 length

    // ID of partition within the THdfsTable associated with this scan node.
    4: optional i64 partition_id

    // total size of the hdfs file, for footer reading
    5: optional i64 file_length

    // file format of hdfs file
    6: optional Descriptors.THdfsFileFormat file_format

    // text file desc
    7: optional Descriptors.TTextFileDesc text_file_desc

    // for iceberg table scanrange should contains the full path of file
    8: optional string full_path

    // delta logs of hudi MOR table
    9: optional list<string> hudi_logs

    // whether to use JNI scanner to read data of hudi MOR table for snapshot queries
    10: optional bool use_hudi_jni_reader

    11: optional list<TIcebergDeleteFile> delete_files

    // number of lines at the start of the file to skip
    12: optional i64 skip_header

    // whether to use JNI scanner to read data of paimon table
    13: optional bool use_paimon_jni_reader

    // paimon split info
    14: optional string paimon_split_info

    // paimon predicate info
    15: optional string paimon_predicate_info

    // last modification time of the hdfs file, for data cache
    16: optional i64 modification_time

    17: optional DataCache.TDataCacheOptions datacache_options

    // identity partition column slots
    18: optional list<Types.TSlotId> identity_partition_slot_ids;

    19: optional bool use_odps_jni_reader

    20: optional map<string, string> odps_split_infos

    // delete columns slots like iceberg equality delete column slots
    21: optional list<Types.TSlotId> delete_column_slot_ids;

    22: optional bool use_iceberg_jni_metadata_reader

    // for metadata table split (eg: iceberg manifest file bean)
    23: optional string serialized_split

    // whether to use JNI scanner to read data of kudu table
    24: optional bool use_kudu_jni_reader

    // kudu master addresses
    25: optional string kudu_master

    // kudu scan token
    26: optional string kudu_scan_token

    // Paimon Deletion Vector File
    27: optional TPaimonDeletionFile paimon_deletion_file

    // for extended column like iceberg data_seq_num or spec_id
    28: optional map<Types.TSlotId, Exprs.TExpr> extended_columns;

    // attached partition value.
    29: optional Descriptors.THdfsPartition partition_value;

    30: optional Types.TTableId table_id;

    31:optional TDeletionVectorDescriptor deletion_vector_descriptor

    32: optional string candidate_node

    // how many records are in this file?
    // could be used for optimization like count(1)
    33: optional i64 record_count

    // is this scan range the first split of this file?
    34: optional bool is_first_split

    // min/max value of slots
    35: optional map<i32, Exprs.TExprMinMaxValue> min_max_values;

    // mapping transformed bucket id, used to schedule scan range
    36: optional i32 bucket_id;
}

struct TBinlogScanRange {
  1: optional string db_name
  2: optional Types.TTableId table_id
  3: optional Types.TPartitionId partition_id
  4: optional Types.TTabletId tablet_id

  // Start offset of binlog consumption
  11: optional Types.TBinlogOffset offset
}

// Specification of an individual data range which is held in its entirety
// by a storage server
struct TScanRange {
  // one of these must be set for every TScanRange
  4: optional TInternalScanRange internal_scan_range
  5: optional binary kudu_scan_token // Decrepated
  6: optional TBrokerScanRange broker_scan_range
  7: optional TEsScanRange es_scan_range

  // scan range for hdfs
  20: optional THdfsScanRange hdfs_scan_range

  30: optional TBinlogScanRange binlog_scan_range
}

struct TMySQLScanNode {
  1: required Types.TTupleId tuple_id
  2: required string table_name
  3: required list<string> columns
  4: required list<string> filters
  5: optional i64 limit
  6: optional string temporal_clause
}

struct TFileScanNode {
    1: required Types.TTupleId tuple_id

    // Partition info used to process partition select in broker load
    2: optional list<Exprs.TExpr> partition_exprs
    3: optional list<Partitions.TRangePartition> partition_infos
    4: optional bool enable_pipeline_load
}

struct TEsScanNode {
    1: required Types.TTupleId tuple_id
    2: optional map<string,string> properties
    // used to indicate which fields can get from ES docavalue
    // because elasticsearch can have "fields" feature, field can have
    // two or more types, the first type maybe have not docvalue but other
    // can have, such as (text field not have docvalue, but keyword can have):
    // "properties": {
    //      "city": {
    //        "type": "text",
    //        "fields": {
    //          "raw": {
    //            "type":  "keyword"
    //          }
    //        }
    //      }
    //    }
    // then the docvalue context provided the mapping between the select field and real request field :
    // {"city": "city.raw"}
    // use select city from table, if enable the docvalue, we will fetch the `city` field value from `city.raw`
    3: optional map<string, string> docvalue_context
    // used to indicate which string-type field predicate should used xxx.keyword etc.
    // "k1": {
    //    "type": "text",
    //    "fields": {
    //        "keyword": {
    //            "type": "keyword",
    //            "ignore_above": 256
    //           }
    //    }
    // }
    // k1 > 'abc' -> k1.keyword > 'abc'
    4: optional map<string, string> fields_context
}

struct TFrontend {
  1: optional string id
  2: optional string ip
  3: optional i32 http_port
}

struct TSchemaScanNode {
  1: required Types.TTupleId tuple_id

  2: required string table_name
  3: optional string db
  4: optional string table
  5: optional string wild
  6: optional string user   // deprecated
  7: optional string ip // frontend ip
  8: optional i32 port  // frontend thrift server port
  9: optional i64 thread_id
  10: optional string user_ip   // deprecated
  11: optional Types.TUserIdentity current_user_ident   // to replace the user and user_ip
  12: optional i64 table_id
  13: optional i64 partition_id
  14: optional i64 tablet_id
  15: optional i64 txn_id
  16: optional i64 job_id
  17: optional string label
  18: optional string type
  19: optional string state
  20: optional i64 limit
  21: optional i64 log_start_ts;
  22: optional i64 log_end_ts;
  23: optional string log_level;
  24: optional string log_pattern;
  25: optional i64 log_limit;
  26: optional list<TFrontend> frontends;

  101: optional string catalog_name;
}

enum TAccessPathType {
    ROOT,       // ROOT
    KEY,        // MAP KEY
    OFFSET,     // ARRAY/MAP OFFSET
    FIELD,      // STRUCT FIELD
    INDEX,      // ARRAY/MAP INDEX-AT POSITION DATA
    ALL,        // ARRAY/MAP ALL DATA
}

struct TColumnAccessPath {
    1: optional TAccessPathType type
    2: optional Exprs.TExpr path
    3: optional list<TColumnAccessPath> children
    4: optional bool from_predicate
    5: optional Types.TTypeDesc type_desc
}

struct TVectorSearchOptions {
  1: optional bool enable_use_ann;
  2: optional i64 vector_limit_k;
  3: optional string vector_distance_column_name;
  4: optional list<string> query_vector;
  5: optional map<string, string> query_params;
  6: optional double vector_range;
  7: optional i32 result_order;
  8: optional bool use_ivfpq;
  9: optional double pq_refine_factor;
  10: optional double k_factor;
  11: optional i32 vector_slot_id;
}

enum SampleMethod {
  BY_BLOCK,
  BY_PAGE,
}

struct TTableSampleOptions {
  1: optional bool enable_sampling;
  2: optional SampleMethod sample_method;
  3: optional i64 random_seed;
  4: optional i64 probability_percent;

}

// If you find yourself changing this struct, see also TLakeScanNode
struct TOlapScanNode {
  1: required Types.TTupleId tuple_id
  2: required list<string> key_column_name
  3: required list<Types.TPrimitiveType> key_column_type
  4: required bool is_preaggregation
  5: optional string sort_column
  // For profile attributes' printing: `Rollup` `Predicates`
  20: optional string rollup_name
  21: optional string sql_predicates
  22: optional bool enable_column_expr_predicate
  23: optional map<i32, i32> dict_string_id_to_int_ids
  // which columns only be used to filter data in the stage of scan data
  24: optional list<string> unused_output_column_name
  25: optional bool sorted_by_keys_per_tablet = false

  26: optional list<Exprs.TExpr> bucket_exprs
  27: optional list<string> sort_key_column_names
  28: optional i32 max_parallel_scan_instance_num
  29: optional list<TColumnAccessPath> column_access_paths

  30: optional bool use_pk_index
  31: optional list<Descriptors.TColumn> columns_desc
  32: optional bool output_chunk_by_bucket
  // order by hint for scan
  33: optional bool output_asc_hint
  34: optional bool partition_order_hint
  35: optional bool enable_prune_column_after_index_filter
  36: optional bool enable_gin_filter
  37: optional i64 schema_id

  40: optional TVectorSearchOptions vector_search_options
  41: optional TTableSampleOptions sample_options;

  //back pressure
  50: optional bool enable_topn_filter_back_pressure
  51: optional i32 back_pressure_max_rounds
  52: optional i64 back_pressure_throttle_time
  53: optional i64 back_pressure_throttle_time_upper_bound
  54: optional i64 back_pressure_num_rows
}

struct TJDBCScanNode {
  1: optional Types.TTupleId tuple_id
  2: optional string table_name
  3: optional list<string> columns
  4: optional list<string> filters
  5: optional i64 limit
}

// If you find yourself changing this struct, see also TOlapScanNode
struct TLakeScanNode {
  1: required Types.TTupleId tuple_id
  2: required list<string> key_column_name
  3: required list<Types.TPrimitiveType> key_column_type
  4: required bool is_preaggregation
  5: optional string sort_column
  // For profile attributes' printing: `Rollup` `Predicates`
  6: optional string rollup_name
  7: optional string sql_predicates
  8: optional bool enable_column_expr_predicate
  9: optional map<i32, i32> dict_string_id_to_int_ids
  // which columns only be used to filter data in the stage of scan data
  10: optional list<string> unused_output_column_name
  11: optional list<string> sort_key_column_names
  12: optional list<Exprs.TExpr> bucket_exprs
  13: optional list<TColumnAccessPath> column_access_paths

  // physical optimize
  25: optional bool sorted_by_keys_per_tablet = false
  32: optional bool output_chunk_by_bucket
  33: optional bool output_asc_hint
  34: optional bool partition_order_hint

  //back pressure
  38: optional bool enable_topn_filter_back_pressure
  39: optional i32 back_pressure_max_rounds
  40: optional i64 back_pressure_throttle_time
  41: optional i64 back_pressure_throttle_time_upper_bound
  42: optional i64 back_pressure_num_rows
}

struct TEqJoinCondition {
  // left-hand side of "<a> = <b>"
  1: required Exprs.TExpr left;
  // right-hand side of "<a> = <b>"
  2: required Exprs.TExpr right;
  // operator of equal join
  3: optional Opcodes.TExprOpcode opcode;
}

enum TStreamingPreaggregationMode {
  AUTO,
  FORCE_STREAMING,
  FORCE_PREAGGREGATION,
  LIMITED_MEM
}

enum TJoinOp {
  INNER_JOIN,
  LEFT_OUTER_JOIN,
  LEFT_SEMI_JOIN,
  RIGHT_OUTER_JOIN,
  FULL_OUTER_JOIN,
  CROSS_JOIN,
  // only used for compatibility
  MERGE_JOIN,

  RIGHT_SEMI_JOIN,
  LEFT_ANTI_JOIN,
  RIGHT_ANTI_JOIN,

  // Similar to LEFT_ANTI_JOIN with special handling for NULLs for the join conjuncts
  // on the build side. Those NULLs are considered candidate matches, and therefore could
  // be rejected (ANTI-join), based on the other join conjuncts. This is in contrast
  // to LEFT_ANTI_JOIN where NULLs are not matches and therefore always returned.
  NULL_AWARE_LEFT_ANTI_JOIN
}

enum TJoinDistributionMode {
  NONE,
  BROADCAST,
  PARTITIONED,
  LOCAL_HASH_BUCKET,
  SHUFFLE_HASH_BUCKET,
  COLOCATE,
  REPLICATED
}

struct THashJoinNode {
  1: required TJoinOp join_op

  // anything from the ON, USING or WHERE clauses that's an equi-join predicate
  2: required list<TEqJoinCondition> eq_join_conjuncts

  // anything from the ON or USING clauses (but *not* the WHERE clause) that's not an
  // equi-join predicate
  3: optional list<Exprs.TExpr> other_join_conjuncts
  4: optional bool is_push_down

  // If true, this join node can (but may choose not to) generate slot filters
  // after constructing the build side that can be applied to the probe side.
  5: optional bool add_probe_filters

  // Mark left anti join whether rewritten from not in
  20: optional bool is_rewritten_from_not_in

  // for profiling
  21: optional string sql_join_predicates
  22: optional string sql_predicates

  // runtime filters built by this node.
  50: optional list<RuntimeFilter.TRuntimeFilterDescription> build_runtime_filters;
  51: optional bool build_runtime_filters_from_planner;

  52: optional TJoinDistributionMode distribution_mode;
  53: optional list<Exprs.TExpr> partition_exprs
  54: optional list<Types.TSlotId> output_columns

  // used in pipeline engine
  55: optional bool interpolate_passthrough = false
  56: optional bool late_materialization = false
  57: optional bool enable_partition_hash_join = false
  58: optional bool is_skew_join = false
}

struct TMergeJoinNode {
  1: optional TJoinOp join_op

  // anything from the ON, USING or WHERE clauses that's an equi-join predicate
  2: optional list<TEqJoinCondition> eq_join_conjuncts

  // anything from the ON or USING clauses (but *not* the WHERE clause) that's not an
  // equi-join predicate
  3: optional list<Exprs.TExpr> other_join_conjuncts
  4: optional bool is_push_down

  // If true, this join node can (but may choose not to) generate slot filters
  // after constructing the build side that can be applied to the probe side.
  5: optional bool add_probe_filters

  // Mark left anti join whether rewritten from not in
  20: optional bool is_rewritten_from_not_in

  // for profiling
  21: optional string sql_join_predicates
  22: optional string sql_predicates

  // runtime filters built by this node.
  50: optional list<RuntimeFilter.TRuntimeFilterDescription> build_runtime_filters;
  51: optional bool build_runtime_filters_from_planner;

  52: optional TJoinDistributionMode distribution_mode;
  53: optional list<Exprs.TExpr> partition_exprs
  54: optional list<Types.TSlotId> output_columns
}

struct TNestLoopJoinNode {
    1: optional TJoinOp join_op
    2: optional list<RuntimeFilter.TRuntimeFilterDescription> build_runtime_filters;
    3: optional list<Exprs.TExpr> join_conjuncts
    4: optional string sql_join_conjuncts
    5: optional bool interpolate_passthrough = false
}

enum TAggregationOp {
  INVALID,
  COUNT,
  MAX,
  DISTINCT_PC,
  DISTINCT_PCSA,
  MIN,
  SUM,
  GROUP_CONCAT,
  HLL,
  COUNT_DISTINCT,
  SUM_DISTINCT,
  LEAD,
  FIRST_VALUE,
  LAST_VALUE,
  RANK,
  DENSE_RANK,
  ROW_NUMBER,
  LAG,
  HLL_C,
  BITMAP_UNION,
  ANY_VALUE,
  NTILE,
  CUME_DIST,
  PERCENT_RANK
}

struct TAggregationNode {
  1: optional list<Exprs.TExpr> grouping_exprs
  // aggregate exprs. The root of each expr is the aggregate function. The
  // other exprs are the inputs to the aggregate function.
  2: required list<Exprs.TExpr> aggregate_functions

  // Tuple id used for intermediate aggregations (with slots of agg intermediate types)
  3: required Types.TTupleId intermediate_tuple_id

  // Tupld id used for the aggregation output (with slots of agg output types)
  // Equal to intermediate_tuple_id if intermediate type == output type for all
  // aggregate functions.
  4: required Types.TTupleId output_tuple_id

  // Set to true if this aggregation function requires finalization to complete after all
  // rows have been aggregated, and this node is not an intermediate node.
  5: required bool need_finalize
  6: optional bool use_streaming_preaggregation

  // For vector query engine
  20: optional bool has_outer_join_child
  21: optional TStreamingPreaggregationMode streaming_preaggregation_mode

  // For profile attributes' printing: `Grouping Keys` `Aggregate Functions`
  22: optional string sql_grouping_keys
  23: optional string sql_aggregate_functions

  24: optional i32 agg_func_set_version = 1

  // used in query cache
  25: optional list<Exprs.TExpr> intermediate_aggr_exprs

  // used in pipeline engine
  26: optional bool interpolate_passthrough = false

  27: optional bool use_sort_agg

  28: optional bool use_per_bucket_optimize

  // enable runtime limit, pipelines share one limit
  29: optional bool enable_pipeline_share_limit = false

  30: optional list<RuntimeFilter.TRuntimeFilterDescription> build_runtime_filters
}

struct TRepeatNode {
 // Tulple id used for output, it has new slots.
  1: required Types.TTupleId output_tuple_id
  // Slot id set used to indicate those slots need to set to null.
  2: required list<set<Types.TSlotId>> slot_id_set_list
  // An integer bitmap list, it indicates the bit position of the exprs not null.
  3: required list<i64> repeat_id_list
  // A list of integer list, it indicates the position of the grouping virtual slot.
  4: required list<list<i64>> grouping_list
  // A list of all slot
  5: required set<Types.TSlotId> all_slot_ids
}

struct TSortInfo {
  1: required list<Exprs.TExpr> ordering_exprs
  2: required list<bool> is_asc_order
  // Indicates, for each expr, if nulls should be listed first or last. This is
  // independent of is_asc_order.
  3: required list<bool> nulls_first
  // Expressions evaluated over the input row that materialize the tuple to be sorted.
  // Contains one expr per slot in the materialized tuple.
  4: optional list<Exprs.TExpr> sort_tuple_slot_exprs
}

enum TTopNType {
  ROW_NUMBER,
  RANK,
  DENSE_RANK
}

enum TLateMaterializeMode {
  AUTO,
  ALWAYS,
  NEVER,
}

struct TSortNode {
  1: required TSortInfo sort_info
  // Indicates whether the backend service should use topn vs. sorting
  2: required bool use_top_n;
  // This is the number of rows to skip before returning results
  3: optional i64 offset

  // TODO(lingbin): remove blew, because duplaicate with TSortInfo
  4: optional list<Exprs.TExpr> ordering_exprs
  5: optional list<bool> is_asc_order
  // Indicates whether the imposed limit comes DEFAULT_ORDER_BY_LIMIT.
  6: optional bool is_default_limit
  // Indicates, for each expr, if nulls should be listed first or last. This is
  // independent of is_asc_order.
  7: optional list<bool> nulls_first
  // Expressions evaluated over the input row that materialize the tuple to be so
  // Contains one expr per slot in the materialized tuple.
  8: optional list<Exprs.TExpr> sort_tuple_slot_exprs

  // For vector query engine
  20: optional bool has_outer_join_child
  // For profile attributes' printing: `Sort Keys`
  21: optional string sql_sort_keys
  // For pipeline execution engine, interpolate local shuffle before PartitionSortOperator
  // in order to eliminate time-consuming LocalMergeSortSourceOperator and parallelize
  // AnalyticNode
  22: optional list<Exprs.TExpr> analytic_partition_exprs
  23: optional list<Exprs.TExpr> partition_exprs
  24: optional i64 partition_limit
  25: optional TTopNType topn_type;
  26: optional list<RuntimeFilter.TRuntimeFilterDescription> build_runtime_filters;
  27: optional i64 max_buffered_rows;
  28: optional i64 max_buffered_bytes;
  29: optional bool late_materialization;
  30: optional bool enable_parallel_merge;
  31: optional bool analytic_partition_skewed;
  32: optional list<Exprs.TExpr> pre_agg_exprs;
  33: optional list<Types.TSlotId> pre_agg_output_slot_id;
  34: optional bool pre_agg_insert_local_shuffle;
  40: optional TLateMaterializeMode parallel_merge_late_materialize_mode;
}

enum TAnalyticWindowType {
  // Specifies the window as a logical offset
  RANGE,

  // Specifies the window in physical units
  ROWS
}

enum TAnalyticWindowBoundaryType {
  // The window starts/ends at the current row.
  CURRENT_ROW,

  // The window starts/ends at an offset preceding current row.
  PRECEDING,

  // The window starts/ends at an offset following current row.
  FOLLOWING
}

struct TAnalyticWindowBoundary {
  1: required TAnalyticWindowBoundaryType type

  // Predicate that checks: child tuple '<=' buffered tuple + offset for the orderby expr
  2: optional Exprs.TExpr range_offset_predicate

  // Offset from the current row for ROWS windows.
  3: optional i64 rows_offset_value
}

struct TAnalyticWindow {
  // Specifies the window type for the start and end bounds.
  1: required TAnalyticWindowType type

  // Absence indicates window start is UNBOUNDED PRECEDING.
  2: optional TAnalyticWindowBoundary window_start

  // Absence indicates window end is UNBOUNDED FOLLOWING.
  3: optional TAnalyticWindowBoundary window_end
}

// Defines a group of one or more analytic functions that share the same window,
// partitioning expressions and order-by expressions and are evaluated by a single
// ExecNode.
struct TAnalyticNode {
  // Exprs on which the analytic function input is partitioned. Input is already sorted
  // on partitions and order by clauses, partition_exprs is used to identify partition
  // boundaries. Empty if no partition clause is specified.
  1: required list<Exprs.TExpr> partition_exprs

  // Exprs specified by an order-by clause for RANGE windows. Used to evaluate RANGE
  // window boundaries. Empty if no order-by clause is specified or for windows
  // specifying ROWS.
  2: required list<Exprs.TExpr> order_by_exprs

  // Functions evaluated over the window for each input row. The root of each expr is
  // the aggregate function. Child exprs are the inputs to the function.
  3: required list<Exprs.TExpr> analytic_functions

  // Window specification
  4: optional TAnalyticWindow window

  // Tuple used for intermediate results of analytic function evaluations
  // (with slots of analytic intermediate types)
  5: required Types.TTupleId intermediate_tuple_id

  // Tupld used for the analytic function output (with slots of analytic output types)
  // Equal to intermediate_tuple_id if intermediate type == output type for all
  // analytic functions.
  6: required Types.TTupleId output_tuple_id

  // id of the buffered tuple (identical to the input tuple, which is assumed
  // to come from a single SortNode); not set if both partition_exprs and
  // order_by_exprs are empty
  7: optional Types.TTupleId buffered_tuple_id

  // predicate that checks: child tuple is in the same partition as the buffered tuple,
  // i.e. each partition expr is equal or both are not null. Only set if
  // buffered_tuple_id is set; should be evaluated over a row that is composed of the
  // child tuple and the buffered tuple
  8: optional Exprs.TExpr partition_by_eq

  // predicate that checks: the order_by_exprs are equal or both NULL when evaluated
  // over the child tuple and the buffered tuple. only set if buffered_tuple_id is set;
  // should be evaluated over a row that is composed of the child tuple and the buffered
  // tuple
  9: optional Exprs.TExpr order_by_eq

  // For profile attributes' printing: `Partition Keys` `Aggregate Functions`
  10: optional string sql_partition_keys
  11: optional string sql_aggregate_functions

  20: optional bool has_outer_join_child
  21: optional bool use_hash_based_partition
  22: optional bool is_skewed
}

struct TMergeNode {
  // A MergeNode could be the left input of a join and needs to know which tuple to write.
  1: required Types.TTupleId tuple_id
  // List or expr lists materialized by this node.
  // There is one list of exprs per query stmt feeding into this merge node.
  2: required list<list<Exprs.TExpr>> result_expr_lists
  // Separate list of expr lists coming from a constant select stmts.
  3: required list<list<Exprs.TExpr>> const_expr_lists
}

enum TLocalExchangerType {
  PASSTHROUGH = 0,
  DIRECT = 1
}

struct TUnionNode {
    // A UnionNode materializes all const/result exprs into this tuple.
    1: required Types.TTupleId tuple_id
    // List or expr lists materialized by this node.
    // There is one list of exprs per query stmt feeding into this union node.
    2: required list<list<Exprs.TExpr>> result_expr_lists
    // Separate list of expr lists coming from a constant select stmts.
    3: required list<list<Exprs.TExpr>> const_expr_lists
    // Index of the first child that needs to be materialized.
    4: required i64 first_materialized_child_idx
    // For pass through child, the slot map is union slot id -> child slot id
    20: optional list<map<Types.TSlotId, Types.TSlotId>> pass_through_slot_maps
    // union node' local exchanger type with parent node, default is PASSTHROUGH
    21: optional TLocalExchangerType local_exchanger_type

    22: optional list<list<Exprs.TExpr>> local_partition_by_exprs
}

struct TIntersectNode {
    // A IntersectNode materializes all const/result exprs into this tuple.
    1: required Types.TTupleId tuple_id
    // List or expr lists materialized by this node.
    // There is one list of exprs per query stmt feeding into this union node.
    2: required list<list<Exprs.TExpr>> result_expr_lists
    // Separate list of expr lists coming from a constant select stmts.
    3: required list<list<Exprs.TExpr>> const_expr_lists
    // Index of the first child that needs to be materialized.
    4: required i64 first_materialized_child_idx

    5: optional bool has_outer_join_child

    6: optional list<list<Exprs.TExpr>> local_partition_by_exprs
}

struct TExceptNode {
    // A ExceptNode materializes all const/result exprs into this tuple.
    1: required Types.TTupleId tuple_id
    // List or expr lists materialized by this node.
    // There is one list of exprs per query stmt feeding into this union node.
    2: required list<list<Exprs.TExpr>> result_expr_lists
    // Separate list of expr lists coming from a constant select stmts.
    3: required list<list<Exprs.TExpr>> const_expr_lists
    // Index of the first child that needs to be materialized.
    4: required i64 first_materialized_child_idx

    5: optional list<list<Exprs.TExpr>> local_partition_by_exprs
}


struct TExchangeNode {
  // The ExchangeNode's input rows form a prefix of the output rows it produces;
  // this describes the composition of that prefix
  1: required list<Types.TTupleId> input_row_tuples
  // For a merging exchange, the sort information.
  2: optional TSortInfo sort_info
  // This is tHe number of rows to skip before returning results
  3: optional i64 offset
  // Sender's partition type
  4: optional Partitions.TPartitionType partition_type;
  5: optional bool enable_parallel_merge
  6: optional TLateMaterializeMode parallel_merge_late_materialize_mode;
}

// This contains all of the information computed by the plan as part of the resource
// profile that is needed by the backend to execute.
struct TBackendResourceProfile {
// The minimum reservation for this plan node in bytes.
1: required i64 min_reservation = 0; // no support reservation

// The maximum reservation for this plan node in bytes. MAX_INT64 means effectively
// unlimited.
2: required i64 max_reservation = 12188490189880;  // no max reservation limit

// The spillable buffer size in bytes to use for this node, chosen by the planner.
// Set iff the node uses spillable buffers.
3: optional i64 spillable_buffer_size = 2097152

// The buffer size in bytes that is large enough to fit the largest row to be processed.
// Set if the node allocates buffers for rows from the buffer pool.
4: optional i64 max_row_buffer_size = 4194304  //TODO chenhao
}

enum TAssertion {
  EQ, // val1 == val2
  NE, // val1 != val2
  LT, // val1 < val2
  LE, // val1 <= val2
  GT, // val1 > val2
  GE // val1 >= val2
}

struct TAssertNumRowsNode {
    1: optional i64 desired_num_rows;
    2: optional string subquery_string;
    3: optional TAssertion assertion;
}

struct THdfsScanNode {
    1: optional Types.TTupleId tuple_id

    // Conjuncts that can be evaluated while materializing the items (tuples) of
    // collection-typed slots. Maps from item tuple id to the list of conjuncts
    // to be evaluated.
    2: optional map<Types.TTupleId, list<Exprs.TExpr>> DEPRECATED_collection_conjuncts

    // Conjuncts that can be evaluated against parquet::Statistics using the tuple
    // referenced by 'min_max_tuple_id'.
    3: optional list<Exprs.TExpr> min_max_conjuncts

    // Tuple to evaluate 'min_max_conjuncts' against.
    4: optional Types.TTupleId min_max_tuple_id

    // The conjuncts that are eligible for dictionary filtering.
    5: optional map<Types.TSlotId, list<i32>> DEPRECATED_dictionary_filter_conjuncts

    // conjuncts in TPlanNode contains non-partition filters if node_type is HDFS_SCAN_NODE.
    // partition_conjuncts contains partition filters that are not evaled by pruner.
    6: optional list<Exprs.TExpr> partition_conjuncts;

    // hive colunm names in ordinal order.
    7: optional list<string> hive_column_names;

    // table name it scans
    8: optional string table_name;

    // conjuncts in explained string
    9: optional string sql_predicates;
    10: optional string min_max_sql_predicates;
    11: optional string partition_sql_predicates;

    // Flag to indicate wheather the column names are case sensitive
    12: optional bool case_sensitive;

    13: optional CloudConfiguration.TCloudConfiguration cloud_configuration;

    // deprecated. not used any more.
    14: optional bool can_use_any_column;

    15: optional bool can_use_min_max_opt;

    16: optional bool use_partition_column_value_only;

    17: optional Types.TTupleId mor_tuple_id;

    // serialized static metadata table
    18: optional string serialized_table;

    // serialized lake format predicate for data skipping
    19: optional string serialized_predicate;

    // if load column statistics for metadata table scan
    20: optional bool load_column_stats;

    // for jni scan factory selection scanner
    21: optional string metadata_table_type

    22: optional DataCache.TDataCacheOptions datacache_options;

    // for extended column like iceberg data_seq_num or spec_id
    23: optional list<Types.TSlotId> extended_slot_ids;

    24: optional bool can_use_count_opt;

    // describe distribution of local exchange
    25: optional list<Partitions.TBucketProperty> bucket_properties;
}

struct TProjectNode {
    1: optional map<Types.TSlotId, Exprs.TExpr> slot_map
    // Used for common operator compute result reuse
    2: optional map<Types.TSlotId, Exprs.TExpr> common_slot_map
}

struct TSelectNode {
     // used for common expressions compute result reuse
    1: optional map<Types.TSlotId, Exprs.TExpr> common_slot_map
}

struct TMetaScanNode {
    // column id to column name
    1: optional map<i32, string> id_to_names
    2: optional list<Descriptors.TColumn> columns
    3: optional i32 low_cardinality_threshold;
}

struct TDecodeNode {
    // dict int column id to string column id
    1: optional map<i32, i32> dict_id_to_string_ids
    2: optional map<Types.TSlotId, Exprs.TExpr> string_functions
}

struct TCrossJoinNode {
    1: optional list<RuntimeFilter.TRuntimeFilterDescription> build_runtime_filters;
}

struct TTableFunctionNode {
    1: optional Exprs.TExpr table_function
    2: optional list<Types.TSlotId> param_columns
    3: optional list<Types.TSlotId> outer_columns
    4: optional list<Types.TSlotId> fn_result_columns
    5: optional bool fn_result_required
}

struct TConnectorScanNode {
  1: optional string connector_name
  // // Scan node for hdfs
  // 2: optional THdfsScanNode hdfs_scan_node
}

// binlog meta column names
const string BINLOG_OP_COLUMN_NAME = "_binlog_op";
const string BINLOG_VERSION_COLUMN_NAME = "_binlog_version";
const string BINLOG_SEQ_ID_COLUMN_NAME = "_binlog_seq_id";
const string BINLOG_TIMESTAMP_COLUMN_NAME = "_binlog_timestamp";

struct TBinlogScanNode {
  1: optional Types.TTupleId tuple_id
}

// Union of all stream source nodes, distinguished by type
struct TStreamScanNode {
  // Common fields for all stream-scan nodes
  1: optional Types.StreamSourceType source_type

  // Specific scan nodes, distinguished by source_type
  11: optional TBinlogScanNode binlog_scan
  // TODO: othe stream scan nodes
}

struct TStreamJoinNode {
  1: required TJoinOp join_op

  // anything from the ON, USING or WHERE clauses that's an equi-join predicate
  2: required list<TEqJoinCondition> eq_join_conjuncts

  // anything from the ON or USING clauses (but *not* the WHERE clause) that's not an
  // equi-join predicate
  3: optional list<Exprs.TExpr> other_join_conjuncts
  4: optional bool is_push_down

  // for profiling
  21: optional string sql_join_predicates
  22: optional string sql_predicates

  52: optional TJoinDistributionMode distribution_mode;
  53: optional list<Exprs.TExpr> partition_exprs
  54: optional list<Types.TSlotId> output_columns
}

struct TStreamAggregationNode {
  1: optional list<Exprs.TExpr> grouping_exprs
  // aggregate exprs. The root of each expr is the aggregate function. The
  // other exprs are the inputs to the aggregate function.
  2: optional list<Exprs.TExpr> aggregate_functions

  // IMT info
  10: optional Descriptors.TIMTDescriptor agg_result_imt
  11: optional Descriptors.TIMTDescriptor agg_intermediate_imt
  12: optional Descriptors.TIMTDescriptor agg_detail_imt

  // For profile attributes' printing: `Grouping Keys` `Aggregate Functions`
  22: optional string sql_grouping_keys
  23: optional string sql_aggregate_functions

  24: optional i32 agg_func_set_version = 1
}


// This is essentially a union of all messages corresponding to subclasses
// of PlanNode.
struct TPlanNode {
  // node id, needed to reassemble tree structure
  1: required Types.TPlanNodeId node_id
  2: required TPlanNodeType node_type
  3: required i32 num_children
  4: required i64 limit
  5: required list<Types.TTupleId> row_tuples

  // nullable_tuples[i] is true if row_tuples[i] is nullable
  6: required list<bool> nullable_tuples
  7: optional list<Exprs.TExpr> conjuncts

  // Produce data in compact format.
  8: required bool compact_data

  // one field per PlanNode subclass
  11: optional THashJoinNode hash_join_node
  12: optional TAggregationNode agg_node
  13: optional TSortNode sort_node
  14: optional TMergeNode merge_node
  15: optional TExchangeNode exchange_node
  17: optional TMySQLScanNode mysql_scan_node
  18: optional TOlapScanNode olap_scan_node
  // 19 is reserved, please DON'T use
  20: optional TFileScanNode file_scan_node
  // 21 is reserved, please DON'T use
  22: optional TSchemaScanNode schema_scan_node
  // 23 is reserved, please DON'T use
  24: optional TMetaScanNode meta_scan_node
  25: optional TAnalyticNode analytic_node
  28: optional TUnionNode union_node
  29: optional TBackendResourceProfile resource_profile
  30: optional TEsScanNode es_scan_node
  31: optional TRepeatNode repeat_node
  32: optional TAssertNumRowsNode assert_num_rows_node
  33: optional TIntersectNode intersect_node
  34: optional TExceptNode except_node
  35: optional TMergeJoinNode merge_join_node

  // For vector query engine
  // 50 is reserved, please don't use
  51: optional bool use_vectorized
  // Scan node for hdfs
  52: optional THdfsScanNode hdfs_scan_node
  53: optional TProjectNode project_node
  54: optional TTableFunctionNode table_function_node
  // runtime filters be probed by this node.
  55: optional list<RuntimeFilter.TRuntimeFilterDescription> probe_runtime_filters
  56: optional TDecodeNode decode_node
  // a set of TPlanNodeIds of whom generate local runtime filters that take effects on this node
  57: optional set<Types.TPlanNodeId> local_rf_waiting_set
  // Columns that null values can be filtered out
  58: optional list<Types.TSlotId> filter_null_value_columns;
  // for outer join and cross join
  59: optional bool need_create_tuple_columns;
  // Scan node for jdbc
  60: optional TJDBCScanNode jdbc_scan_node;

  // generic scan node with connector.
  61: optional TConnectorScanNode connector_scan_node;

  62: optional TCrossJoinNode cross_join_node;

  63: optional TLakeScanNode lake_scan_node;

  64: optional TNestLoopJoinNode nestloop_join_node;

  // 70 ~ 80 are reserved for stream operators
  // Stream plan
  70: optional TStreamScanNode stream_scan_node;
  71: optional TStreamJoinNode stream_join_node;
  72: optional TStreamAggregationNode stream_agg_node;

  81: optional TSelectNode select_node;
}

// A flattened representation of a tree of PlanNodes, obtained by depth-first
// traversal.
struct TPlan {
  1: required list<TPlanNode> nodes
}
