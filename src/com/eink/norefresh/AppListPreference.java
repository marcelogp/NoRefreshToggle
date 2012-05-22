package com.eink.norefresh;

import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.AttributeSet;
import java.util.List;

public class AppListPreference extends ListPreferenceMultiSelect
{
    private Context mContext;

    public AppListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    protected void onPrepareDialogBuilder(Builder builder) {
        loadAppsList();
        super.onPrepareDialogBuilder(builder);
    }

    private void loadAppsList() {
        final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = mContext.getPackageManager().queryIntentActivities(mainIntent, 0);

        String[] pkgNames = new String[apps.size()];
        CharSequence[] appNames = new String[apps.size()];

        for (int i = 0; i < apps.size(); i++) {
            pkgNames[i] = apps.get(i).activityInfo.packageName;
            appNames[i] = apps.get(i).loadLabel(mContext.getPackageManager());
        }
        setEntries(appNames);
        setEntryValues(pkgNames);
    }
}
