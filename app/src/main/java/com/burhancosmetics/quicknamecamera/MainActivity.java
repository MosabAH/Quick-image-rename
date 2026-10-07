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

import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends ComponentActivity {

    private static final int CAMERA_PERMISSION = 100;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;

    private Uri pendingUri;
    private EditText nameBox;

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
                        "يجب السماح للتطبيق باستخدام الكاميرا",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    // =====================================================
    // شاشة الكاميرا
    // =====================================================

    private void showCamera() {

        hideKeyboard();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        previewView = new PreviewView(this);

        previewView.setScaleType(
                PreviewView.ScaleType.FILL_CENTER
        );

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

        // لا نسمح بالتصوير قبل أن تصبح الكاميرا جاهزة
        captureButton.setEnabled(false);

        startCamera(() -> captureButton.setEnabled(true));

        captureButton.setOnClickListener(v -> {

            captureButton.setEnabled(false);

            takePicture(captureButton);
        });
    }

    // =====================================================
    // تشغيل CameraX
    // =====================================================

    private void startCamera(Runnable cameraReady) {

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
                        this,
                        selector,
                        preview,
                        imageCapture
                );

                cameraReady.run();

            } catch (Exception e) {

                Toast.makeText(
                        MainActivity.this,
                        "تعذر تشغيل الكاميرا: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }

        }, ContextCompat.getMainExecutor(this));
    }

    // =====================================================
    // التقاط الصورة
    // =====================================================

    private void takePicture(Button captureButton) {

        if (imageCapture == null) {

            captureButton.setEnabled(true);

            Toast.makeText(
                    this,
                    "الكاميرا غير جاهزة",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        try {

            String tempName =
                    "temp_" + System.currentTimeMillis() + ".jpg";

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    tempName
            );

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES
                                + "/Quick Name Camera"
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

                                Toast.makeText(
                                        MainActivity.this,
                                        "لم يتم إنشاء الصورة",
                                        Toast.LENGTH_LONG
                                ).show();

                                captureButton.setEnabled(true);

                                return;
                            }

                            // إيقاف الكاميرا مؤقتًا
                            if (cameraProvider != null) {
                                cameraProvider.unbindAll();
                            }

                            // مباشرة إلى شاشة الاسم
                            showNameScreen();
                        }

                        @Override
                        public void onError(
                                @NonNull ImageCaptureException exception) {

                            Toast.makeText(
                                    MainActivity.this,
                                    "فشل التصوير: "
                                            + exception.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();

                            captureButton.setEnabled(true);
                        }
                    }
            );

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "حدث خطأ أثناء التصوير: "
                            + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();

            captureButton.setEnabled(true);
        }
    }

    // =====================================================
    // شاشة معاينة + تسمية الصورة
    // =====================================================

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

            image.setImageURI(null);
            image.setImageURI(pendingUri);

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "تعذر عرض الصورة",
                    Toast.LENGTH_SHORT
            ).show();
        }

        root.addView(
                image,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        // خانة الاسم
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

        // الأزرار
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

        retake.setTextSize(18);

        Button save =
                new Button(this);

        save.setText(
                "حفظ"
        );

        save.setTextSize(20);

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

        // =================================================
        // إعادة التصوير
        // =================================================

        retake.setOnClickListener(v -> {

            hideKeyboard();

            deletePendingPhoto();

            showCamera();
        });

        // =================================================
        // حفظ
        // =================================================

        save.setOnClickListener(v -> savePhoto());

        // فتح الكيبورد تلقائيًا
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

        }, 300);
    }

    // =====================================================
    // حفظ الصورة بالاسم
    // =====================================================

    private void savePhoto() {

        if (pendingUri == null) {

            Toast.makeText(
                    this,
                    "لا توجد صورة للحفظ",
                    Toast.LENGTH_LONG
            ).show();

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

            nameBox.requestFocus();

            return;
        }

        name = cleanName(name);

        if (name.isEmpty()) {

            nameBox.setError(
                    "الاسم غير صالح"
            );

            return;
        }

        if (!name.toLowerCase(Locale.ROOT)
                .endsWith(".jpg")) {

            name += ".jpg";
        }

        name = getUniqueName(name);

        try {

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    name
            );

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
            );

            int updated =
                    getContentResolver().update(
                            pendingUri,
                            values,
                            null,
                            null
                    );

            if (updated <= 0) {
                throw new Exception(
                        "تعذر تغيير اسم الملف"
                );
            }

            pendingUri = null;

            hideKeyboard();

            Toast.makeText(
                    this,
                    "تم حفظ الصورة باسم " + name,
                    Toast.LENGTH_SHORT
            ).show();

            // مباشرة إلى الكاميرا للصورة التالية
            showCamera();

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "تعذر حفظ الصورة: "
                            + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // =====================================================
    // تنظيف الاسم
    // =====================================================

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
                .trim();
    }

    // =====================================================
    // منع تكرار الأسماء
    // =====================================================

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
                    base
                            + String.format(
                            Locale.ROOT,
                            "_%02d",
                            number
                    )
                            + extension;

            number++;
        }

        return candidate;
    }

    // =====================================================
    // فحص وجود اسم مسبق
    // =====================================================

    private boolean imageExists(
            String name) {

        Cursor cursor = null;

        try {

            String selection =
                    MediaStore.Images.Media.DISPLAY_NAME
                            + "=?";

            String[] args =
                    new String[]{name};

            cursor =
                    getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            new String[]{
                                    MediaStore.Images.Media._ID
                            },
                            selection,
                            args,
                            null
                    );

            return cursor != null
                    && cursor.moveToFirst();

        } catch (Exception e) {

            return false;

        } finally {

            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // =====================================================
    // حذف الصورة عند اختيار إعادة التصوير
    // =====================================================

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

    // =====================================================
    // إخفاء الكيبورد
    // =====================================================

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

    // =====================================================
    // تنظيف CameraX عند إغلاق التطبيق
    // =====================================================

    @Override
    protected void onDestroy() {

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        super.onDestroy();
    }
}