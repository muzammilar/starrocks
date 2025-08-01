---
displayed_sidebar: docs
---

# 部署前提条件

本文描述了部署 StarRocks 的服务器必须满足哪些软硬件要求。关于 StarRocks 集群的推荐硬件规格，请参阅[规划您的 StarRocks 集群](../deployment/plan_cluster.md)。

## 硬件

### CPU

StarRocks 依靠 AVX2 指令集充分发挥其矢量化能力。因此，在生产环境中，强烈建议您将 StarRocks 部署于 x86 架构 CPU 的服务器上。

您可以在终端中运行以下命令来检查 CPU 是否支持 AVX2 指令集：

```Bash
cat /proc/cpuinfo | grep avx2
```

### 内存

StarRocks 对内存没有特定要求。关于推荐的内存大小，请参考 [规划 StarRocks 集群 - CPU 和内存](../deployment/plan_cluster.md#cpu-和内存)。

### 存储

StarRocks 支持 HDD 和 SSD 作为存储介质。

在实时数据分析场景、以及涉及大量数据扫描或随机磁盘访问的场景下，强烈建议您选择 SSD 作为存储介质。

在涉及 [主键表](../table_design/table_types/primary_key_table.md) 持久化索引的场景中，您必须使用 SSD 作为存储介质。

### 网络

建议使用万兆网络连接（10 Gigabit Ethernet，简称 10 GE）确保 StarRocks 集群内数据能够跨节点高效传输。

## 操作系统

StarRocks 支持在 Red Hat Enterprise Linux 7.9、CentOS Linux 7.9 或 Ubuntu Linux 22.04 上部署。

## 软件

您必须在服务器上安装对应的 JDK 版本以运行 StarRocks。

- StarRocks v3.3, v3.4 请使用 JDK 11 及以上。
- StarRocks v3.5 请使用 JDK 17 及以上。

> **注意**
>
> StarRocks 不支持 JRE。
