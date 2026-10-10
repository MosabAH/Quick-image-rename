package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends ComponentActivity {

    private static final int CAMERA_PERMISSION = 100;

    // Existing barcode lookup endpoint (keep its current behavior).
    private static final String SERVER_URL =
            "http://192.168.1.72:5000/barcode";

    // New endpoint on the SAME Flask server; must be installed server-side.
    private static final String SEARCH_URL =
            "http://192.168.1.72:5000/products/search";

    private static final String UPLOAD_URL =
            "http://192.168.1.72:5000/product-photo";

    // New V5 server capability check: prevents uploading with an outdated server.
    private static final String CAPABILITIES_URL =
            "http://192.168.1.72:5000/api/capabilities";
    private static final int PICK_XLSX_DESTINATION = 701;
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String API_KEY =
            "123456789test";

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;

    private Uri pendingUri;

    private EditText barcodeBox;
    private EditText locationBox;
    private EditText quantityBox;

    private Button saveButton;
    private TextView statusText;
    private TextView selectedProductText;

    // Name-search selection takes precedence over barcode entry.
    private String selectedItemCode = null;
    private String selectedItemLabel = null;
    private String restoredBarcode = "";
    private String restoredLocation = "";
    private String restoredQuantity = "";

    // Debounce and reject stale responses as users type/delete characters.
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearchTask;
    private int searchGeneration = 0;
    private AlertDialog searchDialog;

    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString("pending_uri");
            if (uri != null) {
                pendingUri = Uri.parse(uri);
            }
            restoredBarcode = savedInstanceState.getString("barcode_text", "");
            restoredLocation = savedInstanceState.getString("location_text", "");
            restoredQuantity = savedInstanceState.getString("quantity_text", "");
            selectedItemCode = savedInstanceState.getString("chosen_item_code");
            selectedItemLabel = savedInstanceState.getString("chosen_item_label");
        }

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION
            );

        } else if (pendingUri != null) {
            showNameScreen();
        } else {
            showCamera();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (pendingUri != null) {
            outState.putString("pending_uri", pendingUri.toString());
        }
        if (barcodeBox != null && pendingUri != null) {
            outState.putString("barcode_text", barcodeBox.getText().toString());
        }
        if (locationBox != null && pendingUri != null) {
            outState.putString("location_text", locationBox.getText().toString());
        }
        if (quantityBox != null && pendingUri != null) {
            outState.putString("quantity_text", quantityBox.getText().toString());
        }
        outState.putString("chosen_item_code", selectedItemCode);
        outState.putString("chosen_item_label", selectedItemLabel);
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
                if (pendingUri != null) {
                    showNameScreen();
                } else {
                    showCamera();
                }
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
        selectedItemCode = null;
        selectedItemLabel = null;
        restoredBarcode = "";
        restoredLocation = "";
        restoredQuantity = "";
        barcodeBox = null;
        locationBox = null;
        quantityBox = null;
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

        // Accessible even between photos, for repairing an interrupted export.
        LinearLayout excelButtons = new LinearLayout(this);
        excelButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button chooseExcel = new Button(this);
        chooseExcel.setAllCaps(false);
        chooseExcel.setText("اختيار ملف Excel");
        excelButtons.addView(chooseExcel,
                new LinearLayout.LayoutParams(0, dp(53), 1));
        chooseExcel.setOnClickListener(v -> chooseExcelDestination());
        Button refreshExcel = new Button(this);
        refreshExcel.setAllCaps(false);
        refreshExcel.setText("تحديث Excel");
        excelButtons.addView(refreshExcel,
                new LinearLayout.LayoutParams(0, dp(53), 1));
        refreshExcel.setOnClickListener(v -> exportExcelAgain());
        root.addView(excelButtons);

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

        // Barcode field + magnifying glass for products without barcodes.
        LinearLayout barcodeRow = new LinearLayout(this);
        barcodeRow.setOrientation(LinearLayout.HORIZONTAL);
        barcodeRow.setGravity(Gravity.CENTER_VERTICAL);

        barcodeBox = new EditText(this);
        barcodeBox.setHint("الباركود (أو ابحث بالعدسة)");
        barcodeBox.setSingleLine(true);
        barcodeBox.setTextSize(20);
        barcodeBox.setText(restoredBarcode);
        barcodeRow.addView(barcodeBox,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button searchButton = new Button(this);
        searchButton.setText("🔍");
        searchButton.setContentDescription("البحث عن صنف بالاسم");
        searchButton.setTextSize(24);
        searchButton.setAllCaps(false);
        barcodeRow.addView(searchButton,
                new LinearLayout.LayoutParams(dp(64), dp(58)));
        root.addView(barcodeRow);

        selectedProductText = new TextView(this);
        selectedProductText.setTextSize(16);
        selectedProductText.setPadding(dp(8), dp(5), dp(8), dp(8));
        selectedProductText.setOnClickListener(v -> clearSelectedProduct());
        root.addView(selectedProductText);
        updateSelectedProductLabel();

        // Typing a barcode cancels an earlier name-search selection.
        barcodeBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0 && selectedItemCode != null) {
                    clearSelectedProduct();
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchButton.setOnClickListener(v -> {
            if (!saving) showProductSearch();
        });

        locationBox = new EditText(this);
        locationBox.setHint("موقع البضاعة");
        locationBox.setSingleLine(true);
        locationBox.setTextSize(22);
        locationBox.setText(restoredLocation);

        root.addView(locationBox);

        quantityBox = new EditText(this);
        quantityBox.setHint("الكمية (مثلاً 5 أو 2.5)");
        quantityBox.setSingleLine(true);
        quantityBox.setTextSize(22);
        quantityBox.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL);
        quantityBox.setText(restoredQuantity);
        root.addView(quantityBox);

        Button chooseExcel = new Button(this);
        chooseExcel.setAllCaps(false);
        chooseExcel.setText("📄 اختيار / تغيير ملف Excel على التابلت");
        root.addView(chooseExcel);
        chooseExcel.setOnClickListener(v -> chooseExcelDestination());

        statusText = new TextView(this);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);
        statusText.setText(getExcelFileUri() == null
                ? "يرجى اختيار ملف Excel مرة واحدة قبل أول حفظ"
                : "ملف Excel محدد. سيتم تحديثه بعد نجاح الحفظ في Oracle.");

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

        if (selectedItemCode == null) {
            barcodeBox.requestFocus();
        } else {
            locationBox.requestFocus();
        }

        barcodeBox.postDelayed(() -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE
                    );

            if (imm != null) {
                imm.showSoftInput(
                        selectedItemCode == null ? barcodeBox : locationBox,
                        InputMethodManager.SHOW_IMPLICIT
                );
            }
        }, 300);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void clearSelectedProduct() {
        selectedItemCode = null;
        selectedItemLabel = null;
        updateSelectedProductLabel();
    }

    private void updateSelectedProductLabel() {
        if (selectedProductText == null) return;
        if (selectedItemCode == null) {
            selectedProductText.setVisibility(View.GONE);
        } else {
            selectedProductText.setVisibility(View.VISIBLE);
            selectedProductText.setText("الصنف المختار: " + selectedItemCode +
                    "\n" + (selectedItemLabel == null ? "" : selectedItemLabel) +
                    "\nاضغط هنا لإلغاء الاختيار");
        }
    }

    private void showProductSearch() {
        // Search is inside a dialog so the captured photo and shelf entry stay intact.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        EditText searchBox = new EditText(this);
        searchBox.setHint("ابحث باسم الصنف أو كوده...");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(19);
        root.addView(searchBox);

        TextView message = new TextView(this);
        message.setText("اكتب اسم الصنف، وستتحدث النتائج مع كل حرف");
        message.setTextSize(14);
        message.setPadding(dp(4), dp(8), dp(4), dp(8));
        root.addView(message);

        ScrollView scroll = new ScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(370)));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("بحث عن صنف 🔍")
                .setView(root)
                .setNegativeButton("إغلاق", (d, which) -> { })
                .create();
        searchDialog = dialog;
        searchGeneration++; // cancel replies from a previous dialog

        dialog.setOnDismissListener(d -> {
            searchGeneration++;
            if (pendingSearchTask != null) {
                searchHandler.removeCallbacks(pendingSearchTask);
                pendingSearchTask = null;
            }
            if (searchDialog == dialog) searchDialog = null;
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                String term = editable.toString().trim();
                final int generation = ++searchGeneration;
                if (pendingSearchTask != null) {
                    searchHandler.removeCallbacks(pendingSearchTask);
                }
                results.removeAllViews();
                if (term.isEmpty()) {
                    message.setText("اكتب اسم الصنف للبحث");
                    return;
                }
                if (term.length() > 100) {
                    message.setText("الحد الأقصى للبحث 100 حرف");
                    return;
                }
                message.setText("جاري البحث...");
                // Live search also triggers when a character is deleted.
                pendingSearchTask = () -> searchProductsLive(term, generation,
                        dialog, message, results);
                searchHandler.postDelayed(pendingSearchTask, 300);
            }
        });

        dialog.show();
        searchBox.requestFocus();
        searchBox.postDelayed(() -> {
            if (!dialog.isShowing()) return;
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    private void searchProductsLive(String term, int generation,
                                    AlertDialog dialog, TextView message,
                                    LinearLayout results) {
        new Thread(() -> {
            JSONArray items = null;
            String errorMessage = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(SEARCH_URL + "?q=" +
                        URLEncoder.encode(term, "UTF-8"));
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("X-API-Key", API_KEY);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                int httpStatus = connection.getResponseCode();
                String body = readServerResponse(connection, httpStatus);
                JSONObject json = new JSONObject(body);
                if (httpStatus != 200 || !json.optBoolean("ok", false)) {
                    throw new IOException(json.optString("error",
                            "تعذر البحث (HTTP " + httpStatus + ")"));
                }
                items = json.optJSONArray("items");
            } catch (Exception e) {
                errorMessage = e.getMessage() != null ? e.getMessage() : "خطأ بالبحث";
            } finally {
                if (connection != null) connection.disconnect();
            }
            final JSONArray foundItems = items;
            final String finalError = errorMessage;
            runOnUiThread(() -> {
                // Ignore late network responses from previous keystrokes.
                if (generation != searchGeneration ||
                        searchDialog != dialog || !dialog.isShowing()) return;
                results.removeAllViews();
                if (finalError != null) {
                    message.setText("خطأ في البحث: " + finalError);
                    return;
                }
                if (foundItems == null || foundItems.length() == 0) {
                    message.setText("لا يوجد صنف مطابق");
                    return;
                }
                message.setText("عدد النتائج المعروضة: " + foundItems.length() +
                        " (أول 30 نتيجة)");
                for (int i = 0; i < foundItems.length(); i++) {
                    JSONObject product = foundItems.optJSONObject(i);
                    if (product == null) continue;
                    String code = product.optString("item_code", "");
                    String arName = product.optString("name_ar", "");
                    String enName = product.optString("name_en", "");
                    String location = product.optString("location", "");
                    if (code.isEmpty()) continue;
                    String label = arName.isEmpty() ? enName : arName;
                    if (!enName.isEmpty() && !enName.equals(arName)) {
                        label = label + "\n" + enName;
                    }
                    final String displayLabel = label;
                    Button itemButton = new Button(this);
                    itemButton.setAllCaps(false);
                    itemButton.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    itemButton.setTextSize(15);
                    itemButton.setText(code + "\n" + displayLabel +
                            (location.isEmpty() ? "" : "\nالموقع الحالي: " + location));
                    itemButton.setOnClickListener(v -> {
                        selectedItemCode = code;
                        selectedItemLabel = displayLabel;
                        barcodeBox.setText(""); // no barcode required for a selected item
                        updateSelectedProductLabel();
                        dialog.dismiss();
                        locationBox.requestFocus();
                    });
                    results.addView(itemButton,
                            new LinearLayout.LayoutParams(-1, -2));
                }
            });
        }).start();
    }

    private void lookupAndSave() {

        if (saving || pendingUri == null) {
            return;
        }

        final String barcode = barcodeBox.getText().toString().trim();
        final String chosenItemCode = selectedItemCode;
        final String location = locationBox.getText().toString().trim();
        final Uri photoUri = pendingUri;
        final String quantity;
        try {
            quantity = normalizeQuantity(quantityBox.getText().toString().trim());
        } catch (IllegalArgumentException e) {
            quantityBox.setError(e.getMessage());
            quantityBox.requestFocus();
            return;
        }
        if (getExcelFileUri() == null) {
            statusText.setText("حدد ملف Excel على التابلت ثم اضغط حفظ مرة أخرى");
            chooseExcelDestination();
            return;
        }

        if (barcode.isEmpty() && chosenItemCode == null) {
            barcodeBox.setError("أدخل باركود أو اختر صنفًا من العدسة");
            barcodeBox.requestFocus();
            return;
        }

        if (location.isEmpty()) {
            locationBox.setError("اكتب موقع البضاعة");
            locationBox.requestFocus();
            return;
        }

        // The stock table column TXT_STKSHIELFCODE is VARCHAR2(20).
        if (location.length() > 20) {
            locationBox.setError("موقع البضاعة يجب ألا يزيد عن 20 حرفًا");
            locationBox.requestFocus();
            return;
        }

        if (location.equals(".") || location.equals("..")) {
            locationBox.setError("موقع غير صالح");
            return;
        }

        saving = true;
        saveButton.setEnabled(false);
        statusText.setText(chosenItemCode == null
                ? "جاري البحث عن كود الصنف..." : "جاري حفظ الصنف المختار...");

        new Thread(() -> {
            try {
                // Two supported paths: barcode lookup or direct selection by name.
                String itemCode = chosenItemCode != null
                        ? chosenItemCode : lookupItemCode(barcode);

                // Check that server returns actual NAMEA + basic unit BEFORE
                // starting an irreversible remote Oracle transaction.
                ensureServerSupportsExcel();

                runOnUiThread(() ->
                        statusText.setText("جاري رفع الصورة وتحديث موقع الصنف..."));

                // Oracle transaction: image + location together, no quantity
                // field in Oracle is changed by the Excel feature.
                JSONObject response = uploadPhotoToServer(
                        chosenItemCode == null ? barcode : "",
                        itemCode, location, photoUri);

                // Oracle has ALREADY committed here. Never retry the remote
                // save just because writing the LOCAL Excel file fails.
                String message;
                LocalExcelLedger ledger = new LocalExcelLedger(this);
                try {
                    String nameAr = response.optString("name_ar", "");
                    String basicUnit = response.optString("basic_unit", "");
                    ledger.recordSuccessfulSave(itemCode, nameAr, basicUnit, quantity);
                    try {
                        int exported = ledger.exportTo(getExcelFileUri());
                        message = "تم تحديث Excel: " + exported + " سطر";
                    } catch (Exception exportError) {
                        message = "تم الحفظ على السيرفر وفي سجل الجهاز، لكن تحديث Excel فشل. " +
                                "اضغط تحديث Excel لإعادة تصديره: " + exportError.getMessage();
                    }
                } catch (Exception localError) {
                    message = "الصورة والموقع انحفظوا على السيرفر، لكن لم يتم تسجيل " +
                            "الكمية محليًا! راجع البيانات قبل الاستمرار: " +
                            localError.getMessage();
                } finally {
                    ledger.close();
                }
                final String finalMessage = message;
                // Only rename the local copy after server succeeds.
                runOnUiThread(() -> savePhoto(itemCode, location, finalMessage));

            } catch (Exception e) {
                final String message = e.getMessage() != null
                        ? e.getMessage() : "خطأ غير معروف";
                runOnUiThread(() -> {
                    saving = false;
                    saveButton.setEnabled(true);
                    statusText.setText("تعذر تأكيد الحفظ؛ افحص الصنف قبل إعادة المحاولة: " + message);
                    // Keep pendingUri so the user can retry without
                    // re-taking the photograph.
                });
            }
        }).start();
    }


    // Android Storage Access Framework: the user picks the tablet location ONCE.
    // Permissions survive app restarts, without broad storage permissions.
    private void chooseExcelDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(XLSX_MIME);
        intent.putExtra(Intent.EXTRA_TITLE, "BURHAN_Stock_Count.xlsx");
        startActivityForResult(intent, PICK_XLSX_DESTINATION);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_XLSX_DESTINATION ||
                resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            getSharedPreferences("burhan_excel", MODE_PRIVATE)
                    .edit().putString("workbook_uri", uri.toString()).apply();
            if (statusText != null) {
                statusText.setText("تم اختيار ملف Excel. اضغط حفظ بعد إدخال البيانات.");
            }
            exportExcelAgain(); // create header or re-export existing local journal
        } catch (SecurityException e) {
            Toast.makeText(this, "تعذر حفظ صلاحية الملف: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private Uri getExcelFileUri() {
        String value = getSharedPreferences("burhan_excel", MODE_PRIVATE)
                .getString("workbook_uri", null);
        return value == null ? null : Uri.parse(value);
    }

    private void exportExcelAgain() {
        Uri uri = getExcelFileUri();
        if (uri == null) {
            chooseExcelDestination();
            return;
        }
        new Thread(() -> {
            LocalExcelLedger ledger = new LocalExcelLedger(this);
            String message;
            try {
                int rows = ledger.exportTo(uri);
                message = "تم تحديث ملف Excel على التابلت (" + rows + " سطر)";
            } catch (Exception e) {
                message = "فشل تحديث ملف Excel: " + e.getMessage();
            } finally {
                ledger.close();
            }
            final String finalMessage = message;
            runOnUiThread(() -> {
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                if (statusText != null) statusText.setText(finalMessage);
            });
        }).start();
    }

    private static String normalizeQuantity(String entered) {
        if (entered.isEmpty()) throw new IllegalArgumentException("أدخل الكمية");
        StringBuilder normalized = new StringBuilder();
        for (int offset = 0; offset < entered.length();) {
            int digit = entered.codePointAt(offset);
            offset += Character.charCount(digit);
            int val = Character.digit(digit, 10);
            if (val >= 0) normalized.append((char) ('0' + val));
            else if (digit == '.' || digit == 0x066B) normalized.append('.');
            else throw new IllegalArgumentException("الكمية يجب أن تكون رقمًا موجبًا أو صفرًا");
        }
        String number = normalized.toString();
        if (!number.matches("[0-9]{1,12}(?:\\.[0-9]{1,4})?")) {
            throw new IllegalArgumentException("كمية غير صالحة (حتى 4 منازل عشرية)");
        }
        return new BigDecimal(number).stripTrailingZeros().toPlainString();
    }

    private void ensureServerSupportsExcel() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(CAPABILITIES_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("X-API-Key", API_KEY);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int status = connection.getResponseCode();
            String body = readServerResponse(connection, status);
            JSONObject json = new JSONObject(body);
            if (status != 200 || !json.optBoolean("excel_metadata_v5", false)) {
                throw new IOException("لازم تحديث server.py إلى V5 أولًا");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String lookupItemCode(String barcode) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(SERVER_URL + "?code=" +
                    URLEncoder.encode(barcode, "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("X-API-Key", API_KEY);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int status = connection.getResponseCode();
            String body = readServerResponse(connection, status);
            if (status == 401) {
                throw new IOException("مفتاح الاتصال غير صحيح");
            }
            if (status != 200) {
                throw new IOException("فشل البحث عن الباركود (HTTP " + status + ")");
            }

            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("found", false)) {
                throw new IOException("الباركود غير موجود في قاعدة البيانات");
            }
            String itemCode = json.optString("item_code", "").trim();
            if (itemCode.isEmpty()) {
                throw new IOException("كود الصنف فارغ");
            }
            return itemCode;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject uploadPhotoToServer(
            String barcode, String itemCode, String location, Uri photoUri) throws Exception {

        HttpURLConnection connection = null;
        String boundary = "----Burhan" + UUID.randomUUID().toString().replace("-", "");

        try {
            connection = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000);
            connection.setChunkedStreamingMode(64 * 1024);
            connection.setRequestProperty("X-API-Key", API_KEY);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream out = new DataOutputStream(
                    connection.getOutputStream())) {
                writeFormField(out, boundary, "barcode", barcode);
                writeFormField(out, boundary, "product_code", itemCode);
                writeFormField(out, boundary, "location", location);

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"image\"; " +
                        "filename=\"photo.jpg\"\r\n");
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n");

                try (InputStream in = getContentResolver().openInputStream(photoUri)) {
                    if (in == null) {
                        throw new IOException("تعذر قراءة الصورة من الهاتف");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                out.writeBytes("\r\n--" + boundary + "--\r\n");
            }

            int status = connection.getResponseCode();
            String body = readServerResponse(connection, status);
            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (Exception parseException) {
                throw new IOException("رد غير مفهوم من السيرفر (HTTP " + status + ")");
            }
            if (status != 200 || !json.optBoolean("ok", false)) {
                String message = json.optString("error", "HTTP " + status);
                throw new IOException(message);
            }

            String savedCode = json.optString("product_code", "");
            String savedLocation = json.optString("location", "");
            if (!itemCode.equals(savedCode) || !location.equals(savedLocation)) {
                throw new IOException("السيرفر أعاد بيانات مختلفة؛ راجع الحفظ قبل المحاولة من جديد");
            }
            if (!json.has("name_ar") || !json.has("basic_unit")) {
                throw new IOException("السيرفر لا يرجع اسم الصنف ووحدته؛ راجع إصدار V5");
            }
            return json;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void writeFormField(DataOutputStream out, String boundary,
                                String name, String value) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes("UTF-8"));
        out.writeBytes("\r\n");
    }

    private String readServerResponse(HttpURLConnection connection, int status)
            throws IOException {
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            throw new IOException("لا يوجد رد من السيرفر");
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
                if (response.length() > 8192) {
                    throw new IOException("رد السيرفر أطول من المتوقع");
                }
            }
        }
        return response.toString();
    }

    private void savePhoto(String itemCode, String location, String excelMessage) {

        if (pendingUri == null) {
            saving = false;
            saveButton.setEnabled(true);
            return;
        }

        String visibleName =
                cleanName(itemCode) + "," + cleanName(location);

        String filename = getUniqueName(visibleName + ".jpg");

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
                    "تم حفظ الصورة والموقع على السيرفر: " + shownName +
                            "\n" + excelMessage,
                    Toast.LENGTH_LONG
            ).show();

            showCamera();

        } catch (Exception e) {
            // Upload already committed on the server. Do NOT offer a retry
            // of the same save merely because local renaming failed.
            Toast.makeText(this,
                    "تم الحفظ على السيرفر. " + excelMessage +
                            "، لكن تعذرت تسمية النسخة على الهاتف: "
                            + e.getMessage(), Toast.LENGTH_LONG).show();
            pendingUri = null;
            saving = false;
            showCamera();
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
        int dot = originalName.lastIndexOf('.');
        String stem = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext = dot > 0 ? originalName.substring(dot) : "";

        String candidate = originalName;
        int number = 1;
        while (imageExists(candidate)) {
            candidate = stem + String.format(Locale.ROOT, "_%02d", number) + ext;
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
        searchGeneration++;
        if (pendingSearchTask != null) searchHandler.removeCallbacks(pendingSearchTask);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        super.onDestroy();
    }
}
