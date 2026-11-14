package com.tanjian.service;

import com.tanjian.utils.RedisConstants;
import com.tanjian.observability.ProjectMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class StockRecovery {
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;
    private final ProjectMetrics metrics;
    private final Set<Long> requested=ConcurrentHashMap.newKeySet();
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> DRAINED=OrderStreamConsumer.script("seckill_drained.lua"), INITIALIZE=OrderStreamConsumer.script("seckill_init.lua");
    public StockRecovery(StringRedisTemplate redis,JdbcTemplate jdbc,RedissonClient redisson,ProjectMetrics metrics) {
        this.redis=redis; this.jdbc=jdbc; this.redisson=redisson; this.metrics=metrics;
    }
    public void startup() {
        for(Long id:jdbc.queryForList("SELECT voucher_id FROM tb_seckill_voucher",Long.class))
            if(!Boolean.TRUE.equals(redis.hasKey(RedisConstants.SECKILL_STOCK_KEY+id)) || Boolean.TRUE.equals(redis.hasKey(OrderStreamConsumer.PAUSED+id))) { request(id); recover(id); }
    }
    public boolean ready(Long id) {
        if(Boolean.TRUE.equals(redis.hasKey(RedisConstants.SECKILL_STOCK_KEY+id)) && !Boolean.TRUE.equals(redis.hasKey(OrderStreamConsumer.PAUSED+id))) return true;
        request(id); return false;
    }
    private void request(Long id) { redis.opsForValue().set(OrderStreamConsumer.PAUSED+id,"1"); requested.add(id); }
    public boolean recover(Long id) {
        var lock=redisson.getLock("lock:stock:init:"+id);
        boolean held=false;
        try {
            held=lock.tryLock(100,TimeUnit.MILLISECONDS); if(!held) return false;
            if(!Long.valueOf(1).equals(redis.execute(DRAINED,List.of(OrderStreamConsumer.STREAM),OrderStreamConsumer.GROUP))) return false;
            if(!Long.valueOf(0).equals(redis.opsForSet().size(OrderStreamConsumer.REVIEW+id))) return false;
            // With admission paused and no in-flight messages, this is an authoritative snapshot.
            var stock=jdbc.queryForList("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",id);
            if(stock.isEmpty()) return false;
            List<String> args=new ArrayList<>(); args.add(OrderStreamConsumer.GROUP); args.add(stock.get(0).get("stock").toString());
            jdbc.queryForList("SELECT user_id FROM tb_voucher_order WHERE voucher_id=?",Long.class,id).forEach(user->args.add(user.toString()));
            Long done=redis.execute(INITIALIZE,List.of(OrderStreamConsumer.STREAM,RedisConstants.SECKILL_STOCK_KEY+id,"seckill:order:"+id,OrderStreamConsumer.REVIEW+id,OrderStreamConsumer.PAUSED+id),args.toArray());
            if(Long.valueOf(1).equals(done)) { requested.remove(id); metrics.increment("orders.stock.recovered"); return true; }
        } catch(Exception e) { log.warn("Stock {} remains paused: {}",id,e.getClass().getSimpleName()); }
        finally { if(held&&lock.isHeldByCurrentThread()) lock.unlock(); }
        return false;
    }
    @Scheduled(fixedDelayString="${tanjian.orders.stock-recovery-ms:5000}")
    public void poll() { for(Long id:requested) recover(id); }
}
