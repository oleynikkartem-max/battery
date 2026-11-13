package ua.battery.hub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One battery: frame assembly and decoding, ported 1:1 from index.html
 * (parseSaihang, parseHumsienk, parseJbdFrame), so the site shows the same numbers it
 * showed when it read the battery itself. Pure Java (no Android classes): tested on the PC.
 */
public class Bms {
    public static final String SAIHANG = "saihang", HUMSIENK = "humsienk", JBD = "jbd";

    public final String mac, name, brand, type;

    // decoded data (same field names as session.data on the site)
    Double voltage, current, power, remCap, nomCap;
    Integer soc, soh, cycles, problemCode;
    Long balMask;
    Boolean chgMos, dsgMos;
    List<Double> cells;
    List<String> temps;
    /** epoch ms of the last fresh pack voltage/current, 0 = never */
    public volatile long at;

    private byte[] rx = new byte[0];
    private final byte[][] hs = new byte[256][];

    public Bms(String mac, String name) {
        this.mac = mac;
        this.name = name;
        String up = name.toUpperCase(Locale.ROOT);
        if (up.startsWith("HL") || up.startsWith("SH")) { brand = "Sandisolar"; type = SAIHANG; }
        else if (up.startsWith("HS") || up.startsWith("HUM")) { brand = "Humsienk"; type = HUMSIENK; }
        else { brand = "SJY"; type = JBD; }
    }

    // ---- GATT layout per type ----
    public String service() {
        switch (type) {
            case SAIHANG: return "0000fffa-0000-1000-8000-00805f9b34fb";
            case HUMSIENK: return "00000001-0000-1000-8000-00805f9b34fb";
            default: return "0000ff00-0000-1000-8000-00805f9b34fb";
        }
    }
    public String txChar() {
        switch (type) {
            case SAIHANG: return "0000fffb-0000-1000-8000-00805f9b34fb";
            case HUMSIENK: return "00000002-0000-1000-8000-00805f9b34fb";
            default: return "0000ff02-0000-1000-8000-00805f9b34fb";
        }
    }
    public String rxChar() {
        switch (type) {
            case SAIHANG: return "0000fffc-0000-1000-8000-00805f9b34fb";
            case HUMSIENK: return "00000003-0000-1000-8000-00805f9b34fb";
            default: return "0000ff01-0000-1000-8000-00805f9b34fb";
        }
    }
    /** Sent once after notifications are on. */
    public byte[][] initCommands() {
        return HUMSIENK.equals(type) ? new byte[][]{hex("aa00000000")} : new byte[0][];
    }
    /** One poll round. */
    public byte[][] pollCommands() {
        switch (type) {
            case SAIHANG: return new byte[][]{hex("a5a5000300000036c40d")};
            case HUMSIENK: return new byte[][]{hex("aa20002000"), hex("aa21002100"), hex("aa22002200")};
            default: return new byte[][]{hex("dda50300fffd77"), hex("dda50400fffc77")};
        }
    }

    // ---- receive ----
    public synchronized void onRx(byte[] chunk, long now) {
        if (SAIHANG.equals(type)) {
            if (chunk.length >= 2 && u(chunk, 0) == 0xa5 && u(chunk, 1) == 0xa5) rx = chunk.clone();
            else rx = cat(rx, chunk);
            if (rx.length >= 7 && u(rx, 0) == 0xa5 && u(rx, 1) == 0xa5) {
                int len = 7 + u(rx, 4);
                if (rx.length >= len) { parseSaihang(Arrays.copyOf(rx, len), now); rx = new byte[0]; }
            }
        } else if (HUMSIENK.equals(type)) {
            if (chunk.length >= 5 && u(chunk, 0) == 0xaa) rx = chunk.clone();
            else if (rx.length > 0 && u(rx, 0) == 0xaa) rx = cat(rx, chunk);
            else return;
            int cmd = u(rx, 1);
            hs[cmd] = rx;
            parseHumsienk(cmd, now);
        } else {
            if (chunk.length >= 4 && u(chunk, 0) == 0xdd && (u(chunk, 1) == 3 || u(chunk, 1) == 4 || u(chunk, 1) == 5)) rx = chunk.clone();
            else rx = cat(rx, chunk);
            if (rx.length >= 7 && u(rx, 0) == 0xdd) {
                int len = 7 + u(rx, 3);
                if (rx.length >= len && u(rx, len - 1) == 0x77) { parseJbd(Arrays.copyOf(rx, len), now); rx = new byte[0]; }
            }
        }
    }

