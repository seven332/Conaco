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

import com.hippo.yorozuya.IOUtils;
import com.hippo.yorozuya.io.InputStreamPipe;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

public class ConacoTask {

    private final int mId;
    private final WeakReference<Unikery> mUnikeryWeakReference;
    private final String mKey;
    private final String mUrl;
    private final DataContainer mDataContainer;
    private boolean mUseMemoryCache;
    private boolean mUseDiskCache;
    private boolean mUseNetwork;
    private final DrawableHelper mHelper;
    private final DrawableCache mCache;
    private final OkHttpClient mOkHttpClient;
    private final Executor mDiskExecutor;
    private final Executor mNetworkExecutor;
    private final Conaco mConaco;

    private DiskLoadTask mDiskLoadTask;
    private NetworkLoadTask mNetworkLoadTask;
    private Call mCall;
    private boolean mStart;
    private volatile boolean mStop;

    private ConacoTask(Builder builder) {
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

    private void onFinishe() {
        if (!mStop) {
            mConaco.removeTask(this);
            Unikery unikery = mUnikeryWeakReference.get();
            if (unikery != null) {
                unikery.setTaskId(Unikery.INVAILD_ID);
            }
        } else  {
            // It is done by Conaco
        }
    }

    public void start() {
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

                mNetworkLoadTask = new NetworkLoadTask();
                mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                return;
            }
        }

