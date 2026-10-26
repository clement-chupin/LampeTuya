package com.lampe.tuya;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;

/**
 * Listens for the bulb's own UDP broadcast (port 6667, every few seconds) so the app keeps
 * working if the router hands the bulb a new IP. v3.3 broadcasts are AES-encrypted with a
 * fixed key, md5("yGAdlopoPVldABfn").
 */
public class Discovery {

    public interface Callback { void onFound(String ip); }

    private static final int PORT = 6667;
    private static final int LISTEN_MS = 12000;
    private static volatile boolean busy;

    public static void find(Context ctx, String devId, Callback cb) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wifi.createMulticastLock("lampe");
            lock.acquire();
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(PORT));
                s.setSoTimeout(LISTEN_MS);
                byte[] key = MessageDigest.getInstance("MD5")
                        .digest("yGAdlopoPVldABfn".getBytes(StandardCharsets.UTF_8));
                long end = System.currentTimeMillis() + LISTEN_MS;
                byte[] buf = new byte[1024];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    JSONObject j = decode(key, p.getData(), p.getLength());
                    if (j != null && devId.equals(j.optString("gwId"))) {
                        cb.onFound(j.optString("ip", p.getAddress().getHostAddress()));
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                lock.release();
                busy = false;
            }
        }, "tuya-discovery").start();
    }

    private static JSONObject decode(byte[] key, byte[] d, int len) {
        // 16-byte header, 4-byte return code, payload, then crc + suffix (8 bytes)
        for (int off : new int[]{20, 16}) {
            int n = len - off - 8;
            if (n <= 0 || n % 16 != 0) continue;
            try {
                byte[] enc = new byte[n];
                System.arraycopy(d, off, enc, 0, n);
                return new JSONObject(new String(BulbClient.aes(Cipher.DECRYPT_MODE, key, enc), StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        return null;
    }
}
