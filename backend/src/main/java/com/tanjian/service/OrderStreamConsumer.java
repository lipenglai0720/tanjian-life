package com.tanjian.service;

import cn.hutool.json.JSONUtil;
import com.tanjian.entity.VoucherOrder;
import com.tanjian.observability.ProjectMetrics;
import com.tanjian.utils.RedisConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class OrderStreamConsumer {
    public static final String STREAM="stream.orders", GROUP="g1", RESULT="seckill:result:";
    public static final String REVIEW="seckill:review:", PAUSED="seckill:paused:";
    private final StringRedisTemplate redis;
    private final VoucherOrderProcessor processor;
    private final ProjectMetrics metrics;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final String consumer="order-"+UUID.randomUUID();
    private String recoveryCursor;
    @Value("${tanjian.orders.max-attempts:5}") private int maxAttempts;
    @Value("${tanjian.orders.retry-ms:30000}") private long retryMs;
    @Value("${tanjian.orders.recovery-poll-ms:5000}") private long recoveryPollMs;
    @Value("${tanjian.orders.read-block-ms:2000}") private long readBlockMs;
    @Value("${tanjian.orders.consumer-enabled:true}") private boolean enabled;
    private static final DefaultRedisScript<Long> COMPLETE=script("seckill_complete.lua"), ATTEMPT=script("seckill_attempt.lua"), QUARANTINE=script("seckill_quarantine.lua");
    public OrderStreamConsumer(StringRedisTemplate redis,VoucherOrderProcessor processor,ProjectMetrics metrics) {
        this.redis=redis; this.processor=processor; this.metrics=metrics;
    }
    public static DefaultRedisScript<Long> script(String file) {
        var result=new DefaultRedisScript<Long>(); result.setLocation(new ClassPathResource(file)); result.setResultType(Long.class); return result;
    }
    public void start() {
        if(!running.compareAndSet(false,true)) return;
        try { redis.opsForStream().createGroup(STREAM,ReadOffset.from("0"),GROUP); }
        catch(Exception e) {
            if(!hasCause(e,"BUSYGROUP")) {
                redis.opsForStream().add(STREAM,Map.of("type","bootstrap"));
                try { redis.opsForStream().createGroup(STREAM,ReadOffset.from("0"),GROUP); }
                catch(Exception second) { if(!hasCause(second,"BUSYGROUP")) throw second; }
            }
        }
        if(enabled) executor.submit(this::consume);
    }
    private boolean hasCause(Throwable e,String text) {
        for(Throwable t=e;t!=null;t=t.getCause()) { if(t.getMessage()!=null&&t.getMessage().contains(text)) return true; if(t==t.getCause()) break; }
        return false;
    }
    private void consume() {
        long next=0;
        while(running.get()&&!Thread.currentThread().isInterrupted()) {
            try {
                if(System.nanoTime()>=next) {
                    recoverPending(); next=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(recoveryPollMs);
                }
                var records=redis.opsForStream().read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(10).block(Duration.ofMillis(readBlockMs)),StreamOffset.create(STREAM,ReadOffset.lastConsumed()));
                if(records!=null) for(var record:records) handle(record);
            } catch(Exception failure) {
                if(!running.get()||Thread.currentThread().isInterrupted()) break;
                metrics.increment("orders.consumer.unavailable"); log.warn("Order consumer will retry: {}",failure.getClass().getSimpleName());
                try { Thread.sleep(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
    /** Preserve the cursor across polls; poison messages cannot monopolize the first page. */
    public void recoverPending() {
        for(int page=0;page<10;page++) {
            var from=recoveryCursor==null?Range.<String>unbounded():Range.of(Range.Bound.exclusive(recoveryCursor),Range.Bound.<String>unbounded());
            var messages=redis.opsForStream().pending(STREAM,GROUP,from,50);
            if(messages==null||messages.isEmpty()) { recoveryCursor=null; break; }
            for(var message:messages) {
                recoveryCursor=message.getId().toString();
                if(message.getElapsedTimeSinceLastDelivery().toMillis()<retryMs) continue;
                var claimed=redis.opsForStream().claim(STREAM,GROUP,consumer,Duration.ofMillis(retryMs),message.getId());
                if(claimed!=null) for(var record:claimed) handle(record);
            }
            if(messages.size()<50) { recoveryCursor=null; break; }
        }
        var summary=redis.opsForStream().pending(STREAM,GROUP);
        if(summary!=null) metrics.gauge("orders.pending",summary.getTotalPendingMessages());
    }
    public void handle(MapRecord<String,Object,Object> record) {
        if("bootstrap".equals(record.getValue().get("type"))) { redis.opsForStream().acknowledge(STREAM,GROUP,record.getId()); return; }
        VoucherOrder order;
        try { order=new VoucherOrder().setId(positive(record,"orderId")).setUserId(positive(record,"userId")).setVoucherId(positive(record,"voucherId")); }
        catch(IllegalArgumentException invalid) { quarantine(record,null,1,"INVALID_PAYLOAD"); return; }
        int attempts=0;
        try {
            String state=(String)redis.opsForHash().get(RESULT+order.getId(),"status");
            if("CREATED".equals(state)||"FAILED".equals(state)) { redis.opsForStream().acknowledge(STREAM,GROUP,record.getId()); return; }
            Long count=redis.execute(ATTEMPT,List.of(attemptKey(record.getId().toString())));
            if(count==null||count<1) throw new IllegalStateException("Attempt counter unavailable");
            attempts=Math.toIntExact(count);
            if(attempts>maxAttempts) { quarantine(record,order,maxAttempts,"RETRY_EXHAUSTED"); return; }
            if(attempts>1) metrics.increment("orders.retry");
            var outcome=processor.create(order,true);
            Long completed=redis.execute(COMPLETE,List.of(RedisConstants.SECKILL_STOCK_KEY+order.getVoucherId(),"seckill:order:"+order.getVoucherId(),RESULT+order.getId(),STREAM,REVIEW+order.getVoucherId(),PAUSED+order.getVoucherId(),attemptKey(record.getId().toString())),
                order.getUserId().toString(),order.getVoucherId().toString(),order.getId().toString(),outcome.code(),outcome.message(),outcome.existingOrderId(),GROUP,record.getId().toString());
            if(completed==null||completed<0) throw new IllegalStateException("Result completion unavailable");
            metrics.increment("orders.outcome."+outcome.code());
        } catch(Exception failure) {
            metrics.increment("orders.processing.failure");
            if(attempts>=maxAttempts) quarantine(record,order,maxAttempts,failure.getClass().getSimpleName());
            else log.warn("Order {} remains pending after attempt {}: {}",order.getId(),attempts,failure.getClass().getSimpleName());
        }
    }
    private void quarantine(MapRecord<String,Object,Object> record,VoucherOrder order,int attempts,String reason) {
        boolean valid=order!=null;
        String id=valid?order.getId().toString():"invalid-"+record.getId();
        Long done=redis.execute(QUARANTINE,List.of(STREAM,STREAM+".dlq",RESULT+id,REVIEW+(valid?order.getVoucherId():"invalid"),attemptKey(record.getId().toString())),
            record.getId().toString(),GROUP,valid?order.getUserId().toString():"",valid?order.getVoucherId().toString():"",id,Integer.toString(attempts),reason,JSONUtil.toJsonStr(record.getValue()),valid?"1":"0");
        if(done==null||done<0) throw new IllegalStateException("Dead-letter write failed; message not acknowledged");
        metrics.increment("orders.quarantined");
        metrics.gauge("orders.deadletters",Objects.requireNonNullElse(redis.opsForStream().size(STREAM+".dlq"),0L));
    }
    public static String attemptKey(String sourceId) { return "seckill:attempt:"+sourceId; }
    private Long positive(MapRecord<String,Object,Object> record,String key) {
        try { long value=Long.parseLong(Objects.toString(record.getValue().get(key),"")); if(value>0) return value; }
        catch(NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid order field");
    }
    @PreDestroy public void stop() {
        running.set(false); executor.shutdownNow();
        try { executor.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
