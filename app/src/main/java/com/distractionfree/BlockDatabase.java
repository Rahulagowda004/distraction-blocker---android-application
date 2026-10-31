package com.distractionfree;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class BlockDatabase extends SQLiteOpenHelper {
    private static final String NAME = "distraction_free.db";
    private static final int VERSION = 1;
    private static final String APPS = "blocked_apps";
    private static final String DOMAINS = "blocked_domains";

    BlockDatabase(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE blocked_apps (package_name TEXT PRIMARY KEY NOT NULL)");
        db.execSQL("CREATE TABLE blocked_domains (domain TEXT PRIMARY KEY NOT NULL)");
        String[] defaults = {"instagram.com", "youtube.com", "facebook.com", "x.com", "reddit.com"};
        for (String domain : defaults) {
            ContentValues values = new ContentValues();
            values.put("domain", domain);
            db.insert(DOMAINS, null, values);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // There is no destructive migration; future schema changes must preserve restrictions.
    }

    List<String> getBlockedApps() {
        return readValues(APPS, "package_name");
    }

    List<String> getBlockedDomains() {
        return readValues(DOMAINS, "domain");
    }

    void setAppBlocked(String packageName, boolean blocked) {
        SQLiteDatabase db = getWritableDatabase();
        if (blocked) {
            ContentValues values = new ContentValues();
            values.put("package_name", packageName);
            db.insertWithOnConflict(APPS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } else {
            db.delete(APPS, "package_name = ?", new String[]{packageName});
        }
    }

    void addDefaultAppIfInstalled(String packageName) {
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        getWritableDatabase().insertWithOnConflict(APPS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    boolean addDomain(String domain) {
        ContentValues values = new ContentValues();
        values.put("domain", domain);
        return getWritableDatabase().insertWithOnConflict(
                DOMAINS, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    void removeDomain(String domain) {
        getWritableDatabase().delete(DOMAINS, "domain = ?", new String[]{domain});
    }

    private List<String> readValues(String table, String column) {
        List<String> values = new ArrayList<>();
        try (android.database.Cursor cursor = getReadableDatabase().query(
                table, new String[]{column}, null, null, null, null, column + " COLLATE NOCASE")) {
            while (cursor.moveToNext()) {
                values.add(cursor.getString(0));
            }
        }
        return values;
    }
}
