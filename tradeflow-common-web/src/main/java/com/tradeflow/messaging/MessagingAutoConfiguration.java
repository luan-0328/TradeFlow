package com.tradeflow.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 无网络开关也注册追加/操作能力；传输仅 enabled=true 时启用，Gateway 不依赖此模块。 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class, RabbitAutoConfiguration.class})
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {
    @Bean EventCodec messagingEventCodec(ObjectMapper mapper) { return new EventCodec(mapper); }
    @Bean MessageStore messagingMessageStore(DataSource source, PlatformTransactionManager manager) {
        return new MessageStore(new JdbcTemplate(source), manager);
    }
    @Bean MessageOutbox messageOutbox(DataSource source, MessageStore store, EventCodec codec) {
        return new MessageOutbox(source, store.db, codec);
    }
    @Bean MessageOperations messageOperations(MessageStore store, EventCodec codec) { return new MessageOperations(store, codec); }

    @Bean
    @ConditionalOnProperty(prefix = "tradeflow.messaging", name = "enabled", havingValue = "true")
    MessagingRuntime messagingRuntime(MessageStore store, EventCodec codec, ConnectionFactory connectionFactory,
            ObjectProvider<EventHandler> handlers, ObjectProvider<OutboxGuard> guards, MessagingProperties properties) {
        properties.validate();
        if (!(connectionFactory instanceof CachingConnectionFactory caching))
            throw new IllegalStateException("B5要求CachingConnectionFactory支持correlated confirms与returns");
        caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        caching.setPublisherReturns(true);
        // 专属 template，不修改业务已有 template 的 callbacks；绝不创建 RabbitAdmin/Declarables。
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setUsePublisherConnection(true);
        return new MessagingRuntime(store, new MessageReceiver(store, codec, handlers.orderedStream().toList()),
                new ConfirmedPublisher(store, codec, template, guards.orderedStream().toList(), properties), connectionFactory, properties);
    }
}
