package narlotl.geomarks;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import android.widget.TextView;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NoConnectionError;
import com.android.volley.RetryPolicy;
import com.android.volley.toolbox.JsonArrayRequest;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.widget.AutocompleteSupportFragment;
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import org.apache.commons.text.WordUtils;

import narlotl.geomarks.databinding.ActivityMapsBinding;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {

    public static String capitalizeFirstLetter(String s) {
        String copy = s.toLowerCase();
        return copy.substring(0, 1).toUpperCase() + copy.substring(1);
    }

    private boolean darkMode;
    private GoogleMap map;
    private Geocoder geocoder;
    private RequestQueue volleyQueue;
    private final HashMap<String, String> states = new HashMap<>();
    private FusedLocationProviderClient fusedLocationClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        darkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        narlotl.geomarks.databinding.ActivityMapsBinding binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String apiKey = getString(R.string.API_KEY);
        if (!Places.isInitialized()) {
            Places.initialize(this, apiKey);
        }
        geocoder = new Geocoder(this, Locale.ENGLISH);

        volleyQueue = Volley.newRequestQueue(MapsActivity.this);

        volleyQueue.add(new JsonArrayRequest(Request.Method.GET,
                "https://firebasestorage.googleapis.com/v0/b/survey-markers.appspot.com/o/states.json?alt=media&token=56e7422d-1377-4aa4-8607-30d946323b09",
                null,
                data -> {
                    for (int i = 0; i < data.length(); i++)
                        try {
                            JSONObject state = data.getJSONObject(i);
                            states.put(state.getString("location"), state.getString("file").substring(0, 2));
                        } catch (JSONException e) {
                            Log.e("Error", e.toString());
                        }

                    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
                    Button recenter = findViewById(R.id.recenter);
                    recenter.setOnClickListener(e -> getLocation());

                    SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                            .findFragmentById(R.id.map);
                    assert mapFragment != null;
                    mapFragment.getMapAsync(this);
                },
                error -> {
                    ErrorDialog dialog;
                    if (error instanceof NoConnectionError)
                        dialog = new ErrorDialog("No internet connection", Settings.ACTION_WIFI_SETTINGS);
                    else
                        dialog = new ErrorDialog("Failed to load states\n" + error.getClass().toString().replace("class ", ""), error.getStackTrace());
                    dialog.show(getSupportFragmentManager(), "Request");
                    Log.e("Error", error.toString());
                }));
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        if (darkMode)
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(
                    this, R.raw.dark_map));
        else
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(
                    this, R.raw.light_map));
        map.setOnMapClickListener(e -> loadMap(e.latitude, e.longitude));

        AutocompleteSupportFragment autocompleteFragment = (AutocompleteSupportFragment) getSupportFragmentManager().findFragmentById(R.id.autocomplete);
        assert autocompleteFragment != null;
        autocompleteFragment.setPlaceFields(Collections.singletonList(Place.Field.LAT_LNG));
        autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {

            @Override
            public void onError(@NonNull Status status) {
                Log.e("Error", status.toString());
            }

            @Override
            public void onPlaceSelected(@NonNull Place place) {
                LatLng latLng = place.getLatLng();
                assert latLng != null;
                loadMap(latLng.latitude, latLng.longitude);
            }
        });

        PopupWindow popup = new PopupWindow(this);
        View view = getLayoutInflater().inflate(R.layout.popup, findViewById(R.id.scrollView), false);
        Button close = view.findViewById(R.id.close);
        close.setOnClickListener(e -> popup.dismiss());
        TextView title = view.findViewById(R.id.title);
        TextView setting = view.findViewById(R.id.setting);
        TextView stamping = view.findViewById(R.id.stamping);
        TextView description = view.findViewById(R.id.description);
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
                String id = markerData.getString("id");

                images.removeAllViews();
                volleyQueue.add(new JsonArrayRequest(Request.Method.GET, "https://us-central1-survey-markers.cloudfunctions.net/getImages?id=" + id, null, data -> {
                    try {
                        for (int i = 0; i < data.length(); i++) {
                            ImageView imageView = new ImageView(this);
                            if (i != 0)
                                imageView.setLayoutParams(imageParams);
                            final String image = data.getString(i);
                            Picasso.with(this).load(image).resize(images.getWidth(), 0).into(imageView, new Callback() {

                                @Override
                                public void onSuccess() {
                                    imagesTitle.setVisibility(View.VISIBLE);
                                }

                                @Override
                                public void onError() {
                                    ((LinearLayout) imageView.getParent()).removeView(imageView);
                                }
                            });
                            imageView.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(image))));
                            images.addView(imageView);
                        }
                    } catch (JSONException e) {
                        Log.e("Error", e.toString());
                    }
                }, error -> Log.e("Error", error.toString())));

                title.setText(id);
                setting.setText(getString(R.string.setting, capitalizeFirstLetter(markerData.getString("marker")), markerData.getString("setting").toLowerCase()));
                try {
                    stamping.setText(getString(R.string.stamping, markerData.getString("stamping")));
                } catch (JSONException e) {
                    view.findViewById(R.id.stamping_title).setVisibility(View.GONE);
                    stamping.setVisibility(View.GONE);
                }
                description.setText(WordUtils.capitalizeFully(markerData.getString("description")));
                JSONArray history = markerData.getJSONArray("history");
                StringBuilder historyText = new StringBuilder();
                for (int i = history.length() - 1; i >= 0; i--) {
                    JSONObject report = history.getJSONObject(i);
                    String date = report.getString("date");
                    historyText.append(date.substring(0, Math.min(4, date.length()))).append(" - ").append(capitalizeFirstLetter(report.getString("condition")));
                    if (i > 0) historyText.append("\n");
                }
                historyView.setText(historyText);
                double latitude = markerData.getDouble("latitude"), longitude = markerData.getDouble("longitude");
                openMap.setOnClickListener(click -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/place/" + latitude + "," + longitude))));
                submit.setOnClickListener(click -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://geodesy.noaa.gov/cgi-bin/mark_recovery_form.prl?liteMode=true&PID=" + id))));
            } catch (JSONException e) {
                Log.e("JSON", e.toString());
            }

            popup.setContentView(view);
            popup.showAtLocation(view, Gravity.CENTER, 0, 0);

            return true;
        });

        getLocation();
    }

    private void getLocation() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener(this, location -> loadMap(location.getLatitude(), location.getLongitude()));
    }

    private void loadMap(double latitude, double longitude) {
        map.clear();
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(latitude, longitude), 12f));
        try {
            Bitmap locatorBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeStream(getAssets().open("locator_" + ((darkMode) ? "dark" : "light") + ".png")), 72, 72, false);
            map.addMarker(new MarkerOptions().icon(BitmapDescriptorFactory.fromBitmap(locatorBitmap)).position(new LatLng(latitude, longitude)));
        } catch (IOException e) {
            Log.e("Locator", e.toString());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            geocoder.getFromLocation(latitude, longitude, 1, addresses -> {
                if (addresses.size() > 0)
                    loadMarkers(addresses.get(0).getAdminArea(), latitude, longitude);
            });
        else
            try {
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                assert addresses != null;
                if (addresses.size() > 0)
                    loadMarkers(addresses.get(0).getAdminArea(), latitude, longitude);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
    }

    private final RetryPolicy retryPolicy = new DefaultRetryPolicy(30000, 5, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT);

    private void loadMarkers(String state, double latitude, double longitude) {
        try {
            Bitmap activeBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeStream(getAssets().open("active_" + ((darkMode) ? "dark" : "light") + ".png")), 72, 72, false);
            Bitmap goneBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeStream(getAssets().open("gone_" + ((darkMode) ? "dark" : "light") + ".png")), 72, 72, false);

            final MarkerOptions active = new MarkerOptions().icon(BitmapDescriptorFactory.fromBitmap(activeBitmap));
            final MarkerOptions gone = new MarkerOptions().icon(BitmapDescriptorFactory.fromBitmap(goneBitmap));

            volleyQueue.add(new JsonObjectRequest(
                    Request.Method.GET,
                    "https://us-central1-survey-markers.cloudfunctions.net/getMarkers?state=" + states.get(state) + "&location=" + latitude + "," + longitude + "&radius=8.04672&data=id,latitude,longitude,marker,setting,description,history",
                    null,
                    data -> {
                        try {
                            JSONArray markers = data.getJSONArray("markers");
                            for (int i = 0; i < markers.length(); i++) {
                                JSONObject marker = markers.getJSONObject(i);
                                double markerLatitude = marker.getDouble("latitude");
                                double markerLongitude = marker.getDouble("longitude");

                                LatLng latLng = new LatLng(markerLatitude, markerLongitude);
                                JSONArray history = marker.getJSONArray("history");
                                String condition = history.getJSONObject(history.length() - 1).getString("condition");
                                Marker mapMarker = map.addMarker((condition.equals("GOOD") || condition.equals("POOR") ? active : gone).position(latLng));
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
        } catch (IOException e) {
            Log.e("Error", e.toString());
        }
    }
}
