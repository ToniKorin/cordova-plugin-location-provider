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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.net.HttpURLConnection;
import java.util.Calendar;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.text.SimpleDateFormat;

import com.tonikorin.cordova.plugin.LocationProvider.MyLocation;
import com.tonikorin.cordova.plugin.LocationProvider.MyLocation.LocationResult;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static java.lang.Math.*;

public class LocationService extends IntentService
{
    private static final String TAG = "LocationService";
    private final MyLocationResult myLocationResult = new MyLocationResult();
    private JSONObject config = null;
    private Context myContext;
    public static final String PREFS_NAME = "LocationService";
    public static final String CONFIG_NAME  = "config";
    public static final String HISTORY_NAME = "history";
    private static final String ALIVE    = "ALIVE";
    private static final String POSITION = "POSITION";
    private static final String FAILURE  = "FAILURE";
    private static final String RESERVED = "RESERVED";
    private static final String CHAT = "CHAT";
    private static final String LOCATE = "LOCATE";
    private static final String ME = "\u29bf";
    private static final String BLOCKING = "\u26d4";

    public LocationService() {
        super("LocationService");
    }

    // The IntentService calls this method from the default worker thread with the intent that
    // started the service. When this method returns, IntentService stops the service.
    @Override
    protected void onHandleIntent(Intent intent)
    {
        try
        {
            myContext = LocationService.this;
            // read the configuration
            if (this.config == null)
            {
                String jsonString = myContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(CONFIG_NAME, "{}");
                config = new JSONObject(jsonString);
            }
            // get the input message from intent
            Bundle extras = intent.getExtras();
            String msgJsonStr = extras.getString("data"); // own "data" property inside "data" property
            String time = extras.getString("time", "");
            if (msgJsonStr != null) {
                JSONObject messageIn = new JSONObject(msgJsonStr);
                handleLocationQuery(messageIn, time);
                return;
            } else {
                String pushToken = extras.getString("regid");
                if (pushToken != null){
                    handlePushTokenUpdates(pushToken);
                    return;
                }
            }
            Log.e(TAG, "Unknown command, extras:  " + extras.toString());
        }
        catch (Exception e)
        {
            Log.e(TAG, "onHandleIntent exception ", e);
        }
    }

    private void handleLocationQuery(JSONObject messageIn, String time) throws JSONException, IOException
    {
        Log.d(TAG, "Handle location query...");
        String ownName = config.optString("member", "");
        JSONObject teams = config.optJSONObject("teams");
        String teamName;
        String teamPassword;
        String teamSecret;
        String teamHost;
        JSONObject team = null;
        if(teams != null)
            team = teams.optJSONObject(messageIn.optString("teamId"));
        if (team != null) {
            teamName = team.optString("name", "");
            teamPassword = team.optString("password", "");
            ownName = team.optString("member", ownName);
            teamHost = team.optString("host", "");
            teamHost = messageIn.optString("host", teamHost);
            teamSecret = team.optString("s");
        }
        else
            return; // => URI hanging in PostServer
        String msgType = messageIn.optString("messageType", LOCATE);
        // Create Messaging Server interface
        String messageUrl = config.optString("messageUrl", "").replace("{host}", teamHost);
        MessageServer msgServer = new MessageServer(ownName, teamName, teamPassword, teamSecret, messageUrl);
        if (messageIn.optString("memberName").equals(ownName))
        {
            updateLocateHistory(messageIn,true, msgType, time);
            msgServer.post(RESERVED);
            SystemClock.sleep(5000);// 5 sec delay
            String pushUrl = config.optString("pushUrl", "").replace("{host}", teamHost);
            String uuid = config.optString("uuid", "");
            msgServer.updatePushToken(ownName, teamName, config.optString("token", ""), uuid, pushUrl);
            return;
        }
        // Read extra team configuration (e.g. icon and schedule)
        TeamConfig cTeam = new TeamConfig(config, teamName);
        // Store details about location query
        updateLocateHistory(messageIn,cTeam.isBlocked(), msgType, time);
        msgServer.addBlockedField(cTeam.isBlocked());
        msgServer.post(ALIVE);
        if(cTeam.isBlocked() || CHAT.equals(msgType)) return; // skip giving your location
        try
        {
            //Log.d(TAG, "myContext: " + myContext.getPackageName());
            foregroundServicePriority();
            int timeout = config.optInt("timeout",60);
            new MyLocation(myContext, myLocationResult, messageIn.optInt("accuracy",50), timeout).start();
            JSONObject location = myLocationResult.getJsonLocation(timeout+2);
            //Log.d(TAG, "Background position accuracy: " + location.optInt("accuracy"));
            msgServer.addIconField(cTeam.getIcon());
            msgServer.addTrackerOffField(cTeam.getTrackerOff());
            msgServer.post(POSITION, location.toString());
        }
        catch (Exception e)
        {
            Log.e(TAG, "LocationProvider exception ", e);
            msgServer.post(FAILURE, e.getMessage());
        }
        Log.d(TAG, "Handle location query...completed!");
    }

