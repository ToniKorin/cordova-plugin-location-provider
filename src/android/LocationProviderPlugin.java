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

import android.util.Log;

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
    public boolean execute(String action, JSONArray args,
                           CallbackContext callback) throws JSONException {

        if (action.equalsIgnoreCase("setConfiguration")) {
            JSONObject config = args.getJSONObject(0);
            saveConfiguration(config);
            return true;
        } else if (action.equalsIgnoreCase("getAndClearHistory")) {
            JSONObject history = readAndClearHistory();
            callback.success(history);
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

    private void startService(String notification) {
        Log.d(TAG, "startService");
        Context context = cordova.getActivity().getApplicationContext();
        Intent serviceIntent = new Intent();
        serviceIntent.putExtra("data", notification);
        serviceIntent.setClassName(context, "com.tonikorin.cordova.plugin.LocationProvider.LocationService");
        context.startService(serviceIntent);
    }
}
