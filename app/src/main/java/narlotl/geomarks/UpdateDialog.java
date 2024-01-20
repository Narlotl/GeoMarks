package narlotl.geomarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

public  class UpdateDialog extends DialogFragment {

    private final String newVersion;
    private final String currentVersion;
    public UpdateDialog(String newVersion, String currentVersion) {
        super();
        this.newVersion = newVersion;
        this.currentVersion = currentVersion;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();
        assert activity != null;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setMessage("New version available: " + this.newVersion + " (current: " + currentVersion + ")").setPositiveButton("Update", (dialogInterface, i) -> {
            Intent update = new Intent(Intent.ACTION_VIEW);
            update.setData(Uri.parse("https://github.com/Narlotl/GeoMarks/releases/latest"));
            startActivity(update);
        }).setNegativeButton("Dismiss", (dialogInterface, i) -> this.dismiss());
        return builder.create();
    }
}
