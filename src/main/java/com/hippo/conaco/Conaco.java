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
import android.os.AsyncTask;
import android.os.Process;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.httpclient.HttpClient;
import com.hippo.httpclient.HttpRequest;
import com.hippo.httpclient.HttpResponse;
import com.hippo.yorozuya.IdIntGenerator;
import com.hippo.yorozuya.PriorityThreadFactory;
import com.hippo.yorozuya.SafeSparseArray;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// TODO Open a thread for disk cache
public class Conaco {

    private static final String TAG = Conaco.class.getSimpleName();

    private BitmapCache mCache;
    private HttpClient mHttpClient;

    private SafeSparseArray<LoadTask> mLoadTaskMap;

    private final ThreadPoolExecutor mRequestThreadPool;

    private final IdIntGenerator mIdGenerator;

    private Conaco(Builder builder) {
        BeerBelly.BeerBellyParams beerBellyParams = new BeerBelly.BeerBellyParams();
        beerBellyParams.hasMemoryCache = builder.hasMemoryCache;
        beerBellyParams.memoryCacheMaxSize = builder.memoryCacheMaxSize;
        beerBellyParams.hasDiskCache = builder.hasDiskCache;
        beerBellyParams.diskCacheDir = builder.diskCacheDir;
        beerBellyParams.diskCacheMaxSize = builder.diskCacheMaxSize;
        mCache = new BitmapCache(beerBellyParams);

        mHttpClient = builder.httpClient;

        mLoadTaskMap = new SafeSparseArray<>();

        BlockingQueue<Runnable> requestWorkQueue = new LinkedBlockingDeque<>();
        ThreadFactory threadFactory = new PriorityThreadFactory(TAG,
                Process.THREAD_PRIORITY_BACKGROUND);
        mRequestThreadPool = new ThreadPoolExecutor(3, 3,
                5L, TimeUnit.SECONDS, requestWorkQueue, threadFactory);

        mIdGenerator = new IdIntGenerator();
    }

    public void load(Unikery unikery, String key, String url) {
        cancel(unikery);

        BitmapHolder bitmapHolder = mCache.getFromMemory(key);
        if (bitmapHolder != null) {
            unikery.setBitmap(bitmapHolder, Source.MEMORY);
        } else {
            // Miss in memory cache
            // Set null drawable first
            unikery.setDrawable(null);

            int id = mIdGenerator.nextId();
            unikery.setTaskId(id);
            LoadTask loadTask = new LoadTask(id, unikery, key, url);
            mLoadTaskMap.put(id, loadTask);
            loadTask.executeOnExecutor(mRequestThreadPool);
        }
    }

    public void load(Unikery unikery, Drawable drawable) {
        cancel(unikery);
        unikery.setDrawable(drawable);
    }

    public void cancel(Unikery unikery) {
        int id = unikery.getTaskId();
        if (id != Unikery.INVAILD_ID) {
            LoadTask loadTask = mLoadTaskMap.get(id);
            if (loadTask != null) {
                unikery.onCancel();
                AsyncTask.Status status = loadTask.getStatus();
                if (status == AsyncTask.Status.PENDING) {
                    // The task is pending
                    loadTask.cancel(false);
                    mLoadTaskMap.remove(id);
                    unikery.setTaskId(Unikery.INVAILD_ID);
                } else if (status == AsyncTask.Status.RUNNING) {
                    // The task is running
                    loadTask.stop();
                }
            }
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
        NON_MEMORY
    }

    private class LoadTask extends AsyncTask<Void, Void, BitmapHolder> {

        private int mId;
        private WeakReference<Unikery> mUnikeryWeakReference;
        private String mKey;
        private String mUrl;

        private HttpRequest mRequest;
        private boolean mStop;

        public LoadTask(int id, Unikery unikery, String key, String url) {
            mId = id;
            mUnikeryWeakReference = new WeakReference<>(unikery);
            mKey = key;
            mUrl = url;
        }

        private boolean isNecessary() {
            return !(mStop || mUnikeryWeakReference.get() == null);
        }

        @Override
        protected BitmapHolder doInBackground(Void... params) {
            String key = mKey;
            BitmapHolder bitmapHolder;

            // Is the task necessary
            if (!isNecessary()) {
                return null;
            }

            // Get bitmap from disk
            bitmapHolder = mCache.getFromDisk(key);

            if (bitmapHolder != null) {
                // Put it to memory
                mCache.putToMemory(key, bitmapHolder);
                return bitmapHolder;
            } else {
                // Is the task necessary
                if (!isNecessary()) {
                    return null;
                }

                // Load it from internet
                mRequest = new HttpRequest();

                try {
                    mRequest.setUrl(mUrl);
                    HttpResponse httpResponse = mHttpClient.execute(mRequest);
                    InputStream is = httpResponse.getInputStream();
                    // Put stream itself to disk cache directly
                    if (mCache.putRawToDisk(key, is)) {
                        // Get bitmap from disk cache
                        bitmapHolder = mCache.getFromDisk(key);

                        if (bitmapHolder != null) {
                            // Put it to memory
                            mCache.putToMemory(key, bitmapHolder);
                        }
                        return bitmapHolder;
                    } else {
                        return null;
                    }
                } catch (Exception e) {
                    // Cancel or get trouble
                    return null;
                } finally {
                    mRequest.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(BitmapHolder result) {
            if (!mStop) {
                Unikery unikery = mUnikeryWeakReference.get();
                if (unikery != null) {
                    if (result != null) {
                        unikery.setBitmap(result, Source.NON_MEMORY);
                    } else {
                        unikery.onFailure();
                    }

                    unikery.setTaskId(Unikery.INVAILD_ID);
                }
            }

            mLoadTaskMap.remove(mId);
        }

        public void stop() {
            mStop = true;
            if (mRequest != null) {
                mRequest.disconnect();
            }
        }
    }

    public static class Builder extends BeerBelly.BeerBellyParams {
        /**
         * The client to get image from internet
         */
        public HttpClient httpClient = null;

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
