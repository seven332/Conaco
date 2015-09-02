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
import android.support.annotation.Nullable;

import com.hippo.yorozuya.io.InputStreamPipe;

public interface DrawableHelper {

    @Nullable
    Drawable decode(@NonNull InputStreamPipe isPipe);

    int sizeOf(@NonNull String key, @NonNull Drawable value);

    void onRemove(@NonNull String key, @NonNull DrawableHolder oldValue);

    boolean useMemoryCache(@NonNull String key, DrawableHolder holder);
}
