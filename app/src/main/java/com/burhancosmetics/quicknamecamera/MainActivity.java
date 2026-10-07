package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.app.Activity;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

public class MainActivity extends Activity {

    private static final int CAMERA_PERMISSION = 100;

    private PreviewView previewView;
    private ImageCapture imageCapture;

    private Uri pendingUri;

    private EditText nameBox;

    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
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
                        "يجب السماح باستخدام الكاميرا",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    // ==========================
    // شاشة الكاميرا
    // ==========================

    private void showCamera() {

        hideKeyboard();

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        previewView = new PreviewView(this);

        root.addView(
                previewView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        Button captureButton = new Button(this);

        captureButton.setText("📷 تصوير");
        captureButton.setTextSize(24);

        root.addView(
                captureButton,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        160
                )
        );

        setContentView(root);

        captureButton.setEnabled(false);

        startCamera(() -> {
            captureButton.setEnabled(true);
        });

        captureButton.setOnClickListener(v -> {

            captureButton.setEnabled(false);

            takePicture(captureButton);
        });
    }

    // ==========================
    // تشغيل CameraX
    // ==========================

    private void startCamera(Runnable ready) {

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {

            try {

                cameraProvider = future.get();

                Preview preview =
                        new Preview.Builder()
                                .build();

                imageCapture =
                        new ImageCapture.Builder()
                                .setCaptureMode(
                                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                )
                                .build();

                CameraSelector selector =
                        CameraSelector.DEFAULT_BACK_CAMERA;

                preview.setSurfaceProvider(
                        previewView.getSurfaceProvider()
                );

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                        (LifecycleOwner) this,
                        selector,
                        preview,
                        imageCapture
                );

                ready.run();

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "تعذر تشغيل الكاميرا",
                        Toast.LENGTH_LONG
                ).show();
            }

        }, ContextCompat.getMainExecutor(this));
    }

    // ==========================
    // التقاط الصورة
    // ==========================

    private void takePicture(Button button) {

        if (imageCapture == null) {

            button.setEnabled(true);
            return;
        }

        try {

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "temp_" + System.currentTimeMillis()
            );

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
            );

            if (Build.VERSION.SDK_INT >= 29) {

                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES +
                                "/Quick Name Camera"
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

                            pendingUri =
                                    results.getSavedUri();

                            if (pendingUri == null) {

                                Toast.makeText(
                                        MainActivity.this,
                                        "لم يتم إنشاء الصورة",
                                        Toast.LENGTH_LONG
                                ).show();

                                button.setEnabled(true);
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

                            Toast.makeText(
                                    MainActivity.this,
                                    "فشل التصوير: " +
                                            exception.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();

                            button.setEnabled(true);
                        }
                    }
            );

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "حدث خطأ أثناء التصوير",
                    Toast.LENGTH_LONG
            ).show();

            button.setEnabled(true);
        }
    }

    // ==========================
    // شاشة تسمية الصورة
    // ==========================

    private void showNameScreen() {

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                20,
                20,
                20,
                20
        );

        ImageView image =
                new ImageView(this);

        image.setAdjustViewBounds(true);

        image.setScaleType(
                ImageView.ScaleType.CENTER_INSIDE
        );

        try {
            image.setImageURI(pendingUri);
        } catch (Exception ignored) {
        }

        root.addView(
                image,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        nameBox =
                new EditText(this);

        nameBox.setHint(
                "اكتب اسم الصورة"
        );

        nameBox.setTextSize(24);

        nameBox.setSingleLine(true);

        root.addView(
                nameBox,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        130
                )
        );

        LinearLayout buttons =
                new LinearLayout(this);

        buttons.setOrientation(
                LinearLayout.HORIZONTAL
        );

        Button retake =
                new Button(this);

        retake.setText(
                "إعادة التصوير"
        );

        Button save =
                new Button(this);

        save.setText(
                "حفظ"
        );

        save.setTextSize(20);

        retake.setTextSize(18);

        buttons.addView(
                retake,
                new LinearLayout.LayoutParams(
                        0,
                        130,
                        1
                )
        );

        buttons.addView(
                save,
                new LinearLayout.LayoutParams(
                        0,
                        130,
                        1
                )
        );

        root.addView(buttons);

        setContentView(root);

        // إعادة التصوير
        retake.setOnClickListener(v -> {

            deletePendingPhoto();

            showCamera();
        });

        // حفظ
        save.setOnClickListener(v -> {

            savePhoto();
        });

        // فتح الكيبورد مباشرة
        nameBox.requestFocus();

        nameBox.postDelayed(() -> {

            InputMethodManager imm =
                    (InputMethodManager)
                            getSystemService(
                                    Context.INPUT_METHOD_SERVICE
                            );

            if (imm != null) {

                imm.showSoftInput(
                        nameBox,
                        InputMethodManager.SHOW_IMPLICIT
                );
            }

        }, 250);
    }

    // ==========================
    // حفظ الاسم
    // ==========================

    private void savePhoto() {

        if (pendingUri == null) {
            return;
        }

        String name =
                nameBox.getText()
                        .toString()
                        .trim();

        if (name.isEmpty()) {

            nameBox.setError(
                    "اكتب اسم الصورة"
            );

            return;
        }

        name = cleanName(name);

        if (!name.toLowerCase()
                .endsWith(".jpg")) {

            name += ".jpg";
        }

        name =
                getUniqueName(name);

        try {

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    name
            );

            int updated =
                    getContentResolver().update(
                            pendingUri,
                            values,
                            null,
                            null
                    );

            if (updated <= 0) {
                throw new Exception();
            }

            pendingUri = null;

            hideKeyboard();

            Toast.makeText(
                    this,
                    "تم الحفظ: " + name,
                    Toast.LENGTH_SHORT
            ).show();

            // الرجوع مباشرة للكاميرا
            showCamera();

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "تعذر حفظ الصورة",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // ==========================
    // تنظيف الاسم
    // ==========================

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
                .replace("|", "_");
    }

    // ==========================
    // اسم غير مكرر
    // ==========================

    private String getUniqueName(
            String originalName) {

        String base =
                originalName;

        String extension = "";

        int dot =
                originalName.lastIndexOf(".");

        if (dot > 0) {

            base =
                    originalName.substring(
                            0,
                            dot
                    );

            extension =
                    originalName.substring(dot);
        }

        String candidate =
                base + extension;

        int number = 1;

        while (imageExists(candidate)) {

            candidate =
                    base +
                            String.format(
                                    "_%02d",
                                    number
                            ) +
                            extension;

            number++;
        }

        return candidate;
    }

    private boolean imageExists(
            String name) {

        Cursor cursor = null;

        try {

            cursor =
                    getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            new String[]{
                                    MediaStore.Images.Media._ID
                            },
                            MediaStore.Images.Media.DISPLAY_NAME + "=?",
                            new String[]{name},
                            null
                    );

            return cursor != null &&
                    cursor.moveToFirst();

        } catch (Exception e) {

            return false;

        } finally {

            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ==========================
    // حذف الصورة عند إعادة التصوير
    // ==========================

    private void deletePendingPhoto() {

        if (pendingUri == null) {
            return;
        }

        try {

            getContentResolver().delete(
                    pendingUri,
                    null,
                    null
            );

        } catch (Exception ignored) {
        }

        pendingUri = null;
    }

    // ==========================
    // إخفاء الكيبورد
    // ==========================

    private void hideKeyboard() {

        try {

            View view =
                    getCurrentFocus();

            if (view == null) {
                return;
            }

            InputMethodManager imm =
                    (InputMethodManager)
                            getSystemService(
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
}