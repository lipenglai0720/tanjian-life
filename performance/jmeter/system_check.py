"""Isolated end-to-end checks: six JMeter flows, two JVMs, restart, TTL and replay.

Requires the same local dependencies as manage.py. No business database is modified.
Use MYSQL_PWD or TANJIAN_MYSQL_PASSWORD for the selected local test MySQL account.
"""
import argparse,csv,gzip,json,os,subprocess,sys,time,urllib.request
from pathlib import Path
import manage,analyze


def http(port,path,method='GET',body=None,token=None):
    headers={'Content-Type':'application/json'}
    if token:headers['authorization']=token
    data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
    request=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data,headers,method=method)
    with urllib.request.urlopen(request,timeout=15) as response:return json.load(response)


def wait_for(check,timeout=20):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            result=check()
            if result:return result
        except (OSError,ValueError,KeyError):pass
        time.sleep(.05)
    raise AssertionError('Condition did not converge within '+str(timeout)+' seconds')


def launch(meta,api,consumer=True,notifications=True):
    folder=Path(meta['dir']); java=manage.JAVA_DEFAULT
    env=os.environ.copy();env.pop('MYSQL_PWD',None)
    env['SPRING_DATASOURCE_PASSWORD']=manage.password();env['SPRING_DATASOURCE_USERNAME']='root'
    command=[java,'-Xms128m','-Xmx512m','-jar',str(manage.ROOT/'backend/target/tanjian-backend-0.0.1-SNAPSHOT.jar'),
        '--server.address=127.0.0.1','--server.port='+str(api),'--management.server.port='+str(api+1000),
        '--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+meta['database']+'?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true',
        '--spring.data.redis.host=127.0.0.1','--spring.data.redis.port='+str(meta['redis_port']),'--spring.data.redis.database=0','--spring.data.redis.password=',
        '--spring.data.redis.client-name=tanjian-node-'+str(api),'--tanjian.orders.consumer-enabled='+str(consumer).lower(),
        '--tanjian.cache.notifications.enabled='+str(notifications).lower(),'--logging.level.com.tanjian=WARN','--spring.main.banner-mode=off',
        '--tanjian.upload.path='+str(folder/'upload')]
    for model in ['chat-model','streaming-chat-model']:
        command.extend(['--langchain4j.open-ai.'+model+'.base-url=http://127.0.0.1:1/v1','--langchain4j.open-ai.'+model+'.api-key=local-perf-disabled',
            '--langchain4j.open-ai.'+model+'.log-requests=false','--langchain4j.open-ai.'+model+'.log-responses=false'])
    process=manage.start(command,manage.ROOT/'backend',folder/('node-'+str(api)+'.log'),env)
    try:
        wait_for(lambda:http(api,'/shop-type/list').get('success'),60)
        wait_for(lambda:http(api+1000,'/actuator/health').get('status')=='UP',30)
        return process.pid
    except Exception:
        manage.stop_pid(process.pid,meta['database']);raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jmeter',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--redis-server',default=manage.REDIS_DEFAULT);parser.add_argument('--mysql',default=manage.MYSQL_DEFAULT);parser.add_argument('--java',default=manage.JAVA_DEFAULT)
    args=parser.parse_args();manage.JAVA_DEFAULT=args.java
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    manage.STATE=manage.BASE/'.runtime'/('system-check-'+time.strftime('%Y%m%d_%H%M%S'));manage.CURRENT=manage.STATE/'current.json'
    meta=None;extra=[];results=[]
    def record(name,**fields):
        results.append({'case':name,'passed':True,**fields})
        (output/'system-check.json').write_text(json.dumps({'cases':results},ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(results[-1],ensure_ascii=False),flush=True)
    def stop(pid):manage.stop_pid(pid,meta['database'])
    def name(port):return http(port,'/shop/1')['data']['name']
    def metrics(port):return http(port+1000,'/actuator/tanjian')
    def run_plan(case,threads):
        raw=Path(meta['dir'])/(case+'.jtl')
        command=[manage.JAVA_DEFAULT,'-Xms256m','-Xmx1024m','-Xss512k','-jar',str(args.jmeter),'-n','-t',str(manage.BASE/(case+'-load.jmx')),
            '-q',str(manage.STATE/'current.properties'),f'-Jthreads={threads}','-Jramp_s=10','-Jloops=1','-Jattempts=1','-Jpoll_limit=150','-Jpoll_ms=200',
            '-l',str(raw),'-j',str(Path(meta['dir'])/(case+'-jmeter.log'))]
        with (Path(meta['dir'])/(case+'-console.log')).open('w') as log:
            subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True,creationflags=manage.NO_WINDOW)
        result=analyze.summarize(raw,expected_threads=threads);assert result['passed'],result
        with raw.open(encoding='utf-8-sig',newline='') as f,gzip.open(output/(case+'.jtl.gz'),'wt',encoding='utf-8',newline='') as g:
            rows=csv.DictReader(f);fields=[x for x in rows.fieldnames if x in {'timeStamp','elapsed','label','responseCode','success','threadName','outcome','business_ms'}]
            writer=csv.DictWriter(g,fields);writer.writeheader()
            for row in rows:writer.writerow({k:row[k] for k in fields})
        result['file']=case+'.jtl.gz'
        (output/(case+'-summary.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        return result
    try:
        fixture=argparse.Namespace(users=1000,stock=100,mysql=args.mysql,mysql_user='root',mysql_port=3306,redis_server=args.redis_server,
            redis_port=16379,api_port=18081,java=args.java,jar=None,no_backend=False)
        manage.prepare(fixture);meta=manage.state()
        print('Running 1000-user seckill burst',flush=True)
        result=run_plan('seckill',1000)
        manage.verify(argparse.Namespace(case='seckill',expected_count=100,wait=60),meta)
        record('1000 distinct users / 100 coupons',outcomes=result['submission_outcomes'],orders=100,duplicate_users=0,stock=0,
            observed_end_to_end_ms=result['successful_order_observed_end_to_end_ms'])
        run_plan('duplicate',100);manage.verify(argparse.Namespace(case='duplicate',expected_count=1,wait=60),meta)
        record('100 duplicate submissions',orders=1,stock=99)
        run_plan('claim',100);manage.verify(argparse.Namespace(case='claim',expected_count=100,wait=60),meta);record('ordinary claim',orders=100)
        run_plan('reservation',50);manage.verify(argparse.Namespace(case='reservation',expected_count=50,wait=60),meta);record('reservation create / cancel',cancelled=50,active=0)
        # Actual authenticated business operations, with synthetic manager binding.
        with (Path(meta['dir'])/'users.csv').open(encoding='utf-8') as f:user=next(csv.DictReader(f))
        token=http(18081,'/user/login','POST',{'phone':user['phone'],'code':user['code']})['data']
        manage.mysql(meta,'INSERT IGNORE INTO tb_shop_manager(user_id,shop_id) VALUES('+user['user_id']+',1);',meta['database'])
        # Leave an accepted order in a stopped JVM; a new process recovers it.
        for vid in [800000004,800000005]:
            manage.mysql(meta,f"INSERT INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES({vid},1,'restart IT','','fixture',100,200,1,1); INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES({vid},1,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY));",meta['database'])
        stop(meta['backend_pid']);meta['backend_pid']=None;manage.save(meta)
        paused=launch(meta,18081,consumer=False);extra.append(paused)
        accepted=http(18081,'/voucher-order/seckill/800000004','POST',token=token);assert accepted['success'],accepted
        order_id=str(accepted['data']);assert http(18081,'/voucher-order/result/'+order_id,token=token)['data']['status']=='PENDING'
        stop(paused);extra.remove(paused)
        second=launch(meta,18082);extra.append(second)
        wait_for(lambda:http(18082,'/voucher-order/result/'+order_id,token=token)['data']['status']=='CREATED')
        record('accepted order recovered by another JVM',orders=1)
        first=launch(meta,18081);extra.append(first)
        old=name(18082);name(18081)
        new='system-check-broadcast'
        start=time.monotonic();assert http(18081,'/shop','PUT',{'id':1,'name':new},token)['success']
        wait_for(lambda:name(18082)==new)
        record('two JVM broadcast invalidation',visible_ms=round((time.monotonic()-start)*1000,2))
        # Disable only the second node's cache notification subscription; its L1 must expire.
        stop(second);extra.remove(second);second=launch(meta,18082,notifications=False);extra.append(second)
        assert name(18082)==new
        new='system-check-missed-broadcast';start=time.monotonic()
        assert http(18081,'/shop','PUT',{'id':1,'name':new},token)['success']
        wait_for(lambda:name(18082)==new,7)
        visible=(time.monotonic()-start)*1000;assert visible<=6500,visible
        record('missed notification / L1 TTL fallback',visible_ms=round(visible,2),l1_max_ms=5000)
        stop(second);extra.remove(second);second=launch(meta,18082);extra.append(second)
        name(18082)
        from concurrent.futures import ThreadPoolExecutor
        redis=manage.Redis(meta['redis_port'])
        try:
            # Identify the dedicated cache subscriber, leaving other processes untouched.
            clients=redis.call('CLIENT','LIST','TYPE','PUBSUB')
            entries=[dict(v.split('=',1) for v in line.split() if '=' in v) for line in clients.splitlines()]
            target=[entry['id'] for entry in entries if entry.get('name')=='tanjian-node-18082' and entry.get('sub')=='1']
            assert target,'Named second-node subscriber not found'
            before=metrics(18082).get('cache.subscription.resets',0)
            for client in target:redis.call('CLIENT','KILL','ID',client)
            wait_for(lambda:metrics(18082).get('cache.subscription.resets',0)>before,10)
            record('subscriber reconnect clears L1',reconnected=True)
            redis.call('DEL','cache:shop:1');redis.call('PUBLISH','cache:invalidate','cache:shop:1');time.sleep(.2)
            baseline=sum(metrics(port).get('cache.db.loads',0) for port in [18081,18082])
            with ThreadPoolExecutor(max_workers=100) as pool:
                pages=list(pool.map(lambda i:http(18081+i%2,'/shop/1'),range(200)))
            assert all(page['success'] and page['data']['name']==new for page in pages)
            loads=sum(metrics(port).get('cache.db.loads',0) for port in [18081,18082])-baseline
            assert loads==1,loads
            record('two JVM / 200 cold hot-key reads',database_loads=loads)
        finally:redis.close()
        # A persisted event is consumed after both old workers have exited.
        for pid in list(extra):stop(pid);extra.remove(pid)
        manage.mysql(meta,"START TRANSACTION; UPDATE tb_shop SET name='system-check-restart-event' WHERE id=1; INSERT INTO tb_cache_invalidation_event(shop_id,old_type_id) VALUES(1,1); COMMIT;",meta['database'])
        first=launch(meta,18081);extra.append(first)
        wait_for(lambda:int(manage.mysql(meta,'SELECT COUNT(*) FROM tb_cache_invalidation_event WHERE processed_at IS NULL;',meta['database']))==0)
        assert name(18081)=='system-check-restart-event';record('persisted invalidation event recovered after JVM restart')
        # Exercise the shipped dry-run and replay command on a held reservation fixture.
        stop(first);extra.remove(first);first=launch(meta,18081,consumer=False);extra.append(first)
        accepted=http(18081,'/voucher-order/seckill/800000005','POST',token=token);assert accepted['success'],accepted
        order_id=str(accepted['data']);redis=manage.Redis(meta['redis_port'])
        try:
            batch=redis.call('XREADGROUP','GROUP','g1','interrupted-replay-check','COUNT',1,'STREAMS','stream.orders','>')
            source,fields=batch[0][1][0];payload=dict(zip(fields[::2],fields[1::2]));vid='800000005'
            keys=['stream.orders','stream.orders.dlq','seckill:result:'+order_id,'seckill:review:'+vid,'seckill:attempt:'+source]
            script=(manage.ROOT/'backend/src/main/resources/seckill_quarantine.lua').read_text(encoding='utf-8')
            assert redis.call('EVAL',script,len(keys),*keys,source,'g1',user['user_id'],vid,order_id,'5','controlled replay fixture',json.dumps(payload),'1')==1
            length=redis.call('XLEN','stream.orders')
            command=[sys.executable,str(manage.BASE/'recover_orders.py'),'--fixture',str(manage.CURRENT),'--order-id',order_id]
            preview=json.loads(subprocess.check_output(command,env=os.environ,creationflags=manage.NO_WINDOW))
            assert not preview['applied'] and redis.call('XLEN','stream.orders')==length
            applied=json.loads(subprocess.check_output(command+['--apply','--replay'],env=os.environ,creationflags=manage.NO_WINDOW))
            assert applied['applied'] and redis.call('XLEN','stream.orders')==length+1
            replay=(manage.ROOT/'backend/src/main/resources/seckill_replay.lua').read_text(encoding='utf-8')
            replaykeys=['stream.orders','seckill:result:'+order_id,'seckill:order:'+vid,'seckill:review:'+vid]
            assert redis.call('EVAL',replay,4,*replaykeys,user['user_id'],vid,order_id)==0
        finally:redis.close()
        stop(first);extra.remove(first);first=launch(meta,18081);extra.append(first)
        wait_for(lambda:http(18081,'/voucher-order/result/'+order_id,token=token)['data']['status']=='CREATED')
        count=int(manage.mysql(meta,'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=800000005;',meta['database']))
        assert count==1;record('dry-run / safe replay / repeated replay refusal',orders=count)
        # Public catalogue is anonymous; personal MCP tools remain authenticated (unit tests cover context).
        record('management endpoint',binding='127.0.0.1',ports=[19081,19082])
    finally:
        if meta:
            for pid in extra:stop(pid)
            manage.stop(meta,True)


if __name__=='__main__':main()
