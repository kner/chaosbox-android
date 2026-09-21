package local.sshcopy;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditorActivity extends Activity {
    private static final int PICK_IMAGE = 10;
    private static final int STORAGE_PERMISSION = 11;
    private static final int LIMIT = 25 * 1024;

    private boolean storageRequested;
    private boolean searchMode, busy, indexInitialized;
    private android.widget.ImageButton search;
    private String[] beforeSearch;
    private String beforeSearchLabel;
    private android.net.Uri displayedImage;
    private android.net.Uri beforeSearchImage;
    private Uri selectedUri;
    private String selectedName;
    private ImageView preview;
    private TextView selectedLabel;
    private EditText box;
    private android.widget.AutoCompleteTextView category;
    private EditText amount, alias, packageField;
    private Button decreaseAmount, increaseAmount;
    private android.widget.AutoCompleteTextView device;
    private final List<DeviceRecord> deviceRecords = new ArrayList<>();
    private String openedBoxName;
    private android.widget.ImageButton openJson;
    private android.widget.ImageButton choose, setup;
    private EditText comment;
    private android.widget.ImageButton save;
    private ProgressBar progress;
    private final List<String> categories = new ArrayList<>();
    private String createdAt;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        loadCategories();
        setContentView(buildUi());
    }

    private boolean hasStorageAccess() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasStorageAccess()) {
            refreshCategories();
            initializeIndex();
        } else if (!storageRequested) {
            storageRequested = true;
            Toast.makeText(this, "Für ChaosBox bitte den Zugriff auf alle Dateien erlauben.", Toast.LENGTH_LONG).show();
            requestStorageAccess();
        }
    }

    private void refreshCategories() {
        String previous = category.getText().toString();
        loadCategories();
        if (!searchMode) category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, categories));
        selectCategory(previous);
    }

    private View buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(247, 248, 252));

        TextView title = text("ChaosBox", 26, Color.rgb(26, 31, 44));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());
        root.addView(text("(c) kner.at", 12, Color.DKGRAY), matchWrap());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        choose = icon(actions, android.R.drawable.ic_menu_gallery, "JPG auswählen", v -> chooseImage());
        openJson = icon(actions, android.R.drawable.ic_menu_agenda, "JSON öffnen", v -> openJson());
        search = icon(actions, android.R.drawable.ic_menu_search, "Suchen", v -> searchClicked());
        save = icon(actions, android.R.drawable.ic_menu_save, "Daten speichern", v -> saveImage());
        save.setEnabled(true);
        setup = icon(actions, android.R.drawable.ic_menu_preferences, "Setup bearbeiten", v -> editSetup());
        root.addView(actions, matchWrap());

        selectedLabel = text("Noch kein Bild ausgewählt", 14, Color.DKGRAY);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.setMargins(0, dp(8), 0, dp(12));
        root.addView(selectedLabel, labelParams);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(Color.LTGRAY);

        box = field(root, "Box");
        root.addView(text("Anzahl", 13, Color.DKGRAY), matchWrap());
        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        decreaseAmount = new Button(this);
        decreaseAmount.setText("−");
        decreaseAmount.setContentDescription("Anzahl um 1 verringern");
        decreaseAmount.setOnClickListener(v -> changeAmount(-1));
        amountRow.addView(decreaseAmount, new LinearLayout.LayoutParams(dp(56), dp(48)));
        amount = new EditText(this);
        amount.setSingleLine(true);
        amountRow.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        increaseAmount = new Button(this);
        increaseAmount.setText("+");
        increaseAmount.setContentDescription("Anzahl um 1 erhöhen");
        increaseAmount.setOnClickListener(v -> changeAmount(1));
        amountRow.addView(increaseAmount, new LinearLayout.LayoutParams(dp(56), dp(48)));
        root.addView(amountRow, matchWrap());
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        amount.setHint("0");
        root.addView(text("Device", 13, Color.DKGRAY), matchWrap());
        device = new android.widget.AutoCompleteTextView(this);
        device.setSingleLine(true);
        device.setHint("Auswählen oder eingeben");
        device.setThreshold(1);
        device.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        device.setOnClickListener(v -> {
            if (!searchMode) {
                refreshDevices();
                device.showDropDown();
            }
        });
        device.setOnItemClickListener((parent, view, position, id) ->
                applyRecord((DeviceRecord) parent.getItemAtPosition(position)));
        root.addView(device, matchWrap());
        alias = field(root, "Alias");
        root.addView(text("Kategorie", 13, Color.DKGRAY), matchWrap());
        category = new android.widget.AutoCompleteTextView(this);
        category.setSingleLine(true);
        category.setHint("Auswählen oder eingeben");
        category.setThreshold(0);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, categories));
        category.setOnClickListener(v -> { if (!searchMode) category.showDropDown(); });
        category.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        root.addView(category, matchWrap());
        comment = field(root, "Kommentar");
        comment.setSingleLine(false);
        comment.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        comment.setGravity(Gravity.TOP | Gravity.START);
        comment.setMinLines(2);
        comment.setMaxLines(5);
        packageField = field(root, "Package");

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(12), 0, 0);
        root.addView(progress, progressParams);

        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(190));
        previewParams.setMargins(0, dp(16), 0, 0);
        root.addView(preview, previewParams);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private android.widget.ImageButton icon(LinearLayout row, int drawable, String label, View.OnClickListener action) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageResource(drawable);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(action);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1));
        return button;
    }

    private EditText field(LinearLayout root, String label) {
        root.addView(text(label, 13, Color.DKGRAY), matchWrap());
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(label);
        root.addView(field, matchWrap());
        return field;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void editSetup() {
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        try {
            try (InputStream ignored = openOrCreateSetupFile()) { }
            File file = new File(Environment.getExternalStorageDirectory(), Config.SETUP);
            EditText input = new EditText(this);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setMinLines(10);
            input.setText(LocalData.read(file));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(input);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("setup.ini bearbeiten").setView(scroll)
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Speichern", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        android.util.AtomicFile target = new android.util.AtomicFile(file);
                        FileOutputStream out = null;
                        try {
                            out = target.startWrite();
                            out.write(input.getText().toString().getBytes(StandardCharsets.UTF_8));
                            target.finishWrite(out);
                            refreshCategories();
                            dialog.dismiss();
                            Toast.makeText(this, "Setup gespeichert", Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            if (out != null) target.failWrite(out);
                            input.setError("Speichern fehlgeschlagen: " + e.getMessage());
                        }
                    }));
            dialog.show();
        } catch (IOException e) {
            Toast.makeText(this, "Setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private List<DataIndex.Entry> rebuildIndex() throws IOException {
        return DataIndex.rebuild(new File(Environment.getExternalStorageDirectory(), "ChaosBox"), file -> {
            String raw = ImageProcessor.readUserComment(new java.io.FileInputStream(file));
            try { return new JSONObject(raw); }
            catch (JSONException e) {
                JSONObject record = new JSONObject();
                record.put("comment", raw);
                return record;
            }
        });
    }

    private void initializeStorage() throws IOException {
        File root = new File(Environment.getExternalStorageDirectory(), "ChaosBox");
        for (String name : new String[]{"JPG", "boxes", "data"}) {
            java.nio.file.Files.createDirectories(new File(root, name).toPath());
        }
        android.content.SharedPreferences prefs = getSharedPreferences("initial-data", MODE_PRIVATE);
        if (!prefs.getBoolean("copied", false)) {
            copyInitialAssets("initial", root);
            if (!prefs.edit().putBoolean("copied", true).commit())
                throw new IOException("Initialisierung konnte nicht bestätigt werden.");
        }
    }

    private void copyInitialAssets(String assetPath, File target) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children != null && children.length > 0) {
            java.nio.file.Files.createDirectories(target.toPath());
            for (String child : children) copyInitialAssets(assetPath + "/" + child, new File(target, child));
            return;
        }
        if (target.exists()) return;
        InputStream asset;
        try { asset = getAssets().open(assetPath); }
        catch (java.io.FileNotFoundException e) { return; } // Empty asset directories are not packaged.
        try (InputStream input = asset) {
            java.nio.file.Files.createDirectories(target.getParentFile().toPath());
            java.nio.file.Path temporary = java.nio.file.Files.createTempFile(target.getParentFile().toPath(), ".initial-", ".tmp");
            try {
                try (OutputStream output = java.nio.file.Files.newOutputStream(temporary)) { copy(input, output); }
                // No replacement: existing user files always take precedence.
                java.nio.file.Files.move(temporary, target.toPath());
            } finally { java.nio.file.Files.deleteIfExists(temporary); }
        }
    }

    private void initializeIndex() {
        if (indexInitialized) return;
        indexInitialized = true;
        setBusy(true);
        worker.execute(() -> {
            try {
                initializeStorage();
                rebuildIndex();
                runOnUiThread(() -> { refreshCategories(); setBusy(false); });
            } catch (IOException e) {
                runOnUiThread(() -> { setBusy(false); showIndexError(e.getMessage()); });
            }
        });
    }

    private void showIndexError(String message) {
        new android.app.AlertDialog.Builder(this).setTitle("Datendatei nicht aktualisiert")
                .setMessage(message).setPositiveButton("OK", null).show();
    }

    private EditText[] inputFields() {
        return new EditText[]{box, amount, device, alias, category, comment, packageField};
    }

    private void showImage(Uri image) {
        displayedImage = image;
        if (image == null) preview.setImageDrawable(null);
        else preview.setImageURI(image);
    }

    private void setSearchMode(boolean enabled) {
        searchMode = enabled;
        device.dismissDropDown();
        category.dismissDropDown();
        device.setAdapter(null);
        category.setAdapter(null);
        int arrow = enabled ? 0 : android.R.drawable.arrow_down_float;
        device.setCompoundDrawablesWithIntrinsicBounds(0, 0, arrow, 0);
        category.setCompoundDrawablesWithIntrinsicBounds(0, 0, arrow, 0);
        amount.setInputType(enabled ? android.text.InputType.TYPE_CLASS_TEXT
                : android.text.InputType.TYPE_CLASS_NUMBER);
        device.setHint(enabled ? "Regulärer Ausdruck" : "Auswählen oder eingeben");
        category.setHint(enabled ? "Regulärer Ausdruck" : "Auswählen oder eingeben");
        search.setContentDescription(enabled ? "Suche ausführen" : "Suchen");
        search.setTooltipText(enabled ? "Suche ausführen" : "Suchen");
        if (!enabled) { refreshDevices(); refreshCategories(); }
        for (EditText field : inputFields()) field.setError(null);
        setBusy(false);
    }

    private void searchClicked() {
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        if (!searchMode) {
            EditText[] fields = inputFields();
            beforeSearch = new String[fields.length];
            for (int i = 0; i < fields.length; i++) beforeSearch[i] = fields[i].getText().toString();
            beforeSearchLabel = selectedLabel.getText().toString();
            beforeSearchImage = displayedImage;
            setSearchMode(true);
            for (EditText field : fields) field.setText("");
            showImage(null);
            selectedLabel.setText("Suche: Regex eingeben, dann Lupe. Leere Felder beliebig; Zurück bricht ab.");
            box.requestFocus();
            return;
        }
        String[] keys = {"box", "anzahl", "device", "alias", "category", "comment", "package"};
        EditText[] fields = inputFields();
        java.util.Map<String, java.util.regex.Pattern> patterns = new java.util.LinkedHashMap<>();
        for (int i = 0; i < fields.length; i++) {
            fields[i].setError(null);
            try {
                patterns.putAll(DataIndex.compile(java.util.Collections.singletonMap(keys[i], fields[i].getText().toString())));
            } catch (java.util.regex.PatternSyntaxException e) {
                fields[i].setError("Ungültiger regulärer Ausdruck: " + e.getDescription());
                fields[i].requestFocus();
                return;
            }
        }
        setBusy(true);
        worker.execute(() -> {
            try {
                List<DataIndex.Entry> entries = rebuildIndex();
                List<DataIndex.Entry> hits = DataIndex.search(entries, patterns);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (hits.isEmpty()) {
                        selectedLabel.setText("Keine Treffer. Suchfelder ändern und erneut auf die Lupe tippen.");
                    } else if (hits.size() == 1) {
                        selectSearchHit(hits.get(0), entries);
                    } else {
                        String[] labels = new String[hits.size()];
                        for (int i = 0; i < hits.size(); i++) {
                            JSONObject data = hits.get(i).data;
                            labels[i] = data.optString("box", "") + " · " + data.optString("device", "")
                                    + " · " + data.optString("alias", "") + " · " + data.optString("path", "");
                        }
                        new android.app.AlertDialog.Builder(this).setTitle(hits.size() + " Treffer")
                                .setItems(labels, (dialog, which) -> selectSearchHit(hits.get(which), entries))
                                .setNegativeButton("Weitersuchen", null).show();
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() -> { setBusy(false); showIndexError(e.getMessage()); });
            }
        });
    }

    private void selectSearchHit(DataIndex.Entry hit, List<DataIndex.Entry> entries) {
        openedBoxName = hit.data.optString("box", "");
        deviceRecords.clear();
        DeviceRecord selected = null;
        for (DataIndex.Entry entry : entries) {
            if (entry == hit || (!openedBoxName.isEmpty() && openedBoxName.equals(entry.data.optString("box", "")))) {
                DeviceRecord record = new DeviceRecord(entry.data);
                record.source = entry.source;
                record.image = entry.image;
                record.previewFile = DataIndex.imageFor(entry, entries);
                deviceRecords.add(record);
                if (entry == hit) selected = record;
            }
        }
        setSearchMode(false);
        applyRecord(selected);
        beforeSearch = null;
    }

    @Override public void onBackPressed() {
        if (searchMode) {
            if (busy) return;
            setSearchMode(false);
            EditText[] fields = inputFields();
            for (int i = 0; i < fields.length; i++) {
                if (fields[i] instanceof android.widget.AutoCompleteTextView)
                    ((android.widget.AutoCompleteTextView) fields[i]).setText(beforeSearch[i], false);
                else fields[i].setText(beforeSearch[i]);
            }
            selectedLabel.setText(beforeSearchLabel);
            showImage(beforeSearchImage);
            beforeSearch = null;
            return;
        }
        super.onBackPressed();
    }

    private void refreshDevices() {
        if (searchMode) return;
        List<DeviceRecord> sortedDevices = new ArrayList<>(deviceRecords);
        java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.GERMAN);
        collator.setStrength(java.text.Collator.SECONDARY);
        sortedDevices.sort((left, right) -> collator.compare(left.toString(), right.toString()));
        device.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, sortedDevices));
    }

    private void openJson() {
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        final String name = box.getText().toString().trim();
        try { BoxRecords.validateName(name); box.setError(null); }
        catch (IOException e) { box.setError(e.getMessage()); box.requestFocus(); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                List<JSONObject> records = BoxRecords.load(
                        new File(Environment.getExternalStorageDirectory(), Config.BOXES), name);
                List<DeviceRecord> choices = new ArrayList<>();
                for (JSONObject record : records) choices.add(new DeviceRecord(record));
                runOnUiThread(() -> {
                    selectedUri = null;
                    selectedName = null;
                    showImage(null);
                    openedBoxName = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                    deviceRecords.clear();
                    deviceRecords.addAll(choices);
                    refreshDevices();
                    applyRecord(choices.get(0));
                    selectedLabel.setText("Geöffnet: " + openedBoxName + ".json — " + choices.size() + " Datensätze");
                    setBusy(false);
                    if (choices.size() > 1) {
                        device.requestFocus();
                        device.showDropDown();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, "JSON öffnen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyRecord(DeviceRecord record) {
        MetadataData values = record.values;
        createdAt = values.created;
        box.setText(values.box.isEmpty() ? openedBoxName : values.box);
        amount.setText(Integer.toString(values.amount));
        amount.setError(null);
        device.setText(values.device, false);
        alias.setText(values.alias);
        packageField.setText(values.packageName);
        selectCategory(values.category);
        comment.setText(values.comment);
        if (record.source != null) {
            selectedUri = record.image ? Uri.fromFile(record.source) : null;
            selectedName = record.image ? record.source.getName() : null;
            showImage(record.previewFile == null ? null : Uri.fromFile(record.previewFile));
            selectedLabel.setText("Geladen: " + record.source.getName());
        }
    }

    private static final class DeviceRecord {
        final MetadataData values;
        File source, previewFile;
        boolean image;
        DeviceRecord(JSONObject json) { values = MetadataData.parse(json.toString()); }
        @Override public String toString() {
            return values.device.isEmpty() ? "(ohne Device)" : values.device;
        }
    }

    private void uploadAfterSave(String savedMessage) {
        runOnUiThread(() -> {
            setBusy(false);
            selectedLabel.setText(savedMessage + " — Upload im Hintergrund");
            try {
                startForegroundService(new Intent(this, UploadService.class));
            } catch (RuntimeException e) {
                selectedLabel.setText(savedMessage + " — Upload konnte nicht gestartet werden");
                Toast.makeText(this, "Lokal gespeichert; Upload: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private File existingJpg(Uri uri) throws IOException {
        File candidate = null;
        if ("file".equals(uri.getScheme())) candidate = new File(uri.getPath());
        else if ("com.android.externalstorage.documents".equals(uri.getAuthority())
                && android.provider.DocumentsContract.isDocumentUri(this, uri)) {
            String id = android.provider.DocumentsContract.getDocumentId(uri);
            if (id.startsWith("primary:"))
                candidate = new File(Environment.getExternalStorageDirectory(), id.substring(8));
        }
        if (candidate == null && "content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{MediaStore.Images.Media.DATA}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                    candidate = new File(cursor.getString(0));
            } catch (RuntimeException ignored) { }
        }
        if (candidate == null) return null;
        File root = new File(Environment.getExternalStorageDirectory(), Config.SOURCE).getCanonicalFile();
        File file = candidate.getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || file.equals(root)) return null;
        if (!java.nio.file.Files.isRegularFile(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("JPG-Quelldatei ist nicht verfügbar.");
        return file;
    }

    private Uri overwriteJpg(File file, byte[] data) throws IOException {
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(file.getParentFile().toPath(), ".save-", ".tmp");
        try {
            java.nio.file.Files.write(temporary, data);
            // SFTP compares whole seconds; consecutive saves must remain distinguishable.
            long modified = Math.max(System.currentTimeMillis(), (file.lastModified() / 1000 + 1) * 1000);
            java.nio.file.Files.setLastModifiedTime(temporary, java.nio.file.attribute.FileTime.fromMillis(modified));
            java.nio.file.Files.move(temporary, file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
        android.media.MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()},
                new String[]{"image/jpeg"}, null);
        return Uri.fromFile(file);
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/jpeg");
        startActivityForResult(intent, PICK_IMAGE);
    }

    private void loadCategories() {
        categories.clear();
        try (InputStream in = openSetupFileSafely()) {
            String content;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                content = out.toString("UTF-8");
            }
            boolean inCategory = false;
            for (String raw : content.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    inCategory = line.equalsIgnoreCase("[Kategorie]");
                } else if (inCategory && !line.isEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
                    categories.add(line);
                }
            }
        } catch (Exception ignored) { }
        if (categories.isEmpty()) categories.add("Ohne Kategorie");
    }

    private InputStream openSetupFileSafely() throws IOException {
        try {
            return openOrCreateSetupFile();
        } catch (Exception ignored) {
            // Use defaults until shared-storage access has been granted.
            return getAssets().open("setup.ini");
        }
    }

    private InputStream openOrCreateSetupFile() throws IOException {
        File dir = new File(Environment.getExternalStorageDirectory(), "ChaosBox/Setup");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Setup-Ordner konnte nicht erstellt werden");
        File setup = new File(dir, "setup.ini");
        if (!setup.exists()) {
            try (InputStream asset = getAssets().open("setup.ini"); OutputStream output = new FileOutputStream(setup)) {
                copy(asset, output);
            }
        }
        return new java.io.FileInputStream(setup);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096]; int n;
        while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            deviceRecords.clear();
            openedBoxName = null;
            refreshDevices();
            selectedUri = data.getData();
            selectedName = queryName(selectedUri);
            selectedLabel.setText(selectedName);
            showImage(selectedUri);
            setBusy(true);
            box.setText("");
            comment.setText("");
            category.setText("", false);
            amount.setText("");
            amount.setError(null);
            device.setText("");
            alias.setText("");
            packageField.setText("");
            createdAt = null;
            Uri uri = selectedUri;
            worker.execute(() -> {
                try {
                    String existing = ImageProcessor.readUserComment(getContentResolver().openInputStream(uri));
                    MetadataData values = MetadataData.parse(existing);
                    runOnUiThread(() -> {
                        if (uri.equals(selectedUri)) {
                            createdAt = values.created;
                            box.setText(values.box);
                            amount.setText(Integer.toString(values.amount));
                            device.setText(values.device);
                            alias.setText(values.alias);
                            packageField.setText(values.packageName);
                            selectCategory(values.category);
                            comment.setText(values.comment);
                            comment.setSelection(comment.length());
                            setBusy(false);
                        }
                    });
                } catch (IOException ignored) {
                    runOnUiThread(() -> {
                        if (uri.equals(selectedUri)) {
                            setBusy(false);
                        }
                    });
                }
            });
        }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return "bild.jpg";
    }

    private void saveImage() {
        if (searchMode || busy) return;
        if (!hasStorageAccess()) {
            requestStorageAccess();
            return;
        }
        final int number;
        try {
            number = InputValues.amount(amount.getText().toString());
            amount.setError(null);
        } catch (NumberFormatException e) {
            amount.setError("Bitte eine gültige Ganzzahl eingeben (0 bis 2147483647).");
            amount.requestFocus();
            return;
        }
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (createdAt == null || createdAt.isEmpty()) createdAt = now;
        String selectedCategory = category.getText().toString();
        String userComment = MetadataData.create(createdAt, now, box.getText().toString(), selectedCategory, comment.getText().toString(), number, device.getText().toString(), alias.getText().toString(), packageField.getText().toString());
        final String boxName = box.getText().toString();
        if (selectedUri == null) {
            try { BoxRecords.validateName(boxName); box.setError(null); }
            catch (IOException e) { box.setError(e.getMessage()); box.requestFocus(); return; }
        }
        final Uri imageUri = selectedUri;
        final String imageName = selectedName;
        setBusy(true);
        worker.execute(() -> {
            try {
                if (imageUri == null) {
                    File saved = BoxRecords.save(new File(Environment.getExternalStorageDirectory(), Config.BOXES), boxName, userComment);
                    uploadAfterSave("Gespeichert: ChaosBox/boxes/" + saved.getName());
                    return;
                }
                File existing = existingJpg(imageUri);
                byte[] result;
                Uri saved;
                if (existing != null) {
                    result = JpegComments.update(java.nio.file.Files.readAllBytes(existing.toPath()),
                            userComment, ImageProcessor.exifSegment(userComment));
                    saved = overwriteJpg(existing, result);
                } else {
                    result = ImageProcessor.process(getContentResolver().openInputStream(imageUri), userComment, LIMIT);
                    saved = writeOutput(result, outputName(imageName));
                }
                runOnUiThread(() -> {
                    selectedUri = saved;
                    selectedName = saved.getLastPathSegment();
                    showImage(null);
                    showImage(saved);
                });
                uploadAfterSave("Gespeichert: ChaosBox/JPG/" + saved.getLastPathSegment()
                        + " (" + result.length / 1024 + " kB)");
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            refreshCategories();
            initializeIndex();
        }
    }

    private void changeAmount(int delta) {
        if (busy || searchMode) return;
        try {
            int value = Math.max(0, Math.addExact(InputValues.amount(amount.getText().toString()), delta));
            amount.setText(Integer.toString(value));
            amount.setSelection(amount.length());
            amount.setError(null);
        } catch (NumberFormatException | ArithmeticException e) {
            amount.setError("Bitte eine gültige Ganzzahl eingeben (0 bis 2147483647).");
            amount.requestFocus();
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        openJson.setEnabled(!busy && !searchMode);
        search.setEnabled(!busy);
        save.setEnabled(!busy && !searchMode);
        box.setEnabled(!busy);
        category.setEnabled(!busy);
        comment.setEnabled(!busy);
        amount.setEnabled(!busy);
        decreaseAmount.setEnabled(!busy && !searchMode);
        increaseAmount.setEnabled(!busy && !searchMode);
        device.setEnabled(!busy);
        alias.setEnabled(!busy);
        packageField.setEnabled(!busy);
        choose.setEnabled(!busy && !searchMode);
        setup.setEnabled(!busy && !searchMode);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void selectCategory(String value) {
        category.setText(value == null ? "" : value, false);
    }

    private String outputName(String input) {
        int dot = input.lastIndexOf('.');
        if (dot <= 0) return input + "_cb.jpg";
        return input.substring(0, dot) + "_cb" + input.substring(dot);
    }

    private Uri writeOutput(byte[] data, String name) throws IOException {
        File dir = new File(Environment.getExternalStorageDirectory(), "ChaosBox/JPG");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Ordner ChaosBox/JPG konnte nicht erstellt werden");
        String safeName = new File(name).getName();
        File file = new File(dir, safeName);
        int suffix = 1;
        while (!file.createNewFile()) {
            int dot = safeName.lastIndexOf('.');
            String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
            String extension = dot > 0 ? safeName.substring(dot) : "";
            file = new File(dir, stem + "_" + suffix++ + extension);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        } catch (IOException e) {
            file.delete();
            throw e;
        }
        android.media.MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()},
                new String[]{"image/jpeg"}, null);
        return Uri.fromFile(file);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    static final class MetadataData {
        int amount;
        String device = "", alias = "", packageName = "";
        String created = "";
        String box = "";
        String category = "";
        String comment = "";

        static String create(String created, String modified, String box, String category, String comment, int amount, String device, String alias, String packageName) {
            try {
                JSONObject json = new JSONObject();
                json.put("created", created);
                json.put("modified", modified);
                json.put("box", box);
                json.put("anzahl", amount);
                json.put("device", device);
                json.put("alias", alias);
                json.put("package", packageName);
                json.put("category", category);
                json.put("comment", comment);
                return json.toString();
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static MetadataData parse(String raw) {
            MetadataData data = new MetadataData();
            if (raw == null || raw.trim().isEmpty()) return data;
            try {
                JSONObject json = new JSONObject(raw);
                data.created = json.optString("created", "");
                data.box = json.optString("box", "");
                data.amount = Math.max(0, json.optInt("count", json.optInt("anzahl", 0)));
                data.device = json.optString("device", "");
                data.alias = json.optString("alias", "");
                data.packageName = json.isNull("pack")
                        ? json.optString("package", "") : json.optString("pack", "");
                data.category = json.optString("category", "");
                data.comment = json.optString("comment", "");
                return data;
            } catch (JSONException ignored) {
                // Fremde oder ältere Inhalte bleiben als unstrukturierter Kommentar erhalten.
                data.comment = raw;
                return data;
            }
        }
    }

    static final class ImageProcessor {
        static byte[] process(InputStream input, String comment, int maxBytes) throws IOException {
            if (input == null) throw new IOException("Bild konnte nicht geöffnet werden");
            byte[] source;
            try (InputStream in = input; ByteArrayOutputStream all = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) >= 0) all.write(buffer, 0, n);
                source = all.toByteArray();
            }
            int orientation = readOrientation(source);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Keine gültige JPG-Datei");
            int sample = 1;
            while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            if (bitmap == null) throw new IOException("Bild konnte nicht dekodiert werden");
            bitmap = orient(bitmap, orientation);

            byte[] exif = exifSegment(comment);
            if (exif.length + 256 >= maxBytes) { bitmap.recycle(); throw new IOException("Kommentar ist zu lang für 100 kB"); }
            int imageBudget = maxBytes - exif.length;
            byte[] jpeg = null;
            Bitmap current = bitmap;
            for (int resize = 0; resize < 8 && jpeg == null; resize++) {
                for (int quality = 92; quality >= 25; quality -= 5) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    current.compress(Bitmap.CompressFormat.JPEG, quality, out);
                    if (out.size() <= imageBudget) { jpeg = out.toByteArray(); break; }
                }
                if (jpeg == null) {
                    int w = Math.max(160, Math.round(current.getWidth() * 0.82f));
                    int h = Math.max(160, Math.round(current.getHeight() * 0.82f));
                    Bitmap smaller = Bitmap.createScaledBitmap(current, w, h, true);
                    if (current != bitmap) current.recycle();
                    current = smaller;
                }
            }
            if (current != bitmap) current.recycle();
            bitmap.recycle();
            if (jpeg == null) throw new IOException("Bild konnte nicht unter 100 kB verkleinert werden");
            byte[] result = new byte[jpeg.length + exif.length];
            System.arraycopy(jpeg, 0, result, 0, 2);
            System.arraycopy(exif, 0, result, 2, exif.length);
            System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
            return result;
        }

        private static Bitmap orient(Bitmap source, int orientation) {
            Matrix m = new Matrix();
            switch (orientation) {
                case 2: m.setScale(-1, 1); break;
                case 3: m.setRotate(180); break;
                case 4: m.setScale(1, -1); break;
                case 5: m.setRotate(90); m.postScale(-1, 1); break;
                case 6: m.setRotate(90); break;
                case 7: m.setRotate(270); m.postScale(-1, 1); break;
                case 8: m.setRotate(270); break;
                default: return source;
            }
            Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
            if (rotated != source) source.recycle();
            return rotated;
        }

        private static byte[] exifSegment(String value) throws IOException {
            // EXIF undefined encoding identifier followed by explicitly UTF-8 encoded JSON.
            byte[] prefix = new byte[8];
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            byte[] user = new byte[prefix.length + text.length];
            System.arraycopy(prefix, 0, user, 0, prefix.length);
            System.arraycopy(text, 0, user, prefix.length, text.length);
            // IFD0 enthält den Verweis auf das Exif-IFD; dort liegt UserComment (0x9286).
            int exifIfdOffset = 8 + 2 + 12 + 4;
            int userOffset = exifIfdOffset + 2 + 12 + 4;
            int tiffSize = userOffset + user.length;
            if (tiffSize + 8 > 65533) throw new IOException("Kommentar ist zu lang");
            ByteBuffer payload = ByteBuffer.allocate(6 + tiffSize).order(ByteOrder.LITTLE_ENDIAN);
            payload.put(new byte[]{'E','x','i','f',0,0});
            payload.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8);
            payload.putShort((short)1);
            payload.putShort((short)0x8769).putShort((short)4).putInt(1).putInt(exifIfdOffset);
            payload.putInt(0);
            payload.putShort((short)1);
            payload.putShort((short)0x9286).putShort((short)7).putInt(user.length).putInt(userOffset);
            payload.putInt(0).put(user);
            byte[] data = payload.array();
            ByteBuffer segment = ByteBuffer.allocate(data.length + 4).order(ByteOrder.BIG_ENDIAN);
            segment.put((byte)0xff).put((byte)0xe1).putShort((short)(data.length + 2)).put(data);
            return segment.array();
        }

        static String readUserComment(InputStream input) throws IOException {
            if (input == null) return "";
            byte[] source;
            try (InputStream in = input; ByteArrayOutputStream all = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) >= 0) all.write(buffer, 0, n);
                source = all.toByteArray();
            }
            try {
                int app1 = findExifTiff(source);
                if (app1 < 0) return "";
                ByteOrder order = source[app1] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                ByteBuffer b = ByteBuffer.wrap(source).order(order);
                int ifd0 = app1 + b.getInt(app1 + 4);
                int direct = findTagEntry(b, ifd0, 0x9286);
                if (direct >= 0) return decodeUserComment(source, b, app1, direct);
                int exifPointer = findTagEntry(b, ifd0, 0x8769);
                if (exifPointer < 0) return "";
                int exifIfd = app1 + b.getInt(exifPointer + 8);
                int userEntry = findTagEntry(b, exifIfd, 0x9286);
                return userEntry < 0 ? "" : decodeUserComment(source, b, app1, userEntry);
            } catch (Exception ignored) {
                return "";
            }
        }

        private static int findExifTiff(byte[] jpg) {
            int p = 2;
            while (p + 10 < jpg.length && (jpg[p] & 0xff) == 0xff) {
                int marker = jpg[p + 1] & 0xff;
                int length = ((jpg[p + 2] & 0xff) << 8) | (jpg[p + 3] & 0xff);
                if (marker == 0xe1 && length >= 14 && p + 2 + length <= jpg.length &&
                        jpg[p + 4] == 'E' && jpg[p + 5] == 'x' && jpg[p + 6] == 'i' && jpg[p + 7] == 'f') {
                    return p + 10;
                }
                if (length < 2) break;
                p += 2 + length;
            }
            return -1;
        }

        private static int findTagEntry(ByteBuffer b, int ifd, int tag) {
            int count = b.getShort(ifd) & 0xffff;
            for (int i = 0; i < count; i++) {
                int entry = ifd + 2 + i * 12;
                if ((b.getShort(entry) & 0xffff) == tag) return entry;
            }
            return -1;
        }

        private static String decodeUserComment(byte[] source, ByteBuffer b, int tiff, int entry) {
            int length = b.getInt(entry + 4);
            int start = length <= 4 ? entry + 8 : tiff + b.getInt(entry + 8);
            if (length <= 0 || start < 0 || start + length > source.length) return "";
            if (length >= 8) {
                String marker = new String(source, start, 8, StandardCharsets.US_ASCII);
                if (marker.equals(new String(new byte[8], StandardCharsets.US_ASCII))) {
                    return trimNulls(new String(source, start + 8, length - 8, StandardCharsets.UTF_8));
                }
                if (marker.startsWith("ASCII")) {
                    return trimNulls(new String(source, start + 8, length - 8, StandardCharsets.US_ASCII));
                }
                if (marker.startsWith("UNICODE")) {
                    return trimNulls(new String(
                            source,
                            start + 8,
                            length - 8,
                            b.order() == ByteOrder.LITTLE_ENDIAN ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE));
                }
            }
            return trimNulls(new String(source, start, length, StandardCharsets.UTF_8));
        }

        private static String trimNulls(String value) {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '\0') end--;
            return value.substring(0, end);
        }

        private static int readOrientation(byte[] jpg) {
            try {
                int p = 2;
                while (p + 4 < jpg.length && (jpg[p] & 0xff) == 0xff) {
                    int marker = jpg[p + 1] & 0xff;
                    int length = ((jpg[p + 2] & 0xff) << 8) | (jpg[p + 3] & 0xff);
                    if (marker == 0xe1 && length >= 14 && p + 2 + length <= jpg.length &&
                            jpg[p + 4] == 'E' && jpg[p + 5] == 'x' && jpg[p + 6] == 'i' && jpg[p + 7] == 'f') {
                        int t = p + 10;
                        boolean little = jpg[t] == 'I';
                        ByteOrder order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                        ByteBuffer b = ByteBuffer.wrap(jpg).order(order);
                        int ifd = t + b.getInt(t + 4);
                        int count = b.getShort(ifd) & 0xffff;
                        for (int i = 0; i < count; i++) {
                            int e = ifd + 2 + i * 12;
                            if ((b.getShort(e) & 0xffff) == 0x0112) return b.getShort(e + 8) & 0xffff;
                        }
                    }
                    if (length < 2) break;
                    p += 2 + length;
                }
            } catch (Exception ignored) { }
            return 1;
        }
    }
}
