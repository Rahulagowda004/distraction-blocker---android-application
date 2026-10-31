package com.distractionfree;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

public class BlockVpnService extends VpnService {
    private static final String CHANNEL_ID = "protection";
    private static final int NOTIFICATION_ID = 7;
    private static final String UPSTREAM_DNS = "1.1.1.1";
    private static volatile boolean running;
    private ParcelFileDescriptor vpnInterface;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::runTunnel, "local-dns-filter");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        closeInterface();
        super.onDestroy();
    }

    private void runTunnel() {
        try {
            vpnInterface = new Builder()
                    .setSession("Distraction-Free DNS filter")
                    .addAddress("10.0.0.2", 32)
                    .addRoute("10.0.0.1", 32)
                    .addDnsServer("10.0.0.1")
                    .establish();
            if (vpnInterface == null) {
                stopSelf();
                return;
            }
            running = true;
            FileInputStream input = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(vpnInterface.getFileDescriptor());
            byte[] packet = new byte[32767];
            while (!Thread.currentThread().isInterrupted()) {
                int length = input.read(packet);
                if (length > 0) {
                    handlePacket(packet, length, output);
                }
            }
        } catch (IOException ignored) {
            // The system can close the TUN descriptor during permission revocation or shutdown.
        } finally {
            running = false;
            closeInterface();
        }
    }

    static boolean isRunning() {
        return running;
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream output) {
        if (length < 28 || (packet[0] & 0xF0) != 0x40) {
            return;
        }
        int ipHeaderLength = (packet[0] & 0x0F) * 4;
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8
                || packet[9] != 17 || packet[ipHeaderLength + 2] != 0
                || packet[ipHeaderLength + 3] != 53) {
            return;
        }
        int dnsOffset = ipHeaderLength + 8;
        int dnsLength = length - dnsOffset;
        if (dnsLength < 12) {
            return;
        }
        byte[] response;
        String domain = readQuestionName(packet, dnsOffset, dnsLength);
        if (domain == null) {
            return;
        }
        if (isBlocked(this, domain)) {
            response = makeNxDomain(packet, length, dnsOffset);
        } else {
            response = forward(packet, length, dnsOffset);
        }
        if (response != null) {
            try {
                output.write(response);
            } catch (IOException ignored) {
                // The next service lifecycle will recreate the tunnel.
            }
        }
    }

    private byte[] forward(byte[] query, int length, int dnsOffset) {
        try (DatagramSocket socket = new DatagramSocket()) {
            protect(socket);
            byte[] dns = new byte[length - dnsOffset];
            System.arraycopy(query, dnsOffset, dns, 0, dns.length);
            socket.setSoTimeout(1500);
            socket.send(new DatagramPacket(dns, dns.length, InetAddress.getByName(UPSTREAM_DNS), 53));
            DatagramPacket received = new DatagramPacket(new byte[4096], 4096);
            socket.receive(received);
            return wrapDnsResponse(query, length, dnsOffset, received.getData(), received.getLength());
        } catch (IOException exception) {
            return null;
        }
    }

    private byte[] makeNxDomain(byte[] query, int length, int dnsOffset) {
        byte[] dns = new byte[length - dnsOffset];
        System.arraycopy(query, dnsOffset, dns, 0, dns.length);
        dns[2] = (byte) ((dns[2] | 0x80) & 0xFB);
        dns[3] = (byte) ((dns[3] & 0xF0) | 0x03);
        return wrapDnsResponse(query, length, dnsOffset, dns, dns.length);
    }

    private byte[] wrapDnsResponse(byte[] query, int length, int dnsOffset, byte[] dns, int dnsLength) {
        byte[] response = new byte[dnsOffset + dnsLength];
        System.arraycopy(query, 0, response, 0, dnsOffset);
        System.arraycopy(dns, 0, response, dnsOffset, dnsLength);
        response[2] = (byte) (response.length >> 8);
        response[3] = (byte) response.length;
        for (int i = 0; i < 4; i++) {
            byte source = response[12 + i];
            response[12 + i] = response[16 + i];
            response[16 + i] = source;
        }
        response[20] = 0;
        response[21] = 53;
        response[22] = query[20];
        response[23] = query[21];
        response[24] = 0;
        response[25] = 0;
        response[26] = 0;
        response[27] = 0;
        int checksum = checksum(response, 0, 20);
        response[10] = (byte) (checksum >> 8);
        response[11] = (byte) checksum;
        int udpLength = response.length - 20;
        response[24] = (byte) (udpLength >> 8);
        response[25] = (byte) udpLength;
        int udpChecksum = pseudoUdpChecksum(response);
        response[26] = (byte) (udpChecksum >> 8);
        response[27] = (byte) udpChecksum;
        return response;
    }

    private int pseudoUdpChecksum(byte[] packet) {
        int sum = 17 + (packet.length - 20);
        for (int i = 12; i < 20; i += 2) {
            sum += ((packet[i] & 255) << 8) | (packet[i + 1] & 255);
        }
        for (int i = 20; i < packet.length; i += 2) {
            sum += (packet[i] & 255) << 8;
            if (i + 1 < packet.length) sum += packet[i + 1] & 255;
        }
        return foldChecksum(sum);
    }

    private int checksum(byte[] bytes, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length; i += 2) {
            sum += ((bytes[i] & 255) << 8) | (bytes[i + 1] & 255);
        }
        return foldChecksum(sum);
    }

    private int foldChecksum(int sum) {
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }

    private String readQuestionName(byte[] packet, int offset, int length) {
        int end = offset + length;
        int cursor = offset + 12;
        StringBuilder domain = new StringBuilder();
        while (cursor < end) {
            int labelLength = packet[cursor++] & 255;
            if (labelLength == 0) return domain.toString().toLowerCase(Locale.US);
            if (labelLength > 63 || cursor + labelLength > end) return null;
            if (domain.length() > 0) domain.append('.');
            domain.append(new String(packet, cursor, labelLength, java.nio.charset.StandardCharsets.US_ASCII));
            cursor += labelLength;
        }
        return null;
    }

    static boolean isBlocked(Context context, String queriedDomain) {
        String domain = normalizeDomain(queriedDomain);
        List<String> blocked = new BlockDatabase(context).getBlockedDomains();
        for (String root : blocked) {
            if (domain.equals(root) || domain.endsWith("." + root)) return true;
        }
        return false;
    }

    static String normalizeDomain(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.US);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("www.")) value = value.substring(4);
        return value;
    }

    static boolean isValidDomain(String domain) {
        return domain.length() >= 3 && domain.length() <= 253
                && domain.matches("(?i)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    }

    static boolean isConfigured(Context context) {
        return !new BlockDatabase(context).getBlockedDomains().isEmpty();
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Local website protection is running")
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Protection", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void closeInterface() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
                // Already closed by the system.
            }
            vpnInterface = null;
        }
    }
}
