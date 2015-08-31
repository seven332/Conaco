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
import com.hippo.yorozuya.io.InputStreamPipe;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

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

    private DiskLoadTask mDiskLoadTask;
    private NetworkLoadTask mNetworkLoadTask;
    private HttpRequest mRequest;
    private boolean mStart;
    private volatile boolean mStop;

    private ConacoTask(Builder builder) {
        mId = builder.mId;
        mUnikeryWeakReference = builder.mUnikeryWeakReference;
        mKey = builder.mKey;
        mUrl = builder.mUrl;
        mDataContainer = builder.mDataContainer;
        mHelper = builder.mHelper;
        mCache = builder.mCache;
        mHttpClient = builder.mHttpClient;
        mDiskExecutor = builder.mDiskExecutor;
        mNetworkExecutor = builder.mNetworkExecutor;
    }

    public void start() {
        if (mStop || mStart) {
            return;
        }

        mStart = true;

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null) {
            unikery.onStart();

            if (mKey != null) {
                mDiskLoadTask = new DiskLoadTask();
                mDiskLoadTask.executeOnExecutor(mDiskExecutor);
            } else {
                unikery.onRequest();
                mNetworkLoadTask = new NetworkLoadTask();
                mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
            }
        }
    }

    public void stop() {
        if (mStop) {
            return;
        }

        mStop = true;

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null) {
            unikery.onCancel();

            if (mDiskLoadTask != null) { // Getting from disk
                mDiskLoadTask.cancel(false);
            } else if (mNetworkLoadTask != null) { // Getting from network
                mNetworkLoadTask.cancel(false);
                if (mRequest != null) {
                    mRequest.cancel();
                    mRequest = null;
                }
            }
        }
    }

    private boolean isNotNecessary(AsyncTask asyncTask) {
        return mStop || asyncTask.isCancelled() || mUnikeryWeakReference.get() == null;
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
                if (unikery != null) {
                    if (holder != null) {
                        unikery.onGetDrawable(holder, Conaco.Source.DISK);
                    } else {
                        unikery.onRequest();
                        mNetworkLoadTask = new NetworkLoadTask();
                        mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                    }
                }
            }
        }

        @Override
        protected void onCancelled(DrawableHolder holder) {
            mDiskLoadTask = null;
        }
    }

    private class NetworkLoadTask extends AsyncTask<Void, Void, DrawableHolder> {

        @Override
        protected DrawableHolder doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            }

            DrawableHolder holder;
            // Load it from internet
            mRequest = new HttpRequest();
            try {
                mRequest.setUrl(mUrl);
                HttpResponse httpResponse = mHttpClient.execute(mRequest);
                InputStream is = httpResponse.getInputStream();
                if (mKey != null) {
                    // Put stream itself to disk cache directly
                    if (mCache.putRawToDisk(mKey, is)) {
                        // Get drawable from disk cache
                        holder = mCache.getFromDisk(mKey);
                        if (holder != null) {
                            // Put it to memory
                            mCache.putToMemory(mKey, holder);
                        }
                        return holder;
                    } else {
                        return null;
                    }
                } else {
                    if (!mDataContainer.save(is)) {
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
                if (unikery != null) {
                    if (holder != null) {
                        unikery.onGetDrawable(holder, Conaco.Source.NETWORK);
                    } else {
                        unikery.onFailure();
                    }
                }
            }
        }

        @Override
        protected void onCancelled(DrawableHolder holder) {
            mNetworkLoadTask = null;
        }
    }

    public static class Builder {

        private int mId;
        private WeakReference<Unikery> mUnikeryWeakReference;
        private String mKey;
        private String mUrl;
        private DataContainer mDataContainer;
        private DrawableHelper mHelper;
        private DrawableCache mCache;
        private HttpClient mHttpClient;
        private Executor mDiskExecutor;
        private Executor mNetworkExecutor;

        public Builder setId(int id) {
            mId = id;
            return this;
        }

        public Builder setUnikery(Unikery unikery) {
            mUnikeryWeakReference = new WeakReference<>(unikery);
            return this;
        }

        public Builder setKey(String key) {
            mKey = key;
            return this;
        }

        public Builder setUrl(String url) {
            mUrl = url;
            return this;
        }

        public Builder setDataContainer(DataContainer dataContainer) {
            mDataContainer = dataContainer;
            return this;
        }

        public Builder setHelper(DrawableHelper helper) {
            mHelper = helper;
            return this;
        }

        public Builder setCache(DrawableCache cache) {
            mCache = cache;
            return this;
        }

        public Builder setHttpClient(HttpClient httpClient) {
            mHttpClient = httpClient;
            return this;
        }

        public Builder setDiskExecutor(Executor diskExecutor) {
            mDiskExecutor = diskExecutor;
            return this;
        }

        public Builder setNetworkExecutor(Executor networkExecutor) {
            mNetworkExecutor = networkExecutor;
            return this;
        }

        public ConacoTask build() {
            return new ConacoTask(this);
        }
    }
}
