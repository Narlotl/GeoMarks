package narlotl.geomarks;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.PrerenderException;
import androidx.webkit.PrerenderOperationCallback;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NoConnectionError;
import com.android.volley.RetryPolicy;
import com.android.volley.toolbox.JsonArrayRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapColorScheme;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.AutocompleteSessionToken;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FetchPlaceResponse;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.libraries.places.widget.PlaceAutocomplete;
import com.google.android.libraries.places.widget.PlaceAutocompleteActivity;

import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import org.apache.commons.text.WordUtils;

import narlotl.geomarks.databinding.ActivityMapsBinding;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {
    public static String capitalizeFirstLetter(String s) {
        if (s.isEmpty())
            return s;

        String copy = s.toLowerCase();
        return copy.substring(0, 1).toUpperCase() + copy.substring(1);
    }

    public static String pastEquals(String s) {
        int index = s.indexOf('=');
        if (index == -1)
            return s;
        return s.substring(index + 2);
    }

    private ActivityMapsBinding binding;

    // Go back to the map from the recovery page
    private void returnToMapView() {
        // Reset WebView
        recoveryPage.loadUrl("about:blank");
        recoveryPage.clearHistory();
        // Transition to map view
        View mapView = binding.getRoot();
        mapView.startAnimation(AnimationUtils.loadAnimation(MapsActivity.this, android.R.anim.slide_in_left));
        setContentView(binding.getRoot());
    }

    private final List<Place.Field> placeFields = Collections.singletonList(Place.Field.LOCATION);
    private static final String placeErrorString = "Error finding place.";

    private boolean darkMode;
    private GoogleMap map;
    private RequestQueue volleyQueue;
    private FusedLocationProviderClient fusedLocationClient;
    private MarkerOptions locatorIcon;
    private Marker locatorMarker;
    private double currentLat, currentLon;
    private OnBackPressedCallback backPressedCallback;
    private RecoveryDbHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        darkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set up back button to close recovery screen
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // Once recovery page is closed, the listener shouldn't be active
                this.setEnabled(false);
                returnToMapView();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
        // Initialize database
        dbHelper = new RecoveryDbHelper(this);

        // Initialize place search
        Places.initializeWithNewPlacesApiEnabled(this, getString(R.string.API_KEY));

        Intent autocompleteIntent = new PlaceAutocomplete.IntentBuilder().build(this);
        ActivityResultLauncher<Intent> placeAutocompleteActivityResultLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            Intent intent = result.getData();
                            if (intent == null) {
                                Toast.makeText(this, placeErrorString, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (result.getResultCode() == PlaceAutocompleteActivity.RESULT_OK) {
                                // Get location
                                AutocompletePrediction prediction = PlaceAutocomplete.getPredictionFromIntent(intent);
                                if (prediction == null) {
                                    Toast.makeText(this, placeErrorString, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                AutocompleteSessionToken sessionToken = PlaceAutocomplete.getSessionTokenFromIntent(intent);

                                // Get location coordinates
                                PlacesClient placesClient = Places.createClient(this);
                                FetchPlaceRequest request =
                                        FetchPlaceRequest.builder(prediction.getPlaceId(), placeFields)
                                                .setSessionToken(sessionToken).build();
                                Task<FetchPlaceResponse> task = placesClient.fetchPlace(request);
                                task.addOnSuccessListener(e -> {
                                    Place place = task.getResult().getPlace();
                                    LatLng location = place.getLocation();
                                    if (location == null) {
                                        Toast.makeText(this, placeErrorString, Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    loadMap(location);
                                });
                            }
                        }
                );

        // Check for updates from GitHub releases
        volleyQueue = Volley.newRequestQueue(MapsActivity.this);
        volleyQueue.add(new JsonObjectRequest(Request.Method.GET, "https://api.github.com/repos/Narlotl/GeoMarks/releases/latest", null, data -> {
            try {
                // Compare GitHub and installed versions
                String version = data.getString("tag_name");
                String currentVersion = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA).metaData.getString("version");
                if (!version.equals(currentVersion))
                    // Prompt user for update
                    new UpdateDialog(version, currentVersion).show(getSupportFragmentManager(), "update");
            } catch (JSONException e) {
                Log.e("Error", e.toString());
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        }, error -> {
        }));

        // Initialize location detection
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Set up search button
        ImageButton search = findViewById(R.id.search);
        search.setOnClickListener(e -> placeAutocompleteActivityResultLauncher.launch(autocompleteIntent));
        // Set up recenter button
        ImageButton recenter = findViewById(R.id.recenter);
        recenter.setOnClickListener(e -> getLocation());

        // Create map
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        assert mapFragment != null;
        mapFragment.getMapAsync(this);
    }

    private int crashCount = 0;

    @SuppressLint("SetJavaScriptEnabled")
    private void initRecoveryPage() {
        recoveryPage = new WebView(this);
        // Allow photo selection
        // https://stackoverflow.com/a/38138264
        recoveryPage.setWebChromeClient(new WebChromeClient() {
            public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), r -> {
                }).launch(
                        new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()
                );
                return true;
            }
        });
        WebSettings settings = recoveryPage.getSettings();
        settings.setJavaScriptEnabled(true);
        // Allows recovery page to store cookies
        settings.setDomStorageEnabled(true);
        // Retrieve data from submission page
        recoveryPage.setWebViewClient(new RecoveryPageWebViewClient(url -> {
            if (!url.contains("?pagetype=submit"))
                return;

            recoveryPage.evaluateJavascript(
                    "(() => { " +
                            "const tbody = document.querySelector('table.table > tbody');" +
                            "let pid, condition;" +
                            // Loop through rows of table until PID and condition are found
                            "for (const row of tbody.children) {" +
                            "const field = row.children[0].innerText;" +
                            "if (field === 'PID') {" +
                            "pid = row.children[1].innerText;" +
                            "if (condition)" +
                            "break;" +
                            "}" +
                            "else if (field === 'Condition') {" +
                            "condition = row.children[1].innerText;" +
                            "if (pid)" +
                            "break;" +
                            "}" +
                            "}" +
                            "return pid + ',' + condition;" +
                            "})();",
                    s -> {
                        int index = s.indexOf(',');
                        String pid = s.substring(1, index);
                        int condition = 2;
                    /* Condition codes:
                       G (Good): 0
                       O (Other): 1
                       P (Poor): 1
                       X (Surface mark destroyed): 2
                       Y (Underground mark destroyed): 2
                       N (Not recovered): 2
                     */
                        switch (s.substring(index + 1, index + 2)) {
                            case "G":
                                condition = 0;
                                break;
                            case "O":
                            case "P":
                                condition = 1;
                                break;
                        }
                        int time = (int) (System.currentTimeMillis() / 1000);

                        // Put recovery data into DB
                        ContentValues values = new ContentValues();
                        values.put(RecoveryDbHelper.PID_KEY, pid);
                        values.put(RecoveryDbHelper.CONDITION_KEY, condition);
                        values.put(RecoveryDbHelper.TIME_KEY, time);
                        SQLiteDatabase db = dbHelper.getWritableDatabase();
                        db.insert(RecoveryDbHelper.TABLE_NAME, null, values);
                        db.close();

                        // Update marker icon
                        try {
                            selectedMarker.setIcon(
                                    iconMatrix[((JSONObject) selectedMarker.getTag()).getInt("gone")][darkMode ? 1 : 0][condition + 1]
                                    .getIcon()
                            );
                        } catch (JSONException e) {
                            Log.e("JSONError", e.toString());
                        }

                        returnToMapView();
                        Toast.makeText(this, "Submission recorded", Toast.LENGTH_SHORT).show();
                    }
            );
        },
                () -> {
                    // Handle WebView render process crashes
                    if (crashCount++ >= 3)
                        throw new RuntimeException("WebView crashed too many times");
                    initRecoveryPage();
                }
        ));
    }

    final static String imageHost = "https://geodesy.noaa.gov";
    final Picasso picasso = Picasso.get();
    WebView recoveryPage;
    private static final MarkerOptions[][][] iconMatrix = new MarkerOptions[2][2][4]; // Status > theme > submission
    private Marker selectedMarker = null;

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.setMapColorScheme(MapColorScheme.FOLLOW_SYSTEM);
        map.setOnMapLongClickListener(this::loadMap);
        map.setOnMapClickListener(latLng -> Toast.makeText(this, "Hold to relocate the map.", Toast.LENGTH_SHORT).show());

        // Track user recovery submissions
        initRecoveryPage();

        // Fill icon matrix
        final String[] statuses = {"active", "gone"},
                themes = {"light", "dark"},
                submissions = {"" /* no submission */, "_check" /* good condition */, "_dash" /* poor/other */, "_x" /* not recovered/destroyed */};
        for (int i = 0; i < statuses.length; i++)
            for (int j = 0; j < themes.length; j++)
                for (int k = 0; k < submissions.length; k++)
                    iconMatrix[i][j][k] = new MarkerOptions().anchor(0.5f, 0.5f)
                            .icon(BitmapDescriptorFactory.fromAsset(statuses[i] + "_" + themes[j] + submissions[k] + ".png"));

        locatorIcon = new MarkerOptions().anchor(0.5f, 0.5f).icon(BitmapDescriptorFactory.fromAsset("locator_" + (darkMode ? "dark" : "light") + ".png")).zIndex(2);

        PopupWindow popup = new PopupWindow(this);
        popup.setAnimationStyle(R.style.popup_animation);
        View view = getLayoutInflater().inflate(R.layout.popup, findViewById(R.id.popup), false);
        ScrollView scrollView = view.findViewById(R.id.scroll_view);
        Button close = view.findViewById(R.id.close);
        final CancellationSignal preloadCancellationSignal = new CancellationSignal();
        close.setOnClickListener(e -> {
            popup.dismiss();
            preloadCancellationSignal.cancel();
        });
        TextView title = view.findViewById(R.id.title);
        LinearLayout settingGroup = view.findViewById(R.id.setting_group);
        TextView setting = view.findViewById(R.id.setting);
        LinearLayout stampingGroup = view.findViewById(R.id.stamping_group);
        TextView stamping = view.findViewById(R.id.stamping);
        TextView description = view.findViewById(R.id.description);
        LinearLayout historyGroup = view.findViewById(R.id.history_group);
        TextView historyView = view.findViewById(R.id.history);
        Button openMap = view.findViewById(R.id.openMap);
        Button submit = view.findViewById(R.id.submit);
        TextView imagesTitle = view.findViewById(R.id.images_title);
        LinearLayout images = view.findViewById(R.id.images);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        imageParams.setMargins(0, 14, 0, 0);

        map.setOnMarkerClickListener(marker -> {
            if (marker.getTag() == null)
                return true;

            selectedMarker = marker;

            volleyQueue.cancelAll(request -> request.getClass().equals(JsonArrayRequest.class));
            preloadCancellationSignal.cancel();
            popup.dismiss();
            imagesTitle.setVisibility(View.GONE);

            try {
                JSONObject markerData = (JSONObject) marker.getTag();
                assert markerData != null;
                String pid = markerData.getString("pid");

                // Preload recovery page
                String recoveryUrl = "https://geodesy.noaa.gov/cgi-bin/mark_recovery_form.prl?liteMode=true&PID=" + pid;
                if (WebViewFeature.isFeatureSupported(WebViewFeature.PRERENDER_WITH_URL)) {
                    WebViewCompat.prerenderUrlAsync(
                            recoveryPage,
                            recoveryUrl,
                            preloadCancellationSignal,
                            Runnable::run,
                            new PrerenderOperationCallback() {
                                @Override
                                public void onPrerenderActivated() {
                                }

                                @Override
                                public void onError(@org.jspecify.annotations.NonNull PrerenderException exception) {
                                }
                            }
                    );
                }

                // Request images
                images.removeAllViews();
                volleyQueue.add(new JsonArrayRequest(Request.Method.GET, "https://surveymarkers.eliasfretwell.com/getImages?pid=" + pid, null, data -> {
                    try {
                        for (int i = 0; i < data.length(); i++) {
                            ImageView imageView = new ImageView(this);
                            if (i != 0)
                                imageView.setLayoutParams(imageParams);
                            final Uri imageUri = Uri.parse(imageHost + data.getString(i));
                            picasso.load(imageUri).resize(images.getWidth(), 0).into(imageView, new Callback() {
                                @Override
                                public void onSuccess() {
                                    imagesTitle.setVisibility(View.VISIBLE);
                                    images.addView(imageView);
                                }

                                @Override
                                public void onError(Exception e) {
                                    ((LinearLayout) imageView.getParent()).removeView(imageView);
                                }
                            });
                            imageView.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, imageUri)));
                        }
                    } catch (JSONException e) {
                        Log.e("Error", e.toString());
                    }
                }, error -> Log.e("Error", error.toString())));

                // Have buttons go to marker pages
                double latitude = markerData.getDouble("latitude"), longitude = markerData.getDouble("longitude");
                openMap.setOnClickListener(click -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/place/" + latitude + "," + longitude))));
                // Open recovery page in WebView
                submit.setOnClickListener(click -> {
                    recoveryPage.loadUrl(recoveryUrl);
                    recoveryPage.startAnimation(AnimationUtils.loadAnimation(MapsActivity.this, android.R.anim.slide_out_right));
                    setContentView(recoveryPage);
                    popup.dismiss();
                    // Return to map when back button is pressed
                    backPressedCallback.setEnabled(true);
                });
                //startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://geodesy.noaa.gov/cgi-bin/mark_recovery_form.prl?liteMode=true&PID=" + pid))));

                // Set text box content
                title.setText(pid);
                if (markerData.has("setting")) {
                    settingGroup.setVisibility(View.VISIBLE);
                    setting.setText(getString(
                            R.string.setting,
                            capitalizeFirstLetter(pastEquals(markerData.getString("marker"))),
                            pastEquals(markerData.getString("setting")).toLowerCase()
                    ));
                } else
                    settingGroup.setVisibility(View.GONE);
                if (markerData.has("stamping")) {
                    stampingGroup.setVisibility(View.VISIBLE);
                    stamping.setText(getString(R.string.stamping, markerData.getString("stamping")));
                } else
                    stampingGroup.setVisibility(View.GONE);
                description.setText(WordUtils.capitalizeFully(markerData.getString("description")));

                // Show history
                if (markerData.has("history")) {
                    historyGroup.setVisibility(View.VISIBLE);

                    JSONArray history = markerData.getJSONArray("history");
                    StringBuilder historyText = new StringBuilder();
                    for (int i = history.length() - 1; i >= 0; i--) {
                        JSONObject report = history.getJSONObject(i);
                        int dateNumber = report.optInt("date");
                        String date;
                        if (dateNumber == 0)
                            date = report.getString("date");
                        else
                            date = Integer.toString(report.getInt("date") % 10000); // Get first 4 digits (year)
                        historyText.append(date).append(" - ").append(capitalizeFirstLetter(report.getString("condition")));
                        if (i > 0) historyText.append("\n");
                    }
                    historyView.setText(historyText);
                } else
                    historyGroup.setVisibility(View.GONE);
            } catch (JSONException e) {
                Log.e("JSON", e.toString());
            }

            popup.setContentView(view);
            popup.showAtLocation(view, Gravity.CENTER, 0, 0);
            scrollView.scrollTo(0, 0);

            return true;
        });

        getLocation();
    }

    private void getLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this,
                        location -> loadMap(new LatLng(location.getLatitude(), location.getLongitude()))
                );
    }

    private static final double SAME_DISTANCE = 0.000230347467053; // The amount of degrees in ~0.1 mi

    private void loadMap(LatLng latLng) {
        if (Math.abs(currentLat - latLng.latitude) <= SAME_DISTANCE && Math.abs(currentLon - latLng.longitude) <= SAME_DISTANCE) {
            // If the new location is close to the current one, don't reload markers
            locatorMarker.remove();
            locatorMarker = map.addMarker(locatorIcon.position(latLng));
            return;
        }

        currentLat = latLng.latitude;
        currentLon = latLng.longitude;

        map.clear();
        locatorMarker = map.addMarker(locatorIcon.position(latLng));
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f));

        loadMarkers(latLng);
    }

    private final RetryPolicy retryPolicy = new DefaultRetryPolicy(30000, 5, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT);


    private final static String[] projection = {RecoveryDbHelper.CONDITION_KEY};
    private final static String selection = RecoveryDbHelper.PID_KEY + " = ?";
    private final static String sortOrder = RecoveryDbHelper.TIME_KEY + " DESC";

    @SuppressLint("Range")
    private void loadMarkers(LatLng latLng) {
        volleyQueue.add(new JsonArrayRequest(
                Request.Method.GET,
                "https://surveymarkers.eliasfretwell.com/getMarkers?lat=" + latLng.latitude + "&lon=" + latLng.longitude + "&radius=5&fields=pid,latitude,longitude,marker,setting,stamping,description,history",
                null,
                markers -> {
                    try (SQLiteDatabase db = dbHelper.getReadableDatabase()) {
                        for (int i = 0; i < markers.length(); i++) {
                            JSONObject marker = markers.getJSONObject(i);
                            double markerLatitude = marker.getDouble("latitude");
                            double markerLongitude = marker.getDouble("longitude");

                            LatLng markerLatLng = new LatLng(markerLatitude, markerLongitude);
                            boolean gone;
                            if (!marker.has("history"))
                                // If marker doesn't have history, mark it as existing
                                gone = false;
                            else {
                                JSONArray history = marker.getJSONArray("history");
                                String condition = history.getJSONObject(history.length() - 1).getString("condition");
                                // https://www.ngs.noaa.gov/web/tools/updates/windesc5/dformat_documentation.shtml#A.2
                                gone = condition.equals("MARK NOT FOUND") || condition.equals("SURFACE MARK KNOWN DESTROYED") || condition.equals("UNDERGROUND MARK DESTROYED");
                            }
                            marker.put("gone", gone ? 1 : 0);

                            Cursor cursor = db.query(
                                    RecoveryDbHelper.TABLE_NAME,
                                    projection,
                                    selection,
                                    new String[]{marker.getString("pid")},
                                    null,
                                    null,
                                    sortOrder
                            );
                            int submissionStatus = 0;
                            if (cursor.moveToFirst())
                                submissionStatus = cursor.getInt(cursor.getColumnIndex(RecoveryDbHelper.CONDITION_KEY)) + 1;
                            cursor.close();
                            Marker mapMarker = map.addMarker(iconMatrix[gone ? 1 : 0][darkMode ? 1 : 0][submissionStatus].position(markerLatLng));
                            assert mapMarker != null;
                            mapMarker.setTag(marker);
                        }
                    } catch (JSONException e) {
                        Log.e("JSON", e.toString());
                    }
                },
                error -> {
                    ErrorDialog dialog;
                    if (error instanceof NoConnectionError)
                        dialog = new ErrorDialog("No internet connection", Settings.ACTION_WIFI_SETTINGS);
                    else
                        dialog = new ErrorDialog("Failed to load markers\n" + error.getClass().toString().replace("class ", ""), error.getStackTrace());
                    dialog.show(getSupportFragmentManager(), "Request");
                    Log.e("Error", error.toString());
                }
        ).setRetryPolicy(retryPolicy));
    }

    @Override
    protected void onDestroy() {
        dbHelper.close();
        recoveryPage.destroy();
        super.onDestroy();
    }
}