---
displayed_sidebar: docs
keywords: ['fenqu']
sidebar_position: 10
---

# 表达式分区（推荐）

自 v3.0 起，StarRocks 支持表达式分区（原称自动创建分区），更加灵活易用，适用于大多数场景，比如按照连续日期范围或者枚举值来查询和管理数据。

您仅需要在建表时设置分区表达式。在数据导入时，StarRocks 会根据数据和分区表达式的定义规则自动创建分区，您无需在建表时预先手动/批量创建大量分区，或者配置动态分区属性。

从 v3.4 开始，表达式分区方式进一步得到优化，统一所有分区策略，并支持更复杂的解决方案。在大多数情况下，建议您使用表达式分区。表达式分区将在未来版本中逐渐取代其他分区策略。

从 v3.5 开始，StarRocks 支持根据时间函数合并表达式分区，以优化存储效率和查询性能。详细信息请参阅 [表达式分区合并](#表达式分区合并)。

## 简单时间函数表达式分区

如果您经常按照连续日期范围来查询和管理数据，则只需要在时间函数分区表达式中，指定一个日期类型（DATE 或者 DATETIME ）的分区列，以及指定分区粒度（年、月、日或小时）。StarRocks 会根据导入的数据和分区表达式，自动创建分区并且设置分区的起止时间。

不过在一些特殊场景下，比如历史数据按月划分分区、最近数据按天划分分区，则需要采用 [Range 分区](./Data_distribution.md#range-分区)创建分区。

### 语法

```sql
PARTITION BY expression
...
[ PROPERTIES( { 'partition_live_number' = 'xxx' | 'partition_retention_condition' = 'expr' } ) ]

expression ::=
    { date_trunc ( <time_unit> , <partition_column> ) |
      time_slice ( <partition_column> , INTERVAL <N> <time_unit> [ , boundary ] ) }
```

### 参数解释

#### `expression`

- 必填：是
- 说明：使用 [date_trunc](../../sql-reference/sql-functions/date-time-functions/date_trunc.md) 或 [time_slice](../../sql-reference/sql-functions/date-time-functions/time_slice.md) 的简单时间函数表达式。并且如果您使用 `time_slice` 函数，则可以不传入参数 `boundary`，因为在该场景中该参数默认且仅支持为 `floor`，不支持为 `ceil`。

#### `time_unit`

- 必填：是
- 说明：分区粒度，目前仅支持为 `hour`、`day`、`month` 或 `year`，暂时不支持为 `week`。如果分区粒度为 `hour`，则仅支持分区列为 DATETIME 类型，不支持为 DATE 类型。

#### `partition_column`

- 必填：是
- 说明：分区列。仅支持为日期类型（DATE 或 DATETIME），不支持为其它类型。
  - 如果使用 `date_trunc` 函数，则分区列支持为 DATE 或 DATETIME 类型。
  - 如果使用 `time_slice` 函数，则分区列仅支持为 DATETIME 类型。分区列的值支持为 `NULL`。
  - 如果分区列是 DATE 类型，则范围支持为 [0000-01-01 ~ 9999-12-31]。
  - 如果分区列是 DATETIME 类型，则范围支持为 [0000-01-01 01:01:01 ~ 9999-12-31 23:59:59]。
  - 目前仅支持指定一个分区列，不支持指定多个分区列。

#### `partition_live_number`

- 必填：否
- 说明：保留最近多少数量的分区。最近是指分区按时间的先后顺序进行排序，以**当前时间**为基准，然后从后往前数指定个数的分区进行保留，其余（更早的）分区会被删除。后台会定时调度任务来管理分区数量，调度间隔可以通过 FE 动态参数 `dynamic_partition_check_interval_seconds` 配置，默认为 600 秒，即 10 分钟。假设当前为 2023 年 4 月 4 日，`partition_live_number` 设置为 `2`，分区包含 `p20230401`、`p20230402`、`p20230403`、`p20230404`，则分区 `p20230403`、`p20230404` 会保留，其他分区会删除。如果导入了脏数据，比如未来时间 4 月 5 日和 6 日的数据，导致分区包含 `p20230401`、`p20230402`、`p20230403`、`p20230404`、`p20230405`、`p20230406`，则分区 `p20230403`、`p20230404`、`p20230405`、`p20230406` 会保留，其他分区会删除。

#### `partition_retention_condition`

从 v3.5.0 开始，StarRocks 内表支持通用分区表达式（Common Partition Expression）TTL。

- 必填：否
- 说明：用于声明动态保留分区的表达式。不符合表达式中条件的分区将被定期删除。示例：`"partition_retention_condition" = "dt >= CURRENT_DATE() - INTERVAL 3 MONTH"`。
  - 表达式只能包含分区列和常量。不支持非分区列。
  - 通用分区表达式处理 List 分区和 Range 分区的方式不同：
    - 对于 List 分区表，StarRocks 支持通过通用分区表达式过滤删除分区。
    - 对于 Range 分区表，StarRocks 只能基于 FE 的分区裁剪功能过滤删除分区。对于分区裁剪不支持的谓词，StarRocks 无法过滤删除对应的分区。

### 使用说明

- 在导入的过程中 StarRocks 根据导入数据已经自动创建了一些分区，但是由于某些原因导入作业最终失败，则在当前版本中，已经自动创建的分区并不会由于导入失败而自动删除。
- StarRocks 单次导入自动创建分区数量上限默认为 4096，由 FE 配置参数 `auto_partition_max_creation_number_per_load` 决定。该参数可以防止您由于误操作而创建大量分区。
- 分区命名规则与动态分区的命名规则一致。

### 示例

示例一：假设您经常按天查询数据，则建表时可以使用分区表达式 `date_trunc()` ，并且设置分区列为 `event_day` ，分区粒度为 `day`，实现导入数据时自动按照数据所属日期划分分区。将同一天的数据存储在一个分区中，利用分区裁剪可以显著提高查询效率。

```SQL
CREATE TABLE site_access1 (
    event_day DATETIME NOT NULL,
    site_id INT DEFAULT '10',
    city_code VARCHAR(100),
    user_name VARCHAR(32) DEFAULT '',
    pv BIGINT DEFAULT '0'
)
DUPLICATE KEY(event_day, site_id, city_code, user_name)
PARTITION BY date_trunc('day', event_day)
DISTRIBUTED BY HASH(event_day, site_id);
```

导入如下两行数据，则 StarRocks 会根据导入数据的日期范围自动创建两个分区  `p20230226`、`p20230227`，范围分别为 [2023-02-26 00:00:00,2023-02-27 00:00:00)、[2023-02-27 00:00:00,2023-02-28 00:00:00)。如果后续导入数据的日期属于这两个范围，则都会自动划分至对应分区。

```SQL
-- 导入两行数据
INSERT INTO site_access1 
    VALUES ("2023-02-26 20:12:04",002,"New York","Sam Smith",1),
           ("2023-02-27 21:06:54",001,"Los Angeles","Taylor Swift",1);

-- 查询分区
mysql > SHOW PARTITIONS FROM site_access1;
+-------------+---------------+----------------+---------------------+--------------------+--------+--------------+------------------------------------------------------------------------------------------------------+--------------------+---------+----------------+---------------+---------------------+--------------------------+----------+------------+----------+
| PartitionId | PartitionName | VisibleVersion | VisibleVersionTime  | VisibleVersionHash | State  | PartitionKey | Range                                                                                                | DistributionKey    | Buckets | ReplicationNum | StorageMedium | CooldownTime        | LastConsistencyCheckTime | DataSize | IsInMemory | RowCount |
+-------------+---------------+----------------+---------------------+--------------------+--------+--------------+------------------------------------------------------------------------------------------------------+--------------------+---------+----------------+---------------+---------------------+--------------------------+----------+------------+----------+
| 17138       | p20230226     | 2              | 2023-07-19 17:53:59 | 0                  | NORMAL | event_day    | [types: [DATETIME]; keys: [2023-02-26 00:00:00]; ..types: [DATETIME]; keys: [2023-02-27 00:00:00]; ) | event_day, site_id | 6       | 3              | HDD           | 9999-12-31 23:59:59 | NULL                     | 0B       | false      | 0        |
| 17113       | p20230227     | 2              | 2023-07-19 17:53:59 | 0                  | NORMAL | event_day    | [types: [DATETIME]; keys: [2023-02-27 00:00:00]; ..types: [DATETIME]; keys: [2023-02-28 00:00:00]; ) | event_day, site_id | 6       | 3              | HDD           | 9999-12-31 23:59:59 | NULL                     | 0B       | false      | 0        |
+-------------+---------------+----------------+---------------------+--------------------+--------+--------------+------------------------------------------------------------------------------------------------------+--------------------+---------+----------------+---------------+---------------------+--------------------------+----------+------------+----------+
2 rows in set (0.00 sec)
```

示例二：如果您希望引入分区生命周期管理，即仅保留最近一段时间的分区，删除历史分区，则可以使用 `partition_live_number` 设置只保留最近多少数量的分区。

```SQL
CREATE TABLE site_access2 (
    event_day DATETIME NOT NULL,
    site_id INT DEFAULT '10',
    city_code VARCHAR(100),
    user_name VARCHAR(32) DEFAULT '',
    pv BIGINT DEFAULT '0'
) 
DUPLICATE KEY(event_day, site_id, city_code, user_name)
PARTITION BY date_trunc('month', event_day)
DISTRIBUTED BY HASH(event_day, site_id)
PROPERTIES(
    "partition_live_number" = "3" -- 只保留最近 3 个分区
);
```

示例三：假设您经常按周查询数据，则建表时可以使用分区表达式 `time_slice()`，设置分区列为 `event_day`，分区粒度为七天。将一周的数据存储在一个分区中，利用分区裁剪可以显著提高查询效率。

```SQL
CREATE TABLE site_access3 (
    event_day DATETIME NOT NULL,
    site_id INT DEFAULT '10',
    city_code VARCHAR(100),
    user_name VARCHAR(32) DEFAULT '',
    pv BIGINT DEFAULT '0'
)
DUPLICATE KEY(event_day, site_id, city_code, user_name)
PARTITION BY time_slice(event_day, INTERVAL 7 day)
DISTRIBUTED BY HASH(event_day, site_id);
```

## 列表达式分区（自 v3.1）

如果您经常按照枚举值来查询和管理数据，则您只需要指定表示类型的列为分区列，StarRocks 会根据导入的数据的分区列值，来自动划分并创建分区。

不过在一些特殊场景下，比如表中包含表示城市的列，您经常按照国家和城市来查询和管理数据，希望将同属于一个国家的多个城市的数据存储在一个分区中，则需要使用 [List 分区](./list_partitioning.md)。

### 语法

```SQL
PARTITION BY expression
...

expression ::=
    <partition_columns>
    
partition_columns ::=
    <column>, [ <column> [,...] ]
```

### 参数解释

| 参数                    | 是否必填 | 参数                                                         |
| ----------------------- | -------- | ------------------------------------------------------------ |
| `partition_columns`     | 是       | 分区列。<br /><ul><li>支持为字符串（不支持 BINARY）、日期、整数和布尔值。不支持分区列的值为 `NULL`。</li><li> 导入后自动创建的一个分区中只能包含各分区列的一个值，如果需要包含各分区列的多值，请使用 [List 分区](./list_partitioning.md)。</li></ul> |

:::note

从 v3.4 版开始，您可以省略用于包裹分区列的括号。例如，您可以将 `PARTITION BY (dt,city)` 替换为 `PARTITION BY dt,city`。

:::

### 使用说明

- 在导入的过程中 StarRocks 根据导入数据已经自动创建了一些分区，但是由于某些原因导入作业最终失败，则在当前版本中，已经自动创建的分区并不会由于导入失败而自动删除。
- StarRocks 单次导入自动创建分区数量上限默认为 4096，由 FE 配置参数 `auto_partition_max_creation_number_per_load` 决定。该参数可以防止您由于误操作而创建大量分区。
- 分区命名规则：如果存在多个分区列，则不同分区列的值以下划线（_）连接。例如：存在有两个分区列 `dt` 和 `city`，均为字符串类型，导入一条数据 `2022-04-01`, `beijing`，则自动创建的分区名称为 `p20220401_beijing`。

### 示例

示例一：假设经常按日期范围和特定城市查询机房收费明细，则建表时可以使用分区表达式指定分区列为日期 `dt` 和城市 `city`。这样属于相同日期和城市的数据分组到同一个分区中，利用分区裁剪可以显著提高查询效率。

```SQL
CREATE TABLE t_recharge_detail1 (
    id bigint,
    user_id bigint,
    recharge_money decimal(32,2), 
    city varchar(20) not null,
    dt varchar(20) not null
)
DUPLICATE KEY(id)
PARTITION BY dt,city
DISTRIBUTED BY HASH(`id`);
```

导入一条数据。

```SQL
INSERT INTO t_recharge_detail1 
    VALUES (1, 1, 1, 'Houston', '2022-04-01');
```

查看具体分区。返回结果显示，StarRocks 根据导入数据的分区列值自动创建一个分区 `p20220401_Houston` ，如果后续导入数据的分区列 `dt` 和 `city` 的值是 `2022-04-01`和 `Houston`，则都会被划分至该分区。

> **说明**
>
> 分区中只能包含各分区列的一个值，如果需要一个分区中包含各分区列的多值，请使用 [List 分区](./list_partitioning.md)。

```SQL
MySQL > SHOW PARTITIONS from t_recharge_detail1\G
*************************** 1. row ***************************
             PartitionId: 16890
           PartitionName: p20220401_Houston
          VisibleVersion: 2
      VisibleVersionTime: 2023-07-19 17:24:53
      VisibleVersionHash: 0
                   State: NORMAL
            PartitionKey: dt, city
                    List: (('2022-04-01', 'Houston'))
         DistributionKey: id
                 Buckets: 6
          ReplicationNum: 3
           StorageMedium: HDD
            CooldownTime: 9999-12-31 23:59:59
LastConsistencyCheckTime: NULL
                DataSize: 2.5KB
              IsInMemory: false
                RowCount: 1
1 row in set (0.00 sec)
```

## 复杂时间函数表达式分区 (自 v3.4)

从 v3.4.0 版本开始，表达式分区支持返回 DATE 或 DATETIME 类型的任意表达式，以满足更加复杂的分区场景需求。有关支持的时间函数，请参阅 [附录 - 支持的时间函数](#支持的时间函数)。

例如，您可以定义一个 Unix 时间戳列，并直接在分区表达式中使用 from_unixtime() 函数作为分区键，而无需通过该函数生成一个 DATE 或 DATETIME 列。有关用法的更多信息，请参见以下示例。

自 v3.4.4 起，基于大多数 DATETIME 相关函数的构建的分区都支持分区裁剪。

### 示例

示例一：假设您为每行数据分配一个 Unix 时间戳，并且经常按天查询数据，则建表时可以使用 from_unixtime() 函数，并且设置分区列为时间戳列，分区粒度为一天。将同一天的数据存储在一个分区中，利用分区裁剪可以显著提高查询效率。

```SQL
CREATE TABLE orders (
    ts BIGINT NOT NULL,
    id BIGINT NOT NULL,
    city STRING NOT NULL
)
PARTITION BY from_unixtime(ts,'%Y%m%d');
```

示例二：假设您为每行数据分配了一个 INT 类型时间戳，并且按月存储数据，则建表时可以使用 cast() 和 str_to_date() 函数将时间戳转换为 DATE 类型作为分区列，并使用 date_trunc() 将分区粒度设置为为一月。将同一月的数据存储在一个分区中，利用分区裁剪可以显著提高查询效率。

```SQL
CREATE TABLE orders_new (
    ts INT NOT NULL,
    id BIGINT NOT NULL,
    city STRING NOT NULL
)
PARTITION BY date_trunc('month', str_to_date(CAST(ts as STRING),'%Y%m%d'));
```

### 使用说明

基于复杂时间函数表达式的分区支持分区裁剪，具体包括以下情况：

- 如果分区子句为 `PARTITION BY from_unixtime(ts)`，则带有格式为 `ts > 1727224687` 条件的查询可以裁剪到相应的分区。
- 如果分区子句为 `PARTITION BY str2date(CAST(ts AS string),'%Y%m')`，则带有格式为 `ts = "20240506"` 条件的查询可以裁剪到相应的分区。
- 上述情况同样适用于 [混合表达式分区](#混合表达式分区-自-v34).

## 混合表达式分区 (自 v3.4)

从 v3.4.0 版本开始，表达式分区支持使用多个分区列，其中一个列可以为时间函数表达式。

### 示例

示例一：假设您为每行数据分配一个 Unix 时间戳，并且经常按天和特定城市查询数据，则建表时可以使用时间戳列（结合 from_unixtime() 函数）和城市列作为分区列。将同个城市同一天的数据存储在一个分区中，利用分区裁剪可以显著提高查询效率。

```SQL
CREATE TABLE orders (
    ts BIGINT NOT NULL,
    id BIGINT NOT NULL,
    city STRING NOT NULL
)
PARTITION BY from_unixtime(ts,'%Y%m%d'), city;
```

## 管理分区

### 导入数据至分区

导入数据时，StarRocks 会根据数据和分区表达式定义的分区规则，自动创建分区。

值得注意的是，如果您建表时使用表达式分区，并且需要使用 [INSERT OVERWRITE](../../loading/InsertInto.md#通过-insert-overwrite-select-语句覆盖写入数据) 覆盖写入**指定分区**的数据，不论该分区是否已经创建，目前您都需要在 `PARTITION()` 提供明确的分区范围。而不是同 Range 分区或 List 分区一样，只需要在`PARTITION (partition_name)` 中提供分区名称。

如果建表时您使用时间函数表达式分区，则此时覆盖写入指定分区，您需要提供该分区的起始范围（分区粒度与建表时配置分区粒度一致）。如果该分区不存在，则导入数据时会自动创建该分区。

```SQL
INSERT OVERWRITE site_access1 PARTITION(event_day='2022-06-08 20:12:04')
    SELECT * FROM site_access2 PARTITION(p20220608);
```

如果建表时您使用列表达式分区，则覆盖写入指定分区时，您需要提供该分区包含的分区列值。如果该分区不存在，则导入数据时会自动创建该分区。

```SQL
INSERT OVERWRITE t_recharge_detail1 PARTITION(dt='2022-04-02',city='texas')
    SELECT * FROM t_recharge_detail2 PARTITION(p20220402_texas);
```

### 查看分区

查看自动创建的分区的具体信息时，您需要使用 `SHOW PARTITIONS FROM <table_name>` 语句。而`SHOW CREATE TABLE <table_name>` 语句返回的结果中仅包含建表时配置的表达式分区语法。

```SQL
MySQL > SHOW PARTITIONS FROM t_recharge_detail1;
+-------------+-------------------+----------------+---------------------+--------------------+--------+--------------+-----------------------------+-----------------+---------+----------------+---------------+---------------------+--------------------------+----------+------------+----------+
| PartitionId | PartitionName     | VisibleVersion | VisibleVersionTime  | VisibleVersionHash | State  | PartitionKey | List                        | DistributionKey | Buckets | ReplicationNum | StorageMedium | CooldownTime        | LastConsistencyCheckTime | DataSize | IsInMemory | RowCount |
+-------------+-------------------+----------------+---------------------+--------------------+--------+--------------+-----------------------------+-----------------+---------+----------------+---------------+---------------------+--------------------------+----------+------------+----------+
| 16890       | p20220401_Houston | 2              | 2023-07-19 17:24:53 | 0                  | NORMAL | dt, city     | (('2022-04-01', 'Houston')) | id              | 6       | 3              | HDD           | 9999-12-31 23:59:59 | NULL                     | 2.5KB    | false      | 1        |
| 17056       | p20220402_texas   | 2              | 2023-07-19 17:27:42 | 0                  | NORMAL | dt, city     | (('2022-04-02', 'texas'))   | id              | 6       | 3              | HDD           | 9999-12-31 23:59:59 | NULL                     | 2.5KB    | false      | 1        |
+-------------+-------------------+----------------+---------------------+--------------------+--------+--------------+-----------------------------+-----------------+---------+----------------+---------------+---------------------+--------------------------+----------+------------+----------+
2 rows in set (0.00 sec)
```

### 表达式分区合并

在数据管理中，基于不同时间粒度分区对于优化查询和存储至关重要。为了提高存储效率和查询性能，StarRocks 支持将多个细时间粒度的表达分区合并为一个粗时间粒度的分区，例如，将按日划分的分区合并为按月划分的分区。通过合并符合指定条件（时间范围）的分区，StarRocks 允许按不同的时间粒度对数据进行分区。

#### 语法

```sql
ALTER TABLE [<db_name>.]<table_name>
PARTITION BY <time_expr>
BETWEEN <start_time> AND <end_time>
```

#### 参数解释

##### `PARTITION BY <time_expr>`

- 必填：是
- 说明：指定分区策略的新时间粒度，例如，`PARTITION BY date_trunc('month', dt)`。

##### `WHERE <time_range_column> BETWEEN <start_time> AND <end_time>`

- 必填：是
- 说明：指定要合并的分区的时间范围。该范围内的分区将根据 `PARTITION BY` 子句中定义的规则进行合并。

#### 示例

合并表 `site_access1` 中的分区，并将分区时间粒度从日改为月。合并分区的时间范围从 `2024-01-01` 到 `2024-03-31`。

```SQL
ALTER TABLE site_access1 PARTITION BY date_trunc('month', event_day)
BETWEEN '2024-01-01' AND '2024-03-31';
```

合并后：

- 日级分区 `2024-01-01` 至 `2024-01-31` 合并为月级分区 `2024-01`。
- 日级分区 `2024-02-01` 至 `2024-02-29` 合并为月级分区 `2024-02`。
- 日级分区 `2024-03-01` 至 `2024-03-31` 合并为月级分区 `2024-03`。

#### 使用说明

- 只支持合并基于时间函数的表达式分区。
- 不支持合并具有多个分区列的分区。
- 不支持合并与 Schema Change/DML 并行执行。

## 使用限制

- 自 v3.1.0 起，StarRocks [存算分离模式](../../deployment/shared_data/shared_data.mdx)支持[时间函数表达式分区](#简单时间函数表达式分区)。并且自 v3.1.1 起 StarRocks 存算分离模式支持[列表达式分区](#列表达式分区自-v31)。
- 使用 CTAS 建表时暂时不支持表达式分区。
- 暂时不支持使用 Spark Load 导入数据至表达式分区的表。
- 使用 `ALTER TABLE <table_name> DROP PARTITION <partition_name>` 删除列表达式分区时，分区直接被删除并且不能被恢复。
- 自 v3.4.0、v3.3.8、v3.2.13 以及 v3.1.16 起，StarRocks 支持[备份与恢复](../../administration/management/Backup_and_restore.md)表达式分区表。
- 如果使用表达式分区，则仅支持回滚到 2.5.4 及以后的版本。

## 附录

### 支持的时间函数

表达式分区支持以下函数：

**时间函数**:

- timediff
- datediff
- to_days
- years_add/sub
- quarters_add/sub
- months_add/sub
- weeks_add/sub
- date_add/sub
- days_add/sub
- hours_add/sub
- minutes_add/sub
- seconds_add/sub
- milliseconds_add/sub
- date_trunc
- date_format(YmdHiSf/YmdHisf)
- str2date(YmdHiSf/YmdHisf)
- str_to_date(YmdHiSf/YmdHisf)
- to_iso8601
- to_date
- unix_timestamp
- from_unixtime(YmdHiSf/YmdHisf)
- time_slice

**其他函数**：

- add
- subtract
- cast

:::note

- 支持多种时间函数的组合使用。
- 上述所有时间函数均使用系统默认时区。
- 时间函数的值的格式 `YmdHiSf` 必须以最粗时间粒度 `%Y` 开始。不允许以更细的时间粒度（例如 `%m-%d`）开始的格式。

**示例**

`PARTITION BY from_unixtime(cast(str as INT) + 3600, '%Y-%m-%d')`

:::
