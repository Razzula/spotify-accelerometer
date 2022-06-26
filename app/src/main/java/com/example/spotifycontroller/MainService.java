package com.example.spotifycontroller;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static java.lang.Thread.sleep;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.PlayerApi;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.types.Capabilities;
import com.spotify.protocol.types.UserStatus;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

public class MainService extends Service {

    private static final String TAG = "MainService";
    private static final String CLIENT_ID = "c3ea15ea37eb4121a64ee8af3521f832";
    private static final String REDIRECT_URI = "com.example.spotifycontroller://callback";
    public static MainService context;

    SpotifyAppRemote mSpotifyAppRemote;
    PlayerApi playerApi;
    private BroadcastReceiver broadcastReceiver;

    int currentTrackLength = 0;
    ArrayList<Float> velocities;

    boolean valid = false;

    actionAtEndOfTrack endOfTrackAction;
    actionTowardsEndOfTrack startLocationTracking;

    FusedLocationProviderClient fusedLocationProviderClient;
    Location lastKnownLocation;

    boolean queued = false;
    public static boolean active = false;
    private boolean repeatEnabled;
    private boolean minorRepeatEnabled;
    private int minorRepeatRate;

    private int fadeDuration = 0;
    private int LocationPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY;

    ArrayList<MainActivity.Track> playlist;
    ArrayList<MainActivity.Track> fullPlaylist;
    Queue<String> recentlyPlayed;
    String playlistURI;

    public class actionAtEndOfTrack extends Thread {
        long timeToWait = 0;

