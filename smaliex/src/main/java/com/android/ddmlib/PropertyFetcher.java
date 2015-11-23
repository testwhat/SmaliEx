/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ddmlib;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fetches and caches 'getprop' values from device.
 */
class PropertyFetcher {
    /** the amount of time to wait between unsuccessful prop fetch attempts */
    private static final String GETPROP_COMMAND = "getprop"; //$NON-NLS-1$
    private static final Pattern GETPROP_PATTERN = Pattern.compile("^\\[([^]]+)\\]\\:\\s*\\[(.*)\\]$"); //$NON-NLS-1$
    private static final int GETPROP_TIMEOUT_SEC = 2;
    private static final int EXPECTED_PROP_COUNT = 150;

    private enum CacheState {
        UNPOPULATED, FETCHING, POPULATED
    }

    /**
     * Shell output parser for a getprop command
     */
    static class GetPropReceiver extends MultiLineReceiver {

        private final Map<String, String> mCollectedProperties =
                new HashMap<>(EXPECTED_PROP_COUNT);

        @Override
        public void processNewLines(String[] lines) {
            // We receive an array of lines. We're expecting
            // to have the build info in the first line, and the build
            // date in the 2nd line. There seems to be an empty line
            // after all that.

            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                Matcher m = GETPROP_PATTERN.matcher(line);
                if (m.matches()) {
                    String label = m.group(1);
                    String value = m.group(2);

                    if (!label.isEmpty()) {
                        mCollectedProperties.put(label, value);
                    }
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        Map<String, String> getCollectedProperties() {
            return mCollectedProperties;
        }
    }

    private final Map<String, String> mProperties = new HashMap<>(
            EXPECTED_PROP_COUNT);
    private final Device mDevice;
    private CacheState mCacheState = CacheState.UNPOPULATED;
    private final Map<String, SettableFuture<String>> mPendingRequests =
            new HashMap<>(4);

    public PropertyFetcher(Device device) {
        mDevice = device;
    }

    /**
     * Returns the full list of cached properties.
     */
    public synchronized Map<String, String> getProperties() {
        return mProperties;
    }

    /**
     * Make a possibly asynchronous request for a system property value.
     *
     * @param name the property name to retrieve
     * @return a {@link Future} that can be used to retrieve the prop value
     */
    public synchronized Future<String> getProperty(@Nonnull String name) {
        SettableFuture<String> result;
        if (mCacheState.equals(CacheState.FETCHING)) {
            result = addPendingRequest(name);
        } else if (mCacheState.equals(CacheState.UNPOPULATED) || !isRoProp(name)) {
            // cache is empty, or this is a volatile prop that requires a query
            result = addPendingRequest(name);
            mCacheState = CacheState.FETCHING;
            initiatePropertiesQuery();
        } else {
            result = SettableFuture.create();
            // cache is populated and this is a ro prop
            result.set(mProperties.get(name));
        }
        return result;
    }

    private SettableFuture<String> addPendingRequest(String name) {
        SettableFuture<String> future = mPendingRequests.get(name);
        if (future == null) {
            future = SettableFuture.create();
            mPendingRequests.put(name, future);
        }
        return future;
    }

    private void initiatePropertiesQuery() {
        String threadName = String.format("query-prop-%s", mDevice.getSerialNumber());
        Thread propThread = new Thread(threadName) {
            @Override
            public void run() {
                try {
                    GetPropReceiver propReceiver = new GetPropReceiver();
                    mDevice.executeShellCommand(GETPROP_COMMAND, propReceiver, GETPROP_TIMEOUT_SEC,
                            TimeUnit.SECONDS);
                    populateCache(propReceiver.getCollectedProperties());
                } catch (Exception e) {
                    handleException(e);
                }
            }
        };
        propThread.setDaemon(true);
        propThread.start();
    }

    private synchronized void populateCache(@Nonnull Map<String, String> props) {
        mCacheState = props.isEmpty() ? CacheState.UNPOPULATED : CacheState.POPULATED;
        if (!props.isEmpty()) {
            mProperties.putAll(props);
        }
        for (Map.Entry<String, SettableFuture<String>> entry : mPendingRequests.entrySet()) {
            entry.getValue().set(mProperties.get(entry.getKey()));
        }
        mPendingRequests.clear();
    }

    private synchronized void handleException(Exception e) {
        mCacheState = CacheState.UNPOPULATED;
        Log.w("PropertyFetcher",
                String.format("%s getting properties for device %s: %s",
                        e.getClass().getSimpleName(), mDevice.getSerialNumber(),
                        e.getMessage()));
        for (Map.Entry<String, SettableFuture<String>> entry : mPendingRequests.entrySet()) {
            entry.getValue().setException(e);
        }
        mPendingRequests.clear();
    }

    private static boolean isRoProp(@Nonnull String propName) {
        return propName.startsWith("ro.");
    }
}

// Extract from Guava libraries to reduce dependency
final class SettableFuture<V> implements Future<V> {
    private final Sync<V> sync = new Sync<>();
    private final ExecutionList executionList = new ExecutionList();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!sync.cancel(mayInterruptIfRunning)) {
            return false;
        }
        executionList.execute();
        if (mayInterruptIfRunning) {
            interruptTask();
        }
        return true;
    }

