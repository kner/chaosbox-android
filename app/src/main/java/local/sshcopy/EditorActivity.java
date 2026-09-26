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
import java.io.FileInputStream;
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
    private static final int PICK_JSON = 12;

    private AppSettings.Selection selection;
    private TextView profileTitle;
    private final TextView[] fieldTitles = new TextView[7];
    private final View[] fieldViews = new View[7];
    private boolean storageRequested;
    private boolean searchMode, busy, indexInitialized;
    private android.widget.ImageButton search;
    private String[] beforeSearch;
    private String[] lastSearch;
    private String beforeSearchLabel;
    private android.net.Uri displayedImage;
    private android.net.Uri beforeSearchImage;
    private Uri selectedUri;
    private final List<Uri> selectedImages = new ArrayList<>();
    private ImageView preview;
    private android.app.Dialog fullImageDialog;
    private TextView selectedLabel;
    private EditText box;
    private android.widget.AutoCompleteTextView category;
    private EditText amount, alias, packageField;
    private Button decreaseAmount, increaseAmount, synchronize;
    private android.widget.ImageButton newEntry, repeatSearch;
    private UploadFiles activeUpload;
    private android.app.AlertDialog uploadDialog;
    private android.widget.AutoCompleteTextView device;
    private final List<DeviceRecord> deviceRecords = new ArrayList<>();
    private String openedBoxName;
    private DeviceRecord activeRecord;
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
        try {
            try (InputStream ignored = openSetupFileSafely()) { /* Create or migrate setup.ini. */ }
            selection = AppSettings.load(this);
            loadCategories();
            setContentView(buildUi());
            applyProfileLabels();
        } catch (IOException e) {
            Toast.makeText(this, "Setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
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
        if (selection == null) return;
        if (hasStorageAccess()) {
            if (!busy) reloadProfile();
            refreshCategories();
            initializeIndex();
        } else if (!storageRequested) {
            storageRequested = true;
            Toast.makeText(this, "Please allow access to all files for ChaosBox.", Toast.LENGTH_LONG).show();
            requestStorageAccess();
        }
    }

    private void refreshCategories() {
        String previous = category.getText().toString();
        loadCategories();
        if (!searchMode) category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, categories));
        selectCategory(previous);
    }

    private void applyProfileLabels() {
        setTitle(selection.profile.title);
        profileTitle.setText(selection.profile.title + " ▾");
        profileTitle.setContentDescription(selection.profile.title + ", Select app profile");
        EditText[] fields = inputFields();
        for (int i = 0; i < fields.length; i++) {
            boolean visible = selection.profile.visible(i);
            fieldTitles[i].setText(selection.profile.fields[i]);
            fieldTitles[i].setVisibility(visible ? View.VISIBLE : View.GONE);
            fieldViews[i].setVisibility(visible ? View.VISIBLE : View.GONE);
            fields[i].setHint(selection.profile.fields[i]);
            fields[i].setContentDescription(selection.profile.fields[i]);
        }
    }

    private void resetProfileForm() {
        lastSearch = null;
        clearForm();
        amount.setText("0");
        indexInitialized = false;
    }

    private void clearForm() {
        searchMode = false;
        beforeSearch = null;
        beforeSearchImage = null;
        beforeSearchLabel = null;
        selectedUri = null;
        selectedImages.clear();
        openedBoxName = null;
        activeRecord = null;
        createdAt = null;
        deviceRecords.clear();
        device.dismissDropDown();
        category.dismissDropDown();
        setSearchMode(false);
        for (EditText field : inputFields()) {
            if (field instanceof android.widget.AutoCompleteTextView)
                ((android.widget.AutoCompleteTextView) field).setText("", false);
            else field.setText("");
            field.setError(null);
        }
        showImage(null);
        selectedLabel.setText("No media or record selected");
        refreshDevices();
        applyProfileLabels();
    }

    private void reloadProfile() {
        try {
            AppSettings.Selection updated = AppSettings.load(this);
            boolean changed = !selection.profile.signature().equals(updated.profile.signature());
            selection = updated;
            if (changed) resetProfileForm();
            else applyProfileLabels();
        } catch (IOException e) {
            Toast.makeText(this, "Setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void chooseProfile() {
        if (busy) return;
        try {
            AppProfiles profiles = AppSettings.configuration(this);
            String[] titles = new String[profiles.profiles.size()];
            int checked = -1;
            for (int i = 0; i < titles.length; i++) {
                AppProfiles.Profile profile = profiles.profiles.get(i);
                titles[i] = profile.title + " (" + profile.id + ")";
                if (profile.id.equals(selection.profile.id)) checked = i;
            }
            new android.app.AlertDialog.Builder(this).setTitle("Select app")
                    .setSingleChoiceItems(titles, checked, (dialog, which) -> {
                        try {
                            AppSettings.Selection next = AppSettings.select(this, profiles.profiles.get(which).id);
                            boolean changed = !selection.profile.signature().equals(next.profile.signature());
                            selection = next;
                            if (changed) {
                                resetProfileForm();
                                refreshCategories();
                                if (hasStorageAccess()) initializeIndex();
                            }
                            dialog.dismiss();
                        } catch (IOException e) {
                            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }).setNegativeButton("Cancel", null).show();
        } catch (IOException e) {
            Toast.makeText(this, "Setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private View buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(247, 248, 252));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        profileTitle = text(selection.profile.title, 26, Color.rgb(26, 31, 44));
        profileTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        profileTitle.setSingleLine(true);
        profileTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        profileTitle.setOnClickListener(v -> chooseProfile());
        profileTitle.setFocusable(true);
        profileTitle.setTooltipText("Select app profile");
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(0, -2, 1);
        profileParams.setMarginEnd(dp(8));
        header.addView(profileTitle, profileParams);
        synchronize = new Button(this);
        synchronize.setText("(c) kner");
        synchronize.setTextSize(12);
        synchronize.setAllCaps(false);
        synchronize.setSingleLine(true);
        synchronize.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_upload, 0, 0, 0);
        synchronize.setCompoundDrawableTintList(synchronize.getTextColors());
        synchronize.setCompoundDrawablePadding(dp(6));
        synchronize.setMinWidth(0);
        synchronize.setMinimumWidth(0);
        synchronize.setPadding(dp(10), 0, dp(10), 0);
        synchronize.setContentDescription("(c) kner – synchronize manually");
        synchronize.setTooltipText("Upload saved files now");
        synchronize.setOnClickListener(v -> synchronizeManually());
        header.addView(synchronize, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        choose = icon(actions, android.R.drawable.ic_menu_gallery, "Select JPG, PNG or MP4", v -> chooseImage());
        openJson = icon(actions, android.R.drawable.ic_menu_agenda, "Open JSON", v -> openJson());
        search = icon(actions, android.R.drawable.ic_menu_search, "Search", v -> searchClicked());
        save = icon(actions, android.R.drawable.ic_menu_save, "Save data", v -> saveImage());
        save.setEnabled(true);
        setup = icon(actions, android.R.drawable.ic_menu_preferences, "Edit setup", v -> editSetup());
        root.addView(actions, matchWrap());

        Button snippets = new Button(this);
        snippets.setText("TXT");
        snippets.setContentDescription("Text snippets");
        snippets.setTooltipText("Copy text snippet");
        snippets.setSingleLine(true);
        snippets.setTextSize(12);
        snippets.setMinWidth(0);
        snippets.setMinimumWidth(0);
        snippets.setPadding(dp(8), 0, dp(8), 0);
        snippets.setOnClickListener(v -> chooseTextSnippet());

        selectedLabel = text("No media selected", 14, Color.DKGRAY);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.setMargins(0, dp(8), 0, dp(12));
        root.addView(selectedLabel, labelParams);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.LTGRAY);
        preview.setContentDescription("Image preview. Double-tap to view full screen.");
        preview.setOnClickListener(v -> showFullImage());
        android.view.GestureDetector imageGestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent event) { return true; }
                    @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                        preview.performClick();
                        return true;
                    }
                });
        preview.setOnTouchListener((view, event) -> {
            if (displayedImage == null || isVideo(displayedImage)) return false;
            imageGestures.onTouchEvent(event);
            return true;
        });

        LinearLayout formActions = new LinearLayout(this);
        formActions.setOrientation(LinearLayout.HORIZONTAL);
        newEntry = icon(formActions, R.drawable.ic_clear_all, "Clear all", v -> {
            if (busy) return;
            clearForm();
            for (EditText field : inputFields()) {
                if (field.isShown()) { field.requestFocus(); break; }
            }
        });
        repeatSearch = icon(formActions, R.drawable.ic_return, "Repeat last search", v -> repeatLastSearch());
        repeatSearch.setEnabled(false);
        formActions.addView(snippets, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(formActions, matchWrap());

        LinearLayout fieldsRow = new LinearLayout(this);
        fieldsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout boxColumn = new LinearLayout(this);
        boxColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(0, -2, 3);
        boxParams.setMarginEnd(dp(8));
        fieldsRow.addView(boxColumn, boxParams);
        LinearLayout amountColumn = new LinearLayout(this);
        amountColumn.setOrientation(LinearLayout.VERTICAL);
        fieldsRow.addView(amountColumn, new LinearLayout.LayoutParams(0, -2, 7));
        root.addView(fieldsRow, matchWrap());
        addFieldTitle(boxColumn, "Box", 0);
        LinearLayout boxRow = new LinearLayout(this);
        boxRow.setOrientation(LinearLayout.HORIZONTAL);
        boxRow.setGravity(Gravity.CENTER_VERTICAL);
        box = new EditText(this);
        box.setSingleLine(true);
        box.setHint("Box");
        boxRow.addView(box, new LinearLayout.LayoutParams(0, -2, 3));
        boxColumn.addView(boxRow, matchWrap());
        fieldViews[0] = boxColumn;
        addFieldTitle(amountColumn, "Quantity", 1);
        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        decreaseAmount = new Button(this);
        decreaseAmount.setText("−");
        decreaseAmount.setContentDescription("Decrease quantity by 1");
        decreaseAmount.setOnClickListener(v -> changeAmount(-1));
        amountRow.addView(decreaseAmount, new LinearLayout.LayoutParams(dp(56), dp(48)));
        amount = new EditText(this);
        amount.setSingleLine(true);
        amountRow.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        increaseAmount = new Button(this);
        increaseAmount.setText("+");
        increaseAmount.setContentDescription("Increase quantity by 1");
        increaseAmount.setOnClickListener(v -> changeAmount(1));
        amountRow.addView(increaseAmount, new LinearLayout.LayoutParams(dp(56), dp(48)));
        amountColumn.addView(amountRow, matchWrap());
        fieldViews[1] = amountColumn;
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        amount.setHint("0");
        addFieldTitle(root, "Device", 2);
        device = new android.widget.AutoCompleteTextView(this);
        device.setSingleLine(true);
        device.setHint("Select or enter");
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
        fieldViews[2] = device;
        alias = field(root, "Alias", 3);
        addFieldTitle(root, "Category", 4);
        category = new android.widget.AutoCompleteTextView(this);
        category.setSingleLine(true);
        category.setHint("Select or enter");
        category.setThreshold(0);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, categories));
        category.setOnClickListener(v -> { if (!searchMode) category.showDropDown(); });
        category.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        root.addView(category, matchWrap());
        fieldViews[4] = category;
        addFieldTitle(root, "Comment", 5);
        // Inflate scrollbar attributes so Android initializes its scrollbar drawable at construction.
        comment = (EditText) getLayoutInflater().inflate(R.layout.comment_input, root, false);
        root.addView(comment);
        fieldViews[5] = comment;
        comment.setOnTouchListener((view, event) -> {
            boolean scrolling = view.canScrollVertically(-1) || view.canScrollVertically(1);
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            } else if (scrolling) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false; // Keep the EditText's normal scrolling, selection and editing.
        });
        packageField = field(root, "Package", 6);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(12), 0, 0);
        root.addView(progress, progressParams);

        LinearLayout.LayoutParams previewParams = matchWrap();
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

    private void addFieldTitle(LinearLayout root, String label, int position) {
        fieldTitles[position] = text(label, 13, Color.DKGRAY);
        root.addView(fieldTitles[position], matchWrap());
    }

    private EditText field(LinearLayout root, String label, int position) {
        addFieldTitle(root, label, position);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(label);
        root.addView(field, matchWrap());
        fieldViews[position] = field;
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

    private void chooseTextSnippet() {
        try {
            java.util.Map<String, String> snippets = AppSettings.configuration(this).textSnippets;
            if (snippets.isEmpty()) {
                new android.app.AlertDialog.Builder(this).setTitle("Text snippets")
                        .setMessage("Add entries such as text1=\"text1\" under [TextSnippets] in setup.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            List<String> names = new ArrayList<>(snippets.keySet());
            String[] labels = new String[names.size()];
            for (int i = 0; i < labels.length; i++)
                labels[i] = names.get(i) + " — "
                        + snippets.get(names.get(i)).replace('\n', ' ').replace('\r', ' ');
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, labels) {
                @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, convertView, parent);
                    row.setSingleLine(true);
                    row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    return row;
                }
            };
            new android.app.AlertDialog.Builder(this).setTitle("Copy text snippet")
                    .setAdapter(adapter, (dialog, which) -> {
                        String name = names.get(which);
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(name, snippets.get(name)));
                        Toast.makeText(this, "Text snippet copied – long-press a text field and choose Paste.",
                                Toast.LENGTH_LONG).show();
                    }).setNegativeButton("Cancel", null).show();
        } catch (IOException e) {
            Toast.makeText(this, "Setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
                    .setTitle("Edit setup.ini").setView(scroll)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        android.util.AtomicFile target = new android.util.AtomicFile(file);
                        FileOutputStream out = null;
                        try {
                            String content = StoragePaths.withDefaults(input.getText().toString());
                            AppSettings.validate(AppProfiles.parse(content));
                            out = target.startWrite();
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                            target.finishWrite(out);
                            reloadProfile();
                            refreshCategories();
                            indexInitialized = false;
                            initializeIndex();
                            dialog.dismiss();
                            Toast.makeText(this, "Setup saved", Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            if (out != null) target.failWrite(out);
                            input.setError("Save failed: " + e.getMessage());
                        }
                    }));
            dialog.show();
        } catch (IOException e) {
            Toast.makeText(this, "Setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private List<DataIndex.Entry> rebuildIndex() throws IOException {
        return DataIndex.rebuild(selection.paths, file -> {
            String raw = readMediaComment(Uri.fromFile(file));
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
        StoragePaths paths = selection.paths;
        for (File directory : new File[]{paths.images, paths.data, paths.index}) {
            java.nio.file.Files.createDirectories(directory.toPath());
        }
        if ((selection.profile.id.equalsIgnoreCase("Chaobox")
                || selection.profile.id.equalsIgnoreCase("Chaosbox"))
                && paths.images.equals(new File(root, "JPG").getCanonicalFile())
                && paths.data.equals(new File(root, "boxes").getCanonicalFile())) {
            android.content.SharedPreferences prefs = getSharedPreferences("initial-data", MODE_PRIVATE);
            if (!prefs.getBoolean("jpg-and-boxes-restored-v2", false)) {
                java.util.Set<String> knownImages = new java.util.HashSet<>();
                for (File file : paths.imageFiles())
                    knownImages.add(file.getName().toLowerCase(java.util.Locale.ROOT));
                java.util.Set<String> knownBoxes = new java.util.HashSet<>();
                for (File file : paths.jsonFiles())
                    knownBoxes.add(file.getName().toLowerCase(java.util.Locale.ROOT));
                restoreInitialAssets("initial/JPG", paths.images, knownImages);
                restoreInitialAssets("initial/boxes", paths.data, knownBoxes);
                if (!prefs.edit().putBoolean("jpg-and-boxes-restored-v2", true).commit())
                    throw new IOException("Could not confirm initialization.");
            }
        }
    }

    private void restoreInitialAssets(String assetPath, File target, java.util.Set<String> knownNames)
            throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children != null && children.length > 0) {
            for (String child : children)
                restoreInitialAssets(assetPath + "/" + child, new File(target, child), knownNames);
            return;
        }
        String name = target.getName().toLowerCase(java.util.Locale.ROOT);
        if (knownNames.contains(name)) return;
        copyInitialAssets(assetPath, target);
        if (target.isFile()) knownNames.add(name);
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
                StoragePaths paths = selection.paths;
                List<String> warnings = paths.migrate(file -> {
                    String raw = readMediaComment(Uri.fromFile(file));
                    return MetadataData.parse(raw).category;
                });
                rebuildIndex();
                if (!warnings.isEmpty()) runOnUiThread(() -> showIndexError(
                        "Some files remain unchanged in their original location:\n" + String.join("\n", warnings)));
                runOnUiThread(() -> { refreshCategories(); setBusy(false); });
            } catch (IOException e) {
                runOnUiThread(() -> { setBusy(false); showIndexError(e.getMessage()); });
            }
        });
    }

    private void showIndexError(String message) {
        new android.app.AlertDialog.Builder(this).setTitle("Data file not updated")
                .setMessage(message).setPositiveButton("OK", null).show();
    }

    private EditText[] inputFields() {
        return new EditText[]{box, amount, device, alias, category, comment, packageField};
    }

    private void showImage(Uri image) {
        displayedImage = image;
        if (image == null) preview.setImageDrawable(null);
        else if (isVideo(image)) {
            preview.setImageDrawable(null);
            worker.execute(() -> {
                Bitmap thumbnail = null;
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this, image);
                    thumbnail = Build.VERSION.SDK_INT >= 27
                            ? retriever.getScaledFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960)
                            : retriever.getFrameAtTime();
                } catch (RuntimeException ignored) { /* Metadata editing also works without a preview frame. */ }
                finally { try { retriever.release(); } catch (IOException ignored) { } }
                final Bitmap frame = thumbnail;
                runOnUiThread(() -> {
                    if (!isDestroyed() && image.equals(displayedImage)) preview.setImageBitmap(frame);
                    else if (frame != null) frame.recycle();
                });
            });
        } else preview.setImageURI(image);
    }

    private void showFullImage() {
        if (displayedImage == null || isVideo(displayedImage) || preview.getDrawable() == null
                || isDestroyed() || (fullImageDialog != null && fullImageDialog.isShowing())) return;
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        fullImageDialog = dialog;
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        ZoomImageView image = new ZoomImageView(this);
        // Share the decoded bitmap, but use independent drawable bounds for the full-screen view.
        android.graphics.drawable.Drawable drawable = preview.getDrawable();
        android.graphics.drawable.Drawable.ConstantState state = drawable.getConstantState();
        image.setImageDrawable(state == null ? drawable : state.newDrawable(getResources()).mutate());
        frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Button close = new Button(this);
        close.setText("Close");
        close.setContentDescription("Close full-screen image");
        close.setOnClickListener(v -> dialog.dismiss());
        android.widget.FrameLayout.LayoutParams closeParams = new android.widget.FrameLayout.LayoutParams(
                -2, dp(48), Gravity.TOP | Gravity.END);
        closeParams.setMargins(dp(12), dp(12), dp(12), dp(12));
        frame.addView(close, closeParams);
        dialog.setContentView(frame);
        dialog.setOnDismissListener(d -> {
            image.setImageDrawable(null);
            if (fullImageDialog == dialog) fullImageDialog = null;
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private boolean isVideo(Uri uri) {
        if (Mp4Comments.isVideo(uri.getPath())) return true;
        if ("content".equals(uri.getScheme())) {
            if ("video/mp4".equalsIgnoreCase(getContentResolver().getType(uri))) return true;
            return Mp4Comments.isVideo(queryName(uri));
        }
        return false;
    }

    private String readMediaComment(Uri uri) throws IOException {
        return isVideo(uri) ? Mp4Comments.read(getContentResolver().openInputStream(uri))
                : ImageProcessor.readUserComment(getContentResolver().openInputStream(uri));
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
        device.setHint(enabled ? "Regular expression" : "Select or enter");
        category.setHint(enabled ? "Regular expression" : "Select or enter");
        search.setContentDescription(enabled ? "Run search" : "Search");
        search.setTooltipText(enabled ? "Run search" : "Search");
        if (!enabled) { refreshDevices(); refreshCategories(); }
        for (EditText field : inputFields()) field.setError(null);
        setBusy(false);
    }

    private void repeatLastSearch() {
        if (busy || lastSearch == null) return;
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        if (!searchMode) searchClicked();
        EditText[] fields = inputFields();
        for (int i = 0; i < fields.length; i++) fields[i].setText(lastSearch[i]);
        searchClicked();
    }

    private void searchClicked() {
        if (busy) return;
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
            selectedLabel.setText("Search: enter regex, then tap Search. Empty fields match anything; Back cancels.");
            for (EditText field : fields) if (field.isShown()) { field.requestFocus(); break; }
            return;
        }
        String[] keys = {"box", "anzahl", "device", "alias", "category", "comment", "package"};
        EditText[] fields = inputFields();
        java.util.Map<String, java.util.regex.Pattern> patterns = new java.util.LinkedHashMap<>();
        for (int i = 0; i < fields.length; i++) {
            fields[i].setError(null);
            if (!selection.profile.visible(i)) continue;
            try {
                patterns.putAll(DataIndex.compile(java.util.Collections.singletonMap(keys[i], fields[i].getText().toString())));
            } catch (java.util.regex.PatternSyntaxException e) {
                fields[i].setError("Invalid regular expression: " + e.getDescription());
                fields[i].requestFocus();
                return;
            }
        }
        // Retain the query independently of the record opened from its results.
        lastSearch = new String[fields.length];
        for (int i = 0; i < fields.length; i++) lastSearch[i] = fields[i].getText().toString();
        setBusy(true);
        worker.execute(() -> {
            try {
                List<DataIndex.Entry> entries = rebuildIndex();
                List<DataIndex.Entry> hits = DataIndex.search(entries, patterns);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (hits.isEmpty()) {
                        selectedLabel.setText("No matches. Change the search fields and tap Search again.");
                    } else if (hits.size() == 1) {
                        selectSearchHit(hits.get(0), entries);
                    } else {
                        String[] labels = new String[hits.size()];
                        for (int i = 0; i < hits.size(); i++) {
                            JSONObject data = hits.get(i).data;
                            labels[i] = data.optString("box", "") + " · " + data.optString("device", "")
                                    + " · " + data.optString("alias", "") + " · " + selection.paths.displayPath(hits.get(i).source);
                        }
                        new android.app.AlertDialog.Builder(this).setTitle(hits.size() + " matches")
                                .setItems(labels, (dialog, which) -> selectSearchHit(hits.get(which), entries))
                                .setNegativeButton("Keep searching", null).show();
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
        java.util.Map<File, Integer> sourceIndices = new java.util.HashMap<>();
        for (DataIndex.Entry entry : entries) {
            int sourceIndex = sourceIndices.getOrDefault(entry.source, 0);
            sourceIndices.put(entry.source, sourceIndex + 1);
            if (entry == hit || (!openedBoxName.isEmpty() && openedBoxName.equalsIgnoreCase(entry.data.optString("box", "")))) {
                DeviceRecord record = new DeviceRecord(entry.data);
                record.source = entry.source;
                record.recordIndex = entry.image ? -1 : sourceIndex;
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
        java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.ENGLISH);
        collator.setStrength(java.text.Collator.SECONDARY);
        sortedDevices.sort((left, right) -> collator.compare(left.toString(), right.toString()));
        device.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, sortedDevices));
    }

    private void openJson() {
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        if (box.getText().toString().trim().isEmpty()) {
            showJsonFileDialog();
            return;
        }
        if (!selection.profile.visible(0)) {
            requestBoxName("Open JSON", this::openJson);
            return;
        }
        openJson(box.getText().toString().trim());
    }

    private void showJsonFileDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try {
            File storage = Environment.getExternalStorageDirectory().getCanonicalFile();
            File dataRoot = selection.paths.data;
            if (dataRoot.toPath().startsWith(storage.toPath())) {
                String relative = storage.toPath().relativize(dataRoot.toPath())
                        .toString().replace(File.separatorChar, '/');
                Uri initial = android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:" + relative);
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial);
            }
        } catch (IOException ignored) { /* The system picker can still open at its default location. */ }
        startActivityForResult(intent, PICK_JSON);
    }

    private File selectedJsonFile(Uri uri) throws IOException {
        File candidate = null;
        if ("file".equals(uri.getScheme())) candidate = new File(uri.getPath());
        else if ("com.android.externalstorage.documents".equals(uri.getAuthority())
                && android.provider.DocumentsContract.isDocumentUri(this, uri)) {
            String id = android.provider.DocumentsContract.getDocumentId(uri);
            if (id.startsWith("primary:"))
                candidate = new File(Environment.getExternalStorageDirectory(), id.substring(8));
        }
        if (candidate == null)
            throw new IOException("Select a JSON file from the active profile's data folder.");
        File file = candidate.getCanonicalFile();
        File dataRoot = selection.paths.data;
        if (file.equals(dataRoot) || !file.toPath().startsWith(dataRoot.toPath())
                || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                || !java.nio.file.Files.isRegularFile(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Select a JSON file from the active profile's data folder.");
        return file;
    }

    private void requestBoxName(String title, java.util.function.Consumer<String> action) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("JSON box filename");
        input.setText(box.getText().toString());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(title).setView(input).setPositiveButton("OK", null)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String name = input.getText().toString().trim();
                    try { BoxRecords.validateName(name); }
                    catch (IOException e) { input.setError(e.getMessage()); return; }
                    dialog.dismiss();
                    box.setText(name);
                    action.accept(name);
                }));
        dialog.show();
    }

    private void openJson(String name) {
        openJson(name, null);
    }

    private void openJson(File chosen) {
        openJson(chosen.getName(), chosen);
    }

    private void openJson(String name, File chosen) {
        final String selectedCategory = category.getText().toString();
        try { BoxRecords.validateName(name); box.setError(null); }
        catch (IOException e) { box.setError(e.getMessage()); box.requestFocus(); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                File source = chosen == null ? selection.paths.findBox(name, selectedCategory) : chosen;
                List<JSONObject> records = BoxRecords.load(source.getParentFile(), source.getName());
                List<DeviceRecord> choices = new ArrayList<>();
                for (JSONObject record : records) {
                    DeviceRecord choice = new DeviceRecord(record);
                    choice.recordIndex = choices.size();
                    choice.source = source;
                    choices.add(choice);
                }
                runOnUiThread(() -> {
                    selectedUri = null;
                    selectedImages.clear();
                    showImage(null);
                    openedBoxName = source.getName().substring(0, source.getName().length() - 5);
                    deviceRecords.clear();
                    deviceRecords.addAll(choices);
                    refreshDevices();
                    applyRecord(choices.get(0));
                    selectedLabel.setText("Opened: " + openedBoxName + ".json — " + choices.size() + " records");
                    setBusy(false);
                    if (choices.size() > 1 && selection.profile.visible(2)) {
                        device.requestFocus();
                        device.showDropDown();
                    } else if (choices.size() > 1) {
                        String[] labels = new String[choices.size()];
                        for (int i = 0; i < labels.length; i++)
                            labels[i] = "Record " + (i + 1) + ": " + choices.get(i).toString();
                        new android.app.AlertDialog.Builder(this).setTitle("Select record")
                                .setItems(labels, (dialog, which) -> applyRecord(choices.get(which)))
                                .setNegativeButton("Cancel", null).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, "Open JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyRecord(DeviceRecord record) {
        activeRecord = record;
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
            selectedImages.clear();
            if (selectedUri != null) selectedImages.add(selectedUri);
            showImage(record.previewFile == null ? null : Uri.fromFile(record.previewFile));
            selectedLabel.setText("Loaded: " + selection.paths.displayPath(record.source));
        }
    }

    private static final class DeviceRecord {
        final MetadataData values;
        File source, previewFile;
        boolean image;
        int recordIndex = -1;
        DeviceRecord(JSONObject json) { values = MetadataData.parse(json.toString()); }
        @Override public String toString() {
            return values.device.isEmpty() ? "(no device)" : values.device;
        }
    }

    private void savedLocally(String savedMessage) {
        runOnUiThread(() -> {
            setBusy(false);
            selectedLabel.setText(savedMessage + " — saved locally");
        });
    }

    private void synchronizeManually() {
        if (busy) return;
        if (!hasStorageAccess()) {
            requestStorageAccess();
            return;
        }
        TextView log = text("Preparing saved files …\n", 14, Color.DKGRAY);
        log.setPadding(dp(16), dp(12), dp(16), dp(12));
        log.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        uploadDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Manual synchronization")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("Close", null)
                .setNegativeButton("Cancel", null).create();
        uploadDialog.show();
        final android.app.AlertDialog dialog = uploadDialog;
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        final UploadFiles upload = new UploadFiles(this, message -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            log.append(message + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }), selection.paths);
        activeUpload = upload;
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            upload.cancel();
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            log.append("Cancellation requested …\n");
        });
        setBusy(true);
        worker.execute(() -> {
            String failure = null;
            try {
                rebuildIndex();
                upload.copy();
            } catch (Exception e) {
                failure = "Synchronization failed:\n" + UploadErrors.describe(e)
                        + "\nLocal files are retained. Tap (c) kner to try again.";
            }
            final String error = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                activeUpload = null;
                setBusy(false);
                if (error != null) {
                    log.append("\n" + error + "\n");
                    dialog.setTitle("Synchronization failed");
                    selectedLabel.setText(error);
                } else {
                    dialog.setTitle("Synchronization completed");
                    selectedLabel.setText("Manual synchronization completed.");
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setVisibility(View.GONE);
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    @Override protected void onStop() {
        // No unattended transfer when the app leaves the foreground.
        if (activeUpload != null) activeUpload.cancel();
        super.onStop();
    }

    private File existingMedia(Uri uri) throws IOException {
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
        String name = candidate.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".jpg") && !name.endsWith(".jpeg") && !name.endsWith(".mp4")) return null;
        File root = selection.paths.images;
        File file = candidate.getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || file.equals(root)) return null;
        if (!java.nio.file.Files.isRegularFile(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Media source file is unavailable.");
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
        if (busy) return;
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                List<File> files = selection.paths.imageFiles();
                String[] labels = new String[files.size()];
                for (int i = 0; i < files.size(); i++) labels[i] = selection.paths.displayPath(files.get(i));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this)
                            .setTitle("Open JPG / MP4 – all subfolders")
                            .setNeutralButton("Other file …", (d, which) -> chooseExternalImage())
                            .setNegativeButton("Cancel", null);
                    if (files.isEmpty()) dialog.setMessage("No JPG or MP4 files found in the media folder or its subfolders.");
                    else {
                        boolean[] checked = new boolean[files.size()];
                        dialog.setMultiChoiceItems(labels, checked, (d, which, selected) -> {
                            checked[which] = selected;
                            boolean any = false;
                            for (boolean value : checked) any |= value;
                            ((android.app.AlertDialog) d).getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(any);
                        });
                        dialog.setPositiveButton("Open", (d, which) -> {
                            List<Uri> images = new ArrayList<>();
                            for (int i = 0; i < checked.length; i++)
                                if (checked[i]) images.add(Uri.fromFile(files.get(i)));
                            openImages(images);
                        });
                    }
                    android.app.AlertDialog shown = dialog.show();
                    if (!files.isEmpty()) shown.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                });
            } catch (IOException | java.io.UncheckedIOException e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    new android.app.AlertDialog.Builder(this).setTitle("Could not open media")
                            .setMessage(e.getMessage()).setPositiveButton("OK", null)
                            .setNeutralButton("Other file …", (d, which) -> chooseExternalImage()).show();
                });
            }
        });
    }

    private void chooseExternalImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "video/mp4"});
        File storage = Environment.getExternalStorageDirectory();
        if (selection.paths.images.toPath().startsWith(storage.toPath())) {
            String relative = storage.toPath().relativize(selection.paths.images.toPath()).toString();
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:" + relative));
        }
        startActivityForResult(intent, PICK_IMAGE);
    }

    private void loadCategories() {
        categories.clear();
        if (selection != null) categories.addAll(selection.profile.categories);
        if (categories.isEmpty()) categories.add("Uncategorized");
    }

    private InputStream openSetupFileSafely() throws IOException {
        try {
            return openOrCreateSetupFile();
        } catch (Exception ignored) {
            // Use defaults until shared-storage access has been granted.
            return getAssets().open(Config.SETUP_ASSET);
        }
    }

    private InputStream openOrCreateSetupFile() throws IOException {
        File dir = new File(Environment.getExternalStorageDirectory(), "ChaosBox/Setup");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create setup folder");
        File setup = new File(dir, "setup.ini");
        if (!setup.exists()) {
            try (InputStream asset = getAssets().open(Config.SETUP_ASSET); OutputStream output = new FileOutputStream(setup)) {
                copy(asset, output);
            }
        }
        String existing = LocalData.read(setup);
        String extended = StoragePaths.withDefaults(existing);
        if (!extended.equals(existing)) {
            android.util.AtomicFile target = new android.util.AtomicFile(setup);
            FileOutputStream output = null;
            try {
                output = target.startWrite();
                output.write(extended.getBytes(StandardCharsets.UTF_8));
                target.finishWrite(output);
            } catch (IOException e) {
                if (output != null) target.failWrite(output);
                throw e;
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
        if (requestCode == PICK_JSON && resultCode == RESULT_OK && data != null) {
            try {
                if (data.getData() == null) throw new IOException("No file selected.");
                openJson(selectedJsonFile(data.getData()));
            } catch (IOException e) {
                Toast.makeText(this, "Open JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            List<Uri> images = new ArrayList<>();
            android.content.ClipData clips = data.getClipData();
            if (clips != null) {
                for (int i = 0; i < clips.getItemCount(); i++) {
                    Uri uri = clips.getItemAt(i).getUri();
                    if (uri != null && !images.contains(uri)) images.add(uri);
                }
            } else if (data.getData() != null) images.add(data.getData());
            openImages(images);
        }
    }

    private void openImages(List<Uri> images) {
        if (images.isEmpty()) return;
        Uri first = images.get(0);
        String label = "file".equals(first.getScheme())
                ? selection.paths.displayPath(new File(first.getPath())) : queryName(first);
        openImage(first, images.size() == 1 ? label
                : images.size() + " files selected — preview and initial values: " + label);
        selectedImages.clear();
        selectedImages.addAll(images);
    }

    private void openImage(Uri imageUri, String label) {
        selectedImages.clear();
        selectedImages.add(imageUri);
        deviceRecords.clear();
        openedBoxName = null;
        activeRecord = null;
        refreshDevices();
        selectedUri = imageUri;
        selectedLabel.setText(label);
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
                String existing = readMediaComment(uri);
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

    private String queryName(Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return "image.jpg";
    }

    private void saveImage() {
        if (searchMode || busy) return;
        if (!hasStorageAccess()) {
            requestStorageAccess();
            return;
        }
        if (selectedUri == null && !selection.profile.visible(0) && box.getText().toString().trim().isEmpty()) {
            requestBoxName("Save JSON", name -> saveImage());
            return;
        }
        final int number;
        try {
            number = InputValues.amount(amount.getText().toString());
            amount.setError(null);
        } catch (NumberFormatException e) {
            amount.setError("Enter a valid integer (0 to 2147483647).");
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
        final DeviceRecord originalRecord = activeRecord;
        final Uri imageUri = selectedUri;
        final List<Uri> images = new ArrayList<>(selectedImages);
        setBusy(true);
        worker.execute(() -> {
            int completed = 0;
            try {
                try (InputStream ignored = openOrCreateSetupFile()) { }
                AppSettings.Selection updated = AppSettings.rememberCategory(this, selection, selectedCategory);
                runOnUiThread(() -> {
                    selection = updated;
                    refreshCategories();
                });
                if (imageUri == null) {
                    StoragePaths paths = selection.paths;
                    boolean editingBox = originalRecord != null && !originalRecord.image
                            && originalRecord.source != null;
                    // An opened record always stays in its original JSON file and subfolder.
                    File directory = editingBox ? originalRecord.source.getParentFile() : paths.data;
                    String filename = editingBox ? originalRecord.source.getName()
                            : BoxRecords.filename(boxName);
                    int preferredIndex = editingBox ? originalRecord.recordIndex : -1;
                    File saved = BoxRecords.save(directory, filename, userComment, preferredIndex);
                    List<JSONObject> records = BoxRecords.load(directory, saved.getName());
                    List<DeviceRecord> choices = new ArrayList<>();
                    String savedDevice = new JSONObject(userComment).optString("device", "");
                    DeviceRecord selected = null;
                    for (JSONObject record : records) {
                        DeviceRecord choice = new DeviceRecord(record);
                        choice.source = saved;
                        choice.recordIndex = choices.size();
                        choices.add(choice);
                        if (savedDevice.equals(choice.values.device)
                                && (selected == null || choice.recordIndex == preferredIndex)) selected = choice;
                    }
                    final DeviceRecord savedRecord = selected;
                    runOnUiThread(() -> {
                        openedBoxName = saved.getName().substring(0, saved.getName().length() - 5);
                        deviceRecords.clear();
                        deviceRecords.addAll(choices);
                        refreshDevices();
                        if (savedRecord != null) applyRecord(savedRecord);
                    });
                    savedLocally("Saved: " + saved.getAbsolutePath());
                    return;
                }
                long totalBytes = 0;
                for (int i = 0; i < images.size(); i++) {
                    Uri source = images.get(i);
                    File existing = existingMedia(source);
                    Uri saved;
                    long savedBytes;
                    if (isVideo(source)) {
                        saved = saveVideo(source, existing, userComment, selectedCategory);
                        savedBytes = new File(saved.getPath()).length();
                    } else if (existing != null) {
                        byte[] result = ImageProcessor.process(new java.io.FileInputStream(existing),
                                userComment, selection.config.imageLimit);
                        saved = overwriteJpg(existing, result);
                        savedBytes = result.length;
                    } else {
                        byte[] result = ImageProcessor.process(getContentResolver().openInputStream(source),
                                userComment, selection.config.imageLimit);
                        saved = writeOutput(result, outputName(queryName(source)), selectedCategory);
                        savedBytes = result.length;
                    }
                    images.set(i, saved);
                    completed++;
                    totalBytes += savedBytes;
                    final int index = i;
                    runOnUiThread(() -> {
                        // Keep successful outputs selected, including after a partial failure.
                        selectedImages.set(index, saved);
                        if (index == 0) {
                            selectedUri = saved;
                            showImage(null);
                            showImage(saved);
                        }
                    });
                }
                savedLocally("Saved: " + (images.size() == 1 ? images.get(0).getPath()
                        : images.size() + " files") + " (" + totalBytes / 1024 + " kB)");
            } catch (Exception e) {
                final String message = (images.size() > 1
                        ? "Saved " + completed + " of " + images.size() + " files. Stopped at "
                                + (completed < images.size() ? images.get(completed) : "completion") + ": " : "Error: ") + e.getMessage();
                runOnUiThread(() -> {
                    setBusy(false);
                    selectedLabel.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
            amount.setError("Enter a valid integer (0 to 2147483647).");
            amount.requestFocus();
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        openJson.setEnabled(!busy && !searchMode);
        search.setEnabled(!busy);
        newEntry.setEnabled(!busy);
        repeatSearch.setEnabled(!busy && lastSearch != null);
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
        profileTitle.setEnabled(!busy);
        synchronize.setEnabled(!busy);
    }

    private void selectCategory(String value) {
        category.setText(value == null ? "" : value, false);
    }

    private String outputName(String input) {
        int dot = input.lastIndexOf('.');
        if (dot <= 0) return input + "_cb.jpg";
        return input.substring(0, dot) + "_cb.jpg";
    }

    private Uri saveVideo(Uri source, File existing, String comment, String category) throws IOException {
        File directory = existing == null ? selection.paths.categoryDirectory(true, category) : existing.getParentFile();
        java.nio.file.Files.createDirectories(directory.toPath());
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(directory.toPath(), ".save-video-", ".tmp");
        File target = existing;
        boolean reserved = false, published = false;
        try {
            try (InputStream input = getContentResolver().openInputStream(source);
                 OutputStream output = java.nio.file.Files.newOutputStream(temporary)) {
                if (input == null) throw new IOException("MP4 source is unavailable.");
                copy(input, output);
            }
            Mp4Comments.write(temporary.toFile(), comment);
            if (target == null) {
                String name = new File(queryName(source)).getName();
                int dot = name.lastIndexOf('.');
                String stem = (dot > 0 ? name.substring(0, dot) : name) + "_cb";
                target = new File(directory, stem + ".mp4");
                int suffix = 1;
                while (!target.createNewFile()) target = new File(directory, stem + "_" + suffix++ + ".mp4");
                reserved = true;
            }
            long modified = Math.max(System.currentTimeMillis(), (target.lastModified() / 1000 + 1) * 1000);
            java.nio.file.Files.setLastModifiedTime(temporary, java.nio.file.attribute.FileTime.fromMillis(modified));
            java.nio.file.Files.move(temporary, target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            published = true;
            android.media.MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return Uri.fromFile(target);
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
            if (reserved && !published) java.nio.file.Files.deleteIfExists(target.toPath());
        }
    }

    private Uri writeOutput(byte[] data, String name, String category) throws IOException {
        File dir = selection.paths.categoryDirectory(true, category);
        java.nio.file.Files.createDirectories(dir.toPath());
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
        if (fullImageDialog != null) fullImageDialog.dismiss();
        if (activeUpload != null) activeUpload.cancel();
        if (uploadDialog != null) uploadDialog.dismiss();
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

        private static String commentText(JSONObject json) {
            return json.optString("comment", json.optString("kommentar", json.optString("Kommentar", "")));
        }

        static MetadataData parse(String raw) {
            MetadataData data = new MetadataData();
            if (raw == null || raw.trim().isEmpty()) return data;
            try {
                Object value = new org.json.JSONTokener(raw).nextValue();
                org.json.JSONArray items = value instanceof org.json.JSONArray
                        ? (org.json.JSONArray) value : null;
                if (items != null && items.length() == 0) return data;
                if (items == null && !(value instanceof JSONObject))
                    throw new JSONException("Expected a JSON object or list");
                JSONObject json = items == null ? (JSONObject) value : items.getJSONObject(0);
                data.created = json.optString("created", "");
                data.box = json.optString("box", "");
                data.amount = Math.max(0, json.optInt("count", json.optInt("anzahl", json.optInt("Anzahl", 0))));
                data.device = json.optString("device", "");
                data.alias = json.optString("alias", "");
                data.packageName = json.isNull("pack")
                        ? json.optString("package", "") : json.optString("pack", "");
                data.category = CategoryValues.read(raw);
                data.comment = commentText(json);
                if (items != null) {
                    StringBuilder comments = new StringBuilder();
                    for (int i = 0; i < items.length(); i++) {
                        String text = commentText(items.getJSONObject(i));
                        if (text.isEmpty()) continue;
                        if (comments.length() > 0) comments.append("\n\n");
                        comments.append(text);
                    }
                    data.comment = comments.toString();
                }
                return data;
            } catch (JSONException ignored) {
                // Preserve foreign or older content as an unstructured comment.
                data.comment = raw;
                return data;
            }
        }
    }

    static final class ImageProcessor {
        static byte[] process(InputStream input, String comment, int maxSide) throws IOException {
            if (input == null) throw new IOException("Could not open image");
            byte[] source;
            try (InputStream in = input; ByteArrayOutputStream all = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) >= 0) all.write(buffer, 0, n);
                source = all.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid JPG or PNG file");
            boolean jpegSource = "image/jpeg".equals(bounds.outMimeType);
            if (!jpegSource && !"image/png".equals(bounds.outMimeType))
                throw new IOException("Select a JPG or PNG file");
            byte[] exif = exifSegment(comment);
            if (jpegSource && Math.max(bounds.outWidth, bounds.outHeight) <= maxSide)
                return JpegComments.update(source, comment, exif);
            if (jpegSource) exif = JpegComments.resizedExifSegment(source, comment, exif);

            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2L) >= maxSide) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = jpegSource ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            if (bitmap == null) throw new IOException("Could not decode image");
            bitmap = orient(bitmap, readOrientation(source));
            int decodedLongest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap output = bitmap;
            if (decodedLongest > maxSide) {
                double factor = (double) maxSide / decodedLongest;
                int width = Math.max(1, (int) Math.round(bitmap.getWidth() * factor));
                int height = Math.max(1, (int) Math.round(bitmap.getHeight() * factor));
                output = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (output != bitmap) bitmap.recycle();
            }
            if (output.hasAlpha()) {
                Bitmap opaque = Bitmap.createBitmap(output.getWidth(), output.getHeight(), Bitmap.Config.RGB_565);
                android.graphics.Canvas canvas = new android.graphics.Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(output, 0, 0, null);
                output.recycle();
                output = opaque;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 92, bytes))
                    throw new IOException("Could not save JPG");
            } finally {
                output.recycle();
            }
            byte[] jpeg = bytes.toByteArray();
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
            // IFD0 points to the Exif IFD, which contains UserComment (0x9286).
            int exifIfdOffset = 8 + 2 + 12 + 4;
            int userOffset = exifIfdOffset + 2 + 12 + 4;
            int tiffSize = userOffset + user.length;
            if (tiffSize + 8 > 65533) throw new IOException("Comment is too long");
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
