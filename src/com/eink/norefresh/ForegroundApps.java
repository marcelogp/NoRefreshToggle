package com.eink.norefresh;

import android.app.ActivityManager;
import android.content.Context;
import java.util.List;

public class ForegroundApps
{
    Context mContext;

    public ForegroundApps(Context c) {
        mContext = c;
    }

    public String getForegroundApp() {
        ActivityManager mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> appProcesses = mActivityManager.getRunningTasks(1);

        return appProcesses.get(0).topActivity.getPackageName();
    }
}
