package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Fork, parental controls: a child's avatar, their emoji (or first letter) on a circle of their colour. Used as
 * a compound drawable, so the row or pill keeps its own background and focus outline.
 */
final class AvatarDrawable extends Drawable {
    /**
     * Preset profile colours, readable behind white text and emoji on a dark TV screen. Ordered so neighbours
     * contrast: a child without a chosen colour gets the next one, and two children never start out alike.
     */
    static final int[] COLORS = {
            0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFFB8C00,
            0xFF00897B, 0xFFD81B60, 0xFF3949AB, 0xFFF9A825, 0xFF6D4C41};

    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String label;
    private final int size;

    AvatarDrawable(int color, String label, int sizePx) {
        this.label = label;
        this.size = sizePx;
        circle.setColor(color);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(sizePx * 0.58f);
        text.setFakeBoldText(true);
        setBounds(0, 0, sizePx, sizePx);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle);
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, text);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        circle.setAlpha(alpha);
        text.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        circle.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
