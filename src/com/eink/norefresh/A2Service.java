package com.eink.norefresh;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;

public class A2Service extends Service
{
    View dummyView;
    private float lastY;
    private float lastX;
    private int downHit;
    private int upHit;
    private static int HIT_COUNT_TARGET = 4;
    private boolean justCreated = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
                    N2EpdController.enterA2Mode();
                    downHit = 0;
                } else if (upHit == HIT_COUNT_TARGET - 1) {
                    N2EpdController.exitA2Mode();
                    N2EpdController.setGL16Mode(1);
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
        if (! justCreated) {
            try {
                /* Some delay to avoid setting A2 before Activity ends, which would have no effect */
                Thread.sleep(500L);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            N2EpdController.enterA2Mode();
        }
        justCreated = false;
        
        return Service.START_STICKY;
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
