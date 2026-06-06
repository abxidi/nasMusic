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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

public class NasClient {
    private static final int TIMEOUT_MS = 15000;
    private static final int SONG_PAGE_SIZE = 500;
    private static final int MAX_SONG_PAGES = 100;
    private static final int DISCOVERY_CONNECT_TIMEOUT_MS = 450;
    private static final int DISCOVERY_READ_TIMEOUT_MS = 700;
    private static final int DISCOVERY_MAX_WAIT_MS = 6500;
    private static final int DISCOVERY_THREADS = 32;

    private static final String API_AUTH = "SYNO.API.Auth";
    private static final String API_SONG = "SYNO.AudioStation.Song";
    private static final String API_STREAM = "SYNO.AudioStation.Stream";

    private final Map<String, ApiSpec> apiSpecs = new HashMap<>();
    private final HostnameVerifier discoveryHostnameVerifier = (hostname, session) -> true;

    private String baseUrl;
    private String sid;
    private boolean relaxedHttpsForSession;
    private SSLContext discoverySslContext;

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
        List<String> hosts = localSubnetHosts();
        if (hosts.isEmpty()) {
            return new ArrayList<>();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(DISCOVERY_THREADS, hosts.size() * 2));
        CompletionService<DiscoveredNasCandidate> completion = new ExecutorCompletionService<>(executor);
        int taskCount = 0;
        for (String host : hosts) {
            completion.submit(() -> probeNas(host, 5000));
            completion.submit(() -> probeNas(host, 5001));
            taskCount += 2;
        }

        Map<String, DiscoveredNas> found = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + DISCOVERY_MAX_WAIT_MS;
        try {
            for (int i = 0; i < taskCount; i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }

                Future<DiscoveredNasCandidate> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) {
                    break;
                }

                DiscoveredNasCandidate candidate = future.get();
                if (candidate == null) {
                    continue;
                }

