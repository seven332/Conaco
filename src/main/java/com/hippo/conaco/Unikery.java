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

import com.hippo.conaco.util.IntIdGenerator;

public interface Unikery {

    int INVAILD_ID = IntIdGenerator.INVAILD_ID;

    void setBitmap(BitmapHolder bitmapHolder, Conaco.Source source);

    void setDrawable(Drawable drawable);

    void setTaskId(int id);

    int getTaskId();

    void onFailure();

    void onCancel();
}
