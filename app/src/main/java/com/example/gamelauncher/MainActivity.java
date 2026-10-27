package com.example.gamelauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private String pickTag = "";
    private int pickMax = 1024;
    private long downAt = 0;
    private boolean okLong = false;
    private int stickDir = 0;
    private long stickNext = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
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

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void key(String k, boolean pad) {
        web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{key:'" + k + "'}));"
            + (pad ? "window.__pad&&window.__pad();" : ""), null);
    }

    // REMOTE FIX: every navigation key (D-pad, OK, back, menu, gamepad buttons) is sent to the
    // page explicitly instead of hoping the WebView forwards them.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int rc = e.getRepeatCount();
        String k = null; boolean pad = false, repeat = false;
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "ArrowLeft"; repeat = true; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "ArrowRight"; repeat = true; break;
            case KeyEvent.KEYCODE_DPAD_UP: k = "ArrowUp"; repeat = true; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "ArrowDown"; repeat = true; break;
            case KeyEvent.KEYCODE_BUTTON_B: k = "Escape"; pad = true; break;
            case KeyEvent.KEYCODE_BACK: k = "Escape"; break;
            case KeyEvent.KEYCODE_BUTTON_START: k = "s"; pad = true; break;
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SETTINGS: k = "s"; break;
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_BUTTON_X: k = "x"; pad = true; break;
            case KeyEvent.KEYCODE_BUTTON_L1: k = "q"; pad = true; break;
            case KeyEvent.KEYCODE_BUTTON_R1: k = "e"; pad = true; break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                boolean p = c == KeyEvent.KEYCODE_BUTTON_A;
                // tap OK = open, hold OK = edit app
                if (down) {
                    if (rc == 0) { downAt = e.getEventTime(); okLong = false; }
                    else if (!okLong && e.getEventTime() - downAt >= 550) { okLong = true; key("x", p); }
                } else {
                    if (!okLong) key(e.getEventTime() - downAt >= 550 ? "x" : "Enter", p);
                    okLong = false;
                }
                return true;
            }
            default: return super.dispatchKeyEvent(e);
        }
        if (down && (rc == 0 || repeat)) key(k, pad);
        return true;
    }

    // Left analog stick acts like the D-pad
    @Override
    public boolean onGenericMotionEvent(MotionEvent m) {
        if ((m.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && m.getAction() == MotionEvent.ACTION_MOVE) {
            float x = m.getAxisValue(MotionEvent.AXIS_X), y = m.getAxisValue(MotionEvent.AXIS_Y);
            int dir = x < -.6f ? 1 : x > .6f ? 2 : y < -.6f ? 3 : y > .6f ? 4 : 0;
            long now = SystemClock.uptimeMillis();
            if (dir != 0 && (dir != stickDir || now >= stickNext)) {
                key(new String[]{"", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"}[dir], true);
                stickNext = now + (dir == stickDir ? 120 : 400);
            }
            stickDir = dir;
            return true;
        }
        return super.onGenericMotionEvent(m);
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent d) {
        super.onActivityResult(rq, rs, d);
        if (rq != 7) return;
        final String tag = pickTag;
        if (rs != RESULT_OK || d == null || d.getData() == null) { js("window.__picked&&__picked('" + tag + "','')"); return; }
        final Uri u = d.getData();
        new Thread(() -> {
            String name = "";
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                InputStream a = getContentResolver().openInputStream(u);
                BitmapFactory.decodeStream(a, null, o); a.close();
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / s > pickMax) s *= 2;
                o = new BitmapFactory.Options(); o.inSampleSize = s;
                a = getContentResolver().openInputStream(u);
                Bitmap bm = BitmapFactory.decodeStream(a, null, o); a.close();
                name = "img_" + System.nanoTime() + ".jpg";
                FileOutputStream f = new FileOutputStream(new File(getFilesDir(), name));
                bm.compress(Bitmap.CompressFormat.JPEG, 88, f); f.close();
            } catch (Exception ex) { name = ""; }
            js("window.__picked&&__picked('" + tag + "','" + name + "')");
        }).start();
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

        @JavascriptInterface
        public String getIcon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 128, 128); d.draw(new Canvas(b));
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
                return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void pickImage(String tag, int max) {
            pickTag = tag; pickMax = max;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Choose image"), 7);
            });
        }

        @JavascriptInterface
        public String getImage(String name) {
            try {
                if (name.contains("/")) return "";
                File f = new File(getFilesDir(), name);
                byte[] b = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                new DataInputStream(in).readFully(b); in.close();
                return "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void prompt(String title, String cur, String tag) {
            runOnUiThread(() -> {
                final EditText et = new EditText(MainActivity.this);
                et.setSingleLine(true); et.setText(cur); et.selectAll();
                new AlertDialog.Builder(MainActivity.this).setTitle(title).setView(et)
                    .setPositiveButton("OK", (dlg, w) -> js("window.__prompted&&__prompted("
                        + JSONObject.quote(tag) + "," + JSONObject.quote(et.getText().toString()) + ")"))
                    .setNegativeButton("Cancel", null).show();
            });
        }

        @JavascriptInterface
        public void defaultLauncher() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)));
        }
    }
}
