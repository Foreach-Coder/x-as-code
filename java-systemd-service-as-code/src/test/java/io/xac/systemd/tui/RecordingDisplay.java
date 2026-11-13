package io.xac.systemd.tui;

/**
 * 测试用 Display：不碰终端，只记住最后画的那一帧，允许改尺寸模拟 resize。
 */
final class RecordingDisplay implements Display {

    private int width;
    private int height;
    private Canvas last;
    private int shows;
    private boolean closed;

    RecordingDisplay(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void show(Canvas canvas) {
        this.last = canvas;
        this.shows++;
    }

    @Override
    public Size size() {
        return new Size(width, height);
    }

    @Override
    public void close() {
        this.closed = true;
    }

    void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    String line(int y) {
        if (last == null) {
            throw new IllegalStateException("display was never shown");
        }
        return last.line(y);
    }

    Canvas last() {
        return last;
    }

    int shows() {
        return shows;
    }

    boolean closed() {
        return closed;
    }
}
