# JMeter 与可靠性验证

[项目首页](../../README.md) · [实测报告](../benchmarks/2026-11-14/REPORT.md) · [设计说明](../../docs/reliability.md)

工具只使用 Python 标准库。需要 JDK 17、MySQL 8 客户端与服务、可执行的 Redis Server、Apache JMeter 5.6.3。Java、mysql 可从 PATH 查找；Redis 可通过 `--redis-server` 或 `TANJIAN_REDIS_SERVER` 指定。MySQL 密码由交互输入或当前进程的 `MYSQL_PWD` 提供，不写入夹具或 Java 命令行。

## 编译与独立夹具

从项目根目录执行，Windows 使用 `backend\mvnw.cmd`，Linux/macOS 使用 `backend/mvnw`：

```powershell
backend\mvnw.cmd -f backend/pom.xml -DskipTests package
python performance/jmeter/manage.py prepare --users 1000 --stock 100 --redis-server "Redis Server绝对路径"
```

prepare 创建独立 `tanjian_perf_*` 数据库、16379 Redis、18081 后端和19081回环管理端口。现有 6379/8080/8081 服务保持运行。发现占用或已有活动夹具时会拒绝覆盖。根 `db.sql` 只导入新建测试库；补充脚本中的已知 `USE hmdp` 在内存中去掉，再拒绝其他切库语句。

验证码、CSV、token、fixture.json 和服务日志都留在已忽略的 `.runtime` 中。外部模型 URL 指向本机禁用端口。脚本只停止 PID 和数据库标识均匹配的本次进程。

## 六套计划

| 计划 | 用途 | 必须核对 |
| --- | --- | --- |
| login-load.jmx | 验证码登录 | HTTP 与 JSON success、有效 token |
| read-load.jmx | 九类混合读取 | 统一窗口、P95/P99、错误率 |
| seckill-load.jmx | 不同用户争抢 | 受理与最终 CREATED 分开统计 |
| duplicate-load.jmx | 同用户重复提交 | 最终一单、库存仅扣一次 |
| claim-load.jmx | 普通优惠券领取 | 实际落库与唯一约束 |
| reservation-load.jmx | 预约创建后取消 | 活动预约归零 |

每线程读取不同合成用户行。重复计划单独循环读取同一个用户。线程先调用真实登录接口，再发起后续请求。售罄与重复是正常业务拒绝，独立于系统错误统计。

示例（先将 ApacheJMeter.jar 的实际路径填入）：

```powershell
java -jar "ApacheJMeter.jar绝对路径" -n -t performance/jmeter/seckill-load.jmx -q performance/jmeter/.runtime/current.properties -Jthreads=1000 -Jramp_s=10 -Jattempts=1 -Jpoll_limit=150 -l performance/jmeter/results/seckill.jtl
python performance/jmeter/analyze.py performance/jmeter/results/seckill.jtl --expected-threads 1000
python performance/jmeter/manage.py verify --case seckill --expected-count 100
```

创建 results 目录后使用新的输出文件名。JMeter 退出码为零并不代表所有业务断言通过，需运行 analyze 和数据库/Redis 终态校验。查询请求和轮询请求不应计入秒杀提交吞吐量。成功订单的端到端耗时包含轮询观测延迟。

## 自动端到端检查

```powershell
python performance/jmeter/system_check.py --jmeter "ApacheJMeter.jar绝对路径" --redis-server "Redis Server绝对路径" --output performance/benchmarks/local-system
```

该脚本使用自己命名的夹具状态目录，自动验证1000用户抢100券、100次重复提交、普通券、预约、两个独立Java进程、广播失效、关闭第二实例通知后的TTL兜底、订阅重连、热点回源、进程重启及安全重放。输出脱敏结果与压缩原始采样，退出时清理自己的服务和测试库。断言失败会返回非零。

## 相同条件的版本对比

```powershell
python performance/jmeter/benchmark.py --project-dir "待测版本目录" --label "提交SHA" --jmeter "ApacheJMeter.jar绝对路径" --java "java绝对路径" --mysql "mysql绝对路径" --redis-server "Redis Server绝对路径" --output "结果目录"
```

默认20、50、100、200线程，每档三轮。每轮单进程运行90秒，1秒启动线程，前30秒排除，统计后60秒；每次读取前等待100毫秒。应用和JMeter分别固定512MiB最大堆。原始采样只保留白名单字段，不包含请求体、响应体、账号与token。本仓库对比报告同时说明同机资源竞争与一致性取舍，不能据此推导线上最大容量。

## 核对与重放

默认只读，显式使用当前夹具文件与订单 ID：

```powershell
python performance/jmeter/recover_orders.py --fixture "fixture.json路径" --order-id 订单ID
python performance/jmeter/recover_orders.py --fixture "fixture.json路径" --order-id 订单ID --apply
python performance/jmeter/recover_orders.py --fixture "fixture.json路径" --order-id 订单ID --apply --replay
```

`--apply` 只确认数据库中已有的事实；未落库订单仍保留待核对状态。`--apply --replay` 仅允许沿用未释放的预占和原订单ID，重复执行会拒绝。已补偿失败不能重放。工具限制为明确的隔离 `tanjian_perf_*` 夹具，不会自动退款或处理任意业务库。

## 停止与 GUI

```powershell
python performance/jmeter/manage.py stop --drop-test-database
```

自动检查和版本对比会在自己的状态目录中清理，无需手工 stop。手工夹具用上述命令；不带参数可保留测试库。不要通过结束全部 java 或 redis 进程清理环境。

JMX 可以用GUI检查和小流量调试；正式负载使用非GUI模式。生成器保留 HTTP Defaults 的空 Arguments 节点，兼容 JMeter 5.6.3 GUI 加载。历史记录见 [SMOKE-RESULTS](SMOKE-RESULTS.md)，不代表当前容量。[JMeter 官方最佳实践](https://jmeter.apache.org/usermanual/best-practices.html)
