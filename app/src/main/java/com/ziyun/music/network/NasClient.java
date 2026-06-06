package com.ziyun.music.network;

import android.graphics.Color;

import com.ziyun.music.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NasClient {
    private static final int TIMEOUT_MS = 15000;
    private static final int SONG_PAGE_SIZE = 500;
    private static final int MAX_SONG_PAGES = 100;

    private static final String API_AUTH = "SYNO.API.Auth";
    private static final String API_SONG = "SYNO.AudioStation.Song";
    private static final String API_STREAM = "SYNO.AudioStation.Stream";

    private final Map<String, ApiSpec> apiSpecs = new HashMap<>();

    private String baseUrl;
    private String sid;

    public static class DiscoveredNas {
        public final String name;
        public final String address;
        public final String capability;

        public DiscoveredNas(String name, String address, String capability) {
            this.name = name;
            this.address = address;
            this.capability = capability;
        }
    }

    public static class ConnectionResult {
        public final boolean success;
        public final String title;
        public final String message;

        public ConnectionResult(boolean success, String title, String message) {
            this.success = success;
            this.title = title;
            this.message = message;
        }
    }

    public static class DiagnosticResult {
        public final boolean success;
        public final String title;
        public final String message;

        public DiagnosticResult(boolean success, String title, String message) {
            this.success = success;
            this.title = title;
            this.message = message;
        }
    }

    public List<DiscoveredNas> discoverLocalDevices() {
        return new ArrayList<>();
    }

    public ConnectionResult connect(String address, String account, String password, String otp, boolean verifyCertificate) {
        if (address == null || address.trim().isEmpty()) {
            return new ConnectionResult(false, "NAS 地址不能为空", "请输入 DSM 地址或选择局域网发现的设备。");
        }
        if (account == null || account.trim().isEmpty() || password == null || password.isEmpty()) {
            return new ConnectionResult(false, "账号信息不完整", "请输入 NAS 账号和密码。");
        }

        try {
            baseUrl = normalizeBaseUrl(address);
            sid = null;
            apiSpecs.clear();

            discoverApis();
            if (!hasAudioStationApis()) {
                return new ConnectionResult(false, "Audio Station 不可用", "DSM 未返回歌曲或播放流 API，请确认已安装并启用 Audio Station。");
            }

            ApiSpec auth = api(API_AUTH, "auth.cgi", 6);
            StringBuilder path = new StringBuilder("/webapi/")
                    .append(auth.path)
                    .append("?api=").append(enc(API_AUTH))
                    .append("&version=").append(auth.version(6))
                    .append("&method=login")
                    .append("&account=").append(enc(account.trim()))
                    .append("&passwd=").append(enc(password))
                    .append("&session=AudioStation")
                    .append("&format=sid");

            if (otp != null && !otp.trim().isEmpty()) {
                path.append("&otp_code=").append(enc(otp.trim()));
            }

            JSONObject json = getJson(path.toString());
            if (!json.optBoolean("success", false)) {
                return new ConnectionResult(false, "登录失败", authErrorMessage(json));
            }

            JSONObject data = json.optJSONObject("data");
            sid = data == null ? null : data.optString("sid", "");
            if (sid == null || sid.isEmpty()) {
                return new ConnectionResult(false, "登录失败", "DSM 未返回有效会话，请检查账号权限。");
            }

            String protocol = baseUrl.startsWith("https://") ? "HTTPS" : "HTTP";
            return new ConnectionResult(true, "NAS 登录成功", protocol + " 会话已建立，Audio Station API 可用。");
        } catch (Exception e) {
            sid = null;
            return new ConnectionResult(false, "连接失败", readableError(e));
        }
    }

    public boolean isConnected() {
        return baseUrl != null && sid != null && !sid.isEmpty();
    }

    public DiagnosticResult diagnose() {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return new DiagnosticResult(false, "未连接 NAS", "请先完成 NAS 登录，再运行连接诊断。");
        }

        try {
            discoverApis();
            if (!hasAudioStationApis()) {
                return new DiagnosticResult(false, "Audio Station 不可用", "DSM 可访问，但未发现歌曲或播放流 API。");
            }
            if (!isConnected()) {
                return new DiagnosticResult(false, "会话已失效", "DSM API 可用，请重新登录以恢复 Audio Station 会话。");
            }
            return new DiagnosticResult(true, "连接诊断正常", "DSM API、Audio Station API 与当前登录会话均可用。");
        } catch (Exception e) {
            return new DiagnosticResult(false, "连接诊断失败", readableError(e));
        }
    }

    public List<Song> fetchSongs() throws Exception {
        ensureConnected();

        List<Song> result = new ArrayList<>();
        int offset = 0;
        int total = -1;

        for (int page = 0; page < MAX_SONG_PAGES; page++) {
            ApiSpec songApi = api(API_SONG, "AudioStation/song.cgi", 3);
            String path = new StringBuilder("/webapi/")
                    .append(songApi.path)
                    .append("?api=").append(enc(API_SONG))
                    .append("&version=").append(songApi.version(3))
                    .append("&method=list")
                    .append("&library=all")
                    .append("&limit=").append(SONG_PAGE_SIZE)
                    .append("&offset=").append(offset)
                    .append("&additional=song_tag,song_audio")
                    .append("&_sid=").append(enc(sid))
                    .toString();

            JSONObject json = getJson(path);
            if (!json.optBoolean("success", false)) {
                throw new IllegalStateException("曲库同步失败：" + audioErrorMessage(json));
            }

            JSONObject data = json.optJSONObject("data");
            JSONArray songs = songArray(data);
            if (data != null && data.has("total")) {
                total = data.optInt("total", total);
            }
            if (songs == null || songs.length() == 0) {
                break;
            }

            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item != null) {
                    result.add(parseSong(item));
                }
            }

            offset += songs.length();
            if (songs.length() < SONG_PAGE_SIZE || (total >= 0 && offset >= total)) {
                break;
            }
        }

        return result;
    }

    public String streamUrl(String songId) {
        if (baseUrl == null || songId == null || sid == null) {
            return "";
        }

        ApiSpec stream = api(API_STREAM, "AudioStation/stream.cgi", 2);
        return new StringBuilder(baseUrl)
                .append("/webapi/")
                .append(stream.path)
                .append("?api=").append(enc(API_STREAM))
                .append("&version=").append(stream.version(2))
                .append("&method=stream")
                .append("&id=").append(enc(songId))
                .append("&_sid=").append(enc(sid))
                .toString();
    }

    public void logout() {
        if (!isConnected()) {
            return;
        }

        try {
            ApiSpec auth = api(API_AUTH, "auth.cgi", 6);
            getJson(new StringBuilder("/webapi/")
                    .append(auth.path)
                    .append("?api=").append(enc(API_AUTH))
                    .append("&version=").append(auth.version(6))
                    .append("&method=logout")
                    .append("&session=AudioStation")
                    .append("&_sid=").append(enc(sid))
                    .toString());
        } catch (Exception ignored) {
            // Logout is best effort; the local session is cleared either way.
        } finally {
            sid = null;
        }
    }

    private void discoverApis() throws Exception {
        String query = API_AUTH + "," + API_SONG + "," + API_STREAM;
        JSONObject json = getJson("/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=" + enc(query));
        if (!json.optBoolean("success", false)) {
            throw new IllegalStateException("DSM API 信息获取失败。");
        }

        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return;
        }

        readApiSpec(data, API_AUTH);
        readApiSpec(data, API_SONG);
        readApiSpec(data, API_STREAM);
    }

    private void readApiSpec(JSONObject data, String name) {
        JSONObject object = data.optJSONObject(name);
        if (object == null) {
            return;
        }

        String path = object.optString("path", "");
        int minVersion = object.optInt("minVersion", 1);
        int maxVersion = object.optInt("maxVersion", minVersion);
        if (!path.trim().isEmpty()) {
            apiSpecs.put(name, new ApiSpec(path, minVersion, maxVersion));
        }
    }

    private boolean hasAudioStationApis() {
        return apiSpecs.containsKey(API_SONG) && apiSpecs.containsKey(API_STREAM);
    }

    private Song parseSong(JSONObject item) {
        String id = firstNonEmpty(item.optString("id"), item.optString("song_id"), item.optString("path"));
        if (id.isEmpty()) {
            id = "nas-" + Integer.toHexString(item.toString().hashCode());
        }
        String title = firstNonEmpty(item.optString("title"), nestedString(item, "song_tag", "title"), "未知曲目");
        String artist = firstNonEmpty(item.optString("artist"), nestedString(item, "song_tag", "artist"), "未知艺术家");
        String album = firstNonEmpty(item.optString("album"), nestedString(item, "song_tag", "album"), "NAS 曲库");
        String folder = firstNonEmpty(item.optString("path"), item.optString("folder"), "/music");
        String codec = firstNonEmpty(nestedString(item, "song_audio", "codec"), item.optString("type"), "NAS");
        int duration = durationSec(item);
        int start = colorFor(title, 0);
        int end = colorFor(album + artist, 1);

        return new Song(
                id,
                title,
                artist,
                album,
                folder,
                codec.toUpperCase(Locale.ROOT),
                duration,
                start,
                end,
                coverLetter(title),
                false,
                false,
                streamUrl(id)
        );
    }

    private int durationSec(JSONObject item) {
        int direct = item.optInt("duration", 0);
        if (direct > 0) {
            return direct > 10000 ? direct / 1000 : direct;
        }

        JSONObject audio = item.optJSONObject("song_audio");
        int nested = audio == null ? 0 : audio.optInt("duration", 0);
        if (nested > 0) {
            return nested > 10000 ? nested / 1000 : nested;
        }

        return 240;
    }

    private JSONArray songArray(JSONObject data) {
        if (data == null) {
            return null;
        }

        JSONArray songs = data.optJSONArray("songs");
        if (songs != null) {
            return songs;
        }

        songs = data.optJSONArray("items");
        if (songs != null) {
            return songs;
        }

        return data.optJSONArray("song");
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + "：" + body);
            }
            return new JSONObject(body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private String normalizeBaseUrl(String address) {
        String value = address.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void ensureConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("NAS 尚未登录或会话已失效。");
        }
    }

    private ApiSpec api(String name, String fallbackPath, int fallbackVersion) {
        ApiSpec spec = apiSpecs.get(name);
        if (spec != null) {
            return spec;
        }
        return new ApiSpec(fallbackPath, fallbackVersion, fallbackVersion);
    }

    private String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String nestedString(JSONObject object, String child, String key) {
        JSONObject nested = object.optJSONObject(child);
        return nested == null ? "" : nested.optString(key, "");
    }

    private String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return fallback;
    }

    private String coverLetter(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "N";
        }
        return title.trim().substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private int colorFor(String value, int shift) {
        int hash = Math.abs((value == null ? "" : value).hashCode() + shift * 7919);
        float hue = hash % 360;
        return Color.HSVToColor(new float[]{hue, 0.72f, 0.88f});
    }

    private String authErrorMessage(JSONObject json) {
        JSONObject error = json.optJSONObject("error");
        int code = error == null ? 0 : error.optInt("code", 0);
        switch (code) {
            case 400:
                return "账号或密码错误。";
            case 401:
                return "账号被停用。";
            case 402:
                return "权限不足。";
            case 403:
                return "需要两步验证码。";
            case 404:
                return "两步验证码错误。";
            default:
                return "DSM 返回错误码 " + code + "。";
        }
    }

    private String audioErrorMessage(JSONObject json) {
        JSONObject error = json.optJSONObject("error");
        int code = error == null ? 0 : error.optInt("code", 0);
        if (code == 105 || code == 119) {
            sid = null;
            return "登录会话已过期，请重新连接 NAS。";
        }
        if (code == 400 || code == 401) {
            return "Audio Station 参数或权限异常。";
        }
        return "Audio Station 返回错误码 " + code + "。";
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private static final class ApiSpec {
        private final String path;
        private final int minVersion;
        private final int maxVersion;

        private ApiSpec(String path, int minVersion, int maxVersion) {
            this.path = path;
            this.minVersion = Math.max(1, minVersion);
            this.maxVersion = Math.max(this.minVersion, maxVersion);
        }

        private int version(int preferred) {
            return Math.max(minVersion, Math.min(preferred, maxVersion));
        }
    }
}
