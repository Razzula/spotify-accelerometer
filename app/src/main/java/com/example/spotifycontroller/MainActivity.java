package com.example.spotifycontroller;

import static java.lang.Thread.sleep;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;

// for Spotify SDK
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.android.appremote.api.PlayerApi;
import com.spotify.android.appremote.api.UserApi;
import com.spotify.protocol.types.Image;
import com.spotify.protocol.types.ImageUri;
// for Spotify Web API
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.lang.Thread;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static MainActivity context;

    public static SpotifyAppRemote mSpotifyAppRemote;
    public static String token;

    static PlayerApi playerApi;

    private boolean repeat = false;

    private SharedPreferences prefs;
    public Intent mainIntent;

    ArrayList<Playlist> playlists;
    PlaylistsAdapter playlistsRecyclerViewAdapter;
    ArrayList<Track> playlist;
    String selectedPlaylistID;
    public static class Track {

        public String name;
        public String id;
        public float energy;

        public Track(String name, String id) {
            this.name = name;
            this.id = id;

        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;

        playerApi = mSpotifyAppRemote.getPlayerApi();
        token = getIntent().getStringExtra("token");

        // setup playlistsRecyclerView
        playlists = new ArrayList<>();
        playlistsRecyclerViewAdapter = new PlaylistsAdapter(playlists);
        RecyclerView playlistsRecyclerView = findViewById(R.id.recyclerPlaylists);
        playlistsRecyclerView.setAdapter(playlistsRecyclerViewAdapter);
        playlistsRecyclerView.setLayoutManager(new LinearLayoutManager(this));


        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        getUserPlaylists();

        // repeat button
        repeat = prefs.getBoolean("repeatEnabled", false);
        View view = findViewById(R.id.repeatButton);
        if (repeat) {
            view.setBackgroundColor(0x8800AA00);
        }
        else {
            view.setBackgroundColor(0x00000000);
        }

        // switch
        Switch toggle = findViewById(R.id.switchEnable);
        if (toggle != null) {
            toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {

                if (isChecked) {
                    checkLocationPermissions();
                }

                else {
                    if (mainIntent != null) {
                        getApplicationContext().stopService(mainIntent);
                        mainIntent = null;
                    }
                }

            });
        }

        View selectedPlaylistView = findViewById(R.id.selectedPlaylist);
        TextView itemName = selectedPlaylistView.findViewById(R.id.textName);
        TextView itemDesc = selectedPlaylistView.findViewById(R.id.textDescription);
        TextView itemInfo = selectedPlaylistView.findViewById(R.id.textInfo);
        ImageView itemImg = selectedPlaylistView.findViewById(R.id.imageView);

        itemName.setText("No Playlist Selected");
        itemDesc.setText("");
        itemInfo.setText("");
        itemImg.setVisibility(View.GONE);
    }

    private void checkLocationPermissions() {
        // LOCATION PERMISSIONS
        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            checkStandbyPermissions();
        }
        else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.context);
                builder.setMessage(R.string.dialogue_location)
                        .setTitle(R.string.dialogue_permissions_T)
                        .setPositiveButton(R.string.dialogue_ok, (dialog, id) -> ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 44))
                        .setNegativeButton(R.string.dialogue_cancel, (dialog, id) -> setSwitch(false))
                        .setOnCancelListener(dialog -> setSwitch(false));

                AlertDialog dialog = builder.create();
                dialog.show();
            } else {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 44);
            }
        }
    }

    private void checkStandbyPermissions() {
        // IGNORE BATTERY OPTIMIZATIONS WHITELIST
        PowerManager tpm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (tpm.isIgnoringBatteryOptimizations("com.example.spotifycontroller")) {
            beginProcess();
        }
        else {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            Uri uri = Uri.fromParts("package", "com.example.spotifycontroller", null);
            intent.setData(uri);

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.context);
            builder.setMessage(R.string.dialogue_ignoreBattery)
                    .setTitle(R.string.dialogue_permissions_T)
                    .setPositiveButton(R.string.dialogue_ok, (dialog, id) -> startActivity(intent))
                    .setNegativeButton(R.string.dialogue_awakeOnly, null)
                    .setOnDismissListener(dialog -> beginProcess());

            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onStart() {
        Switch toggle = (Switch) findViewById(R.id.switchEnable);
        toggle.setChecked(MainService.active);

        if (mainIntent != null) {
            mainIntent.setAction("TO_BACK");
            context.startForegroundService(mainIntent);
        }

        // CHECK USER HAS PREMIUM
        /*UserApi userApi = mSpotifyAppRemote.getUserApi();
        userApi.getCapabilities()
                .setResultCallback(capabilities -> { userIsPremium = capabilities.canPlayOnDemand; })
                .setErrorCallback(error -> Log.e(TAG, "Could not get user state"));*/
        userIsPremium = true;

        super.onStart();
    }

    boolean userIsPremium;

    @Override
    protected void onStop() {

        if (mainIntent != null) {
            mainIntent.setAction("TO_FORE");
            context.startForegroundService(mainIntent);
        }

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mainIntent != null) {
            getApplicationContext().stopService(mainIntent);
            mainIntent = null;
        }
        Log.e("", "Destroyed");
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 44) { // LOCATION
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkStandbyPermissions();
            }
            else {
                setSwitch(false);
                Toast myToast = Toast.makeText(this, "Location permissions required", Toast.LENGTH_LONG);
                myToast.show();
            }
        }
    }

    private void beginProcess() {

        if (userIsPremium) {
            getPlaylistTracks(selectedPlaylistID);
            playerApi.resume();

            Context context = getApplicationContext();
            mainIntent = new Intent(context, MainService.class);
            mainIntent.setAction("START");
            context.startService(mainIntent);
        }
        else {

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.context);
            builder.setMessage(R.string.dialogue_noPremium)
                    .setTitle(R.string.dialogue_noPremium_T)
                    .setPositiveButton(R.string.dialogue_takeMe, (dialog, id) -> {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://spotify.com/premium/"));
                        startActivity(browserIntent);
                    })
                    .setNegativeButton(R.string.dialogue_cancel, null)
                    .setOnDismissListener(dialog -> setSwitch(false));

            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }

    // INTERACTION WITH SPOTIFY WEB API

    private static JSONObject GET(final String endpoint, final String id) {

        class WebThread implements Runnable {
            private volatile JSONObject json;

            @Override
            public void run() {
                try {

                    // establish connection to API
                    URL url = new URL(endpoint+id);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Authorization",  "Bearer " + token);

                    if (conn.getResponseCode() != 200) { //400 bad request, 401 unauthorised, 429 too many requests

                        if (conn.getResponseCode() == 400) {
                            Log.e(TAG, "HTTP Error 400: Bad Request (" + endpoint + id + ")");
                        } else {
                            Log.e(TAG, endpoint + id);
                            throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
                        }
                    }

                    BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
                    String data = br.lines().collect(Collectors.joining()); //get result from API
                    conn.disconnect();

                    try {
                        json = new JSONObject(data);
                    }
                    catch (JSONException e) {
                        Log.e(TAG, "Error parsing String to JSON");
                    }

                } catch (MalformedURLException e) {
                    Log.e(TAG, "Malformed URL Exception");
                } catch (IOException e) {
                    Log.e(TAG, "IO Exception connecting to Web API");
                }
            }

            public JSONObject getJSON() {
                return json;
            }
        }

        // run thread, getting JSONObject result
        WebThread webThread = new WebThread();
        Thread connectToWebAPI = new Thread(webThread);
        connectToWebAPI.start();
        try {
            connectToWebAPI.join();
        }
        catch (InterruptedException e) {
            Log.e(TAG, "connectToWebAPI.join() interrupted");
        }
        return webThread.getJSON();
    }

    private void getUserPlaylists() {

        // get user preference
        String getImagesWhen = prefs.getString("getImagesWhen", "Always");
        boolean shouldGetImages = false;
        if (getImagesWhen.equals("Always")) {
            shouldGetImages = true;
        }
        else if (getImagesWhen.equals("WiFi Only")) {

            ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo deviceWiFI = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

            if (deviceWiFI.isConnected()) {
                shouldGetImages = true;
            }
        }

        try {
            JSONArray playlists = GET("https://api.spotify.com/v1/me/playlists", "").getJSONArray("items"); // get user's playlist data
            for (int i=0; i<playlists.length(); i++) { // for each playlist in list
                JSONObject playlist = playlists.getJSONObject(i);

                String id = playlist.getString("id");
                String name = playlist.getString("name");
                String description = playlist.getString("description"); //TODO, properly decode
                int numberOfTracks = Integer.parseInt(playlist.getJSONObject("tracks").getString("total"));

                if (shouldGetImages) { // GET IMAGES //TODO, refactor into function

                    Bitmap newImg = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888);

                    String url;
                    try {
                        url = playlist.getJSONArray("images").getJSONObject(0).getString("url").split("/")[4];
                    } catch (JSONException e) {
                        Log.e(TAG, name + " does not have image");

                        this.playlists.add(new Playlist(id, name, description, numberOfTracks));
                        checkIfPlaylistsLoaded(playlists.length());
                        continue;
                    }
                    //Log.e("", url);

                    if (url.length() > 40) { //mosaic

                        ArrayList<Bitmap> images = new ArrayList<>();
                        ArrayList<ImageUri> uris = new ArrayList<>();

                        for (int n = 0; n < 4; n += 1) {
                            String tempurl = String.valueOf(Arrays.copyOfRange(url.toCharArray(), n * 40, (n + 1) * 40));
                            uris.add(new ImageUri("spotify:image:" + tempurl));
                        }

                        mSpotifyAppRemote.getImagesApi().getImage(uris.get(0)).setResultCallback(
                                bitmap -> {
                                    images.add(Bitmap.createScaledBitmap(bitmap, 144, 144, false));

                                    mSpotifyAppRemote.getImagesApi().getImage(uris.get(1)).setResultCallback(
                                            bitmap2 -> {
                                                images.add(Bitmap.createScaledBitmap(bitmap2, 144, 144, false));

                                                mSpotifyAppRemote.getImagesApi().getImage(uris.get(2)).setResultCallback(
                                                        bitmap3 -> {
                                                            images.add(Bitmap.createScaledBitmap(bitmap3, 144, 144, false));

                                                            mSpotifyAppRemote.getImagesApi().getImage(uris.get(3)).setResultCallback(
                                                                    bitmap4 -> {
                                                                        images.add(Bitmap.createScaledBitmap(bitmap4, 144, 144, false));

                                                                        for (int x = 0; x < 144; x++) {
                                                                            for (int y = 0; y < 144; y++) {
                                                                                int pixelColour = images.get(0).getPixel(x, y);
                                                                                newImg.setPixel(x, y, pixelColour);

                                                                                pixelColour = images.get(1).getPixel(x, y);
                                                                                newImg.setPixel(x + 144, y, pixelColour);

                                                                                pixelColour = images.get(2).getPixel(x, y);
                                                                                newImg.setPixel(x, y + 144, pixelColour);

                                                                                pixelColour = images.get(3).getPixel(x, y);
                                                                                newImg.setPixel(x + 144, y + 144, pixelColour);

                                                                            }
                                                                        }
                                                                        this.playlists.add(new Playlist(id, name, description, numberOfTracks, newImg));
                                                                        checkIfPlaylistsLoaded(playlists.length());

                                                                    });
                                                        });
                                            });
                                });
                    } else { //single image
                        ImageUri imageUri = new ImageUri("spotify:image:" + url);

                        mSpotifyAppRemote
                                .getImagesApi()
                                .getImage(imageUri, Image.Dimension.THUMBNAIL)
                                .setResultCallback(
                                        bitmap -> {
                                            this.playlists.add(new Playlist(id, name, description, numberOfTracks, bitmap));
                                            checkIfPlaylistsLoaded(playlists.length());
                                        })
                                .setErrorCallback(
                                        error -> {
                                            this.playlists.add(new Playlist(id, name, description, numberOfTracks));
                                            checkIfPlaylistsLoaded(playlists.length());
                                        });
                    }
                }
                else { // NO IMAGES
                    this.playlists.add(new Playlist(id, name, description, numberOfTracks));
                    checkIfPlaylistsLoaded(playlists.length());
                }

            }

            if (playlists.length() <= 0) {
                findViewById(R.id.textNoPlaylists).setVisibility(View.VISIBLE);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Map does not exists in playlists JSONObject");
        }

    }

    private void checkIfPlaylistsLoaded(int targetSize) {

        if (this.playlists.size() == targetSize) {
            SpotifyAppRemote.disconnect(mSpotifyAppRemote);
            findViewById(R.id.loadingPlaylists).setVisibility(View.GONE);
        }
        playlistsRecyclerViewAdapter.notifyItemInserted(playlists.size()-1);
    }

    private void getPlaylistTracks(String id) {

        playlist = new ArrayList<>();

        JSONObject playlist = GET("https://api.spotify.com/v1/playlists/", id+"/tracks"); // get playlist track data
        try {
            JSONArray playlistInfo = playlist.getJSONArray("items");
            for (int i=0; i<playlistInfo.length(); i++) { // for each track in playlist
                JSONObject trackInfo = playlistInfo.getJSONObject(i).getJSONObject("track"); // get track data

                if (trackInfo.getBoolean("is_local")) { // prevent local files from being used (unable to obtain audio analysis..)
                    //Log.e(TAG, trackInfo.getString("name"));
                    continue; //local track
                }

                Track track = new Track(trackInfo.getString("name"), trackInfo.getString("id"));
                this.playlist.add(track);
            }
        }
        catch (JSONException e) {
            Log.e(TAG, "Map does not exist in JSONObject");
            return;
        }

        getPlaylistEnergies();
        Log.e(TAG, "["+this.playlist.get(0).name+", "+this.playlist.get(0).id+", "+this.playlist.get(0).energy+"]"); //DEBUG
    }

    public static String getSingleTrackEnergy(String id) {
        JSONObject trackAnalysis = GET("https://api.spotify.com/v1/audio-features/", id);
        try {
            return trackAnalysis.get("energy").toString();
        }
        catch (JSONException e) {
            Log.e(TAG, "Map does not exist in trackAnalysis JSONObject");
            return null;
        }
        catch (NullPointerException e) {
            return null;
        }
    }

    private void getPlaylistEnergies() { // update playlist's energy values
        String request = "?ids=";
        for (int i=0; i<playlist.size(); i++) {
            request += playlist.get(i).id+",";
        }

        try {
            JSONArray tracksAnalyses = GET("https://api.spotify.com/v1/audio-features/", request).getJSONArray("audio_features"); //get data from API
            for (int i=0; i<tracksAnalyses.length(); i++) {
                JSONObject trackAnalysis = tracksAnalyses.getJSONObject(i);
                playlist.get(i).energy = Float.parseFloat(trackAnalysis.getString("energy")); //update Track's energy to value received from API
            }
        }
        catch (JSONException e) {
            Log.e(TAG, "Map does not exist in tracksAnalyses JSONObject");
        }
    }

    // UI

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        if (item.getItemId() == R.id.menuHome) {
            Intent newIntent = new Intent(MainActivity.this, SettingsActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(newIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    View previouslySelectedPlaylist;
    public void playlistsSelected(View view) {

        Object id = view.getTag();
        if (id == null) {
            return;
        }
        selectedPlaylistID = String.valueOf(id);

        View selectedPlaylistView = findViewById(R.id.selectedPlaylist);

        Switch toggle = findViewById(R.id.switchEnable);

        if (toggle.isChecked()) {
            return;
        }

        // current selection views
        TextView itemName = selectedPlaylistView.findViewById(R.id.textName);
        TextView itemDesc = selectedPlaylistView.findViewById(R.id.textDescription);
        TextView itemInfo = selectedPlaylistView.findViewById(R.id.textInfo);
        ImageView itemImg = selectedPlaylistView.findViewById(R.id.imageView);

        if (previouslySelectedPlaylist != null) {
            previouslySelectedPlaylist.findViewById(R.id.clickableMain).setBackgroundColor(0x00000000); // deselect previous selection
            selectedPlaylistView.findViewById(R.id.clickableMain).setBackgroundColor(0x00000000);

            itemName.setText("No Playlist Selected");
            itemDesc.setText("");
            itemInfo.setText("");
            itemImg.setVisibility(View.GONE);
        }
        else {
            toggle.setEnabled(true);
        }

        if (previouslySelectedPlaylist == view) { // selected playlist is clicked

            // deselect
            previouslySelectedPlaylist = null;
            selectedPlaylistID = null;
            toggle.setEnabled(false);
            return;
        }

        previouslySelectedPlaylist = view;

        //get playlist data
        Playlist selectedPlaylist = playlists.get(0);
        for (int i=0; i<playlists.size(); i++) {
            if (playlists.get(i).getID().equals(selectedPlaylistID)) {
                selectedPlaylist = playlists.get(i);

                itemName.setText(selectedPlaylist.getName());
                itemDesc.setText(selectedPlaylist.getDescription());
                itemInfo.setText(selectedPlaylist.getNumberOfTracks()+" tracks");
                if (selectedPlaylist.getImage() != null) {
                    itemImg.setImageBitmap(selectedPlaylist.getImage());
                }
                else {
                    itemImg.setImageResource(R.drawable.spotify_icon);
                }

                itemImg.setVisibility(View.VISIBLE);
                itemImg.setTag(selectedPlaylistID);

                break;
            }
        }

        if (selectedPlaylist.getNumberOfTracks() <= 0) {
            view.findViewById(R.id.clickableMain).setBackgroundColor(0x88AA0000);
            selectedPlaylistView.findViewById(R.id.clickableMain).setBackgroundColor(0x88AA0000);
            toggle.setEnabled(false);
        }
        else {
            view.findViewById(R.id.clickableMain).setBackgroundColor(0x8800AA00);
            selectedPlaylistView.findViewById(R.id.clickableMain).setBackgroundColor(0x8800AA00);
            toggle.setEnabled(true);
        }


    }

    public void togglePlaylistList (View view) {
        RecyclerView recyclerView = findViewById(R.id.recyclerPlaylists);
        View selectedPlaylistView = findViewById(R.id.selectedPlaylist);
        TextView textView = findViewById(R.id.textTitle);

        ImageButton btn = (ImageButton) view;
        if (recyclerView.getVisibility() == View.VISIBLE) { // show specific
            recyclerView.setVisibility(View.GONE);
            selectedPlaylistView.setVisibility(View.VISIBLE);
            btn.setImageResource(android.R.drawable.arrow_down_float);
            textView.setText("Playlist");

            findViewById(R.id.textNoPlaylists).setVisibility(View.GONE);
        }
        else { // show all
            recyclerView.setVisibility(View.VISIBLE);
            selectedPlaylistView.setVisibility(View.GONE);
            btn.setImageResource(android.R.drawable.arrow_up_float);
            textView.setText("Playlists");

            if (playlists.size() <= 0) {
                findViewById(R.id.textNoPlaylists).setVisibility(View.VISIBLE);
            }
        }
    }

    public void goToPlaylist(View view) {

        String uri = String.valueOf(view.getTag());

        PackageManager pm = getPackageManager();
        boolean isSpotifyInstalled;
        try {
            pm.getPackageInfo("com.spotify.music", 0);
            isSpotifyInstalled = true;
        } catch (PackageManager.NameNotFoundException e) {
            isSpotifyInstalled = false;
        }

        if (isSpotifyInstalled) {
            launchSpotify("playlist:"+uri);
        }
        else {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/playlist/"+uri));
            startActivity(browserIntent);
        }

    }

    public void toggleRepeat (View view) {
        repeat = !repeat;
        if (repeat) { // enable
            view.setBackgroundColor(0x8800AA00);
        }
        else { // disable
            view.setBackgroundColor(0x00000000);
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("repeatEnabled", repeat);
        editor.apply();
    }

    public void goToSpotify(View view) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/collection/playlists"));
        startActivity(browserIntent);
    }

    private void launchSpotify(String uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("spotify:" + uri));
        intent.putExtra(Intent.EXTRA_REFERRER,
                Uri.parse("android-app://" + context.getPackageName()));
        startActivity(intent);
    }

    public void setSwitch(boolean state) {

        Switch toggle = MainActivity.context.findViewById(R.id.switchEnable);

        runOnUiThread(() -> toggle.setChecked(state));

    }
}