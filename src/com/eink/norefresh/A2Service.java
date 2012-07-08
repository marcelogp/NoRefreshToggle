package com.eink.norefresh;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import net.tedstein.AndroSS.AndroSS;

public class A2Service extends Service
{
    private Button flashButton;
    private View dummyView;
    private GestureModule gestDetector = null;
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
    private boolean pref_autoOn, pref_autoOff;
    private String pref_contrast;
    private boolean pref_gestureOn, pref_gestureOff;
    private boolean pref_keepManual, pref_clearScreen;
    private int pref_gestureTaps;
    private static final int[] shadeLevels = {0x60, 0x80, 0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xDC, 0xE1, 0xE5};
    private int pref_shadeColor;
    private boolean ignoreNext;
    private ForegroundApps foreground;
    private AndroSS ssManager;
    private String pref_appWhiteList;
    private int lastActivationMode;
    private static final int SAMPLES_N = 9;
    private static int FASTEST_THR_MS = 400, FAST_THR_MS = 2000;
    private static final int A2_GHOSTING_DELAY_MS = 600;
    private static int A2_IDLE_MS = 1500;
    private static final int OVERLAY_TIMEOUT = 4000;
    private static final int MSG_TRIGGER_AUTO = 0, MSG_KEEP_AUTO = 1, MSG_EPD_OVERRIDE = 2;
    private static final int ACTIVATION_MANUAL = 0, ACTIVATION_GEST = 1, ACTIVATION_AUTO = 2;
    private static final int LUMINANCE_TARGET = 355000000;
    private static final int CONTRAST_MAX = 0xDC, CONTRAST_MIN = 0x60;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        loadSettings();
        
        viewHandler = new Handler()
        {
            public void handleMessage(Message msg) {
                if (msg.what == MSG_EPD_OVERRIDE) {
                    setEpdNormal();
                    return;
                }

                if ((msg.what == MSG_TRIGGER_AUTO || a2Active) && pref_autoOff) {
                    if (idleTimer != null)
                        idleTimer.cancel();

                    idleTimer = new CountDownTimer(A2_IDLE_MS, A2_IDLE_MS)
                    {
                        @Override
                        public void onTick(long arg0) {
                        }

                        @Override
                        public void onFinish() {
                            if (a2Active && (!pref_keepManual || lastActivationMode != ACTIVATION_MANUAL))
                                setEpdNormal();
                        }
                    }.start();
                }

                if (!a2Active && msg.what == MSG_TRIGGER_AUTO && pref_autoOn)
                    setEpdA2Delay(ACTIVATION_AUTO);
            }
        };

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

