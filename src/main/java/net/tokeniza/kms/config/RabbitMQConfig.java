package net.tokeniza.kms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }

    // ── Exchanges ─────────────────────────────────────────────────────────────

    @Bean
    public TopicExchange tokenCreationRequestExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getTokenCreationRequest(), true, false);
    }

    @Bean
    public TopicExchange tokenCreationResponseExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getTokenCreationResponse(), true, false);
    }

    @Bean
    public TopicExchange balanceResponseExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getBalanceResponse(), true, false);
    }

    @Bean
    public TopicExchange tokenEventExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getTokenEvent(), true, false);
    }

    @Bean
    public TopicExchange tokenEventResponseExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getTokenEventResponse(), true, false);
    }

    @Bean
    public TopicExchange tokenTransferRequestExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getTokenTransferRequest(), true, false);
    }

    @Bean
    public TopicExchange tokenTransferResponseExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getTokenTransferResponse(), true, false);
    }

    @Bean
    public TopicExchange accountCreateRequestExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getAccountCreateRequest(), true, false);
    }

    @Bean
    public TopicExchange accountCreateResponseExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getAccountCreateResponse(), true, false);
    }

    @Bean
    public TopicExchange userTransferRequestExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getUserTransferRequest(), true, false);
    }

    @Bean
    public TopicExchange userTransferResponseExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getUserTransferResponse(), true, false);
    }

    @Bean
    public TopicExchange errorExchange(AppProperties p) {
        return new TopicExchange(p.getExchange().getError(), true, false);
    }

    // ── Queues ────────────────────────────────────────────────────────────────

    @Bean public Queue tokenCreationQueue(AppProperties p) { return new Queue(p.getQueue().getTokenCreation(), true); }
    @Bean public Queue balanceQueue(AppProperties p)       { return new Queue(p.getQueue().getBalance(), true); }
    @Bean public Queue tokenEventQueue(AppProperties p)    { return new Queue(p.getQueue().getTokenEvent(), true); }
    @Bean public Queue tokenTransferQueue(AppProperties p) { return new Queue(p.getQueue().getTokenTransfer(), true); }
    @Bean public Queue accountCreateQueue(AppProperties p) { return new Queue(p.getQueue().getAccountCreate(), true); }
    @Bean public Queue userTransferQueue(AppProperties p)  { return new Queue(p.getQueue().getUserTransfer(), true); }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    public Binding tokenCreationBinding(Queue tokenCreationQueue, TopicExchange tokenCreationRequestExchange) {
        return BindingBuilder.bind(tokenCreationQueue).to(tokenCreationRequestExchange).with("#");
    }

    @Bean
    public Binding balanceBinding(Queue balanceQueue, TopicExchange balanceResponseExchange) {
        return BindingBuilder.bind(balanceQueue).to(balanceResponseExchange).with("#");
    }

    @Bean
    public Binding tokenEventBinding(Queue tokenEventQueue, TopicExchange tokenEventExchange) {
        return BindingBuilder.bind(tokenEventQueue).to(tokenEventExchange).with("#");
    }

    @Bean
    public Binding tokenTransferBinding(Queue tokenTransferQueue, TopicExchange tokenTransferRequestExchange) {
        return BindingBuilder.bind(tokenTransferQueue).to(tokenTransferRequestExchange).with("#");
    }

    @Bean
    public Binding accountCreateBinding(Queue accountCreateQueue, TopicExchange accountCreateRequestExchange) {
        return BindingBuilder.bind(accountCreateQueue).to(accountCreateRequestExchange).with("#");
    }

    @Bean
    public Binding userTransferBinding(Queue userTransferQueue, TopicExchange userTransferRequestExchange) {
        return BindingBuilder.bind(userTransferQueue).to(userTransferRequestExchange).with("#");
    }
}
