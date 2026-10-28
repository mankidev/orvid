package com.orvid.net;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class Hedge {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private Hedge() {
    }

    public interface Task<T> {
        T run(String circuit) throws Exception;
    }

    public static class Final extends java.io.IOException {
        public Final(String message) {
            super(message);
        }
    }

    public static final class Result<T> {
        public final T value;
        public final String circuit;

        Result(T value, String circuit) {
            this.value = value;
            this.circuit = circuit;
        }
    }

    public static <T> Result<T> call(String first, Supplier<String> spare, long delayMs, Task<T> task) throws Exception {
        CompletionService<Result<T>> done = new ExecutorCompletionService<>(POOL);
        java.util.Map<Future<Result<T>>, Fetch.Scope> lanes = new java.util.HashMap<>();
        Future<Result<T>> a = submit(done, lanes, first, task, true);
        Future<Result<T>> b = null;
        Future<Result<T>> got = delayMs > 0 ? done.poll(delayMs, TimeUnit.MILLISECONDS) : null;
        int running = 1;
        if (got == null && delayMs > 0) {
            String second = spare.get();
            Diag.mark("hedge: second circuit after " + delayMs + "ms");
            Trace.note(Trace.Step.LANE, 2);
            b = submit(done, lanes, second, task, true);
            running = 2;
        }
        Exception last = null;
        while (running > 0) {
            Future<Result<T>> f = got != null ? got : done.take();
            got = null;
            running--;
            try {
                Result<T> r = f.get();
                stopOthers(lanes, f);
                return r;
            } catch (ExecutionException e) {
                last = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                if (last instanceof Final) {
                    stopOthers(lanes, null);
                    throw last;
                }
                if (b == null && spare != null && Fetch.torMode() && !(last instanceof Fetch.Cancelled)) {
                    Diag.mark("hedge: first circuit failed early, second circuit now");
                    Trace.note(Trace.Step.LANE, 2);
                    b = submit(done, lanes, spare.get(), task, true);
                    running++;
                }
            }
        }
        throw last;
    }

    public static <T> Result<T> race(java.util.List<String> circuits, long staggerMs, Task<T> task) throws Exception {
        return race(circuits, staggerMs, true, task);
    }

    public static <T> Result<T> race(java.util.List<String> circuits, long staggerMs, boolean retry, Task<T> task) throws Exception {
        CompletionService<Result<T>> done = new ExecutorCompletionService<>(POOL);
        java.util.Map<Future<Result<T>>, Fetch.Scope> lanes = new java.util.HashMap<>();
        Future<Result<T>> won = null;
        submit(done, lanes, circuits.get(0), task, retry);
        int next = 1;
        int running = 1;
        Exception last = null;
        try {
            while (running > 0) {
                Future<Result<T>> f = next < circuits.size()
                        ? done.poll(staggerMs, TimeUnit.MILLISECONDS) : done.take();
                if (f == null) {
                    Diag.mark("race: circuit " + (next + 1) + " joins");
                    Trace.note(Trace.Step.LANE, next + 1);
                    submit(done, lanes, circuits.get(next++), task, retry);
                    running++;
                    continue;
                }
                running--;
                try {
                    Result<T> r = f.get();
                    won = f;
                    return r;
                } catch (ExecutionException e) {
                    last = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                    if (last instanceof Final) throw last;
                    if (next < circuits.size()) {
                        submit(done, lanes, circuits.get(next++), task, retry);
                        running++;
                    }
                }
            }
            throw last;
        } finally {
            stopOthers(lanes, won);
        }
    }

    private static <T> Future<Result<T>> submit(CompletionService<Result<T>> done,
            java.util.Map<Future<Result<T>>, Fetch.Scope> lanes, String circuit, Task<T> task, boolean retry) {
        Fetch.Scope parent = Fetch.scope();
        Fetch.Scope lane = parent != null ? parent.lane() : new Fetch.Scope();
        if (parent == null) lane.background = Focus.backgroundThread();
        Future<Result<T>> f = done.submit(wrap(lane, circuit, task, retry));
        lanes.put(f, lane);
        return f;
    }

    private static <T> void stopOthers(java.util.Map<Future<Result<T>>, Fetch.Scope> lanes, Future<Result<T>> keep) {
        for (java.util.Map.Entry<Future<Result<T>>, Fetch.Scope> e : lanes.entrySet()) {
            if (e.getKey() == keep) continue;
            e.getKey().cancel(true);
            e.getValue().cancel();
        }
    }

    private static <T> Callable<Result<T>> wrap(Fetch.Scope lane, String circuit, Task<T> task, boolean retry) {
        Trace.Sink sink = Trace.current();
        if (!retry) return () -> Trace.with(sink, () -> Fetch.within(lane, () -> new Result<>(Fetch.on(circuit, () -> task.run(circuit)), circuit)));
        return () -> Trace.with(sink, () -> Fetch.within(lane, () -> new Result<>(Fetch.retrying(circuit, task::run), circuit)));
    }
}
