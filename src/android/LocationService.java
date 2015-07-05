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

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.Iterator;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.net.MalformedURLException;
import java.io.OutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import android.location.Location;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.util.Log;

import com.tonikorin.cordova.plugin.LocationProvider.MyLocation;
import com.tonikorin.cordova.plugin.LocationProvider.MyLocation.LocationResult;

public class LocationService extends IntentService {

  public static final String PREFS_NAME = "LocationService";
  public static final String TAG = "Cordova " + PREFS_NAME;
  public static final String CONFIG_NAME = "config";
  public static final String HISTORY_NAME = "history";
  private JSONObject config = null;
  private Context myContext;
  private final MyLocationResult myLocationResult = new MyLocationResult();
  
  public LocationService() {
    super("LocationService");
  }

  /**
   * The IntentService calls this method from the default worker thread with
   * the intent that started the service. When this method returns, IntentService
   * stops the service, as appropriate.
   */
  @Override
  protected void onHandleIntent(Intent intent) {    
    try {
        myContext = LocationService.this;
        // read the configuration
        if(this.config == null){
            String jsonString = myContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(CONFIG_NAME,"{}");
            config = new JSONObject(jsonString);
        }
        // get the input message from intent
        Bundle extras = intent.getExtras();
        String msgJsonStr = extras.getString("data"); // own "data" property inside "data" property
        JSONObject messageIn = new JSONObject(msgJsonStr);
        handleLocationQuery(messageIn);
    } catch (Exception e) {
        Log.e(TAG, "onHandleIntent exception ",e);
    }
  }

  private void handleLocationQuery(JSONObject messageIn) throws JSONException, MalformedURLException, IOException, UnsupportedEncodingException{
            
    Log.d(TAG, "Handle location query...");
    
    // Store details about latest location query
    // todo writeUpdateStatus(messageIn);

    String ownName = config.optString("member","");

    // Read team password
    JSONObject teams = config.optJSONObject("teams");
    String teamName = "";
    String teamPassword = "";
    for(Iterator<String> iter = teams.keys();iter.hasNext();) {
        String team = iter.next();
        if(team.equals(messageIn.optString("teamId"))){
            teamName = teams.optJSONObject(team).optString("name","");
            teamPassword = teams.optJSONObject(team).optString("password","");
            break;
        }
    }
    if (ownName.equals("") || teamName.equals(""))
        return; // => URI hanging in PostLocator
    
    // Messaging Server interface
    MessageServer msgServer = new MessageServer(ownName, teamName, teamPassword, config.optString("messageUrl",""));

    if (messageIn.optString("memberName").equals(ownName))
    {
        msgServer.post("RESERVED");
        //msgServer.disconnect();
        /* todo
        PushLocator pushLocator = new PushLocator(ownName, messageIn.teamId, "");
        // 5 sec delay
        pushLocator.updateToken();
        */
        return;
    }
    msgServer.post("ALIVE");
    try
    {
        Log.d(TAG,"myContext: " + myContext.getPackageName());
        new MyLocation(myContext, myLocationResult,  messageIn.optInt("accuracy"), 60 /* sec timeout */).start();
        JSONObject location = myLocationResult.getJsonLocation();
        Log.d(TAG,"Background position accuracy: " + location.optInt("accuracy"));
        msgServer.post("POSITION", location.toString());
    }
    catch (Exception e)
    {
        Log.e(TAG, "LocationProvider exception ",e);
        msgServer.post("FAILURE", e.getMessage());
    }
    Log.d(TAG, "Handle location query...completed!");
  }
  

    private class MessageServer{
          
        private HttpsURLConnection con;
        private JSONObject messageOut;
        private OutputStream os;
        private String xChannel;
        private String urlString;
        //private InputStream is;

        public MessageServer(String ownName, String team, String password, String urlString) throws JSONException, MalformedURLException, IOException {
            // create message template
            this.messageOut = new JSONObject();
            this.messageOut.put("memberName", ownName);
            this.messageOut.put("teamId", team);
            this.xChannel = "/channel/" + team + ":" + password;
            this.urlString = urlString;
        }

        public void post(String messageType) throws JSONException, UnsupportedEncodingException, IOException {
            Log.d(TAG, "POST: " + messageType);
            messageOut.put("messageType", messageType);          
            try{
                // Create connection and request
                URL url = new URL(urlString);
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod( "POST" );
                con.setDoOutput(true); // = POST method
                //con.setDoInput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("X-channel", xChannel);
                con.setChunkedStreamingMode(0);
                // Send POST with JSON message as string
                os = con.getOutputStream();
                os.write(messageOut.toString().getBytes("UTF-8"));
                os.flush();
                Log.d(TAG, "POST response code:" + con.getResponseCode());
            }finally{
                os.close();
                con.disconnect(); // connection just moved to pool
            }
        }

        public void post(String messageType, String content) throws JSONException, UnsupportedEncodingException, IOException {
            messageOut.put("content", content);
            post(messageType);
        }
              
    }
    
     private class MyLocationResult extends LocationResult{
            private Location location = null;
            private final CountDownLatch locationLatch = new CountDownLatch(1);
            
            @Override
            public void gotLocation(Location location){
                this.location = location;
                locationLatch.countDown(); // release await in getJsonLocation
            }
            public JSONObject getJsonLocation() throws JSONException, InterruptedException {
            // todo round and convert or maybe client is enough...
                locationLatch.await(2, TimeUnit.MINUTES);            
                JSONObject loc = new JSONObject();
                if(location == null) throw new InterruptedException();
                loc.put("latitude", location.getLatitude());
                loc.put("longitude", location.getLongitude());
                loc.put("accuracy", location.getAccuracy());
                loc.put("altitude", location.getAltitude());
                loc.put("altitudeAccuracy", "-"); // not supported in Android
                loc.put("heading", location.getBearing());
                loc.put("speed", location.getSpeed());
                loc.put("timestamp", this.getDateAndTimeString(location.getTime()));
                return loc;
            }
            public String getDateAndTimeString(long utcTime){
                Date date = new Date(utcTime);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");    
                return sdf.format(date);
            }
    }
} 
  
