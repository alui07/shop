package dev.shop.api.auth;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * A session that no longer deserialises (an upgrade changed a class, or the value was rejected by the allowlist) is
 * deleted and treated as signed out. Deleting also drops the back-channel mapping of that session.
 */
final class SafeSessionRepository<S extends Session> implements SessionRepository<S> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SafeSessionRepository.class);

    private final SessionRepository<S> delegate;
    private final Consumer<String> afterDelete;

    SafeSessionRepository(SessionRepository<S> delegate, Consumer<String> afterDelete) {
        this.delegate = delegate;
        this.afterDelete = afterDelete;
    }

    @Override
    public S createSession() {
        return delegate.createSession();
    }

    @Override
    public void save(S session) {
        delegate.save(session);
    }

    @Override
    public S findById(String id) {
        try {
            return delegate.findById(id);
        } catch (SerializationException e) {
            LOGGER.warn(
                    "Dropping a session that cannot be read: {}", e.getClass().getSimpleName());
            deleteById(id);
            return null;
        }
    }

    @Override
    public void deleteById(String id) {
        delegate.deleteById(id);
        afterDelete.accept(id);
    }
}
