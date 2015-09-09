package com.example.android.sunshine.app.sync;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.example.android.sunshine.app.ForecastFragment;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.example.android.sunshine.app.data.WeatherProvider;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;

/**
 * Created by biche on 09/09/2015.
 */
public class WearableWatchFaceListener extends WearableListenerService {
    public static final String ASK_WEATHER_MESSAGE_PATH = "/ask_weather_data";
    public static final String TAG = "WearableWatchFaceListen";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        super.onDataChanged(dataEvents);
        Toast.makeText(getApplicationContext(),"testtt",Toast.LENGTH_SHORT).show();
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }

            DataItem dataItem = dataEvent.getDataItem();
            if (!dataItem.getUri().getPath().equals(
                    ASK_WEATHER_MESSAGE_PATH)) {
                continue;
            }
            sendWeatherData(this);
        }
    }

    public static void sendWeatherData(Context context){
        String location = Utility.getPreferredLocation(context);
        Uri updatedUri = WeatherContract.WeatherEntry.buildWeatherLocation(location);
        Cursor cursor = context.getContentResolver().query(updatedUri, ForecastFragment.FORECAST_COLUMNS, null, null, null);
        if (cursor!=null){
            if (cursor.getCount()>0){
                cursor.moveToFirst();
                GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                        .addApi(Wearable.API)
                        .build();

                ConnectionResult connectionResult =
                        googleApiClient.blockingConnect(30, TimeUnit.SECONDS);

                if (!connectionResult.isSuccess()) {
                    Log.e(TAG, "Failed to connect to GoogleApiClient.");
                    return;
                }
                PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(ForecastFragment.DATAMAP_REQUEST_PATH);
                putDataMapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());
                int mMinTemp = cursor.getInt(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP));
                int mMaxTemp = cursor.getInt(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP));
                int weatherId = cursor.getInt(ForecastFragment.COL_WEATHER_CONDITION_ID);
                Bitmap mIcon = BitmapFactory.decodeResource(context.getResources(), Utility.getArtResourceForWeatherCondition(weatherId));
                putDataMapRequest.getDataMap().putInt("maxtemp", mMaxTemp);
                putDataMapRequest.getDataMap().putInt("mintemp", mMinTemp);
                putDataMapRequest.getDataMap().putAsset("icon", Utility.createAssetFromBitmap(mIcon));

                PutDataRequest dataRequest = putDataMapRequest.asPutDataRequest();
                Wearable.DataApi.putDataItem(googleApiClient,dataRequest)
                        .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(DataApi.DataItemResult dataItemResult) {

                            }
                        });
            }
            cursor.close();
        }
    }
}
