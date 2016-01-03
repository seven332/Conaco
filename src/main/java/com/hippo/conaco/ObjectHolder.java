/*
 * Copyright 2016 Hippo Seven
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

import android.support.annotation.NonNull;

import java.io.InputStream;

public class ObjectHolder {

    @NonNull
    private final Object mObject;

    private int mReference = 0;

    private boolean mInMemoryCache = false;

    // Stuff to do trick
    InputStream is;
    long length;
    ProgressNotify notify;

    public ObjectHolder(@NonNull Object object) {
        mObject = object;
    }

    public @NonNull Object getObject() {
        return mObject;
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

    void setInMemoryCache(boolean inMemoryCache) {
        mInMemoryCache = inMemoryCache;
    }

    public boolean isInMemoryCache() {
        return mInMemoryCache;
    }
}
