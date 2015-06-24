package com.hippo.conaco;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import com.hippo.beerbelly.BeerBelly;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

class BitmapCache extends BeerBelly<Bitmap> {

    /**
     * Compression settings when writing images to disk cache
     */
    private static final Bitmap.CompressFormat COMPRESS_FORMAT = Bitmap.CompressFormat.JPEG;

    /**
     * Image compression quality
     */
    private static final int COMPRESS_QUALITY = 98;

    private final Set<WeakReference<Bitmap>> mReusableBitmapSet = new LinkedHashSet<>();

    public BitmapCache(BeerBellyParams params) {
        super(params);
    }

    @Override
    protected int sizeOf(String key, Bitmap value) {
        return value.getByteCount();
    }

    @Override
    protected void memoryEntryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
        if (oldValue != null) {
            addReusableBitmap(oldValue);
        }
    }

    @Override
    protected Bitmap read(InputStreamHelper ish) {
        final BitmapFactory.Options options = new BitmapFactory.Options();

        options.inJustDecodeBounds = true;

        try {
            InputStream is = ish.open();
            BitmapFactory.decodeStream(is, null, options);
        } finally {
            ish.close();
        }

        // Check out size
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }

        options.inJustDecodeBounds = false;
        options.inMutable = true;
        options.inSampleSize = 1;
        // Reuse bitmap
        synchronized (mReusableBitmapSet) {
            final Iterator<WeakReference<Bitmap>> iterator = mReusableBitmapSet.iterator();
            Bitmap item;
            while (iterator.hasNext()) {
                item = iterator.next().get();
                if (item != null && item.isMutable()) {
                    if (canUseForInBitmap(item, options)) {
                        options.inBitmap = item;
                        // Remove from reusable set so it can't be used again.
                        iterator.remove();
                        break;
                    }
                } else {
                    // Remove from the set if the reference has been cleared or
                    // it can't be used.
                    iterator.remove();
                }
            }
        }

        try {
            InputStream is = ish.open();
            return BitmapFactory.decodeStream(is, null, options);
        } finally {
            ish.close();
        }
    }

    @Override
    protected boolean write(OutputStream os, Bitmap value) {
        return value.compress(COMPRESS_FORMAT, COMPRESS_QUALITY, os);
    }

    private void addReusableBitmap(Bitmap bitmap) {
        mReusableBitmapSet.add(new WeakReference<>(bitmap));
    }

    private static boolean canUseForInBitmap(
            Bitmap candidate, BitmapFactory.Options targetOptions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // From Android 4.4 (KitKat) onward we can re-use if the byte size of
            // the new bitmap is smaller than the reusable bitmap candidate
            // allocation byte count.
            int width = targetOptions.outWidth / targetOptions.inSampleSize;
            int height = targetOptions.outHeight / targetOptions.inSampleSize;
            int byteCount = width * height * getBytesPerPixel(candidate.getConfig());
            return byteCount <= candidate.getAllocationByteCount();
        } else {
            // On earlier versions, the dimensions must match exactly and the inSampleSize must be 1
            return candidate.getWidth() == targetOptions.outWidth
                    && candidate.getHeight() == targetOptions.outHeight
                    && targetOptions.inSampleSize == 1;
        }
    }

    /**
     * A helper function to return the byte usage per pixel of a bitmap based on its configuration.
     */
    private static int getBytesPerPixel(Bitmap.Config config) {
        if (config == Bitmap.Config.ARGB_8888) {
            return 4;
        } else if (config == Bitmap.Config.RGB_565) {
            return 2;
        } else if (config == Bitmap.Config.ARGB_4444) {
            return 2;
        } else if (config == Bitmap.Config.ALPHA_8) {
            return 1;
        } else {
            return 1;
        }
    }
}
