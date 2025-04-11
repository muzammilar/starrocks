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

#include "common/status.h"
#include "exec/olap_common.h"
#include "exprs/expr.h"
#include "exprs/expr_context.h"
#include "filter_condition.h"
#include "runtime/descriptors.h"
#include "storage/predicate_tree/predicate_tree_fwd.h"
#include "storage/predicate_tree_params.h"
#include "storage/runtime_filter_predicate.h"
#include "storage/runtime_range_pruner.h"

namespace starrocks {

class RuntimeState;

class RuntimeFilterProbeCollector;
class PredicateParser;
class ColumnPredicate;
class VectorizedLiteral;
using ColumnPredicatePtr = std::unique_ptr<ColumnPredicate>;
using ColumnPredicatePtrs = std::vector<ColumnPredicatePtr>;

struct ScanConjunctsManagerOptions {
    // fields from olap scan node
    const std::vector<ExprContext*>* conjunct_ctxs_ptr = nullptr;
    const TupleDescriptor* tuple_desc = nullptr;
    ObjectPool* obj_pool = nullptr;
    const std::vector<std::string>* key_column_names = nullptr;
    const RuntimeFilterProbeCollector* runtime_filters = nullptr;
    RuntimeState* runtime_state = nullptr;

    int32_t driver_sequence = -1;

    bool scan_keys_unlimited = true;
    int32_t max_scan_key_num = 1024;
    bool enable_column_expr_predicate = false;
    bool is_olap_scan = true;

    PredicateTreeParams pred_tree_params;
};

struct BoxedExpr {
    explicit BoxedExpr(Expr* root_expr);

    Expr* root() const;
    StatusOr<ExprContext*> expr_context(ObjectPool* obj_pool, RuntimeState* state) const;

    Expr* root_expr;
    mutable ExprContext* new_expr_ctx = nullptr;
};

struct BoxedExprContext {
    explicit BoxedExprContext(ExprContext* expr_ctx);

    Expr* root() const;
    StatusOr<ExprContext*> expr_context(ObjectPool* obj_pool, RuntimeState* state) const;

    ExprContext* expr_ctx;
};

template <typename E>
concept BoxedExprType = std::is_same_v<E, BoxedExpr> || std::is_same_v<E, BoxedExprContext>;

template <BoxedExprType E, CompoundNodeType Type>
class ChunkPredicateBuilder {
public:
    ChunkPredicateBuilder(const ScanConjunctsManagerOptions& opts, std::vector<E> exprs, bool is_root_builder);

    StatusOr<bool> parse_conjuncts();

    StatusOr<PredicateCompoundNode<Type>> get_predicate_tree_root(PredicateParser* parser,
                                                                  ColumnPredicatePtrs& col_preds_owner);

    Status get_key_ranges(std::vector<std::unique_ptr<OlapScanRange>>* key_ranges);

    bool is_pred_normalized(size_t index) const;

    const UnarrivedRuntimeFilterList& unarrived_runtime_filters() { return rt_ranger_params; }

    template <LogicalType SlotType, LogicalType MappingType, template <class> class Decoder, class... Args>
    void normalized_rf_with_null(const RuntimeFilter* rf, const SlotDescriptor* slot_desc, Args&&... args);

private:
    const ScanConjunctsManagerOptions& _opts;
    const std::vector<E> _exprs;
    const bool _is_root_builder;

    using ChunkPredicateBuilderVar = std::variant<ChunkPredicateBuilder<BoxedExpr, CompoundNodeType::AND>,
                                                  ChunkPredicateBuilder<BoxedExpr, CompoundNodeType::OR>>;
    std::vector<ChunkPredicateBuilderVar> _child_builders;

    // fields generated by parsing conjunct ctxs.
    // same size with |_conjunct_ctxs|, indicate which element has been normalized.
    std::vector<uint8_t> _normalized_exprs;
    std::map<std::string, ColumnValueRangeType> column_value_ranges; // from conjunct_ctxs
    OlapScanKeys scan_keys;                                          // from _column_value_ranges
    std::vector<OlapCondition> olap_filters;                         // from _column_value_ranges
    std::vector<GeneralCondition> external_filters;                  // from _column_value_ranges
    std::vector<TCondition> is_null_vector;                          // from conjunct_ctxs

