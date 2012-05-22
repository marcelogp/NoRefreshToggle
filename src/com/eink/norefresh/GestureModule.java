package com.eink.norefresh;

import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import java.util.LinkedList;

public abstract class GestureModule
{
    private float lastY, lastX;
    private int downHit, upHit;
    private LinkedList<Long> touchTimes;
    private static int GESTURE_TIMEOUT = 3000;
    private final int hits;

    public GestureModule(View v, int hitCount) {
        touchTimes = new LinkedList<Long>();
        downHit = upHit = 0;
        hits = hitCount;

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

                if (touchTimes.size() == hits)
                    touchTimes.removeFirst();
                touchTimes.addLast(System.currentTimeMillis());

                if (touchTimes.getLast() - touchTimes.getFirst() <= GESTURE_TIMEOUT) {
                    if (downHit == hits - 1) {
                        downGestureCallback();
                        downHit = 0;
                    } else if (upHit == hits - 1) {
                        upGestureCallback();
                        upHit = 0;
                    }
                }
                return false;
            }
        };
        v.setOnTouchListener(touch);
    }

    public abstract void downGestureCallback();

    public abstract void upGestureCallback();
}
