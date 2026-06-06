package com.ziyun.music.data;

import android.content.Context;
import android.content.SharedPreferences;

public class NasConnectionStore {
    private static final String PREFS = "nas_connection";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_LAST_SYNC_COUNT = "last_sync_count";
    private static final String KEY_LAST_SYNC_TIME = "last_sync_time";

    private final SharedPreferences preferences;

    public NasConnectionStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Profile profile() {
        return new Profile(
                preferences.getString(KEY_ADDRESS, ""),
                preferences.getString(KEY_ACCOUNT, ""),
                preferences.getInt(KEY_LAST_SYNC_COUNT, 0),
                preferences.getLong(KEY_LAST_SYNC_TIME, 0L)
        );
    }

    public void saveProfile(String address, String account) {
        preferences.edit()
                .putString(KEY_ADDRESS, safe(address))
                .putString(KEY_ACCOUNT, safe(account))
                .apply();
    }

    public void saveSyncStats(int songCount) {
        preferences.edit()
                .putInt(KEY_LAST_SYNC_COUNT, Math.max(0, songCount))
                .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Profile {
        public final String address;
        public final String account;
        public final int lastSyncCount;
        public final long lastSyncTime;

        private Profile(String address, String account, int lastSyncCount, long lastSyncTime) {
            this.address = address;
            this.account = account;
            this.lastSyncCount = lastSyncCount;
            this.lastSyncTime = lastSyncTime;
        }

        public boolean hasConnectionInfo() {
            return !address.isEmpty() || !account.isEmpty();
        }
    }
}
