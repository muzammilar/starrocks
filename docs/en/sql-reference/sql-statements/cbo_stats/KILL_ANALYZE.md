---
displayed_sidebar: docs
---

# KILL ANALYZE

KILL ANALYZE cancels a **running** collection task, including manual and custom automatic tasks.

This statement is supported from v2.4.

## Syntax

```SQL
KILL ANALYZE <ID>
```

The task ID for a manual collection task can be obtained from SHOW ANALYZE STATUS. The task ID for a custom collection task can be obtained from SHOW ANALYZE SHOW ANALYZE JOB.

## References

[SHOW ANALYZE STATUS](SHOW_ANALYZE_STATUS.md)

[SHOW ANALYZE JOB](SHOW_ANALYZE_JOB.md)

For more information about collecting statistics for CBO, see [Gather statistics for CBO](../../../using_starrocks/Cost_based_optimizer.md).