    protected void interruptTask() {
    }

    @Override
    public boolean isCancelled() {
        return sync.isCancelled();
    }

    @Override
    public boolean isDone() {
        return sync.isDone();
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        return sync.get();
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
            TimeoutException {
        return sync.get(unit.toNanos(timeout));
    }

    public boolean set(@Nullable V value) {
        boolean result = sync.set(value);
        if (result) {
            executionList.execute();
        }
        return result;
    }

    public static <V> SettableFuture<V> create() {
        return new SettableFuture<>();
    }

    public boolean setException(@Nonnull Throwable throwable) {
        boolean result = sync.setException(throwable);
        if (result) {
            executionList.execute();
        }
        return result;
    }
}

final class Sync<V> extends AbstractQueuedSynchronizer {
    private static final long serialVersionUID = 0L;

    static final int RUNNING = 0;
    static final int COMPLETING = 1;
    static final int COMPLETED = 2;
    static final int CANCELLED = 4;
    static final int INTERRUPTED = 8;

    private V value;
    private Throwable exception;

    static final CancellationException cancellationExceptionWithCause(
            @Nullable String message, @Nullable Throwable cause) {
        CancellationException exception = new CancellationException(message);
        exception.initCause(cause);
        return exception;
    }

    @Override
    protected int tryAcquireShared(int ignored) {
        if (isDone()) {
            return 1;
        }
        return -1;
    }

    @Override
    protected boolean tryReleaseShared(int finalState) {
        setState(finalState);
        return true;
    }

    V get(long nanos) throws TimeoutException, CancellationException,
            ExecutionException, InterruptedException {

        if (!tryAcquireSharedNanos(-1, nanos)) {
            throw new TimeoutException("Timeout waiting for task.");
        }

        return getValue();
    }

    V get() throws CancellationException, ExecutionException,
            InterruptedException {

        acquireSharedInterruptibly(-1);
        return getValue();
    }

    private V getValue() throws CancellationException, ExecutionException {
        int state = getState();
        switch (state) {
            case COMPLETED:
                if (exception != null) {
                    throw new ExecutionException(exception);
                } else {
                    return value;
                }

            case CANCELLED:
            case INTERRUPTED:
                throw cancellationExceptionWithCause(
                        "Task was cancelled.", exception);

            default:
                throw new IllegalStateException(
                        "Error, synchronizer in invalid state: " + state);
        }
    }

    boolean isDone() {
        return (getState() & (COMPLETED | CANCELLED | INTERRUPTED)) != 0;
    }

    boolean isCancelled() {
        return (getState() & (CANCELLED | INTERRUPTED)) != 0;
    }

    boolean wasInterrupted() {
        return getState() == INTERRUPTED;
    }

    boolean set(@Nullable V v) {
        return complete(v, null, COMPLETED);
    }

    boolean setException(Throwable t) {
        return complete(null, t, COMPLETED);
    }

    boolean cancel(boolean interrupt) {
        return complete(null, null, interrupt ? INTERRUPTED : CANCELLED);
    }

    private boolean complete(@Nullable V v, @Nullable Throwable t,
            int finalState) {
        boolean doCompletion = compareAndSetState(RUNNING, COMPLETING);
        if (doCompletion) {
            this.value = v;
            this.exception = ((finalState & (CANCELLED | INTERRUPTED)) != 0)
                    ? new CancellationException("Future.cancel() was called.") : t;
            releaseShared(finalState);
        } else if (getState() == COMPLETING) {
            acquireShared(-1);
        }
        return doCompletion;
    }
}

final class ExecutionList {
    private RunnableExecutorPair runnables;
    private boolean executed;

    public ExecutionList() {
    }

    public void add(Runnable runnable, Executor executor) {
        synchronized (this) {
            if (!executed) {
                runnables = new RunnableExecutorPair(runnable, executor, runnables);
                return;
            }
        }
        executeListener(runnable, executor);
    }

    public void execute() {
        RunnableExecutorPair list;
        synchronized (this) {
            if (executed) {
                return;
            }
            executed = true;
            list = runnables;
            runnables = null;
        }
        RunnableExecutorPair reversedList = null;
        while (list != null) {
            RunnableExecutorPair tmp = list;
            list = list.next;
            tmp.next = reversedList;
            reversedList = tmp;
        }
        while (reversedList != null) {
            executeListener(reversedList.runnable, reversedList.executor);
            reversedList = reversedList.next;
        }
    }

    private static void executeListener(Runnable runnable, Executor executor) {
        try {
            executor.execute(runnable);
        } catch (RuntimeException e) {
        }
    }

    private static final class RunnableExecutorPair {
        final Runnable runnable;
        final Executor executor;
        @Nullable
        RunnableExecutorPair next;

        RunnableExecutorPair(Runnable runnable, Executor executor, RunnableExecutorPair next) {
            this.runnable = runnable;
            this.executor = executor;
            this.next = next;
        }
    }
}