        onFinishe();
    }

    public void stop() {
        if (mStop) {
            return;
        }

        mStop = true;

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null && unikery.getTaskId() == mId) {

            if (mDiskLoadTask != null) { // Getting from disk
                mDiskLoadTask.cancel(false);
            } else if (mNetworkLoadTask != null) { // Getting from network
                mNetworkLoadTask.cancel(false);
                if (mCall != null) {
                    mCall.cancel();
                    mCall = null;
                }
            }

            unikery.onCancel();
        }
    }

    private boolean isNotNecessary(AsyncTask asyncTask) {
        Unikery unikery = mUnikeryWeakReference.get();
        return mStop || asyncTask.isCancelled() || unikery == null || unikery.getTaskId() != mId;
    }

    private class DiskLoadTask extends AsyncTask<Void, Void, DrawableHolder> {

        @Override
        protected DrawableHolder doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            } else {
                DrawableHolder holder = null;

                if (mDataContainer != null) {
                    InputStreamPipe isp = mDataContainer.get();
                    if (isp != null) {
                        Drawable drawable = mHelper.decode(isp);
                        if (drawable != null) {
                            holder = new DrawableHolder(drawable);
                        }
                    }
                }

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
        protected void onPostExecute(DrawableHolder holder) {
            if (isCancelled() || mStop) {
                onCancelled(holder);
            } else {
                mDiskLoadTask = null;
                Unikery unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    boolean getDrawable = false;
                    if ((holder == null || !(getDrawable = unikery.onGetDrawable(holder, Conaco.Source.DISK))) && mUseNetwork) {
                        unikery.onMiss(Conaco.Source.DISK);
                        unikery.onRequest();
                        mNetworkLoadTask = new NetworkLoadTask();
                        mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                        return;
                    } else if (!getDrawable) {
                        unikery.onMiss(Conaco.Source.DISK);
                        unikery.onFailure();
                    }
                }
                onFinishe();
            }
        }

        @Override
        protected void onCancelled(DrawableHolder holder) {
            mDiskLoadTask = null;
            onFinishe();
        }
    }

    public class NetworkLoadTask extends AsyncTask<Void, Long, DrawableHolder> implements ProgressNotify {

        @Override
        public void notifyProgress(long singleReceivedSize, long receivedSize, long totalSize) {
            if (!isNotNecessary(this)) {
                publishProgress(singleReceivedSize, receivedSize, totalSize);
            }
        }

        @Override
        protected DrawableHolder doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            }

            DrawableHolder holder;
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
                    holder = new DrawableHolder(null);
                    holder.is = is;
                    holder.notify = this;
                    holder.length = response.body().contentLength();
                    boolean result = mCache.putToDisk(mKey, holder);
                    holder.notify = null;
                    holder.is = null;
                    holder = null;

                    if (result) {
                        // Get drawable from disk cache
                        holder = mCache.getFromDisk(mKey);
                        if (holder != null && mUseMemoryCache && mHelper.useMemoryCache(mKey, holder)) {
                            // Put it to memory
                            mCache.putToMemory(mKey, holder);
                        }
                        return holder;
                    } else {
                        // TODO Remove bad data in disk cache
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
                    HttpUrl requestHttpUrl = request.httpUrl();
                    HttpUrl responseHttpUrl = response.request().httpUrl();
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
                    Drawable drawable = mHelper.decode(isp);
                    if (drawable != null) {
                        holder = new DrawableHolder(drawable);
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
        protected void onPostExecute(DrawableHolder holder) {
            if (isCancelled() || mStop) {
                onCancelled(holder);
            } else {
                mNetworkLoadTask = null;
                Unikery unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    if (holder == null || !unikery.onGetDrawable(holder, Conaco.Source.NETWORK)) {
                        unikery.onFailure();
                    }
                }
                onFinishe();
            }
        }

        @Override
        protected void onCancelled(DrawableHolder holder) {
            mNetworkLoadTask = null;
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

    public static class Builder {

        private int mId;
        private Unikery mUnikery;
        private String mKey;
        private String mUrl;
        private DataContainer mDataContainer;
        private boolean mUseMemoryCache = true;
        private boolean mUseDiskCache = true;
        private boolean mUseNetwork = true;
        private DrawableHelper mHelper;
        private DrawableCache mCache;
        private OkHttpClient mOkHttpClient;
        private Executor mDiskExecutor;
        private Executor mNetworkExecutor;
        private Conaco mConaco;

        public Builder setId(int id) {
            mId = id;
            return this;
        }

        public Builder setUnikery(Unikery unikery) {
            mUnikery = unikery;
            return this;
        }

        public Unikery getUnikery() {
            return mUnikery;
        }

        public Builder setKey(String key) {
            mKey = key;
            return this;
        }

        public String getKey() {
            return mKey;
        }

        public Builder setUrl(String url) {
            mUrl = url;
            return this;
        }

        public Builder setDataContainer(DataContainer dataContainer) {
            mDataContainer = dataContainer;
            return this;
        }

        public Builder setUseMemoryCache(boolean useMemoryCache) {
            mUseMemoryCache = useMemoryCache;
            return this;
        }

        boolean isUseMemoryCache() {
            return mUseMemoryCache;
        }

        public Builder setUseDiskCache(boolean useDiskCache) {
            mUseDiskCache = useDiskCache;
            return this;
        }

        boolean isUseDiskCache() {
            return mUseDiskCache;
        }

        public Builder setUseNetwork(boolean useNetwork) {
            mUseNetwork = useNetwork;
            return this;
        }

        boolean isUseNetwork() {
            return mUseNetwork;
        }

        Builder setHelper(DrawableHelper helper) {
            mHelper = helper;
            return this;
        }

        DrawableHelper getHelper() {
            return mHelper;
        }

        Builder setCache(DrawableCache cache) {
            mCache = cache;
            return this;
        }

        Builder setOkHttpClient(OkHttpClient okHttpClient) {
            mOkHttpClient = okHttpClient;
            return this;
        }

        Builder setDiskExecutor(Executor diskExecutor) {
            mDiskExecutor = diskExecutor;
            return this;
        }

        Builder setNetworkExecutor(Executor networkExecutor) {
            mNetworkExecutor = networkExecutor;
            return this;
        }

        Builder setConaco(Conaco conaco) {
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

        public ConacoTask build() {
            return new ConacoTask(this);
        }
    }
}
