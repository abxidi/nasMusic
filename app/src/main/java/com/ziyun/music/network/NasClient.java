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
import java.util.List;
import java.util.Locale;

public class NasClient {
    private static final int TIMEOUT_MS = 15000;

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

    public List<DiscoveredNas> discoverLocalDevices() {
        List<DiscoveredNas> devices = new ArrayList<>();
        devices.add(new DiscoveredNas("DiskStation-Home", "http://192.168.31.8:5000", "Audio Station 可用"));
        return devices;
    }

    public ConnectionResult connect(String address, String account, String password, String otp, boolean verifyCertificate) {
        if (address == null || address.trim().isEmpty()) {
            return new ConnectionResult(false, "NAS 地址不能为空", "请输入 DSM 地址或选择局域网发现的设备。");
        }
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            return new ConnectionResult(false, "地址格式不正确", "请使用 http:// 或 https:// 开头，并包含端口时写完整地址。");
        }
        if (account == null || account.trim().isEmpty() || password == null || password.isEmpty()) {
            return new ConnectionResult(false, "账号信息不完整", "请输入拥有音乐目录读取权限的 DSM 账号。");
        }

        baseUrl = normalizeBaseUrl(address);
        sid = null;

        try {
            StringBuilder path = new StringBuilder("/webapi/auth.cgi")
                    .append("?api=SYNO.API.Auth")
                    .append("&version=6")
                    .append("&method=login")
                    .append("&account=").append(enc(account.trim()))
                    .append("&passwd=").append(enc(password))
                    .append("&session=AudioStation")
                    .append("&format=sid");
            if (otp != null && !otp.trim().isEmpty()) {
                path.append("&otp_code=").append(enc(otp.trim()));
            }

            JSONObject json = getJson(path.toString());
            if (!json.optBoolean("success")) {
                return new ConnectionResult(false, "登录失败", authErrorMessage(json));
            }

            JSONObject data = json.optJSONObject("data");
            sid = data == null ? null : data.optString("sid", null);
            if (sid == null || sid.isEmpty()) {
                return new ConnectionResult(false, "登录失败", "DSM 未返回有效会话，请检查账号权限。");
            }
            return new ConnectionResult(true, "NAS 登录成功", "DSM 会话已建立，可以同步 Audio Station 曲库。");
        } catch (Exception e) {
            sid = null;
            return new ConnectionResult(false, "连接失败", readableError(e));
        }
    }

    public boolean isConnected() {
        return baseUrl != null && sid != null && !sid.isEmpty();
    }

    public List<Song> fetchSongs() throws Exception {
        ensureConnected();
        String path = "/webapi/AudioStation/song.cgi"
                + "?api=SYNO.AudioStation.Song"
                + "&version=3"
                + "&method=list"
                + "&library=all"
                + "&limit=500"
                + "&offset=0"
                + "&additional=song_tag,song_audio"
                + "&_sid=" + enc(sid);
        JSONObject json = getJson(path);
        if (!json.optBoolean("success")) {
            throw new IllegalStateException(audioErrorMessage(json));
        }

        JSONObject data = json.optJSONObject("data");
        JSONArray array = data == null ? null : data.optJSONArray("songs");
        List<Song> result = new ArrayList<>();
        if (array == null) {
            return result;
        }

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                result.add(toSong(item, i));
            }
        }
        return result;
    }

    public String streamUrl(String songId) {
        if (baseUrl == null || sid == null || songId == null || songId.isEmpty()) {
            return null;
        }
        return baseUrl + "/webapi/AudioStation/stream.cgi"
                + "?api=SYNO.AudioStation.Stream"
                + "&version=2"
                + "&method=stream"
                + "&id=" + enc(songId)
                + "&_sid=" + enc(sid);
    }

    public void logout() {
        if (!isConnected()) {
            return;
        }
        try {
            getJson("/webapi/auth.cgi?api=SYNO.API.Auth&version=6&method=logout&session=AudioStation&_sid=" + enc(sid));
        } catch (Exception ignored) {
            // Logout is best-effort; stale sessions expire on DSM.
        } finally {
            sid = null;
        }
    }

    private Song toSong(JSONObject item, int index) {
        String id = item.optString("id", item.optString("path", "nas-" + index));
        String title = firstNonEmpty(item.optString("title"), item.optString("name"), "未命名歌曲");
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

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + body);
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
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        return builder.toString();
    }

    private void ensureConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("请先登录 NAS。");
        }
    }

    private String normalizeBaseUrl(String address) {
        String value = address.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
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
        return "Audio Station 返回错误码 " + code + "。";
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
