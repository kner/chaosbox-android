package local.sshcopy;

public final class ImageZoomTest {
    private static void equal(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.01f) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        ImageZoom image = new ImageZoom();
        image.fit(1000, 800, 2000, 1000);
        equal(image.scale, .5f); equal(image.x, 0); equal(image.y, 150);
        image.pan(300, -500);
        equal(image.x, 0); equal(image.y, 150);
        // The image coordinate under the fingers remains there during zoom.
        float focusX = (400 - image.x) / image.scale;
        float focusY = (400 - image.y) / image.scale;
        image.zoomTo(2, 400, 400);
        equal(image.x + focusX * image.scale, 400);
        equal(image.y + focusY * image.scale, 400);
        image.pan(10000, -10000);
        equal(image.x, 0); equal(image.y, -200);
        image.zoomTo(100, 500, 400); equal(image.zoom, 8);
        image.zoomTo(.1f, 50, 50);
        equal(image.zoom, 1); equal(image.x, 0); equal(image.y, 150);
        image.fit(800, 1000, 1000, 2000);
        equal(image.scale, .5f); equal(image.x, 150); equal(image.y, 0);
        image.zoomTo(2, 400, 500); image.pan(-10000, 10000);
        equal(image.x, -200); equal(image.y, 0);
        image.zoomTo(Float.NaN, 0, 0); equal(image.zoom, 2);
        image.fit(0, 0, 0, 0); image.zoomTo(2, 0, 0); equal(image.zoom, 1);
        System.out.println("PASS: image fit, zoom focus, limits, pan bounds, reset, portrait and empty geometry");
    }
}
