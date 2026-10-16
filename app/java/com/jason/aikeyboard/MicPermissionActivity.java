package com.jason.aikeyboard;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/** v7: a keyboard can't show the runtime permission dialog itself, so this transparent activity asks for the microphone. */
public final class MicPermissionActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { finish(); return; }
        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
    }
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, ok ? "Microphone allowed. Tap 🎤 on the keyboard to talk." : "Voice typing needs the microphone. You can allow it in Settings > Apps > LocalKey AI > Permissions.", Toast.LENGTH_LONG).show();
        finish();
    }
}
