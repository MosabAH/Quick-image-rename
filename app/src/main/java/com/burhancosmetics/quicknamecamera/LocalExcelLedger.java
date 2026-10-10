package com.burhancosmetics.quicknamecamera;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Safe local journal + self-contained Office Open XML (.xlsx) writer.
 * No third-party Android XLSX libraries needed.
 *
 * Every successful Oracle save is first journaled to SQLite. The .xlsx file
 * is an export snapshot of that journal. If Excel writing is interrupted,
 * re-exporting restores the whole file from SQLite.
 */
public final class LocalExcelLedger extends SQLiteOpenHelper {
    private static final String DB_NAME = "burhan_stock_count_log.db";
    private static final int DB_VERSION = 2;
    private static final Object EXPORT_LOCK = new Object();
    private final Context context;

    public LocalExcelLedger(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE count_entries (" +
                "id TEXT PRIMARY KEY, " +
                "item_code TEXT NOT NULL, " +
                "name_ar TEXT NOT NULL, " +
                "name_en TEXT NOT NULL DEFAULT '', " +
                "basic_unit TEXT NOT NULL, " +
                "quantity TEXT NOT NULL, " +
                "created_at TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Upgrade existing V5/V5.1 journal IN PLACE; do not delete counted items.
        if (oldVersion == 1 && newVersion >= 2) {
            db.execSQL("ALTER TABLE count_entries " +
                    "ADD COLUMN name_en TEXT NOT NULL DEFAULT ''");
            oldVersion = 2;
        }
        if (oldVersion != newVersion) {
            throw new IllegalStateException("Unexpected journal database version");
        }
    }

    public String recordSuccessfulSave(String itemCode, String nameAr,
                                       String nameEn, String unit, String quantity) {
        String id = UUID.randomUUID().toString();
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("item_code", itemCode);
        values.put("name_ar", nameAr == null ? "" : nameAr);
        values.put("name_en", nameEn == null ? "" : nameEn);
        values.put("basic_unit", unit == null ? "" : unit);
        values.put("quantity", new BigDecimal(quantity).toPlainString());
        values.put("created_at", date);
        if (getWritableDatabase().insertOrThrow("count_entries", null, values) <= 0) {
            throw new IllegalStateException("تعذر تسجيل السطر محليًا");
        }
        return id;
    }

    public int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM count_entries", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /**
     * Recreates one valid XLSX file containing ALL records, not just the latest.
     * The URI must have been granted with ACTION_CREATE_DOCUMENT by the user.
     */
    public int exportTo(Uri destination) throws IOException {
        synchronized (EXPORT_LOCK) {
            if (destination == null) throw new IOException("ملف Excel غير محدد");
        // 'wt' explicitly truncates when supported; never append ZIP entries to XLSX.
        try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException("تعذر فتح ملف Excel للكتابة");
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                writeEntry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                        "</Types>");
                writeEntry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                        "</Relationships>");
                writeEntry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                        "</Relationships>");
                writeEntry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                        "<sheets><sheet name=\"جرد الأصناف\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                writeEntry(zip, "xl/styles.xml", stylesXml());

                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                        "<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\">" +
                        "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>" +
                        "</sheetView></sheetViews>" +
                        "<cols><col min=\"1\" max=\"1\" width=\"23\" customWidth=\"1\"/>" +
                        "<col min=\"2\" max=\"2\" width=\"48\" customWidth=\"1\"/>" +
                        "<col min=\"3\" max=\"3\" width=\"48\" customWidth=\"1\"/>" +
                        "<col min=\"4\" max=\"4\" width=\"20\" customWidth=\"1\"/>" +
                        "<col min=\"5\" max=\"5\" width=\"16\" customWidth=\"1\"/></cols>" +
                        "<sheetData><row r=\"1\" ht=\"27\" customHeight=\"1\">");
                textCell(zip, "A1", "كود الصنف", 1);
                textCell(zip, "B1", "اسم الصنف عربي (NAMEA)", 1);
                textCell(zip, "C1", "اسم الصنف إنجليزي (NAMEE)", 1);
                textCell(zip, "D1", "الوحدة", 1);
                textCell(zip, "E1", "الكمية", 1);
                write(zip, "</row>");
                int rowNumber = 1;
                try (Cursor cursor = getReadableDatabase().rawQuery(
                        "SELECT item_code,name_ar,name_en,basic_unit,quantity " +
                                "FROM count_entries ORDER BY created_at, rowid", null)) {
                    while (cursor.moveToNext()) {
                        rowNumber++;
                        // Excel sheet's maximum is 1,048,576 rows, including header.
                        if (rowNumber > 1048576) {
                            throw new IOException("عدد السجلات تجاوز سعة ورقة Excel");
                        }
                        int style = rowNumber % 2 == 0 ? 0 : 2;
                        write(zip, "<row r=\"" + rowNumber + "\">");
                        textCell(zip, "A" + rowNumber, cursor.getString(0), style);
                        textCell(zip, "B" + rowNumber, cursor.getString(1), style);
                        textCell(zip, "C" + rowNumber, cursor.getString(2), style);
                        textCell(zip, "D" + rowNumber, cursor.getString(3), style);
                        numericCell(zip, "E" + rowNumber, cursor.getString(4),
                                rowNumber % 2 == 0 ? 3 : 4);
                        write(zip, "</row>");
                    }
                }
                write(zip, "</sheetData><autoFilter ref=\"A1:E" + rowNumber + "\"/>" +
                        "</worksheet>");
                zip.closeEntry();
                zip.finish();
                return rowNumber - 1;
            }
        }
        }
    }

    private static void textCell(ZipOutputStream zip, String address,
                                 String value, int style) throws IOException {
        write(zip, "<c r=\"" + address + "\" s=\"" + style +
                "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
                escapeXml(value == null ? "" : value) + "</t></is></c>");
    }

    private static void numericCell(ZipOutputStream zip, String address,
                                    String quantity, int style) throws IOException {
        // Validate before putting it into XML as a number (not a formula).
        String number = new BigDecimal(quantity).toPlainString();
        write(zip, "<c r=\"" + address + "\" s=\"" + style +
                "\"><v>" + number + "</v></c>");
    }

    private static String escapeXml(String text) {
        StringBuilder result = new StringBuilder(text.length() + 16);
        for (int offset = 0; offset < text.length();) {
            int ch = text.codePointAt(offset);
            offset += Character.charCount(ch);
            if (ch == '&') result.append("&amp;");
            else if (ch == '<') result.append("&lt;");
            else if (ch == '>') result.append("&gt;");
            else if (ch == '\"') result.append("&quot;");
            else if (ch == '\'') result.append("&apos;");
            else if (ch == 9 || ch == 10 || ch == 13 ||
                    (ch >= 32 && ch <= 0xD7FF) ||
                    (ch >= 0xE000 && ch <= 0xFFFD) ||
                    (ch >= 0x10000 && ch <= 0x10FFFF)) {
                result.appendCodePoint(ch);
            }
        }
        return result.toString();
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.####\"/></numFmts>" +
                "<fonts count=\"2\">" +
                "<font><sz val=\"11\"/><name val=\"Arial\"/></font>" +
                "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Arial\"/></font>" +
                "</fonts><fills count=\"4\">" +
                "<fill><patternFill patternType=\"none\"/></fill>" +
                "<fill><patternFill patternType=\"gray125\"/></fill>" +
                "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF19334C\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
                "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF0F5FA\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
                "</fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"5\">" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/>" +
                "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
                "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\"/>" +
                "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>";
    }

    private static void writeEntry(ZipOutputStream zip, String name, String contents)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        write(zip, contents);
        zip.closeEntry();
    }

    private static void write(ZipOutputStream zip, String text) throws IOException {
        zip.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
