package com.eink.norefresh;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class A2Service extends Service
{
    View dummyView;
    private float lastY;
    private float lastX;
    private int downHit;
    private int upHit;
    private static int HIT_COUNT_TARGET = 4;
    private boolean justCreated = false;
    private Process process;
    private BufferedReader reader;
    private boolean a2Active = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            process = Runtime.getRuntime().exec("/system/bin/logcat NATIVE-EPD:D EPD#NoRefreshToggle:D *:S");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        OnTouchListener touch = new OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float x = event.getX();
                float y = event.getY();

                if (x > lastX && y > lastY)
                    downHit++;
                else
                    downHit = 0;

                if (x < lastX && y < lastY)
                    upHit++;
                else
                    upHit = 0;

                lastX = x;
                lastY = y;

                if (downHit == HIT_COUNT_TARGET - 1) {
                    setEpdA2();
                    downHit = 0;
                } else if (upHit == HIT_COUNT_TARGET - 1) {
                    setEpdNormal();
                    upHit = 0;
                }
                return false;
            }
        };
        /*
         * Invisible dummy view to receive touch events
         */
        dummyView = new View(this);
        dummyView.setOnTouchListener(touch);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.addView(dummyView, params);

        justCreated = true;

        downHit = upHit = 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!justCreated && !guessA2Active()) {
            try {
                /*
                 * Some delay to avoid setting A2 before Activity ends, which
                 * would have no effect
                 */
                Thread.sleep(500L);
            } catch (InterruptedException ex) {
            }
            setEpdA2();
        }
        justCreated = false;

        return Service.START_STICKY;
    }

    private void setEpdNormal() {
        N2EpdController.exitA2Mode();
        N2EpdController.setGL16Mode(1);
    }

    private void setEpdA2() {
        N2EpdController.enterA2Mode();
    }

    private boolean guessA2Active() {
        try {
            String line;
            while (reader.ready() && (line = reader.readLine()) != null) {
                if (line.contains("A2"))
                    a2Active = true;
                else if (line.contains("epd_reset_region"))
                    a2Active = false;
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return a2Active;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dummyView != null) {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(dummyView);
            dummyView = null;
        }
    }
}
