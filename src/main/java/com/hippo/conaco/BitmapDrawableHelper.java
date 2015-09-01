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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;

import com.hippo.yorozuya.io.InputStreamPipe;

import java.io.InputStream;

public class BitmapDrawableHelper implements DrawableHelper {

    private BitmapPool mBitmapPool = new BitmapPool();

    @Override
    public Drawable decode(@NonNull InputStreamPipe isPipe) {
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
                return new BitmapDrawable(bitmap);
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
    public int sizeOf(String key, @NonNull Drawable value) {
        return ((BitmapDrawable) value).getBitmap().getByteCount();
    }

    @Override
    public void onRemove(String key, @NonNull DrawableHolder oldValue) {
        if (oldValue.isFree()) {
            mBitmapPool.addReusableBitmap(((BitmapDrawable) oldValue.getDrawable()).getBitmap());
        }
    }
}