    void parseSaihang(byte[] m, long now) {
        double cur = rd(m, 5, 4, false, true) / 100.0;
        double v = rd(m, 9, 4, false, false) / 100.0;
        voltage = v; current = cur; power = v * cur;
        soc = (int) rd(m, 13, 2, false, false);
        soh = (int) rd(m, 15, 2, false, false);
        remCap = rd(m, 17, 4, false, false) / 100.0;
        nomCap = rd(m, 25, 4, false, false) / 100.0;
        cycles = (int) rd(m, 29, 2, false, false);
        problemCode = (int) ((~rd(m, 31, 2, false, false)) & 0xFFFF);
        long mos = rd(m, 37, 2, false, false);
        chgMos = (mos & 0x0200) != 0;
        dsgMos = (mos & 0x0400) != 0;
        balMask = rd(m, 41, 2, false, false);
        int n = (int) Math.min(rd(m, 43, 2, false, false), 20);
        List<Double> c = new ArrayList<>();
        for (int i = 0; i < n; i++) { int p = 45 + i * 2; if (p + 2 <= m.length) c.add(rd(m, p, 2, false, false) / 1000.0); }
        cells = c;
        int ts = (int) Math.min(rd(m, 85, 2, false, false), 10);
        List<String> t = new ArrayList<>();
        for (int i = 0; i < ts; i++) {
            int p = 87 + i * 2;
            if (p + 2 <= m.length) { long raw = rd(m, p, 2, false, true); if (raw != 0) t.add(f1((raw - 2730) / 10.0)); }
        }
        temps = t;
        at = now;
    }

