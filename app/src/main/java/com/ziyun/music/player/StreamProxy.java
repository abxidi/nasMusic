package com.ziyun.music.player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

final class StreamProxy {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, String> streams = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private ServerSocket serverSocket;
    private SSLContext relaxedSslContext;

    synchronized String localUrl(String remoteUrl) throws IOException {
        ensureStarted();
        String token = Integer.toHexString(tokenCounter.incrementAndGet());
        streams.put(token, remoteUrl);
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/" + token;
    }

    synchronized void close() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        streams.clear();
        executor.shutdownNow();
    }

    private void ensureStarted() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return;
        }
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        executor.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket) {
            socket.setSoTimeout(20000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String request = reader.readLine();
            if (request == null || !request.startsWith("GET ")) {
                writeError(socket, 400, "Bad Request");
                return;
            }

            String token = request.substring(4, request.indexOf(' ', 4));
            if (token.startsWith("/")) {
                token = token.substring(1);
            }
            int query = token.indexOf('?');
            if (query >= 0) {
                token = token.substring(0, query);
            }

            String range = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    range = line.substring(colon + 1).trim();
                }
            }

            String remoteUrl = streams.get(token);
            if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
                writeError(socket, 404, "Not Found");
                return;
            }
            proxy(remoteUrl, range, socket);
        } catch (Exception ignored) {
        }
    }

    private void proxy(String remoteUrl, String range, Socket socket) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(remoteUrl).openConnection();
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(relaxedSslContext().getSocketFactory());
            https.setHostnameVerifier((hostname, session) -> true);
        }
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "*/*");
        if (range != null && !range.isEmpty()) {
            connection.setRequestProperty("Range", range);
        }

        int code = connection.getResponseCode();
        InputStream rawStream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (rawStream == null) {
            writeError(socket, code, connection.getResponseMessage());
            connection.disconnect();
            return;
        }

        try (InputStream input = new BufferedInputStream(rawStream);
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            String message = connection.getResponseMessage();
            output.write(("HTTP/1.1 " + code + " " + (message == null ? "OK" : message) + "\r\n").getBytes(StandardCharsets.UTF_8));
            writeHeader(output, "Content-Type", firstNonEmpty(connection.getContentType(), "audio/mpeg"));
            writeHeader(output, "Accept-Ranges", "bytes");
            writeHeader(output, "Connection", "close");
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange != null && !contentRange.trim().isEmpty()) {
                writeHeader(output, "Content-Range", contentRange);
            }
            long length = connection.getContentLengthLong();
            if (length >= 0) {
                writeHeader(output, "Content-Length", Long.toString(length));
            }
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));

            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            connection.disconnect();
        }
    }

    private void writeError(Socket socket, int code, String message) throws IOException {
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        String text = message == null || message.trim().isEmpty() ? "Error" : message;
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + code + " " + text + "\r\n").getBytes(StandardCharsets.UTF_8));
        writeHeader(output, "Content-Length", Integer.toString(body.length));
        writeHeader(output, "Connection", "close");
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private void writeHeader(BufferedOutputStream output, String key, String value) throws IOException {
        output.write((key + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private SSLContext relaxedSslContext() throws Exception {
        if (relaxedSslContext != null) {
            return relaxedSslContext;
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
        relaxedSslContext = context;
        return relaxedSslContext;
    }

    private String firstNonEmpty(String first, String fallback) {
        return first == null || first.trim().isEmpty() ? fallback : first;
    }
}
