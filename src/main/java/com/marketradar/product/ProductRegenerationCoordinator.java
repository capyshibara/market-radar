package com.marketradar.product;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Ensures only one Product regeneration can write event/edition tables at a time. */
@Component
public class ProductRegenerationCoordinator {

    private final ReentrantLock lock = new ReentrantLock();

    public <T> T runExclusive(Supplier<T> work) {
        if (!lock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Product regeneration is already running; wait for it to finish before retrying.");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
