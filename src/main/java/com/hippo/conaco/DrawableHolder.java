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
import android.support.annotation.NonNull;

import java.io.InputStream;

public class DrawableHolder {

    @NonNull
    private final Drawable mDrawable;

    private int mReference;

    // Stuff to do trick
    InputStream is;
    long length;
    ProgressNotify notify;

    public DrawableHolder(@NonNull Drawable drawable) {
        mDrawable = drawable;
    }

    public @NonNull Drawable getDrawable() {
        return mDrawable;
    }

    public synchronized void obtain() {
        mReference++;
    }

    public synchronized void release() {
        if (mReference != 0) {
            mReference--;
        }
    }

    public synchronized boolean isFree() {
        return mReference == 0;
    }
}
