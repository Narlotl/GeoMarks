package narlotl.geomarks;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
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
        if (s.length() == 0)
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

    private final List<Place.Field> placeFields = Collections.singletonList(Place.Field.LOCATION);
    private static final String placeErrorString = "Error finding place.";

    private boolean darkMode;
    private GoogleMap map;
    private RequestQueue volleyQueue;
    private FusedLocationProviderClient fusedLocationClient;
    private MarkerOptions activeIcon;
    private MarkerOptions goneIcon;
    private MarkerOptions locatorIcon;
    private Marker locatorMarker;
    private double currentLat, currentLon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        darkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        narlotl.geomarks.databinding.ActivityMapsBinding binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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

    final static String imageHost = "https://geodesy.noaa.gov";
    final Picasso picasso = Picasso.get();

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.setMapColorScheme(MapColorScheme.FOLLOW_SYSTEM);
        map.setOnMapLongClickListener(this::loadMap);

        String theme = darkMode ? "dark" : "light";
        activeIcon = new MarkerOptions().anchor(0.5f, 0.5f).icon(BitmapDescriptorFactory.fromAsset("active_" + theme + ".png"));
        goneIcon = new MarkerOptions().anchor(0.5f, 0.5f).icon(BitmapDescriptorFactory.fromAsset("gone_" + theme + ".png"));
        locatorIcon = new MarkerOptions().anchor(0.5f, 0.5f).icon(BitmapDescriptorFactory.fromAsset("locator_" + theme + ".png")).zIndex(2);

        PopupWindow popup = new PopupWindow(this);
        popup.setAnimationStyle(R.style.popup_animation);
        View view = getLayoutInflater().inflate(R.layout.popup, findViewById(R.id.popup), false);
        ScrollView scrollView = view.findViewById(R.id.scroll_view);
        Button close = view.findViewById(R.id.close);
        close.setOnClickListener(e -> popup.dismiss());
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

            volleyQueue.cancelAll(request -> request.getClass().equals(JsonArrayRequest.class));
            popup.dismiss();
            imagesTitle.setVisibility(View.GONE);

            try {
                JSONObject markerData = (JSONObject) marker.getTag();
                assert markerData != null;
                String pid = markerData.getString("pid");

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
                submit.setOnClickListener(click -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://geodesy.noaa.gov/cgi-bin/mark_recovery_form.prl?liteMode=true&PID=" + pid))));

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
                if (markerData.has("HISTORY")) {
                    historyGroup.setVisibility(View.VISIBLE);

                    JSONArray history = markerData.getJSONArray("HISTORY");
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
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
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

    private void loadMarkers(LatLng latLng) {
        volleyQueue.add(new JsonArrayRequest(
                Request.Method.GET,
                "https://surveymarkers.eliasfretwell.com/getMarkers?lat=" + latLng.latitude + "&lon=" + latLng.longitude + "&radius=5&fields=pid,latitude,longitude,marker,setting,stamping,description,HISTORY",
                null,
                markers -> {
                    try {
                        for (int i = 0; i < markers.length(); i++) {
                            JSONObject marker = markers.getJSONObject(i);
                            double markerLatitude = marker.getDouble("latitude");
                            double markerLongitude = marker.getDouble("longitude");

                            LatLng markerLatLng = new LatLng(markerLatitude, markerLongitude);
                            boolean gone;
                            if (!marker.has("HISTORY"))
                                // If marker doesn't have history, mark it as existing
                                gone = false;
                            else {
                                JSONArray history = marker.getJSONArray("HISTORY");
                                String condition = history.getJSONObject(history.length() - 1).getString("condition");
                                // https://www.ngs.noaa.gov/web/tools/updates/windesc5/dformat_documentation.shtml#A.2
                                gone = condition.equals("MARK NOT FOUND") || condition.equals("SURFACE MARK KNOWN DESTROYED") || condition.equals("UNDERGROUND MARK DESTROYED");
                            }
                            Marker mapMarker = map.addMarker((gone ? goneIcon : activeIcon).position(markerLatLng));
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
}
