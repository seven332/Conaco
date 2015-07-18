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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.annotation.NonNull;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.yorozuya.io.InputStreamPipe;

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
    protected BitmapHolder read(@NonNull InputStreamPipe isPipe) {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();

            isPipe.obtain();

            options.inJustDecodeBounds = true;

            InputStream is = isPipe.open();
            BitmapFactory.decodeStream(is, null, options);
            isPipe.close();

            // Check out size
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                isPipe.release();
                return null;
            }

            options.inJustDecodeBounds = false;
            options.inMutable = true;
            options.inSampleSize = 1;
            options.inBitmap = mBitmapPool.getInBitmap(options);

            is = isPipe.open();
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
            if (bitmap != null) {
                return new BitmapHolder(bitmap);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            isPipe.close();
            isPipe.release();
        }
    }

    @Override
    protected boolean write(OutputStream os, BitmapHolder value) {
        return value.getBitmap().compress(COMPRESS_FORMAT, COMPRESS_QUALITY, os);
    }
}
