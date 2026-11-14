# 探见项目性能与可靠性验证报告

验证日期：2026-11-14（Asia/Shanghai）。以下为本机隔离环境实测，不代表线上业务或最大容量。

基线核心版本 `88d6cd9`，改造核心版本 `e4ef49d`。修改前后使用相同数据、JVM堆和负载计划。

## 环境与口径

- Windows 11 x64；Intel Core Ultra 7 270K Plus，24个逻辑处理器，可用物理内存约23.5GiB。
- JDK 17.0.13，MySQL 8.0.34，Redis 8.10.2，JMeter 5.6.3。
- 应用与压测机同机，应用最大堆512MiB；读压测JMeter最大堆512MiB。桌面后台环境未完全隔离。
- 两个版本各用200名合成用户与14家示例店铺，独立数据库、Redis与后端；不调用外部模型。
- 20/50/100/200线程，各三轮；每轮90秒，排除前30秒，统计后约60秒；1秒启动，每个读请求前等待100毫秒。
- 表中吞吐为九类读请求合计，每项为三轮中位数；P95/P99先逐轮按nearest-rank计算，再取中位数。正常业务拒绝不混入系统错误。

## 混合读取对比

|线程|基线请求/秒|改造请求/秒|基线P95 ms|改造P95 ms|基线P99 ms|改造P99 ms|两版系统错误|
|---:|---:|---:|---:|---:|---:|---:|---:|
|20|185.94|186.0|3|3|4|4|0 / 0|
|50|462.81|464.94|4|3|5|4|0 / 0|
|100|920.75|924.62|10|4|14|6|0 / 0|
|200|1835.89|1821.7|9|9|16|15|0 / 0|

本轮重点是故障收敛和数据正确性。固定思考时间会限制吞吐，短L1 TTL及事件处理也有开销；这些结果不足以宣称最大QPS或确定某个小幅性能差异具有统计显著性。

## 数据库回源

MySQL performance_schema 的归一化店铺详情 `SELECT ... FROM tb_shop WHERE id = ?` 累计次数：基线14次、改造14次，包含各自完整矩阵的预热。两版已有热点互斥与Redis缓存，本轮没有把原有回源抑制效果宣称为新增提升。查询观察记录见 [database-observations.json](database-observations.json)。

## 真实业务与故障验证

|场景|结果|
|---|---|
|1000 distinct users / 100 coupons|通过；{"outcomes": {"ACCEPTED": 100, "SOLD_OUT": 900}, "orders": 100, "duplicate_users": 0, "stock": 0, "observed_end_to_end_ms": {"samples": 100, "p50": 942, "p95": 1356, "p99": 1370}}|
|100 duplicate submissions|通过；{"orders": 1, "stock": 99}|
|ordinary claim|通过；{"orders": 100}|
|reservation create / cancel|通过；{"cancelled": 50, "active": 0}|
|accepted order recovered by another JVM|通过；{"orders": 1}|
|two JVM broadcast invalidation|通过；{"visible_ms": 78.0}|
|missed notification / L1 TTL fallback|通过；{"visible_ms": 5047.0, "l1_max_ms": 5000}|
|subscriber reconnect clears L1|通过；{"reconnected": true}|
|two JVM / 200 cold hot-key reads|通过；{"database_loads": 1}|
|persisted invalidation event recovered after JVM restart|通过；{}|
|dry-run / safe replay / repeated replay refusal|通过；{"orders": 1}|
|management endpoint|通过；{"binding": "127.0.0.1", "ports": [19081, 19082]}|

1000用户抢购场景使用1000个JMeter线程、10秒启动与同步集合，JMeter最大堆1GiB；受理100次、售罄900次、最终100单、重复用户0，两侧库存0，Pending/lag归零。端到端耗时含200毫秒轮询观测间隔，不能作为纯消费服务耗时。

漏收通知用第二个Java进程关闭缓存订阅模拟，五秒TTL的观测值还包含HTTP及50毫秒轮询开销。热点场景为两个Java进程、最多100个客户端工作线程，共200次读取。完整样本见 [system-check.json](reliability/system-check.json)。

## 自动化检查

Java单元与控制器测试35项通过，2项既有手工测试跳过；真实MySQL/Redis集成测试10项通过。集成场景覆盖数据库提交后未ACK、五次失败保留预占、死信写入失败不ACK、无效消息隔离、库存键丢失、售罄补偿、Pending分页、事务回滚、事件重试及多缓存重建。两套结果见 [test-results.json](test-results.json)。

前端18个入口、17个视图检查通过；nginx配置检查通过。外部模型推理与真实短信、支付不在本轮验证范围。

## 数据与复现

- [基线逐轮汇总](baseline/summary.json) / [改造逐轮汇总](upgraded/summary.json) / [合并对比数据](comparison.json)。
- 两个目录内的 `.jtl.gz` 为实际JMeter采样的字段白名单副本，包含时间、耗时、标签和结果，不含请求/响应体、账号或token。
- [运行说明](../../jmeter/README.md) / [源码实验脚本](../../jmeter/system_check.py) / [表格生成脚本](../../jmeter/build_report.py)。
- 使用 `python performance/jmeter/build_report.py --directory performance/benchmarks/2026-11-14` 从原始采样重建本表。
