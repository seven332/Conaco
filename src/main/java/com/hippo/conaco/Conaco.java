package com.hippo.conaco;

import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Process;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.conaco.util.IntIdGenerator;
import com.hippo.conaco.util.PriorityThreadFactory;
import com.hippo.conaco.util.SafeSparseArray;
import com.hippo.conaco.util.Utils;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// TODO Open a thread for disk cache
public class Conaco {

    private static final String TAG = Conaco.class.getSimpleName();

    private BitmapCache mCache;
    private OkHttpClient mHttpClient;

    private SafeSparseArray<LoadTask> mLoadTaskMap;

    private final ThreadPoolExecutor mRequestThreadPool;

    private final IntIdGenerator mIdGenerator;

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

        BlockingQueue<Runnable> requestWorkQueue = new LinkedBlockingQueue<>();
        ThreadFactory threadFactory = new PriorityThreadFactory(TAG,
                Process.THREAD_PRIORITY_BACKGROUND);
        mRequestThreadPool = new ThreadPoolExecutor(3, 3,
                5L, TimeUnit.SECONDS, requestWorkQueue, threadFactory);

        mIdGenerator = IntIdGenerator.create();
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

    public enum Source {
        MEMORY,
        NON_MEMORY
    }

    private class LoadTask extends AsyncTask<Void, Void, BitmapHolder> {

        private int mId;
        private WeakReference<Unikery> mUnikeryWeakReference;
        private String mKey;
        private String mUrl;

        private Call mCall;
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
                Request request = new Request.Builder()
                        .get()
                        .url(mUrl)
                        .build();
                Call call = mHttpClient.newCall(request);
                mCall = call;

                try {
                    Response response = call.execute();
                    InputStream is = response.body().byteStream();
                    // Put stream itself to disk cache directly
                    mCache.putRawToDisk(key, is);
                    Utils.closeQuietly(is);
                    // Get bitmap from disk cache
                    bitmapHolder = mCache.getFromDisk(key);

                    if (bitmapHolder != null) {
                        // Put it to memory
                        mCache.putToMemory(key, bitmapHolder);
                    }
                    return bitmapHolder;
                } catch (IOException e) {
                    // Cancel or get trouble
                    return null;
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
            if (mCall != null) {
                mCall.cancel();
            }
        }
    }

    public static class Builder extends BeerBelly.BeerBellyParams {
        /**
         * The client to get image from internet
         */
        public OkHttpClient httpClient = null;

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
