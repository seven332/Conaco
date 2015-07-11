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
import android.os.Build;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class BitmapPool {

    private final Set<WeakReference<Bitmap>> mReusableBitmapSet = new LinkedHashSet<>();

    public synchronized void addReusableBitmap(Bitmap bitmap) {
        mReusableBitmapSet.add(new WeakReference<>(bitmap));
    }

    public synchronized Bitmap getInBitmap(BitmapFactory.Options options) {
        final Iterator<WeakReference<Bitmap>> iterator = mReusableBitmapSet.iterator();
        Bitmap item;
        while (iterator.hasNext()) {
            item = iterator.next().get();
            if (item != null && item.isMutable()) {
                if (canUseForInBitmap(item, options)) {
                    // Remove from reusable set so it can't be used again.
                    iterator.remove();
                    return item;
                }
            } else {
                // Remove from the set if the reference has been cleared or
                // it can't be used.
                iterator.remove();
            }
        }
        return null;
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
