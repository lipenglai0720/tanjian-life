package com.tanjian.service;

import com.tanjian.entity.*;
import com.tanjian.dto.UserDTO;
import com.tanjian.utils.*;
import com.tanjian.observability.ProjectMetrics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.redisson.api.RedissonClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Destructive fixture reset is restricted to a named isolated database and high Redis port. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "management.server.port=0","tanjian.orders.consumer-enabled=false","tanjian.cache.events.enabled=false",
    "tanjian.orders.retry-ms=100","tanjian.orders.stock-recovery-ms=3600000","logging.level.com.tanjian=WARN",
    "langchain4j.open-ai.chat-model.api-key=integration-disabled","langchain4j.open-ai.chat-model.base-url=http://127.0.0.1:1/v1",
    "langchain4j.open-ai.streaming-chat-model.api-key=integration-disabled","langchain4j.open-ai.streaming-chat-model.base-url=http://127.0.0.1:1/v1"})
@EnabledIfEnvironmentVariable(named="TANJIAN_RUN_INTEGRATION_TESTS",matches="true")
class ReliabilityIT {
    static final long VID=800000001L;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        String url=System.getenv("TANJIAN_TEST_DB_URL");
        int port=Integer.parseInt(System.getenv("TANJIAN_TEST_REDIS_PORT"));
        if(url==null || !url.matches(".*[/]tanjian_perf_[a-z0-9_]+(?:[?].*)?$") || port<10000)
            throw new IllegalStateException("Integration tests require an isolated tanjian_perf_* database and high Redis port");
        p.add("spring.datasource.url",()->url);
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TANJIAN_TEST_DB_USER","root"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TANJIAN_TEST_DB_PASSWORD",""));
        p.add("spring.data.redis.host",()->"127.0.0.1"); p.add("spring.data.redis.port",()->port);
        p.add("spring.data.redis.password",()->""); p.add("spring.data.redis.database",()->0);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired IVoucherOrderService orders;
    @Autowired IShopService shops;
    @Autowired OrderStreamConsumer consumer;
    @Autowired StockRecovery recovery;
    @Autowired CacheInvalidationEvents events;
    @Autowired TransactionTemplate transactions;
    @Autowired RedissonClient redisson;
    @Autowired ProjectMetrics metrics;
    @MockitoSpyBean VoucherOrderProcessor processor;
    @MockitoSpyBean CacheClient cache;

    @BeforeEach void fixture() {
        UserHolder.removeUser(); cache.clearLocal();
        try(var connection=redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        redis.opsForStream().add(OrderStreamConsumer.STREAM,Map.of("type","bootstrap"));
        redis.opsForStream().createGroup(OrderStreamConsumer.STREAM,ReadOffset.from("$"),OrderStreamConsumer.GROUP);
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?",VID);
        jdbc.update("DELETE FROM tb_cache_invalidation_event");
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES(?,1,'IT coupon','','test',100,200,1,1) ON DUPLICATE KEY UPDATE status=1",VID);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,100,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE stock=100,begin_time=DATE_SUB(NOW(),INTERVAL 1 DAY),end_time=DATE_ADD(NOW(),INTERVAL 1 DAY)",VID);
        redis.opsForValue().set("seckill:stock:"+VID,"100");
    }
    @AfterEach void cleanup() { UserHolder.removeUser(); }
    String admit(long user) {
        UserDTO identity=new UserDTO(); identity.setId(user); UserHolder.saveUser(identity);
        try { var result=orders.seckillVoucher(VID); assertTrue(result.getSuccess(),result.getErrorMsg()); return result.getData().toString(); }
        finally { UserHolder.removeUser(); }
    }
    List<MapRecord<String,Object,Object>> read() {
        var rows=redis.opsForStream().read(Consumer.from(OrderStreamConsumer.GROUP,"interrupted-worker"),StreamReadOptions.empty().count(200),StreamOffset.create(OrderStreamConsumer.STREAM,ReadOffset.lastConsumed()));
        return rows==null?List.of():rows;
    }
    long pending() { return redis.opsForStream().pending(OrderStreamConsumer.STREAM,OrderStreamConsumer.GROUP).getTotalPendingMessages(); }
    long countOrders() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Long.class,VID); }
    String status(String id) { return (String)redis.opsForHash().get(OrderStreamConsumer.RESULT+id,"status"); }

    @Test void duplicateDeliveryAfterDatabaseCommitDoesNotDeductAgain() {
        String id=admit(900000001); var record=read().get(0);
        var order=new VoucherOrder().setId(Long.parseLong(id)).setUserId(900000001L).setVoucherId(VID);
        processor.create(order,true); // Process dies here: committed, no result and no ACK.
        assertEquals("PENDING",status(id)); assertEquals(1,pending());
        consumer.handle(record); consumer.handle(record);
        assertEquals(1,countOrders()); assertEquals(99,jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,VID));
        assertEquals("99",redis.opsForValue().get("seckill:stock:"+VID));
        assertEquals("CREATED",status(id)); assertEquals(0,pending());
    }
    @Test void exhaustedRetriesPreserveReservationAndWriteOneAuditableDeadLetter() {
        String id=admit(900000001); var record=read().get(0);
        doThrow(new DataAccessResourceFailureException("injected DB outage")).when(processor).create(any(),eq(true));
        for(int i=0;i<6;i++) consumer.handle(record);
        assertEquals("REVIEW_REQUIRED",status(id)); assertEquals(0,countOrders()); assertEquals(0,pending());
        assertEquals("99",redis.opsForValue().get("seckill:stock:"+VID));
        assertTrue(redis.opsForSet().isMember(OrderStreamConsumer.REVIEW+VID,id));
        assertEquals(1L,redis.opsForStream().size(OrderStreamConsumer.STREAM+".dlq"));
        var dlq=redis.opsForStream().range(OrderStreamConsumer.STREAM+".dlq",org.springframework.data.domain.Range.unbounded()).get(0).getValue();
        assertEquals("5",dlq.get("attempts")); assertTrue(dlq.get("payload").toString().contains(id));
        verify(processor,times(5)).create(any(),eq(true));
    }
    @Test void deadLetterWriteFailureNeverAcknowledgesTheOriginalMessage() {
        admit(900000001); var record=read().get(0);
        doThrow(new DataAccessResourceFailureException("injected DB outage")).when(processor).create(any(),eq(true));
        redis.opsForValue().set(OrderStreamConsumer.STREAM+".dlq","wrong type");
        for(int i=0;i<4;i++) consumer.handle(record);
        assertThrows(IllegalStateException.class,()->consumer.handle(record)); assertEquals(1,pending());
        redis.delete(OrderStreamConsumer.STREAM+".dlq"); consumer.handle(record);
        assertEquals(0,pending()); verify(processor,times(5)).create(any(),eq(true));
    }
    @Test void malformedPayloadIsIsolatedWithoutTouchingInventory() {
        redis.opsForStream().add(OrderStreamConsumer.STREAM,Map.of("userId","invalid"));
        consumer.handle(read().get(0)); assertEquals(0,pending()); assertEquals(0,countOrders());
        assertEquals("100",redis.opsForValue().get("seckill:stock:"+VID));
    }
    @Test void missingStockIsNotRebuiltWhileAcceptedMessagesRemainInFlight() {
        String id=admit(900000001); var record=read().get(0); redis.delete("seckill:stock:"+VID);
        assertFalse(recovery.ready(VID)); assertFalse(recovery.recover(VID)); assertFalse(redis.hasKey("seckill:stock:"+VID));
        consumer.handle(record); assertEquals("CREATED",status(id));
        assertTrue(recovery.recover(VID)); assertEquals("99",redis.opsForValue().get("seckill:stock:"+VID));
        assertEquals(1L,redis.opsForSet().size("seckill:order:"+VID));
    }
    @Test void confirmedDatabaseSoldOutIsCompensatedOnceThenReconciled() {
        String id=admit(900000001); var record=read().get(0);
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?",VID);
        consumer.handle(record); consumer.handle(record);
        assertEquals("FAILED",status(id)); assertEquals("100",redis.opsForValue().get("seckill:stock:"+VID));
        assertFalse(recovery.ready(VID)); assertTrue(recovery.recover(VID));
        assertEquals("0",redis.opsForValue().get("seckill:stock:"+VID)); assertEquals(0,countOrders());
    }
    @Test void recoveryScansPastYoungMessagesInTheFirstPage() throws Exception {
        for(int i=0;i<100;i++) redis.opsForStream().add(OrderStreamConsumer.STREAM,Map.of("invalid",Integer.toString(i)));
        var records=read(); Thread.sleep(180);
        for(int i=0;i<50;i++) redis.opsForStream().claim(OrderStreamConsumer.STREAM,OrderStreamConsumer.GROUP,"recent",Duration.ZERO,records.get(i).getId());
        consumer.recoverPending(); assertTrue(pending()<=50,"eligible second-page messages must be recovered");
    }
    @Test void shopAndInvalidationEventRollBackTogether() {
        String old=jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class);
        assertThrows(IllegalStateException.class,()->transactions.executeWithoutResult(tx->{
            jdbc.update("UPDATE tb_shop SET name='rollback IT' WHERE id=1"); events.record(1L,1L); throw new IllegalStateException("rollback");
        }));
        assertEquals(old,jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM tb_cache_invalidation_event",Long.class));
    }
    @Test void committedShopUpdateSurvivesInvalidationFailureAndWorkerRestart() {
        String old=jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class);
        doThrow(new IllegalStateException("injected publisher outage")).doCallRealMethod().when(cache).delete("cache:shop:1");
        assertTrue(shops.updateShop(new Shop().setId(1L).setName("committed IT")).getSuccess());
        assertEquals("committed IT",jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class));
        long id=jdbc.queryForObject("SELECT id FROM tb_cache_invalidation_event LIMIT 1",Long.class);
        assertNull(jdbc.queryForObject("SELECT processed_at FROM tb_cache_invalidation_event WHERE id=?",java.sql.Timestamp.class,id));
        new CacheInvalidationEvents(jdbc,cache,redis,redisson,transactions,metrics).deliver(id);
        assertNotNull(jdbc.queryForObject("SELECT processed_at FROM tb_cache_invalidation_event WHERE id=?",java.sql.Timestamp.class,id));
        shops.updateShop(new Shop().setId(1L).setName(old));
    }
    @Test void twoIndependentCachesShareOneRebuildAndInvalidation() throws Exception {
        CacheClient other=new CacheClient(redis,redisson,new ProjectMetrics());
        var pool=Executors.newFixedThreadPool(20); var gate=new CountDownLatch(1); var loads=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var tasks=new ArrayList<Future<Shop>>();
            for(int i=0;i<20;i++) { CacheClient c=i%2==0?cache:other; tasks.add(pool.submit(()->{gate.await();return c.queryWithPassThroughAndMutex("cache:shop:","lock:shop:",1L,Shop.class,k->{loads.incrementAndGet();return shops.getById(k);},30L,TimeUnit.MINUTES,30L,TimeUnit.SECONDS);})); }
            gate.countDown(); for(var task:tasks) assertEquals(1L,task.get(10,TimeUnit.SECONDS).getId());
            assertEquals(1,loads.get());
        } finally { pool.shutdownNow(); }
        other.invalidateLocal("cache:shop:1"); cache.delete("cache:shop:1");
        long newId=987654321L;
        assertNull(other.queryWithPassThroughAndMutex("cache:shop:","lock:shop:",newId,Shop.class,shops::getById,30L,TimeUnit.MINUTES,30L,TimeUnit.SECONDS));
        assertTrue(shops.save(new Shop().setId(newId).setName("new ID IT").setTypeId(1L).setImages("").setArea("").setAddress("IT").setX(120.0).setY(30.0).setSold(0).setComments(0).setScore(0)));
        other.invalidateLocal("cache:shop:"+newId);
        assertEquals("new ID IT",other.queryWithPassThroughAndMutex("cache:shop:","lock:shop:",newId,Shop.class,shops::getById,30L,TimeUnit.MINUTES,30L,TimeUnit.SECONDS).getName());
        jdbc.update("DELETE FROM tb_shop WHERE id=?",newId);
    }
}