    std::map<int, std::vector<ExprContext*>> slot_index_to_expr_ctxs; // from conjunct_ctxs

    // unreached runtime filter and they can push down to storage engine
    UnarrivedRuntimeFilterList rt_ranger_params;

private:
    StatusOr<bool> _normalize_compound_predicates();
    StatusOr<bool> _normalize_compound_predicate(const Expr* root_expr);

    Status _get_column_predicates(PredicateParser* parser, ColumnPredicatePtrs& col_preds_owner);

    Status _build_bitset_in_predicates(PredicateCompoundNode<Type>& tree_root, PredicateParser* parser,
                                       ColumnPredicatePtrs& col_preds_owner);

    friend struct ColumnRangeBuilder;
    friend class ConjunctiveTestFixture;

    Status normalize_expressions();
    Status build_olap_filters();
    Status build_scan_keys(bool unlimited, int32_t max_scan_key_num);

    // If Type is OR, to convert OR to AND to utilize existing parsing logic, perform the `Negative` operation
    // in normalize_xxx_predicate and build_olap_filters. For example, `pred_c1_1 or pred_c1_2 or pred_c2_1 or pred_c2_2`
    // will be converted to `!(!pred_c1_1 and !pred_c1_2) or !(!pred_c2_1 and !pred_c2_2)`
    // The specific steps are as follows:
    // 1. When normalizing predicates to value ranges, pass true to <Negative> of each normalize_xxx_predicate.
    // 2. When building olap filters by the normalized value ranges, pass true to <Negative> of ColumnValueRange::to_olap_filter.
    template <LogicalType SlotType, typename RangeValueType>
    Status normalize_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType, bool Negative>
    requires(!lt_is_date<SlotType>) Status
            normalize_in_or_equal_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);
    template <LogicalType SlotType, typename RangeValueType, bool Negative>
    requires lt_is_date<SlotType> Status normalize_in_or_equal_predicate(const SlotDescriptor& slot,
                                                                         ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType, bool Negative>
    Status normalize_binary_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType, bool Negative>
    Status normalize_join_runtime_filter(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType, bool Negative>
    Status normalize_not_in_or_not_equal_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    Status normalize_is_null_predicate(const SlotDescriptor& slot);

    // To build `ColumnExprPredicate`s from conjuncts passed from olap scan node.
    // `ColumnExprPredicate` would be used in late materialization, zone map filtering,
    // dict encoded column filtering and bitmap value column filtering etc.
    Status build_column_expr_predicates();

    Expr* _gen_min_binary_pred(Expr* col_ref, VectorizedLiteral* min_literal, bool is_close_interval);
    Expr* _gen_max_binary_pred(Expr* col_ref, VectorizedLiteral* max_literal, bool is_close_interval);
    Expr* _gen_is_null_pred(Expr* col_ref);
    Expr* _gen_and_pred(Expr* left, Expr* right);
};

class ScanConjunctsManager {
public:
    explicit ScanConjunctsManager(ScanConjunctsManagerOptions&& opts);

    Status parse_conjuncts();

    static Status eval_const_conjuncts(const std::vector<ExprContext*>& conjunct_ctxs, Status* status);

    StatusOr<PredicateTree> get_predicate_tree(PredicateParser* parser, ColumnPredicatePtrs& col_preds_owner);
    StatusOr<RuntimeFilterPredicates> get_runtime_filter_predicates(ObjectPool* obj_pool, PredicateParser* parser);

    Status get_key_ranges(std::vector<std::unique_ptr<OlapScanRange>>* key_ranges);

    void get_not_push_down_conjuncts(std::vector<ExprContext*>* predicates);

    const UnarrivedRuntimeFilterList& unarrived_runtime_filters();

private:
    ScanConjunctsManagerOptions _opts;
    ChunkPredicateBuilder<BoxedExprContext, CompoundNodeType::AND> _root_builder;
};

} // namespace starrocks
