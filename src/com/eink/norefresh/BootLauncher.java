package com.eink.norefresh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class BootLauncher extends BroadcastReceiver
{
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (sharedPrefs.getBoolean("start_startup", false)) {
            Intent startServiceIntent = new Intent(context, A2Service.class);
            context.startService(startServiceIntent);
        }
    }
}
