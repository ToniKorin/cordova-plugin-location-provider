/*
    Thanks to 
    http://stackoverflow.com/questions/3145089/what-is-the-simplest-and-most-robust-way-to-get-the-users-current-location-in-a
*/
package com.tonikorin.cordova.plugin.LocationProvider;

import java.util.Timer;
import java.util.TimerTask;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import static android.content.Context.POWER_SERVICE;

class MyLocation extends Thread {
    private Timer timer1;
    private Context context;
    private LocationManager lm;
    private LocationResult locationResult;
    private boolean gps_enabled = false;
    private boolean network_enabled = false;
    private int desiredAccuracy = 65; // 65 meter
    private int timeout = 60*1000; // 65 sec
    private static final String TAG = "MyLocation";

    private LocationListener locationListenerNetwork = new LocationListener() {
        public void onLocationChanged(Location location) {
            validateLocation(location);
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };
    private LocationListener locationListenerGps = new LocationListener() {
        public void onLocationChanged(Location location) {
            validateLocation(location);
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    public MyLocation(Context context, LocationResult result, int accuracy, int timeout) {
        this.context = context;
        // LocationResult callback class to pass location value from MyLocation to user code.
        this.locationResult = result;
        this.desiredAccuracy = accuracy;
        this.timeout = timeout * 1000;
    }

    public void run() {
        this.getLocation();
    }

    private void getLocation() {
        if (lm == null)
            lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        //exceptions will be thrown if provider is not permitted.
        try {
            gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ex) {
        }
        try {
            network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
        }

        //don't start listeners if no provider is enabled
        if (!gps_enabled && !network_enabled) {
            locationResult.gotLocation(null);
            return;
        }
        try {
            handleDeepSleepAndPowerSaveModes();
            if (Looper.myLooper() == null) Looper.prepare();
            if (gps_enabled)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListenerGps, Looper.myLooper());
            if (network_enabled)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListenerNetwork, Looper.myLooper());
            timer1 = new Timer();
            timer1.schedule(new GetLastLocation(Looper.myLooper()), timeout);
            Looper.loop();
        } catch (Exception ex) { // very likely user permission missing...
            locationResult.gotLocation(null);
            if (timer1 != null) timer1.cancel();
            Looper.myLooper().quitSafely();
            return;
        }
    }

    private void validateLocation(Location location) {
        if (location.getAccuracy() > desiredAccuracy || (System.currentTimeMillis()-location.getTime()) > this.timeout)
            return; // continue, accuracy or age is not valid
        else { // accuracy ok, stop
            timer1.cancel();
            locationResult.gotLocation(location);
            lm.removeUpdates(locationListenerGps);
            lm.removeUpdates(locationListenerNetwork);
            Looper.myLooper().quitSafely();
        }
    }

    private void handleDeepSleepAndPowerSaveModes(){ // Deep sleep = Doze mode
        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (powerManager != null ) {
            if (android.os.Build.VERSION.SDK_INT >= 23 && powerManager.isDeviceIdleMode()) {
                Log.d(TAG, "In deep sleep mode -- Location service is off -- getLastKnownLocation after 1 sec");
                locationResult.setInDeepSleepTrue();
                timeout = 1000;
            } else if (powerManager.isPowerSaveMode()){
                Log.d(TAG, "Power save mode -- Location service limited -- getLastKnownLocation after 3 sec");
                timeout = 3000;
            }
        }
    }

    public static abstract class LocationResult {
        public abstract void gotLocation(Location location);
        public abstract void setInDeepSleepTrue();
    }

    private class GetLastLocation extends TimerTask {

        private Looper parentLooper; // MyLocation

        GetLastLocation(Looper parentLooper) {
            this.parentLooper = parentLooper;
        }

        @Override
        public void run() {
            lm.removeUpdates(locationListenerGps);
            lm.removeUpdates(locationListenerNetwork);

            Location net_loc = null, gps_loc = null;
            if (gps_enabled)
                gps_loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (network_enabled)
                net_loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            //if there are both values use the latest one
            if (gps_loc != null && net_loc != null) {
                if (gps_loc.getTime() > net_loc.getTime())
                    locationResult.gotLocation(gps_loc);
                else
                    locationResult.gotLocation(net_loc);
            } else {
                if (gps_loc != null)
                    locationResult.gotLocation(gps_loc);
                else if (net_loc != null)
                    locationResult.gotLocation(net_loc);
                else
                    locationResult.gotLocation(null);
            }
            parentLooper.quitSafely();
            timer1.cancel();
        }
    }
}
