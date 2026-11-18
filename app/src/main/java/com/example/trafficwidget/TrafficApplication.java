package com.example.trafficwidget;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

public class TrafficApplication extends Application {

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                Log.d("TrafficWidget", "全局收到解锁广播，准备刷新小部件");
//                WidgetLogger.log(context, "全局收到解锁广播");
                // 触发刷新逻辑
                RefreshController.refreshIfAllowed(context, "全局解锁广播触发");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // 动态注册解锁广播
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);

        // 适配 Android 13 (API 33) 及以上系统的注册方式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(unlockReceiver, filter);
        }
    }
}