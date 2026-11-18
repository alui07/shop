package dev.shop.api.auth;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.serializer.support.SerializationFailedException;

/** JDK deserialisation limited to the types a session holds (JEP 290), so a tampered Valkey value cannot run gadgets. */
final class AllowlistDeserializer implements Converter<byte[], Object> {

    static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter("maxdepth=20;"
            + "java.lang.*;java.math.*;java.net.*;java.util.**;java.time.**;"
            + "org.springframework.security.**;org.springframework.session.**;dev.shop.api.**;"
            + "!*");

    private final ClassLoader classLoader;

    AllowlistDeserializer(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public Object convert(byte[] source) {
        try (ObjectInputStream input =
                new ConfigurableObjectInputStream(new ByteArrayInputStream(source), classLoader)) {
            input.setObjectInputFilter(FILTER);
            return input.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new SerializationFailedException("Rejected a session value", e);
        }
    }
}
