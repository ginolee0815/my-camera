package com.ubiqconn.mycamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;

import android.graphics.RectF;
import com.google.mediapipe.tasks.components.containers.Detection;
import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private List<Detection> results = new ArrayList<>();
    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private int imageWidth;
    private int imageHeight;

    // Scale factor to map image coordinates to view coordinates
    private float scaleFactor = 1.0f;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8.0f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(50.0f);
        textPaint.setStyle(Paint.Style.FILL);
    }

    public void setResults(List<Detection> detectionResults, int imageHeight, int imageWidth) {
        this.results = detectionResults;
        this.imageHeight = imageHeight;
        this.imageWidth = imageWidth;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Log.d("OverlayView", "onDraw called " + getWidth() + "x" + getHeight());

        // DEBUG: Force drawing something to verify overlay visibility
        // Paint debugPaint = new Paint();
        // debugPaint.setColor(Color.GREEN);
        // debugPaint.setTextSize(100);
        // canvas.drawText("DEBUG VIEW", 50, 200, debugPaint);

        if (results == null || results.isEmpty())
            return;

        if (imageWidth == 0 || imageHeight == 0)
            return;

        // Calculate scale factor assuming CENTER_CROP or FIT usually,
        // but here we just scale to fit width for simplicity or use the matrix in
        // MainActivity.
        // MainActivity uses a matrix to scale the TextureView. The OverlayView is
        // likely on top.
        // We really need the scale relevant to the OverlayView's size.

        // Assuming the image fills the view or consistent aspect ratio,
        // we scale coordinates based on View dimensions vs Image dimensions.
        // Since we are using TextureView.getBitmap(), the image size PROBABLY matches
        // the View size
        // (unless scale type splits them).
        // Let's check the relation.
        float scaleX = (float) getWidth() / imageWidth;
        float scaleY = (float) getHeight() / imageHeight;

        // Log.d("OverlayView", "View: " + getWidth() + "x" + getHeight() + " Image: " +
        // imageWidth + "x" + imageHeight + " Scale: " + scaleX + "," + scaleY);

        // int count = 0;
        for (Detection detection : results) {
            RectF boundingBox = detection.boundingBox();
            // if (count < 3) {
            // Log.d("OverlayView", "Raw Box: " + boundingBox.toString());
            // }
            // count++;

            // MediaPipe detection bounding box is in pixel coordinates of the input image.
            // If scaleX/Y are not 1, we map them to View coordinates.
            // Since we use TextureView.getBitmap(), the bitmap is scaled/rotated to match
            // View.
            // So scaleX/Y should be ~1.0.
            float top = boundingBox.top * scaleY;
            float bottom = boundingBox.bottom * scaleY;
            float left = boundingBox.left * scaleX;
            float right = boundingBox.right * scaleX;

            // Draw bounding box
            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRect(rect, boxPaint);

            // Draw label and score
            if (detection.categories() != null && !detection.categories().isEmpty()) {
                String label = detection.categories().get(0).categoryName();
                float score = detection.categories().get(0).score();
                String text = String.format("%s %.2f", label, score);
                canvas.drawText(text, left, top - 10, textPaint);
            }
        }
    }
}
