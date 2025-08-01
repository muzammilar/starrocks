---
displayed_sidebar: docs
---

# EXPLAIN ANALYZE

EXPLAIN ANALYZE executes the specified SQL statement, and shows the query profile of this statement. For more information about query profile, see [Query Profile Overview](../../../../best_practices/query_tuning/query_profile_overview.md).

This feature is supported from v3.1 onwards.

> **CAUTION**
>
> Only users with the SELECT or INSERT privilege on a specific table can perform this operation.

## Syntax

```SQL
EXPLAIN ANALYZE <statement>
```

## Parameters

`statement`: the SQL statement of which to show the query profile. Both [SELECT](../../table_bucket_part_index/SELECT.md) and [INSERT INTO](../../loading_unloading/INSERT.md) are supported.

## Usage notes

Please note that when you analyze the profiles of an INSERT INTO statement, no data will actually be inserted. By default, the transaction is aborted, ensuring that no unintended changes are made to the data in the process of profile analysis.

## Examples

Example 1: Simulate and analyze a SELECT statement. The query result are discarded.

![img](../../../../_assets/Profile/text_based_explain_analyze_select.jpeg)

Example 2: Simulate and analyze an INSERT INTO statement. The loading transaction will be aborted.

![img](../../../../_assets/Profile/text_based_explain_analyze_insert.jpeg)

## Relevant SQLs

- [SHOW PROFILELIST](./SHOW_PROFILELIST.md)
- [ANALYZE PROFILE](./ANALYZE_PROFILE.md)
