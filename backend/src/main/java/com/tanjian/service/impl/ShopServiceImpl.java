package com.tanjian.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tanjian.service.CacheInvalidationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import com.tanjian.dto.Result;
import com.tanjian.utils.CacheClient;
import com.tanjian.entity.Shop;
import com.tanjian.mapper.ShopMapper;
import com.tanjian.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tanjian.utils.RedisConstants;
import com.tanjian.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Point;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;


@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Resource
    private org.redisson.api.RedissonClient redissonClient;

    @Resource private CacheInvalidationEvents cacheEvents;
    @Resource private TransactionTemplate transactions;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithPassThroughAndMutex(
                RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
                id, Shop.class, this::getById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES,
                RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS
        );
        if(shop==null){
            return Result.fail("店铺不存在");
        }else{
            return Result.ok(shop);
        }

    }



    @Override
    public Result updateShop(Shop shop) {
        if(shop.getId()==null) return Result.fail("店铺id不能为空");
        var lock=redissonClient.getLock(RedisConstants.LOCK_SHOP_KEY+shop.getId());
        boolean held=false;
        try {
            held=lock.tryLock(3,TimeUnit.SECONDS);
            if(!held) return Result.fail("店铺资料正在更新，请稍后重试");
            Long event=transactions.execute(tx -> {
                Shop previous=getById(shop.getId());
                if(previous==null) return null;
                if(!updateById(shop)) throw new IllegalStateException("店铺信息更新失败");
                return cacheEvents.record(shop.getId(),previous.getTypeId());
            });
            if(event==null) return Result.fail("店铺不存在");
            // Failure after commit must not turn a committed update into a client-visible failure.
            try { cacheEvents.deliver(event); }
            catch(Exception e) { log.warn("Committed shop update awaits cache event {}",event); }
            return Result.ok();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); return Result.fail("更新被中断，请稍后重试");
        } finally { if(held && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    @Override
    public boolean save(Shop shop) {
        Long event=transactions.execute(tx -> {
            if(!super.save(shop)) return null;
            return cacheEvents.record(shop.getId(),null);
        });
        if(event==null) return false;
        try { cacheEvents.deliver(event); }
        catch(Exception e) { log.warn("New shop awaits cache event {}",event); }
        return true;
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null || typeId <= 0) {
            return Result.fail("店铺类型不能为空");
        }
        int page = current == null || current < 1 ? 1 : current;
        if (page > 10000) return Result.fail("页码过大");
        if ((x == null) != (y == null)) return Result.fail("请同时提供经度和纬度");
        if (x != null && (!Double.isFinite(x) || !Double.isFinite(y)
                || x < -180 || x > 180 || y < -85.05112878 || y > 85.05112878)) {
            return Result.fail("坐标格式不正确");
        }
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> dbPage = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(page, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(dbPage.getRecords());
        }

        // 2.计算分页参数
        int from = (page - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = page * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        // GEORADIUS 同时兼容本项目使用的 Redis 5 和较新版本。
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(
                        key,
                        new Circle(new Point(x, y), new Distance(5, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue() * 1000);
        }
        // 6.返回
        return Result.ok(shops);
    }


}
