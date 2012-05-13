package com.eink.norefresh;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.EpdController;
import android.hardware.EpdController.Mode;
import android.hardware.EpdController.Region;
import android.hardware.EpdController.RegionParams;
import android.hardware.EpdController.Wave;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.*;
import android.widget.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;

public class A2Service extends Service
{
    private Button flashButton;
    private Process process;
    private BufferedReader reader;
    private boolean a2Active = false;
    private Display display;
    private Button shadeOverlay;
    private int shadeColor = 0xD0;
    private CountDownTimer idleTimer;
    private A2Monitor threadA2;
    private Handler viewHandler;
    private static int SAMPLES_N = 9;
    private static int SAMPLING_DELAY_MS = 0;
    private static int FASTEST_THR_MS = 400, FAST_THR_MS = 2000;
    private static int A2_GHOSTING_DELAY_MS = 500;
    private static int A2_IDLE_MS = 1500;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            process = Runtime.getRuntime().exec("/system/bin/logcat -v time SurfaceFlinger:D *:S");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        display = wm.getDefaultDisplay();

        LayoutInflater l = LayoutInflater.from(this);

        flashButton = new Button(this);
        flashButton.setBackgroundColor(Color.WHITE);
        flashButton.setVisibility(View.GONE);

        shadeOverlay = (Button) l.inflate(R.layout.shade_overlay, null);
        shadeOverlay.setBackgroundColor((shadeColor << 24) + 0xffffff);

        WindowManager.LayoutParams passThroughParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        wm.addView(flashButton, passThroughParams);
        wm.addView(shadeOverlay, passThroughParams);

        viewHandler = new Handler()
        {
            public void handleMessage(Message msg) {
                if (msg.what == 0 || a2Active) {
                    if (idleTimer != null)
                        idleTimer.cancel();

                    idleTimer = new CountDownTimer(A2_IDLE_MS, A2_IDLE_MS)
                    {
                        @Override
                        public void onTick(long arg0) {
                        }

                        @Override
                        public void onFinish() {
                            if (a2Active)
                                setEpdNormalDelay(0);
                        }
                    }.start();
                }

                if (!a2Active && msg.what == 0)
                    setEpdA2Delay(A2_GHOSTING_DELAY_MS);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        threadA2 = new A2Monitor(reader, this);
        threadA2.start();

        return Service.START_STICKY;
    }

    private void setEpdNormal() {
        a2Active = false;
        EpdController.setRegion("NoRefreshToggle", Region.APP_3,
                                new RegionParams(0, 0, 600, 800, Wave.A2, 16));
        EpdController.setRegion("NoRefreshToggle", Region.APP_3,
                                new RegionParams(0, 0, 600, 800, Wave.GU),
                                Mode.CLEAR_ALL);
    }

    private void setEpdA2() {
        a2Active = true;
        try {
            EpdController.setRegion("NoRefreshToggle", Region.APP_3,
                                    new RegionParams(0, 0, 600, 800, Wave.DU, 16));
            Thread.sleep(100L);
            EpdController.setRegion("NoRefreshToggle", Region.APP_3,
                                    new RegionParams(0, 0, 600, 800, Wave.A2, 14));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void blankScreen(Button view) {
        view.setHeight(display.getHeight());
        view.setWidth(display.getWidth());
        view.setVisibility(View.VISIBLE);
        view.postInvalidate();
    }
    
    private void setEpdNormalDelay(int ms) {
        blankScreen(flashButton);
        shadeOverlay.setVisibility(View.GONE);
        
        CountDownTimer timer = new CountDownTimer(ms, ms)
        {
            @Override
            public void onTick(long arg0) {
            }
            
            @Override
            public void onFinish() {
                setEpdNormal();
                flashButton.setVisibility(View.GONE);
            }
        }.start();
    }

    private void setEpdA2Delay(int ms) {
        blankScreen(flashButton);
        shadeOverlay.setVisibility(View.VISIBLE);
        shadeOverlay.postInvalidate();
        
        CountDownTimer timer = new CountDownTimer(ms, ms)
        {
            @Override
            public void onTick(long arg0) {
            }

            @Override
            public void onFinish() {
                setEpdA2();
                flashButton.setVisibility(View.GONE);
            }
        }.start();
    }

    class A2Monitor extends Thread
    {
        private BufferedReader reader;

        public A2Monitor(BufferedReader reader, Context c) {
            this.reader = reader;
        }

        @Override
        public void run() {
            try {
                String line;
                long tval = 0;
                long[] tl = new long[SAMPLES_N];
                int upos = 0;

                while (reader.ready())
                    reader.readLine();

                while ((line = reader.readLine()) != null) {
                    line = line.substring(0, 17);
                    SimpleDateFormat format = new SimpleDateFormat("dd-MM HH:mm:ss.SSS");

                    Date parsed = format.parse(line);
                    tval = parsed.getTime();

                    if (tval - tl[upos] < FASTEST_THR_MS) {
                        viewHandler.sendEmptyMessage(0);
                        tl = new long[SAMPLES_N];
                    } else if (tval - tl[upos] < FAST_THR_MS) {
                        viewHandler.sendEmptyMessage(1);
                    }
                    tl[upos] = tval;
                    upos = (upos + 1) % SAMPLES_N;

                    Thread.sleep(SAMPLING_DELAY_MS);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeView(shadeOverlay);
        removeView(flashButton);
    }

    private void removeView(View v) {
        if (v != null)
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
    }
}
