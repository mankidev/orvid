package com.orvid.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

public final class ThumbView extends ImageView {

    public ThumbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float radius = 12 * context.getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        boolean tall = drawable != null && drawable.getIntrinsicWidth() > 0
                && drawable.getIntrinsicHeight() > drawable.getIntrinsicWidth() * 1.2f;
        setScaleType(tall ? ScaleType.FIT_CENTER : ScaleType.CENTER_CROP);
        super.setImageDrawable(drawable);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, width * 9 / 16);
    }
}
