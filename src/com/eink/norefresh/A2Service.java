package com.eink.norefresh;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.preference.PreferenceManager;
import android.util.Log;
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
    private View dummyView;
    private GestureModule gestDetector;
    private BufferedReader autoReader, overrideReader;
    private boolean a2Active = false, a2Processing = false;
    private Display display;
    private View overlay;
    private Button contrastUp, contrastDown;
    private Button shadeOverlay;
    private CountDownTimer idleTimer, overlayTimer;
    private AutoThread autoThread;
    private OverrideThread overrideThread;
    private Handler viewHandler;
    public static boolean serviceRunning = false;
    private boolean autoOn, autoOff;
    private boolean contrastOn, clearScreen;
    private boolean gestureOn, gestureOff;
    private boolean keepManual;
    private int gestureTaps;
    private static int[] shadeLevels = {0x60, 0x80, 0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xDC, 0xE1, 0xE5};
    private int shadeColor;
    private boolean ignoreNext;
    ForegroundApps foreground;
    private String appWhiteList;
    private int lastActivationMode;
    private static int SAMPLES_N = 9;
    private static int SAMPLING_DELAY_MS = 0;
    private static int FASTEST_THR_MS = 400, FAST_THR_MS = 2000;
    private static int A2_GHOSTING_DELAY_MS = 600;
    private static int A2_IDLE_MS = 1500;
    private static int OVERLAY_TIMEOUT = 3000;
    private static int MSG_TRIGGER_AUTO = 0, MSG_KEEP_AUTO = 1, MSG_EPD_OVERRIDE = 2;
    private static int ACTIVATION_MANUAL = 0, ACTIVATION_GEST = 1, ACTIVATION_AUTO = 2;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        loadSettings();

        WindowManager.LayoutParams passThroughParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        WindowManager.LayoutParams activeParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        display = wm.getDefaultDisplay();

        LayoutInflater l = LayoutInflater.from(this);

        if (autoOn || autoOff) {
            try {
                Process process = Runtime.getRuntime().exec("/system/bin/logcat -v time SurfaceFlinger:D *:S");
                autoReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            autoThread = new AutoThread(autoReader, this);
            autoThread.start();
        }

        try {
            Process process = Runtime.getRuntime().exec("/system/bin/logcat NATIVE-EPD:D EPD#NoRefreshToggle:D EPD#Dialog:D EPD#StatusBar:D EPD#KeyboardView:D *:S");
            overrideReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        if (gestureOn || gestureOff) {
            dummyView = new View(this);
            gestDetector = new GestureModule(dummyView, gestureTaps)
            {
                @Override
                public void downGestureCallback() {
                    if (gestureOn)
                        setEpdA2Delay(ACTIVATION_GEST);
                }

                @Override
                public void upGestureCallback() {
                    if (gestureOff)
                        setEpdNormal();
                }
            };
            wm.addView(dummyView, passThroughParams);
        }

        if (contrastOn) {
            overlay = (View) l.inflate(R.layout.overlay, null);

            contrastUp = (Button) overlay.findViewById(R.id.contrast_up);

            contrastUp.setOnClickListener(new View.OnClickListener()
            {
                public void onClick(View v) {
                    if (shadeColor < shadeLevels.length - 1) {
                        shadeColor++;
                    }
                    setShadeColor(shadeColor);
                    startOverlayTimeout();
                }
            });

            contrastDown = (Button) overlay.findViewById(R.id.contrast_down);

            contrastDown.setOnClickListener(new View.OnClickListener()
            {
                public void onClick(View v) {
                    if (shadeColor > 0) {
                        shadeColor--;
                    }
                    setShadeColor(shadeColor);
                    startOverlayTimeout();
                }
            });
            shadeOverlay = (Button) l.inflate(R.layout.shade_overlay, null);
            setShadeColor(shadeColor);

            wm.addView(overlay, activeParams);
            wm.addView(shadeOverlay, passThroughParams);
        }

        flashButton = new Button(this);
        flashButton.setBackgroundColor(Color.WHITE);
        flashButton.setVisibility(View.GONE);

        wm.addView(flashButton, passThroughParams);

        serviceRunning = true;
        ignoreNext = true;

        foreground = new ForegroundApps(this);

        viewHandler = new Handler()
        {
            public void handleMessage(Message msg) {
                if (msg.what == MSG_EPD_OVERRIDE) {
                    Log.i("A2", "Received OVERRIDE MSG");
                    setEpdNormal();
                    return;
                }

                if ((msg.what == MSG_TRIGGER_AUTO || a2Active) && autoOff) {
                    if (idleTimer != null)
                        idleTimer.cancel();

                    idleTimer = new CountDownTimer(A2_IDLE_MS, A2_IDLE_MS)
                    {
                        @Override
                        public void onTick(long arg0) {
                        }

                        @Override
                        public void onFinish() {
                            if (a2Active && (!keepManual || lastActivationMode != ACTIVATION_MANUAL))
                                setEpdNormal();
                        }
                    }.start();
                }

                if (!a2Active && msg.what == MSG_TRIGGER_AUTO && autoOn)
                    setEpdA2Delay(ACTIVATION_AUTO);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && !ignoreNext && !a2Active) {
            try {
                /*
                 * Some delay to avoid setting A2 before Activity ends, which would
                 * have no effect
                 */
                Thread.sleep(600L);
            } catch (InterruptedException ex) {
            }
            setEpdA2Delay(ACTIVATION_MANUAL);
        } else
            setEpdNormal();
        ignoreNext = false;

        return Service.START_STICKY;
    }

    private void loadSettings() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        appWhiteList = sharedPrefs.getString("whitelist", null);
        autoOn = sharedPrefs.getBoolean("auto_activate", false);
        autoOff = sharedPrefs.getBoolean("auto_deactivate", false);
        contrastOn = sharedPrefs.getBoolean("contrast_enable", false);
        shadeColor = sharedPrefs.getInt("contrast_value", 5);
        clearScreen = sharedPrefs.getBoolean("clear_screen", false);
        gestureOn = sharedPrefs.getBoolean("act_gesture", false);
        gestureOff = sharedPrefs.getBoolean("deact_gesture", false);
        gestureTaps = Integer.parseInt(sharedPrefs.getString("taps_number", "3"));
        FASTEST_THR_MS = Integer.parseInt(sharedPrefs.getString("activate_delay", "0"));
        A2_IDLE_MS = Integer.parseInt(sharedPrefs.getString("deactivate_delay", "0"));
        keepManual = sharedPrefs.getBoolean("keep_manual", false);
    }

    private void saveSettings() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefs.edit().putInt("contrast_value", shadeColor);
        sharedPrefs.edit().commit();
    }

    private void setEpdNormal() {
        if (contrastOn) {
            shadeOverlay.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
        }

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

    private void setEpdA2Delay(int mode) {
        if (mode != ACTIVATION_MANUAL && !isAllowedApp(foreground.getForegroundApp())) {
            Log.i("A2", "A2 not active for " + foreground.getForegroundApp());
            return;
        }
        if (a2Active || a2Processing) {
            Log.i("A2", "Unsucessful A2 attempt (" + mode + ")");
            return;
        }
        a2Processing = true;
        lastActivationMode = mode;

        int delay = 0;
        if (clearScreen)
            delay = A2_GHOSTING_DELAY_MS;

        blankScreen(flashButton);

        if (contrastOn) {
            shadeOverlay.setVisibility(View.VISIBLE);
            shadeOverlay.postInvalidate();
            if (mode != ACTIVATION_AUTO)
                startOverlayTimeout();
        }
        overrideThread = new OverrideThread(overrideReader, this);
        overrideThread.setRunning(true);

        CountDownTimer timer = new CountDownTimer(delay, delay)
        {
            @Override
            public void onTick(long arg0) {
            }

            @Override
            public void onFinish() {
                setEpdA2();
                a2Processing = false;
                overrideThread.start();
                flashButton.setVisibility(View.GONE);
            }
        }.start();
    }

    private void startOverlayTimeout() {
        if (overlayTimer != null)
            overlayTimer.cancel();
        overlay.setVisibility(View.VISIBLE);

        viewHandler.sendEmptyMessage(MSG_KEEP_AUTO);

        overlayTimer = new CountDownTimer(OVERLAY_TIMEOUT, OVERLAY_TIMEOUT)
        {
            @Override
            public void onTick(long arg0) {
            }

            @Override
            public void onFinish() {
                overlay.setVisibility(View.GONE);
            }
        }.start();
    }

    private void setShadeColor(int c) {
        shadeOverlay.setBackgroundColor((shadeLevels[c] << 24) + 0xffffff);
    }

    private boolean isAllowedApp(String pkgName) {
        return ListPreferenceMultiSelect.contains(pkgName, appWhiteList, null);
    }

    class AutoThread extends Thread
    {
        private BufferedReader reader;
        private volatile boolean stop = false;

        public AutoThread(BufferedReader reader, Context c) {
            this.reader = reader;
        }

        @Override
        public void run() {
            try {
                String line;
                long tval = 0;
                long[] tl = new long[SAMPLES_N];
                int upos = 0;

                while (!stop && reader.ready())
                    reader.readLine();

                while (!stop && (line = reader.readLine()) != null) {
                    line = line.substring(0, 17);
                    SimpleDateFormat format = new SimpleDateFormat("dd-MM HH:mm:ss.SSS");

                    Date parsed = format.parse(line);
                    tval = parsed.getTime();

                    if (tval - tl[upos] < FASTEST_THR_MS) {
                        viewHandler.sendEmptyMessage(MSG_TRIGGER_AUTO);
                        tl = new long[SAMPLES_N];
                    } else if (tval - tl[upos] < FAST_THR_MS) {
                        viewHandler.sendEmptyMessage(MSG_KEEP_AUTO);
                    }
                    tl[upos] = tval;
                    upos = (upos + 1) % SAMPLES_N;

                    Thread.sleep(SAMPLING_DELAY_MS);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        public synchronized void requestStop() {
            stop = true;
        }
    }

    class OverrideThread extends Thread
    {
        private BufferedReader reader;
        private volatile boolean _run = false;

        public OverrideThread(BufferedReader reader, Context c) {
            this.reader = reader;
        }

        @Override
        public void run() {
            try {
                String line;

                a2Active = true;

                while (reader.ready())
                    reader.readLine();

                while (_run && (line = reader.readLine()) != null) {
                    if (!_run)
                        break;

                    if (line.contains("600,800 = A2"))
                        a2Active = true;
                    if (line.contains("epd_reset_region") || line.contains("600,800 = GU")) {
                        a2Active = false;
                        viewHandler.sendEmptyMessage(MSG_EPD_OVERRIDE);
                        break;
                    }

                    Thread.sleep(500L);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        public synchronized void setRunning(boolean run) {
            _run = run;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saveSettings();

        removeView(dummyView);
        removeView(overlay);
        removeView(shadeOverlay);
        removeView(flashButton);
        if (autoThread != null)
            autoThread.requestStop();
        if (overrideThread != null)
            overrideThread.setRunning(false);

        serviceRunning = false;
    }

    private void removeView(View v) {
        if (v != null)
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
    }
}