        public void run() {

            if (timeToWait < 1000) {
                return;
            }

            try {
                sleep(timeToWait); // wait until near end of song
                if (!queued) {
                    queued = true;
                    //getLocation();
                    stopLocationUpdates();
                    queueNextTrack();
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void setTimeToWait(long timeToWait) {
            this.timeToWait = timeToWait;
        }
    }

    public class actionTowardsEndOfTrack extends Thread {
        long timeToWait = 0;

        public void run() {

            if (timeToWait < 0) {
                return;
            }

            try {
                sleep(timeToWait); // wait until near end of song
                startLocationUpdates();

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void setTimeToWait(long timeToWait) {
            this.timeToWait = timeToWait;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.e(TAG, "Service created");

        this.context = this; //TODO, use sharedPreferences, not static variables
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startID) {
        //START
        if (intent.getAction().equals("START")) {

            Log.e(TAG, "Service started");

            //data
            fullPlaylist = MainActivity.context.playlist;
            playlist = (ArrayList<MainActivity.Track>) fullPlaylist.clone();
            recentlyPlayed = new LinkedList();
            playlistURI = MainActivity.context.selectedPlaylistID;

            // PREFERENCES LISTENER
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            minorRepeatEnabled = prefs.getBoolean("allowMinorRepetition", false);
            minorRepeatRate = prefs.getInt("repetitionTolerance", 1);
            if (minorRepeatRate >= playlist.size()) {
                minorRepeatRate = playlist.size() - 1;
            }
            repeatEnabled = prefs.getBoolean("repeatEnabled", false);
            updateLocationPriority(prefs);

            SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs1, key) -> {
                switch (key) {
                    case "allowMinorRepetition":
                        minorRepeatEnabled = prefs1.getBoolean("allowMinorRepetition", false);
                        minorRepeatRate = prefs1.getInt("repetitionTolerance", 1);
                        if (minorRepeatRate >= playlist.size()) {
                            minorRepeatRate = playlist.size() - 1;
                        }
                        break;
                    case "repetitionTolerance":
                        minorRepeatRate = prefs1.getInt("repetitionTolerance", 1);
                        if (minorRepeatRate >= playlist.size()) {
                            minorRepeatRate = playlist.size() - 1;
                        }
                        break;
                    case "repeatEnabled":
                        repeatEnabled = prefs1.getBoolean("repeatEnabled", false);
                        break;
                    case "locationAccuracy":
                        updateLocationPriority(prefs1);
                        break;
                }

                if (repeatEnabled && minorRepeatEnabled) {
                    playlist = (ArrayList<MainActivity.Track>) fullPlaylist.clone();
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(listener);

            connectToSpotifyApp();
            return START_STICKY;
        }
        // STOP
        else if (intent.getAction().equals("HALT")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // BECOME FOREGROUND SERVICE
        else if (intent.getAction().equals("TO_FORE")) {
            // NOTIFICATION
            Intent stopSelf = new Intent(this, MainService.class);
            stopSelf.setAction("HALT");
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, stopSelf,PendingIntent.FLAG_CANCEL_CURRENT);

            createNotificationChannel();

            Notification notification =
                    new Notification.Builder(this, "foregroundAlert")
                            .setSmallIcon(R.drawable.logo)
                            .setContentTitle("Controller is still active")
                            .setContentText("Tap to stop.")
                            .setOngoing(true)
                            .setAutoCancel(true)
                            .setContentIntent(pendingIntent)
                            .build();

            startForeground(NotificationCompat.PRIORITY_LOW, notification);
            return START_STICKY;
        }
        // RETURN TO BACKGROUND
        else if (intent.getAction().equals("TO_BACK")) {
            stopForeground(true);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel("foregroundAlert", name, importance);
        channel.setDescription(description);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private void beginProcess() {
        playerApi = mSpotifyAppRemote.getPlayerApi();

        playerApi.getCrossfadeState()
                 .setResultCallback(crossfadeState -> fadeDuration = crossfadeState.duration);

        active = true;

        // BROADCAST RECEIVER
        broadcastReceiver = new SpotifyBroadcastReceiver();

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.spotify.music.playbackstatechanged");
        filter.addAction("com.spotify.music.metadatachanged");

        getApplicationContext().registerReceiver(broadcastReceiver, filter);

        // INITIALISE QUEUE
        playerApi.getPlayerState().setResultCallback(playerState -> {
            if (playerState.isPaused) {
                playerApi.play("spotify:playlist:"+playlistURI);
            }
        });
    }

    private void updateLocationPriority(SharedPreferences prefs) {
        String priority = prefs.getString("locationAccuracy", "Balance Location Accuracy and Battery Life");
        switch (priority) {
            case "Favour Location Accuracy":
                LocationPriority = Priority.PRIORITY_HIGH_ACCURACY;
                break;
            case "Balance Location Accuracy and Battery Life":
                LocationPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY;
                break;
            case "Favour Battery Life":
                LocationPriority = Priority.PRIORITY_LOW_POWER;
                break;
        }
    }

    @Override
    public void onDestroy() {
        active = false;
        try {
            getApplicationContext().unregisterReceiver(broadcastReceiver);
        }
        catch (IllegalArgumentException ignored) {}

        if (endOfTrackAction != null) {
            endOfTrackAction.interrupt();
        }
        if (startLocationTracking != null) {
            startLocationTracking.interrupt();
        }
        stopLocationUpdates();

        if (mSpotifyAppRemote != null) {
            SpotifyAppRemote.disconnect(mSpotifyAppRemote);
        }

        MainActivity.context.setSwitch(false);

        Log.e(TAG, "Service stopped");
        super.onDestroy();
    }

    // INTERACTION WITH ANDROID SDK

    private void connectToSpotifyApp() {
        ConnectionParams connectionParams =
                new ConnectionParams.Builder(CLIENT_ID)
                        .setRedirectUri(REDIRECT_URI)
                        .showAuthView(true)
                        .build();

        SpotifyAppRemote.connect(this, connectionParams,
                new Connector.ConnectionListener() {

                    @Override
                    public void onConnected(SpotifyAppRemote spotifyAppRemote) {
                        mSpotifyAppRemote = spotifyAppRemote;
                        beginProcess();
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        Log.e(TAG, throwable.getMessage(), throwable);

                        // Handle errors here
                        AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
                        builder.setMessage(R.string.dialogue_appRemoteFail)
                                .setTitle(R.string.dialogue_appRemoteFail_T);

                        stopSelf();
                    }
                });
    }

    public void onMetadataChange(String trackID, int trackLength, String trackName) {

        currentTrackLength = trackLength;
        queued = false;

        Log.e(TAG, "META CHANGED");
        //TEMP
        try {
            String energy = MainActivity.getSingleTrackEnergy(trackID.split(":")[2]); // get id from URI
            Log.e(TAG, "Playing " + trackName + ", Energy:" + energy);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Invalid trackID");
            return;
        }

        stopLocationUpdates();
        new Thread(() -> { // quickly pause then resume track, to ensure playback is caught after meta
            playerApi.pause();
            try {
                sleep(10);
            }
            catch (InterruptedException ignore) {}
            playerApi.resume();
        }).start();

        // make sure receiver is receiving, if not, point to Spotify settings
        playerApi.getPlayerState().setResultCallback(playerState -> {
            if (!playerState.track.uri.equals(trackID)) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.context);
                builder.setMessage("Spotify doesn't appear to be broadcasting. Without this, the app cannot function properly.\n\nPlease enable 'Device Broadcast Status' in Spotify's settings.")
                        .setTitle("Uh oh")
                        .setPositiveButton("Close", (dialog, id) -> {});

                AlertDialog dialog = builder.create();
                dialog.show();
            }
        });
    }

    public void onPlaybackStateChange(boolean playing, int playbackPos) {

        if (playing) {
            Log.e(TAG, "PLAYBACK STARTED");
            long timeToWait;

            // start thread to queue next track at end of current track
            timeToWait = currentTrackLength - playbackPos - fadeDuration - 2000; // time (in ms) until 10s from end of track
            //Log.d(TAG, "" + timeToWait);

            if (endOfTrackAction != null) {
                endOfTrackAction.interrupt();
            }
            endOfTrackAction = new actionAtEndOfTrack();
            try {
                endOfTrackAction.setTimeToWait(timeToWait);
                endOfTrackAction.start();
            } catch (IllegalThreadStateException e) {
                Log.e(TAG, "Illegal State Exception");
            }

            // start thread to start location updates towards end of track
            timeToWait = currentTrackLength - playbackPos - 60000; // time (in ms) until 60s from end of track
            //Log.d(TAG, "" + timeToWait);

            if (startLocationTracking != null) {
                startLocationTracking.interrupt();
            }

            /*if (currentTrackLength - playbackPos < 10000) {
                return;
            }*/

            if (timeToWait < 0) {
                startLocationUpdates();
            }
            else {

                startLocationTracking = new actionTowardsEndOfTrack();
                try {
                    startLocationTracking.setTimeToWait(timeToWait);
                    startLocationTracking.start();
                } catch (IllegalThreadStateException e) {
                    Log.e(TAG, "Illegal State Exception");
                }
            }


        } else {
            Log.e(TAG, "PLAYBACK STOPPED");

            if (endOfTrackAction != null) {
                endOfTrackAction.interrupt();
            }
            if (startLocationTracking != null) {
                startLocationTracking.interrupt();
            }
            stopLocationUpdates();
        }
    }

    private void queueNextTrack() {

        if (playlist.size() == 0) { // PLAYLIST ENDED
            Log.d(TAG, "Playlist empty");
            onDestroy();
        }
        else if (playlist.size() == 1) { // END OF PLAYLIST
            String nextTrackID = playlist.get(0).id;
            if (repeatEnabled) {
                playerApi.queue("spotify:track:" + nextTrackID); //queue
                Log.d(TAG, "Playlist looped");
                playlist = (ArrayList<MainActivity.Track>) fullPlaylist.clone();
            }
            else {
                // wait until end of song, then play final track
                new Thread(() -> {
                    try {
                        sleep(2000 + fadeDuration);
                    }
                    catch (InterruptedException ignored) {}
                    playerApi.play("spotify:track:" + nextTrackID);
                }).start();

                playlist.remove(0);
            }
            // update data structures
            recentlyPlayed.add(nextTrackID);
            while (recentlyPlayed.size() > minorRepeatRate) {
                recentlyPlayed.remove();
            }
        }
        else {

            // calculate velocity
            float currentVelocity = 0;
            int i;
            for (i = 0; i < velocities.size(); i++) {
                if (!velocities.get(i).isNaN()) {
                    currentVelocity += velocities.get(i);
                }
            }
            currentVelocity /= i; //TODO, use modal average not mean
            Log.e(TAG, "Average Speed: " + currentVelocity + "m/s");

            float currentEnergy = currentVelocity / 31.2928f; //calculates energy as a % of car speed out of 70mph
            /*//FOR DEBUG
            Random rand = new Random();
            currentEnergy = rand.nextFloat();*/
            Log.e(TAG, "Energy: " + currentEnergy);

            // find song based off of energy //TODO, randomize to prevent always same order of tracks
            float minDelta = 1;
            MainActivity.Track nextTrack = null;
            for (i = 0; i < playlist.size(); i++) {
                float delta = Math.abs(currentEnergy - playlist.get(i).energy);

                if (delta <= minDelta) { // closer choice
                    if (!recentlyPlayed.contains(playlist.get(i).id)) { // track is only valid if not recently played
                        minDelta = delta;
                        nextTrack = playlist.get(i);
                    }

                }
            }

            if (nextTrack == null) { // catch
                Log.e(TAG, "Could not find next track");
                return;
            }

            // update data structures
            recentlyPlayed.add(nextTrack.id);
            while (recentlyPlayed.size() > minorRepeatRate) {
                recentlyPlayed.remove();
            }

            if (!minorRepeatEnabled) { // only remove track if not repeating
                playlist.remove(nextTrack);
            }

            //queue
            playerApi.queue("spotify:track:" + nextTrack.id);
            Log.e(TAG, "QUEUED " + nextTrack.name);
        }
    }

    // GPS LOCATION

    public class getLocationCaller extends Thread {

        public void run() {
            try {
                sleep(5000);
                getLocation();
                getNextLocation = new getLocationCaller();
                getNextLocation.start();

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    Thread getNextLocation;

    private void startLocationUpdates() {

        velocities = new ArrayList<>();

        if (getNextLocation != null) {
            getNextLocation.interrupt();
        }
        getNextLocation = new getLocationCaller();
        getNextLocation.start();

        Log.e(TAG, "Location updates started");

        //getLocation();

    }

    private void stopLocationUpdates() {
        if (getNextLocation !=  null) {
            getNextLocation.interrupt();
            Log.e(TAG, "Location updates halted");
        }
    }

    private void getLocation() {

        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Tried to getLocation without FINE_LOCATION permission");
            return;
        }
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Tried to getLocation without FINE_LOCATION permission");
            return;
        }

        CurrentLocationRequest currentLocationRequest = new CurrentLocationRequest.Builder()
                .setPriority(LocationPriority)
                .setMaxUpdateAgeMillis(1000)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setDurationMillis(30000)
                .build();

        try {

            fusedLocationProviderClient.getCurrentLocation(currentLocationRequest, null).addOnCompleteListener(task -> {
                Location currentLocation = task.getResult(); // get Location
                if (currentLocation != null) {
                    locationUpdated(currentLocation);
                }
                else {
                    Log.e(TAG, "Location is null");
                }
            });
        }
        catch (SecurityException e) {
            Log.e(TAG, "Security Exception");
        }
        catch (NullPointerException e) {
            Log.e(TAG, "fusedLocationProviderClient is null");
        }

    }

    private void locationUpdated(Location newLocation) {

        if (lastKnownLocation != null) {
            // calculate velocity
            float distanceTravelled = newLocation.distanceTo(lastKnownLocation);
            float timePassed = (SystemClock.elapsedRealtimeNanos() - lastKnownLocation.getElapsedRealtimeNanos()) / 1000000000; //in ms

            float currentVelocity = distanceTravelled / timePassed;
            if (Float.isNaN(currentVelocity)) {
                currentVelocity = 0f;
                Log.e(TAG, "NaN: "+distanceTravelled+", "+timePassed);
            }
            velocities.add(currentVelocity);


            Log.e(TAG, "GPS: "+currentVelocity+"m/s ("+(currentVelocity*2.23694)+"mph)");

            MainActivity.context.setLocationText(
                    "lat:"+newLocation.getLatitude()+"\nlong:"+newLocation.getLongitude(),
                    "time :"+timePassed+"s\nGPS: "+currentVelocity+"m/s ("+Math.round(currentVelocity*2.23694*100)/100+"mph)"+"\n\nenergy: "+(currentVelocity/31.2928)
            );
        }
        else {
            MainActivity.context.setLocationText(
                    "lat:"+newLocation.getLatitude()+"\nlong:"+newLocation.getLongitude(),
                    "no data on velocity"
            );
        }

        lastKnownLocation = newLocation;
    }

}