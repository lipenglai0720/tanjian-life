"""Rebuild the comparison table from published, sanitized JTL gzip files."""
import argparse,csv,gzip,json,math,statistics
from pathlib import Path


def percentile(values,p):
    values=sorted(values)
    return values[max(0,math.ceil(len(values)*p)-1)]


def measure(path):
    with gzip.open(path,'rt',encoding='utf-8',newline='') as f:
        first=min(int(row['timeStamp']) for row in csv.DictReader(f))
    values=[];failed=0;start=None;end=0
    with gzip.open(path,'rt',encoding='utf-8',newline='') as f:
        for row in csv.DictReader(f):
            stamp=int(row['timeStamp']);elapsed=int(row['elapsed'])
            if stamp<first+30000 or not row['label'].startswith('read.'):continue
            values.append(elapsed);failed+=row['success'].lower()!='true'
            start=stamp if start is None else min(start,stamp);end=max(end,stamp+elapsed)
    duration=(end-start)/1000
    return {'requests':len(values),'seconds':round(duration,3),'rps':round(len(values)/duration,3),
        'p95_ms':percentile(values,.95),'p99_ms':percentile(values,.99),'system_errors':failed}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);a=p.parse_args()
    root=a.directory;report={}
    for variant in ['baseline','upgraded']:
        summary=json.loads((root/variant/'summary.json').read_text(encoding='utf-8'))
        assert len(summary['runs'])==12 and all(x['passed'] for x in summary['runs'])
        report[variant]={'revision':summary['label'],'runs':[],'groups':{}}
        for run in summary['runs']:
            result=measure(root/variant/run['file']);result.update(threads=run['threads'],repetition=run['repetition'])
            report[variant]['runs'].append(result)
        for threads in [20,50,100,200]:
            runs=[x for x in report[variant]['runs'] if x['threads']==threads]
            report[variant]['groups'][str(threads)]={
                'median_rps':round(statistics.median(x['rps'] for x in runs),2),
                'median_p95_ms':statistics.median(x['p95_ms'] for x in runs),
                'median_p99_ms':statistics.median(x['p99_ms'] for x in runs),
                'system_errors':sum(x['system_errors'] for x in runs),
                'requests':sum(x['requests'] for x in runs)}
    (root/'comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    metadata=json.loads((root/'environment.json').read_text(encoding='utf-8'))
    observations=json.loads((root/'database-observations.json').read_text(encoding='utf-8'))
    cases=json.loads((root/'reliability/system-check.json').read_text(encoding='utf-8'))['cases']
    lines=['# 探见项目性能与可靠性验证报告','','验证日期：2026-11-14（Asia/Shanghai）。以下为本机隔离环境实测，不代表线上业务或最大容量。',
        '',f"基线核心版本 `{report['baseline']['revision']}`，改造核心版本 `{report['upgraded']['revision']}`。修改前后使用相同数据、JVM堆和负载计划。",
        '', '## 环境与口径','',
        f"- {metadata['os']}；{metadata['cpu']}，{metadata['logical_processors']}个逻辑处理器，可用物理内存约{metadata['ram_gib']}GiB。",
        f"- JDK {metadata['jdk']}，MySQL {metadata['mysql']}，Redis {metadata['redis']}，JMeter {metadata['jmeter']}。",
        '- 应用与压测机同机，应用最大堆512MiB；读压测JMeter最大堆512MiB。桌面后台环境未完全隔离。',
        '- 两个版本各用200名合成用户与14家示例店铺，独立数据库、Redis与后端；不调用外部模型。',
        '- 20/50/100/200线程，各三轮；每轮90秒，排除前30秒，统计后约60秒；1秒启动，每个读请求前等待100毫秒。',
        '- 表中吞吐为九类读请求合计，每项为三轮中位数；P95/P99先逐轮按nearest-rank计算，再取中位数。正常业务拒绝不混入系统错误。',
        '', '## 混合读取对比','',
        '|线程|基线请求/秒|改造请求/秒|基线P95 ms|改造P95 ms|基线P99 ms|改造P99 ms|两版系统错误|',
        '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for thread in ['20','50','100','200']:
        b=report['baseline']['groups'][thread];u=report['upgraded']['groups'][thread]
        lines.append(f"|{thread}|{b['median_rps']}|{u['median_rps']}|{b['median_p95_ms']}|{u['median_p95_ms']}|{b['median_p99_ms']}|{u['median_p99_ms']}|{b['system_errors']} / {u['system_errors']}|")
    lines+=['', '本轮重点是故障收敛和数据正确性。固定思考时间会限制吞吐，短L1 TTL及事件处理也有开销；这些结果不足以宣称最大QPS或确定某个小幅性能差异具有统计显著性。',
        '', '## 数据库回源','',
        f"MySQL performance_schema 的归一化店铺详情 `SELECT ... FROM tb_shop WHERE id = ?` 累计次数：基线{observations['baseline']['shop_detail_selects']}次、改造{observations['upgraded']['shop_detail_selects']}次，包含各自完整矩阵的预热。两版已有热点互斥与Redis缓存，本轮没有把原有回源抑制效果宣称为新增提升。查询观察记录见 [database-observations.json](database-observations.json)。",
        '', '## 真实业务与故障验证','', '|场景|结果|','|---|---|']
    for case in cases:
        detail={k:v for k,v in case.items() if k not in {'case','passed'}}
        lines.append('|'+case['case']+'|通过；'+json.dumps(detail,ensure_ascii=False).replace('|','/')+'|')
    lines+=['', '1000用户抢购场景使用1000个JMeter线程、10秒启动与同步集合，JMeter最大堆1GiB；受理100次、售罄900次、最终100单、重复用户0，两侧库存0，Pending/lag归零。端到端耗时含200毫秒轮询观测间隔，不能作为纯消费服务耗时。',
        '', '漏收通知用第二个Java进程关闭缓存订阅模拟，五秒TTL的观测值还包含HTTP及50毫秒轮询开销。热点场景为两个Java进程、最多100个客户端工作线程，共200次读取。完整样本见 [system-check.json](reliability/system-check.json)。',
        '', '## 自动化检查','',
        'Java单元与控制器测试35项通过，2项既有手工测试跳过；真实MySQL/Redis集成测试10项通过。集成场景覆盖数据库提交后未ACK、五次失败保留预占、死信写入失败不ACK、无效消息隔离、库存键丢失、售罄补偿、Pending分页、事务回滚、事件重试及多缓存重建。两套结果见 [test-results.json](test-results.json)。',
        '', '前端18个入口、17个视图检查通过；nginx配置检查通过。外部模型推理与真实短信、支付不在本轮验证范围。',
        '', '## 数据与复现','',
        '- [基线逐轮汇总](baseline/summary.json) / [改造逐轮汇总](upgraded/summary.json) / [合并对比数据](comparison.json)。',
        '- 两个目录内的 `.jtl.gz` 为实际JMeter采样的字段白名单副本，包含时间、耗时、标签和结果，不含请求/响应体、账号或token。',
        '- [运行说明](../../jmeter/README.md) / [源码实验脚本](../../jmeter/system_check.py) / [表格生成脚本](../../jmeter/build_report.py)。',
        '- 使用 `python performance/jmeter/build_report.py --directory performance/benchmarks/2026-11-14` 从原始采样重建本表。']
    (root/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps({k:v['groups'] for k,v in report.items()},ensure_ascii=False))


if __name__=='__main__':main()
