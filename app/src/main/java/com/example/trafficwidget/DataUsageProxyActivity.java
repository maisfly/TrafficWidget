package com.example.trafficwidget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible trampoline used by the widget click; handles OEM settings-screen fallbacks. */
public class DataUsageProxyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Intents.openDataUsage(this)) {
            Toast.makeText(this, "这台手机没有找到数据用量页面", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
