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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.icu.text.SimpleDateFormat;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import com.vstechlab.easyfonts.EasyFonts;

import net.lapismc.watchface.R;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
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
        private final Handler mUpdateTimeHandler = new Handler(Looper.myLooper()) {
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

        private boolean mIsSilentMode = false;

        private long mLastTapTime = 0L;
        private Paint mBackgroundPaint;
        private Paint mAmbientPaint;
        private Paint mSilentModePaint;
        private Paint mHandPaint;
        private Paint mTimePaint;
        private Paint mDatePaint;
        private Paint mBatteryPaint;

        private SimpleDateFormat mAllInfoTimeFormat;
        private SimpleDateFormat mTimeFormat;
        private SimpleDateFormat mCleanDateFormat;
        private SimpleDateFormat mStandardDateFormat;

        private boolean mAmbient;

        private int mHeight;
        private float mCenterX;
        private float mCenterY;

        private String mBatteryText;
        private int mBatteryCounter;
        private boolean mLowBattery;
        private int mCurrentHour;
        private Long mDateFormatTime;

        private int mOffsetY, mOffsetX;
        private int mOffsetCounter;

        private float mTimeX, mTimeY, mTimeLength, mTimeHeight;

        private MediaPlayer mMediaPlayer;

        @RequiresApi(api = Build.VERSION_CODES.Q)
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

            int mInteractiveColor = Color.BLUE;
            int mSilentModeColor = Color.rgb(0, 0, 50);
            int mAmbientColor = Color.BLACK;

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mInteractiveColor);

            mAmbientPaint = new Paint();
            mAmbientPaint.setColor(mAmbientColor);

            mSilentModePaint = new Paint();
            mSilentModePaint.setColor(mSilentModeColor);

            DisplayMetrics displayMetrics = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay()
                    .getMetrics(displayMetrics);
            float textScale = (float) (displayMetrics.scaledDensity * 0.7);
            System.out.println("TextScale: " + textScale);

            mTimePaint = new Paint();
            mTimePaint.setTextSize(100 * textScale);
            mTimePaint.setColor(Color.WHITE);
            mTimePaint.setAntiAlias(true);
            mTimePaint.setTypeface(EasyFonts.captureIt(getBaseContext()));

            mDatePaint = new Paint();
            mDatePaint.setTextSize(20 * textScale);
            mDatePaint.setColor(Color.WHITE);
            mDatePaint.setAntiAlias(true);
            mDatePaint.setTypeface(EasyFonts.droidSerifRegular(getBaseContext()));

            mBatteryPaint = new Paint();
            mBatteryPaint.setTextSize(45 * textScale);
            mBatteryPaint.setColor(Color.WHITE);
            mBatteryPaint.setAntiAlias(true);
            mBatteryPaint.setTypeface(EasyFonts.robotoMedium(getBaseContext()));
            mBatteryCounter = 3;

            mAllInfoTimeFormat = new SimpleDateFormat("hh:mm:ss dd/MM/yyyy", Locale.ENGLISH);
            mTimeFormat = new SimpleDateFormat("hh:mm", Locale.ENGLISH);
            mCleanDateFormat = new SimpleDateFormat("EE dd MMM", Locale.ENGLISH);
            mStandardDateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH);

            mCalendar = Calendar.getInstance();
            mIsCleanDateFormat = true;

            mMediaPlayer = MediaPlayer.create(getApplicationContext(), R.raw.beep);


            //Set silent mode if it was active before reboot
            AudioManager man = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int currentVolume = man.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            int maxVolume = man.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            if (maxVolume != currentVolume && !mIsSilentMode) {
                toggleSilentMode();
            }
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
                mTimePaint.setAntiAlias(false);
                mDatePaint.setAntiAlias(false);
            } else {
                //make battery % update when screen wakes
                mBatteryCounter = 3;
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
            if (tapType != TAP_TYPE_TAP) {
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
                    toggleSilentMode();
                }
                mLastTapTime = System.currentTimeMillis();
            }
        }

        private void toggleSilentMode() {
            mIsSilentMode = !mIsSilentMode;
            //Process ring volume change for silent mode
            AudioManager man = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (mIsSilentMode) {
                man.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            } else {
                int maxVolume = man.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                man.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mHeight = height;
            /*
             * Find the coordinates of the center point on the screen.
             * Ignore the window insets so that, on round watches
             * with a "chin", the watch face is centered on the entire screen,
             * not just the usable portion.
             */
            mCenterX = width / 2f;
            mCenterY = mHeight / 2f;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            System.out.println(mAllInfoTimeFormat.format(mCalendar.getTime().getTime()));

            //Get battery percentage and check if it is below 25%
            if (mBatteryCounter >= 3 || mAmbient) {
                IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = MyWatchFaceService.this.registerReceiver(null, iFilter);
                int watchBattery = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : 0;
                mBatteryText = watchBattery + "%";
                //50 normal , 60 low battery
                //White normal but red on low battery
                mLowBattery = watchBattery < 31;
                if (watchBattery > 30) {
                    mBatteryPaint.setTextSize(50);
                } else {
                    mBatteryPaint.setTextSize(60);
                }
                mBatteryCounter = 0;
            }
            mBatteryCounter++;

            if (mIsSilentMode) {
                //175
                int alpha = 255;
                int colour = Color.GRAY;
                mTimePaint.setAlpha(alpha);
                mTimePaint.setColor(colour);
                mDatePaint.setAlpha(alpha);
                mDatePaint.setColor(colour);
                mBatteryPaint.setAlpha(alpha);
                if (mLowBattery)
                    mBatteryPaint.setColor(Color.RED);
                else
                    mBatteryPaint.setColor(colour);

                //Process ring volume change for silent mode
                //This is here to enforce the mute of sound
                AudioManager man = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (mIsSilentMode) {
                    man.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                } else {
                    int maxVolume = man.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                    man.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                }
            } else {
                int alpha = 255;
                int colour = Color.WHITE;
                mTimePaint.setAlpha(alpha);
                mTimePaint.setColor(colour);
                mDatePaint.setAlpha(alpha);
                mDatePaint.setColor(colour);
                mBatteryPaint.setAlpha(alpha);
                if (mLowBattery)
                    mBatteryPaint.setColor(Color.RED);
                else
                    mBatteryPaint.setColor(colour);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            //Deal with offset for burn in prevention
            mOffsetCounter++;
            if (mOffsetCounter >= 30 || mAmbient) {
                Random r = new Random();
                int variance = 30;
                mOffsetCounter = 0;
                mOffsetX = r.nextBoolean() ? -r.nextInt(variance) : r.nextInt(variance);
                mOffsetY = r.nextBoolean() ? -r.nextInt(variance) : r.nextInt(variance);
            }

            // Draw the background.
            if (mAmbient) {
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mAmbientPaint);
            } else {
                if (mIsSilentMode) {
                    canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mSilentModePaint);
                } else {
                    canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
                }
            }

            //Vibrate if on the hour
            if (mCurrentHour != mCalendar.get(Calendar.HOUR_OF_DAY)) {
                mCurrentHour = mCalendar.get(Calendar.HOUR_OF_DAY);
                //Check if the minutes is 00
                //This stops it going off when the watch is woken up or the app installed
                if (mCalendar.get(Calendar.MINUTE) == 0) {
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    // 2 alert vibes + 1 break 12 hours * 2
                    long[] timings = new long[27];
                    int[] amplitudes = new int[27];
                    timings[0] = 750;
                    timings[1] = 500;
                    timings[2] = 1000;
                    amplitudes[0] = 100;
                    amplitudes[1] = 255;
                    amplitudes[2] = 0;
                    int hour = mCalendar.get(Calendar.HOUR);
                    if (hour == 0) hour = 12;
                    for (int i = 3; i < timings.length; i++) {
                        //(i % 2 == 0) = off time
                        // 100 off, 250 on
                        timings[i] = i % 2 == 0 ? 200 : 250;
                        // 0 off, 255 on
                        amplitudes[i] = i % 2 == 0 || (i / 2) > hour ? 0 : 255;
                    }
                    System.out.println(Arrays.toString(timings) + " : " + Arrays.toString(amplitudes));
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                    //If the time is between 6am and 11pm(23 hours) exclusive
                    if (!mIsSilentMode) {
                        mMediaPlayer.setVolume(0.1f, 0.1f);
                        mMediaPlayer.start();
                    }
                }
            }

            //Get and draw the time
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
                mHandPaint.setColor(((int) seconds) % 15 == 0 ? Color.CYAN : Color.WHITE);
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

    }
}
