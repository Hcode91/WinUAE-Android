package com.buddyapps.winuae;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PICK_ROM = 1, PICK_DISK = 2;
    private static native void nativeSurface(Surface surface);
    private static native boolean nativeStart(String home, String rom, String disk);
    private static native void nativeStop();
    private static native void nativeMouse(int dx, int dy, boolean down);
    private static native void nativeKey(int code, boolean down);
    private static native void nativeJoystick(int control, boolean down);
    private static native void nativeWarp(boolean down);
    private static native boolean nativeState(String path, boolean load);
    private SurfaceView screen;
    private TextView status;
    private Button fullscreenButton;
    private String romPath = "", diskPath = "";
    private boolean started;
    private boolean fastForwardHeld;
    private boolean fullscreen;
    private float lastX, lastY;
    private boolean havePointer;

    private boolean coreLoaded;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff10151b);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(0xffe5edf5);
        status.setText("Choose an Amiga Kickstart ROM and disk image");
        status.setPadding(10, 4, 10, 4);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Kickstart ROM", v -> pick(PICK_ROM)));
        bar.addView(button("Disk image", v -> pick(PICK_DISK)));
        bar.addView(button("Start", v -> startEmulator()));
        bar.addView(button("Stop", v -> stopEmulator()));
        fullscreenButton = button("Full screen", v -> setFullscreen(!fullscreen));
        fullscreenButton.setContentDescription("Toggle full screen");
        bar.addView(fullscreenButton);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        screen = new SurfaceView(this);
        screen.getHolder().addCallback(this);
        screen.setFocusable(true);
        screen.setOnTouchListener(this::touchScreen);
        root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        keys.addView(keyButton("←", 80, 0)); keys.addView(keyButton("↓", 81, 1));
        keys.addView(keyButton("↑", 82, 2)); keys.addView(keyButton("→", 79, 3));
        keys.addView(keyButton("Fire", -1, 4));
        Button fastForward = button("Fast", v -> { });
        fastForward.setContentDescription("Hold for fast forward");
        fastForward.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                setFastForward(true);
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                setFastForward(false);
                return true;
            }
            return true;
        });
        keys.addView(fastForward);
        root.addView(keys, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout states = new LinearLayout(this);
        states.setGravity(Gravity.CENTER);
        states.addView(button("Save state", v -> saveState()));
        states.addView(button("Load state", v -> loadState()));
        root.addView(states, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        coreLoaded = loadCore();
        File savedRom = new File(getFilesDir(), "kick.rom");
        File savedDisk = new File(getFilesDir(), "disk.adf");
        if (savedRom.isFile()) romPath = savedRom.getAbsolutePath();
        if (savedDisk.isFile()) diskPath = savedDisk.getAbsolutePath();
        if (coreLoaded && screen.getHolder().getSurface().isValid()) nativeSurface(screen.getHolder().getSurface());
        if (romPath.isEmpty()) updateStatus();
    }

    private boolean loadCore() {
        try {
            File dir = getDir("native", MODE_PRIVATE);
            int version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            File lib = new File(dir, "libwinuae-" + version + ".so");
            if (!lib.isFile() || lib.length() < 1024 * 1024) {
                File temporary = new File(dir, lib.getName() + ".tmp");
                temporary.delete();
                try (InputStream in = getAssets().open("libwinuae.so");
                     FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (!temporary.renameTo(lib)) throw new java.io.IOException("Could not save native core");
            }
            lib.setReadable(true, false);
            lib.setWritable(false, false);
            System.load(lib.getAbsolutePath());
            return true;
        } catch (Exception | UnsatisfiedLinkError e) {
            status.setText("WinUAE ARM64 core missing: " + (e.getMessage() == null ? "load failed" : e.getMessage()));
            return false;
        }
    }

    private Button button(String title, View.OnClickListener action) {
        Button b = new Button(this); b.setText(title); b.setOnClickListener(action); return b;
    }
    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(enabled ? flags : View.SYSTEM_UI_FLAG_VISIBLE);
        fullscreenButton.setText(enabled ? "Exit full" : "Full screen");
    }
    private void setFastForward(boolean enabled) {
        if (!coreLoaded || !started || fastForwardHeld == enabled) return;
        fastForwardHeld = enabled;
        nativeWarp(enabled);
    }
    private File stateFile() { return new File(getFilesDir(), "quick.uss"); }
    private void saveState() {
        if (!coreLoaded || !started) {
            Toast.makeText(this, "Start the emulator before saving", Toast.LENGTH_SHORT).show();
        } else if (nativeState(stateFile().getAbsolutePath(), false)) {
            Toast.makeText(this, "Saving state…", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not queue save state", Toast.LENGTH_LONG).show();
        }
    }
    private void loadState() {
        if (!coreLoaded || !started) {
            Toast.makeText(this, "Start the emulator before loading", Toast.LENGTH_SHORT).show();
        } else if (!stateFile().isFile()) {
            Toast.makeText(this, "No saved state yet", Toast.LENGTH_SHORT).show();
        } else if (nativeState(stateFile().getAbsolutePath(), true)) {
            Toast.makeText(this, "Loading state…", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not queue load state", Toast.LENGTH_LONG).show();
        }
    }
    private Button keyButton(String title, int code, int control) {
        Button b = button(title, v -> { });
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (coreLoaded && started) {
                    if (code >= 0) nativeKey(code, true);
                    else { nativeMouse(0, 0, true); nativeKey(228, true); }
                    nativeJoystick(control, true);
                }
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (coreLoaded && started) {
                    if (code >= 0) nativeKey(code, false);
                    else { nativeMouse(0, 0, false); nativeKey(228, false); }
                    nativeJoystick(control, false);
                }
                return true;
            }
            return true;
        });
        return b;
    }
    private void pick(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        startActivityForResult(i, request);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        String name = request == PICK_ROM ? "kick.rom" : "disk.adf";
        try {
            File target = new File(getFilesDir(), name);
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            }
            if (request == PICK_ROM) romPath = target.getAbsolutePath(); else diskPath = target.getAbsolutePath();
            updateStatus();
        } catch (Exception e) { Toast.makeText(this, "Could not copy selected file", Toast.LENGTH_LONG).show(); }
    }
    private void updateStatus() {
        status.setText((coreLoaded ? "ROM: " + (romPath.isEmpty() ? "not selected" : "ready") + "   Disk: " + (diskPath.isEmpty() ? "none" : "ready") : "WinUAE ARM64 core missing"));
    }
    private void startEmulator() {
        if (started) return;
        if (!coreLoaded) { Toast.makeText(this, "WinUAE core is not included in this APK", Toast.LENGTH_LONG).show(); return; }
        if (romPath.isEmpty()) { pick(PICK_ROM); return; }
        if (!nativeStart(getFilesDir().getAbsolutePath(), romPath, diskPath)) {
            Toast.makeText(this, "Native WinUAE core is unavailable", Toast.LENGTH_LONG).show(); return;
        }
        started = true;
        status.setText("WinUAE running • touch the display to move the mouse");
    }
    private void stopEmulator() {
        if (started) {
            setFastForward(false);
            nativeStop(); started = false; updateStatus();
        }
    }
    private boolean touchScreen(View view, MotionEvent event) {
        if (!coreLoaded || !started) return true;
        float x = event.getX(), y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN) { lastX = x; lastY = y; havePointer = true; nativeMouse(0, 0, true); return true; }
        if (event.getAction() == MotionEvent.ACTION_MOVE && havePointer) {
            int dx = Math.round(x - lastX), dy = Math.round(y - lastY);
            nativeMouse(dx, dy, true); lastX = x; lastY = y; return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            havePointer = false; nativeMouse(0, 0, false); return true;
        }
        return true;
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { if (coreLoaded) nativeSurface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { if (coreLoaded) nativeSurface(holder.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { if (coreLoaded) nativeSurface(null); }
    @Override protected void onDestroy() { stopEmulator(); if (coreLoaded) nativeSurface(null); super.onDestroy(); }
}
