package io.github.samettns.reservation.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Runs N copies of a task at (as close as possible to) the same moment, one virtual
 * thread each, and collects every result or exception.
 *
 * <p>The start gate matters: without it, early threads would finish before late ones
 * start and the test would measure almost no real contention.
 */
public final class ConcurrentRunner {

    /** Result of one task: exactly one of value / error is non-null. */
    public record Attempt<T>(T value, Throwable error) {
        public boolean failed() {
            return error != null;
        }
    }

    private ConcurrentRunner() {
    }

    public static <T> List<Attempt<T>> run(int count, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt<T>>> futures = new ArrayList<>(count);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Attempt<>(task.apply(index), null);
                    } catch (Throwable t) {
                        return new Attempt<T>(null, t);
                    }
                }));
            }
            ready.await();
            start.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        // executor.close() above waited for every task, so get() never blocks here.
        return futures.stream().map(ConcurrentRunner::join).toList();
    }

    private static <T> Attempt<T> join(Future<Attempt<T>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
