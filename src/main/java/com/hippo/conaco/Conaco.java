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
import android.support.annotation.NonNull;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.httpclient.HttpClient;
import com.hippo.yorozuya.IdIntGenerator;
import com.hippo.yorozuya.OSUtils;
import com.hippo.yorozuya.PriorityThreadFactory;
import com.hippo.yorozuya.SafeSparseArray;
import com.hippo.yorozuya.SerialThreadExecutor;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// TODO Open a thread for disk cache
public class Conaco {

    private static final String TAG = Conaco.class.getSimpleName();

    private DrawableHelper mHelper;
    private DrawableCache mCache;
    private HttpClient mHttpClient;

    private SafeSparseArray<ConacoTask> mLoadTaskMap;

    private final SerialThreadExecutor mDiskTasExecutor;
    private final ThreadPoolExecutor mNetworkExecutor;

    private final IdIntGenerator mIdGenerator;

    private Conaco(Builder builder) {
        mHelper = builder.drawableHelper;
        if (mHelper == null) {
            mHelper = new BitmapDrawableHelper();
        }

        BeerBelly.BeerBellyParams beerBellyParams = new BeerBelly.BeerBellyParams();
        beerBellyParams.hasMemoryCache = builder.hasMemoryCache;
        beerBellyParams.memoryCacheMaxSize = builder.memoryCacheMaxSize;
        beerBellyParams.hasDiskCache = builder.hasDiskCache;
        beerBellyParams.diskCacheDir = builder.diskCacheDir;
        beerBellyParams.diskCacheMaxSize = builder.diskCacheMaxSize;

        mCache = new DrawableCache(beerBellyParams, mHelper);

        mHttpClient = builder.httpClient;

        mLoadTaskMap = new SafeSparseArray<>();

        mDiskTasExecutor = new SerialThreadExecutor(3000, new LinkedBlockingDeque<Runnable>(),
                new PriorityThreadFactory("Conaco-Disk", Process.THREAD_PRIORITY_BACKGROUND));

        mNetworkExecutor = new ThreadPoolExecutor(3, 3, 5L, TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>(),
                new PriorityThreadFactory("Conaco-Network", Process.THREAD_PRIORITY_BACKGROUND));

        mIdGenerator = new IdIntGenerator();
    }

    public void load(@NonNull Unikery unikery, @NonNull String key, @NonNull String url) {
        load(unikery, key, url, null);
    }

    /**
     * @param key null for no cache, but container can't be null
     */
    public void load(@NonNull Unikery unikery, String key, @NonNull String url, DataContainer container) {
        OSUtils.checkMainLoop();

        // Check valid
        if ((key == null && container == null) || (key != null && container != null)) {
            throw new IllegalStateException("Only one in key and container can and must be null");
        }

        cancel(unikery);

        DrawableHolder holder = null;

        if (key != null) {
            holder = mCache.getFromMemory(key);
        }

        if (holder != null) {
            unikery.onGetDrawable(holder, Source.MEMORY);
        } else {
            int id = mIdGenerator.nextId();
            unikery.setTaskId(id);
            ConacoTask task = new ConacoTask.Builder()
                    .setId(id)
                    .setUnikery(unikery)
                    .setKey(key)
                    .setUrl(url)
                    .setDataContainer(container)
                    .setHelper(mHelper)
                    .setCache(mCache)
                    .setHttpClient(mHttpClient)
                    .setDiskExecutor(mDiskTasExecutor)
                    .setNetworkExecutor(mNetworkExecutor)
                    .build();
            mLoadTaskMap.put(id, task);
            task.start();
        }
    }

    public void load(Unikery unikery, Drawable drawable) {
        OSUtils.checkMainLoop();

        cancel(unikery);
        unikery.onGetDrawable(new DrawableHolder(drawable), Source.USER);
    }

    public void cancel(Unikery unikery) {
        OSUtils.checkMainLoop();

        int id = unikery.getTaskId();
        if (id != Unikery.INVAILD_ID) {
            ConacoTask task = mLoadTaskMap.get(id);
            task.stop();
            mLoadTaskMap.remove(id);
            unikery.setTaskId(Unikery.INVAILD_ID);
        }
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
        NETWORK,
        USER
    }

    public static class Builder extends BeerBelly.BeerBellyParams {
        /**
         * The client to get image from internet
         */
        public HttpClient httpClient = null;

        /**
         * Decode, get size and others
         */
        public DrawableHelper drawableHelper = null;


        @Override
        public void isVaild() throws IllegalStateException {
            super.isVaild();

            if (httpClient == null) {
                throw new IllegalStateException("No http client? How can I load image via url?");
            }
        }

        public Conaco build() {
            isVaild();
            return new Conaco(this);
        }
    }
}
