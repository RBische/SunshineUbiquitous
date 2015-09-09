/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.service.carrier.CarrierMessagingService;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {
    public static final String ASK_WEATHER_MESSAGE_PATH = "/ask_weather_data";
    private static final String TAG = "WeatherWatchFace";
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface LIGHT_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        private static final long TIMEOUT_MS = 500;
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar = new GregorianCalendar(TimeZone.getTimeZone(intent.getStringExtra("time-zone")));
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mBackgroundPaintAmbient;
        Paint mSeparatorPaint;
        Paint mTextPaint;
        Paint mTextDayPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Paint mWeatherIconPaint;

        private Bitmap mWeatherIconBitmap;
        private Bitmap mGrayWeatherIconBitmap;

        boolean mAmbient;

        Calendar mCalendar;

        float mYOffset;
        private float mXHighTempOffset;
        private float mYHighTempOffset;
        private float mXLowTempOffset;
        private float mYLowTempOffset;
        private float mYDayOffset;
        private float mYSeparatorOffset;
        private float mSeparatorWidth;
        private float mSeparatorHeight;
        private float mXWeatherIconTempOffset;
        private float mYWeatherIconTempOffset;

        private String mHighTemp;
        private String mLowTemp;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.time_y_offset);
            mYDayOffset = resources.getDimension(R.dimen.day_y_offset);
            mYSeparatorOffset = resources.getDimension(R.dimen.separator_y_offset);
            mYHighTempOffset = resources.getDimension(R.dimen.high_temp_y_offset);
            mYLowTempOffset = resources.getDimension(R.dimen.low_temp_y_offset);
            mYWeatherIconTempOffset = resources.getDimension(R.dimen.weather_icon_y_offset);
            mSeparatorWidth = resources.getDimension(R.dimen.separator_width);
            mSeparatorHeight = resources.getDimension(R.dimen.separator_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mBackgroundPaintAmbient = new Paint();
            mBackgroundPaintAmbient.setColor(resources.getColor(R.color.digital_background_ambient));

            mSeparatorPaint = new Paint();
            mSeparatorPaint.setColor(resources.getColor(R.color.white));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextDayPaint = new Paint();
            mTextDayPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTextDayPaint.setTypeface(LIGHT_TYPEFACE);

            mHighTempPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mLowTempPaint = new Paint();
            mLowTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mLowTempPaint.setTypeface(LIGHT_TYPEFACE);

            mCalendar = new GregorianCalendar();

            mHighTemp = "";
            mLowTemp = "";

            mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar = new GregorianCalendar();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXHighTempOffset = resources.getDimension(isRound
                    ? R.dimen.high_x_offset_round : R.dimen.high_x_offset);
            mXWeatherIconTempOffset = resources.getDimension(isRound
                    ? R.dimen.weather_icon_x_offset_round : R.dimen.weather_icon_x_offset);
            mXLowTempOffset = resources.getDimension(isRound
                    ? R.dimen.low_temp_x_offset_round : R.dimen.low_temp_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);

            mTextPaint.setTextSize(textSize);
            mTextDayPaint.setTextSize(resources.getDimension(R.dimen.day_text_size));
            mHighTempPaint.setTextSize(resources.getDimension(R.dimen.high_temp_size));
            mLowTempPaint.setTextSize(resources.getDimension(R.dimen.low_temp_size));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
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
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mTextDayPaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (mAmbient){
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaintAmbient);
            }else{
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            canvas.drawRect(bounds.width()/2-mSeparatorWidth/2, mYSeparatorOffset, bounds.width()/2+mSeparatorWidth/2, mYSeparatorOffset+mSeparatorHeight, mSeparatorPaint);

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mCalendar = new GregorianCalendar();
            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), Calendar.MINUTE);
            SimpleDateFormat sdfDay = new SimpleDateFormat("EEE",getResources().getConfiguration().locale);
            SimpleDateFormat sdfMonth = new SimpleDateFormat("MMM",getResources().getConfiguration().locale);
            SimpleDateFormat sdfRest = new SimpleDateFormat("d yyyy",getResources().getConfiguration().locale);

            String dayText = sdfDay.format(mCalendar.getTime()).toUpperCase().substring(0,3) + ", " + sdfMonth.format(mCalendar.getTime()).toUpperCase().substring(0, 3) + " " + sdfRest.format(mCalendar.getTime());
            float width = mTextDayPaint.measureText(dayText);
            float widthTime = mTextPaint.measureText(text);
            float dayX = bounds.width()/2-width/2;
            float timeX = bounds.width()/2-widthTime/2;
            canvas.drawText(text, timeX, mYOffset, mTextPaint);
            canvas.drawText(dayText,dayX,mYDayOffset,mTextDayPaint);
            canvas.drawText(mHighTemp,mXHighTempOffset,mYHighTempOffset,mHighTempPaint);
            canvas.drawText(mLowTemp,mXLowTempOffset,mYLowTempOffset,mLowTempPaint);

            if (mWeatherIconBitmap!=null){
                if (mAmbient) {
                    canvas.drawBitmap(mGrayWeatherIconBitmap, mXWeatherIconTempOffset, mYWeatherIconTempOffset, mWeatherIconPaint);
                } else {
                    canvas.drawBitmap(mWeatherIconBitmap, mXWeatherIconTempOffset, mYWeatherIconTempOffset, mWeatherIconPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + bundle);
            }
            sendStartMessage();
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + connectionResult);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        WeatherWatchFaceUtil.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                updateUiForConfigDataMap(config);
            }
        }

        private void updateUiForConfigDataMap(DataMap config) {
            mHighTemp = ""+config.getInt("maxtemp")+getString(R.string.degree);
            mLowTemp = ""+config.getInt("mintemp")+getString(R.string.degree);
            mWeatherIconBitmap = loadBitmapFromAsset(config.getAsset("icon"));
            /* Scale loaded background image (more efficient) if surface dimensions change. */
            float scale = ((float) 50) / (float) mWeatherIconBitmap.getWidth();

            mWeatherIconBitmap = Bitmap.createScaledBitmap(mWeatherIconBitmap,
                    (int) (mWeatherIconBitmap.getWidth() * scale),
                    (int) (mWeatherIconBitmap.getHeight() * scale), true);
            mGrayWeatherIconBitmap = Bitmap.createBitmap(
                    mWeatherIconBitmap.getWidth(),
                    mWeatherIconBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mGrayWeatherIconBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mWeatherIconBitmap, 0, 0, grayPaint);
            invalidate();
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                return null;
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        private void sendStartMessage(){
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(ASK_WEATHER_MESSAGE_PATH);
            putDataMapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());
            PutDataRequest dataRequest = putDataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, dataRequest)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                        }
                    });
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
