package com.tanjian.config;

import com.tanjian.utils.CacheClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.nio.charset.StandardCharsets;

@Configuration
public class CacheInvalidationConfig {
    @Bean
    @ConditionalOnProperty(name="tanjian.cache.notifications.enabled",havingValue="true",matchIfMissing=true)
    public RedisMessageListenerContainer cacheInvalidationListener(RedisConnectionFactory connectionFactory,CacheClient cache) {
        var listener=new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.addMessageListener(new Invalidations(cache),new ChannelTopic(CacheClient.INVALIDATION_CHANNEL));
        return listener;
    }
    public static class Invalidations implements MessageListener,SubscriptionListener {
        private final CacheClient cache;
        public Invalidations(CacheClient cache) { this.cache=cache; }
        @Override public void onMessage(Message message,byte[] pattern) {
            cache.invalidateLocal(new String(message.getBody(),StandardCharsets.UTF_8));
        }
        @Override public void onChannelSubscribed(byte[] channel,long count) { cache.clearLocal(); }
        @Override public void onChannelUnsubscribed(byte[] channel,long count) { cache.clearLocal(); }
    }
}
