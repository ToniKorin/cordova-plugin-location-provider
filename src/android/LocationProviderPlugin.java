/*
 Author: Toni Korin

 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */
package com.tonikorin.cordova.plugin.LocationProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import android.content.SharedPreferences;

import com.tonikorin.cordova.plugin.LocationProvider.LocationService;
import com.tonikorin.cordova.plugin.LocationProvider.MyLocation;

import android.location.Location;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.round;

public class LocationProviderPlugin extends CordovaPlugin {

    private static final String TAG = "LocationProviderPlugin";

    /**
     * Executes the request.
     *
     * @param action   The action to execute.
     * @param args     The exec() arguments.
     * @param callback The callback context used when calling back into
     *                 JavaScript.
     * @return Returning false results in a "MethodNotFound" error.
     * @throws JSONException
     */
    @Override
    public boolean execute(String action, final JSONArray args,
                           final CallbackContext callback) throws JSONException {

        if (action.equalsIgnoreCase("setConfiguration")) {
            JSONObject config = args.getJSONObject(0);
            saveConfiguration(config);
            return true;
        } else if (action.equalsIgnoreCase("getAndClearHistory")) {
            JSONObject history = readAndClearHistory();
            callback.success(history);
            return true;
        } else if (action.equalsIgnoreCase("getOwnPosition")) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        JSONObject params = args.getJSONObject(0);
                        JSONObject position = getOwnPosition(params);
                        if (position == null) {
                            JSONObject error = new JSONObject();
                            error.put("message", "generic location provider failure");
                            error.put("code", -1);
                            callback.error(error);
                        } else {
                            callback.success(position);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "own position, json failed", e);
                    }
                }
            });
            return true;
        } else if (action.equalsIgnoreCase("startService")) {
            String notification = args.getString(0);
            startService(notification); // for testing your config without GCM
            return true;
        }
        return false;
    }

    private void saveConfiguration(JSONObject config) {
        Log.d(TAG, "saveConfiguration");
        SharedPreferences sp = getSharedPreferences();
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(LocationService.CONFIG_NAME, config.toString());
        editor.commit();
    }

    private JSONObject readAndClearHistory() throws JSONException {
        Log.d(TAG, "readAndClearHistory");
        SharedPreferences sp = getSharedPreferences();
        String historyJsonStr = sp.getString(LocationService.HISTORY_NAME, "{}");
        // Clear
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(LocationService.HISTORY_NAME, "{}");
        editor.commit();
        return new JSONObject(historyJsonStr);
    }

    private SharedPreferences getSharedPreferences() {
        Context context = cordova.getActivity().getApplicationContext();
        return context.getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private class MyOwnLocationResult extends MyLocation.LocationResult {
        private final CountDownLatch locationLatch = new CountDownLatch(1);
        private Location location = null;

        @Override
        public void gotLocation(Location location)
        {
            this.location = location;
            locationLatch.countDown(); // release await in getJsonLocation
        }

        @Override
        public void setInDeepSleepTrue() {}
        @Override
        public void setPowerSaveTrue() {}

        public JSONObject getJsonOwnLocation(int timeout) throws JSONException, InterruptedException
        {
            locationLatch.await(timeout, TimeUnit.SECONDS);
            JSONObject loc = new JSONObject();
            JSONObject coords = new JSONObject();
            if (location == null) throw new InterruptedException();
            coords.put("latitude", location.getLatitude());
            coords.put("longitude", location.getLongitude());
            coords.put("accuracy", round(location.getAccuracy()));
            coords.put("altitude", round(location.getAltitude()));
            coords.put("heading", round(location.getBearing()));
            coords.put("speed", round(location.getSpeed()));
            loc.put("coords", coords);
            loc.put("timestamp", location.getTime());
            return loc;
        }
    }

    private JSONObject getOwnPosition(JSONObject config)
    {
        Log.d(TAG, "getOwnPosition");
        Context ctx = cordova.getActivity().getApplicationContext();
        int accuracy = config.optInt("accuracy",50);
        int timeout = config.optInt("timeout",60);
        MyOwnLocationResult myLocationResult = new MyOwnLocationResult();
        try {
            new MyLocation(ctx, myLocationResult, accuracy, timeout).start();
            return myLocationResult.getJsonOwnLocation(timeout + 2);
        } catch (Exception e)
        {
            Log.e(TAG, "Own position exception ", e);
            return null;
        }
    }

    private void startService(String notification) {
        Log.d(TAG, "startService");
        Context context = cordova.getActivity().getApplicationContext();
        Intent serviceIntent = new Intent();
        serviceIntent.putExtra("data", notification);
        serviceIntent.setClassName(context, "com.tonikorin.cordova.plugin.LocationProvider.LocationService");
        context.startService(serviceIntent);
    }
}
