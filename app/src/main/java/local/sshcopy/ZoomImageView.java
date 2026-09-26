package local.sshcopy;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Full-screen image viewer: pinch to zoom, drag to pan, double-tap to zoom/reset. */
final class ZoomImageView extends ImageView {
    private final ImageZoom zoom = new ImageZoom();
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector scales;
    private final GestureDetector taps;
    private int pointer = MotionEvent.INVALID_POINTER_ID;
    private float lastX, lastY;

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setContentDescription("Full-screen image. Pinch to zoom, drag to move, double-tap to zoom or reset.");
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom.zoomTo(zoom.zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                applyTransform();
                return true;
            }
        });
        scales.setQuickScaleEnabled(false);
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return performClick(); }
            @Override public boolean onDoubleTap(MotionEvent event) {
                zoom.zoomTo(zoom.zoom > 1.01f ? 1f : 2.5f, event.getX(), event.getY());
                applyTransform();
                return true;
            }
        });
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        Drawable image = getDrawable();
        if (image != null) {
            zoom.fit(width, height, image.getIntrinsicWidth(), image.getIntrinsicHeight());
            applyTransform();
        }
    }

    private void applyTransform() {
        transform.setValues(new float[]{zoom.scale, 0, zoom.x, 0, zoom.scale, zoom.y, 0, 0, 1});
        setImageMatrix(transform);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) return false;
        scales.onTouchEvent(event);
        taps.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                lastX = event.getX(); lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index >= 0) {
                    float x = event.getX(index), y = event.getY(index);
                    if (!scales.isInProgress() && event.getPointerCount() == 1) {
                        zoom.pan(x - lastX, y - lastY);
                        applyTransform();
                    }
                    lastX = x; lastY = y;
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int lifted = event.getActionIndex();
                if (event.getPointerId(lifted) == pointer) {
                    int remaining = lifted == 0 ? 1 : 0;
                    pointer = event.getPointerId(remaining);
                    lastX = event.getX(remaining); lastY = event.getY(remaining);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pointer = MotionEvent.INVALID_POINTER_ID;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
