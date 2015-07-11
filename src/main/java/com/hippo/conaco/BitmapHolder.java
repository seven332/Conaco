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
import android.support.annotation.NonNull;

public class BitmapHolder {

    private Bitmap mBitmap;

    private int mReference;

    public BitmapHolder(Bitmap bitmap) {
        if (bitmap == null) {
            throw new IllegalStateException("null bitmap can't initialize BitmapHolder");
        }
        mBitmap = bitmap;
    }

    public @NonNull Bitmap getBitmap() {
        return mBitmap;
    }

    public void obtain() {
        mReference++;
    }

    public void release() {
        if (mReference != 0) {
            mReference--;
        }
    }

    public boolean isFree() {
        return mReference == 0;
    }
}
