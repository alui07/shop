package dev.shop.api.order;

import java.util.UUID;

/** Calls the repository's unscoped lock on purpose; only the module rules test looks at this class. */
class LockByIdPeeker {

    void peek(OrderRepository repository, UUID id) {
        repository.lockById(id);
    }
}
