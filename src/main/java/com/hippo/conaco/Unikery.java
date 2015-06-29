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
