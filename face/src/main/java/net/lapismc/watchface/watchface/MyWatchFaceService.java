/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lapismc.watchface.watchface;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.icu.text.SimpleDateFormat;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;

import com.vstechlab.easyfonts.EasyFonts;

import net.lapismc.watchface.R;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFaceService extends CanvasWatchFaceService {

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private static final float STROKE_WIDTH = 3f;
        /* Handler to update the time once a second in interactive mode. */
        @SuppressLint("HandlerLeak")
        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (R.id.message_update == message.what) {
                    invalidate();
                    if (shouldTimerBeRunning()) {
                        long timeMs = System.currentTimeMillis();
                        long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                        mUpdateTimeHandler.sendEmptyMessageDelayed(R.id.message_update, delayMs);
                    }
                }
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mIsCleanDateFormat;

        private boolean mIsImageBackground = false;
        private long mLastTapTime = 0L;
        private Bitmap mBackgroundBitmap, mGrayBackgroundBitmap;
        private Paint mBackgroundPaint;
        private Paint mHandPaint;
        private Paint mTimePaint;
        private Paint mDatePaint;
        private Paint mBatteryPaint;

        private SimpleDateFormat mAllInfoTimeFormat;
        private SimpleDateFormat mTimeFormat;
        private SimpleDateFormat mCleanDateFormat;
        private SimpleDateFormat mStandardDateFormat;

        private int mAmbientColor;
        private int mInteractiveColor;

        private boolean mAmbient;

        private int mWidth;
        private int mHeight;
        private float mCenterX;
        private float mCenterY;

        private String mBatteryText;
        private int mBatteryCounter;
        private Long mDateFormatTime;

        private int mOffsetY, mOffsetX;
        private int mOffsetCounter;

        private float mTimeX, mTimeY, mTimeLength, mTimeHeight;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFaceService.this)
                    .setAcceptsTapEvents(true).build());

            mHandPaint = new Paint();
            mHandPaint.setColor(Color.WHITE);
            mHandPaint.setStrokeWidth(STROKE_WIDTH);
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStyle(Paint.Style.STROKE);

            mInteractiveColor = Color.BLUE;
            mAmbientColor = Color.BLACK;

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mInteractiveColor);
            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(),
                    R.drawable.custom_background);

            mTimePaint = new Paint();
            mTimePaint.setTextSize(110);
            mTimePaint.setColor(Color.WHITE);
            mTimePaint.setAntiAlias(true);
            mTimePaint.setTypeface(EasyFonts.captureIt(getBaseContext()));

            mDatePaint = new Paint();
            mDatePaint.setTextSize(25);
            mDatePaint.setColor(Color.WHITE);
            mDatePaint.setAntiAlias(true);
            mDatePaint.setTypeface(EasyFonts.droidSerifRegular(getBaseContext()));

            mBatteryPaint = new Paint();
            mBatteryPaint.setTextSize(50);
            mBatteryPaint.setColor(Color.WHITE);
            mBatteryPaint.setAntiAlias(true);
            mBatteryPaint.setTypeface(EasyFonts.robotoMedium(getBaseContext()));
            mBatteryCounter = 3;

            if (mIsImageBackground) {
                int alpha = 100;
                mTimePaint.setAlpha(alpha);
                mDatePaint.setAlpha(alpha);
                mBatteryPaint.setAlpha(alpha);
            }

            mAllInfoTimeFormat = new SimpleDateFormat("hh:mm:ss dd/MM/yyyy");
            mTimeFormat = new SimpleDateFormat("hh:mm");
            mCleanDateFormat = new SimpleDateFormat("EE dd MMM");
            mStandardDateFormat = new SimpleDateFormat("dd/MM/yyyy");

            mCalendar = Calendar.getInstance();
            mIsCleanDateFormat = true;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(R.id.message_update);
            super.onDestroy();
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                invalidate();
            }

            if (mAmbient) {
                mBackgroundPaint.setColor(mAmbientColor);
                mTimePaint.setAntiAlias(false);
                mDatePaint.setAntiAlias(false);
            } else {
                mBackgroundPaint.setColor(mInteractiveColor);
                mTimePaint.setAntiAlias(true);
                mDatePaint.setAntiAlias(true);
            }

            /*
             * Whether the timer should be running depends on whether we're visible (as well as
             * whether we're in ambient mode), so we may need to start or stop the timer.
             */
            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long time) {
            //Top left = 0. Bottom right = 1
            if (tapType != 0) {
                return;
            }
            int x0, y0, x1, y1;
            x0 = (int) (mTimeX);
            y0 = (int) (mTimeY);
            x1 = (int) (mTimeX + mTimeLength);
            y1 = (int) (mTimeY - mTimeHeight);
            boolean isHit = false;
            if (x > x0 && x < x1) {
                if (y < y0 && y > y1) {
                    isHit = true;
                }
            }
            if (isHit) {
                mIsCleanDateFormat = !mIsCleanDateFormat;
                if (!mIsCleanDateFormat) {
                    mDateFormatTime = System.currentTimeMillis();
                }
            } else {
                if (mLastTapTime != 0 && System.currentTimeMillis() - mLastTapTime < 1000) {
                    mIsImageBackground = !mIsImageBackground;
                }
                mLastTapTime = System.currentTimeMillis();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mWidth = width;
            mHeight = height;
            /*
             * Find the coordinates of the center point on the screen.
             * Ignore the window insets so that, on round watches
             * with a "chin", the watch face is centered on the entire screen,
             * not just the usable portion.
             */
            mCenterX = mWidth / 2f;
            mCenterY = mHeight / 2f;

            float mScale = ((float) width) / (float) mBackgroundBitmap.getWidth();
            mBackgroundBitmap = Bitmap.createScaledBitmap
                    (mBackgroundBitmap, (int) (mBackgroundBitmap.getWidth() * mScale),
                            (int) (mBackgroundBitmap.getHeight() * mScale), true);
            initGrayBackgroundBitmap();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            System.out.println(mAllInfoTimeFormat.format(mCalendar.getTime().getTime()));
            if (mIsImageBackground) {
                int alpha = 175;
                mTimePaint.setAlpha(alpha);
                mDatePaint.setAlpha(alpha);
                mBatteryPaint.setAlpha(alpha);
            } else {
                int alpha = 255;
                mTimePaint.setAlpha(alpha);
                mDatePaint.setAlpha(alpha);
                mBatteryPaint.setAlpha(alpha);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // Draw the background.
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
            if (mIsImageBackground) {
                if (mAmbient) {
                    canvas.drawBitmap(mGrayBackgroundBitmap, 0, 0, mBackgroundPaint);
                } else {
                    canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
                }
            }

            mOffsetCounter++;
            if (mOffsetCounter >= 30 || mAmbient) {
                Random r = new Random();
                mOffsetCounter = 0;
                mOffsetX = r.nextBoolean() ? -r.nextInt(15) : r.nextInt(15);
                mOffsetY = r.nextBoolean() ? -r.nextInt(15) : r.nextInt(15);
            }

            String time = mTimeFormat.format(mCalendar.getTime());
            Rect timeBounds = new Rect();
            mTimePaint.getTextBounds(time, 0, time.length(), timeBounds);
            int timeOffset = timeBounds.height() / 2;
            mTimeX = mCenterX - (mTimePaint.measureText(time) / 2) + mOffsetX;
            mTimeY = mCenterY + timeOffset + mOffsetY;
            mTimeLength = mTimePaint.measureText(time);
            mTimeHeight = timeBounds.height();
            canvas.drawText(time, mTimeX, mTimeY, mTimePaint);

            String date;
            if (!mIsCleanDateFormat && mDateFormatTime != null) {
                //60000ms in one minute
                long differenceInTime = System.currentTimeMillis() - mDateFormatTime;
                if (differenceInTime > 60000) {
                    mIsCleanDateFormat = true;
                }
            }
            if (mIsCleanDateFormat) {
                date = mCleanDateFormat.format(mCalendar.getTime().getTime());
            } else {
                date = mStandardDateFormat.format(mCalendar.getTime().getTime());
            }
            Rect dateBounds = new Rect();
            mDatePaint.getTextBounds(date, 0, date.length(), dateBounds);
            int dateOffset = timeBounds.height() / 2 + dateBounds.height() / 2;
            canvas.drawText(date, mCenterX - (mDatePaint.measureText(date) / 2) + mOffsetX, mCenterY - dateOffset + mOffsetY, mDatePaint);

            if (mBatteryCounter >= 3 || mAmbient) {
                IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = MyWatchFaceService.this.registerReceiver(null, iFilter);
                int watchBattery = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : 0;
                mBatteryText = watchBattery + "%";
                mBatteryCounter = 0;
            }
            mBatteryCounter++;

            Rect batteryBounds = new Rect();
            mBatteryPaint.getTextBounds(mBatteryText, 0, mBatteryText.length(), batteryBounds);
            int batteryOffset = timeBounds.height() / 2 + batteryBounds.height() + 20;
            canvas.drawText(mBatteryText, mCenterX - (mBatteryPaint.measureText(mBatteryText) / 2) + mOffsetX, mCenterY + batteryOffset + mOffsetY, mBatteryPaint);

            if (!mAmbient) {
                /*
                 * These calculations reflect the rotation in degrees per unit of time, e.g.,
                 * 360 / 60 = 6 and 360 / 12 = 30.
                 */
                final float seconds = (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
                final float secondsRotation = seconds * 6f;
                canvas.save();
                canvas.rotate(secondsRotation + 180, mCenterX, mCenterY);
                float lengthOfSecondHand = 0.2f;
                float secondHandStart = mCenterY + (mHeight / 2f) - ((mHeight / 2f) * lengthOfSecondHand);
                canvas.drawLine(mCenterX, secondHandStart, mCenterX, mCenterY + mHeight / 2f, mHandPaint);
                canvas.restore();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /*
             * Whether the timer should be running depends on whether we're visible
             * (as well as whether we're in ambient mode),
             * so we may need to start or stop the timer.
             */
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(R.id.message_update);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(R.id.message_update);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                    mBackgroundBitmap.getWidth(),
                    mBackgroundBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mGrayBackgroundBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new
                    ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mBackgroundBitmap, 0, 0, grayPaint);
        }
    }
}
