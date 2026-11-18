package dev.shop.api.auth;

import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@Configuration(proxyBeanMethods = false)
class SessionStoreConfiguration {

    /** The bean name is the one Spring Session looks up for the value serialiser of its Redis hash. */
    @Bean(name = "springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new JdkSerializationRedisSerializer(
                new SerializingConverter(), new AllowlistDeserializer(getClass().getClassLoader()));
    }

    @Bean
    static BeanPostProcessor safeSessionRepositoryPostProcessor(ObjectProvider<ValkeyOidcSessionRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SessionRepository<?> repository) {
                    return safe(repository, sessionId -> registry.getObject().removeSessionInformation(sessionId));
                }
                return bean;
            }
        };
    }

    private static <S extends Session> SessionRepository<S> safe(
            SessionRepository<S> repository, Consumer<String> afterDelete) {
        return new SafeSessionRepository<>(repository, afterDelete);
    }
}
