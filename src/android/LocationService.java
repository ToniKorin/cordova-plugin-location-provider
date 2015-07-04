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
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.io.OutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import android.location.LocationManager;
import android.location.LocationListener;
import android.location.Location;
import android.os.Looper;
import android.location.Criteria;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.util.Log;

public class LocationService extends IntentService {

  public static final String PREFS_NAME = "LocationService";
  public static final String CONFIG_NAME = "config";
  public static final String HISTORY_NAME = "history";
  private JSONObject config;
  
  /**
   * A constructor is required, and must call the super IntentService(String)
   * constructor with a name for the worker thread.
   */
  public LocationService() throws JSONException {
      super("LocationService");
      // read configuration
      String jsonString = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(CONFIG_NAME,"{}");
      config = new JSONObject(jsonString);
  }

  /**
   * The IntentService calls this method from the default worker thread with
   * the intent that started the service. When this method returns, IntentService
   * stops the service, as appropriate.
   */
  @Override
  protected void onHandleIntent(Intent intent) {
      // Normally we would do some work here, like download a file.
      // For our sample, we just sleep for 5 seconds.
      synchronized (this) {
          try {
              Bundle extras = intent.getExtras();
              String msgJsonStr = extras.getString("data"); // own "data" property inside "data" property
              JSONObject messageIn = new JSONObject(msgJsonStr);
              handleLocationQuery(messageIn);
          } catch (Exception e) {
              Log.e("Cordova " + PREFS_NAME, "onHandleIntent exception ",e);
          }
      }
  }


  private void handleLocationQuery(JSONObject messageIn) throws JSONException, MalformedURLException, IOException, UnsupportedEncodingException{
            
    Log.d("Cordova " + PREFS_NAME, "Handle location query...");
    
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
        msgServer.disconnect();
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
        LocationProvider locationProvider = new LocationProvider();
        JSONObject location = locationProvider.getCurrentLocation( messageIn.optInt("accuracy"));
        Log.d("Cordova " + PREFS_NAME,"Background position accuracy: " + location.optInt("accuracy"));
        msgServer.post("POSITION", location.toString());
    }
    catch (Exception e)
    {
        Log.e("Cordova " + PREFS_NAME, "LocationProvider exception ",e);
        msgServer.post("FAILURE", e.getMessage());
    }

    msgServer.disconnect();

    Log.d("Cordova " + PREFS_NAME, "Handle location query...completed!");
  }
  

    private class MessageServer{
          
        private HttpURLConnection con;
        private JSONObject messageOut; 

        public MessageServer(String ownName, String team, String password, String urlString) throws JSONException, MalformedURLException, IOException {
        // create message
        messageOut = new JSONObject();
        messageOut.put("memberName", ownName);
        messageOut.put("teamId", team);
        // create connection
        URL url = new URL(urlString);
        con = (HttpURLConnection) url.openConnection();
        con.setDoOutput(true); // = POST method
        con.setDoInput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("X-channel", "/channel/" + team + ":" + password);
        }

        public void post(String messageType) throws JSONException, UnsupportedEncodingException, IOException {
        messageOut.put("messageType", messageType);          
        OutputStream os = con.getOutputStream();
        os.write(messageOut.toString().getBytes("UTF-8"));
        os.close();
        }

        public void post(String messageType, String content) throws JSONException, UnsupportedEncodingException, IOException {
          messageOut.put("content", content);
          post(messageType);
        }

        public void disconnect(){
          con.disconnect();
        }
              
    }
      
    private final class LocationProvider implements LocationListener {
        
        private Location location;
     
        public JSONObject getCurrentLocation(int accuracy) throws JSONException {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Looper.prepare();
            // Request one GPS update. The third param (null) is the looper to use, which defaults the one for the current thread.
            lm.requestSingleUpdate(getLocationCriteria(accuracy), this /*LocationListener*/, null /* Looper */);
            Looper.loop(); // start waiting...when this is done, we'll have the location in this.location
            return getJsonLocation();
        }

        @Override
        public void onLocationChanged(Location location) {
            // Store the location, then get the current thread's looper and tell it to
            // quit looping so it can continue on doing work with the new location.
            this.location = location;
            Looper.myLooper().quit();
        }
        
        @Override
        public void onProviderDisabled(String provider) {
           // called when the GPS provider is turned off (user turning off the GPS on the phone)
        }

        @Override
        public void onProviderEnabled(String provider) {
           // called when the GPS provider is turned on (user turning on the GPS on the phone)
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
           // called when the status of the GPS provider changes
        }

        
        private Criteria getLocationCriteria(int accuracy){
            
            Criteria criteria =  new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            if( accuracy < 50){
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
                criteria.setAltitudeRequired(true);
            }else if ( accuracy < 100 )
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
            else
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_MEDIUM);

            return criteria;
        }
        
        private JSONObject getJsonLocation() throws JSONException {
            // todo round and convert or maybe client is enough...
            JSONObject loc = new JSONObject();
            loc.put("latitude", location.getLatitude());
            loc.put("longitude", location.getLongitude());
            loc.put("accuracy", location.getAccuracy());
            loc.put("altitude", location.getAltitude());
            loc.put("altitudeAccuracy", "-"); // not supported in Android
            loc.put("heading", location.getBearing());
            loc.put("speed", location.getSpeed());
            loc.put("timestamp", getDateAndTimeString(location.getTime()));
            return loc;
        }
        
        private String getDateAndTimeString(long utcTime){
            Date date = new Date(utcTime);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");    
            return sdf.format(date);
        }
    }
} 
  
