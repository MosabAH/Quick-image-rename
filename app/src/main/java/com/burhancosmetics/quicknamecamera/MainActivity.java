package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 10;
    private static final int REQ_TAKE_PHOTO = 20;

    private Uri pendingUri;
    private EditText nameBox;
    private ImageView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showCameraScreen();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{Manifest.permission.CAMERA},
                        REQ_CAMERA
                );

            } else if (savedInstanceState == null) {
                takePhoto();
            }

        } else if (savedInstanceState == null) {
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

        if (requestCode == REQ_CAMERA) {

            if (grantResults.length > 0
                    && grantResults[0]
                    == PackageManager.PERMISSION_GRANTED) {

                takePhoto();

            } else {

                Toast.makeText(
                        this,
                        "لازم تسمح للتطبيق باستخدام الكاميرا",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    /*
     * إنشاء ملف مؤقت ثم فتح الكاميرا
     */
    private void takePhoto() {

        try {

            // إذا كان هناك ملف مؤقت قديم نحذفه
            if (pendingUri != null) {
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

            // نتأكد أن هناك تطبيق كاميرا
            if (cameraIntent.resolveActivity(
                    getPackageManager()) == null) {

                deletePendingPhoto();

                Toast.makeText(
                        this,
                        "لم يتم العثور على تطبيق كاميرا",
                        Toast.LENGTH_LONG
                ).show();

                return;
            }

            startActivityForResult(
                    cameraIntent,
                    REQ_TAKE_PHOTO
            );

        } catch (Exception e) {

            deletePendingPhoto();

            Toast.makeText(
                    this,
                    "تعذر فتح الكاميرا",
                    Toast.LENGTH_LONG
            ).show();

            showCameraScreen();
        }
    }

    /*
     * النتيجة بعد الرجوع من الكاميرا
     *
     * مهم:
     * لا نفحص SIZE.
     * RESULT_OK يعني أن التصوير تم بنجاح.
     */
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

        // الصورة تم التقاطها بنجاح
        if (resultCode == RESULT_OK
                && pendingUri != null) {

            showNameScreen();
            return;
        }

        // المستخدم ألغى الكاميرا
        deletePendingPhoto();

        showCameraScreen();
    }

    /*
     * شاشة تسمية الصورة
     */
    private void showNameScreen() {

        LinearLayout layout =
                new LinearLayout(this);

        layout.setOrientation(
                LinearLayout.VERTICAL
        );

        layout.setPadding(
                20,
                20,
                20,
                20
        );

        /*
         * معاينة الصورة
         */
        preview = new ImageView(this);

        preview.setAdjustViewBounds(true);

        preview.setScaleType(
                ImageView.ScaleType.CENTER_INSIDE
        );

        try {

            preview.setImageURI(
                    null
            );

            preview.setImageURI(
                    pendingUri
            );

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

        /*
         * خانة الاسم
         */
        nameBox =
                new EditText(this);

        nameBox.setHint(
                "اكتب اسم الصورة"
        );

        nameBox.setTextSize(24);

        nameBox.setSingleLine(true);

        layout.addView(
                nameBox,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        120
                )
        );

        /*
         * الأزرار
         */
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

        /*
         * إعادة التصوير
         */
        retake.setOnClickListener(v -> {

            hideKeyboard();

            deletePendingPhoto();

            showCameraScreen();

            new Handler(
                    getMainLooper()
            ).postDelayed(
                    this::takePhoto,
                    200
            );
        });

        /*
         * حفظ الصورة
         */
        save.setOnClickListener(
                v -> savePhoto()
        );

        setContentView(layout);

        /*
         * فتح الكيبورد تلقائيًا
         */
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

    /*
     * حفظ الصورة بالاسم
     */
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

        /*
         * تنظيف الاسم من الرموز غير المناسبة
         */
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

        /*
         * إضافة jpg
         */
        if (!name.toLowerCase()
                .endsWith(".jpg")) {

            name += ".jpg";
        }

        /*
         * منع تكرار الاسم
         */
        name =
                getUniqueName(name);

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

            /*
             * بعد الحفظ:
             * افتح الكاميرا مباشرة للصورة التالية
             */
            showCameraScreen();

            new Handler(
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

    /*
 * مثال:
 * chair.jpg
 * chair_01.jpg
 * chair_02.jpg
 */