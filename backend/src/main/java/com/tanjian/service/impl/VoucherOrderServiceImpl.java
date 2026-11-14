package com.tanjian.service.impl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tanjian.dto.*;
import com.tanjian.entity.*;
import com.tanjian.mapper.VoucherOrderMapper;
import com.tanjian.service.*;
import com.tanjian.utils.*;
import jakarta.annotation.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper,VoucherOrder> implements IVoucherOrderService {

    public static final String RESULT_KEY=OrderStreamConsumer.RESULT;
    @Resource private ISeckillVoucherService seckillVoucherService;
    @Resource private IVoucherService voucherService;
    @Resource private IShopService shopService;
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private VoucherOrderProcessor processor;
    @Resource private OrderStreamConsumer consumer;
    @Resource private StockRecovery stockRecovery;
    private static final DefaultRedisScript<Long> SECKILL=OrderStreamConsumer.script("seckill.lua");
    @EventListener(ApplicationStartedEvent.class)
    public void init() { consumer.start(); stockRecovery.startup(); }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if(voucherId==null||voucherId<=0||UserHolder.getUser()==null) return Result.fail("请先登录并选择有效优惠券");
        Voucher voucher=voucherService.getById(voucherId);
        SeckillVoucher seckill=seckillVoucherService.getById(voucherId);
        if(voucher==null||!Integer.valueOf(1).equals(voucher.getType())||!Integer.valueOf(1).equals(voucher.getStatus())
                ||seckill==null||seckill.getBeginTime()==null||seckill.getEndTime()==null) return Result.fail("优惠券不可用");
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDateTime now=LocalDateTime.now(zone);
        if(now.isBefore(seckill.getBeginTime())) return Result.fail("活动尚未开始");
        if(!now.isBefore(seckill.getEndTime())) return Result.fail("活动已经结束");
        if(!stockRecovery.ready(voucherId)) return Result.fail("活动库存正在核对，请稍后重试");
        long orderId=redisIdWorker.nextId("order");
        Long code=stringRedisTemplate.execute(SECKILL,List.of(RedisConstants.SECKILL_STOCK_KEY+voucherId,
                        "seckill:order:"+voucherId,OrderStreamConsumer.STREAM,RESULT_KEY+orderId,OrderStreamConsumer.PAUSED+voucherId),
                UserHolder.getUser().getId().toString(),voucherId.toString(),Long.toString(orderId),
                Long.toString(seckill.getBeginTime().atZone(zone).toInstant().toEpochMilli()),
                Long.toString(seckill.getEndTime().atZone(zone).toInstant().toEpochMilli()));
        if(code==null) return Result.fail("抢购服务暂时不可用");
        return switch(code.intValue()) {
            case 0 -> Result.ok(Long.toString(orderId));
            case 1 -> Result.fail("库存不足");
            case 2 -> Result.fail("每人限领一张，请在我的订单查看");
            case 4 -> Result.fail("活动尚未开始");
            case 5 -> Result.fail("活动已经结束");
            case 6 -> Result.fail("活动库存正在核对，请稍后重试");
            default -> Result.fail("抢购服务暂时不可用，请稍后重试");
        };
    }

    @Override
    public Result claimVoucher(Long voucherId) {
        if(UserHolder.getUser()==null) return Result.fail("请先登录");
        Voucher voucher=voucherId==null?null:voucherService.getById(voucherId);
        if(voucher==null||!Integer.valueOf(0).equals(voucher.getType())||!Integer.valueOf(1).equals(voucher.getStatus()))
            return Result.fail("优惠券不可领取");
        var order=new VoucherOrder().setId(redisIdWorker.nextId("order")).setUserId(UserHolder.getUser().getId()).setVoucherId(voucherId);
        var result=processor.create(order,false);
        if("DUPLICATE".equals(result.code())) return Result.fail("已经领取过该优惠券，请在我的订单查看");
        return Result.ok(order.getId().toString());
    }

    @Override
    public Result queryOrderResult(Long id) {
        if(UserHolder.getUser()==null) return Result.fail("请先登录");
        VoucherOrder order=getById(id);
        if(order!=null) {
            if(!order.getUserId().equals(UserHolder.getUser().getId())) return Result.fail("订单不存在");
            return Result.ok(Map.of("orderId",order.getId().toString(),"status","CREATED","message","订单已创建"));
        }
        Map<Object,Object> result=stringRedisTemplate.opsForHash().entries(RESULT_KEY+id);
        if(!UserHolder.getUser().getId().toString().equals(result.get("userId"))) return Result.fail("订单不存在或结果已过期");
        Map<String,Object> safe=new LinkedHashMap<>();
        for(String key:List.of("orderId","status","message","existingOrderId")) if(result.containsKey(key)) safe.put(key,result.get(key));
        return Result.ok(safe);
    }

    @Override
    public Result queryMyOrders(Integer current) {
        if(UserHolder.getUser()==null) return Result.fail("请先登录");
        int number=current==null||current<1?1:current;
        if(number>10000) return Result.fail("页码过大");
        Page<VoucherOrder> page=lambdaQuery().eq(VoucherOrder::getUserId,UserHolder.getUser().getId())
                .orderByDesc(VoucherOrder::getCreateTime).orderByDesc(VoucherOrder::getId)
                .page(new Page<>(number,SystemConstants.MAX_PAGE_SIZE));
        List<Long> voucherIds=page.getRecords().stream().map(VoucherOrder::getVoucherId).distinct().toList();
        Map<Long,Voucher> vouchers=voucherIds.isEmpty()?Map.of():voucherService.listByIds(voucherIds).stream().collect(Collectors.toMap(Voucher::getId,v->v));
        List<Long> shopIds=vouchers.values().stream().map(Voucher::getShopId).distinct().toList();
        Map<Long,Shop> shops=shopIds.isEmpty()?Map.of():shopService.listByIds(shopIds).stream().collect(Collectors.toMap(Shop::getId,s->s));
        List<VoucherOrderView> views=page.getRecords().stream().map(o -> {
            var v=new VoucherOrderView(); v.setId(o.getId().toString()); v.setVoucherId(o.getVoucherId());
            v.setStatus(o.getStatus()); v.setCreateTime(o.getCreateTime());
            Voucher voucher=vouchers.get(o.getVoucherId());
            if(voucher!=null) { v.setVoucherTitle(voucher.getTitle()); v.setShopId(voucher.getShopId()); v.setPayValue(voucher.getPayValue());
                Shop shop=shops.get(voucher.getShopId()); if(shop!=null) v.setShopName(shop.getName()); }
            return v;
        }).toList();
        return Result.ok(views,page.getTotal());
    }
}

