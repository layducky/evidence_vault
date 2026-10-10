package com.evidencevault.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fires tasks at the same instant to maximise interleaving in race-condition tests.
 * Every task waits on a shared start gate, so none gets a head start while others are still being scheduled.
 */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<Outcome<T>> run(int threads, Callable<T> task) throws InterruptedException {
        List<Callable<T>> tasks = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return run(tasks);
    }

    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(tasks.size());

        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready in time");
            }
            start.countDown();
        }

        List<Outcome<T>> outcomes = new ArrayList<>(futures.size());
        for (Future<T> f : futures) {
            try {
                outcomes.add(Outcome.success(f.get()));
            } catch (java.util.concurrent.ExecutionException e) {
                outcomes.add(Outcome.failure(e.getCause()));
            }
        }
        return outcomes;
    }

    public record Outcome<T>(T value, Throwable error) {

        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failure(Throwable error) {
            return new Outcome<>(null, error);
        }

        public boolean succeeded() {
            return error == null;
        }
    }
}
