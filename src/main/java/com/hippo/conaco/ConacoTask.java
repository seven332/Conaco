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

import com.hippo.httpclient.HttpClient;
import com.hippo.httpclient.HttpRequest;
import com.hippo.httpclient.HttpResponse;
import com.hippo.yorozuya.IOUtils;
import com.hippo.yorozuya.io.InputStreamPipe;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.zip.GZIPInputStream;

public class ConacoTask {

    private final int mId;
    private final WeakReference<Unikery> mUnikeryWeakReference;
    private final String mKey;
    private final String mUrl;
    private final DataContainer mDataContainer;
    private final DrawableHelper mHelper;
    private final DrawableCache mCache;
    private final HttpClient mHttpClient;
    private final Executor mDiskExecutor;
    private final Executor mNetworkExecutor;
    private final Conaco mConaco;

    private DiskLoadTask mDiskLoadTask;
    private NetworkLoadTask mNetworkLoadTask;
    private HttpRequest mRequest;
    private boolean mStart;
    private volatile boolean mStop;

    private ConacoTask(Builder builder) {
        mId = builder.mId;
        mUnikeryWeakReference = new WeakReference<>(builder.mUnikery);
        mKey = builder.mKey;
        mUrl = builder.mUrl;
        mDataContainer = builder.mDataContainer;
        mHelper = builder.mHelper;
        mCache = builder.mCache;
        mHttpClient = builder.mHttpClient;
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
            unikery.onStart();

            if (mKey != null) {
                mDiskLoadTask = new DiskLoadTask();
                mDiskLoadTask.executeOnExecutor(mDiskExecutor);
            } else {
                unikery.onRequest();
                mNetworkLoadTask = new NetworkLoadTask();
                mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
            }
        } else {
            onFinishe();
        }
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
                if (mRequest != null) {
                    mRequest.cancel();
                    mRequest = null;
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
                return mCache.getFromDisk(mKey);
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
                    if (holder == null || !unikery.onGetDrawable(holder, Conaco.Source.DISK)) {
                        unikery.onRequest();
                        mNetworkLoadTask = new NetworkLoadTask();
                        mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                        return;
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
            mRequest = new HttpRequest();
            try {
                mRequest.setUrl(mUrl);
                HttpResponse httpResponse = mHttpClient.execute(mRequest);
                is = httpResponse.getInputStream();
                String contentEncoding = httpResponse.getContentEncoding();
                if (contentEncoding != null && contentEncoding.trim().toLowerCase().equals("gzip")) {
                    is = new GZIPInputStream(is);
                }

                if (isNotNecessary(this)) {
                    return null;
                }

                if (mKey != null) {
                    // It is a trick to call onProgress
                    holder = new DrawableHolder(null);
                    holder.is = is;
                    holder.notify = this;
                    holder.length = httpResponse.getContentLength();
                    boolean result = mCache.putToDisk(mKey, holder);
                    holder.notify = null;
                    holder.is = null;
                    holder = null;

                    if (result) {
                        // Get drawable from disk cache
                        holder = mCache.getFromDisk(mKey);
                        if (holder != null) {
                            // Put it to memory
                            mCache.putToMemory(mKey, holder);
                        }
                        return holder;
                    } else {
                        // TODO Remove bad data in disk cache
                        return null;
                    }
                } else {
                    if (!mDataContainer.save(is, this)) {
                        return null;
                    }
                    InputStreamPipe isp = mDataContainer.get();
                    if (isp == null) {
                        return null;
                    }
                    Drawable drawable = mHelper.decode(isp);
                    if (drawable == null) {
                        return null;
                    }
                    return new DrawableHolder(drawable);
                }
            } catch (Exception e) {
                // Cancel or get trouble
                return null;
            } finally {
                IOUtils.closeQuietly(is);
                mRequest.disconnect();
                mRequest = null;
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
        private DrawableHelper mHelper;
        private DrawableCache mCache;
        private HttpClient mHttpClient;
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

        Builder setHttpClient(HttpClient httpClient) {
            mHttpClient = httpClient;
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
            if (mUrl == null) {
                throw new IllegalStateException("Must set key");
            }
            if ((mKey == null && mDataContainer == null) || (mKey != null && mDataContainer != null)) {
                throw new IllegalStateException("Only one in key and container can and must be null");
            }
        }

        public ConacoTask build() {
            return new ConacoTask(this);
        }
    }
}
