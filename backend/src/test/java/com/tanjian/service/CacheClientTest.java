package com.tanjian.service;

import com.tanjian.config.CacheInvalidationConfig;
import com.tanjian.entity.Shop;
import com.tanjian.observability.ProjectMetrics;
import com.tanjian.utils.CacheClient;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.springframework.data.redis.core.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CacheClientTest {
    CacheClient cache; StringRedisTemplate redis; ValueOperations<String,String> values;
    RedissonClient redisson; AtomicLong nanos; ProjectMetrics metrics;
    @BeforeEach @SuppressWarnings("unchecked") void init() {
        redis=mock(StringRedisTemplate.class); values=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        redisson=mock(RedissonClient.class); var lock=mock(RLock.class); when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true); when(lock.isHeldByCurrentThread()).thenReturn(true);
        nanos=new AtomicLong(); metrics=new ProjectMetrics(); cache=new CacheClient(redis,redisson,metrics,nanos::get);
        when(redis.getExpire(anyString(),eq(TimeUnit.MILLISECONDS))).thenReturn(60_000L);
    }
    Shop query(Long id,Function<Long,Shop> fallback) { return cache.queryWithPassThroughAndMutex("cache:shop:","lock:shop:",id,Shop.class,fallback,30L,TimeUnit.MINUTES,30L,TimeUnit.SECONDS); }
    @Test void aNewShopIdIsNotRejectedByAStaleProcessLocalMembershipFilter() {
        assertEquals("new",query(999L,id->new Shop().setId(id).setName("new")).getName());
    }
    @Test void doubleCheckAfterLockAvoidsAnUnnecessaryDatabaseQuery() {
        when(values.get("cache:shop:1")).thenReturn(null,"{\"id\":1,\"name\":\"shop\"}");
        assertEquals("shop",query(1L,id->{throw new AssertionError("database must not run");}).getName());
    }
    @Test void localEntryExpiresAfterFiveSecondsEvenWhenRedisLivesLonger() {
        when(values.get("key")).thenReturn("old","new");
        assertEquals("old",cache.get("key",30L,TimeUnit.MINUTES)); nanos.set(TimeUnit.SECONDS.toNanos(4));
        assertEquals("old",cache.get("key",30L,TimeUnit.MINUTES)); nanos.set(TimeUnit.SECONDS.toNanos(5));
        assertEquals("new",cache.get("key",30L,TimeUnit.MINUTES)); verify(values,times(2)).get("key");
    }
    @Test void localCacheNeverOutlivesRedisExpiry() {
        when(values.get("key")).thenReturn("old","new"); when(redis.getExpire("key",TimeUnit.MILLISECONDS)).thenReturn(1L);
        assertEquals("old",cache.get("key",30L,TimeUnit.MINUTES)); nanos.set(TimeUnit.MILLISECONDS.toNanos(2));
        assertEquals("new",cache.get("key",30L,TimeUnit.MINUTES));
    }
    @Test void invalidationDuringRedisReadCannotResurrectStaleLocalValue() {
        when(values.get("key")).thenAnswer(i->{cache.invalidateLocal("key"); return "old";}).thenReturn("new");
        assertEquals("old",cache.get("key",30L,TimeUnit.MINUTES));
        assertEquals("new",cache.get("key",30L,TimeUnit.MINUTES));
        assertEquals(1L,metrics.snapshot().get("cache.stale_fill.discarded"));
    }
    @Test void subscriptionReconnectPurgesLocalValues() {
        when(values.get("key")).thenReturn("old","new"); assertEquals("old",cache.get("key",30L,TimeUnit.MINUTES));
        new CacheInvalidationConfig.Invalidations(cache).onChannelSubscribed(new byte[0],1);
        assertEquals("new",cache.get("key",30L,TimeUnit.MINUTES));
    }
    @Test void nullResultsAreCachedAndDoNotRepeatedlyLoadTheDatabase() {
        var count=new AtomicInteger();
        assertNull(query(999L,id->{count.incrementAndGet();return null;}));
        assertNull(query(999L,id->{count.incrementAndGet();return null;}));
        assertEquals(1,count.get());
        verify(values).set("cache:shop:999","",30_000L,TimeUnit.MILLISECONDS);
    }
    @Test void simultaneousDatabaseFallbacksAreBounded() throws Exception {
        var entered=new CountDownLatch(16); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(16);
        try {
            var tasks=new java.util.ArrayList<Future<Shop>>();
            for(long id=1;id<=16;id++) { final long key=id; tasks.add(pool.submit(()->query(key,k->{entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new Shop().setId(k);}))); }
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->query(17L,k->{throw new AssertionError("overload must not reach DB");}));
            release.countDown(); for(var task:tasks) assertNotNull(task.get(5,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
