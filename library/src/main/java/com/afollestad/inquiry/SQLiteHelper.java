package com.afollestad.inquiry;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

class SQLiteHelper extends SQLiteOpenHelper {

    private String lastTableName;

    SQLiteHelper(Context context, String databaseName, int version) {
        super(context, databaseName == null || databaseName.equals(":memory") ? null : databaseName, null, version);
    }

    void createTableIfNecessary(String name, Class<?> rowCls) {
        lastTableName = name;
        try {
            String columns = ClassRowConverter.getClassSchema(rowCls);
            String createStatement = String.format("CREATE TABLE IF NOT EXISTS %s (%s);", name, columns);
            getWritableDatabase().execSQL(createStatement);
        } catch (Exception e) {
            Utils.wrapInReIfNeccessary(e);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (lastTableName == null) return;
        Log.w(SQLiteHelper.class.getName(), "Upgrading database from version " + oldVersion + " to " + newVersion + ", which will destroy all old data");
        db.execSQL("DROP TABLE IF EXISTS " + lastTableName);
        onCreate(db);
    }

    public final Cursor query(String tableName, String[] projection, String selection,
                              String[] selectionArgs, String groupBy, String sortOrder) {
        return getReadableDatabase().query(tableName, projection, selection, selectionArgs, groupBy, null, sortOrder);
    }

    public final long insert(String tableName, ContentValues values) {
        return getWritableDatabase().insert(tableName, null, values);
    }

    public final int delete(String tableName, String selection, String[] selectionArgs) {
        if (selection == null) selection = "1";
        return getWritableDatabase().delete(tableName, selection, selectionArgs);
    }

    public final int update(String tableName, ContentValues values, String selection, String[] selectionArgs) {
        return getWritableDatabase().update(tableName, values, selection, selectionArgs);
    }

}