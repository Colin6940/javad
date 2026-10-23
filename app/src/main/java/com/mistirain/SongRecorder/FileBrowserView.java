package com.mistirain.SongRecorder;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java/View port of the former FileBrowserScreen composable. */
public class FileBrowserView extends LinearLayout {

    public interface EditListener { void onEditRequested(File file); }

    private static final String TAG = "FileBrowserView";

    private final EditListener onEditRequested;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Uri storagePathUri;
    private List<AudioItem> audioItems = new ArrayList<>();
    private String selectedItemId = null;
    private String searchQuery = "";

    private MediaPlayer mediaPlayer = null;
    private boolean isPlaying = false;
    private int currentPositionMs = 0;
    private int durationMs = 1;

    private final LinearLayout listContainer;
    private final TextView emptyText;
    private final ScrollView scroll;

    // Views of the selected (expanded) card, updated by the ticker.
    private Slider selectedSlider;
    private TextView selectedCurrentLabel;
    private ImageButton selectedPlayButton;
    private boolean sliderTouching = false;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (isPlaying) {
                try {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        currentPositionMs = mediaPlayer.getCurrentPosition();
                    }
                } catch (Exception ignored) { }
                updateSelectedProgress();
                main.postDelayed(this, 100L);
            }
        }
    };

    public FileBrowserView(Context c, Uri storagePathUri, EditListener onEditRequested) {
        super(c);
        this.storagePathUri = storagePathUri;
        this.onEditRequested = onEditRequested;
        setOrientation(VERTICAL);
        int pad = Ui.dp(c, 16);
        setPadding(pad, pad, pad, pad);

        TextInputLayout searchLayout = new TextInputLayout(c, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        searchLayout.setPlaceholderText("파일명 검색");
        searchLayout.setHint(null);
        TextInputEditText search = new TextInputEditText(searchLayout.getContext());
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setHint("파일명 검색");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int co, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int be, int co) { }
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString();
                rebuildList();
            }
        });
        searchLayout.addView(search);
        addView(searchLayout, Ui.matchWrap());

        LinearLayout header = Ui.horizontal(c);
        header.addView(Ui.text(c, "저장된 오디오 파일 목록", 16, true), Ui.weight(1f));
        MaterialButton refresh = Ui.button(c, "새로고침", v -> loadFiles());
        refresh.setTextSize(11);
        refresh.setTypeface(android.graphics.Typeface.DEFAULT);
        refresh.setMinHeight(Ui.dp(c, 28));
        refresh.setMinimumHeight(Ui.dp(c, 28));
        refresh.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        header.addView(refresh, new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 28)));
        LayoutParams headerLp = Ui.matchWrap();
        headerLp.topMargin = Ui.dp(c, 8);
        headerLp.bottomMargin = Ui.dp(c, 10);
        addView(header, headerLp);

        FrameLayout body = new FrameLayout(c);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        scroll = new ScrollView(c);
        listContainer = Ui.vertical(c);
        scroll.addView(listContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        body.addView(scroll);
        emptyText = Ui.muted(c, "검색 결과가 없거나 저장된 오디오 파일이 없습니다.", 14);
        emptyText.setGravity(Gravity.CENTER);
        body.addView(emptyText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rebuildList();
    }

    /** Called when the tab becomes visible or the storage folder changes. */
    public void onShown(Uri storageUri) {
        boolean changed = (storageUri == null) ? storagePathUri != null : !storageUri.equals(storagePathUri);
        storagePathUri = storageUri;
        if (changed) {
            releasePlayer();
            selectedItemId = null;
        }
        loadFiles();
    }

    public void onHidden() {
        releasePlayer();
        selectedItemId = null;
        rebuildList();
    }

    public void release() {
        releasePlayer();
        io.shutdownNow();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
            try { mediaPlayer.release(); } catch (Exception ignored) { }
        }
        mediaPlayer = null;
        isPlaying = false;
        currentPositionMs = 0;
        main.removeCallbacks(progressTick);
    }

    private static boolean isAudioName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".wav");
    }

    private void loadFiles() {
        final Uri treeUri = storagePathUri;
        final Context context = getContext().getApplicationContext();
        try {
            io.execute(() -> {
                List<AudioItem> list = treeUri != null ? queryTree(context, treeUri) : queryDefault(context);
                // distinctBy(name + size), sorted by lastModified desc
                Set<String> seen = new HashSet<>();
                List<AudioItem> distinct = new ArrayList<>();
                for (AudioItem item : list) {
                    if (seen.add(item.name + "_" + item.sizeBytes)) distinct.add(item);
                }
                distinct.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
                main.post(() -> {
                    audioItems = distinct;
                    rebuildList();
                });
            });
        } catch (Exception ignored) { }
    }

    private static List<AudioItem> queryTree(Context context, Uri treeUri) {
        List<AudioItem> list = new ArrayList<>();
        try {
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = context.getContentResolver().query(childrenUri, projection, null, null, null)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
                    int modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                    while (cursor.moveToNext()) {
                        String docId = cursor.getString(idCol);
                        String name = cursor.getString(nameCol);
                        if (docId == null || name == null || !isAudioName(name)) continue;
                        long size = cursor.isNull(sizeCol) ? 0L : cursor.getLong(sizeCol);
                        long mod = cursor.isNull(modCol) ? 0L : cursor.getLong(modCol);
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        list.add(new AudioItem(fileUri.toString(), name, size, mod, fileUri, null));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Tree query failed", e);
        }
        return list;
    }

    @SuppressWarnings("deprecation")
    private static List<AudioItem> queryDefault(Context context) {
        List<AudioItem> list = new ArrayList<>();
        try {
            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.DATA
            };
            String selection = MediaStore.Audio.Media.DATA + " LIKE ?";
            String[] args = {"%SongRecorder%"};
            try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, args, null)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    int modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
                    int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String name = cursor.getString(nameCol);
                        if (name == null || !isAudioName(name)) continue;
                        long size = cursor.getLong(sizeCol);
                        long mod = cursor.getLong(modCol) * 1000L;
                        String path = cursor.getString(dataCol);
                        Uri itemUri = ContentUris.withAppendedId(collection, id);
                        File f = path != null ? new File(path) : null;
                        list.add(new AudioItem(itemUri.toString(), name, size, mod, itemUri,
                                f != null && f.exists() ? f : null));
                    }
                }
            }
        } catch (Exception ignored) { }

        File musicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "SongRecorder");
        File localDir = new File(context.getExternalFilesDir(null), "SongRecorder");
        for (File dir : new File[]{musicDir, localDir}) {
            if (!dir.exists()) continue;
            File[] files = dir.listFiles(f -> isAudioName(f.getName()) && f.length() > 44L);
            if (files == null) continue;
            for (File f : files) {
                boolean exists = false;
                for (AudioItem it : list) {
                    if (it.name.equals(f.getName())) { exists = true; break; }
                }
                if (!exists) {
                    list.add(new AudioItem(f.getAbsolutePath(), f.getName(), f.length(), f.lastModified(),
                            Uri.fromFile(f), f));
                }
            }
        }
        return list;
    }

    // ================================================================== list rendering

    private void rebuildList() {
        Context c = getContext();
        listContainer.removeAllViews();
        selectedSlider = null;
        selectedCurrentLabel = null;
        selectedPlayButton = null;
        String q = searchQuery.toLowerCase(Locale.ROOT);
        int shown = 0;
        for (AudioItem item : audioItems) {
            if (!item.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            View card = buildCard(c, item);
            LayoutParams lp = Ui.matchWrap();
            if (shown > 0) lp.topMargin = Ui.dp(c, 8);
            listContainer.addView(card, lp);
            shown++;
        }
        emptyText.setVisibility(shown == 0 ? VISIBLE : GONE);
        scroll.setVisibility(shown == 0 ? GONE : VISIBLE);
    }

    private View buildCard(Context c, AudioItem item) {
        boolean isSelected = item.id.equals(selectedItemId);
        int surfaceVariant = com.google.android.material.color.MaterialColors.getColor(c,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY);

        LinearLayout card = Ui.vertical(c);
        int pad = Ui.dp(c, 12);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.rounded(surfaceVariant, Ui.dp(c, 8), Ui.dp(c, 1),
                isSelected ? Ui.primary(c) : Color.TRANSPARENT));
        card.setOnClickListener(v -> onCardClicked(item, isSelected));

        LinearLayout top = Ui.horizontal(c);
        top.setGravity(Gravity.TOP);
        LinearLayout info = Ui.vertical(c);
        info.addView(Ui.text(c, item.name, 14, true));
        info.addView(Ui.muted(c, String.format(Locale.US, "크기: %.1f KB", item.sizeBytes / 1024f), 11));
        top.addView(info, Ui.weight(1f));

        ImageButton more = new ImageButton(c);
        more.setImageResource(R.drawable.ic_more_vert);
        more.setColorFilter(Color.GRAY);
        more.setBackgroundColor(Color.TRANSPARENT);
        more.setContentDescription("옵션 메뉴");
        int size = Ui.dp(c, 24);
        top.addView(more, new LayoutParams(size, size));
        more.setOnClickListener(v -> showItemMenu(more, item, isSelected));
        card.addView(top, Ui.matchWrap());

        if (isSelected) {
            LayoutParams divLp = Ui.matchWrap();
            divLp.topMargin = Ui.dp(c, 12);
            divLp.bottomMargin = Ui.dp(c, 10);
            View div = Ui.divider(c, 0.5f, Color.DKGRAY);
            card.addView(div, divLp);

            Slider slider = Ui.slider(c, 0f, Math.max(durationMs, 1), Ui.clamp(currentPositionMs, 0, durationMs), v -> {
                currentPositionMs = (int) v;
                try { if (mediaPlayer != null) mediaPlayer.seekTo((int) v); } catch (Exception ignored) { }
                updateSelectedProgress();
            });
            slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override public void onStartTrackingTouch(Slider s) { sliderTouching = true; }
                @Override public void onStopTrackingTouch(Slider s) { sliderTouching = false; }
            });
            card.addView(slider, Ui.matchWrap());

            LinearLayout times = Ui.horizontal(c);
            TextView cur = Ui.muted(c, "00:00", 11);
            TextView tot = Ui.muted(c, mmss(durationMs), 11);
            times.addView(cur, Ui.weight(1f));
            times.addView(tot, Ui.wrap());
            card.addView(times, Ui.matchWrap());

            ImageButton play = new ImageButton(c);
            play.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            play.setColorFilter(Color.WHITE);
            play.setBackground(Ui.rounded(Ui.primary(c), Ui.dp(c, 24)));
            play.setContentDescription(isPlaying ? "정지" : "재생");
            play.setOnClickListener(v -> togglePlay());
            LayoutParams playLp = new LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48));
            playLp.gravity = Gravity.CENTER_HORIZONTAL;
            playLp.topMargin = Ui.dp(c, 8);
            card.addView(play, playLp);

            selectedSlider = slider;
            selectedCurrentLabel = cur;
            selectedPlayButton = play;
            updateSelectedProgress();
        }
        return card;
    }

    private static String mmss(int ms) {
        return String.format(Locale.US, "%02d:%02d", (ms / 1000) / 60, (ms / 1000) % 60);
    }

    private void updateSelectedProgress() {
        if (selectedSlider != null && !sliderTouching) {
            Ui.setSlider(selectedSlider, Ui.clamp(currentPositionMs, 0f, durationMs));
        }
        if (selectedCurrentLabel != null) selectedCurrentLabel.setText(mmss(currentPositionMs));
        if (selectedPlayButton != null) {
            selectedPlayButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            selectedPlayButton.setContentDescription(isPlaying ? "정지" : "재생");
        }
    }

    private void onCardClicked(AudioItem item, boolean isSelected) {
        if (isSelected) {
            selectedItemId = null;
            releasePlayer();
        } else {
            releasePlayer();
            try {
                MediaPlayer mp = new MediaPlayer();
                if (item.file != null && item.file.exists()) {
                    mp.setDataSource(item.file.getAbsolutePath());
                } else if (item.uri != null) {
                    mp.setDataSource(getContext(), item.uri);
                }
                mp.prepare();
                durationMs = Math.max(mp.getDuration(), 1);
                mp.setOnCompletionListener(p -> {
                    isPlaying = false;
                    currentPositionMs = 0;
                    updateSelectedProgress();
                });
                mediaPlayer = mp;
                selectedItemId = item.id;
            } catch (Exception e) {
                Log.e(TAG, "Unable to prepare player", e);
                Toast.makeText(getContext(), "재생할 수 없는 오디오 파일입니다.", Toast.LENGTH_SHORT).show();
            }
        }
        rebuildList();
    }

    private void togglePlay() {
        try {
            if (isPlaying) {
                if (mediaPlayer != null) mediaPlayer.pause();
                isPlaying = false;
            } else {
                if (mediaPlayer != null) mediaPlayer.start();
                isPlaying = true;
                main.removeCallbacks(progressTick);
                main.post(progressTick);
            }
        } catch (Exception e) {
            Log.e(TAG, "Playback toggle failed", e);
        }
        updateSelectedProgress();
    }

    private void showItemMenu(View anchor, AudioItem item, boolean isSelected) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "삭제");
        menu.getMenu().add(0, 2, 1, "이름 변경");
        menu.getMenu().add(0, 3, 2, "편집");
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: deleteItem(item, isSelected); return true;
                case 2: showRenameDialog(item); return true;
                case 3: editItem(item); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void deleteItem(AudioItem item, boolean isSelected) {
        Context c = getContext();
        boolean deleted = false;
        try {
            if (item.file != null && item.file.exists()) {
                deleted = item.file.delete();
            } else if (item.uri != null) {
                try {
                    deleted = DocumentsContract.deleteDocument(c.getContentResolver(), item.uri);
                } catch (Exception e) {
                    deleted = c.getContentResolver().delete(item.uri, null, null) > 0;
                }
            }
        } catch (Exception ignored) { }

        if (deleted) {
            Toast.makeText(c, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
            if (isSelected) {
                selectedItemId = null;
                releasePlayer();
            }
            loadFiles();
        } else {
            Toast.makeText(c, "삭제 실패", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameDialog(AudioItem item) {
        Context c = getContext();
        TextInputLayout layout = new TextInputLayout(c, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint("새 파일 이름");
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        int dot = item.name.lastIndexOf('.');
        input.setText(dot >= 0 ? item.name.substring(0, dot) : item.name);
        layout.addView(input);
        FrameLayout wrapper = new FrameLayout(c);
        int p = Ui.dp(c, 20);
        wrapper.setPadding(p, Ui.dp(c, 8), p, 0);
        wrapper.addView(layout);

        new MaterialAlertDialogBuilder(c)
                .setTitle("이름 변경")
                .setView(wrapper)
                .setPositiveButton("확인", (d, w) -> {
                    String typed = input.getText() == null ? "" : input.getText().toString();
                    String ext = dot >= 0 ? item.name.substring(dot + 1) : "";
                    String newName = (!ext.isEmpty() && !typed.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT)))
                            ? typed + "." + ext : typed;
                    boolean renamed = false;
                    try {
                        if (item.file != null && item.file.exists()) {
                            renamed = item.file.renameTo(new File(item.file.getParent(), newName));
                        } else if (item.uri != null) {
                            renamed = DocumentsContract.renameDocument(c.getContentResolver(), item.uri, newName) != null;
                        }
                    } catch (Exception ignored) { }
                    if (renamed) {
                        Toast.makeText(c, "이름이 변경되었습니다.", Toast.LENGTH_SHORT).show();
                        loadFiles();
                    } else {
                        Toast.makeText(c, "이름 변경 실패 (권한 확인 필요)", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void editItem(AudioItem item) {
        releasePlayer();
        updateSelectedProgress();
        final Context context = getContext().getApplicationContext();
        try {
            io.execute(() -> {
                int dot = item.name.lastIndexOf('.');
                String ext = dot >= 0 ? item.name.substring(dot + 1) : "wav";
                File tempImport = new File(context.getCacheDir(), "import_edit_source." + ext);
                boolean copied = false;
                try {
                    InputStream in = null;
                    if (item.file != null && item.file.exists()) {
                        in = new FileInputStream(item.file);
                    } else if (item.uri != null) {
                        in = context.getContentResolver().openInputStream(item.uri);
                    }
                    if (in != null) {
                        try (InputStream input = in; OutputStream output = new FileOutputStream(tempImport, false)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
                        }
                        copied = tempImport.exists() && tempImport.length() > 0L;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Import copy failed", e);
                }
                final boolean ok = copied;
                main.post(() -> {
                    if (ok) onEditRequested.onEditRequested(tempImport);
                    else Toast.makeText(context, "파일을 가져오지 못했습니다.", Toast.LENGTH_SHORT).show();
                });
            });
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(progressTick);
        super.onDetachedFromWindow();
    }
}
