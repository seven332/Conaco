package com.hippo.conaco;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.hippo.beerbelly.BeerBelly;

import java.io.InputStream;
import java.io.OutputStream;

class BitmapCache extends BeerBelly<BitmapHolder> {

    /**
     * Compression settings when writing images to disk cache
     */
    private static final Bitmap.CompressFormat COMPRESS_FORMAT = Bitmap.CompressFormat.JPEG;

    /**
     * Image compression quality
     */
    private static final int COMPRESS_QUALITY = 98;

    private BitmapPool mBitmapPool;

    public BitmapCache(BeerBellyParams params) {
        super(params);

        mBitmapPool = new BitmapPool();
    }

    @Override
    protected int sizeOf(String key, BitmapHolder value) {
        return value.getBitmap().getByteCount();
    }

    @Override
    protected void memoryEntryRemoved(boolean evicted, String key, BitmapHolder oldValue, BitmapHolder newValue) {
        if (oldValue != null && oldValue.isFree()) {
            mBitmapPool.addReusableBitmap(oldValue.getBitmap());
        }
    }

    @Override
    protected BitmapHolder read(InputStreamHelper ish) {
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
        options.inBitmap = mBitmapPool.getInBitmap(options);

        try {
            InputStream is = ish.open();
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
            if (bitmap != null) {
                return new BitmapHolder(bitmap);
            } else {
                return null;
            }
        } finally {
            ish.close();
        }
    }

    @Override
    protected boolean write(OutputStream os, BitmapHolder value) {
        return value.getBitmap().compress(COMPRESS_FORMAT, COMPRESS_QUALITY, os);
    }
}
