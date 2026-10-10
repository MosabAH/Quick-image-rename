package com.burhancosmetics.quicknamecamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Bundle;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
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
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.File;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final int PICK_XLSX_DESTINATION = 701; // old local workbook support
    private static final int PICK_CENTRAL_XLSX = 702;
    private static final int PICK_GALLERY_IMAGE = 703;
    private static final int PICK_FILES_IMAGE = 704;
    private static final String EXPORT_URL =
            "http://192.168.1.72:5000/api/count/export";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String API_KEY =
            "123456789test";

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;

    private Uri pendingUri;
    private String pendingOperationId; // persisted across retries of the same photo

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
    private boolean photoImportBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString("pending_uri");
            if (uri != null) {
                pendingUri = Uri.parse(uri);
                pendingOperationId = savedInstanceState.getString("operation_id");
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
            outState.putString("operation_id", pendingOperationId);
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
                Toast.makeText(this,
                        "صلاحية الكاميرا مرفوضة؛ يمكنك اختيار صورة من الاستوديو أو الملفات",
                        Toast.LENGTH_LONG).show();
                if (pendingUri != null) showNameScreen();
                else showCamera();
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
        pendingOperationId = null;
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
        chooseExcel.setText("تصدير Excel الموحد");
        excelButtons.addView(chooseExcel,
                new LinearLayout.LayoutParams(0, dp(53), 1));
        chooseExcel.setOnClickListener(v -> chooseCentralExcelDestination());
        Button refreshExcel = new Button(this);
        refreshExcel.setAllCaps(false);
        refreshExcel.setText("تنزيل النسخة الجديدة");
        excelButtons.addView(refreshExcel,
                new LinearLayout.LayoutParams(0, dp(53), 1));
        refreshExcel.setOnClickListener(v -> chooseCentralExcelDestination());
        root.addView(excelButtons);

        // Diagnostic only: GET /api/capabilities, does not modify Oracle.
        Button testConnectionButton = new Button(this);
        testConnectionButton.setAllCaps(false);
        testConnectionButton.setText("اختبار اتصال السيرفر");
        root.addView(testConnectionButton,
                new LinearLayout.LayoutParams(-1, dp(55)));
        testConnectionButton.setOnClickListener(v -> testServerConnection(testConnectionButton));

        Button captureButton = new Button(this);
        captureButton.setText("تصوير");
        captureButton.setTextSize(24);

        root.addView(
                captureButton,
                new LinearLayout.LayoutParams(-1, 150)
        );

        captureButton.setEnabled(false);
        if (Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
            startCamera(() -> captureButton.setEnabled(true));
        } else {
            Toast.makeText(this, "يمكنك اختيار صورة بدون إذن الكاميرا",
                    Toast.LENGTH_LONG).show();
        }

        captureButton.setOnClickListener(v -> {
            captureButton.setEnabled(false);
            takePicture(captureButton);
        });

        // Both image sources work without broad storage/gallery permissions.
        LinearLayout imageSources = new LinearLayout(this);
        imageSources.setOrientation(LinearLayout.HORIZONTAL);
        Button galleryButton = new Button(this);
        galleryButton.setText("🖼️ الاستوديو");
        galleryButton.setAllCaps(false);
        imageSources.addView(galleryButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        galleryButton.setOnClickListener(v -> openGalleryPicker());
        Button filesButton = new Button(this);
        filesButton.setText("📁 الملفات");
        filesButton.setAllCaps(false);
        imageSources.addView(filesButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        filesButton.setOnClickListener(v -> openFilesPicker());
        root.addView(imageSources);
        setContentView(root);
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

        // CameraX must first write the JPEG somewhere. Keep it temporarily
        // in the APP-PRIVATE CACHE, not in Gallery / MediaStore / Pictures.
        // Delete it immediately after server confirms the Oracle transaction.
        final File temporaryPhoto;
        try {
            File pendingDir = new File(getCacheDir(), "pending_uploads");
            if (!pendingDir.exists() && !pendingDir.mkdirs()) {
                throw new IOException("تعذر تجهيز مساحة الصور المؤقتة");
            }
            temporaryPhoto = File.createTempFile("burhan_pending_", ".jpg", pendingDir);
        } catch (IOException e) {
            button.setEnabled(true);
            Toast.makeText(this, "تعذر بدء التصوير: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(temporaryPhoto).build();

        Executor executor =
                ContextCompat.getMainExecutor(this);

        imageCapture.takePicture(
                options,
                executor,
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults results) {

                        // For File destinations CameraX may return a NULL URI.
                        // Uri.fromFile() is valid for private in-app use only.
                        pendingUri = Uri.fromFile(temporaryPhoto);

                        if (!temporaryPhoto.isFile() || temporaryPhoto.length() == 0) {
                            temporaryPhoto.delete();
                            pendingUri = null;
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

                        temporaryPhoto.delete();
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
        Button scanBarcodeButton = new Button(this);
        scanBarcodeButton.setText("📷");
        scanBarcodeButton.setContentDescription("مسح الباركود بالكاميرا");
        scanBarcodeButton.setTextSize(23);
        scanBarcodeButton.setAllCaps(false);
        barcodeRow.addView(scanBarcodeButton,
                new LinearLayout.LayoutParams(dp(58), dp(58)));
        Button verifyBarcodeButton = new Button(this);
        verifyBarcodeButton.setText("تحقق");
        verifyBarcodeButton.setContentDescription("التحقق من الباركود وإظهار اسم الصنف");
        verifyBarcodeButton.setAllCaps(false);
        verifyBarcodeButton.setTextSize(14);
        barcodeRow.addView(verifyBarcodeButton,
                new LinearLayout.LayoutParams(dp(66), dp(58)));
        root.addView(barcodeRow);
        verifyBarcodeButton.setOnClickListener(v -> {
            if (saving) return;
            String enteredBarcode = barcodeBox.getText().toString().trim();
            if (enteredBarcode.isEmpty()) {
                barcodeBox.setError("أدخل الباركود أولًا");
                barcodeBox.requestFocus();
                return;
            }
            clearSelectedProduct();
            statusText.setText("جاري التحقق من الباركود وجلب اسم الصنف...");
            lookupScannedBarcode(enteredBarcode);
        });
        scanBarcodeButton.setOnClickListener(v -> {
            if (!saving) showBarcodeCamera();
        });

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
        chooseExcel.setText("📄 تصدير Excel الموحد للأندرويد والآيباد");
        root.addView(chooseExcel);
        chooseExcel.setOnClickListener(v -> chooseCentralExcelDestination());

        statusText = new TextView(this);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);
        statusText.setText("الكمية ستُحفظ مركزيًا مع الصورة والموقع في Oracle. " +
                "اضغط تصدير Excel عند الحاجة.");

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
        Button changeImageButton = new Button(this);
        changeImageButton.setAllCaps(false);
        changeImageButton.setText("🖼️ تغيير الصورة من الاستوديو أو الملفات");
        root.addView(changeImageButton, new LinearLayout.LayoutParams(-1, dp(54)));
        setContentView(root);
        changeImageButton.setOnClickListener(v -> {
            if (!saving && !photoImportBusy) showImageSourceDialog();
        });

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
            selectedProductText.setText("كود الصنف: " + selectedItemCode +
                    (selectedItemLabel == null || selectedItemLabel.trim().isEmpty()
                            ? "" : "\n" + selectedItemLabel) +
                    "\nاضغط هنا لإلغاء الاختيار");
        }
    }

    /**
     * Scan barcodes LOCALLY on the Android device via bundled ML Kit.
     * This is a separate CameraX preview.  It NEVER saves an image and
     * NEVER commits anything to Oracle.  Save still requires the user to
     * enter location/quantity and press the existing Save button.
     */
    private void showBarcodeCamera() {
        if (saving || barcodeBox == null) return;
        hideKeyboard();
        final PreviewView scanView = new PreviewView(this);
        scanView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView hint = new TextView(this);
        hint.setText("وجّه الكاميرا نحو الباركود (EAN / UPC / Code 128)");
        hint.setPadding(dp(10),dp(7),dp(10),dp(7));
        root.addView(hint);
        root.addView(scanView, new LinearLayout.LayoutParams(-1, dp(320)));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("📷 مسح الباركود")
                .setView(root)
                .setNegativeButton("إلغاء", (d,w)->{})
                .create();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
        final BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE)
                .build();
        final BarcodeScanner reader = BarcodeScanning.getClient(options);
        final Preview[] scanPreview = new Preview[1];
        final ImageAnalysis[] scanAnalysis = new ImageAnalysis[1];
        dialog.setOnDismissListener(d -> {
            finished.set(true);
            if (cameraProvider != null) {
                if (scanAnalysis[0] != null) {
                    scanAnalysis[0].clearAnalyzer();
                }
                if (scanPreview[0] != null && scanAnalysis[0] != null) {
                    cameraProvider.unbind(scanPreview[0], scanAnalysis[0]);
                }
            }
            // No scanner frame is saved on the tablet.
            reader.close();
            analyzerExecutor.shutdown();
        });
        dialog.show();

        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (finished.get() || !dialog.isShowing()) return;
            try {
                cameraProvider = future.get();
                scanPreview[0] = new Preview.Builder().build();
                scanPreview[0].setSurfaceProvider(scanView.getSurfaceProvider());
                scanAnalysis[0] = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                scanAnalysis[0].setAnalyzer(analyzerExecutor, proxy -> {
                    if (finished.get()) { proxy.close(); return; }
                    android.media.Image cameraFrame = proxy.getImage();
                    if (cameraFrame == null) { proxy.close(); return; }
                    try {
                        InputImage frame = InputImage.fromMediaImage(
                                cameraFrame, proxy.getImageInfo().getRotationDegrees());
                        reader.process(frame)
                                .addOnSuccessListener(barcodes -> {
                                    if (finished.get()) return;
                                    for (Barcode code : barcodes) {
                                        String value = code.getRawValue();
                                        if (value == null || value.trim().isEmpty()) continue;
                                        if (!finished.compareAndSet(false, true)) return;
                                        String found = value.trim();
                                        runOnUiThread(() -> {
                                            dialog.dismiss();
                                            clearSelectedProduct();
                                            barcodeBox.setText(found);
                                            statusText.setText("تمت قراءة الباركود " + found +
                                                    "، جاري التأكد من الصنف...");
                                            lookupScannedBarcode(found);
                                        });
                                        return;
                                    }
                                })
                                .addOnFailureListener(e -> Log.w("BURHAN_SCAN", "Frame decode failed", e))
                                .addOnCompleteListener(task -> proxy.close());
                    } catch (Exception e) {
                        proxy.close();
                        Log.w("BURHAN_SCAN", "Frame could not be decoded", e);
                    }
                });
                cameraProvider.unbindAll(); // Previous photo preview has been stopped.
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        scanPreview[0], scanAnalysis[0]);
            } catch (Exception e) {
                Log.e("BURHAN_SCAN", "Cannot open scanner", e);
                dialog.dismiss();
                Toast.makeText(this, "تعذر فتح قارئ الباركود: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void lookupScannedBarcode(String scanned) {
        new Thread(() -> {
            JSONObject item = null;
            String problem = null;
            try {
                item = lookupBarcodeDetails(scanned);
            } catch (Exception e) {
                problem = e.getMessage();
            }
            final JSONObject product = item;
            final String errorMessage = problem;
            runOnUiThread(() -> {
                // Do not show a response for a barcode the user already changed.
                if (barcodeBox == null || !scanned.equals(barcodeBox.getText().toString().trim())) return;
                if (product == null) {
                    clearSelectedProduct();
                    statusText.setText("تعذر التحقق من الباركود: " + errorMessage);
                    return;
                }
                selectedItemCode = product.optString("item_code", "").trim();
                // TXT_NAMEE comes from BURHAN.STOCK_TBL; print it exactly,
                // without a label. A missing server field is not an empty DB value.
                if (!product.has("TXT_NAMEE") && !product.has("name_en")) {
                    clearSelectedProduct();
                    statusText.setText("السيرفر لا يرجع TXT_NAMEE. حدّث server.py إلى V6.5");
                    return;
                }
                String nameEn = product.optString("TXT_NAMEE",
                        product.optString("name_en", "")).trim();
                selectedItemLabel = nameEn;
                updateSelectedProductLabel();
                statusText.setText(nameEn.isEmpty()
                        ? "TXT_NAMEE فارغ لهذا الصنف في Oracle؛ راجع بيانات STOCK_TBL"
                        : "تم التحقق من الباركود — أدخل الموقع والكمية ثم احفظ");
                locationBox.requestFocus();
            });
        }).start();
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
                        // Show only TXT_NAMEE, directly under the product code.
                        selectedItemLabel = product.optString("TXT_NAMEE", enName).trim();
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

                // Check NAMEA + NAMEE + basic unit response support BEFORE
                // starting an irreversible remote Oracle transaction.
                ensureServerSupportsExcel();

                runOnUiThread(() ->
                        statusText.setText("جاري رفع الصورة وتحديث موقع الصنف..."));

                // Oracle transaction: image + shelf + central inventory COUNT.
                // Stock's actual quantity is NEVER changed by this endpoint.
                JSONObject response = uploadPhotoToServer(
                        barcode, itemCode, location, quantity, photoUri);

                // Photo + shelf + quantity are committed in ONE Oracle transaction.
                // We no longer append a tablet-only Excel row here; both devices
                // use the server's single shared central ledger.
                if (!response.optBoolean("count_saved", false)) {
                    throw new IOException("السيرفر لم يؤكد حفظ الكمية ضمن الجرد الموحد");
                }
                final String finalMessage = "تم حفظ الصورة والموقع والكمية في الجرد الموحد " +
                        "للصنف: " + itemCode;
                runOnUiThread(() -> finishSuccessfulSave(finalMessage));

            } catch (Exception e) {
                Log.e("BURHAN_NET", "Product save network/processing error", e);
                final String message = "(" + e.getClass().getSimpleName() + ") " +
                        (e.getMessage() != null ? e.getMessage() : "خطأ غير معروف");
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


    // V6.6: choose an existing photo either from a gallery provider or Files.
    // Copy/convert only into our private cache; never delete the user's original.
    private void openGalleryPicker() {
        if (saving || photoImportBusy) return;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(intent, "اختيار صورة من الاستوديو"),
                    PICK_GALLERY_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الاستوديو: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openFilesPicker() {
        if (saving || photoImportBusy) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, PICK_FILES_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الملفات: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showImageSourceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("اختيار صورة بديلة")
                .setItems(new String[]{"🖼️ الاستوديو", "📁 الملفات"}, (dialog, index) -> {
                    if (index == 0) openGalleryPicker();
                    else openFilesPicker();
                }).show();
    }

    private void rememberCurrentEntry() {
        if (barcodeBox != null) restoredBarcode = barcodeBox.getText().toString();
        if (locationBox != null) restoredLocation = locationBox.getText().toString();
        if (quantityBox != null) restoredQuantity = quantityBox.getText().toString();
    }

    private void importPickedImage(Uri source) {
        if (saving || photoImportBusy) return;
        photoImportBusy = true;
        Toast.makeText(this, "جاري تجهيز الصورة المختارة...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File newImage = null;
            String problem = null;
            try {
                newImage = prepareSelectedPhoto(source);
            } catch (Exception e) {
                problem = e.getMessage();
                Log.e("BURHAN_IMAGE", "Cannot prepare selected photo", e);
            }
            final File prepared = newImage;
            final String errorMessage = problem;
            runOnUiThread(() -> {
                photoImportBusy = false;
                if (isFinishing() || isDestroyed() || saving) {
                    if (prepared != null) prepared.delete();
                    return;
                }
                if (prepared == null) {
                    Toast.makeText(this, "فشل تجهيز الصورة: " + errorMessage,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                rememberCurrentEntry();
                // Remove only the previously owned private-cache file.
                deletePendingPhoto();
                pendingUri = Uri.fromFile(prepared);
                pendingOperationId = null; // a different photo = a different upload
                if (cameraProvider != null) cameraProvider.unbindAll();
                showNameScreen();
                Toast.makeText(this, "تم اختيار الصورة؛ أكمل البيانات واضغط حفظ",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private File prepareSelectedPhoto(Uri source) throws Exception {
        final int maxSide = 2400;
        Bitmap bitmap = null;
        File temp = null;
        boolean complete = false;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source imageSource = ImageDecoder.createSource(
                        getContentResolver(), source);
                bitmap = ImageDecoder.decodeBitmap(imageSource, (decoder, info, src) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    if (width <= 0 || height <= 0) throw new IllegalArgumentException("صورة غير صالحة");
                    double factor = Math.min(1.0, (double) maxSide / Math.max(width, height));
                    if (factor < 1.0) decoder.setTargetSize(
                            Math.max(1, (int) Math.round(width * factor)),
                            Math.max(1, (int) Math.round(height * factor)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream stream = getContentResolver().openInputStream(source)) {
                    if (stream == null) throw new IOException("تعذر قراءة الملف");
                    BitmapFactory.decodeStream(stream, null, opts);
                }
                if (opts.outWidth <= 0 || opts.outHeight <= 0)
                    throw new IOException("صيغة الصورة غير مدعومة");
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = 1;
                while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > maxSide)
                    opts.inSampleSize *= 2;
                try (InputStream stream = getContentResolver().openInputStream(source)) {
                    if (stream == null) throw new IOException("تعذر فتح الملف");
                    bitmap = BitmapFactory.decodeStream(stream, null, opts);
                }
                if (bitmap != null && Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxSide) {
                    double factor = (double) maxSide / Math.max(bitmap.getWidth(), bitmap.getHeight());
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                            Math.max(1, (int) Math.round(bitmap.getWidth() * factor)),
                            Math.max(1, (int) Math.round(bitmap.getHeight() * factor)), true);
                    if (scaled != bitmap) bitmap.recycle();
                    bitmap = scaled;
                }
            }
            if (bitmap == null) throw new IOException("لا يمكن فتح الصورة المختارة");
            File directory = new File(getCacheDir(), "pending_uploads");
            if (!directory.exists() && !directory.mkdirs())
                throw new IOException("تعذر تجهيز ملفات التطبيق المؤقتة");
            temp = File.createTempFile("burhan_pending_", ".jpg", directory);
            try (OutputStream output = new java.io.FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                    throw new IOException("تعذر تحويل الصورة إلى JPEG");
            }
            if (temp.length() <= 0 || temp.length() > 10 * 1024 * 1024)
                throw new IOException("الصورة بعد التحويل أكبر من 10 ميجابايت");
            complete = true;
            return temp;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (!complete && temp != null) temp.delete();
        }
    }

    // Android Storage Access Framework: export central workbook on demand.
    // Permissions survive app restarts, without broad storage permissions.
    // New unified spreadsheet from Oracle, including both Android and iPad.
    private void chooseCentralExcelDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(XLSX_MIME);
        intent.putExtra(Intent.EXTRA_TITLE, "BURHAN_Unified_Count.xlsx");
        startActivityForResult(intent, PICK_CENTRAL_XLSX);
    }

    private void downloadCentralExcel(Uri destination) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            String message;
            try {
                connection = (HttpURLConnection) new URL(EXPORT_URL).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("X-API-Key", API_KEY);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new IOException("تصدير Excel فشل - HTTP " + code);
                }
                try (InputStream in = connection.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("تعذر فتح ملف Excel");
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                message = "تم تنزيل ملف الجرد الموحد من السيرفر";
            } catch (Exception ex) {
                Log.e("BURHAN_EXCEL", "Export failed", ex);
                message = "فشل تنزيل Excel: " + ex.getMessage();
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        }).start();
    }

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
        if (requestCode == PICK_GALLERY_IMAGE || requestCode == PICK_FILES_IMAGE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null)
                importPickedImage(data.getData());
            return;
        }
        if (requestCode == PICK_CENTRAL_XLSX) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                downloadCentralExcel(data.getData());
            }
            return;
        }
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

    // Returns an exact diagnosis on the TABLET rather than a generic
    // "Failed to connect". Does not write to Oracle or the Excel file.
    private void testServerConnection(Button button) {
        if (!button.isEnabled()) return;
        button.setEnabled(false);
        new Thread(() -> {
            HttpURLConnection connection = null;
            String diagnostic;
            try {
                boolean httpAllowed = Build.VERSION.SDK_INT < 23 ||
                        NetworkSecurityPolicy.getInstance()
                                .isCleartextTrafficPermitted("192.168.1.72");
                if (!httpAllowed) {
                    diagnostic = "النظام يمنع HTTP إلى 192.168.1.72.\n" +
                            "راجع إعدادات networkSecurityConfig في AndroidManifest.";
                } else {
                    connection = (HttpURLConnection) new URL(CAPABILITIES_URL).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("X-API-Key", API_KEY);
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(10000);
                    int httpStatus = connection.getResponseCode();
                    // HTTP status 200/401/404 all prove that Android reached Flask.
                    if (httpStatus == 200) {
                        diagnostic = "اتصال التطبيق بالسيرفر ناجح ✅\n" +
                                "HTTP 200 - خدمة V5 متاحة.";
                    } else if (httpStatus == 401) {
                        diagnostic = "وصل التطبيق للسيرفر ✅\n" +
                                "HTTP 401 - مفتاح API في التطبيق لا يطابق السيرفر.";
                    } else if (httpStatus == 404) {
                        diagnostic = "وصل التطبيق للسيرفر ✅\n" +
                                "HTTP 404 - مسار V5 غير متوفر؛ افحص نسخة server.py.";
                    } else {
                        diagnostic = "وصل التطبيق للسيرفر ✅\n" +
                                "HTTP " + httpStatus + ". راجع سجل السيرفر.";
                    }
                }
            } catch (Exception e) {
                Log.e("BURHAN_NET", "Network diagnostic failed", e);
                diagnostic = "فشل الاتصال من التطبيق ❌\n" +
                        "نوع الخطأ: " + e.getClass().getSimpleName() + "\n" +
                        "التفاصيل: " + (e.getMessage() == null ? "بدون تفاصيل" : e.getMessage()) +
                        "\n\nافحص Logcat باستخدام BURHAN_NET.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String result = diagnostic;
            runOnUiThread(() -> {
                button.setEnabled(true);
                if (!isFinishing() && !isDestroyed()) {
                    new AlertDialog.Builder(this)
                            .setTitle("نتيجة اختبار الاتصال")
                            .setMessage(result)
                            .setPositiveButton("إغلاق", null)
                            .show();
                }
            });
        }).start();
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
            if (status != 200 || !json.optBoolean("excel_metadata_v5", false) ||
                    !json.optBoolean("excel_name_en_v5_2", false) ||
                    !json.optBoolean("central_count_v6", false)) {
                throw new IOException("لازم تحديث server.py إلى V6 لدعم الجرد الموحد");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String lookupItemCode(String barcode) throws Exception {
        return lookupBarcodeDetails(barcode).optString("item_code", "").trim();
    }

    private JSONObject lookupBarcodeDetails(String barcode) throws Exception {
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
            return json;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject uploadPhotoToServer(
            String barcode, String itemCode, String location,
            String quantity, Uri photoUri) throws Exception {

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
                if (pendingOperationId == null) {
                    pendingOperationId = UUID.randomUUID().toString();
                }
                writeFormField(out, boundary, "quantity", quantity);
                writeFormField(out, boundary, "device_type", "Android");
                writeFormField(out, boundary, "operation_id", pendingOperationId);

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
            if (!json.has("name_ar") || !json.has("name_en") ||
                    !json.has("basic_unit")) {
                throw new IOException("السيرفر لا يرجع NAMEA / NAMEE / الوحدة؛ راجع إصدار V5.2");
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

    // Called ONLY after the server confirms that photo + shelf were committed.
    // We never save the photo to the phone's Gallery. It is temporarily held
    // in a private cache file until upload succeeds.
    private void finishSuccessfulSave(String excelMessage) {
        boolean deleted = deletePendingPhoto();
        saving = false;
        hideKeyboard();
        Toast.makeText(this,
                "تم حفظ الصورة والموقع على Oracle. " + excelMessage +
                        (deleted ? "\nتم حذف الصورة المؤقتة من التابلت."
                                : "\nتنبيه: لم يتم حذف الصورة المؤقتة؛ امسح بيانات التخزين المؤقت للتطبيق لاحقًا."),
                Toast.LENGTH_LONG).show();
        showCamera();
    }

    /**
     * Remove only an image captured by this app.  When a network error
     * happens this method is NOT called: the user can retry the upload.
     * @return true if the temporary image has been removed (or was absent).
     */
    private boolean deletePendingPhoto() {
        Uri uri = pendingUri;
        pendingUri = null;
        if (uri == null) return true;

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String rawPath = uri.getPath();
            if (rawPath == null) return false;
            try {
                File photo = new File(rawPath).getCanonicalFile();
                File directory = new File(getCacheDir(), "pending_uploads").getCanonicalFile();
                if (!directory.equals(photo.getParentFile()) ||
                        !photo.getName().startsWith("burhan_pending_") ||
                        !photo.getName().endsWith(".jpg")) {
                    Log.w("BURHAN_CAMERA", "Refusing to delete unexpected file URI");
                    return false;
                }
                return !photo.exists() || photo.delete();
            } catch (IOException e) {
                Log.w("BURHAN_CAMERA", "Failed to delete pending JPEG", e);
                return false;
            }
        }
        // Compatibility with a capture left pending by the older V5 builds.
        // Do not delete arbitrary content URIs (e.g. a user's gallery image).
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            Log.w("BURHAN_CAMERA", "Older MediaStore photo still exists; manual cleanup may be required");
        }
        return false;
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
