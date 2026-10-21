package com.instagram.insfav.fetcher;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class QueueStore extends SQLiteOpenHelper {
    QueueStore(Context context) { super(context, "fetcher-queue.db", null, 3); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE accounts (username TEXT PRIMARY KEY, position INTEGER NOT NULL, status TEXT NOT NULL, code TEXT NOT NULL DEFAULT '', eligible INTEGER NOT NULL DEFAULT 1, attempts INTEGER NOT NULL DEFAULT 0)"); db.execSQL("CREATE INDEX account_work_order ON accounts(status,position)"); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) db.execSQL("ALTER TABLE accounts ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS account_work_order ON accounts(status,position)");
    }
    void sync(Collection<String> users) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Set<String> wanted=new LinkedHashSet<>(users);
            for(String old:users(false)) if(!wanted.contains(old)) db.delete("accounts","username=?",new String[]{old});
            int position=0;
            for(String user:wanted) if(Identity.valid(user)) {
                ContentValues v=new ContentValues();v.put("username",user);v.put("position",position++);v.put("status","PENDING");db.insertWithOnConflict("accounts",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                v=new ContentValues();v.put("position",position-1);db.update("accounts",v,"username=?",new String[]{user});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void requeueLost(String user){getWritableDatabase().execSQL("UPDATE accounts SET status='PENDING',code='',attempts=0 WHERE username=? AND status IN ('SUCCESS','EXISTING')",new Object[]{user});}
    List<String> completedImages(){ArrayList<String> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE status IN ('SUCCESS','EXISTING')",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    int attempts(String user) {try(Cursor c=getReadableDatabase().rawQuery("SELECT attempts FROM accounts WHERE username=?",new String[]{user})){return c.moveToFirst()?c.getInt(0):0;}}
    void attempted(String user) {getWritableDatabase().execSQL("UPDATE accounts SET attempts=attempts+1 WHERE username=?",new String[]{user});}
    void retrySkipped(){getWritableDatabase().execSQL("UPDATE accounts SET status='PENDING',code='',attempts=0 WHERE status IN ('SKIPPED_UNAVAILABLE','SKIPPED_NO_IMAGE','FAILED','RETRY_LATER')");}
    void refreshLegacy(){getWritableDatabase().execSQL("UPDATE accounts SET status='PENDING',code='',attempts=0 WHERE status IN ('SUCCESS','EXISTING')");}
    void prepareMissing() {getWritableDatabase().execSQL("UPDATE accounts SET status='PENDING',code='',attempts=0,eligible=1 WHERE status NOT IN ('SUCCESS','EXISTING','SKIPPED_UNAVAILABLE','SKIPPED_NO_IMAGE')");}
    String next(){
        for(String condition:new String[]{"status='PENDING'","status='RETRY_LATER' AND attempts<3"})try(Cursor c=getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE "+condition+" ORDER BY position LIMIT 1",null)){if(c.moveToFirst())return c.getString(0);}return null;
    }
    List<String> eligible() {
        ArrayList<String> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE status='PENDING' OR (status='RETRY_LATER' AND attempts<3) ORDER BY CASE WHEN status='PENDING' THEN 0 ELSE 1 END,position",null)){while(c.moveToNext())result.add(c.getString(0));}return result;
    }
    List<String> users(boolean failedOnly) {
        ArrayList<String> users = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("accounts", new String[]{"username"}, failedOnly ? "status='FAILED'" : null, null, null, null, "position")) {
            while (cursor.moveToNext()) users.add(cursor.getString(0));
        }
        return users;
    }
    void mark(String user, String status, String code) {
        ContentValues values = new ContentValues(); values.put("status", status); values.put("code", code);
        getWritableDatabase().update("accounts", values, "username=?", new String[]{user});
    }
    List<String> pending() {
        ArrayList<String> users = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE status='PENDING' OR (status='RETRY_LATER' AND attempts<3) ORDER BY position", null)) { while (cursor.moveToNext()) users.add(cursor.getString(0)); }
        return users;
    }
    void reset(List<String> users) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.execSQL("UPDATE accounts SET eligible=0"); for (String user : users) { mark(user, "PENDING", ""); ContentValues v = new ContentValues(); v.put("eligible", 1); db.update("accounts", v, "username=?", new String[]{user}); } db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }
    Map<String, Integer> failures() {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,COUNT(*) FROM accounts WHERE status IN ('FAILED','RETRY_LATER') GROUP BY code ORDER BY COUNT(*) DESC", null)) { while(c.moveToNext()) result.put(c.getString(0), c.getInt(1)); }
        return result;
    }
    Map<String, Integer> counts() {
        Map<String, Integer> result = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT status,COUNT(*) FROM accounts GROUP BY status", null)) {
            while (cursor.moveToNext()) result.put(cursor.getString(0), cursor.getInt(1));
        }
        return result;
    }
}
