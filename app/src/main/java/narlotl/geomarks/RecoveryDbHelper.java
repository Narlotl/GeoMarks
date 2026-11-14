package narlotl.geomarks;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class RecoveryDbHelper extends SQLiteOpenHelper {
    public static final String PID_KEY = "PID";
    public static final String CONDITION_KEY = "condition";
    public static final String TIME_KEY = "time";
    public static final String TABLE_NAME = "recoveries";

    public RecoveryDbHelper(Context context) {
        super(context, "Recovery.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (_ID INTEGER PRIMARY KEY, " + PID_KEY + " TEXT, " + CONDITION_KEY + " INTEGER, " + TIME_KEY + "  INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }
}
