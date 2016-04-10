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

import com.hippo.beerbelly.BeerBelly;
import com.hippo.yorozuya.io.InputStreamPipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ValueCache<V> extends BeerBelly<ValueHolder<V>> {

    private final ValueHelper<V> mHelper;

    public ValueCache(BeerBelly.BeerBellyParams params, ValueHelper<V> helper) {
        super(params);
        mHelper = helper;
    }

    @Override
    protected int sizeOf(String key, ValueHolder<V> value) {
        return mHelper.sizeOf(key, value.getValue());
    }

    @Override
    protected void memoryEntryAdded(ValueHolder<V> value) {
        value.setInMemoryCache(true);
    }

    @Override
    protected void memoryEntryRemoved(boolean evicted, String key,
            ValueHolder<V> oldValue, ValueHolder<V> newValue) {
        if (oldValue != null) {
            oldValue.setInMemoryCache(false);
            mHelper.onRemove(key, oldValue);
        }
    }

    @Override
    protected ValueHolder<V> read(@NonNull InputStreamPipe isPipe) {
        V value = mHelper.decode(isPipe);
        if (value != null) {
            return new ValueHolder<>(value);
        } else {
            return null;
        }
    }

    @Override
    protected boolean write(OutputStream os, ValueHolder<V> value) {
        ProgressNotify notify = value.notify;
        long length = value.length;
        InputStream is = value.is;

        final byte buffer[] = new byte[1024 * 4];
        long receivedSize = 0;
        int bytesRead;

        try {
            while((bytesRead = is.read(buffer)) !=-1) {
                os.write(buffer, 0, bytesRead);
                receivedSize += bytesRead;
                notify.notifyProgress((long) bytesRead, receivedSize, length);
            }
            os.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
