package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends ComponentActivity {

    private static final int CAMERA_PERMISSION = 100;

    private static final String SERVER_URL =
            "http://192.168.1.72:5000/barcode";

    private static final String API_KEY =
            "123456789test";

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;

    private Uri pendingUri;

    private EditText barcodeBox;
    private EditText locationBox;

    private Button saveButton;
    private TextView statusText;

    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString("pending_uri");
            if (uri != null) {
                pendingUri = Uri.parse(uri);
            }
        }

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION
            );

        } else {
            showCamera();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (pendingUri != null) {
            outState.putString("pending_uri", pendingUri.toString());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showCamera();
            } else {
                Toast.makeText(
                        this,
                        "Camera permission required",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void showCamera() {

        saving = false;
        hideKeyboard();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        root.addView(
                previewView,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        Button captureButton = new Button(this);
        captureButton.setText("تصوير");
        captureButton.setTextSize(24);

        root.addView(
                captureButton,
                new LinearLayout.LayoutParams(-1, 150)
        );

        setContentView(root);

        captureButton.setEnabled(false);

        startCamera(() -> captureButton.setEnabled(true));

        captureButton.setOnClickListener(v -> {
            captureButton.setEnabled(false);
            takePicture(captureButton);
        });
    }

    private void startCamera(Runnable ready) {

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {

            try {
                cameraProvider = future.get();

                Preview preview =
                        new Preview.Builder().build();

                imageCapture =
                        new ImageCapture.Builder()
                                .setCaptureMode(
                                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                )
                                .build();

                preview.setSurfaceProvider(
                        previewView.getSurfaceProvider()
                );

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                );

                ready.run();

            } catch (Exception e) {
                Toast.makeText(
                        this,
                        "Camera error: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }

        }, ContextCompat.getMainExecutor(this));
    }

    private void takePicture(Button button) {

        if (imageCapture == null) {
            button.setEnabled(true);
            return;
        }

        ContentValues values = new ContentValues();

        values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "temp_" + System.currentTimeMillis() + ".jpg"
        );

        values.put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/jpeg"
        );

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Quick Name Camera"
            );
        }

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(
                        getContentResolver(),
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                ).build();

        Executor executor =
                ContextCompat.getMainExecutor(this);

        imageCapture.takePicture(
                options,
                executor,
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults results) {

                        pendingUri = results.getSavedUri();

                        if (pendingUri == null) {
                            button.setEnabled(true);
                            Toast.makeText(
                                    MainActivity.this,
                                    "Photo not saved",
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }

                        if (cameraProvider != null) {
                            cameraProvider.unbindAll();
                        }

                        showNameScreen();
                    }

                    @Override
                    public void onError(
                            @NonNull ImageCaptureException exception) {

                        button.setEnabled(true);

                        Toast.makeText(
                                MainActivity.this,
                                "Capture failed: " + exception.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private void showNameScreen() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setImageURI(pendingUri);

        root.addView(
                image,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        barcodeBox = new EditText(this);
        barcodeBox.setHint("الباركود");
        barcodeBox.setSingleLine(true);
        barcodeBox.setTextSize(22);

        root.addView(barcodeBox);

        locationBox = new EditText(this);
        locationBox.setHint("موقع البضاعة");
        locationBox.setSingleLine(true);
        locationBox.setTextSize(22);

        root.addView(locationBox);

        statusText = new TextView(this);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button retakeButton = new Button(this);
        retakeButton.setText("إعادة التصوير");

        saveButton = new Button(this);
        saveButton.setText("حفظ");
        saveButton.setTextSize(20);

        buttons.addView(
                retakeButton,
                new LinearLayout.LayoutParams(0, 120, 1)
        );

        buttons.addView(
                saveButton,
                new LinearLayout.LayoutParams(0, 120, 1)
        );

        root.addView(buttons);
        setContentView(root);

        retakeButton.setOnClickListener(v -> {
            if (saving) return;

            hideKeyboard();
            deletePendingPhoto();
            showCamera();
        });

        saveButton.setOnClickListener(v -> lookupAndSave());

        barcodeBox.requestFocus();

        barcodeBox.postDelayed(() -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE
                    );

            if (imm != null) {
                imm.showSoftInput(
                        barcodeBox,
                        InputMethodManager.SHOW_IMPLICIT
                );
            }
        }, 300);
    }

    private void lookupAndSave() {

        if (saving || pendingUri == null) {
            return;
        }

        String barcode = barcodeBox.getText().toString().trim();
        String location = locationBox.getText().toString().trim();

        if (barcode.isEmpty()) {
            barcodeBox.setError("اكتب الباركود");
            barcodeBox.requestFocus();
            return;
        }

        if (location.isEmpty()) {
            locationBox.setError("اكتب موقع البضاعة");
            locationBox.requestFocus();
            return;
        }

        if (location.equals(".") || location.equals("..")) {
            locationBox.setError("موقع غير صالح");
            return;
        }

        saving = true;
        saveButton.setEnabled(false);
        statusText.setText("جاري البحث عن كود الصنف...");

        new Thread(() -> {

            String itemCode = null;
            String error = null;

            HttpURLConnection connection = null;

            try {
                String encodedBarcode =
                        URLEncoder.encode(barcode, "UTF-8");

                URL url = new URL(
                        SERVER_URL + "?code=" + encodedBarcode
                );

                connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setRequestProperty("X-API-Key", API_KEY);

                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);

                int responseCode = connection.getResponseCode();

                InputStream stream =
                        responseCode >= 200 && responseCode < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                if (stream == null) {
                    throw new Exception("Empty server response");
                }

                StringBuilder response = new StringBuilder();

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(stream, "UTF-8")
                             )) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                if (responseCode == 401) {
                    throw new Exception("مفتاح الاتصال غير صحيح");
                }

                if (responseCode != 200) {
                    throw new Exception("Server HTTP " + responseCode);
                }

                JSONObject json = new JSONObject(response.toString());

                boolean found = json.optBoolean("found", false);

                if (!found) {
                    error = "الباركود غير موجود في قاعدة البيانات";
                } else {
                    itemCode = json.optString("item_code", "").trim();

                    if (itemCode.isEmpty()) {
                        error = "كود الصنف فارغ";
                    }
                }

            } catch (Exception e) {
                error = "فشل الاتصال أو البحث: " + e.getMessage();

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final String finalItemCode = itemCode;
            final String finalError = error;

            runOnUiThread(() -> {

                if (finalError != null) {
                    saving = false;
                    saveButton.setEnabled(true);
                    statusText.setText(finalError);
                    return;
                }

                Toast.makeText(
        MainActivity.this,
        "كود الصنف من Oracle: " + finalItemCode,
        Toast.LENGTH_LONG
).show();

savePhoto(finalItemCode, location);
            });

        }).start();
    }

    private void savePhoto(String itemCode, String location) {

        if (pendingUri == null) {
            saving = false;
            saveButton.setEnabled(true);
            return;
        }

        String visibleName =
                cleanName(itemCode) + "," + cleanName(location);

        String filename = getUniqueName(visibleName);

        try {
            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    filename
            );

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
            );

            int updated = getContentResolver().update(
                    pendingUri,
                    values,
                    null,
                    null
            );

            if (updated <= 0) {
                throw new Exception("Rename failed");
            }

            pendingUri = null;
            saving = false;

            hideKeyboard();

            String shownName = filename;

            if (shownName.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
                shownName = shownName.substring(
                        0,
                        shownName.length() - 4
                );
            }

            Toast.makeText(
                    this,
                    "تم الحفظ: " + shownName,
                    Toast.LENGTH_SHORT
            ).show();

            showCamera();

        } catch (Exception e) {
            saving = false;
            saveButton.setEnabled(true);
            statusText.setText("تعذر حفظ الصورة: " + e.getMessage());
        }
    }

    private String cleanName(String name) {
        return name
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_")
                .replace(",", "_")
                .trim();
    }

    private String getUniqueName(String originalName) {

    String candidate = originalName;
    int number = 1;

    while (imageExists(candidate)) {
        candidate = originalName +
                String.format(Locale.ROOT, "_%02d", number);
        number++;
    }

    return candidate;
}

    private boolean imageExists(String name) {

        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    MediaStore.Images.Media.DISPLAY_NAME + "=?",
                    new String[]{name},
                    null
            );

            return cursor != null && cursor.moveToFirst();

        } catch (Exception e) {
            return false;

        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void deletePendingPhoto() {

        if (pendingUri == null) {
            return;
        }

        try {
            getContentResolver().delete(pendingUri, null, null);
        } catch (Exception ignored) {
        }

        pendingUri = null;
    }

    private void hideKeyboard() {

        try {
            View view = getCurrentFocus();

            if (view == null) {
                return;
            }

            InputMethodManager imm =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE
                    );

            if (imm != null) {
                imm.hideSoftInputFromWindow(
                        view.getWindowToken(),
                        0
                );
            }

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        super.onDestroy();
    }
}
