package com.eink.norefresh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class A2Activity extends Activity
{
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent svc = new Intent(this, A2Service.class);
        startService(svc);
        finish();
    }
}