    private String getDateAndTimeString(long utcTime)
    {
        Date date = new Date(utcTime);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date);
    }

    private void updateLocateHistory(JSONObject messageIn, boolean blocked, String msgType, String time) throws JSONException
    {   // Read current history
        SharedPreferences sp = myContext.getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE);
        String historyJsonStr = sp.getString(LocationService.HISTORY_NAME, "{}");
        JSONObject history = new JSONObject(historyJsonStr);
        if(CHAT.equals(msgType)){ // CHAT history, save hole message
            JSONArray chatMessages = history.optJSONArray("chatMessages");
            if (chatMessages == null) chatMessages = new JSONArray();
            //Log.d(TAG, "CHAT history TIME: " + time);
            messageIn.put("time",Long.parseLong(time));
            chatMessages.put(messageIn.toString());
            if (chatMessages.length() > 100)
                chatMessages.remove(0); // store only last 100 chat messages
            history.put("chatMessages", chatMessages);
        } else {// Current LOCATE
            String member = messageIn.optString("memberName", "");
            if (member.equals(ME)) return; // skip history if own query
            if (blocked) member = BLOCKING + " " + member;
            JSONObject updateStatus = new JSONObject();
            updateStatus.put("member", member);
            updateStatus.put("team", messageIn.optString("teamId", ""));
            updateStatus.put("date", getDateAndTimeString(System.currentTimeMillis()));
            updateStatus.put("target", messageIn.optString("target", ""));
            history.put("updateStatus", updateStatus);
            // History LOCATE lines...
            JSONArray historyLines = history.optJSONArray("lines");
            if (historyLines == null) historyLines = new JSONArray();
            String target;
            if (updateStatus.getString("target").equals(""))
                target = updateStatus.optString("team");
            else
                target = updateStatus.optString("target");
            String historyLine = updateStatus.getString("member") + " (" + target + ") " + updateStatus.getString("date") + "\n";
            historyLines.put(historyLine);
            if (historyLines.length() > 200)
                historyLines.remove(0); // store only last 200 locate queries
            history.put("lines", historyLines);
        }
        // Save new history
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(LocationService.HISTORY_NAME, history.toString());
        editor.commit();
        //Log.d(TAG, "history:" + history.toString());
    }

    private void handlePushTokenUpdates(String pushToken) {
        Log.d(TAG, "Handle push token updates...");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                String CHANNEL_ID = "LocationService_channel_01";
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "Token update",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
                Notification notification = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle(getAppName())
                        .setContentText("Push token updated").build();
                startForeground(999, notification);
            }
            JSONObject teams = config.optJSONObject("teams");
            if (teams != null) {
                String uuid = config.optString("uuid", "");
                MessageServer pushServer = new MessageServer();
                JSONArray teamNames = teams.names();
                for (int i = 0; i < teamNames.length(); ++i) {
                    JSONObject team = teams.optJSONObject(teamNames.getString(i));
                    String teamName = team.optString("name", "");
                    String ownName = team.optString("member", "");
                    String teamHost = team.optString("host", "");
                    String pushUrl = config.optString("pushUrl", "").replace("{host}", teamHost);
                    pushServer.updatePushToken(ownName, teamName, pushToken, uuid, pushUrl);
                }
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "updatePushTokens exception ", e);
        }
    }

    private void foregroundServicePriority() {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            return;
        }
        Log.d(TAG, "Set app to foreground service priority to complete the location service properly...");
        try {
            String CHANNEL_ID = "LocationService_channel_02";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Location priority query",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(getAppName())
                    .setContentText("Your location queried").build();
            startForeground(9999, notification, FOREGROUND_SERVICE_TYPE_LOCATION);
        }
        catch (Exception e)
        {
            Log.e(TAG, "foregroundServicePriority exception ", e);
        }
    }

    private String getAppName()
    {
        Context context = getApplicationContext();
        CharSequence appName = context
                        .getPackageManager()
                        .getApplicationLabel(context.getApplicationInfo());
        return (String)appName;
    }

    private class MessageServer
    {
        private HttpURLConnection con;
        private JSONObject messageOut;
        private OutputStream os;
        private String xTeam;
        private String xPass;
        private String xSecret;
        private String xChannel;
        private String urlString;
        private static final String TOKEN = "TOKEN";
        //private InputStream is;

        public MessageServer(){
        }

        public MessageServer(String ownName, String teamName, String teamPassword, String teamSecret,String urlMessageServer) throws JSONException, IOException
        {  // Create message template for location data
            this.messageOut = new JSONObject();
            this.messageOut.put("memberName", ownName);
            this.messageOut.put("teamId", teamName);
            this.xTeam = teamName;
            this.xPass = teamPassword;
            this.xSecret = teamSecret;
            this.xChannel = "/channel/" + teamName + ":" + teamPassword;
            this.urlString = urlMessageServer;
        }

        public void updatePushToken(String ownName, String teamName, String pushToken, String uuid, String urlPushServer) throws JSONException, IOException
        {   // Create message template for push server and post it
            this.messageOut = new JSONObject();
            this.messageOut.put("name", ownName);
            this.messageOut.put("team", teamName);
            this.messageOut.put("token", pushToken);
            this.messageOut.put("tokenType", 3); // GCM
            this.messageOut.put("uuid", uuid);
            this.urlString = urlPushServer;
            this.post(TOKEN);
        }

        public void post(String messageType) throws JSONException, IOException
        {
            Log.d(TAG, "POST: " + messageType);
            if( !messageType.equals(TOKEN) )// messageType property not needed in PushServer
                messageOut.put("messageType", messageType);
            try
            {   // Create connection and request
                URL url = new URL(urlString);
                if(url.getProtocol().equals("https"))
                    con = (HttpsURLConnection) url.openConnection();
                else
                    con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true); // = POST method
                //con.setDoInput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("X-channel", xChannel);
                con.setRequestProperty("X-team", xTeam);
                con.setRequestProperty("X-pass", xPass);
                con.setRequestProperty("X-s", xSecret);
                con.setChunkedStreamingMode(0);
                // Send POST with JSON message as string
                os = con.getOutputStream();
                os.write(messageOut.toString().getBytes("UTF-8"));
                os.flush();
                Log.d(TAG, "POST response code:" + con.getResponseCode());
            } catch (Exception e){
                Log.e(TAG, "POST exception: " + e.getMessage());
                e.printStackTrace();
            }
            finally
            {
                if (os!=null) os.close();
                if (con!=null) con.disconnect(); // connection just moved to pool
            }
        }

        public void post(String messageType, String content) throws JSONException, IOException
        {
            messageOut.put("content", content);
            post(messageType);
        }

        public void addIconField(String icon) throws JSONException
        {
            if (icon!=null) messageOut.put("icon", icon);
        }

        public void addTrackerOffField(String trackerOff) throws JSONException
        {
            if (trackerOff!=null) messageOut.put("trackerOff", trackerOff);
        }

        public void addBlockedField(boolean blocked) throws JSONException
        {
            if (blocked) messageOut.put("blocked", true);
        }
    }

    private class MyLocationResult extends LocationResult
    {
        private final CountDownLatch locationLatch = new CountDownLatch(1);
        private Location location = null;
        private boolean deepSleep = false;
        private static final int ROUND6 = 1000000;

        @Override
        public void gotLocation(Location location)
        {
            //Log.v(TAG,"gotLocation from " + location.getProvider());
            this.location = location;
            locationLatch.countDown(); // release await in getJsonLocation
        }

        @Override
        public void setInDeepSleepTrue() {
            deepSleep = true;
        }

        public JSONObject getJsonLocation(int timeout) throws JSONException, InterruptedException
        {
            locationLatch.await(timeout, TimeUnit.SECONDS);
            JSONObject loc = new JSONObject();
            if (location == null) throw new InterruptedException();
            loc.put("latitude", (double) round(location.getLatitude() * ROUND6) / ROUND6); // 6 decimals = 1m accuracy
            loc.put("longitude", (double) round(location.getLongitude() * ROUND6) / ROUND6);
            loc.put("accuracy", round(location.getAccuracy()));
            loc.put("altitude", round(location.getAltitude()));
            loc.put("altitudeAccuracy", "-"); // not supported in Android
            loc.put("heading", round(location.getBearing()));
            loc.put("speed", round(location.getSpeed()));
            loc.put("timestamp", getDateAndTimeString(location.getTime()));
            loc.put("mTime", location.getTime());
            if (deepSleep) { // age is not relevant is deep sleep mode...
                loc.put("age", 0);
            } else {
                loc.put("age", System.currentTimeMillis() - location.getTime());
            }
            //Log.d(TAG, "Time:" + getDateAndTimeString(System.currentTimeMillis()) + " Location age:" + loc.getInt("age"));
            return loc;
        }
    }

    private class TeamConfig
    {
        private String icon = null;
        private String trackerOff = null;
        private boolean blocked = false;

        public TeamConfig(JSONObject conf, String teamName)
        {
            JSONObject cTeams = conf.optJSONObject("cTeams");
            JSONObject cTeam = null;
            if(cTeams == null)
                return;
            else
                cTeam = cTeams.optJSONObject(teamName);
            if( cTeam != null){
                icon = cTeam.optString("icon", null);
                trackerOff = cTeam.optString("trackerOff", null);
                long startDate = cTeam.optLong("startDate", 0); // epoch
                long endDate = cTeam.optLong("endDate", 0);
                int startTime = cTeam.optInt("startTime", 0); // total minutes from day start
                int endTime = cTeam.optInt("endTime", 0);
                String repeat = cTeam.optString("repeat", ""); // weekdays 0-6
                blocked = isLocationBlocked(startDate, endDate, startTime, endTime, repeat);
            }
        }

        public String getIcon() { return icon;}

        public String getTrackerOff() { return trackerOff;}

        public boolean isBlocked() {
            return blocked;
        }

        private boolean isLocationBlocked(long startDate, long endDate, int startTime, int endTime, String repeat)
        {
            if (startDate > 0 || endDate > 0 || !repeat.isEmpty()) {
                Calendar c = Calendar.getInstance();
                long time = c.getTimeInMillis();
                if (startDate > 0) {
                    long start = startDate + (startTime * 60 * 1000);
                    if (time < start)
                        return true;
                }
                if (endDate > 0) {
                    long end = endDate + (endTime * 60 * 1000);
                    if (time > end)
                        return true;
                }
                if (!repeat.isEmpty()) {
                    int nowInMinutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
                    if(startTime == 0 && endTime == 0) endTime = 1440; // hole day if not set
                    int day = c.get(Calendar.DAY_OF_WEEK)-1;
                    int index = repeat.indexOf(Integer.toString(day));
                    if (startTime > endTime && index == -1) { // check also previous day
                        int prevDay = day - 1;
                        if (prevDay == -1) prevDay = 6;
                        index = repeat.indexOf(Integer.toString(prevDay));
                    }
                    if (index == -1)
                        return true;
                    if (startTime < endTime) {
                        if (nowInMinutes < startTime || nowInMinutes > endTime)
                            return true;
                    } else {
                        if (nowInMinutes < startTime && nowInMinutes > endTime)
                            return true;
                    }
                }
            }
            return false;
        }
    }

} 
