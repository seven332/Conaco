/*
 * Copyright 2015-2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.conaco;

import android.os.Process;
import android.support.annotation.IntDef;
import android.util.Log;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.yorozuya.thread.PriorityThreadFactory;
import com.hippo.yorozuya.thread.SerialThreadExecutor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

public class Conaco<V> {

    private static final String TAG = Conaco.class.getSimpleName();

    @IntDef({SOURCE_MEMORY, SOURCE_DISK, SOURCE_NETWORK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Source {}

    public static final int SOURCE_MEMORY = 0;
    public static final int SOURCE_DISK = 1;
    public static final int SOURCE_NETWORK = 2;

    private ValueHelper<V> mHelper;
    private ValueCache<V> mCache;
    private OkHttpClient mOkHttpClient;

    private Register<V> mRegister;

    private final SerialThreadExecutor mDiskExecutor;
    private final ThreadPoolExecutor mNetworkExecutor;
    private final AtomicInteger mIdGenerator;

    boolean mDebug;

    private Conaco(Builder<V> builder) {
        mHelper = builder.objectHelper;

        BeerBelly.BeerBellyParams beerBellyParams = new BeerBelly.BeerBellyParams();
        beerBellyParams.hasMemoryCache = builder.hasMemoryCache;
        beerBellyParams.memoryCacheMaxSize = builder.memoryCacheMaxSize;
        beerBellyParams.hasDiskCache = builder.hasDiskCache;
        beerBellyParams.diskCacheDir = builder.diskCacheDir;
        beerBellyParams.diskCacheMaxSize = builder.diskCacheMaxSize;

        mCache = new ValueCache<>(beerBellyParams, mHelper);
        mOkHttpClient = builder.okHttpClient;

        mRegister = new Register<>();

        mDiskExecutor = new SerialThreadExecutor(3000L, new LinkedList<Runnable>(),
                new PriorityThreadFactory(TAG + "-Disk", Process.THREAD_PRIORITY_BACKGROUND));
        mNetworkExecutor = new ThreadPoolExecutor(3, 3, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new PriorityThreadFactory(TAG + "-Network", Process.THREAD_PRIORITY_BACKGROUND));

        mIdGenerator = new AtomicInteger();

        mDebug = builder.debug;
    }

    /**
     * True to enable debug info
     */
    public void setDebug(boolean debug) {
        mDebug = debug;
    }

    /**
     * Return true if debug enabled.
     */
    public boolean isDebug() {
        return mDebug;
    }

    public BeerBelly getBeerBelly() {
        return mCache;
    }

    private void startSameKeyTask(ConacoTask<V> task) {
        // Start another task with the same key
        ConacoTask<V> next = mRegister.getByKey(task.getKey());
        if (next != null) {
            startConacoTask(next);
        }
    }

    private void startConacoTask(ConacoTask<V> task) {
        Unikery<V> unikery = task.getUnikery();

        if (unikery == null) {
            // Unikery is gone, finish the task
            finishConacoTask(task);
            return;
        }

        String key = task.getKey();
        V value = null;

        // Get from memory
        if (key != null && task.useMemoryCache() && mHelper.useMemoryCache(key, null)) {
            value = mCache.getFromMemory(key);
        }

        if (value != null) {
            // Get the object, finish the task
            unikery.onGetValue(value, SOURCE_MEMORY);
            finishConacoTask(task);
        } else {
            // Can't get value from memory cache, start the task
            unikery.onMiss(SOURCE_MEMORY);
            task.start();
        }
    }

    /**
     * Load the Conaco task from the build.
     *
     * Call it in Ui thread only.
     */
    public void load(ConacoTask.Builder<V> builder) {
        builder.isValid();

        if (mDebug) {
            Log.d(TAG, "Key " + builder.key);
            Log.d(TAG, "Url " + builder.url);
        }

        Unikery<V> unikery = builder.unikery;

        // Cancel first
        cancel(unikery);

        // Build conaco task
        int id;
        // Skip Unikery.INVALID_ID
        while ((id = mIdGenerator.getAndIncrement()) == Unikery.INVALID_ID);
        unikery.setTaskId(id);
        builder.id = id;
        builder.conaco = this;
        if (builder.helper == null) builder.helper = mHelper;
        if (builder.cache == null) builder.cache = mCache;
        if (builder.okHttpClient == null) builder.okHttpClient = mOkHttpClient;
        if (builder.diskExecutor == null) builder.diskExecutor = mDiskExecutor;
        if (builder.networkExecutor == null) builder.networkExecutor = mNetworkExecutor;
        ConacoTask<V> task = builder.build();

        if (!mRegister.register(id, task)) {
            startConacoTask(task);
        } else {
            // The key is repeated, wait
            unikery.onWait();
        }
    }

    /**
     * Cancel the task associated with the Unikery.
     *
     * Call it in Ui thread only.
     */
    public void cancel(Unikery<V> unikery) {
        int id = unikery.getTaskId();
        if (id != Unikery.INVALID_ID) {
            unikery.setTaskId(Unikery.INVALID_ID);
            ConacoTask<V> task = mRegister.unregister(id);
            if (task != null) {
                task.stop();
                // Don't need unikery anymore
                task.clearUnikery();
                // Check another task with the same key
                startSameKeyTask(task);
            } else {
                Log.e(TAG, "Can't find conaco task by id " + id);
            }
        }
    }

    /**
     * Return return true if the conaco is loading the unikery.
     *
     * Call it in Ui thread only.
     */
    public boolean isLoading(Unikery<V> unikery) {
        int id = unikery.getTaskId();
        return id != Unikery.INVALID_ID && mRegister.contain(id);
    }

    void finishConacoTask(ConacoTask<V> task) {
        // Unregister task, reset unikery id, clear unikery in task, call next
        mRegister.unregister(task.getId());
        Unikery unikery = task.getUnikery();
        if (unikery != null) {
            unikery.setTaskId(Unikery.INVALID_ID);
        }
        task.clearUnikery();
        // Check another task with the same key
        startSameKeyTask(task);
    }

    /**
     * A builder to create Conaco.
     */
    public static class Builder<T> extends BeerBelly.BeerBellyParams {
        /**
         * The client to get image from internet
         */
        public OkHttpClient okHttpClient = null;

        /**
         * Decode, get size and others
         */
        public ValueHelper<T> objectHelper = null;

        public boolean debug = false;

        @Override
        public void isValid() throws IllegalStateException {
            super.isValid();

            if (okHttpClient == null) {
                throw new IllegalStateException("No http client? How can I load image via url?");
            }
        }

        public Conaco<T> build() {
            isValid();
            return new Conaco<>(this);
        }
    }
}