    void parseHumsienk(int cmd, long now) {
        byte[] m20 = hs[0x20], m21 = hs[0x21], m22 = hs[0x22];
        boolean fresh = cmd == 0x21 && m21 != null && m21.length >= 25;
        if (m21 != null && m21.length >= 25) {
            double v = rd(m21, 3, 4, true, false) / 1000.0, cur = rd(m21, 7, 4, true, true) / 1000.0;
            voltage = v; current = cur; power = v * cur;
            soc = (int) rd(m21, 11, 1, true, false);
            soh = (int) rd(m21, 12, 1, true, false);
            remCap = rd(m21, 13, 4, true, false) / 1000.0;
            nomCap = rd(m21, 17, 4, true, false) / 1000.0;
            cycles = (int) rd(m21, 21, 2, true, false);
            List<String> t = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (23 + i < m21.length - 2) { long tv = rd(m21, 23 + i, 1, true, true); if (tv != 0) t.add(String.valueOf(tv)); }
            }
            temps = t;
        }
        if (m20 != null && m20.length >= 16) {
            chgMos = (u(m20, 7) & 0x80) != 0;
            dsgMos = (u(m20, 9) & 0x80) != 0;
            balMask = rd(m20, 11, 3, true, false);
            problemCode = (int) (rd(m20, 7, 4, true, false) & 0xFF7F7F7FL);
        }
        if (m22 != null && m22.length >= 7) {
            List<Double> c = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int p = 3 + i * 2;
                if (p + 2 <= m22.length - 2) { long cv = rd(m22, p, 2, true, false); if (cv > 0) c.add(cv / 1000.0); }
            }
            cells = c;
        }
        if (fresh) at = now;
    }

    void parseJbd(byte[] f, long now) {
        int cmd = u(f, 1);
        if (cmd == 3 && f.length >= 27) {
            int tc = u(f, 26);
            int ctrl = 27 + 2 * tc;
            boolean d10 = ctrl < f.length - 3 && (u(f, ctrl) & 0x80) != 0;
            double scale = d10 ? 10.0 : 100.0;
            double v = rd(f, 4, 2, false, false) / 100.0, cur = rd(f, 6, 2, false, true) / scale;
            voltage = v; current = cur; power = v * cur;
            remCap = rd(f, 8, 2, false, false) / scale;
            nomCap = rd(f, 10, 2, false, false) / scale;
            cycles = (int) rd(f, 12, 2, false, false);
            long lo = rd(f, 16, 2, false, false), hi = rd(f, 18, 2, false, false);
            balMask = ((hi << 16) | lo) & 0xFFFFFFFFL;
            problemCode = (int) rd(f, 20, 2, false, false);
            soc = u(f, 23);
            chgMos = (u(f, 24) & 1) != 0;
            dsgMos = (u(f, 24) & 2) != 0;
            List<String> t = new ArrayList<>();
            for (int i = 0; i < tc; i++) {
                int p = 27 + i * 2;
                if (p + 2 <= f.length - 3) t.add(f1((rd(f, p, 2, false, false) - 2731) / 10.0));
            }
            temps = t;
            at = now;
        } else if (cmd == 4) {
            int n = (f.length - 7) / 2;
            List<Double> c = new ArrayList<>();
            for (int i = 0; i < n; i++) { long cv = rd(f, 4 + i * 2, 2, false, false); if (cv > 0) c.add(cv / 1000.0); }
            cells = c;
        }
    }

    // ---- JSON (hand-written to stay free of Android classes) ----
    public synchronized String json(boolean connected, String err) {
        StringBuilder s = new StringBuilder("{");
        kv(s, "id", q(mac)); kv(s, "name", q(name)); kv(s, "brand", q(brand)); kv(s, "type", q(type));
        kv(s, "connected", String.valueOf(connected)); kv(s, "at", String.valueOf(at));
        if (err != null) kv(s, "err", q(err));
        StringBuilder d = new StringBuilder("{");
        num(d, "voltage", voltage); num(d, "current", current); num(d, "power", power);
        num(d, "soc", soc); num(d, "soh", soh); num(d, "remCap", remCap); num(d, "nomCap", nomCap);
        num(d, "cycles", cycles); num(d, "problemCode", problemCode); num(d, "balMask", balMask);
        if (chgMos != null) kv(d, "chgMos", chgMos.toString());
        if (dsgMos != null) kv(d, "dsgMos", dsgMos.toString());
        if (cells != null) { StringBuilder a = new StringBuilder("["); for (Double c : cells) { if (a.length() > 1) a.append(','); a.append(fmt(c)); } kv(d, "cells", a.append(']').toString()); }
        if (temps != null) { StringBuilder a = new StringBuilder("["); for (String t : temps) { if (a.length() > 1) a.append(','); a.append(q(t)); } kv(d, "temps", a.append(']').toString()); }
        kv(s, "data", d.append('}').toString());
        return s.append('}').toString();
    }

    private static void kv(StringBuilder s, String k, String v) { if (s.length() > 1) s.append(','); s.append(q(k)).append(':').append(v); }
    private static void num(StringBuilder s, String k, Number v) { if (v != null) kv(s, k, v instanceof Double ? fmt((Double) v) : v.toString()); }
    private static String fmt(double v) { return Double.isFinite(v) ? String.format(Locale.US, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "") : "null"; }
    static String q(String v) { return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String f1(double v) { return String.format(Locale.US, "%.1f", v); }

    // ---- bytes ----
    static int u(byte[] b, int i) { return b[i] & 0xff; }
    static byte[] cat(byte[] a, byte[] b) { byte[] o = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, o, a.length, b.length); return o; }
    static byte[] hex(String h) { byte[] o = new byte[h.length() / 2]; for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16); return o; }
    /** Same as readInt() on the site (out of range reads count as 0, like DataView would throw there). */
    static long rd(byte[] b, int off, int size, boolean le, boolean signed) {
        if (off < 0 || off + size > b.length) return 0;
        long v = 0;
        for (int i = 0; i < size; i++) {
            int x = u(b, le ? off + size - 1 - i : off + i);
            v = (v << 8) | x;
        }
        if (signed && (v & (1L << (size * 8 - 1))) != 0) v -= 1L << (size * 8);
        return v;
    }
}
