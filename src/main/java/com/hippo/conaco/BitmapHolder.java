package com.hippo.conaco;

import android.graphics.Bitmap;
import android.support.annotation.NonNull;

public class BitmapHolder {

    private Bitmap mBitmap;

    private int mReference;

    public BitmapHolder(Bitmap bitmap) {
        if (bitmap == null) {
            throw new IllegalStateException("null bitmap can't initialize BitmapHolder");
        }
        mBitmap = bitmap;
    }

    public @NonNull Bitmap getBitmap() {
        return mBitmap;
    }

    public void obtain() {
        mReference++;
    }

    public void release() {
        if (mReference != 0) {
            mReference--;
        }
    }

    public boolean isFree() {
        return mReference == 0;
    }
}
