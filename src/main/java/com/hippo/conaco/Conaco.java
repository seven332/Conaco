/*
 * Copyright 2015 Hippo Seven
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

import android.graphics.drawable.Drawable;
import android.os.Process;
import android.support.annotation.UiThread;
import android.util.Log;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.yorozuya.IntIdGenerator;
import com.hippo.yorozuya.OSUtils;
import com.hippo.yorozuya.PriorityThreadFactory;
import com.hippo.yorozuya.SerialThreadExecutor;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class Conaco<V> {

    private static final String TAG = Conaco.class.getSimpleName();

    private ValueHelper<V> mHelper;
    private ValueCache<V> mCache;
    private OkHttpClient mOkHttpClient;

    private Register<V> mRegister;

    private final SerialThreadExecutor mDiskTasExecutor;
    private final ThreadPoolExecutor mNetworkExecutor;

    private final IntIdGenerator mIdGenerator;

    private final boolean DEBUG;

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

        mDiskTasExecutor = new SerialThreadExecutor(3000, new LinkedBlockingDeque<Runnable>(),
                new PriorityThreadFactory("Conaco-Disk", Process.THREAD_PRIORITY_BACKGROUND));

        mNetworkExecutor = new ThreadPoolExecutor(3, 3, 5L, TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>(),
                new PriorityThreadFactory("Conaco-Network", Process.THREAD_PRIORITY_BACKGROUND));

        mIdGenerator = new IntIdGenerator();

        DEBUG = builder.debug;
    }

    public BeerBelly getBeerBelly() {
        return mCache;
    }

    @UiThread
    private void onUnregisterConacoTask(ConacoTask<V> task) {
        ConacoTask<V> next = mRegister.getByKey(task.getKey());
        if (next != null) {
            startConacoTask(next);
        }
    }

    @UiThread
    private void startConacoTask(ConacoTask<V> task) {
        Unikery<V> unikery = task.getUnikery();

        // Can't unikery is gone, finish the task now
        if (unikery == null) {
            finishConacoTask(task);
            return;
        }

        String key = task.getKey();
        ValueHolder<V> holder = null;

        // Get from memory
        if (key != null && task.isUseMemoryCache() && mHelper.useMemoryCache(key, null)) {
            holder = mCache.getFromMemory(key);
        }

        if (holder == null || !unikery.onGetObject(holder, Source.MEMORY)) {
            unikery.onMiss(Source.MEMORY);
            task.start();
        } else {
            // Get the object, finish the task
            finishConacoTask(task);
        }
    }

    @UiThread
    public void load(ConacoTask.Builder<V> builder) {
        OSUtils.checkMainLoop();
        builder.isValid();

        if (DEBUG) {
            Log.d(TAG, "Key " + builder.getKey());
            Log.d(TAG, "Url " + builder.getUrl());
        }

        Unikery<V> unikery = builder.getUnikery();

        // Cancel first
        cancel(unikery);

        // Build conaco task
        int id = mIdGenerator.nextId();
        unikery.setTaskId(id);
        builder.setId(id);
        builder.setHelper(mHelper);
        builder.setCache(mCache);
        builder.setOkHttpClient(mOkHttpClient);
        builder.setDiskExecutor(mDiskTasExecutor);
        builder.setNetworkExecutor(mNetworkExecutor);
        builder.setConaco(this);
        ConacoTask<V> task = builder.build();

        if (!mRegister.register(id, task)) {
            startConacoTask(task);
        } else {
            // The key is repeated, wait
            unikery.onSetDrawable(null); // TODO Is it cool?
        }
    }

    @UiThread
    public void load(Unikery<V> unikery, Drawable drawable) {
        OSUtils.checkMainLoop();

        // Cancel first
        cancel(unikery);

        unikery.onSetDrawable(drawable);
    }

    @UiThread
    public void cancel(Unikery<V> unikery) {
        OSUtils.checkMainLoop();

        int id = unikery.getTaskId();
        if (id != Unikery.INVALID_ID) {
            unikery.setTaskId(Unikery.INVALID_ID);
            ConacoTask<V> task = mRegister.popById(id);
            if (task != null) {
                task.stop();
                // Don't need unikery anymore
                task.clearUnikery();
                // Callback
                onUnregisterConacoTask(task);
            } else {
                Log.e(TAG, "Can't find conaco task by id " + id);
            }
        }
    }

    /**
     * Unregister task, reset unikery id, clear unikery in task, call next
     */
    void finishConacoTask(ConacoTask<V> task) {
        mRegister.unregister(task.getId());
        Unikery unikery = task.getUnikery();
        if (unikery != null) {
            unikery.setTaskId(Unikery.INVALID_ID);
        }
        task.clearUnikery();

        onUnregisterConacoTask(task);
    }

    public void clearMemoryCache() {
        mCache.clearMemory();
    }

    public void clearDiskCache() {
        mCache.clearDisk();
    }

    public int memoryCacheSize() {
        return mCache.memorySize();
    }

    public long diskCacheSize() {
        return mCache.diskSize();
    }

    public enum Source {
        MEMORY,
        DISK,
        NETWORK
    }

    public static class Builder<T> extends BeerBelly.BeerBellyParams {
        /**
         * The client to get image from internet
         */
        public OkHttpClient okHttpClient = null;

        /**
         * Decode, get size and others
         */
        public ValueHelper<T> objectHelper = null;

        public boolean debug = BuildConfig.DEBUG;

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
