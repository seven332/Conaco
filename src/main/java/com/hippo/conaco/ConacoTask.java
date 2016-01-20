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

import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;

import com.hippo.yorozuya.IOUtils;
import com.hippo.yorozuya.io.InputStreamPipe;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ConacoTask<V> {

    private final int mId;
    private final WeakReference<Unikery<V>> mUnikeryWeakReference;
    private final String mKey;
    private final String mUrl;
    private final DataContainer mDataContainer;
    private boolean mUseMemoryCache;
    private boolean mUseDiskCache;
    private boolean mUseNetwork;
    private final ValueHelper<V> mHelper;
    private final ValueCache<V> mCache;
    private final OkHttpClient mOkHttpClient;
    private final Executor mDiskExecutor;
    private final Executor mNetworkExecutor;
    private final Conaco<V> mConaco;

    private DiskLoadTask mDiskLoadTask;
    private NetworkLoadTask mNetworkLoadTask;
    private Call mCall;
    private boolean mStart;
    private volatile boolean mStop;

    private ConacoTask(Builder<V> builder) {
        mId = builder.mId;
        mUnikeryWeakReference = new WeakReference<>(builder.mUnikery);
        mKey = builder.mKey;
        mUrl = builder.mUrl;
        mDataContainer = builder.mDataContainer;
        mUseMemoryCache = builder.mUseMemoryCache;
        mUseDiskCache = builder.mUseDiskCache;
        mUseNetwork = builder.mUseNetwork;
        mHelper = builder.mHelper;
        mCache = builder.mCache;
        mOkHttpClient = builder.mOkHttpClient;
        mDiskExecutor = builder.mDiskExecutor;
        mNetworkExecutor = builder.mNetworkExecutor;
        mConaco = builder.mConaco;
    }

    int getId() {
        return mId;
    }

    String getKey() {
        return mKey;
    }

    boolean isUseMemoryCache() {
        return mUseMemoryCache;
    }

    @Nullable
    Unikery<V> getUnikery() {
        return mUnikeryWeakReference.get();
    }

    void clearUnikery() {
        mUnikeryWeakReference.clear();
    }

    private void onFinishe() {
        if (!mStop) {
            mConaco.finishConacoTask(this);
        }/* else  {
            // It is done by Conaco
        }*/
    }

    @UiThread
    void start() {
        if (mStop || mStart) {
            return;
        }

        mStart = true;

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null && unikery.getTaskId() == mId) {
            if (mUseDiskCache) {
                mDiskLoadTask = new DiskLoadTask();
                mDiskLoadTask.executeOnExecutor(mDiskExecutor);
                return;
            } else if (mUseNetwork) {
                unikery.onMiss(Conaco.Source.DISK);
                unikery.onRequest();
                mNetworkLoadTask = new NetworkLoadTask();
                mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                return;
            } else {
                unikery.onMiss(Conaco.Source.DISK);
                unikery.onMiss(Conaco.Source.NETWORK);
                unikery.onFailure();
            }
        }

        onFinishe();
    }

    @UiThread
    void stop() {
        if (mStop) {
            return;
        }

        mStop = true;

        // Stop jobs
        if (mDiskLoadTask != null) { // Getting from disk
            mDiskLoadTask.cancel(false);
        } else if (mNetworkLoadTask != null) { // Getting from network
            mNetworkLoadTask.cancel(false);
            if (mCall != null) {
                mCall.cancel();
                mCall = null;
            }
        }

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null) {
            unikery.onCancel();
        }

        // Conaco handle the clean up
    }

    private boolean isNotNecessary(AsyncTask asyncTask) {
        Unikery unikery = mUnikeryWeakReference.get();
        return mStop || asyncTask.isCancelled() || unikery == null || unikery.getTaskId() != mId;
    }

    private class DiskLoadTask extends AsyncTask<Void, Void, ValueHolder<V>> {

        @Override
        protected ValueHolder<V> doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            } else {
                ValueHolder<V> holder = null;

                // First check data container
                if (mDataContainer != null) {
                    InputStreamPipe isp = mDataContainer.get();
                    if (isp != null) {
                        V value = mHelper.decode(isp);
                        if (value != null) {
                            holder = new ValueHolder<>(value);
                        }
                    }
                }

                // Then check disk cache
                if (mKey != null) {
                    if (holder == null && mUseDiskCache) {
                        holder = mCache.getFromDisk(mKey);
                    }

                    if (holder != null && mUseMemoryCache && mHelper.useMemoryCache(mKey, holder)) {
                        // Put it to memory
                        mCache.putToMemory(mKey, holder);
                    }
                }

                return holder;
            }
        }

        @Override
        protected void onPostExecute(ValueHolder<V> holder) {
            mDiskLoadTask = null;

            if (isCancelled() || mStop) {
                onCancelled(holder);
            } else {
                Unikery<V> unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    boolean getValue = false;
                    if ((holder == null || !(getValue = unikery.onGetObject(holder, Conaco.Source.DISK))) && mUseNetwork) {
                        unikery.onMiss(Conaco.Source.DISK);
                        unikery.onRequest();
                        mNetworkLoadTask = new NetworkLoadTask();
                        mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                        return;
                    } else if (!getValue) {
                        unikery.onMiss(Conaco.Source.DISK);
                        unikery.onFailure();
                    }
                }
                onFinishe();
            }
        }

        @Override
        protected void onCancelled(ValueHolder<V> holder) {
            onFinishe();
        }
    }

    public class NetworkLoadTask extends AsyncTask<Void, Long, ValueHolder<V>> implements ProgressNotify {

        @Override
        public void notifyProgress(long singleReceivedSize, long receivedSize, long totalSize) {
            if (!isNotNecessary(this)) {
                publishProgress(singleReceivedSize, receivedSize, totalSize);
            }
        }

        @Override
        protected ValueHolder<V> doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            }

            ValueHolder<V> holder;
            InputStream is = null;
            // Load it from internet
            Request request = new Request.Builder().url(mUrl).build();
            mCall = mOkHttpClient.newCall(request);
            try {
                Response response = mCall.execute();
                is = response.body().byteStream();

                if (isNotNecessary(this)) {
                    return null;
                }

                if (mDataContainer == null && mKey != null) {
                    // It is a trick to call onProgress
                    //noinspection ConstantConditions
                    holder = new ValueHolder<>(null);
                    holder.is = is;
                    holder.notify = this;
                    holder.length = response.body().contentLength();
                    boolean result = mCache.putToDisk(mKey, holder);
                    holder.notify = null;
                    holder.is = null;

                    if (result) {
                        // Get object from disk cache
                        holder = mCache.getFromDisk(mKey);
                        if (holder != null && mUseMemoryCache && mHelper.useMemoryCache(mKey, holder)) {
                            // Put it to memory
                            mCache.putToMemory(mKey, holder);
                        }
                        return holder;
                    } else {
                        // Maybe bad download, remove it from disk cache
                        mCache.removeFromDisk(mKey);
                        return null;
                    }
                } else if (mDataContainer != null) {
                    ResponseBody body = response.body();
                    String mediaType;
                    MediaType mt = body.contentType();
                    if (mt != null) {
                        mediaType = mt.type() + '/' + mt.subtype();
                    } else {
                        mediaType = null;
                    }

                    // Check url Moved
                    HttpUrl requestHttpUrl = request.url();
                    HttpUrl responseHttpUrl = response.request().url();
                    if (!responseHttpUrl.equals(requestHttpUrl)) {
                        mDataContainer.onUrlMoved(mUrl, responseHttpUrl.url().toString());
                    }

                    if (!mDataContainer.save(is, body.contentLength(), mediaType, this)) {
                        return null;
                    }
                    InputStreamPipe isp = mDataContainer.get();
                    if (isp == null) {
                        return null;
                    }
                    V value = mHelper.decode(isp);
                    if (value != null) {
                        holder = new ValueHolder<>(value);
                        if (mKey != null && mUseMemoryCache && mHelper.useMemoryCache(mKey, holder)) {
                            // Put it to memory
                            mCache.putToMemory(mKey, holder);
                        }
                        return holder;
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } catch (Exception e) {
                // Cancel or get trouble
                return null;
            } finally {
                IOUtils.closeQuietly(is);
            }
        }

        @Override
        protected void onPostExecute(ValueHolder<V> holder) {
            mNetworkLoadTask = null;

            if (isCancelled() || mStop) {
                onCancelled(holder);
            } else {
                Unikery<V> unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    if (holder == null || !unikery.onGetObject(holder, Conaco.Source.NETWORK)) {
                        unikery.onFailure();
                    }
                }
                onFinishe();
            }
        }

        @Override
        protected void onCancelled(ValueHolder<V> holder) {
            onFinishe();
        }

        @Override
        protected void onProgressUpdate(Long... values) {
            Unikery unikery = mUnikeryWeakReference.get();
            if (!mStop && !isCancelled() && unikery != null && unikery.getTaskId() == mId) {
                unikery.onProgress(values[0], values[1], values[2]);
            }
        }
    }

    public static class Builder<T> {

        private int mId;
        private Unikery<T> mUnikery;
        private String mKey;
        private String mUrl;
        private DataContainer mDataContainer;
        private boolean mUseMemoryCache = true;
        private boolean mUseDiskCache = true;
        private boolean mUseNetwork = true;
        private ValueHelper<T> mHelper;
        private ValueCache<T> mCache;
        private OkHttpClient mOkHttpClient;
        private Executor mDiskExecutor;
        private Executor mNetworkExecutor;
        private Conaco<T> mConaco;

        public Builder<T> setId(int id) {
            mId = id;
            return this;
        }

        public Builder<T> setUnikery(Unikery<T> unikery) {
            mUnikery = unikery;
            return this;
        }

        public Unikery<T> getUnikery() {
            return mUnikery;
        }

        public Builder<T> setKey(String key) {
            mKey = key;
            return this;
        }

        public String getKey() {
            return mKey;
        }

        public Builder<T> setUrl(String url) {
            mUrl = url;
            return this;
        }

        public String getUrl() {
            return mUrl;
        }

        public Builder<T> setDataContainer(DataContainer dataContainer) {
            mDataContainer = dataContainer;
            return this;
        }

        public Builder<T> setUseMemoryCache(boolean useMemoryCache) {
            mUseMemoryCache = useMemoryCache;
            return this;
        }

        boolean isUseMemoryCache() {
            return mUseMemoryCache;
        }

        public Builder<T> setUseDiskCache(boolean useDiskCache) {
            mUseDiskCache = useDiskCache;
            return this;
        }

        boolean isUseDiskCache() {
            return mUseDiskCache;
        }

        public Builder<T> setUseNetwork(boolean useNetwork) {
            mUseNetwork = useNetwork;
            return this;
        }

        boolean isUseNetwork() {
            return mUseNetwork;
        }

        Builder<T> setHelper(ValueHelper<T> helper) {
            mHelper = helper;
            return this;
        }

        ValueHelper<T> getHelper() {
            return mHelper;
        }

        Builder<T> setCache(ValueCache<T> cache) {
            mCache = cache;
            return this;
        }

        Builder<T> setOkHttpClient(OkHttpClient okHttpClient) {
            mOkHttpClient = okHttpClient;
            return this;
        }

        Builder<T> setDiskExecutor(Executor diskExecutor) {
            mDiskExecutor = diskExecutor;
            return this;
        }

        Builder<T> setNetworkExecutor(Executor networkExecutor) {
            mNetworkExecutor = networkExecutor;
            return this;
        }

        Builder<T> setConaco(Conaco<T> conaco) {
            mConaco = conaco;
            return this;
        }

        public void isValid() {
            if (mUnikery == null) {
                throw new IllegalStateException("Must set unikery");
            }
            if (mKey == null && mUrl == null && mDataContainer == null) {
                throw new IllegalStateException("At least one of mKey and mUrl and mDataContainer have to not be null");
            }
        }

        public ConacoTask<T> build() {
            return new ConacoTask<>(this);
        }
    }
}
