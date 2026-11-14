package com.tanjian.utils;

import cn.hutool.json.JSONUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.tanjian.observability.ProjectMetrics;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

@Component
public class CacheClient {
    public static final String INVALIDATION_CHANNEL = "cache:invalidate";
    private final Cache<String, LocalValue> local = CacheBuilder.newBuilder().maximumSize(10_000).build();
    private final Object invalidationGuard = new Object();
    private long generation;
    private final Semaphore databasePermits = new Semaphore(16);
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ProjectMetrics metrics;
    private final LongSupplier clock;

    @Autowired
    public CacheClient(StringRedisTemplate redis, RedissonClient redisson, ProjectMetrics metrics) {
        this(redis, redisson, metrics, System::nanoTime);
    }
    public CacheClient(StringRedisTemplate redis, RedissonClient redisson, ProjectMetrics metrics, LongSupplier clock) {
        this.redis=redis; this.redisson=redisson; this.metrics=metrics; this.clock=clock;
    }
    public String get(String key, Long time, TimeUnit unit) {
        LocalValue value=local.getIfPresent(key);
        if (value!=null && clock.getAsLong()<value.expiresAt()) {
            metrics.increment("cache.l1.hit"); return value.json();
        }
        local.invalidate(key);
        long observed;
        synchronized(invalidationGuard) { observed=generation; }
        String json=redis.opsForValue().get(key);
        if(json!=null) {
            metrics.increment("cache.l2.hit");
            Long ttl=redis.getExpire(key,TimeUnit.MILLISECONDS);
            if(ttl!=null && ttl>0) putLocal(key,json,Math.min(unit.toMillis(time),ttl),observed);
        } else metrics.increment("cache.miss");
        return json;
    }
    public void set(String key,Object value,Long time,TimeUnit unit) {
        setJson(key,JSONUtil.toJsonStr(value),unit.toMillis(time));
    }
    public void delete(String key) {
        invalidateLocal(key);
        redis.delete(key);
        redis.convertAndSend(INVALIDATION_CHANNEL,key);
    }
    public void invalidateLocal(String key) {
        synchronized(invalidationGuard) { generation++; local.invalidate(key); }
        metrics.increment("cache.invalidations");
    }
    /** Subscription reset also invalidates outstanding fills. */
    public void clearLocal() {
        synchronized(invalidationGuard) { generation++; local.invalidateAll(); }
        metrics.increment("cache.subscription.resets");
    }
    private void putLocal(String key,String json,long ttlMillis,long observed) {
        synchronized(invalidationGuard) {
            if(observed==generation && ttlMillis>0)
                local.put(key,new LocalValue(json,clock.getAsLong()+TimeUnit.MILLISECONDS.toNanos(Math.min(5000,ttlMillis))));
            else metrics.increment("cache.stale_fill.discarded");
        }
    }
    private void setJson(String key,String json,long ttlMillis) {
        long observed;
        synchronized(invalidationGuard) { observed=generation; }
        redis.opsForValue().set(key,json,ttlMillis,TimeUnit.MILLISECONDS);
        putLocal(key,json,ttlMillis,observed);
    }
    public <R,ID> R queryWithPassThroughAndMutex(String prefix,String lockPrefix,ID id,Class<R> type,
            Function<ID,R> fallback,Long time,TimeUnit unit,Long nullTtl,TimeUnit nullUnit) {
        if(id==null) return null;
        String key=prefix+id;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(true) {
            String json;
            try { json=get(key,time,unit); }
            catch(org.springframework.data.redis.RedisConnectionFailureException e) {
                metrics.increment("cache.redis.unavailable"); return load(id,fallback);
            }
            if(json!=null) return json.isEmpty()?null:JSONUtil.toBean(json,type);
            RLock lock=redisson.getLock(lockPrefix+id);
            boolean held=false;
            try {
                held=lock.tryLock();
                if(!held) {
                    if(System.nanoTime()>=deadline) throw new IllegalStateException("店铺查询繁忙，请稍后重试");
                    Thread.sleep(25); continue;
                }
                json=get(key,time,unit);
                if(json!=null) return json.isEmpty()?null:JSONUtil.toBean(json,type);
                R result=load(id,fallback);
                if(result==null) setJson(key,"",nullUnit.toMillis(nullTtl));
                else {
                    long ttl=unit.toMillis(time);
                    ttl+=ThreadLocalRandom.current().nextLong(Math.max(1,ttl/5));
                    setJson(key,JSONUtil.toJsonStr(result),ttl);
                }
                return result;
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("查询被中断",e);
            } finally { if(held && lock.isHeldByCurrentThread()) lock.unlock(); }
        }
    }
    private <R,ID> R load(ID id,Function<ID,R> fallback) {
        boolean acquired=false;
        try {
            acquired=databasePermits.tryAcquire(100,TimeUnit.MILLISECONDS);
            if(!acquired) { metrics.increment("cache.db.rejected"); throw new IllegalStateException("店铺查询繁忙，请稍后重试"); }
            metrics.increment("cache.db.loads"); return fallback.apply(id);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("查询被中断",e);
        } finally { if(acquired) databasePermits.release(); }
    }
    private record LocalValue(String json,long expiresAt) {}
}
