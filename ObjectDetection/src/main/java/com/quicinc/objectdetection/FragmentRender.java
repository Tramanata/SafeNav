// ---------------------------------------------------------------------
// Copyright (c) 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.quicinc.objectdetection;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.Matrix;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayList;

/**
 * FragmentRender draws the final prediction image and overlays debugging text.
 * Now also supports recording to a Surface for video capture.
 */

public class FragmentRender extends View {
    private static final String TAG = "FragmentRender";
    private final ReentrantLock mLock = new ReentrantLock();
    private Bitmap mBitmap = null;
    private Size mCameraSize = null;
    private ArrayList<RectangleBox> boxlist = new ArrayList<>();
    private int mDisplayRotation = 0;
    private final Rect mTargetRect = new Rect();
    private float fps;
    private long inferTime = 0;
    private long preprocessTime = 0;
    private long postprocessTime = 0;
    private Matrix mTransform = new Matrix();
    private final Paint mBorderColor = new Paint();
    private final Paint mTextColor = new Paint();
    private Paint mFramePaint = new Paint();
    private Paint mTextPaint = new Paint();
    private Paint mLabelFramePaint = new Paint();

    // NEW: Recording support
    private Surface recordingSurface;
    private boolean isRecording = false;

    public static @ColorInt int labelColor(int label, int alpha) {
        // Generic colors that do not correspond to a dataset
        final int[] baseColors = new int[]{
                0xFFF44336, // Red
                0xFFE91E63, // Pink
                0xFF9C27B0, // Purple
                0xFF673AB7, // Deep Purple
                0xFF3F51B5, // Indigo
                0xFF2196F3, // Blue
                0xFF03A9F4, // Light Blue
                0xFF00BCD4, // Cyan
                0xFF009688, // Teal
                0xFF4CAF50, // Green
                0xFF8BC34A, // Light Green
                0xFFCDDC39, // Lime
                0xFFFFEB3B, // Yellow
                0xFFFFC107, // Amber
                0xFFFF9800, // Orange
                0xFFFF5722, // Deep Orange
                0xFF795548, // Brown
                0xFF9E9E9E, // Gray
                0xFF607D8B  // Blue Gray
        };

        int index = Math.abs(label % baseColors.length);
        int color = baseColors[index];
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public FragmentRender(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mBorderColor.setColor(Color.MAGENTA);
        mBorderColor.setStyle(Paint.Style.STROKE);
        mBorderColor.setStrokeWidth(6);

        mTextColor.setColor(Color.WHITE);
        mTextColor.setTypeface(Typeface.DEFAULT_BOLD);
        mTextColor.setStyle(Paint.Style.FILL);
        mTextColor.setTextSize(50);
    }

    // NEW: Set recording surface
    public void setRecordingSurface(Surface surface) {
        mLock.lock();
        try {
            this.recordingSurface = surface;
            this.isRecording = (surface != null);
            Log.i(TAG, "Recording surface set: " + (isRecording ? "enabled" : "disabled"));
        } finally {
            mLock.unlock();
        }
    }

    public void setCoordsList(ArrayList<RectangleBox> t_boxlist) {
        mLock.lock();
        postInvalidate();

        if (boxlist==null)
        {
            mLock.unlock();
            return;
        }
        boxlist.clear();
        for(int j=0;j<t_boxlist.size();j++) {
            boxlist.add(t_boxlist.get(j));
        }
        mLock.unlock();
        postInvalidate();
    }

    public void render(Bitmap image, Size cameraSize, float fps, long inferTime, long preprocessTime, long postprocessTime, int displayRotation)
    {
        this.mBitmap = image;
        this.mCameraSize = cameraSize;
        this.fps = fps;
        this.inferTime = inferTime;
        this.preprocessTime = preprocessTime;
        this.postprocessTime = postprocessTime;
        this.mDisplayRotation = displayRotation;
        postInvalidate();
    }

    @SuppressLint("DefaultLocale")
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        mLock.lock();

        if (mBitmap != null && mCameraSize != null) {
            // Draw to screen canvas (for live preview)
            drawContent(canvas);

            // NEW: Also draw to recording surface if recording
            if (isRecording && recordingSurface != null && recordingSurface.isValid()) {
                try {
                    Canvas recordingCanvas = recordingSurface.lockCanvas(null);
                    if (recordingCanvas != null) {
                        // Draw the same content to recording canvas
                        drawContent(recordingCanvas);
                        recordingSurface.unlockCanvasAndPost(recordingCanvas);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error drawing to recording surface: " + e.getMessage());
                }
            }
        }
        mLock.unlock();
    }

    // NEW: Extracted drawing logic to be reusable
    @SuppressLint("DefaultLocale")
    private void drawContent(Canvas canvas) {
        int insetHeight, insetWidth;

        float canvasRatio = (float) canvas.getWidth() / (float) canvas.getHeight();
        float bitmapRatio = (float) mBitmap.getWidth() / mBitmap.getHeight();

        if (canvasRatio > bitmapRatio) {
            insetHeight = canvas.getHeight();
            insetWidth = (int) ((float) canvas.getHeight() * bitmapRatio);
        } else {
            insetWidth = canvas.getWidth();
            insetHeight = (int) ((float) canvas.getWidth() / bitmapRatio);
        }

        int offsetWidth = (canvas.getWidth() - insetWidth) / 2;
        int offsetHeight = (canvas.getHeight() - insetHeight) / 2;

        float scaleX;
        float scaleY;
        if (mDisplayRotation == 0 || mDisplayRotation == 2) {
            scaleX = (float)mCameraSize.getHeight() / (float)canvas.getWidth();
            scaleY = (float)mCameraSize.getWidth() / (float)canvas.getHeight();
        } else {
            scaleX = (float)mCameraSize.getWidth() / (float)canvas.getWidth();
            scaleY = (float)mCameraSize.getHeight() / (float)canvas.getHeight();
        }

        if (scaleX < scaleY) {
            scaleX /= scaleY;
            scaleY = 1.0f;
        } else {
            scaleY /= scaleX;
            scaleX = 1.0f;
        }

        float tx = (float)canvas.getWidth() / 2.0f;
        float ty = (float)canvas.getHeight() / 2.0f;

        mTransform.reset();
        switch (mDisplayRotation) {
            case 0:
                mTransform.preTranslate(tx, ty);
                mTransform.preScale(scaleX, scaleY);
                mTransform.preTranslate(-tx, -ty);
                break;
            case 1:
                mTransform.preRotate(-90, tx, ty);
                mTransform.preTranslate(tx, ty);
                mTransform.preScale(
                        scaleY * ty / tx,
                        scaleX * tx / ty);
                mTransform.preTranslate(-tx, -ty);
                break;
            case 3:
                mTransform.preRotate(90, tx, ty);
                mTransform.preTranslate(tx, ty);
                mTransform.preScale(
                        scaleY * ty / tx,
                        scaleX * tx / ty);
                mTransform.preTranslate(-tx, -ty);
                break;
            default:
                break;
        }

        mTargetRect.left = offsetWidth;
        mTargetRect.top = offsetHeight;
        mTargetRect.right = offsetWidth + insetWidth;
        mTargetRect.bottom = offsetHeight + insetHeight;

        canvas.save();
        canvas.concat(mTransform);
        canvas.drawBitmap(mBitmap, null, mTargetRect, null);
        canvas.restore();

        // Draw detection boxes
        for(int j=0;j<boxlist.size();j++) {

            RectangleBox rbox = boxlist.get(j);

            float[] p0 = new float[] {rbox.left, rbox.top};
            float[] p1 = new float[] {rbox.right, rbox.bottom};
            mTransform.mapPoints(p0);
            mTransform.mapPoints(p1);

            float left = Math.min(p0[0], p1[0]);
            float upper = Math.min(p0[1], p1[1]);

            int alpha = (int)(255 * rbox.confidence);
            int color = labelColor(rbox.classIdx, alpha);

            mFramePaint.setColor(color);
            mFramePaint.setStyle(Paint.Style.STROKE);
            mFramePaint.setStrokeWidth(6);

            canvas.drawRect(p0[0], p0[1], p1[0], p1[1], mFramePaint);

            int white = Color.argb(alpha, 255, 255, 255);
            mTextPaint.setColor(white);
            mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            mTextPaint.setStyle(Paint.Style.FILL);
            mTextPaint.setTextSize(30);

            float buf = 2.0f;
            float textWidth = mTextPaint.measureText(rbox.label);
            float textHeight = mTextPaint.getFontMetrics().bottom - mTextPaint.getFontMetrics().top - 8.0f;

            mLabelFramePaint.setColor(color);
            mLabelFramePaint.setStyle(Paint.Style.FILL);

            canvas.drawRect(left, upper, left+textWidth+2*buf, upper-textHeight-2*buf, mLabelFramePaint);
            canvas.drawText(rbox.label, left+buf, upper+mTextPaint.getFontMetrics().top+buf+17.0f, mTextPaint);
        }
    }
}