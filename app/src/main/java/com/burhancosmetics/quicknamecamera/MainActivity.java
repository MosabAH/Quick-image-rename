package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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
    private static final int REQ_TAKE_PHOTO = 20;

    private File pendingFile;
    private Uri pendingUri;

    private EditText nameBox;
    private ImageView preview;

 @Override
protected void onCreate(Bundle b) {
    super.onCreate(b);

    showCameraScreen();

    if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {

        requestPermissions(
                new String[]{Manifest.permission.CAMERA},
                REQ_CAMERA
        );

    } else if (b == null) {
        takePhoto();
    }
}
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            takePhoto();

        } else {
            Toast.makeText(
                    this,
                    "لازم تسمح للتطبيق باستخدام الكاميرا",
                    Toast.LENGTH_LONG
            ).show();
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

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES
                            + "/Quick Name Camera"
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

        Intent cameraIntent =
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        cameraIntent.putExtra(
                MediaStore.EXTRA_OUTPUT,
                pendingUri
        );

        cameraIntent.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivityForResult(
                cameraIntent,
                REQ_TAKE_PHOTO
        );

    } catch (Exception e) {

        Toast.makeText(
                this,
                "تعذر فتح الكاميرا: " + e.getMessage(),
                Toast.LENGTH_LONG
        ).show();
    }
}

  
@Override
protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data) {

    super.onActivityResult(
            requestCode,
            resultCode,
            data
    );

    if (requestCode != REQ_TAKE_PHOTO) {
        return;
    }

    // إذا الصورة موجودة فعلياً في MediaStore،
    // ننتقل للاسم حتى لو الكاميرا رجعت RESULT_CANCELED
    if (pendingUri != null) {

        try {
            android.database.Cursor cursor =
                    getContentResolver().query(
                            pendingUri,
                            new String[]{
                                    MediaStore.Images.Media.SIZE
                            },
                            null,
                            null,
                            null
                    );

            long size = 0;

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    size = cursor.getLong(0);
                }
                cursor.close();
            }

            if (size > 0) {
                showNameScreen();
                return;
            }

        } catch (Exception ignored) {
        }
    }

    // ما في صورة فعلية
    if (pendingUri != null) {
        getContentResolver().delete(
                pendingUri,
                null,
                null
        );
    }

    pendingUri = null;
    showCameraScreen();
}

    private void showNameScreen() {

        LinearLayout layout = new LinearLayout(this);

        layout.setOrientation(
                LinearLayout.VERTICAL
        );

        layout.setPadding(
                20,
                20,
                20,
                20
        );

        preview = new ImageView(this);

        preview.setAdjustViewBounds(true);

        preview.setImageURI(pendingUri);

        layout.addView(
                preview,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                )
        );

        nameBox = new EditText(this);

        nameBox.setHint(
                "اكتب اسم الصورة"
        );

        nameBox.setTextSize(24);

        nameBox.setSingleLine(true);

        layout.addView(
                nameBox,
                new LinearLayout.LayoutParams(
                        -1,
                        120
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

        retake.setOnClickListener(
                v -> {

                    if (pendingFile != null) {
                        pendingFile.delete();
                    }

                    pendingFile = null;
                    pendingUri = null;

                    takePhoto();
                }
        );

        save.setOnClickListener(
                v -> savePhoto()
        );

        setContentView(layout);

        nameBox.requestFocus();

        nameBox.postDelayed(
                () -> {

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

                },
                300
        );
    }

    private void savePhoto() {

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

        // تنظيف الأحرف غير المسموحة
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

        name = getUniqueName(name);

        Uri outputUri = null;

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

            if (Build.VERSION.SDK_INT >= 29) {

                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES
                                + "/Quick Name Camera"
                );

                values.put(
                        MediaStore.Images.Media.IS_PENDING,
                        1
                );
            }

            outputUri =
                    getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values
                    );

            if (outputUri == null) {
                throw new Exception(
                        "Could not create image"
                );
            }

            try (
                    FileInputStream input =
                            new FileInputStream(pendingFile);

                    OutputStream output =
                            getContentResolver()
                                    .openOutputStream(outputUri)
            ) {

                if (output == null) {
                    throw new Exception(
                            "Could not open output"
                    );
                }

                byte[] buffer =
                        new byte[8192];

                int length;

                while (
                        (length = input.read(buffer))
                                > 0
                ) {

                    output.write(
                            buffer,
                            0,
                            length
                    );
                }
            }

            if (Build.VERSION.SDK_INT >= 29) {

                ContentValues done =
                        new ContentValues();

                done.put(
                        MediaStore.Images.Media.IS_PENDING,
                        0
                );

                getContentResolver().update(
                        outputUri,
                        done,
                        null,
                        null
                );
            }

            if (pendingFile != null) {
                pendingFile.delete();
            }

            pendingFile = null;
            pendingUri = null;

            Toast.makeText(
                    this,
                    "تم حفظ الصورة باسم " + name,
                    Toast.LENGTH_SHORT
            ).show();

            showCameraScreen();

            // فتح الكاميرا مباشرة من جديد
            new android.os.Handler().postDelayed(
                    this::takePhoto,
                    300
            );

        } catch (Exception e) {

            if (outputUri != null) {

                getContentResolver().delete(
                        outputUri,
                        null,
                        null
                );
            }

            Toast.makeText(
                    this,
                    "تعذر حفظ الصورة",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String getUniqueName(String originalName) {

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

    private boolean imageExists(String name) {

        android.database.Cursor cursor =
                null;

        try {

            String[] projection = {
                    MediaStore.Images.Media._ID
            };

            String selection =
                    MediaStore.Images.Media.DISPLAY_NAME
                            + "=?";

            String[] args = {
                    name
            };

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

        } finally {

            if (cursor != null) {
                cursor.close();
            }
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

        shot.setText(
                "📷 تصوير صورة"
        );

        shot.setTextSize(24);

        layout.addView(
                shot,
                new LinearLayout.LayoutParams(
                        -1,
                        150
                )
        );

        shot.setOnClickListener(
                v -> takePhoto()
        );

        setContentView(layout);
    }
}