"""Run the same 30s warm-up + 60s read matrix against a supplied project snapshot.

MYSQL_PWD/TANJIAN_MYSQL_PASSWORD is read by manage.py; credentials never enter results.
The load generator and application share a machine in the supplied local run.
"""
import argparse, csv, gzip, importlib.util, json, os, platform, subprocess, time
from pathlib import Path


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--project-dir', type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--label', required=True)
    p.add_argument('--jmeter', type=Path, required=True, help='ApacheJMeter.jar')
    p.add_argument('--java', default='java')
    p.add_argument('--redis-server', required=True)
    p.add_argument('--mysql', required=True)
    p.add_argument('--jar', type=Path)
    p.add_argument('--repeat', type=int, default=3)
    p.add_argument('--threads', default='20,50,100,200')
    args = p.parse_args()
    root = args.project_dir.resolve()
    base = root/'performance/jmeter'
    manage, analyze = module(base/'manage.py', 'bench_manage'), module(base/'analyze.py', 'bench_analyze')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    fixture = argparse.Namespace(users=200, stock=100, mysql=args.mysql, mysql_user='root',
        mysql_port=3306, redis_server=args.redis_server, redis_port=16379, api_port=18081,
        java=args.java, jar=str(args.jar) if args.jar else None)
    report = {'label': args.label, 'platform': platform.platform(), 'python': platform.python_version(),
        'warmup_seconds': 30, 'measured_seconds': 60, 'ramp_seconds': 1, 'think_ms': 100,
        'jvm': '-Xms128m -Xmx512m', 'model_calls': False, 'runs': []}
    try:
        manage.prepare(fixture)
        meta = manage.state()
        for threads in map(int, args.threads.split(',')):
            for repetition in range(1, args.repeat+1):
                stem = f'read-{threads}-r{repetition}'
                raw = Path(meta['dir'])/(stem+'.jtl')
                command = [args.java, '-Xms128m', '-Xmx512m', '-jar', str(args.jmeter), '-n',
                    '-t', str(base/'read-load.jmx'), '-q', str(base/'.runtime/current.properties'),
                    f'-Jthreads={threads}', '-Jramp_s=1', '-Jduration_s=90', '-Jthink_ms=100',
                    '-l', str(raw), '-j', str(Path(meta['dir'])/(stem+'.log'))]
                print(json.dumps({'starting': stem, 'label': args.label}), flush=True)
                with (Path(meta['dir'])/(stem+'-console.log')).open('w') as log:
                    subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True,
                        creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                result = analyze.summarize(raw, skip=30, expected_threads=threads)
                result['file'] = stem+'.jtl.gz'
                result.update(threads=threads, repetition=repetition)
                # Publish only a whitelist of synthetic, non-secret sample fields.
                with raw.open(encoding='utf-8-sig', newline='') as f, gzip.open(output/(stem+'.jtl.gz'), 'wt', encoding='utf-8', newline='') as g:
                    rows=csv.DictReader(f)
                    fields=[k for k in rows.fieldnames if k in {'timeStamp','elapsed','label','responseCode','success','threadName','bytes','sentBytes','grpThreads','allThreads','Latency','IdleTime','Connect','outcome','business_ms'}]
                    writer=csv.DictWriter(g, fields); writer.writeheader()
                    for row in rows: writer.writerow({k:row[k] for k in fields})
                report['runs'].append(result)
                (output/'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2),encoding='utf-8')
                print(json.dumps({'completed':stem,'passed':result['passed'],'errors':result['unexpected_failed_samples']}),flush=True)
    finally:
        if manage.CURRENT.exists():
            current=manage.state()
            if not current.get('stopped'): manage.stop(current, True)


if __name__ == '__main__':
    main()
