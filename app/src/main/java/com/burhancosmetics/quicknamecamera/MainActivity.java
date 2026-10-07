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
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Uri pendingUri;
    private EditText nameBox;
    private ImageView preview;

    // تشغيل الكاميرا بالطريقة الحديثة
    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.TakePicture(),
                    success -> {
                        if (success && pendingUri != null) {
                            // الصورة تم التقاطها بنجاح
                            showNameScreen();
                        } else {
                            // المستخدم ألغى التصوير
                            deletePendingPhoto();
                            showCameraScreen();
                        }
                    }
            );

    // طلب صلاحية الكاميرا
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            takePhoto();
                        } else {
                            Toast.makeText(
                                    this,
                                    "لازم تسمح للتطبيق باستخدام الكاميرا",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showCameraScreen();

        // فقط عند فتح التطبيق لأول مرة
        if (savedInstanceState == null) {
            checkCameraAndStart();
        }
    }

    private void checkCameraAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {

            takePhoto();

        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void takePhoto() {
        try {
            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "temp_" + System.currentTimeMillis() + ".jpg"
            );

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Quick Name Camera"
                );

                values.put(
                        MediaStore.Images.Media.IS_PENDING,
                        1
                );
            }

            pendingUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );

            if (pendingUri == null) {
                throw new Exception("Could not create image");
            }

            // فتح الكاميرا
            takePictureLauncher.launch(pendingUri);

        } catch (Exception e) {

            pendingUri = null;

            Toast.makeText(
                    this,
                    "تعذر فتح الكاميرا",
                    Toast.LENGTH_LONG
            ).show();

            showCameraScreen();
        }
    }

    private void showNameScreen() {

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        // معاينة الصورة
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);

        try {
            preview.setImageURI(pendingUri);
        } catch (Exception ignored) {
        }

        layout.addView(
                preview,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        // خانة الاسم
        nameBox = new EditText(this);
        nameBox.setHint("اكتب اسم الصورة");
        nameBox.setTextSize(24);
        nameBox.setSingleLine(true);

        layout.addView(
                nameBox,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        120
                )
        );

        // الأزرار
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button retake = new Button(this);
        retake.setText("إعادة التصوير");
        retake.setTextSize(18);

        Button save = new Button(this);
        save.setText("حفظ");
        save.setTextSize(20);

        buttons.addView(
                retake,
                new LinearLayout.LayoutParams(
                        0,
                        120,
                        1
                )
        );

        buttons.addView(
                save,
                new LinearLayout.LayoutParams(
                        0,
                        120,
                        1
                )
        );

        layout.addView(buttons);

        // إعادة التصوير
        retake.setOnClickListener(v -> {

            hideKeyboard();

            deletePendingPhoto();

            // فتح الكاميرا مباشرة
            takePhoto();
        });

        // حفظ
        save.setOnClickListener(v -> savePhoto());

        setContentView(layout);

        // فتح الكيبورد تلقائيًا
        nameBox.requestFocus();

        nameBox.postDelayed(() -> {

            InputMethodManager imm =
                    (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);

            if (imm != null) {
                imm.showSoftInput(
                        nameBox,
                        InputMethodManager.SHOW_IMPLICIT
                );
            }

        }, 300);
    }

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

            nameBox.setError("اكتب اسم الصورة");
            nameBox.requestFocus();

            return;
        }

        // إزالة الرموز الممنوعة من اسم الملف
        name = name
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_");

        if (!name.toLowerCase().endsWith(".jpg")) {
            name += ".jpg";
        }

        // منع تكرار الاسم
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

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q) {

                values.put(
                        MediaStore.Images.Media.IS_PENDING,
                        0
                );
            }

            int updated =
                    getContentResolver().update(
                            pendingUri,
                            values,
                            null,
                            null
                    );

            if (updated <= 0) {
                throw new Exception(
                        "Could not update image"
                );
            }

            pendingUri = null;

            hideKeyboard();

            Toast.makeText(
                    this,
                    "تم حفظ الصورة باسم " + name,
                    Toast.LENGTH_SHORT
            ).show();

            // شاشة مؤقتة
            showCameraScreen();

            // فتح الكاميرا للصورة التالية
            new android.os.Handler(
                    getMainLooper()
            ).postDelayed(
                    this::takePhoto,
                    300
            );

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "تعذر حفظ الصورة",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String getUniqueName(
            String originalName
    ) {

        String base = originalName;
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
                            "_%02d",
                            number
                    )
                            + extension;

            number++;
        }

        return candidate;
    }

    private boolean imageExists(
            String name
    ) {

        Cursor cursor = null;

        try {

            String[] projection = {
                    MediaStore.Images.Media._ID
            };

            String selection =
                    MediaStore.Images.Media.DISPLAY_NAME
                            + "=?";

            String[] args = {name};

            cursor =
                    getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
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

    private void hideKeyboard() {

        try {

            InputMethodManager imm =
                    (InputMethodManager)
                            getSystemService(
                                    Context.INPUT_METHOD_SERVICE
                            );

            if (imm != null
                    && getCurrentFocus() != null) {

                imm.hideSoftInputFromWindow(
                        getCurrentFocus()
                                .getWindowToken(),
                        0
                );
            }

        } catch (Exception ignored) {
        }
    }

    private void showCameraScreen() {

        LinearLayout layout =
                new LinearLayout(this);

        layout.setOrientation(
                LinearLayout.VERTICAL
        );

        layout.setGravity(
                Gravity.CENTER
        );

        layout.setPadding(
                30,
                30,
                30,
                30
        );

        Button shot =
                new Button(this);

        shot.setText("📷 تصوير صورة");
        shot.setTextSize(24);

        layout.addView(
                shot,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        150
                )
        );

        shot.setOnClickListener(v -> {

            if (checkSelfPermission(
                    Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED) {

                takePhoto();

            } else {

                cameraPermissionLauncher.launch(
                        Manifest.permission.CAMERA
                );
            }
        });

        setContentView(layout);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // لا نحذف الصورة هنا حتى لا تضيع
        // عند دوران الشاشة أو إعادة إنشاء Activity
    }
}