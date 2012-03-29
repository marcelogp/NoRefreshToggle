package com.eink.norefresh;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.*;
import android.view.View.OnTouchListener;
import android.widget.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;

public class A2Service extends Service
{
    private View dummyView;
    private Button flashButton;
    private float lastY, lastX;
    private int downHit, upHit;
    private LinkedList<Long> touchTimes;
    private boolean ignoreNext = false;
    private Process process;
    private BufferedReader reader;
    private boolean a2Active = false;
    private Display display;
    private View overlay;
    private Button contrastUp, contrastDown;
    private Button shadeOverlay;
    private static int HIT_COUNT_TARGET = 4;
    private static int OVERLAY_TIMEOUT = 3000;
    private static int GESTURE_TIMEOUT = 3000;
    private static boolean GESTURES_ENABLED = false;
    private static int[] shadeLevels = {0x60, 0x80, 0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xDC, 0xE1, 0xE5};
    private int shadeColor = 6;
    private CountDownTimer overlayTimer;
    private A2Monitor threadA2;
    private Handler viewHandler;
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        try {
            process = Runtime.getRuntime().exec("/system/bin/logcat NATIVE-EPD:D EPD#NoRefreshToggle:D EPD#Dialog:D EPD#StatusBar:D EPD#KeyboardView:D *:S");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        display = wm.getDefaultDisplay();

        /*
         * Invisible dummy view to receive touch events
         */
        dummyView = new View(this);
        
        flashButton = new Button(this);
        flashButton.setBackgroundColor(Color.WHITE);
        flashButton.setVisibility(View.GONE);
        
        if (GESTURES_ENABLED) {
            touchTimes = new LinkedList<Long>();
            
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
                    
                    if (touchTimes.size() == HIT_COUNT_TARGET)
                        touchTimes.removeFirst();
                    touchTimes.addLast(System.currentTimeMillis());
                    
                    if (touchTimes.getLast() - touchTimes.getFirst() <= GESTURE_TIMEOUT) {
                        if (downHit == HIT_COUNT_TARGET - 1) {
                            setEpdA2Delay(300);
                            downHit = 0;
                        } else if (upHit == HIT_COUNT_TARGET - 1) {
                            setEpdNormalDelay(300);
                            upHit = 0;
                        }
                    }
                    return false;
                }
            };
            dummyView.setOnTouchListener(touch);
            ignoreNext = true;
        } else
            ignoreNext = false;
        
        LayoutInflater l = LayoutInflater.from(this);
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
        
        wm.addView(dummyView, passThroughParams);
        wm.addView(overlay, activeParams);
        wm.addView(flashButton, passThroughParams);
        wm.addView(shadeOverlay, passThroughParams);
        
        viewHandler = new Handler()
        {
            public void handleMessage(Message msg) {
                setEpdNormalDelay(300);
            }
        };
        
        downHit = upHit = 0;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && !ignoreNext && !a2Active) {
            try {
                /*
                 * Some delay to avoid setting A2 before Activity ends, which
                 * would have no effect
                 */
                Thread.sleep(600L);
            } catch (InterruptedException ex) {
            }
            setEpdA2Delay(300);
        } else
            setEpdNormalDelay(0);
        ignoreNext = false;
        
        return Service.START_STICKY;
    }
    
    private void setEpdNormal() {
        N2EpdController.exitA2Mode();
        N2EpdController.setGL16Mode(1);
    }
    
    private void setEpdA2() {
        /*
         * Set A2 mode after some delay to force an entire redraw in 1-bit
         * (avoid initial ghosting)
         */
        N2EpdController.enterA2Mode();
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
        overlay.setVisibility(View.GONE);
        
        if (threadA2 != null)
            threadA2.setRunning(false);
        
        CountDownTimer timer = new CountDownTimer(ms, ms)
        {
            @Override
            public void onTick(long arg0) {
            }
            
            @Override
            public void onFinish() {
                setEpdNormal();
                a2Active = false;
                flashButton.setVisibility(View.GONE);
            }
        }.start();
    }
    
    private void setEpdA2Delay(int ms) {
        blankScreen(flashButton);
        shadeOverlay.setVisibility(View.VISIBLE);
        shadeOverlay.postInvalidate();
        startOverlayTimeout();
        
        threadA2 = new A2Monitor(reader, this);
        threadA2.setRunning(true);
        
        CountDownTimer timer = new CountDownTimer(ms, ms)
        {
            @Override
            public void onTick(long arg0) {
            }
            
            @Override
            public void onFinish() {
                setEpdA2();
                threadA2.start();
                flashButton.setVisibility(View.GONE);
            }
        }.start();
    }
    
    private void startOverlayTimeout() {
        if (overlayTimer != null)
            overlayTimer.cancel();
        overlay.setVisibility(View.VISIBLE);
        
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
    
    class A2Monitor extends Thread
    {
        private BufferedReader reader;
        private boolean _run;
        
        public A2Monitor(BufferedReader reader, Context c) {
            this.reader = reader;
        }
        
        public void setRunning(boolean run) {
            _run = run;
        }
        
        @Override
        public void run() {
            try {
                String line;
                
                a2Active = true;
                
                while (reader.ready())
                    reader.readLine();
                
                while (_run && (line = reader.readLine()) != null) {
                    if (line.contains("600,800 = A2"))
                        a2Active = true;
                    if (line.contains("epd_reset_region") || line.contains("600,800 = GU")) {
                        a2Active = false;
                        viewHandler.sendEmptyMessage(0);
                        break;
                    }
                    
                    Thread.sleep(500L);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        removeView(dummyView);
        removeView(overlay);
        removeView(shadeOverlay);
        removeView(flashButton);
    }
    
    private void removeView(View v) {
        if (v != null)
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
    }
}
