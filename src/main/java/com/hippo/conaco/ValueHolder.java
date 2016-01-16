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
import android.util.Log;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class ValueHolder<V> {

    private static final String TAG = ValueHolder.class.getSimpleName();

    @NonNull
    private final V mValue;

    private ArrayList<WeakReference<Object>> mReferenceList = new ArrayList<>(3);

    private boolean mInMemoryCache = false;

    // Stuff to do trick
    InputStream is;
    long length;
    ProgressNotify notify;

    public ValueHolder(@NonNull V value) {
        mValue = value;
    }

    public @NonNull V getValue() {
        return mValue;
    }

    public synchronized void obtain(Object reference) {
        mReferenceList.add(new WeakReference<>(reference));
    }

    public synchronized void release(Object reference) {
        ArrayList<WeakReference<Object>> list = mReferenceList;
        for (int i = 0, size = list.size(); i < size; i++) {
            WeakReference<Object> ref = list.get(i);
            Object obj = ref.get();
            if (obj == null) {
                // The reference is invalid, remove it
                list.remove(i);
                i--;
                size--;
            } else if (reference == obj) {
                // It is the reference, remove it
                list.remove(i);
                return;
            }
        }

        Log.w(TAG, "Can't find the reference " + reference);
    }

    public synchronized boolean isFree() {
        ArrayList<WeakReference<Object>> list = mReferenceList;

        // Remove all invalid reference
        for (int i = 0, size = list.size(); i < size; i++) {
            WeakReference<Object> ref = list.get(i);
            Object obj = ref.get();
            if (obj == null) {
                // The reference is invalid, remove it
                list.remove(i);
                i--;
                size--;
            }
        }

        return list.isEmpty();
    }

    void setInMemoryCache(boolean inMemoryCache) {
        mInMemoryCache = inMemoryCache;
    }

    public boolean isInMemoryCache() {
        return mInMemoryCache;
    }
}
