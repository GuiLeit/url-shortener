package com.shortener.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqTopology {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public TopicExchange urlEventsExchange() {
        return new TopicExchange("url.events", true, false);
    }

    @Bean
    public DirectExchange urlEventsDlx() {
        return new DirectExchange("url.events.dlx", true, false);
    }

    @Bean
    public Queue accessLogQueue() {
        return QueueBuilder.durable("url.access.log")
                .withArgument("x-dead-letter-exchange", "url.events.dlx")
                .build();
    }

    @Bean
    public Queue accessLogDlq() {
        return QueueBuilder.durable("url.access.log.dlq").build();
    }

    @Bean
    public Binding accessLogBinding() {
        return BindingBuilder.bind(accessLogQueue())
                .to(urlEventsExchange())
                .with("url.accessed");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(accessLogDlq())
                .to(urlEventsDlx())
                .with("url.access.log");
    }
}
