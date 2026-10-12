package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import java.awt.geom.Point2D;
import java.util.Optional;

/** One mapping for image, software pointer and future owner-scoped preview hit testing. */
record DesktopViewportTransform(double x, double y, double width, double height,
        int sourceX, int sourceY, int sourceWidth, int sourceHeight,
        double logicalWidth, double logicalHeight, boolean geometryKnown, long frameGeneration) {

    static DesktopViewportTransform fit(DesktopFrame frame, double x, double y,
            double availableWidth, double availableHeight) {
        return fit(frame, x, y, availableWidth, availableHeight, Double.POSITIVE_INFINITY);
    }

    static DesktopViewportTransform fit(DesktopFrame frame, double x, double y,
            double availableWidth, double availableHeight, double maxScale) {
        DesktopFrameGeometry geometry = frame.geometry();
        int sourceX = geometry == null ? 0 : geometry.contentX();
        int sourceY = geometry == null ? 0 : geometry.contentY();
        int sourceWidth = geometry == null ? frame.width() : geometry.contentWidth();
        int sourceHeight = geometry == null ? frame.height() : geometry.contentHeight();
        double logicalWidth = geometry == null ? sourceWidth : geometry.logicalWidth();
        double logicalHeight = geometry == null ? sourceHeight : geometry.logicalHeight();
        double scale = Math.max(0, Math.min(maxScale, Math.min(availableWidth / logicalWidth,
                availableHeight / logicalHeight)));
        double width = logicalWidth * scale;
        double height = logicalHeight * scale;
        return new DesktopViewportTransform(x + (availableWidth - width) / 2,
                y + (availableHeight - height) / 2, width, height,
                sourceX, sourceY, sourceWidth, sourceHeight,
                logicalWidth, logicalHeight, geometry != null, frame.windowGeneration());
    }

    Point2D.Double sourceToView(double frameX, double frameY) {
        return new Point2D.Double(x + (frameX - sourceX) * width / sourceWidth,
                y + (frameY - sourceY) * height / sourceHeight);
    }

    Optional<Point2D.Double> viewToSource(long expectedGeneration, double viewX, double viewY) {
        if (expectedGeneration != frameGeneration || width <= 0 || height <= 0 || viewX < x || viewY < y
                || viewX >= x + width || viewY >= y + height) return Optional.empty();
        return Optional.of(new Point2D.Double(sourceX + (viewX - x) * sourceWidth / width,
                sourceY + (viewY - y) * sourceHeight / height));
    }

    boolean containsSource(double frameX, double frameY) {
        return frameX >= sourceX && frameY >= sourceY
                && frameX < (long) sourceX + sourceWidth
                && frameY < (long) sourceY + sourceHeight;
    }
}
