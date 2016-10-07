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

import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ConacoTask<V> {

    private static final String TAG = ConacoTask.class.getSimpleName();

    private final int mId;
    private final WeakReference<Unikery<V>> mUnikeryWeakReference;
    private final String mKey;
    private final String mUrl;
    private final DataContainer mDataContainer;
    private boolean mUseMemoryCache;
    private boolean mUseDiskCache;
    private boolean mUseNetwork;
    private boolean mSkipDecode;
    private final ValueHelper<V> mHelper;
    private final ValueCache<V> mCache;
    private final OkHttpClient mOkHttpClient;
    private final Executor mDiskExecutor;
    private final Executor mNetworkExecutor;
    private final Conaco<V> mConaco;

    private boolean mDiskMiss;

    private DiskLoadTask mDiskLoadTask;
    private NetworkLoadTask mNetworkLoadTask;
    @NonNull
    private AtomicReference<Call> mCall = new AtomicReference<>();
    private boolean mStart;
    @NonNull
    private AtomicBoolean mStop = new AtomicBoolean();

    private ConacoTask(Builder<V> builder) {
        mId = builder.id;
        mUnikeryWeakReference = new WeakReference<>(builder.unikery);
        mKey = builder.key;
        mUrl = builder.url;
        mDataContainer = builder.dataContainer;
        mUseMemoryCache = builder.useMemoryCache;
        mUseDiskCache = builder.useDiskCache;
        mUseNetwork = builder.useNetwork;
        mSkipDecode = builder.skipDecode;
        mHelper = builder.helper;
        mCache = builder.cache;
        mOkHttpClient = builder.okHttpClient;
        mDiskExecutor = builder.diskExecutor;
        mNetworkExecutor = builder.networkExecutor;
        mConaco = builder.conaco;
    }

    int getId() {
        return mId;
    }

    String getKey() {
        return mKey;
    }

    boolean useMemoryCache() {
        return mUseMemoryCache;
    }

    boolean skipDecode() {
        return mSkipDecode;
    }

    @Nullable
    Unikery<V> getUnikery() {
        return mUnikeryWeakReference.get();
    }

    void clearUnikery() {
        mUnikeryWeakReference.clear();
    }

    // Ui thread
    private void onFinish() {
        if (!mStop.get()) {
            mConaco.finishConacoTask(this);
        }/* else  {
            // It is done by Conaco
        }*/
    }

    // Ui thread
    void start() {
        if (mStop.get() || mStart) {
            return;
        }

        mStart = true;

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null && unikery.getTaskId() == mId) {
            if ((mUseDiskCache && mKey != null) || mDataContainer != null) {
                mDiskLoadTask = new DiskLoadTask();
                mDiskLoadTask.executeOnExecutor(mDiskExecutor);
                return;
            } else {
                // No disk support, no network support
                mDiskMiss = true;
                unikery.onMiss(Conaco.SOURCE_DISK);
                unikery.onMiss(Conaco.SOURCE_NETWORK);
                unikery.onFailure();
            }
        }

        onFinish();
    }

    // Ui thread
    void stop() {
        if (mStop.get()) {
            return;
        }

        mStop.lazySet(true);

        // Stop jobs
        if (mDiskLoadTask != null) { // Getting from disk
            mDiskLoadTask.cancel(false);
        } else if (mNetworkLoadTask != null) { // Getting from network
            mNetworkLoadTask.cancel(false);
            Call call = mCall.get();
            if (call != null) {
                call.cancel();
                mCall.lazySet(null);
            }
        }

        Unikery unikery = mUnikeryWeakReference.get();
        if (unikery != null) {
            // Id of unikery has been set to invalid in Conaco.cancel(),
            // so no need to worry callback called twice.
            if (!mDiskMiss) {
                unikery.onMiss(Conaco.SOURCE_DISK);
            }
            unikery.onMiss(Conaco.SOURCE_MEMORY);
            unikery.onCancel();
        }

        // Conaco handle the clean up
    }

    private boolean isNotNecessary(AsyncTask asyncTask) {
        Unikery unikery = mUnikeryWeakReference.get();
        return mStop.get() || asyncTask.isCancelled() || unikery == null || unikery.getTaskId() != mId;
    }

    private void putFromDiskCacheToDataContainer(String key, ValueCache cache, DataContainer container) {
        SimpleDiskCache diskCache = cache.getDiskCache();
        if (diskCache != null) {
            InputStreamPipe pipe = diskCache.getInputStreamPipe(key);
            if (pipe != null) {
                try {
                    pipe.obtain();
                    container.save(pipe.open(), -1L, null, null);
                } catch (IOException e) {
                    if (mConaco.mDebug) {
                        Log.e(TAG, "Can't save value from disk cache to data container", e);
                    }
                    container.remove();
                } finally {
                    pipe.close();
                    pipe.release();
                }
            }
        }
    }

    private void putFromDataContainerToDiskCache(String key, ValueCache cache, DataContainer container) {
        InputStreamPipe pipe = container.get();
        if (pipe != null) {
            try {
                pipe.obtain();
                cache.pushRawToDisk(key, pipe.open());
            } catch (IOException e) {
                if (mConaco.mDebug) {
                    Log.w(TAG, "Can't save value from data container to disk cache", e);
                }
                cache.removeFromDisk(key);
            } finally {
                pipe.close();
                pipe.release();
            }
        }
    }

    private class DiskLoadTask extends AsyncTask<Void, Void, Object> {

        private V getValue() {
            V value = null;

            // First check data container
            if (mDataContainer != null && mDataContainer.isEnabled()) {
                InputStreamPipe isp = mDataContainer.get();
                if (isp != null) {
                    value = mHelper.decode(isp);
                }
            }

            // Then check disk cache
            if (value == null && mUseDiskCache && mKey != null) {
                value = mCache.getFromDisk(mKey);
                // Put back to data container
                if (value != null && mDataContainer != null && mDataContainer.isEnabled()) {
                    putFromDiskCacheToDataContainer(mKey, mCache, mDataContainer);
                }
            }

            return value;
        }

        private InputStreamPipe getPipe() {
            InputStreamPipe isp = null;

            // First check data container
            if (mDataContainer != null && mDataContainer.isEnabled()) {
                isp = mDataContainer.get();
            }

            // Then check disk cache
            if (isp == null && mUseDiskCache && mKey != null && mCache.hasDiskCache()) {
                isp = mCache.getDiskCache().getInputStreamPipe(mKey);
                // Put back to data container
                if (isp != null && mDataContainer != null && mDataContainer.isEnabled()) {
                    putFromDiskCacheToDataContainer(mKey, mCache, mDataContainer);
                }
            }

            return isp;
        }

        @Override
        protected Object doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            } else if (mSkipDecode) {
                return getPipe();
            } else {
                return getValue();
            }
        }

        private void postValue(V value) {
            // Put value to memory cache
            if (value != null && mKey != null && mUseMemoryCache && mHelper.useMemoryCache(mKey, value)) {
                mCache.putToMemory(mKey, value);
            }

            if (isCancelled() || mStop.get()) {
                onCancelled(value);
            } else {
                Unikery<V> unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    if (value != null) {
                        // Get the value
                        unikery.onGetValue(value, Conaco.SOURCE_DISK);
                        onFinish();
                    } else if (mUseNetwork && mUrl != null &&
                            ((mUseDiskCache && mKey != null) || mDataContainer != null)) {
                        // Try to get value from network
                        mDiskMiss = true;
                        unikery.onMiss(Conaco.SOURCE_DISK);
                        mNetworkLoadTask = new NetworkLoadTask();
                        mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                    } else {
                        // Failed
                        mDiskMiss = true;
                        unikery.onMiss(Conaco.SOURCE_DISK);
                        unikery.onMiss(Conaco.SOURCE_NETWORK);
                        unikery.onFailure();
                        onFinish();
                    }
                }
            }
        }

        private void postPipe(InputStreamPipe pipe) {
            if (isCancelled() || mStop.get()) {
                onCancelled(pipe);
            } else {
                Unikery<V> unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    if (pipe != null) {
                        // Get the pipe
                        unikery.onGetPipe(pipe);
                        onFinish();
                    } else if (mUseNetwork && mUrl != null &&
                            ((mUseDiskCache && mKey != null) || mDataContainer != null)) {
                        // Try to get value from network
                        mDiskMiss = true;
                        unikery.onMiss(Conaco.SOURCE_DISK);
                        mNetworkLoadTask = new NetworkLoadTask();
                        mNetworkLoadTask.executeOnExecutor(mNetworkExecutor);
                    } else {
                        // Failed
                        mDiskMiss = true;
                        unikery.onMiss(Conaco.SOURCE_DISK);
                        unikery.onMiss(Conaco.SOURCE_NETWORK);
                        unikery.onFailure();
                        onFinish();
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(Object obj) {
            mDiskLoadTask = null;
            if (mSkipDecode) {
                postPipe((InputStreamPipe) obj);
            } else {
                postValue((V) obj);
            }
        }

        @Override
        protected void onCancelled(Object obj) {
            onFinish();
        }
    }

    private class NetworkLoadTask extends AsyncTask<Void, Long, Object> implements ProgressNotifier {

        @Override
        public void notifyProgress(long singleReceivedSize, long receivedSize, long totalSize) {
            if (!isNotNecessary(this)) {
                publishProgress(singleReceivedSize, receivedSize, totalSize);
            }
        }

        private boolean putToDiskCache(InputStream is, long length) {
            SimpleDiskCache diskCache = mCache.getDiskCache();
            if (diskCache == null) {
                return false;
            }

            OutputStreamPipe pipe = diskCache.getOutputStreamPipe(mKey);
            try {
                pipe.obtain();
                OutputStream os = pipe.open();

                final byte buffer[] = new byte[1024 * 4];
                long receivedSize = 0;
                int bytesRead;

                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    receivedSize += bytesRead;
                    notifyProgress((long) bytesRead, receivedSize, length);
                }

                return true;
            } catch (IOException e) {
                if (mConaco.mDebug) {
                    Log.e(TAG, "Failed to write to disk cache", e);
                }
                return false;
            } finally {
                pipe.close();
                pipe.release();
            }
        }

        private boolean putToDataContainer(InputStream is, ResponseBody body) {
            // Get media type
            String mediaType;
            MediaType mt = body.contentType();
            if (mt != null) {
                mediaType = mt.type() + '/' + mt.subtype();
            } else {
                mediaType = null;
            }
            return mDataContainer.save(is, body.contentLength(), mediaType, this);
        }

        @Override
        protected Object doInBackground(Void... params) {
            if (isNotNecessary(this)) {
                return null;
            }

            InputStream is = null;
            try {
                // Load it from internet
                Request request = new Request.Builder().url(mUrl).build();
                Call call = mOkHttpClient.newCall(request);
                mCall.lazySet(call);

                Response response = call.execute();
                ResponseBody body = response.body();
                is = body.byteStream();

                if (isNotNecessary(this)) {
                    return null;
                }

                if (mDataContainer != null && mDataContainer.isEnabled()) {
                    // Check url Moved
                    HttpUrl requestHttpUrl = request.url();
                    HttpUrl responseHttpUrl = response.request().url();
                    if (!responseHttpUrl.equals(requestHttpUrl)) {
                        mDataContainer.onUrlMoved(mUrl, responseHttpUrl.url().toString());
                    }

                    // Put to data container
                    if (!putToDataContainer(is, body)) {
                        mDataContainer.remove();
                        return null;
                    }

                    // Get value from data container
                    InputStreamPipe isp = mDataContainer.get();
                    if (isp == null) {
                        return null;
                    }

                    if (mSkipDecode) {
                        // Need InputStreamPipe
                        return isp;
                    } else {
                        // Need value
                        V value = mHelper.decode(isp);
                        if (value == null) {
                            mDataContainer.remove();
                        } else if (mUseDiskCache && mKey != null) {
                            // Put to disk cache
                            putFromDataContainerToDiskCache(mKey, mCache, mDataContainer);
                        }
                        return value;
                    }
                } else if (mUseDiskCache && mKey != null) {
                    if (putToDiskCache(is, body.contentLength())) {
                        if (mSkipDecode) {
                            // Need InputStreamPipe
                            return mCache.getDiskCache().getInputStreamPipe(mKey);
                        } else {
                            // Need value
                            // Get object from disk cache
                            V value = mCache.getFromDisk(mKey);
                            if (value == null) {
                                // Maybe bad download, remove it from disk cache
                                mCache.removeFromDisk(mKey);
                            }
                            return value;
                        }
                    } else {
                        // Maybe bad download, remove it from disk cache
                        mCache.removeFromDisk(mKey);
                        return null;
                    }
                } else {
                    return null;
                }
            } catch (Exception e) {
                if (mConaco.mDebug) {
                    e.printStackTrace();
                }
                return null;
            } finally {
                mCall.lazySet(null);
                try {
                    if (is != null) {
                        is.close();
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        private void postValue(V value) {
            // Put value to memory cache
            if (value != null && mKey != null && mUseMemoryCache && mHelper.useMemoryCache(mKey, value)) {
                mCache.putToMemory(mKey, value);
            }

            if (isCancelled() || mStop.get()) {
                onCancelled(value);
            } else {
                Unikery<V> unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    if (value != null) {
                        unikery.onGetValue(value, Conaco.SOURCE_NETWORK);
                    } else {
                        unikery.onMiss(Conaco.SOURCE_NETWORK);
                        unikery.onFailure();
                    }
                }
                onFinish();
            }
        }

        private void postPipe(InputStreamPipe pipe) {
            if (isCancelled() || mStop.get()) {
                onCancelled(pipe);
            } else {
                Unikery<V> unikery = mUnikeryWeakReference.get();
                if (unikery != null && unikery.getTaskId() == mId) {
                    if (pipe != null) {
                        unikery.onGetPipe(pipe);
                    } else {
                        unikery.onMiss(Conaco.SOURCE_NETWORK);
                        unikery.onFailure();
                    }
                }
                onFinish();
            }
        }

        @Override
        protected void onPostExecute(Object obj) {
            mNetworkLoadTask = null;
            if (mSkipDecode) {
                postPipe((InputStreamPipe) obj);
            } else {
                postValue((V) obj);
            }
        }

        @Override
        protected void onCancelled(Object value) {
            onFinish();
        }

        @Override
        protected void onProgressUpdate(Long... values) {
            Unikery unikery = mUnikeryWeakReference.get();
            if (!mStop.get() && !isCancelled() && unikery != null && unikery.getTaskId() == mId) {
                unikery.onProgress(values[0], values[1], values[2]);
            }
        }
    }

    public static class Builder<T> {

        int id;
        Conaco<T> conaco;
        public Unikery<T> unikery;
        public String key;
        public String url;
        public DataContainer dataContainer;
        public boolean useMemoryCache = true;
        public boolean useDiskCache = true;
        public boolean useNetwork = true;
        /**
         * {@code false} for call {@link Unikery#onGetValue(Object, int)},
         * {@code true} for call {@link Unikery#onGetPipe(InputStreamPipe)}.
         * Default value is false.
         */
        public boolean skipDecode;
        public ValueHelper<T> helper;
        public ValueCache<T> cache;
        public OkHttpClient okHttpClient;
        public Executor diskExecutor;
        public Executor networkExecutor;

        public void isValid() {
            if (unikery == null) {
                throw new IllegalStateException("Must set unikery");
            }
            if (key == null && url == null && dataContainer == null) {
                throw new IllegalStateException("At least one of mKey and mUrl and mDataContainer have to not be null");
            }
        }

        public ConacoTask<T> build() {
            return new ConacoTask<>(this);
        }
    }
}
