package com.mistirain.SongRecorder;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity implements RecorderEditorView.Host {

    private static volatile boolean firstActivityCreatedInProcess = true;

    private static final String PREFS = "SongRecorderPrefs";
    private static final List<String> THEMES = Arrays.asList("시스템 기본값", "라이트", "다크");

    private DspProcessor dspProcessor;
    private AudioEngine audioEngine;
    private SharedPreferences prefs;

    private DrawerLayout drawer;
    private ViewPager2 pager;
    private TextView tabRecord;
    private TextView tabFiles;
    private TextView themeItemLabel;
    private TextView notificationItemLabel;
    private RecorderEditorView recorderView;
    private FileBrowserView fileBrowserView;

    private Uri storagePathUri;
    private String storagePathDisplay;
    private String defaultFormat;
    private boolean notificationEnabled;
    private long backPressedTime = 0L;
    private Ui.BoolListener pendingRecordPermissionResult;
    private MaterialSwitch notificationSwitch;
    private TextView notificationStateLabel;

    private final RecordingNotificationCommandBus.Listener commandListener = event -> {
        selectTab(0);
        if (recorderView != null) recorderView.enqueueNotificationCommand(event);
    };

    // ---------------------------------------------------------------- result launchers

    private final ActivityResultLauncher<String> recordPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Ui.BoolListener cb = pendingRecordPermissionResult;
                pendingRecordPermissionResult = null;
                if (cb != null) cb.onValue(granted);
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    RecordingService.enableNotificationForCurrentSession(this);
                    setNotificationEnabledUi(true);
                    openNotificationSettingsIfBlocked(this);
                } else {
                    RecordingService.disableNotificationForCurrentSession(this);
                    setNotificationEnabledUi(false);
                    Toast.makeText(this,
                            "알림 권한이 거부되어 알림 사용을 켜지 않았습니다. Android 설정에서 알림을 허용해주세요.",
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Uri> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) { }
                storagePathUri = uri;
                String docId;
                try {
                    docId = DocumentsContract.getTreeDocumentId(uri);
                } catch (Exception e) {
                    docId = uri.getPath();
                }
                String display;
                if (docId == null) display = "사용자 지정 폴더";
                else {
                    int idx = docId.indexOf(':');
                    display = idx >= 0 ? docId.substring(idx + 1) : docId;
                }
                storagePathDisplay = display;
                prefs.edit().putString("storage_uri", uri.toString()).putString("storage_display", display).apply();
                if (recorderView != null) recorderView.setStorage(storagePathUri, storagePathDisplay);
                if (fileBrowserView != null && pager.getCurrentItem() == 1) fileBrowserView.onShown(storagePathUri);
            });

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        applyNightMode(prefs.getString("theme", "시스템 기본값"));
        super.onCreate(savedInstanceState);

        // A fresh launcher entry starts a clean recorder session. Never do this during tab
        // navigation, and preserve a take when the Activity was explicitly opened by a
        // notification action that needs that take (playback/save/share).
        Intent startIntent = getIntent();
        String action = startIntent != null ? startIntent.getAction() : null;
        boolean preserveNotificationTake = action != null && Arrays.asList(
                RecordingService.ACTION_NOTIFICATION_START_RECORDING,
                RecordingService.ACTION_PLAYBACK_RESET,
                RecordingService.ACTION_PLAYBACK_PLAY,
                RecordingService.ACTION_PLAYBACK_PAUSE,
                RecordingService.ACTION_PLAYBACK_TOGGLE,
                RecordingService.ACTION_SAVE,
                RecordingService.ACTION_SHARE).contains(action);
        boolean freshActivityEntry = firstActivityCreatedInProcess;
        boolean normalLauncherEntry = savedInstanceState == null
                && (action == null || Intent.ACTION_MAIN.equals(action));
        if ((freshActivityEntry || normalLauncherEntry) && !preserveNotificationTake) {
            boolean hasActiveTake = RecordingService.hasActiveRecordingOrReviewSession();
            boolean isCapturing = RecordingService.isActivelyRecording();
            // The ON/OFF control is session-only. A clean launcher entry resets it to OFF,
            // while a genuine active capture keeps its required foreground-service notification.
            if (!isCapturing) RecordingService.prepareNotificationStateForAppLaunch(getApplicationContext());
            if (!hasActiveTake) {
                AppAudioRuntime.beginFreshAppSession(getApplicationContext());
                AppAudioRuntime.clearAbandonedTemporaryTake(getApplicationContext());
                RecordingService.resetTransientTakeForFreshAppStart(getApplicationContext());
            }
        }
        firstActivityCreatedInProcess = false;
        AppAudioRuntime.Runtime audioRuntime = AppAudioRuntime.get(getApplicationContext());
        dspProcessor = audioRuntime.dsp;
        audioEngine = audioRuntime.audioEngine;

        String storedUri = prefs.getString("storage_uri", null);
        storagePathUri = storedUri != null ? Uri.parse(storedUri) : null;
        storagePathDisplay = prefs.getString("storage_display", "Music/SongRecorder");
        if (storagePathDisplay == null) storagePathDisplay = "Music/SongRecorder";
        defaultFormat = prefs.getString("default_format", "m4a");
        if (defaultFormat == null) defaultFormat = "m4a";
        notificationEnabled = RecordingService.isNotificationFeatureEnabled(this);

        setupWindow();
        setContentView(buildContent());
        setupBackHandling();

        recorderView.loadInitialState(null, null);
        RecordingNotificationCommandBus.setListener(commandListener);
        dispatchNotificationAction(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-publish an enabled idle notification after returning from system settings or
        // after the Activity was recreated without recreating the process.
        RecordingService.restoreNotificationIfEnabled(getApplicationContext());
    }

    @Override
    protected void onStop() {
        // Preserve the current working chain when a user leaves the app for notification controls,
        // and refresh the notification's effect-state labels to match the editor.
        AppAudioRuntime.persistWorkingSnapshot(getApplicationContext());
        RecordingService.refreshNotificationState(getApplicationContext());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Reset the switch on exit. If capture is still starting/running, leave its required
        // foreground card alone until the recorder reaches a safe stopping point.
        boolean engineBusy = audioEngine != null && audioEngine.isRecordingActive();
        if (isFinishing() && !isChangingConfigurations()) {
            RecordingService.resetNotificationPreferenceForAppExit(getApplicationContext(),
                    RecordingService.isActivelyRecording() || engineBusy);
        }
        RecordingNotificationCommandBus.clearListener(commandListener);
        if (recorderView != null) recorderView.release();
        if (fileBrowserView != null) fileBrowserView.release();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatchNotificationAction(intent);
    }

    private void dispatchNotificationAction(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        RecordingNotificationCommand.Type type;
        switch (action) {
            case RecordingService.ACTION_NOTIFICATION_START_RECORDING: type = RecordingNotificationCommand.Type.START_RECORDING; break;
            case RecordingService.ACTION_STOP_RECORDING: type = RecordingNotificationCommand.Type.STOP_RECORDING; break;
            case RecordingService.ACTION_CANCEL_RECORDING: type = RecordingNotificationCommand.Type.CANCEL_RECORDING; break;
            case RecordingService.ACTION_PLAYBACK_RESET: type = RecordingNotificationCommand.Type.RESET_PLAYBACK; break;
            case RecordingService.ACTION_PLAYBACK_PLAY: type = RecordingNotificationCommand.Type.PLAY_PLAYBACK; break;
            case RecordingService.ACTION_PLAYBACK_PAUSE: type = RecordingNotificationCommand.Type.PAUSE_PLAYBACK; break;
            case RecordingService.ACTION_PLAYBACK_TOGGLE: type = RecordingNotificationCommand.Type.TOGGLE_PLAYBACK; break;
            case RecordingService.ACTION_SAVE: type = RecordingNotificationCommand.Type.SAVE_RECORDING; break;
            case RecordingService.ACTION_SHARE: type = RecordingNotificationCommand.Type.SHARE_RECORDING; break;
            default: return;
        }
        RecordingNotificationCommandBus.dispatch(RecordingNotificationCommand.of(type));
    }

    // ---------------------------------------------------------------- Host

    @Override
    public void requestRecordPermission(Ui.BoolListener onResult) {
        pendingRecordPermissionResult = onResult;
        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    @Override
    public void finishHost() {
        finish();
    }

    // ---------------------------------------------------------------- window / theme

    private static void applyNightMode(String theme) {
        int mode;
        if ("다크".equals(theme)) mode = AppCompatDelegate.MODE_NIGHT_YES;
        else if ("라이트".equals(theme)) mode = AppCompatDelegate.MODE_NIGHT_NO;
        else mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void setupWindow() {
        // Extend into the system-bar area and match status/navigation icon colors to the theme.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        boolean dark = Ui.isDark(this);
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    // ---------------------------------------------------------------- layout

    private View buildContent() {
        drawer = new DrawerLayout(this);
        drawer.setBackgroundColor(Ui.background(this));

        // ===== main content
        LinearLayout mainColumn = Ui.vertical(this);
        drawer.addView(mainColumn, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = Ui.horizontal(this);
        ImageButton menuButton = new ImageButton(this);
        menuButton.setImageResource(R.drawable.ic_menu);
        menuButton.setColorFilter(Ui.onSurface(this));
        menuButton.setContentDescription("설정 메뉴");
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        menuButton.setBackgroundResource(tv.resourceId);
        menuButton.setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        int iconSize = Ui.dp(this, 48);
        topBar.addView(menuButton, new LinearLayout.LayoutParams(iconSize, iconSize));
        tabRecord = makeTopTab("녹음", 0);
        tabFiles = makeTopTab("파일", 1);
        topBar.addView(tabRecord, new LinearLayout.LayoutParams(0, iconSize, 1f));
        topBar.addView(tabFiles, new LinearLayout.LayoutParams(0, iconSize, 1f));
        topBar.addView(Ui.spacer(this, 48, 1));
        mainColumn.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));

        recorderView = new RecorderEditorView(this, audioEngine, dspProcessor, this,
                defaultFormat, storagePathUri, storagePathDisplay);
        fileBrowserView = new FileBrowserView(this, storagePathUri, file -> {
            selectTab(0);
            recorderView.loadInitialState(file, null);
        });

        pager = new ViewPager2(this);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new FixedPagesAdapter(new View[]{recorderView, fileBrowserView}));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateTopTabs(position);
                if (position == 1) fileBrowserView.onShown(storagePathUri);
                else fileBrowserView.onHidden();
            }
        });
        // Effect sliders and graphs live inside the pages; make horizontal paging less eager.
        mainColumn.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        updateTopTabs(0);

        // ===== drawer sheet
        LinearLayout sheet = Ui.vertical(this);
        int surface = com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainerLow, Ui.isDark(this) ? Color.parseColor("#1E1E1E") : Color.WHITE);
        sheet.setBackgroundColor(surface);
        sheet.setClickable(true);
        DrawerLayout.LayoutParams sheetLp = new DrawerLayout.LayoutParams(Ui.dp(this, 280), ViewGroup.LayoutParams.MATCH_PARENT);
        sheetLp.gravity = GravityCompat.START;
        drawer.addView(sheet, sheetLp);

        sheet.addView(Ui.spacer(this, 1, 16));
        TextView title = Ui.text(this, "전체 설정", 18, true);
        int p16 = Ui.dp(this, 16);
        title.setPadding(p16, p16, p16, p16);
        sheet.addView(title, Ui.matchWrap());
        sheet.addView(new MaterialDivider(this), Ui.matchWrap());
        sheet.addView(drawerItem("파일 설정", v -> { drawer.closeDrawers(); showFileSettingDialog(); }));
        sheet.addView(drawerItem("저장 위치", v -> { drawer.closeDrawers(); showStorageDialog(); }));
        View themeItem = drawerItem("", v -> { drawer.closeDrawers(); showThemeDialog(); });
        themeItemLabel = (TextView) themeItem;
        updateThemeLabel();
        sheet.addView(themeItem);
        View notifItem = drawerItem("알림 사용", v -> { drawer.closeDrawers(); showNotificationSettingsDialog(); });
        notificationItemLabel = (TextView) notifItem;
        sheet.addView(notifItem);
        updateNotificationDrawerItem();

        // Apply system-bar insets (Compose Scaffold used to do this).
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            mainColumn.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            sheet.setPadding(bars.left, bars.top, 0, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        return drawer;
    }

    private TextView makeTopTab(String label, int index) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        t.setOnClickListener(v -> selectTab(index));
        return t;
    }

    private void updateTopTabs(int selected) {
        int primary = Ui.primary(this);
        tabRecord.setTypeface(selected == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabRecord.setTextColor(selected == 0 ? primary : Color.GRAY);
        tabFiles.setTypeface(selected == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabFiles.setTextColor(selected == 1 ? primary : Color.GRAY);
    }

    private void selectTab(int index) {
        if (pager != null && pager.getCurrentItem() != index) pager.setCurrentItem(index, true);
    }

    private View drawerItem(String label, View.OnClickListener onClick) {
        TextView t = Ui.text(this, label, 14);
        int h = Ui.dp(this, 28);
        t.setPadding(h, 0, h, 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(Ui.dp(this, 56));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        t.setOnClickListener(onClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        return t;
    }

    private void updateThemeLabel() {
        if (themeItemLabel != null) {
            themeItemLabel.setText("테마 (" + prefs.getString("theme", "시스템 기본값") + ")");
        }
    }

    private void updateNotificationDrawerItem() {
        if (notificationItemLabel == null) return;
        notificationItemLabel.setTypeface(notificationEnabled ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        notificationItemLabel.setTextColor(notificationEnabled ? Ui.primary(this) : Ui.onSurface(this));
    }

    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawers();
                } else if (recorderView != null && recorderView.isRecordingNow()) {
                    recorderView.showExitConfirmDialog();
                } else {
                    long now = System.currentTimeMillis();
                    if (now - backPressedTime < 2000L) {
                        audioEngine.stopPreview();
                        finish();
                    } else {
                        backPressedTime = now;
                        Toast.makeText(MainActivity.this, "뒤로가기를 한 번 더 누르면 앱이 종료됩니다.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------- dialogs

    private LinearLayout dialogColumn() {
        LinearLayout col = Ui.vertical(this);
        int p = Ui.dp(this, 24);
        col.setPadding(p, Ui.dp(this, 8), p, 0);
        return col;
    }

    private void showFileSettingDialog() {
        LinearLayout col = dialogColumn();

        Ui.addSpaced(col, Ui.text(this, "기본 파일 형식", 13, true), 8);
        List<String> formats = Arrays.asList("m4a", "mp3", "wav");
        ChipGroup formatGroup = Ui.chipGroup(this, Arrays.asList("m4a (기본값)", "mp3", "wav"),
                Math.max(0, formats.indexOf(defaultFormat)), idx -> {
                    defaultFormat = formats.get(idx);
                    prefs.edit().putString("default_format", defaultFormat).apply();
                    if (recorderView != null) recorderView.setDefaultFormat(defaultFormat);
                });
        Ui.addSpaced(col, formatGroup, 8);
        Ui.addSpaced(col, new MaterialDivider(this), 8);

        Ui.addSpaced(col, Ui.text(this, "파일 이름 생성 규칙", 13, true), 8);
        List<String> rules = Arrays.asList("(없음)", "YY.MM.DD", "YYYY.MM.DD", "YYMMDD", "YYYYMMDD", "YY-MM-DD", "YYYY-MM-DD");
        String currentRule = prefs.getString("filename_rule", "(없음)");
        RadioGroup ruleGroup = new RadioGroup(this);
        for (String rule : rules) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(rule);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            ruleGroup.addView(rb);
            if (rule.equals(currentRule)) rb.setChecked(true);
            rb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) prefs.edit().putString("filename_rule", rule).apply();
            });
        }
        Ui.addSpaced(col, ruleGroup, 8);
        Ui.addSpaced(col, new MaterialDivider(this), 8);

        Ui.addSpaced(col, Ui.text(this, "저장 샘플레이트", 13, true), 8);
        List<String> rates = Arrays.asList("44100 Hz", "48000 Hz");
        Ui.addSpaced(col, Ui.chipGroup(this, rates, Math.max(0, rates.indexOf(prefs.getString("samplerate", "44100 Hz"))),
                idx -> prefs.edit().putString("samplerate", rates.get(idx)).apply()), 8);
        Ui.addSpaced(col, new MaterialDivider(this), 8);

        Ui.addSpaced(col, Ui.text(this, "M4A / MP3 비트레이트", 13, true), 8);
        List<String> bitrates = Arrays.asList("128 kbps", "192 kbps", "320 kbps");
        Ui.addSpaced(col, Ui.chipGroup(this, bitrates, Math.max(0, bitrates.indexOf(prefs.getString("bitrate", "192 kbps"))),
                idx -> prefs.edit().putString("bitrate", bitrates.get(idx)).apply()), 8);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        new MaterialAlertDialogBuilder(this)
                .setTitle("파일 설정")
                .setView(scroll)
                .setPositiveButton("확인", null)
                .show();
    }

    private void showStorageDialog() {
        LinearLayout col = dialogColumn();
        Ui.addSpaced(col, Ui.muted(this, "현재 저장 경로 (터치하여 변경):", 12), 8);

        final AlertDialog[] holder = new AlertDialog[1];
        int primary = Ui.primary(this);
        int surfaceVariant = com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.rounded(surfaceVariant, Ui.dp(this, 6), Ui.dp(this, 1), primary));
        int p10 = Ui.dp(this, 10);
        box.setPadding(p10, p10, p10, p10);
        TextView path = Ui.text(this, storagePathDisplay, 13, true);
        path.setTextColor(primary);
        box.addView(path);
        box.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            folderPickerLauncher.launch(storagePathUri);
        });
        Ui.addSpaced(col, box, 8);
        TextView hint = Ui.text(this, "경로를 터치하면 디렉토리 선택 탐색기가 열립니다.", 11);
        hint.setTextColor(Ui.isDark(this) ? Color.LTGRAY : Color.DKGRAY);
        Ui.addSpaced(col, hint, 8);

        holder[0] = new MaterialAlertDialogBuilder(this)
                .setTitle("저장 위치 설정")
                .setView(col)
                .setPositiveButton("닫기", null)
                .show();
    }

    private void showThemeDialog() {
        String current = prefs.getString("theme", "시스템 기본값");
        int checked = Math.max(0, THEMES.indexOf(current));
        new MaterialAlertDialogBuilder(this)
                .setTitle("테마 선택")
                .setSingleChoiceItems(THEMES.toArray(new String[0]), checked, (dialog, which) -> {
                    String theme = THEMES.get(which);
                    prefs.edit().putString("theme", theme).apply();
                    updateThemeLabel();
                    dialog.dismiss();
                    // Recreates the Activity with the new DayNight configuration.
                    applyNightMode(theme);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showNotificationSettingsDialog() {
        LinearLayout col = dialogColumn();
        Ui.addSpaced(col, Ui.text(this, "알림을 켜면 알림창에서 녹음을 시작하고 녹음·미리 듣기 상태를 확인할 수 있습니다.", 14), 12);
        Ui.addSpaced(col, Ui.text(this, "알림 사용은 앱이 실행 중일 때 ON으로 켜주세요. 앱을 종료한 뒤 다시 실행하면 기본적으로 OFF로 초기화됩니다. 녹음 중에는 Android가 요구하는 녹음 알림이 표시될 수 있으며, 녹음이 끝나면 OFF 설정에 따라 사라집니다.", 14), 12);

        LinearLayout row = Ui.horizontal(this);
        LinearLayout labels = Ui.vertical(this);
        labels.addView(Ui.text(this, "알림 사용", 14, true));
        notificationStateLabel = Ui.text(this, "", 13);
        notificationStateLabel.setTextColor(Ui.onSurfaceVariant(this));
        labels.addView(notificationStateLabel);
        row.addView(labels, Ui.weight(1f));
        notificationSwitch = new MaterialSwitch(this);
        notificationSwitch.setChecked(notificationEnabled);
        notificationSwitch.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked != notificationEnabled) setNotificationEnabled(isChecked);
        });
        row.addView(notificationSwitch, Ui.wrap());
        Ui.addSpaced(col, row, 12);
        updateNotificationStateLabel();

        new MaterialAlertDialogBuilder(this)
                .setTitle("알림 설정")
                .setView(col)
                .setPositiveButton("완료", null)
                .setOnDismissListener(d -> {
                    notificationSwitch = null;
                    notificationStateLabel = null;
                })
                .show();
    }

    private void updateNotificationStateLabel() {
        if (notificationStateLabel != null) {
            notificationStateLabel.setText(notificationEnabled ? "ON · 현재 실행에서 활성화" : "OFF · 현재 실행에서 비활성화");
        }
    }

    private void setNotificationEnabledUi(boolean enabled) {
        notificationEnabled = enabled;
        if (notificationSwitch != null && notificationSwitch.isChecked() != enabled) notificationSwitch.setChecked(enabled);
        updateNotificationStateLabel();
        updateNotificationDrawerItem();
    }

    private void setNotificationEnabled(boolean enabled) {
        if (!enabled) {
            RecordingService.disableNotificationForCurrentSession(this);
            setNotificationEnabledUi(false);
            return;
        }
        boolean needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        if (needsRuntimePermission) {
            // Keep the switch showing the old state until the permission result arrives.
            if (notificationSwitch != null) notificationSwitch.setChecked(notificationEnabled);
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            RecordingService.enableNotificationForCurrentSession(this);
            setNotificationEnabledUi(true);
            openNotificationSettingsIfBlocked(this);
        }
    }

    /** Opens the exact Android notification setting that is blocking this app, if any. */
    private static void openNotificationSettingsIfBlocked(Context context) {
        String reason = RecordingService.notificationBlockReason(context);
        if (reason == null) return;
        Toast.makeText(context, "앱의 알림 스위치는 켜져 있지만 " + reason, Toast.LENGTH_LONG).show();

        Intent intent;
        if (reason.contains("알림 채널")) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, RecordingService.CHANNEL_ID);
        } else {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // Keep the diagnostic toast even if this Android build lacks the settings activity.
        }
    }

    // ---------------------------------------------------------------- pager adapter

    /** ViewPager2 adapter over two pre-built, long-lived page views. */
    private static final class FixedPagesAdapter extends RecyclerView.Adapter<FixedPagesAdapter.Holder> {
        private final View[] pages;

        FixedPagesAdapter(View[] pages) {
            this.pages = pages;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout frame;

            Holder(FrameLayout frame) {
                super(frame);
                this.frame = frame;
            }
        }

        @Override public int getItemViewType(int position) { return position; }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(parent.getContext());
            frame.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View page = pages[viewType];
            if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
            frame.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(frame);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { }

        @Override public int getItemCount() { return pages.length; }
    }
}
