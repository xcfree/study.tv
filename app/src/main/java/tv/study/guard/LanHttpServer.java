package tv.study.guard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** An HTTP/1.1 listener for the bundled LAN management page. */
final class LanHttpServer implements AutoCloseable {
    static final int PORT = 8787;

    interface Handler {
        void handle(Request request, OutputStream output) throws IOException;
    }

    private static final int HEADER_LIMIT = 8192;
    private final ServerSocket listener = new ServerSocket();
    private final Set<Socket> clients = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> new Thread(task, "StudyTV web request"));

    LanHttpServer(Inet4Address address) throws IOException {
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(address, PORT), 8);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    String host() {
        return listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort();
    }

    void start(Handler handler, Runnable onFailure) {
        new Thread(() -> accept(handler, onFailure), "StudyTV web listener").start();
    }

    private void accept(Handler handler, Runnable onFailure) {
        try {
            while (!listener.isClosed()) {
                Socket client = listener.accept();
                synchronized (clients) {
                    if (listener.isClosed()) {
                        client.close();
                        return;
                    }
                    clients.add(client);
                    try {
                        workers.execute(() -> serve(client, handler));
                    } catch (RejectedExecutionException e) {
                        clients.remove(client);
                        client.close();
                    }
                }
            }
        } catch (IOException ignored) {
            boolean failed = !listener.isClosed();
            close();
            if (failed) onFailure.run();
        }
    }

    private void serve(Socket client, Handler handler) {
        try (Socket socket = client) {
            socket.setSoTimeout(5_000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            try {
                Request request = new Request(input);
                if (!host().equals(request.headers.get("host"))) throw new Failure(403, "请使用电视显示的地址");
                handler.handle(request, output);
            } catch (Failure e) {
                respond(output, e.status, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
        } catch (IOException ignored) {
            // The browser disconnected, the socket timed out, or the TV closed the session.
        } finally {
            synchronized (clients) {
                clients.remove(client);
            }
        }
    }

    boolean isOpen() {
        return !listener.isClosed();
    }

    @Override
    public void close() {
        try { listener.close(); } catch (IOException ignored) {}
        synchronized (clients) {
            workers.shutdownNow();
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) {}
            }
            clients.clear();
        }
    }

    static void respond(OutputStream output, int status, String type, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n"
                + "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; "
                + "connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Content Too Large";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            default: return "Service Unavailable";
        }
    }

    static final class Failure extends IOException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Request {
        final String method, path;
        final Map<String, String> headers = new HashMap<>();
        private final InputStream input;
        private final int length;

        Request(InputStream input) throws IOException {
            this.input = input;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String[] line = readLine(input, HEADER_LIMIT, deadline).split(" ", -1);
            if (line.length != 3 || !line[1].startsWith("/")
                    || (!"HTTP/1.1".equals(line[2]) && !"HTTP/1.0".equals(line[2]))) {
                throw new Failure(400, "请求格式不正确");
            }
            method = line[0];
            path = line[1];
            int remaining = HEADER_LIMIT - line[0].length() - path.length() - line[2].length() - 4;
            while (true) {
                String header = readLine(input, remaining, deadline);
                if (header.isEmpty()) break;
                remaining -= header.length() + 2;
                int colon = header.indexOf(':');
                if (colon < 1) throw new Failure(400, "请求头格式不正确");
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.put(name, header.substring(colon + 1).trim()) != null) {
                    throw new Failure(400, "请求头重复");
                }
            }
            try {
                String size = headers.get("content-length");
                if (headers.containsKey("transfer-encoding") || (size != null && !size.matches("[0-9]{1,10}"))) {
                    throw new NumberFormatException();
                }
                length = size == null ? 0 : Integer.parseInt(size);
            } catch (NumberFormatException e) {
                throw new Failure(400, "请求长度不正确");
            }
        }

        InputStream body(long limit) throws Failure {
            if (length <= 0) throw new Failure(400, "请求内容为空");
            if (length > limit) throw new Failure(413, "文件或请求超过大小限制");
            return new FilterInputStream(input) {
                private int left = length;
                private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);

                @Override public int read() throws IOException {
                    if (left == 0) return -1;
                    if (System.nanoTime() >= deadline) throw new IOException("上传超时");
                    int value = in.read();
                    if (value < 0) throw new EOFException("上传中断");
                    left--;
                    return value;
                }

                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (count == 0) return 0;
                    if (left == 0) return -1;
                    if (System.nanoTime() >= deadline) throw new IOException("上传超时");
                    int size = in.read(bytes, offset, Math.min(left, count));
                    if (size < 0) throw new EOFException("上传中断");
                    left -= size;
                    return size;
                }

                @Override public void close() {}
            };
        }

        private static String readLine(InputStream input, int limit, long deadline) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < limit; i++) {
                if (System.nanoTime() >= deadline) throw new SocketTimeoutException();
                int value = input.read();
                if (value == -1) throw new EOFException();
                if (value == '\r' && input.read() == '\n') return bytes.toString("US-ASCII");
                if (value != '\t' && (value < 32 || value > 126)) {
                    throw new Failure(400, "请求头格式不正确");
                }
                bytes.write(value);
            }
            throw new Failure(431, "请求头过大");
        }
    }
}
