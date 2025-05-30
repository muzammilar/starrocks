---
displayed_sidebar: docs
---

# DROP CATALOG

DROP CATALOG deletes an external catalog. The internal catalog cannot be deleted. A StarRocks cluster has only one internal catalog named `default_catalog`.

## Syntax

```SQL
DROP CATALOG [IF EXISTS] <catalog_name>
```

## Parameters

`catalog_name`: The name of an external catalog.

## Examples

Create a Hive catalog named `hive1`.

```SQL
CREATE EXTERNAL CATALOG hive1
PROPERTIES(
  "type"="hive", 
  "hive.metastore.uris"="thrift://xx.xx.xx.xx:9083"
);
```

Delete the Hive catalog.

```SQL
DROP CATALOG hive1;
```