                DiscoveredNas existing = found.get(candidate.host);
                if (existing == null || candidate.nas.address.startsWith("https://")) {
                    found.put(candidate.host, candidate.nas);
                }
            }
        } catch (Exception ignored) {
            // Discovery should never block manual login. Partial results are still useful.
        } finally {
            executor.shutdownNow();
        }

        return new ArrayList<>(found.values());
    }

    private DiscoveredNasCandidate probeNas(String host, int port) {
        String scheme = port == 5001 ? "https" : "http";
        String address = scheme + "://" + host + ":" + port;
        String query = "/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query="
                + enc(API_AUTH + "," + API_SONG + "," + API_STREAM);

        try {
            JSONObject json = getJson(address, query, DISCOVERY_CONNECT_TIMEOUT_MS, DISCOVERY_READ_TIMEOUT_MS, true);
            if (!json.optBoolean("success", false)) {
                return null;
            }

            JSONObject data = json.optJSONObject("data");
            if (data == null || data.optJSONObject(API_AUTH) == null) {
                return null;
            }

            boolean audioAvailable = data.optJSONObject(API_SONG) != null && data.optJSONObject(API_STREAM) != null;
            String capability = audioAvailable ? "Audio Station 可用" : "DSM API 可用";
            String name = firstNonEmpty(fetchNasName(address), "", "Synology NAS " + host);
            return new DiscoveredNasCandidate(host, new DiscoveredNas(name, address, capability));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String fetchNasName(String address) {
        try {
            JSONObject json = getJson(address, "/webman/info.cgi", DISCOVERY_CONNECT_TIMEOUT_MS, DISCOVERY_READ_TIMEOUT_MS, true);
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                return "";
            }

            return firstNonEmpty(
                    data.optString("hostname"),
                    data.optString("server"),
                    data.optString("model")
            );
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> localSubnetHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }

                    addSubnetHosts(hosts, (Inet4Address) address, interfaceAddress.getNetworkPrefixLength());
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }

        return new ArrayList<>(hosts);
    }

    private void addSubnetHosts(Set<String> hosts, Inet4Address localAddress, short prefixLength) {
        int prefix = prefixLength;
        if (prefix < 24 || prefix > 30) {
            prefix = 24;
        }

        int local = ipv4ToInt(localAddress.getAddress());
        int mask = prefix == 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
        int network = local & mask;
        int broadcast = network | ~mask;

        int added = 0;
        for (int value = network + 1; value < broadcast && added < 254; value++) {
            if (value == local) {
                continue;
            }
            hosts.add(intToIpv4(value));
            added++;
        }
    }

    private int ipv4ToInt(byte[] bytes) {
        return ((bytes[0] & 0xff) << 24)
                | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff);
    }

    private String intToIpv4(int value) {
        return ((value >> 24) & 0xff) + "."
                + ((value >> 16) & 0xff) + "."
                + ((value >> 8) & 0xff) + "."
                + (value & 0xff);
    }

    public ConnectionResult connect(String address, String account, String password, String otp, boolean verifyCertificate) {
        if (address == null || address.trim().isEmpty()) {
            return new ConnectionResult(false, "NAS 地址不能为空", "请输入 DSM 地址或选择局域网发现的设备。");
        }
        if (account == null || account.trim().isEmpty() || password == null || password.isEmpty()) {
            return new ConnectionResult(false, "账号信息不完整", "请输入 NAS 账号和密码。");
        }

        ConnectionResult lastResult = null;
        Exception lastException = null;
        for (ConnectionTarget target : connectionTargets(address, verifyCertificate)) {
            try {
                baseUrl = target.url;
                sid = null;
                relaxedHttpsForSession = target.relaxedHttps;
                apiSpecs.clear();

                ConnectionResult result = connectCurrentTarget(account, password, otp);
                if (result.success || isCredentialOrPermissionFailure(result)) {
                    return result;
                }
                lastResult = result;
            } catch (Exception e) {
                sid = null;
                lastException = e;
            }
        }

        sid = null;
        relaxedHttpsForSession = false;
        if (lastResult != null) {
            return lastResult;
        }
        return new ConnectionResult(false, "连接失败", readableError(lastException));
    }

    private ConnectionResult connectCurrentTarget(String account, String password, String otp) throws Exception {
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
        String trustText = relaxedHttpsForSession ? "，局域网自签证书已接受" : "";
        return new ConnectionResult(true, "NAS 登录成功", protocol + " 会话已建立" + trustText + "，Audio Station API 可用。");
    }

    private boolean isCredentialOrPermissionFailure(ConnectionResult result) {
        return result != null && ("登录失败".equals(result.title) || "Audio Station 不可用".equals(result.title));
    }

    public boolean isConnected() {
        return baseUrl != null && sid != null && !sid.isEmpty();
    }

    public void configureBaseUrl(String address) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        baseUrl = normalizeBaseUrl(address);
        relaxedHttpsForSession = false;
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
        return getJson(baseUrl, path, TIMEOUT_MS, TIMEOUT_MS, relaxedHttpsForSession);
    }

    private JSONObject getJson(String requestBaseUrl, String path, int connectTimeoutMs, int readTimeoutMs, boolean relaxedHttps) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestBaseUrl + path).openConnection();
            if (relaxedHttps && connection instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
                httpsConnection.setSSLSocketFactory(discoverySslContext().getSocketFactory());
                httpsConnection.setHostnameVerifier(discoveryHostnameVerifier);
            }
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
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

    private SSLContext discoverySslContext() throws Exception {
        if (discoverySslContext != null) {
            return discoverySslContext;
        }

        X509TrustManager trustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[]{trustManager}, new SecureRandom());
        discoverySslContext = context;
        return discoverySslContext;
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
        boolean userSuppliedScheme = value.startsWith("http://") || value.startsWith("https://");
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!hasExplicitPort(value) && shouldUseDsmDefaultPort(value, userSuppliedScheme)) {
            value = value + (value.startsWith("https://") ? ":5001" : ":5000");
        }
        return value;
    }

    private List<ConnectionTarget> connectionTargets(String address, boolean verifyCertificate) {
        List<ConnectionTarget> targets = new ArrayList<>();
        String raw = address == null ? "" : address.trim();
        boolean hasScheme = raw.startsWith("http://") || raw.startsWith("https://");

        if (hasScheme) {
            String normalized = normalizeBaseUrl(raw);
            addTarget(targets, normalized, false);
            if (verifyCertificate && normalized.startsWith("https://") && isLocalAddressUrl(normalized)) {
                addTarget(targets, normalized, true);
            }
            return targets;
        }

        if (raw.endsWith(":5000")) {
            addTarget(targets, "http://" + raw, false);
            return targets;
        }

        if (raw.endsWith(":5001")) {
            String https = "https://" + raw;
            addTarget(targets, https, false);
            addTarget(targets, https, true);
            return targets;
        }

        String https = normalizeBaseUrl(raw);
        addTarget(targets, https, false);
        addTarget(targets, https, true);
        if (!hasRawPort(raw)) {
            addTarget(targets, "http://" + raw + ":5000", false);
        }
        return targets;
    }

    private void addTarget(List<ConnectionTarget> targets, String url, boolean relaxedHttps) {
        for (ConnectionTarget target : targets) {
            if (target.url.equals(url) && target.relaxedHttps == relaxedHttps) {
                return;
            }
        }
        targets.add(new ConnectionTarget(url, relaxedHttps));
    }

    private boolean isLocalAddressUrl(String value) {
        try {
            String host = new URL(value).getHost();
            return host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    || host.endsWith(".local")
                    || host.equalsIgnoreCase("diskstation")
                    || host.equalsIgnoreCase("synology");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasRawPort(String value) {
        return value != null && value.matches("[^/]+:\\d+");
    }

    private boolean hasExplicitPort(String value) {
        try {
            return new URL(value).getPort() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldUseDsmDefaultPort(String value, boolean userSuppliedScheme) {
        if (!userSuppliedScheme) {
            return true;
        }
        try {
            String host = new URL(value).getHost();
            return host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    || host.endsWith(".local")
                    || host.equalsIgnoreCase("diskstation")
                    || host.equalsIgnoreCase("synology");
        } catch (Exception e) {
            return false;
        }
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
        if (e == null) {
            return "请检查 NAS 地址、端口和网络连接。";
        }
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

    private static final class DiscoveredNasCandidate {
        private final String host;
        private final DiscoveredNas nas;

        private DiscoveredNasCandidate(String host, DiscoveredNas nas) {
            this.host = host;
            this.nas = nas;
        }
    }

    private static final class ConnectionTarget {
        private final String url;
        private final boolean relaxedHttps;

        private ConnectionTarget(String url, boolean relaxedHttps) {
            this.url = url;
            this.relaxedHttps = relaxedHttps;
        }
    }
}
