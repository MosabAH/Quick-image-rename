package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 10;
    private Uri pendingUri;
    private File pendingFile;
    private ImageView preview;
    private EditText nameBox;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        showCameraScreen();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        else takePhoto();
    }

    private void showCameraScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(30,30,30,30);

        Button shot = new Button(this);
        shot.setText("📷  تصوير صورة");
        shot.setTextSize(24);
        layout.addView(shot, new LinearLayout.LayoutParams(-1, 150));
        shot.setOnClickListener(v -> takePhoto());
        setContentView(layout);
    }

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "images");
            dir.mkdirs();
            pendingFile = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, pendingFile.getName());
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Quick Name Camera");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            pendingUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (pendingUri == null) throw new Exception("Could not create camera file");

            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, pendingUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, 20);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الكاميرا", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != 20) return;
        if (result != RESULT_OK || pendingUri == null) {
            showCameraScreen();
            return;
        }
        showNameScreen();
    }

    private void showNameScreen() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(25,25,25,25);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        try {
            preview.setImageURI(pendingUri);
        } catch (Exception ignored) {}
        box.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        nameBox = new EditText(this);
        nameBox.setText("");
        nameBox.setHint("اكتب اسم الصورة");
        nameBox.setTextSize(25);
        nameBox.setSingleLine(true);
        box.addView(nameBox, new LinearLayout.LayoutParams(-1, 100));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button retake = new Button(this);
        retake.setText("إعادة التصوير");
        retake.setTextSize(18);
        Button save = new Button(this);
        save.setText("حفظ");
        save.setTextSize(20);

        buttons.addView(retake, new LinearLayout.LayoutParams(0,100,1));
        buttons.addView(save, new LinearLayout.LayoutParams(0,100,1));
        box.addView(buttons);

        retake.setOnClickListener(v -> { if (pendingUri != null) getContentResolver().delete(pendingUri, null, null); takePhoto(); });
        save.setOnClickListener(v -> savePhoto());

        setContentView(box);
        nameBox.requestFocus();
        nameBox.postDelayed(() -> ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(nameBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT), 200);
    }

    private void savePhoto() {
        String name = nameBox.getText().toString().trim();
        if (name.isEmpty()) {
            nameBox.setError("اكتب اسم الصورة");
            return;
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.toLowerCase().endsWith(".jpg")) name += ".jpg";

        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Quick Name Camera");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri out = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (out == null) throw new Exception("MediaStore insert failed");

            try (OutputStream os = getContentResolver().openOutputStream(out)) {
                try (java.io.InputStream in = getContentResolver().openInputStream(pendingUri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(out, done, null, null);
                ContentValues rename = new ContentValues();
                rename.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                getContentResolver().update(pendingUri, rename, null, null);
                getContentResolver().delete(out, null, null);
            } else {
                ContentValues rename = new ContentValues();
                rename.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                getContentResolver().update(pendingUri, rename, null, null);
                getContentResolver().delete(out, null, null);
            }
            getContentResolver().delete(pendingUri, null, null);
            Toast.makeText(this, "تم حفظ الصورة باسم " + name, Toast.LENGTH_SHORT).show();
            showCameraScreen();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر حفظ الصورة", Toast.LENGTH_LONG).show();
        }
    }
}
