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

import android.util.SparseArray;

class Register<V> {

    private final SparseArray<ConacoTask<V>> mConacoTaskMap = new SparseArray<>();

    /**
     * @return true for the key is registered
     */
    public synchronized boolean register(int id, ConacoTask<V> task) {
        boolean repeatedKey = false;
        String taskKey = task.getKey();

        if (taskKey != null) {
            // Check repeated key
            for (int i = 0, size = mConacoTaskMap.size(); i < size; i++) {
                ConacoTask<V> ct = mConacoTaskMap.valueAt(i);
                if (taskKey.equals(ct.getKey())) {
                    repeatedKey = true;
                    break;
                }
            }
        }

        // Append task
        mConacoTaskMap.append(id, task);

        return repeatedKey;
    }

    public synchronized void unregister(int id) {
        mConacoTaskMap.remove(id);
    }

    public synchronized ConacoTask<V> getByKey(String key) {
        if (key == null) {
            return null;
        }
        for (int i = 0, size = mConacoTaskMap.size(); i < size; i++) {
            ConacoTask<V> ct = mConacoTaskMap.valueAt(i);
            if (key.equals(ct.getKey())) {
                return ct;
            }
        }
        return null;
    }

    public synchronized ConacoTask<V> popById(int id) {
        int index = mConacoTaskMap.indexOfKey(id);
        if (index >= 0) {
            ConacoTask<V> task = mConacoTaskMap.valueAt(index);
            mConacoTaskMap.removeAt(index);
            return task;
        } else {
            return null;
        }
    }
}
