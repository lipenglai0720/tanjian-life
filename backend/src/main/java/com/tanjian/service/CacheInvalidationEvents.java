package com.tanjian.service;

import com.tanjian.observability.ProjectMetrics;
import com.tanjian.utils.CacheClient;
import com.tanjian.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/** Event and shop commit together. Redis invalidation is idempotent and retryable. */
@Slf4j
@Service
public class CacheInvalidationEvents {
    private final JdbcTemplate jdbc;
    private final CacheClient cache;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final TransactionTemplate transactions;
    private final ProjectMetrics metrics;
    @Value("${tanjian.cache.events.enabled:true}") private boolean enabled;
    public CacheInvalidationEvents(JdbcTemplate jdbc,CacheClient cache,StringRedisTemplate redis,
            RedissonClient redisson,TransactionTemplate transactions,ProjectMetrics metrics) {
        this.jdbc=jdbc; this.cache=cache; this.redis=redis; this.redisson=redisson;
        this.transactions=transactions; this.metrics=metrics;
    }
    public long record(Long shopId,Long oldTypeId) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cache event must belong to the shop transaction");
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO tb_cache_invalidation_event(shop_id,old_type_id) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,shopId); statement.setObject(2,oldTypeId); return statement;
        },keys);
        return keys.getKey().longValue();
    }
    /** Call after the shop TransactionTemplate returns, not inside an afterCommit connection. */
    public void deliver(long id) {
        transactions.executeWithoutResult(tx -> {
            var rows=jdbc.queryForList("SELECT * FROM tb_cache_invalidation_event WHERE id=? AND processed_at IS NULL FOR UPDATE SKIP LOCKED",id);
            if(rows.isEmpty()) return;
            var row=rows.get(0); long shopId=((Number)row.get("shop_id")).longValue();
            var lock=redisson.getLock(RedisConstants.LOCK_SHOP_KEY+shopId);
            boolean held=false;
            try {
                held=lock.tryLock(100,TimeUnit.MILLISECONDS);
                if(!held) throw new IllegalStateException("cache rebuild lock busy");
                var shops=jdbc.queryForList("SELECT type_id,x,y FROM tb_shop WHERE id=?",shopId);
                if(row.get("old_type_id")!=null)
                    redis.opsForGeo().remove(RedisConstants.SHOP_GEO_KEY+row.get("old_type_id"),Long.toString(shopId));
                if(!shops.isEmpty()) {
                    var shop=shops.get(0);
                    if(shop.get("type_id")!=null && shop.get("x")!=null && shop.get("y")!=null)
                        redis.opsForGeo().add(RedisConstants.SHOP_GEO_KEY+shop.get("type_id"),
                            new Point(((Number)shop.get("x")).doubleValue(),((Number)shop.get("y")).doubleValue()),Long.toString(shopId));
                }
                cache.delete(RedisConstants.CACHE_SHOP_KEY+shopId);
                jdbc.update("UPDATE tb_cache_invalidation_event SET processed_at=CURRENT_TIMESTAMP(3),last_error=NULL WHERE id=?",id);
                metrics.increment("cache.events.delivered");
                Object created=row.get("created_at");
                LocalDateTime time=created instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)created;
                metrics.duration("cache.event.delay",Math.max(0,Duration.between(time,LocalDateTime.now()).toMillis()));
            } catch(Exception failure) {
                if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts=((Number)row.get("attempts")).intValue()+1;
                int delay=Math.min(30,1<<Math.min(attempts-1,5));
                jdbc.update("UPDATE tb_cache_invalidation_event SET attempts=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),last_error=? WHERE id=?",
                    attempts,delay,failure.getClass().getSimpleName(),id);
                metrics.increment("cache.events.retry");
                log.warn("Cache invalidation pending: event={}, cause={}",id,failure.getClass().getSimpleName());
            } finally { if(held && lock.isHeldByCurrentThread()) lock.unlock(); }
        });
    }
    @Scheduled(fixedDelayString="${tanjian.cache.events.poll-ms:1000}")
    public void poll() {
        if(!enabled) return;
        try {
            for(Long id:jdbc.queryForList("SELECT id FROM tb_cache_invalidation_event WHERE processed_at IS NULL AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY id LIMIT 20",Long.class)) deliver(id);
            metrics.gauge("cache.events.pending",jdbc.queryForObject("SELECT COUNT(*) FROM tb_cache_invalidation_event WHERE processed_at IS NULL",Long.class));
        } catch(Exception e) { metrics.increment("cache.events.poll_failure"); log.warn("Cache event worker unavailable: {}",e.getClass().getSimpleName()); }
    }
}
