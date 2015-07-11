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

package com.hippo.conaco.util;

import android.support.annotation.NonNull;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Interface like queue, work like stack
 */
public class FakeBlockingQueue<E> extends LinkedBlockingDeque<E> implements BlockingQueue<E> {

    @Override
    public boolean add(E e) {
        addFirst(e);
        return true;
    }

    @Override
    public boolean offer(@NonNull E e) {
        return offerFirst(e);
    }

    @Override
    public void put(E e) throws InterruptedException {
        putFirst(e);
    }

    @Override
    public boolean offer(E e, long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        return super.offerFirst(e, timeout, unit);
    }
}
