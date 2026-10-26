package com.lampe.tuya;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal Tuya local protocol v3.3 client (TCP 6668, AES-128-ECB with the device local key).
 * One worker thread sends, one reader thread per connection receives. Writes are coalesced:
 * set() only stores the latest value per DP, so dragging a slider never floods the bulb.
 */
public class BulbClient {

    public interface Listener {
        void onStatus(JSONObject dps);
        void onConnection(boolean connected);
    }

    private static final int PORT = 6668;
    private static final int CONTROL = 7, HEART_BEAT = 9, DP_QUERY = 10;
    private static final long MIN_INTERVAL_MS = 50, HEARTBEAT_MS = 10000, RETRY_MS = 2000;
    private static final byte[] V33_HEADER = new byte[15];
    static {
        V33_HEADER[0] = '3'; V33_HEADER[1] = '.'; V33_HEADER[2] = '3';
    }

    private final String devId;
    private final byte[] key;
    private final Listener listener;
    private volatile String ip;

    private final Map<String, Object> pending = new LinkedHashMap<>();
    private boolean queryPending;
    private volatile boolean running;
    private Thread worker;

    private final Object sockLock = new Object();
    private Socket sock;
    private OutputStream out;
    private int seq = 1;

    public BulbClient(String devId, String localKey, String ip, Listener listener) {
        this.devId = devId;
        this.key = localKey.getBytes(StandardCharsets.UTF_8);
        this.ip = ip;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "tuya-send");
        worker.start();
    }

    public void stop() {
        running = false;
        synchronized (this) { notifyAll(); }
        closeSocket();
    }

    public synchronized void set(String dp, Object value) {
        pending.put(dp, value);
        notifyAll();
    }

    public void setIp(String newIp) {
        if (newIp.equals(ip)) return;
        ip = newIp;
        closeSocket();
        synchronized (this) { notifyAll(); }
    }

    public String getIp() { return ip; }

    private void loop() {
        while (running) {
            try {
                if (!isConnected()) connect();
                Map<String, Object> batch = null;
                boolean query;
                synchronized (this) {
                    if (pending.isEmpty() && !queryPending) wait(HEARTBEAT_MS);
                    if (!running) break;
                    if (!isConnected()) continue;
                    query = queryPending;
                    queryPending = false;
                    if (!pending.isEmpty()) {
                        batch = new LinkedHashMap<>(pending);
                        pending.clear();
                    }
                }
                if (batch != null) {
                    send(CONTROL, controlJson(batch));
                    if (query) synchronized (this) { queryPending = true; }
                } else if (query) {
                    send(DP_QUERY, queryJson());
                } else {
                    send(HEART_BEAT, queryJson());
                }
                Thread.sleep(MIN_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                closeSocket();
                try { Thread.sleep(RETRY_MS); } catch (InterruptedException ie) { break; }
            }
        }
        closeSocket();
    }

    private boolean isConnected() {
        synchronized (sockLock) { return sock != null; }
    }

    private void connect() throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, PORT), 3000);
        } catch (Exception e) {
            listener.onConnection(false);
            throw e;
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout((int) (HEARTBEAT_MS * 2));
        synchronized (sockLock) {
            sock = s;
            out = s.getOutputStream();
        }
        synchronized (this) { queryPending = true; }
        Thread reader = new Thread(() -> readLoop(s), "tuya-read");
        reader.start();
        listener.onConnection(true);
    }

    private void closeSocket() {
        Socket s;
        synchronized (sockLock) {
            s = sock;
            sock = null;
            out = null;
        }
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
            listener.onConnection(false);
            synchronized (this) { notifyAll(); }
        }
    }

    private String controlJson(Map<String, Object> dps) throws Exception {
        JSONObject j = new JSONObject();
        j.put("devId", devId);
        j.put("uid", devId);
        j.put("t", String.valueOf(System.currentTimeMillis() / 1000));
        j.put("dps", new JSONObject(dps));
        return j.toString();
    }

    private String queryJson() throws Exception {
        JSONObject j = new JSONObject();
        j.put("gwId", devId);
        j.put("devId", devId);
        j.put("uid", devId);
        j.put("t", String.valueOf(System.currentTimeMillis() / 1000));
        return j.toString();
    }

    private void send(int cmd, String json) throws Exception {
        byte[] enc = aes(Cipher.ENCRYPT_MODE, key, json.getBytes(StandardCharsets.UTF_8));
        byte[] payload = enc;
        if (cmd == CONTROL) {
            payload = new byte[V33_HEADER.length + enc.length];
            System.arraycopy(V33_HEADER, 0, payload, 0, V33_HEADER.length);
            System.arraycopy(enc, 0, payload, V33_HEADER.length, enc.length);
        }
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + 8);
        b.putInt(0x000055AA).putInt(seq++).putInt(cmd).putInt(payload.length + 8).put(payload);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, 16 + payload.length);
        b.putInt((int) crc.getValue()).putInt(0x0000AA55);
        synchronized (sockLock) {
            if (out == null) throw new IllegalStateException("not connected");
            out.write(b.array());
            out.flush();
        }
    }

    private void readLoop(Socket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (running) {
                int prefix = in.readInt();
                while (prefix != 0x000055AA) prefix = (prefix << 8) | in.readUnsignedByte();
                in.readInt();                       // seq
                in.readInt();                       // cmd
                int len = in.readInt();
                if (len < 8 || len > 65536) throw new IllegalStateException("bad length " + len);
                byte[] body = new byte[len];
                in.readFully(body);
                handlePayload(body, len - 8);
            }
        } catch (Exception e) {
            synchronized (sockLock) { if (sock != s) return; }
            closeSocket();
        }
    }

    private void handlePayload(byte[] data, int n) {
        int off = 0;
        if (n - off >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0) off += 4;  // return code
        if (n - off >= 15 && data[off] == '3' && data[off + 1] == '.' && data[off + 2] == '3') off += 15;
        if (n - off < 16) return;                                // plain ack, nothing to parse
        try {
            byte[] enc = new byte[n - off];
            System.arraycopy(data, off, enc, 0, enc.length);
            JSONObject j = new JSONObject(new String(aes(Cipher.DECRYPT_MODE, key, enc), StandardCharsets.UTF_8));
            JSONObject dps = j.optJSONObject("dps");
            if (dps != null) listener.onStatus(dps);
        } catch (Exception ignored) { }
    }

    static byte[] aes(int mode, byte[] key, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }
}
