package com.eink.norefresh;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.*;
import android.util.Log;

public class PrefsActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener
{
    private boolean changed = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        updateSummary((ListPreference) findPreference("taps_number"));
        updateSummary((ListPreference) findPreference("contrast_setting"));

        ((CheckBoxPreference) findPreference("on_off")).setChecked(A2Service.serviceRunning);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Preference p = findPreference(key);
        if (p == null)
            return;

        if (key.equals("on_off")) {
            if (((CheckBoxPreference) p).isChecked())
                startService(new Intent(this, A2Service.class));
            else
                stopService(new Intent(this, A2Service.class));
        } else {
            if (key.equals("taps_number") || key.equals("contrast_setting"))
                updateSummary((ListPreference) p);
            changed = true;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (changed && A2Service.serviceRunning) {
            Log.i("A2", "Will Update settings");
            stopService(new Intent(this, A2Service.class));
            startService(new Intent(this, A2Service.class));
        }
    }

    void updateSummary(ListPreference lp) {
        lp.setSummary(lp.getEntry());
        getListView().postInvalidate();
    }
}
