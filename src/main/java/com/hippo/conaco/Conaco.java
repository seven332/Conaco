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
import com.hippo.yorozuya.IdIntGenerator;
import com.hippo.yorozuya.OSUtils;
import com.hippo.yorozuya.PriorityThreadFactory;
import com.hippo.yorozuya.SerialThreadExecutor;
import com.squareup.okhttp.OkHttpClient;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Conaco {

    private static final String TAG = Conaco.class.getSimpleName();

    private ObjectHelper mHelper;
    private ObjectCache mCache;
    private OkHttpClient mOkHttpClient;

    private Register mRegister;

    private final SerialThreadExecutor mDiskTasExecutor;
    private final ThreadPoolExecutor mNetworkExecutor;

    private final IdIntGenerator mIdGenerator;

    private Conaco(Builder builder) {
        mHelper = builder.objectHelper;

        BeerBelly.BeerBellyParams beerBellyParams = new BeerBelly.BeerBellyParams();
        beerBellyParams.hasMemoryCache = builder.hasMemoryCache;
        beerBellyParams.memoryCacheMaxSize = builder.memoryCacheMaxSize;
        beerBellyParams.hasDiskCache = builder.hasDiskCache;
        beerBellyParams.diskCacheDir = builder.diskCacheDir;
        beerBellyParams.diskCacheMaxSize = builder.diskCacheMaxSize;

        mCache = new ObjectCache(beerBellyParams, mHelper);

        mOkHttpClient = builder.okHttpClient;

        mRegister = new Register();

        mDiskTasExecutor = new SerialThreadExecutor(3000, new LinkedBlockingDeque<Runnable>(),
                new PriorityThreadFactory("Conaco-Disk", Process.THREAD_PRIORITY_BACKGROUND));

        mNetworkExecutor = new ThreadPoolExecutor(3, 3, 5L, TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>(),
                new PriorityThreadFactory("Conaco-Network", Process.THREAD_PRIORITY_BACKGROUND));

        mIdGenerator = new IdIntGenerator();
    }

    public BeerBelly getBeerBelly() {
        return mCache;
    }

    @UiThread
    private void onUnregisterConacoTask(ConacoTask task) {
        ConacoTask next = mRegister.getByKey(task.getKey());
        if (next != null) {
            startConacoTask(next);
        }
    }

    @UiThread
    private void startConacoTask(ConacoTask task) {
        Unikery unikery = task.getUnikery();

        // Can't unikery is gone, finish the task now
        if (unikery == null) {
            finishConacoTask(task);
            return;
        }

        String key = task.getKey();
        ObjectHolder holder = null;

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
    public void load(ConacoTask.Builder builder) {
        OSUtils.checkMainLoop();
        builder.isValid();

        Unikery unikery = builder.getUnikery();

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
        ConacoTask task = builder.build();

        if (!mRegister.register(id, task)) {
            startConacoTask(task);
        }/* else {
            // The key is repeated, wait
        }*/
    }

    @UiThread
    public void load(Unikery unikery, Drawable drawable) {
        OSUtils.checkMainLoop();

        // Cancel first
        cancel(unikery);

        unikery.onSetDrawable(drawable);
    }

    @UiThread
    public void cancel(Unikery unikery) {
        OSUtils.checkMainLoop();

        int id = unikery.getTaskId();
        if (id != Unikery.INVAILD_ID) {
            unikery.setTaskId(Unikery.INVAILD_ID);
            ConacoTask task = mRegister.popById(id);
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
    void finishConacoTask(ConacoTask task) {
        mRegister.unregister(task.getId());
        Unikery unikery = task.getUnikery();
        if (unikery != null) {
            unikery.setTaskId(Unikery.INVAILD_ID);
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

    public static class Builder extends BeerBelly.BeerBellyParams {
        /**
         * The client to get image from internet
         */
        public OkHttpClient okHttpClient = null;

        /**
         * Decode, get size and others
         */
        public ObjectHelper objectHelper = null;

        @Override
        public void isVaild() throws IllegalStateException {
            super.isVaild();

            if (okHttpClient == null) {
                throw new IllegalStateException("No http client? How can I load image via url?");
            }
        }

        public Conaco build() {
            isVaild();
            return new Conaco(this);
        }
    }
}
