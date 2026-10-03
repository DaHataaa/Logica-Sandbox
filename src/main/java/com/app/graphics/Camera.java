package com.app.graphics;

import com.app.Config;
import javafx.animation.AnimationTimer;

public class Camera {
    private double x;
    private double y;
    private double zoom;
    private int viewportWidth;
    private int viewportHeight;

    private final int worldSizePx;
    private final double minZoom;
    private final double maxZoom;
    private final int baseCellSize;

    // Для инерции зума
    private double targetZoom;
    private double velocityZoom = 0;
    private AnimationTimer inertiaTimer;
    private static final double ZOOM_FRICTION = 0.7;
    private static final double MIN_ZOOM_VELOCITY = 0.0003;

    // Сила импульса от одного шага зума
    private static final double WHEEL_IMPULSE = 0.08;
    private static final double BUTTON_IMPULSE = 0.08;

    // Точка зума
    private double lastMouseScreenX = 0;
    private double lastMouseScreenY = 0;

    public Camera(int worldSizeCells, int baseCellSize, int viewportWidth, int viewportHeight) {
        this.baseCellSize = baseCellSize;
        this.worldSizePx = worldSizeCells * baseCellSize;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;

        Config config = Config.getInstance();
        this.minZoom = (double) config.getCellSizeMin() / baseCellSize;
        this.maxZoom = (double) config.getCellSizeMax() / baseCellSize;
        this.zoom = 1.0;
        this.targetZoom = 1.0;

        this.x = (worldSizePx - viewportWidth) / 2.0;
        this.y = (worldSizePx - viewportHeight) / 2.0;

        initInertiaTimer();
    }

    private void initInertiaTimer() {
        inertiaTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (Math.abs(velocityZoom) > MIN_ZOOM_VELOCITY) {
                    double newZoom = zoom + velocityZoom;

                    if (newZoom < minZoom) {
                        newZoom = minZoom;
                        velocityZoom = 0;
                    }
                    if (newZoom > maxZoom) {
                        newZoom = maxZoom;
                        velocityZoom = 0;
                    }

                    if (Math.abs(newZoom - zoom) > 0.0001) {
                        applyZoom(newZoom, lastMouseScreenX, lastMouseScreenY);
                    }

                    velocityZoom *= ZOOM_FRICTION;
                } else {
                    velocityZoom = 0;
                }
            }
        };
        inertiaTimer.start();
    }

    private void applyZoom(double newZoom, double mouseScreenX, double mouseScreenY) {
        if (Math.abs(newZoom - zoom) < 0.0001) return;

        double worldX = screenToWorldX(mouseScreenX);
        double worldY = screenToWorldY(mouseScreenY);

        zoom = newZoom;

        x = worldX - mouseScreenX / zoom;
        y = worldY - mouseScreenY / zoom;
    }

    public void addZoomInertia(double deltaZoom, double mouseScreenX, double mouseScreenY) {
        lastMouseScreenX = mouseScreenX;
        lastMouseScreenY = mouseScreenY;
        velocityZoom += deltaZoom;

        if (velocityZoom > 0.03) velocityZoom = 0.03;
        if (velocityZoom < -0.03) velocityZoom = -0.03;
    }

    public void stopZoomInertia() {
        velocityZoom = 0;
    }

    public void moveWorld(double dx, double dy) {
        x += dx;
        y += dy;
        stopZoomInertia();
    }

    public void moveScreen(double dx, double dy) {
        x -= dx / zoom;
        y -= dy / zoom;
        stopZoomInertia();
    }

    /** Зум колесом мыши (точка — под курсором). */
    public void zoom(double factor, double mouseScreenX, double mouseScreenY) {
        zoomAt(factor, mouseScreenX, mouseScreenY, WHEEL_IMPULSE);
    }

    /** Зум с клавиатуры: точка задаётся снаружи. */
    public void zoomAt(double factor, double screenX, double screenY) {
        zoomAt(factor, screenX, screenY, BUTTON_IMPULSE);
    }

    /** Зум к центру вьюпорта (fallback). */
    public void zoomFromCenter(double factor) {
        zoomAt(factor, viewportWidth / 2.0, viewportHeight / 2.0, BUTTON_IMPULSE);
    }

    private void zoomAt(double factor, double screenX, double screenY, double impulse) {
        double newZoom = zoom * factor;

        if (newZoom < minZoom) newZoom = minZoom;
        if (newZoom > maxZoom) newZoom = maxZoom;

        if (Math.abs(newZoom - zoom) < 0.0001) return;

        lastMouseScreenX = screenX;
        lastMouseScreenY = screenY;

        double zoomDelta = newZoom - zoom;
        addZoomInertia(zoomDelta * impulse, screenX, screenY);

        applyZoom(newZoom, screenX, screenY);
    }

    public void setViewportSize(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZoom() { return zoom; }
    public int getViewportWidth() { return viewportWidth; }
    public int getViewportHeight() { return viewportHeight; }
    public int getBaseCellSize() { return baseCellSize; }
    public int getWorldSizePx() { return worldSizePx; }

    public double worldToScreenX(double worldX) {
        return (worldX - x) * zoom;
    }

    public double worldToScreenY(double worldY) {
        return (worldY - y) * zoom;
    }

    public double screenToWorldX(double screenX) {
        return screenX / zoom + x;
    }

    public double screenToWorldY(double screenY) {
        return screenY / zoom + y;
    }

    public int getVisibleStartX(int worldSizeCells) {
        double startWorldX = x;
        int start = (int) Math.floor(startWorldX / baseCellSize);
        return Math.max(0, Math.min(worldSizeCells, start));
    }

    public int getVisibleStartY(int worldSizeCells) {
        double startWorldY = y;
        int start = (int) Math.floor(startWorldY / baseCellSize);
        return Math.max(0, Math.min(worldSizeCells, start));
    }

    public int getVisibleEndX(int worldSizeCells) {
        double endWorldX = x + viewportWidth / zoom;
        int end = (int) Math.ceil(endWorldX / baseCellSize) + 1;
        return Math.max(0, Math.min(worldSizeCells, end));
    }

    public int getVisibleEndY(int worldSizeCells) {
        double endWorldY = y + viewportHeight / zoom;
        int end = (int) Math.ceil(endWorldY / baseCellSize) + 1;
        return Math.max(0, Math.min(worldSizeCells, end));
    }

    public int getCellScreenSize() {
        return (int) Math.max(1, Math.round(baseCellSize * zoom));
    }

    public void stop() {
        inertiaTimer.stop();
    }
}