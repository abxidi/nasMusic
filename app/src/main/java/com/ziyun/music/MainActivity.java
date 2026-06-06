package com.ziyun.music;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ziyun.music.data.MusicRepository;
import com.ziyun.music.data.MusicLibraryStore;
import com.ziyun.music.data.NasConnectionStore;
import com.ziyun.music.model.Playlist;
import com.ziyun.music.model.Song;
import com.ziyun.music.network.NasClient;
import com.ziyun.music.player.PlayerController;
import com.ziyun.music.ui.RecordView;

import java.util.ArrayList;
import java.util.Collections;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements PlayerController.Listener {
    private static final int DARK = Color.rgb(15, 16, 32);
    private static final int PAGE = Color.rgb(249, 250, 251);

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private enum Screen {
        SPLASH,
        CONNECT,
        HOME,
        LIBRARY,
        PLAYLIST,
        SEARCH,
        PLAYER,
        QUEUE,
        ME
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MusicRepository repository;
    private MusicLibraryStore libraryStore;
    private NasConnectionStore connectionStore;
    private NasClient nasClient;
    private PlayerController player;
    private FrameLayout root;
    private Screen currentScreen = Screen.SPLASH;
    private Screen lastMainScreen = Screen.HOME;
    private Runnable splashTask;
    private boolean nasConnectInProgress;
    private List<NasClient.DiscoveredNas> discoveredNasDevices = Collections.emptyList();
    private int nasDiscoveryGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new MusicRepository();
        libraryStore = new MusicLibraryStore(this);
        connectionStore = new NasConnectionStore(this);
        List<Song> savedSongs = libraryStore.loadSongs();
        if (!savedSongs.isEmpty()) {
            repository.replaceWithNasSongs(savedSongs);
        }
        nasClient = new NasClient();
        player = new PlayerController(repository.songs());
        player.setListener(this);

        root = new FrameLayout(this);
        setContentView(root);
        show(Screen.SPLASH);

        splashTask = () -> show(firstScreenAfterSplash());
        handler.postDelayed(splashTask, 900);
    }

    private Screen firstScreenAfterSplash() {
        NasConnectionStore.Profile profile = connectionStore.profile();
        return nasClient.isConnected() || profile.hasSyncedLibrary() ? Screen.HOME : Screen.CONNECT;
    }

    @Override
    protected void onDestroy() {
        if (splashTask != null) {
            handler.removeCallbacks(splashTask);
        }
        networkExecutor.shutdownNow();
        player.release();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    @Override
    public void onPlaybackChanged() {
        runOnUiThread(() -> syncPlaybackUi(root));
    }

    private void show(Screen screen) {
        if (isMainScreen(screen)) {
            lastMainScreen = screen;
        }
        currentScreen = screen;
        setBars(isDarkScreen(screen));

        root.removeAllViews();
        View page = getLayoutInflater().inflate(layoutFor(screen), root, false);
        root.addView(page, new FrameLayout.LayoutParams(match(), match()));
        applySafeArea(page, screen);

        bindCommon(page);
        bindScreen(page, screen);
        syncPlaybackUi(page);
    }

    private int layoutFor(Screen screen) {
        switch (screen) {
            case SPLASH:
                return R.layout.ui_splash;
            case CONNECT:
                return R.layout.ui_connect_nas;
            case LIBRARY:
                return R.layout.ui_library;
            case PLAYLIST:
                return R.layout.ui_playlist_detail;
            case SEARCH:
                return R.layout.ui_search;
            case PLAYER:
                return R.layout.ui_player;
            case QUEUE:
                return R.layout.ui_queue;
            case ME:
                return R.layout.ui_me;
            case HOME:
            default:
                return R.layout.ui_home;
        }
    }

    private boolean isMainScreen(Screen screen) {
        return screen == Screen.HOME || screen == Screen.LIBRARY || screen == Screen.PLAYLIST || screen == Screen.ME;
    }

    private boolean isDarkScreen(Screen screen) {
        return screen == Screen.SPLASH || screen == Screen.PLAYER || screen == Screen.QUEUE;
    }

    private void bindCommon(View page) {
        click(page, R.id.nav_home, () -> show(Screen.HOME));
        click(page, R.id.nav_library, () -> show(Screen.LIBRARY));
        click(page, R.id.nav_playlists, () -> show(Screen.PLAYLIST));
        click(page, R.id.nav_me, () -> show(Screen.ME));

        click(page, R.id.mini_player, () -> show(Screen.PLAYER));
        click(page, R.id.mini_play, () -> {
            player.toggle();
            syncPlaybackUi(root);
        });
        click(page, R.id.mini_queue, () -> show(Screen.QUEUE));
    }

    private void bindScreen(View page, Screen screen) {
        switch (screen) {
            case CONNECT:
                startNasDiscovery(page);
                prefillNasProfile(page);
                click(page, R.id.back_button, () -> show(Screen.ME));
                click(page, R.id.discovery_row, () -> applyDiscoveredNas(page));
                click(page, R.id.connect_primary, () -> connectNas(page));
                click(page, R.id.connect_offline, () -> {
                    toast("已进入离线模式");
                    show(Screen.LIBRARY);
                });
                break;
            case HOME:
                bindConnectionSummary(page);
                click(page, R.id.home_search_button, () -> show(Screen.SEARCH));
                click(page, R.id.home_search_entry, () -> show(Screen.SEARCH));
                bindSong(page, "Lake Lights", false);
                bindSong(page, "南方夜航", false);
                bindSong(page, "Private Cloud", false);
                break;
            case LIBRARY:
                click(page, R.id.library_search_button, () -> show(Screen.SEARCH));
                bindSong(page, "After Rain", false);
                bindSong(page, "北站以南", false);
                bindSong(page, "Cold Drive", false);
                wireText(page, "/music/jazz/live", () -> {
                    player.playQueue(repository.songs(), 4);
                    toast("已从 /music/jazz/live 开始播放");
                    syncPlaybackUi(root);
                });
                break;
            case PLAYLIST:
                click(page, R.id.playlist_search_button, () -> show(Screen.SEARCH));
                click(page, R.id.playlist_play_all, () -> {
                    Playlist playlist = repository.primaryPlaylist();
                    player.playQueue(playlist.songs(), 0);
                    show(Screen.PLAYER);
                });
                click(page, R.id.playlist_shuffle, () -> {
                    player.toggleShuffle();
                    player.playQueue(repository.primaryPlaylist().songs(), 0);
                    show(Screen.PLAYER);
                });
                click(page, R.id.playlist_download, () -> toast("已加入离线下载队列"));
                bindPlaylistSong(page, "Moon Bridge");
                bindPlaylistSong(page, "午夜电台");
                bindPlaylistSong(page, "Riverside Tape");
                bindPlaylistSong(page, "城市低频");
                bindPlaylistSong(page, "Quiet Server");
                break;
            case SEARCH:
                click(page, R.id.back_button, () -> show(lastMainScreen));
                bindSong(page, "Private Cloud", false);
                bindSong(page, "Cloudless Night", false);
                wireText(page, "查看专辑", () -> toast("专辑详情将在后续页面接入"));
                break;
            case PLAYER:
                click(page, R.id.back_button, () -> show(lastMainScreen));
                bindPlayerControls(page, true);
                break;
            case QUEUE:
                click(page, R.id.back_button, () -> show(Screen.PLAYER));
                bindQueueDismiss(page);
                bindPlayerControls(page, false);
                wireText(page, "⇄  随机", () -> {
                    player.toggleShuffle();
                    toast(player.isShuffle() ? "已开启随机播放" : "已关闭随机播放");
                });
                wireText(page, "↻  循环", () -> {
                    player.toggleRepeat();
                    toast(player.isRepeat() ? "已开启循环播放" : "已关闭循环播放");
                });
                wireText(page, "清空", () -> toast("清空队列前需要确认"));
                bindSong(page, "午夜电台", false);
                bindSong(page, "Riverside Tape", false);
                bindSong(page, "山谷回声", false);
                bindSong(page, "Quiet Server", false);
                break;
            case ME:
                wireText(page, "DiskStation-Home", () -> show(Screen.CONNECT));
                wireText(page, "连接诊断正常", this::diagnoseNas);
                bindConnectionSummary(page);
                wireText(page, "播放设置", () -> toast("当前为原始音质播放"));
                wireText(page, "仅 Wi-Fi 下载", () -> toast("仅 Wi-Fi 下载已开启"));
                click(page, R.id.wifi_download_toggle, () -> toast("仅 Wi-Fi 下载已开启"));
                wireText(page, "外观设置", () -> toast("当前为跟随系统"));
                wireText(page, "安全设置", () -> toast("HTTPS 可信"));
                break;
            case SPLASH:
            default:
                break;
        }
    }

    private void connectNas(View page) {
        if (nasConnectInProgress) {
            toast("正在连接 NAS，请稍候");
            return;
        }
        List<EditText> fields = collect(page, EditText.class);
        String address = valueAt(fields, 0);
        String account = valueAt(fields, 1);
        String password = valueAt(fields, 2);
        String otp = valueAt(fields, 3);
        nasConnectInProgress = true;
        setConnectButtonEnabled(page, false);
        toast("正在连接 NAS...");

        networkExecutor.execute(() -> {
            NasSyncResult syncResult = loginAndSyncSongs(address, account, password, otp);
            runOnUiThread(() -> handleNasSyncResult(page, syncResult));
        });
    }

    private NasSyncResult loginAndSyncSongs(String address, String account, String password, String otp) {
        NasClient.ConnectionResult connection = nasClient.connect(address, account, password, otp, true);
        if (!connection.success) {
            return new NasSyncResult(connection, Collections.emptyList(), null, address, account);
        }

        try {
            return new NasSyncResult(connection, nasClient.fetchSongs(), null, address, account);
        } catch (Exception e) {
            return new NasSyncResult(connection, Collections.emptyList(), e, address, account);
        }
    }

    private void handleNasSyncResult(View page, NasSyncResult syncResult) {
        nasConnectInProgress = false;
        setConnectButtonEnabled(page, true);

        NasClient.ConnectionResult connection = syncResult.connection;
        if (!connection.success) {
            toast(connection.title + "：" + connection.message);
            return;
        }

        connectionStore.saveProfile(syncResult.address, syncResult.account);
        if (!syncResult.songs.isEmpty()) {
            connectionStore.saveSyncStats(syncResult.songs.size());
            repository.replaceWithNasSongs(syncResult.songs);
            libraryStore.saveSongs(syncResult.songs);
            player.playQueue(repository.songs(), 0);
            show(Screen.HOME);
            toast("NAS 登录成功，已同步 " + syncResult.songs.size() + " 首歌曲");
            return;
        }

        show(Screen.HOME);
        if (syncResult.error != null) {
            toast("登录成功，曲库同步失败：" + readableError(syncResult.error));
        } else {
            toast("登录成功，Audio Station 暂无可同步歌曲");
        }
    }

    private void diagnoseNas() {
        toast("正在检查 NAS 连接...");
        NasConnectionStore.Profile profile = connectionStore.profile();
        if (!nasClient.isConnected() && !profile.address.isEmpty()) {
            nasClient.configureBaseUrl(profile.address);
        }
        networkExecutor.execute(() -> {
            NasClient.DiagnosticResult result = nasClient.diagnose();
            runOnUiThread(() -> toast(result.title + "：" + result.message));
        });
    }

    private void applyDiscoveredNas(View page) {
        List<NasClient.DiscoveredNas> devices = discoveredNasDevices;
        if (devices.isEmpty()) {
            toast("未发现局域网 NAS");
            return;
        }
        List<EditText> fields = collect(page, EditText.class);
        if (!fields.isEmpty()) {
            NasClient.DiscoveredNas device = devices.get(0);
            fields.get(0).setText(device.address);
            toast("已填入 " + device.name);
        }
    }

    private void startNasDiscovery(View page) {
        int generation = ++nasDiscoveryGeneration;
        discoveredNasDevices = Collections.emptyList();
        showDiscoveryStatus(page, "正在扫描局域网 NAS", "检测同网段 DSM 5000/5001 端口", "扫描中", true);

        networkExecutor.execute(() -> {
            List<NasClient.DiscoveredNas> devices = nasClient.discoverLocalDevices();
            runOnUiThread(() -> {
                if (generation != nasDiscoveryGeneration || currentScreen != Screen.CONNECT) {
                    return;
                }
                discoveredNasDevices = devices;
                bindDiscoveredNas(page, devices);
            });
        });
    }

    private void bindDiscoveredNas(View page, List<NasClient.DiscoveredNas> devices) {
        View row = page.findViewById(R.id.discovery_row);
        if (row == null) {
            return;
        }

        if (devices.isEmpty()) {
            showDiscoveryStatus(page, "未发现局域网 NAS", "请确认手机与 NAS 在同一局域网，或手动输入地址", "手动输入", true);
            return;
        }

        NasClient.DiscoveredNas device = devices.get(0);
        showDiscoveryStatus(page, device.name, device.address + " · " + device.capability, "已发现", true);
    }

    private void showDiscoveryStatus(View page, String titleText, String subtitleText, String statusText, boolean visible) {
        View row = page.findViewById(R.id.discovery_row);
        if (row == null) {
            return;
        }

        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        TextView title = page.findViewById(R.id.discovery_title);
        TextView subtitle = page.findViewById(R.id.discovery_subtitle);
        TextView status = page.findViewById(R.id.discovery_status);
        if (title != null) {
            title.setText(titleText);
        }
        if (subtitle != null) {
            subtitle.setText(subtitleText);
        }
        if (status != null) {
            status.setText(statusText);
        }
    }

    private void prefillNasProfile(View page) {
        NasConnectionStore.Profile profile = connectionStore.profile();
        if (!profile.hasConnectionInfo()) {
            return;
        }

        List<EditText> fields = collect(page, EditText.class);
        if (fields.size() > 0) {
            fields.get(0).setText(profile.address);
        }
        if (fields.size() > 1) {
            fields.get(1).setText(profile.account);
        }
    }

    private void bindConnectionSummary(View page) {
        NasConnectionStore.Profile profile = connectionStore.profile();
        String nasName = profile.address.isEmpty() ? "未连接 NAS" : displayNasName(profile.address);
        String nasAddress = profile.address.isEmpty() ? "请连接 Synology NAS" : profile.address;
        String syncText = profile.hasSyncedLibrary()
                ? profile.lastSyncCount + " 首 · " + formatSyncTime(profile.lastSyncTime)
                : "等待首次同步";
        String statusText = nasClient.isConnected() ? "内网在线 · Audio Station" : "离线模式 · 本地索引";

        replaceText(page, "DiskStation-Home", nasName);
        replaceText(page, "home-nas.example.com:5001", nasAddress);
        replaceText(page, "内网在线 · FLAC 原始播放", statusText);
        replaceText(page, "DSM API 与 Audio Station 能力可用", syncText);
        replaceText(page, "连接诊断正常", nasClient.isConnected() ? "连接诊断正常" : "运行连接诊断");
    }

    private String displayNasName(String address) {
        String value = address == null ? "" : address.trim();
        value = value.replace("https://", "").replace("http://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        return value.isEmpty() ? "Synology NAS" : value;
    }

    private String formatSyncTime(long timestamp) {
        if (timestamp <= 0L) {
            return "未同步";
        }
        return "同步于 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(timestamp));
    }

    private void setConnectButtonEnabled(View page, boolean enabled) {
        View button = page.findViewById(R.id.connect_primary);
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.64f);
        }
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private String valueAt(List<EditText> fields, int index) {
        if (index < 0 || index >= fields.size()) {
            return "";
        }
        return fields.get(index).getText().toString();
    }

    private void bindSong(View page, String title, boolean openPlayer) {
        Song song = songByTitle(title);
        if (song == null) {
            return;
        }
        wireText(page, title, () -> {
            player.playSong(song, repository.songs());
            if (openPlayer) {
                show(Screen.PLAYER);
            } else {
                syncPlaybackUi(root);
                toast("正在播放：" + song.title);
            }
        });
    }

    private void bindPlaylistSong(View page, String title) {
        Song song = songByTitle(title);
        if (song == null) {
            return;
        }
        List<Song> songs = repository.primaryPlaylist().songs();
        wireText(page, title, () -> {
            player.playSong(song, songs);
            syncPlaybackUi(root);
            toast("正在播放：" + song.title);
        });
    }

    private Song songByTitle(String title) {
        for (Song song : repository.songs()) {
            if (song.title.equals(title)) {
                return song;
            }
        }
        return null;
    }

    private void syncPlaybackUi(View page) {
        if (page == null || player.currentSong() == null) {
            return;
        }
        Song song = player.currentSong();
        setText(page, R.id.mini_title, song.title);
        setText(page, R.id.mini_subtitle, miniSubtitle(song));
        setText(page, R.id.mini_play, player.isPlaying() ? "Ⅱ" : "▶");
        setText(page, R.id.player_title, song.title);
        setText(page, R.id.player_artist, song.artist);
        setPlayerPlayIcon(page, player.isPlaying());
        bindPlayerRecord(page, song);
    }

    private void bindPlayerControls(View page, boolean queueButtonOpensQueue) {
        click(page, R.id.player_shuffle, () -> {
            player.toggleShuffle();
            toast(player.isShuffle() ? "已开启随机播放" : "已关闭随机播放");
        });
        click(page, R.id.player_prev, () -> {
            player.previous();
            syncPlaybackUi(root);
        });
        click(page, R.id.player_play, () -> {
            player.toggle();
            syncPlaybackUi(root);
        });
        click(page, R.id.player_next, () -> {
            player.next();
            syncPlaybackUi(root);
        });
        click(page, R.id.player_queue, () -> {
            if (queueButtonOpensQueue) {
                show(Screen.QUEUE);
            } else {
                toast("正在查看播放队列");
            }
        });
        click(page, R.id.player_download, () -> toast("本曲已缓存"));
        click(page, R.id.player_cast, () -> toast("投放能力将在后续版本接入"));
        click(page, R.id.player_equalizer, () -> toast("均衡器将在后续版本接入"));
    }

    private void bindQueueDismiss(View page) {
        click(page, R.id.queue_dismiss_area, () -> show(Screen.PLAYER));

        View dragHandle = page.findViewById(R.id.queue_drag_handle);
        if (dragHandle == null) {
            return;
        }

        final float threshold = dp(48);
        final float[] startY = new float[1];
        final boolean[] dismissed = new boolean[1];
        dragHandle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0] = event.getRawY();
                    dismissed[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (!dismissed[0] && event.getRawY() - startY[0] >= threshold) {
                        dismissed[0] = true;
                        show(Screen.PLAYER);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dismissed[0] = false;
                    return true;
                default:
                    return true;
            }
        });
    }

    private void setPlayerPlayIcon(View rootView, boolean playing) {
        ImageView view = rootView.findViewById(R.id.player_play);
        if (view != null) {
            view.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        }
    }

    private void bindPlayerRecord(View rootView, Song song) {
        RecordView view = rootView.findViewById(R.id.player_record);
        if (view != null) {
            view.bind(song, player.isPlaying());
        }
    }

    private String miniSubtitle(Song song) {
        if (currentScreen == Screen.ME) {
            return "正在播放";
        }
        if (currentScreen == Screen.PLAYLIST) {
            return song.downloaded ? "已缓存" : "NAS";
        }
        return "NAS · " + song.format;
    }

    @SuppressWarnings("deprecation")
    private void handleBack() {
        switch (currentScreen) {
            case CONNECT:
                show(Screen.ME);
                break;
            case SEARCH:
                show(lastMainScreen);
                break;
            case PLAYER:
                show(lastMainScreen);
                break;
            case QUEUE:
                show(Screen.PLAYER);
                break;
            case PLAYLIST:
                show(Screen.HOME);
                break;
            case LIBRARY:
            case ME:
                show(Screen.HOME);
                break;
            case HOME:
            default:
                super.onBackPressed();
                break;
        }
    }

    private void click(View rootView, int id, Runnable action) {
        View target = rootView.findViewById(id);
        if (target != null) {
            target.setOnClickListener(v -> action.run());
        }
    }

    private void wireText(View rootView, String text, Runnable action) {
        for (TextView view : collect(rootView, TextView.class)) {
            if (text.contentEquals(view.getText())) {
                view.setOnClickListener(v -> action.run());
            }
        }
    }

    private void setText(View rootView, int id, String value) {
        TextView view = rootView.findViewById(id);
        if (view != null) {
            view.setText(value);
        }
    }

    private void replaceText(View rootView, String oldValue, String newValue) {
        for (TextView view : collect(rootView, TextView.class)) {
            if (oldValue.contentEquals(view.getText())) {
                view.setText(newValue);
            }
        }
    }

    private <T extends View> List<T> collect(View view, Class<T> type) {
        if (view == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        collectInto(view, type, result);
        return result;
    }

    private <T extends View> void collectInto(View view, Class<T> type, List<T> result) {
        if (type.isInstance(view)) {
            result.add(type.cast(view));
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectInto(group.getChildAt(i), type, result);
        }
    }

    private void applySafeArea(View page, Screen screen) {
        View topTarget = topInsetTarget(page, screen);
        View bottomNav = bottomNav(page);
        View miniPlayer = page.findViewById(R.id.mini_player);
        List<ScrollView> scrollViews = collect(page, ScrollView.class);
        List<View> bottomAnchored = bottomAnchoredChildren(page, bottomNav, miniPlayer);

        Padding baseTopPadding = Padding.from(topTarget);
        Size baseTopSize = Size.from(topTarget);
        Padding baseNavPadding = Padding.from(bottomNav);
        Size baseNavSize = Size.from(bottomNav);
        Margins baseMiniMargins = Margins.from(miniPlayer);
        List<Padding> baseScrollPadding = paddings(scrollViews);
        List<Padding> baseBottomPadding = paddings(bottomAnchored);

        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();

            if (topTarget != null) {
                baseTopPadding.apply(topTarget, 0, top, 0, 0);
                if (screen == Screen.HOME || screen == Screen.PLAYLIST) {
                    baseTopSize.apply(topTarget, 0, top);
                }
            }

            for (int i = 0; i < scrollViews.size(); i++) {
                baseScrollPadding.get(i).apply(scrollViews.get(i), 0, 0, 0, bottom);
            }

            if (bottomNav != null) {
                baseNavPadding.apply(bottomNav, 0, 0, 0, bottom);
                baseNavSize.apply(bottomNav, 0, bottom);
            }

            if (miniPlayer != null) {
                baseMiniMargins.apply(miniPlayer, 0, 0, 0, bottom);
            }

            for (int i = 0; i < bottomAnchored.size(); i++) {
                baseBottomPadding.get(i).apply(bottomAnchored.get(i), 0, 0, 0, bottom);
            }
            return insets;
        });
        page.requestApplyInsets();
    }

    private View topInsetTarget(View page, Screen screen) {
        View hero = page.findViewById(R.id.home_hero);
        if (hero != null) {
            return hero;
        }
        if (page instanceof ScrollView) {
            return firstChild((ViewGroup) page);
        }
        if (!(page instanceof ViewGroup)) {
            return page;
        }
        ViewGroup group = (ViewGroup) page;
        View first = firstChild(group);
        if (first instanceof ScrollView) {
            View content = firstChild((ViewGroup) first);
            if (screen == Screen.PLAYLIST && content instanceof ViewGroup) {
                return firstChild((ViewGroup) content);
            }
            return content;
        }
        return first;
    }

    private View bottomNav(View page) {
        View navHome = page.findViewById(R.id.nav_home);
        if (navHome == null) {
            return null;
        }
        ViewParent parent = navHome.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    private List<View> bottomAnchoredChildren(View page, View bottomNav, View miniPlayer) {
        if (!(page instanceof FrameLayout)) {
            return Collections.emptyList();
        }
        List<View> result = new ArrayList<>();
        ViewGroup group = (ViewGroup) page;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == bottomNav || child == miniPlayer) {
                continue;
            }
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                continue;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
            if (params.gravity >= 0
                    && (params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                result.add(child);
            }
        }
        return result;
    }

    private View firstChild(ViewGroup group) {
        return group.getChildCount() > 0 ? group.getChildAt(0) : group;
    }

    private List<Padding> paddings(List<? extends View> views) {
        List<Padding> result = new ArrayList<>();
        for (View view : views) {
            result.add(Padding.from(view));
        }
        return result;
    }

    private static final class Padding {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Padding(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        static Padding from(View view) {
            if (view == null) {
                return new Padding(0, 0, 0, 0);
            }
            return new Padding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
        }

        void apply(View view, int extraLeft, int extraTop, int extraRight, int extraBottom) {
            if (view != null) {
                view.setPadding(left + extraLeft, top + extraTop, right + extraRight, bottom + extraBottom);
            }
        }
    }

    private static final class Size {
        private final int width;
        private final int height;

        private Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        static Size from(View view) {
            if (view == null || view.getLayoutParams() == null) {
                return new Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            return new Size(params.width, params.height);
        }

        void apply(View view, int extraWidth, int extraHeight) {
            if (view == null || view.getLayoutParams() == null || height <= 0) {
                return;
            }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = width > 0 ? width + extraWidth : width;
            params.height = height + extraHeight;
            view.setLayoutParams(params);
        }
    }

    private static final class Margins {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Margins(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        static Margins from(View view) {
            if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                return new Margins(0, 0, 0, 0);
            }
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            return new Margins(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin);
        }

        void apply(View view, int extraLeft, int extraTop, int extraRight, int extraBottom) {
            if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                return;
            }
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.leftMargin = left + extraLeft;
            params.topMargin = top + extraTop;
            params.rightMargin = right + extraRight;
            params.bottomMargin = bottom + extraBottom;
            view.setLayoutParams(params);
        }
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void setBars(boolean dark) {
        Window window = getWindow();
        window.setStatusBarColor(dark ? DARK : PAGE);
        window.setNavigationBarColor(dark ? DARK : Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int flags = dark ? 0 : mask;
                controller.setSystemBarsAppearance(flags, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(dark ? 0 : flags);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class NasSyncResult {
        private final NasClient.ConnectionResult connection;
        private final List<Song> songs;
        private final Exception error;
        private final String address;
        private final String account;

        private NasSyncResult(NasClient.ConnectionResult connection, List<Song> songs, Exception error, String address, String account) {
            this.connection = connection;
            this.songs = songs == null ? Collections.emptyList() : songs;
            this.error = error;
            this.address = address == null ? "" : address;
            this.account = account == null ? "" : account;
        }
    }
}
