// SPDX-License-Identifier: GPL-3.0-or-later

package hath.base;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Isolates slow filesystem metadata reads from session threads and the common pool. */
final class CacheFileLookup {
    private static final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        8, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32), task -> {
            Thread thread = new Thread(task, "CacheFileLookup");
            thread.setDaemon(true);
            return thread;
        });

    static {
        workers.allowCoreThreadTimeOut(true);
    }

    static long size(File file) throws IOException {
        return size(file, 8, TimeUnit.SECONDS);
    }

    static long size(File file, long timeout, TimeUnit unit) throws IOException {
        Future<Long> result;
        try {
            result = workers.submit(() -> file.exists() ? file.length() : -1L);
        } catch(RejectedExecutionException e) {
            throw new IOException("Cache lookup capacity exhausted", e);
        }
        try {
            return result.get(timeout, unit);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cache lookup interrupted", e);
        } catch(ExecutionException | TimeoutException e) {
            throw new IOException("Cache lookup failed or exceeded its deadline", e);
        } finally {
            result.cancel(true);
            workers.purge();
        }
    }

    static void shutdown() {
        workers.shutdownNow();
    }
}
