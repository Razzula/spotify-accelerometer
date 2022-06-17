package com.example.spotifycontroller;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import android.util.Log;
import android.content.Intent;

import android.location.Location;
import android.view.View;
import android.widget.TextView;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.android.appremote.api.PlayerApi;

import com.spotify.sdk.android.auth.AuthorizationClient;
import com.spotify.sdk.android.auth.AuthorizationRequest;
import com.spotify.sdk.android.auth.AuthorizationResponse;

import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

import java.lang.Thread;
import java.util.Random;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences.Editor editor;
    private SharedPreferences msharedPreferences;

    private static final String CLIENT_ID = "c3ea15ea37eb4121a64ee8af3521f832";
    private static final String REDIRECT_URI = "com.example.spotifycontroller://callback";
    private static final int REQUEST_CODE = 1337;
    private static final String SCOPES = "user-read-email,user-read-private,playlist-read-private";
    private SpotifyAppRemote mSpotifyAppRemote;

    Intent service;
    PlayerApi playerApi;

    static int currentTrackLength = 0;
    static float currentVelocity = 0;
    static long timeToWait = 0;
    static Thread thread;
    static Thread nextThread;

    FusedLocationProviderClient fusedLocationProviderClient;
    Location lastKnownLocation;

    static boolean valid = false;

    public class Test extends Thread {

        public void run() {
            nextThread = new Test();

            if (timeToWait < 500) {
                return;
            }

            try {
                sleep(timeToWait);
                getLocation();
                queueNextTrack();

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // move this to trigger when made
        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 44);
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        nextThread = new Test();

        // SPOTIFY SDK
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

                        connected();
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        Log.e("MainActivity", throwable.getMessage(), throwable);

                        // Handle errors here
                    }
                });

        //SPOTIFY WEB API
        AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI);
        builder.setScopes(new String[]{SCOPES});
        AuthorizationRequest request = builder.build();
        AuthorizationClient.openLoginActivity(this, REQUEST_CODE, request);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        // Check if result comes from the correct activity
        if (requestCode == REQUEST_CODE) {
            AuthorizationResponse response = AuthorizationClient.getResponse(resultCode, intent);

            switch (response.getType()) {
                // Response was successful and contains auth token
                case TOKEN:
                    editor = getSharedPreferences("SPOTIFY", 0).edit();
                    editor.putString("token", response.getAccessToken());
                    token = response.getAccessToken();
                    editor.apply();

                    valid = true;
                    getPlaylistData();
                    break;

                // Auth flow returned an error
                case ERROR:
                    // Handle error response
                    break;

                // Most likely auth flow was cancelled
                default:
                    // Handle other cases
            }
        }
    }

    static String token;

    private static String getSingleTrackEnergy(String id) {
        JSONObject trackAnalysis = GET("https://api.spotify.com/v1/audio-features/", id);
        try {
            return trackAnalysis.get("energy").toString();
        }
        catch (JSONException e) {
            Log.e("", "Map does not exist in trackAnalysis JSONObject");
            return null;
        }
    }

    private void getPlaylistEnergies() {
        String request = "?ids=";
        for (int i=0; i<playlist.size(); i++) {
            request += playlist.get(i).id+",";
        }

        try {
            JSONArray tracksAnalyses = GET("https://api.spotify.com/v1/audio-features/", request).getJSONArray("audio_features");
            for (int i=0; i<tracksAnalyses.length(); i++) {
                JSONObject trackAnalysis = tracksAnalyses.getJSONObject(i);
                playlist.get(i).energy = Float.parseFloat(trackAnalysis.getString("energy"));
            }
            //return trackAnalysis.get("energy").toString();
        }
        catch (JSONException e) {
            Log.e("", "Map does not exist in tracksAnalyses JSONObject");
            //return null;
        }
    }

    private static JSONObject GET(final String inURL, final String inID) {

        class WebThread implements Runnable {
            private volatile JSONObject json;

            @Override
            public void run() {
                String endpoint = inURL;
                String id = inID;
                try {

                    URL url = new URL(endpoint+id);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Authorization",  "Bearer " + token);

                    if (conn.getResponseCode() != 200) {
                        Log.e("", endpoint+id);
                        throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
                    }

                    BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));

                    String data = br.lines().collect(Collectors.joining());
                    /*String output;
                    while ((output = br.readLine()) != null) {
                        data += output;
                    }*/
                    conn.disconnect();

                    try {
                        json = new JSONObject(data);
                    }
                    catch (JSONException e) {
                        Log.e("", "Error parsing String to JSON");
                    }

                } catch (MalformedURLException e) {
                    Log.e("", "Malformed URL Exception");
                } catch (IOException e) {
                    Log.e("", "IO Exception connecting to Web API");
                }
            }

            public JSONObject getJSON() {
                return json;
            }
        }

        WebThread webThread = new WebThread();
        Thread connectToWebAPI = new Thread(webThread);
        connectToWebAPI.start();
        try {
            connectToWebAPI.join();
        }
        catch (InterruptedException e) {
            Log.e("", "connectToWebAPI.join() interrupted");
        }
        return webThread.getJSON();
    }

    private void connected() {

        playerApi = mSpotifyAppRemote.getPlayerApi();

        if (service != null) {
            this.stopService(service);
        }
        service = new Intent(this, ReceiverService.class);
        this.startService(service);

    }

    public static void onPlaybackStateChange(boolean playing, int playbackPos) {

        if (playing) {
            Log.d("", "PLAYBACK STARTED");

            timeToWait = currentTrackLength - playbackPos - 10000; //time (in ms) until 10s from end of track
            Log.e("", ""+timeToWait);

            if (thread != null) {
                thread.interrupt();
            }
            thread = nextThread;
            try {
                thread.start();
            }
            catch (IllegalThreadStateException e) {
                Log.e("", "Illegal State Exception");
            }
        }
        else {
            Log.d("", "PLAYBACK STOPPED");

            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    public static void onMetadataChange(String trackID, int trackLength, String trackName) {

        Log.d("", "META CHANGED");
        if (valid) {
            try {
                String energy = getSingleTrackEnergy(trackID.split(":")[2]);
                Log.e("", "Playing " + trackName + ", Energy:" + energy);
            } catch (IndexOutOfBoundsException e) {
                Log.e("", "Invalid trackID");
            }
        }

        currentTrackLength = trackLength;
    }

    @Override
    protected void onStop() {
        super.onStop();
        SpotifyAppRemote.disconnect(mSpotifyAppRemote);

        if (service != null) {
            this.stopService(service);
        }

    }

    private void getLocation() {

        TextView textLocation = findViewById(R.id.location);
        TextView textSpeed = findViewById(R.id.data);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textLocation.setText("");
            }
        });

        try {

            fusedLocationProviderClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).addOnCompleteListener(new OnCompleteListener<Location>() {
                @Override
                public void onComplete(@NonNull Task<Location> task) {

                    Location currentLocation = task.getResult();
                    if (currentLocation != null) {
                        Log.d("", "lat:"+currentLocation.getLatitude()+" long:"+currentLocation.getLongitude());

                        if (lastKnownLocation != null) {
                            float distanceTravelled = currentLocation.distanceTo(lastKnownLocation);
                            float timePassed = (SystemClock.elapsedRealtimeNanos() - lastKnownLocation.getElapsedRealtimeNanos()) / 1000000000; //in ms
                            currentVelocity = distanceTravelled / timePassed;

                            //TEMP
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textSpeed.setText("distance: "+distanceTravelled+"m\ntime :"+timePassed+"s\nspeed: "+currentVelocity+"m/s\n\nenergy: "+(currentVelocity/31.2928));
                                }
                            });
                        }

                        //TEMP
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textLocation.setText("lat:"+currentLocation.getLatitude()+"\nlong:"+currentLocation.getLongitude());
                            }
                        });

                        lastKnownLocation = currentLocation;
                    }
                    else {
                        Log.e("", "Location is null");
                    }
                }
            });;
        }
        catch (SecurityException e) {
            Log.e("", "Security Exception");
        }

    }

    private class Track {

        public String name;
        public String id;
        public float energy;

        private Track(String name, String id) {
            this.name = name;
            this.id = id;

        }
    }
    ArrayList<Track> playlist;

    private void getPlaylistData() {

        playlist = new ArrayList<Track>();

        JSONObject playlist = GET("https://api.spotify.com/v1/playlists/", "1Ef8kGg89vFiO7ELA8KjA6/tracks");
        try {
            JSONArray playlistInfo = playlist.getJSONArray("items");
            for (int i=0; i<playlistInfo.length(); i++) {
                JSONObject trackInfo = playlistInfo.getJSONObject(i).getJSONObject("track");

                String trackID = trackInfo.getString("id");
                if (trackInfo.getBoolean("is_local")) {
                    //Log.e("", trackInfo.getString("name"));
                    continue; //local track
                }

                Track track = new Track(trackInfo.getString("name"), trackID);
                this.playlist.add(track);
            }
            Log.e("", "["+this.playlist.get(0).name+", "+this.playlist.get(0).id+", "+this.playlist.get(0).energy+"]");
        }
        catch (JSONException e) {
            Log.e("", "Map does not exist in JSONObject");
            return;
        }

        getPlaylistEnergies();
        Log.e("", "["+this.playlist.get(0).name+", "+this.playlist.get(0).id+", "+this.playlist.get(0).energy+"]");
    }

    public void Click(View view) {
        getLocation();
    }

    private void queueNextTrack() {
        float currentEnergy = currentVelocity / 31.2928f; //calculates energy as a % of car speed out of 70mph
            /*//FOR DEBUG
            Random rand = new Random();
            currentEnergy = rand.nextFloat();*/
        Log.e("", "Energy: "+currentEnergy);


        // find song based off of energy
        if (playlist.size() > 0) {
            float minDelta = 1;
            Track nextTrack = new Track("", "");
            for (int i = 0; i < playlist.size(); i++) {
                float delta = Math.abs(currentEnergy - playlist.get(i).energy);
                if (delta <= minDelta) {
                    minDelta = delta;
                    nextTrack = playlist.get(i);
                }
            }
            playlist.remove(nextTrack);

            //queue
            playerApi.queue("spotify:track:" + nextTrack.id);
            Log.e("", "QUEUED " + nextTrack.name);
        }
        else {
            Log.e("", "Playlist empty");
        }
    }
}