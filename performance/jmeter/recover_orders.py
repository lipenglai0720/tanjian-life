"""Preview/reconcile/replay REVIEW_REQUIRED orders in an explicitly isolated fixture.

Default is read-only. --apply resolves confirmed committed orders; --apply --replay
requeues unresolved orders with their original ID and reservation. No blind refund.
"""
import argparse,json,re
from pathlib import Path
import manage


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fixture',type=Path,default=manage.CURRENT)
    p.add_argument('--order-id',type=int,required=True)
    p.add_argument('--apply',action='store_true')
    p.add_argument('--replay',action='store_true')
    args=p.parse_args()
    meta=json.loads(args.fixture.read_text(encoding='utf-8'))
    if not re.fullmatch(r'tanjian_perf_[a-z0-9_]+',meta['database']) or int(meta['redis_port'])<10000:
        raise ValueError('Only an explicitly isolated tanjian_perf_* fixture is supported')
    if args.order_id<=0:raise ValueError('order-id must be positive')
    redis=manage.Redis(meta['redis_port'])
    try:
        key='seckill:result:'+str(args.order_id)
        fields=redis.call('HGETALL',key); result=dict(zip(fields[::2],fields[1::2]))
        if result.get('status')!='REVIEW_REQUIRED':raise ValueError('Order is not REVIEW_REQUIRED')
        user,vid=int(result['userId']),int(result['voucherId'])
        if user<=0 or vid<=0:raise ValueError('Invalid reservation identity')
        exact=manage.mysql(meta,f'SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE id={args.order_id};',meta['database']).strip()
        existing=manage.mysql(meta,f'SELECT id FROM tb_voucher_order WHERE user_id={user} AND voucher_id={vid};',meta['database']).strip()
        if exact:
            values=list(map(int,exact.split('\t')))
            if values!=[args.order_id,user,vid]:raise ValueError('Database order identity mismatch')
            action='CONFIRM_CREATED'; outcome='CREATED'; existing=str(args.order_id)
        elif existing:
            action='CONFIRM_DUPLICATE'; outcome='DUPLICATE'
        else: action='REPLAY_WITH_HELD_RESERVATION' if args.replay else 'KEEP_REVIEW_REQUIRED'
        changed=False
        resources=manage.ROOT/'backend/src/main/resources'
        if args.apply and action.startswith('CONFIRM_'):
            script=(resources/'seckill_complete.lua').read_text(encoding='utf-8')
            keys=['seckill:stock:'+str(vid),'seckill:order:'+str(vid),key,'stream.orders','seckill:review:'+str(vid),'seckill:paused:'+str(vid),'seckill:attempt:'+result.get('sourceId','')]
            code=redis.call('EVAL',script,len(keys),*keys,str(user),str(vid),str(args.order_id),outcome,'订单已核对',existing,'g1','')
            if code!=1:raise RuntimeError('Atomic completion rejected: '+str(code))
            changed=True
        elif args.apply and args.replay and action=='REPLAY_WITH_HELD_RESERVATION':
            script=(resources/'seckill_replay.lua').read_text(encoding='utf-8')
            keys=['stream.orders',key,'seckill:order:'+str(vid),'seckill:review:'+str(vid)]
            code=redis.call('EVAL',script,len(keys),*keys,str(user),str(vid),str(args.order_id))
            if code!=1:raise RuntimeError('Reservation replay rejected: '+str(code))
            changed=True
        print(json.dumps({'order_id':args.order_id,'action':action,'applied':changed,'reservation_refunded':action=='CONFIRM_DUPLICATE' and changed},ensure_ascii=False))
    finally:redis.close()


if __name__=='__main__':main()
