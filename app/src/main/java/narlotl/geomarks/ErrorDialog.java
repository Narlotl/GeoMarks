package narlotl.geomarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import java.util.Arrays;

public class ErrorDialog extends DialogFragment {

    private final String message;
    private StackTraceElement[] traceback;
    public ErrorDialog(String message, StackTraceElement[] traceback) {
        super();
        this.message = message;
        this.traceback = traceback;
    }

    private String setting;
    public ErrorDialog(String message, String setting) {
        super();
        this.message = message;
        this.setting = setting;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();
        assert activity != null;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        if (traceback!=null)
            builder.setMessage(this.message).setNegativeButton("Close", (dialogInterface, i) -> {
                activity.finish();
                System.exit(0);
            }).setPositiveButton("Report", (dialogInterface, i) -> {
                Intent report = new Intent(Intent.ACTION_VIEW);
                report.setData(Uri.parse("https://github.com/narlotl/GeoMarks/issues/new?title=" +message + "&body=" + Arrays.toString(traceback)));
                activity.startActivity(report);
            });
        else
            builder.setMessage(this.message).setNegativeButton("Close", (dialogInterface, i) -> {
                activity.finish();
                System.exit(0);
            }).setPositiveButton("Settings", (dialogInterface, i) -> {
                Intent settings = new Intent(setting);
                activity.startActivity(settings);
            });
        return builder.create();
    }
}