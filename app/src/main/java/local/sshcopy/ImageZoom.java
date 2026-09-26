package local.sshcopy;

/** Screen-space geometry for a fitted image, pinch zoom and bounded panning. */
final class ImageZoom {
    static final float MAX_ZOOM = 8f;
    float zoom = 1f, x, y, scale;
    private float width, height, imageWidth, imageHeight, fitScale;

    void fit(float width, float height, float imageWidth, float imageHeight) {
        this.width = width;
        this.height = height;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        zoom = 1f;
        fitScale = width > 0 && height > 0 && imageWidth > 0 && imageHeight > 0
                ? Math.min(width / imageWidth, height / imageHeight) : 0f;
        scale = fitScale;
        x = (width - imageWidth * scale) / 2f;
        y = (height - imageHeight * scale) / 2f;
    }

    void zoomTo(float requested, float focusX, float focusY) {
        if (fitScale <= 0 || !Float.isFinite(requested) || !Float.isFinite(focusX) || !Float.isFinite(focusY)) return;
        float next = Math.max(1f, Math.min(MAX_ZOOM, requested));
        float factor = next / zoom;
        x = focusX - (focusX - x) * factor;
        y = focusY - (focusY - y) * factor;
        zoom = next;
        scale = fitScale * zoom;
        constrain();
    }

    void pan(float dx, float dy) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) return;
        x += dx;
        y += dy;
        constrain();
    }

    private void constrain() {
        x = bound(x, width, imageWidth * scale);
        y = bound(y, height, imageHeight * scale);
    }

    private static float bound(float position, float viewport, float image) {
        return image <= viewport ? (viewport - image) / 2f
                : Math.max(viewport - image, Math.min(0f, position));
    }
}
