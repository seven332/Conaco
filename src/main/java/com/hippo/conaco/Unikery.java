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

import com.hippo.yorozuya.IdIntGenerator;

public interface Unikery<V> {

    int INVAILD_ID = IdIntGenerator.INVAILD_ID;

    void setTaskId(int id);

    int getTaskId();

    void onMiss(Conaco.Source source);

    void onRequest();

    void onProgress(long singleReceivedSize, long receivedSize, long totalSize);

    /**
     * @return Can use this object holder or not
     */
    boolean onGetObject(@NonNull ValueHolder<V> holder, Conaco.Source source);

    void onSetDrawable(Drawable drawable);

    void onFailure();

    void onCancel();
}