        if (pref_autoOn || pref_autoOff) {
            try {
                // Get a adb logcat stream containing approximate event about framebuffer updates
                // used for screen animation detecting
                Process process = Runtime.getRuntime().exec("/system/bin/logcat -v time SurfaceFlinger:D *:S");
                autoReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            autoThread = new AutoThread(autoReader, this);
            autoThread.start();
        }

        try {
            // Get a adb logcat stream containing information about possible screen mode overrides (by system)
            Process process = Runtime.getRuntime().exec("/system/bin/logcat NATIVE-EPD:D EPD#NoRefreshToggle:D EPD#Dialog:D EPD#StatusBar:D EPD#KeyboardView:D *:S");
            overrideReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        if (pref_gestureOn || pref_gestureOff) {
            dummyView = new View(this);
            gestDetector = new GestureModule(dummyView, pref_gestureTaps)
            {
                @Override
                public void downGestureCallback() {
                    if (pref_gestureOn)
                        setEpdA2Delay(ACTIVATION_GEST);
                }

                @Override
                public void upGestureCallback() {
                    if (pref_gestureOff)
                        setEpdNormal();
                }
            };
            wm.addView(dummyView, passThroughParams);
        }

        if (!pref_contrast.equals("off")) {
            overlay = (View) l.inflate(R.layout.overlay, null);

            contrastUp = (Button) overlay.findViewById(R.id.contrast_up);

            contrastUp.setOnClickListener(new View.OnClickListener()
            {
                public void onClick(View v) {
                    if (pref_shadeColor < shadeLevels.length - 1) {
                        pref_shadeColor++;
                    }
                    setShadeColor(pref_shadeColor);
                    startOverlayTimeout();
                }
            });

            contrastDown = (Button) overlay.findViewById(R.id.contrast_down);

            contrastDown.setOnClickListener(new View.OnClickListener()
            {
                public void onClick(View v) {
                    if (pref_shadeColor > 0) {
                        pref_shadeColor--;
                    }
                    setShadeColor(pref_shadeColor);
                    startOverlayTimeout();
                }
            });
            shadeOverlay = (Button) l.inflate(R.layout.shade_overlay, null);
            setShadeColor(pref_shadeColor);

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
        
        ssManager = new AndroSS(this);
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

        pref_appWhiteList = sharedPrefs.getString("whitelist", "");
        pref_autoOn = sharedPrefs.getBoolean("auto_activate", false);
        pref_autoOff = sharedPrefs.getBoolean("auto_deactivate", false);
        pref_contrast = sharedPrefs.getString("contrast_setting", "off");
        pref_shadeColor = sharedPrefs.getInt("contrast_value", 5);
        pref_clearScreen = sharedPrefs.getBoolean("clear_screen", false);
        pref_gestureOn = sharedPrefs.getBoolean("act_gesture", false);
        pref_gestureOff = sharedPrefs.getBoolean("deact_gesture", false);
        pref_gestureTaps = Integer.parseInt(sharedPrefs.getString("taps_number", "3"));
        FASTEST_THR_MS = Integer.parseInt(sharedPrefs.getString("activate_delay", "0"));
        A2_IDLE_MS = Integer.parseInt(sharedPrefs.getString("deactivate_delay", "0"));
        pref_keepManual = sharedPrefs.getBoolean("keep_manual", false);
    }

    private void saveSettings() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefs.edit().putInt("contrast_value", pref_shadeColor);
        sharedPrefs.edit().commit();
    }

    private void setEpdNormal() {
        if (!pref_contrast.equals("off")) {
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
            Log.e("A2", "A2 not active for " + foreground.getForegroundApp());
            return;
        }
        if (a2Active || a2Processing) {
            Log.e("A2", "Unsucessful A2 attempt (" + mode + ")");
            return;
        }
        a2Processing = true;
        lastActivationMode = mode;

        int delay = pref_clearScreen ? A2_GHOSTING_DELAY_MS : 0;
        
        if (!pref_contrast.equals("off")) {
            if (pref_contrast.equals("auto") || 
                (pref_contrast.equals("manual") && mode == ACTIVATION_GEST) ||
                (pref_contrast.equals("gesture") && mode != ACTIVATION_AUTO))
                setBestConstrast(); // automatic contrast
            else
                startOverlayTimeout(); // manual contrast
            
            shadeOverlay.setVisibility(View.VISIBLE);
            shadeOverlay.postInvalidate();
        }

        blankScreen(flashButton);

        overrideThread = new OverrideThread(overrideReader, this);
        overrideThread.setRunning(true);

        new CountDownTimer(delay, delay)
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
        return ListPreferenceMultiSelect.contains(pkgName, pref_appWhiteList, null);
    }
    
    private void setBestConstrast() {
        Bitmap ss = ssManager.getScreenshot();
        int[] pixels = new int[ss.getHeight()*ss.getWidth()];
        ss.getPixels(pixels, 0, ss.getWidth(), 0, 0, ss.getWidth(), ss.getHeight());
        
        /* Get screenshot "luminance" and calculate, when possible, an shade alpha 
         * such that final "luminance" is equal to LUMINANCE_TARGET
         */
        int lumi = getTotalLuminance(pixels);
        int maxLumi = 3*255*ss.getWidth()*ss.getHeight();
        double alpha = ((double) (LUMINANCE_TARGET - lumi)) / (maxLumi - lumi);
        int ialpha = Math.min(CONTRAST_MAX, Math.max(CONTRAST_MIN, (int)(alpha*255)));
        
        Log.i("A2", "Screen lumiance = "+lumi);
        Log.i("A2", "Target lumiance = "+LUMINANCE_TARGET);
        Log.i("A2", "Max lumiance = "+maxLumi);
        Log.i("A2", "Target alpha (0 to 255) = "+ialpha);
        
        shadeOverlay.setBackgroundColor((ialpha << 24) + 0xffffff);
    }
    
    private int getTotalLuminance(int[] pixels) {
        int avg = 0;
        for (int px : pixels)
            avg += (px & 0xff) + ((px>>8) & 0xff) + ((px>>16) & 0xff);
        
        return avg;
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
                        // a "fastest" animation should trigger A2 during a while
                        viewHandler.sendEmptyMessage(MSG_TRIGGER_AUTO);
                        tl = new long[SAMPLES_N];
                    } else if (tval - tl[upos] < FAST_THR_MS) {
                        // a "fast" animation should keep A2 active during a shorter while
                        viewHandler.sendEmptyMessage(MSG_KEEP_AUTO);
                    }
                    tl[upos] = tval;
                    upos = (upos + 1) % SAMPLES_N;
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
