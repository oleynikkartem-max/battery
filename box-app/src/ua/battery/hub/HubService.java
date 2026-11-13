package ua.battery.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps BLE links to the batteries, polls them every few seconds and uploads the decoded
 * data to the battery-hub Worker. Config: files/hub.conf (url=..., bat=MAC,NAME per line).
 * All GATT operations go through one queue, one at a time (old BT stacks choke otherwise).
 */
public class HubService extends Service {
    static final String TAG = "BatteryHub";
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final long POLL_MS = 5000, UPLOAD_MS = 5000, OP_TIMEOUT_MS = 4000, CONNECT_TIMEOUT_MS = 10 * 60000;
    static final long STALE_MS = 45000;   // no fresh data this long while "connected" -> reconnect
    static final long WRITE_GAP_MS = 220; // pause after each request to a BMS

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, HubService.class));
    }

    class Link {
        final Bms bms;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic tx, rx;
        boolean ready, connecting;
        int fails;
        boolean pending;              // connectGatt issued, waiting for the link to come up
        long nextTry, readySince, connectDeadline;
        String err;
        Link(Bms b) { bms = b; }
    }

    /** One GATT operation: start() returns false if it could not be issued (or is not a GATT op). */
    interface Op { boolean start(); }

    static class QOp {
        final Link owner; final Op op;
        QOp(Link owner, Op op) { this.owner = owner; this.op = op; }
    }

    private final List<Link> links = new ArrayList<>();
    private final ArrayDeque<QOp> ops = new ArrayDeque<>();
    private QOp cur;                 // op in flight, null = idle
    private Runnable opTimeout;
    private Handler h;
    private String url;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private volatile String lastUpload = "-";
    private final long started = System.currentTimeMillis();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("hub", "Battery Hub", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "hub")
                .setContentTitle("Battery Hub").setContentText("Читаю акумулятори")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build();
        startForeground(1, n);

        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hub:ble");
        wake.acquire();
        wifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hub:wifi");
        wifi.acquire();

        HandlerThread t = new HandlerThread("hub");
        t.start();
        h = new Handler(t.getLooper());
        h.post(this::init);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        for (Link l : links) if (l.gatt != null) { try { l.gatt.close(); } catch (Exception ignored) {} }
        if (wake.isHeld()) wake.release();
        if (wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    // ---------------- config / log ----------------
    private void init() {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(getFilesDir(), "hub.conf")))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("url=")) url = line.substring(4).trim();
                else if (line.startsWith("bat=")) {
                    String[] p = line.substring(4).split(",", 2);
                    links.add(new Link(new Bms(p[0].trim().toUpperCase(Locale.ROOT), p.length > 1 ? p[1].trim() : p[0].trim())));
                }
            }
        } catch (Exception e) {
            log("no config: " + e);
        }
        log("start: " + links.size() + " batteries, upload " + (url != null ? "on" : "OFF"));
        h.post(this::tick);
        h.postDelayed(this::upload, UPLOAD_MS);
    }

    void log(String s) {
        Log.i(TAG, s);
        try {
            File f = new File(getFilesDir(), "hub.log");
            if (f.length() > 300_000) f.delete();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + s + "\n");
            }
        } catch (Exception ignored) {}
    }

    // ---------------- main loop ----------------
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            log("bluetooth is off");
        } else {
            for (Link l : links) {
                if (l.pending && now > l.connectDeadline) {
                    log(l.bms.name + ": still not connected, restarting the attempt");
                    fail(l, "не підключається");
                }
            }
            for (Link l : links) {
                if (l.ready) {
                    // connected but nothing fresh for a while: the link is dead, start over
                    boolean noFresh = l.bms.at == 0 || System.currentTimeMillis() - l.bms.at > STALE_MS;
                    if (noFresh && now - l.readySince > STALE_MS) {
                        log(l.bms.name + ": no fresh data " + STALE_MS / 1000 + " s, reconnecting");
                        drop(l, "немає даних");
                        continue;
                    }
                    // a pause after each request, as the site did: the SJY (JBD) BMS drops a request
                    // that comes right after the previous one
                    if (ops.size() < 24) for (byte[] c : l.bms.pollCommands()) { write(l, c); enqueue(l, gapOp(WRITE_GAP_MS)); }
                } else if (!l.connecting && now >= l.nextTry) {
                    connect(ad, l);       // background connect, outside the op queue: a battery that
                }                         // is not reachable never stalls the others
            }
        }
        h.postDelayed(this::tick, POLL_MS);
    }

    private void connect(BluetoothAdapter ad, Link l) {
        l.connecting = true;
        l.pending = true;
        l.connectDeadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        log(l.bms.name + ": connecting");
        // autoConnect=true: the controller connects as soon as the battery is reachable (after a box
        // reboot the batteries keep the dead link for minutes), no 30 s direct-connect timeouts
        l.gatt = ad.getRemoteDevice(l.bms.mac).connectGatt(this, true, callback(l), BluetoothDevice.TRANSPORT_LE);
        if (l.gatt == null) fail(l, "connectGatt null");
    }

    private void fail(Link l, String why) {
        l.err = why;
        l.fails++;
        l.nextTry = SystemClock.elapsedRealtime() + Math.min(30000, 3000L << Math.min(l.fails - 1, 4));
        drop(l, why);
    }

    private void drop(Link l, String why) {
        l.ready = false;
        l.connecting = false;
        l.pending = false;
        l.err = why;
        ops.removeIf(o -> o.owner == l);          // its queued ops are pointless now
        boolean mine = cur != null && cur.owner == l;
        if (l.gatt != null) {
            try { l.gatt.disconnect(); } catch (Exception ignored) {}
            try { l.gatt.close(); } catch (Exception ignored) {}
            l.gatt = null;
        }
        if (mine) opDone();                      // its op in flight will never complete
    }

    private BluetoothGattCallback callback(Link l) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                h.post(() -> {
                    if (g != l.gatt) { try { g.close(); } catch (Exception ignored) {} return; }
                    if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        log(l.bms.name + ": connected");
                        l.pending = false;
                        // link setup goes ahead of the other batteries' polls
                        enqueueFront(l, () -> g.requestMtu(247), g::discoverServices);
                    } else {
                        log(l.bms.name + ": disconnected (status " + status + ")");
                        fail(l, "розʼєднано (" + status + ")");
                    }
                });
            }

            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { h.post(() -> doneFor(l)); }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                h.post(() -> {
                    doneFor(l);
                    if (g != l.gatt) return;
                    BluetoothGattService s = g.getService(UUID.fromString(l.bms.service()));
                    if (s == null) { log(l.bms.name + ": service not found"); fail(l, "сервіс не знайдено"); return; }
                    l.tx = s.getCharacteristic(UUID.fromString(l.bms.txChar()));
                    l.rx = s.getCharacteristic(UUID.fromString(l.bms.rxChar()));
                    if (l.tx == null || l.rx == null) { fail(l, "характеристики не знайдено"); return; }
                    List<Op> setup = new ArrayList<>();
                    setup.add(() -> {
                        g.setCharacteristicNotification(l.rx, true);
                        BluetoothGattDescriptor d = l.rx.getDescriptor(CCCD);
                        if (d == null) return false;
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        return g.writeDescriptor(d);
                    });
                    for (byte[] c : l.bms.initCommands()) setup.add(writeOp(l, c));
                    setup.add(() -> {
                        l.ready = true;
                        l.connecting = false;
                        l.fails = 0;
                        l.err = null;
                        l.readySince = SystemClock.elapsedRealtime();
                        log(l.bms.name + ": ready");
                        return false;   // not a GATT op: finishes at once
                    });
                    enqueueFront(l, setup.toArray(new Op[0]));
                });
            }

            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { h.post(() -> doneFor(l)); }
            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { h.post(() -> doneFor(l)); }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                byte[] v = c.getValue();
                if (v == null) return;
                byte[] copy = v.clone();
                long now = System.currentTimeMillis();
                h.post(() -> l.bms.onRx(copy, now));
            }
        };
    }

    private void write(Link l, byte[] data) {
        enqueue(l, writeOp(l, data));
    }

    private Op writeOp(Link l, byte[] data) {
        return () -> {
            if (l.gatt == null || l.tx == null) return false;
            int p = l.tx.getProperties();
            l.tx.setWriteType((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            l.tx.setValue(data);
            return l.gatt.writeCharacteristic(l.tx);
        };
    }

    /** Holds the queue for ms (lets the BMS answer before the next request). */
    private Op gapOp(long ms) {
        return () -> {
            opTimeout = this::opDone;
            h.postDelayed(opTimeout, ms);
            return true;
        };
    }

    /** Puts ops at the head of the queue, keeping their order. */
    private void enqueueFront(Link owner, Op... list) {
        for (int i = list.length - 1; i >= 0; i--) ops.addFirst(new QOp(owner, list[i]));
        if (cur == null) next();
    }

    // ---------------- GATT op queue (runs on h) ----------------
    private void enqueue(Link owner, Op op) {
        ops.add(new QOp(owner, op));
        if (cur == null) next();
    }

    private void next() {
        while (cur == null && !ops.isEmpty()) {
            QOp q = ops.poll();
            cur = q;
            opTimeout = null;
            boolean started;
            try { started = q.op.start(); } catch (Exception e) { log("op error: " + e); started = false; }
            if (cur != q) return;                 // the op finished itself (e.g. fail -> drop)
            if (!started) { cur = null; continue; }
            if (opTimeout == null) {              // ops may set their own (longer) timeout
                opTimeout = () -> { log("GATT op timeout"); opDone(); };
                h.postDelayed(opTimeout, OP_TIMEOUT_MS);
            }
        }
    }

    /** A GATT callback of link l: completes the op in flight only if it is l's (late callbacks ignored). */
    private void doneFor(Link l) {
        if (cur != null && cur.owner == l) opDone();
    }

    private void opDone() {
        if (opTimeout != null) h.removeCallbacks(opTimeout);
        opTimeout = null;
        cur = null;
        next();
    }

    // ---------------- upload ----------------
    private void upload() {
        h.postDelayed(this::upload, UPLOAD_MS);
        if (url == null) return;
        StringBuilder s = new StringBuilder("{\"box\":{\"t\":").append(System.currentTimeMillis())
                .append(",\"up\":").append(System.currentTimeMillis() - started)
                .append(",\"last\":").append(Bms.q(lastUpload)).append("},\"bats\":[");
        for (int i = 0; i < links.size(); i++) {
            Link l = links.get(i);
            if (i > 0) s.append(',');
            s.append(l.bms.json(l.ready, l.err));
        }
        final byte[] body = s.append("]}").toString().getBytes();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("content-type", "application/json");
                c.setRequestProperty("user-agent", "battery-hub/1.0");
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
                int code = c.getResponseCode();
                c.disconnect();
                if (code != 200 && !lastUpload.startsWith("HTTP " + code)) log("upload: HTTP " + code);
                lastUpload = code == 200 ? "ok" : "HTTP " + code;
            } catch (Exception e) {
                if (!lastUpload.startsWith("err")) log("upload: " + e);
                lastUpload = "err " + e.getClass().getSimpleName();
            }
        }).start();
    }
}
