package com.example.gamelauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void key(String k, boolean pad) {
        web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{key:'" + k + "'}));"
            + (pad ? "window.__pad&&window.__pad();" : ""), null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k = null; boolean pad = true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: k = "Enter"; break;
            case KeyEvent.KEYCODE_BUTTON_B: k = "Escape"; break;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START: k = "s"; break;
            case KeyEvent.KEYCODE_BUTTON_L1: k = "q"; break;
            case KeyEvent.KEYCODE_BUTTON_R1: k = "e"; break;
            case KeyEvent.KEYCODE_BACK: k = "Escape"; pad = false; break;
            case KeyEvent.KEYCODE_MENU: k = "s"; pad = false; break;
        }
        if (k == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) key(k, pad);
        return true;
    }

    private class Bridge {
        @JavascriptInterface
        public String getApps() {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            List<String[]> apps = new ArrayList<>();
            for (ResolveInfo r : list) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName())) continue;
                apps.add(new String[]{String.valueOf(r.loadLabel(pm)), p});
            }
            Collections.sort(apps, (a, c) -> a[0].compareToIgnoreCase(c[0]));
            JSONArray out = new JSONArray();
            try {
                for (String[] a : apps) out.put(new JSONObject().put("n", a[0]).put("p", a[1]));
            } catch (Exception ignored) {}
            return out.toString();
        }

        @JavascriptInterface
        public void launch(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        }
    }
}
