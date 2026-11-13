import ua.battery.hub.Bms;

/** Feeds frames captured from the real batteries (13.11.2026) into Bms and checks the decode. */
public class BmsTest {
    static int fails = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static byte[] hex(String h) {
        byte[] o = new byte[h.length() / 2];
        for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return o;
    }

    public static void main(String[] a) {
        String f20 = "aa200f000000000800800000000000000000b700";
        String f21 = "aa211a4e340000000000006364d0830100a08601000000171a1a1a171a9504";
        String f22 = "aa22301c0d0f0d190d130d00000000000000000000000000000000000000000000000000000000000000000000000000000000dd00";

        // whole frames (MTU 251)
        Bms b = new Bms("44:3D:81:BB:C9:ED", "HS0302AC26261071");
        b.onRx(hex("aa00000000"), 1);
        check("init reply does not count as fresh data", b.at == 0);
        b.onRx(hex(f20), 2);
        b.onRx(hex(f22), 3);
        check("status/cell frames do not count as fresh data", b.at == 0);
        b.onRx(hex(f21), 4);
        String j = b.json(true, null);
        System.out.println(j);
        check("voltage 13.39", j.contains("\"voltage\":13.39,"));
        check("current 0", j.contains("\"current\":0,"));
        check("soc 99", j.contains("\"soc\":99,"));
        check("cells", j.contains("\"cells\":[3.356,3.343,3.353,3.347]"));
        check("nomCap 100", j.contains("\"nomCap\":100,"));
        check("fresh at 4", b.at == 4);

        // the same 0x21 frame split into 20-byte BLE packets (default MTU)
        Bms s = new Bms("43:A3:02:7F:CD:BB", "HS030302AC26262574");
        String g21 = "aa211a34350000000000006464c4850100a08601000000151a1a1a161a7004";
        s.onRx(hex(g21.substring(0, 40)), 10);
        check("half frame is not fresh", s.at == 0);
        s.onRx(hex(g21.substring(40)), 11);
        String k = s.json(true, null);
        System.out.println(k);
        check("split frame glued: voltage 13.62", k.contains("\"voltage\":13.62,"));
        check("split frame glued: soc 100", k.contains("\"soc\":100,"));

        // JBD: synthetic 0x03 reply built from the protocol layout (4S pack, 2 temp sensors)
        Bms j1 = new Bms("A5:C2:37:29:80:25", "DP04S007L4S100A");
        j1.onRx(hex("dd03001b0540fff6271027100002000000000000000021640304020b8b0b84fc5c77"), 20);
        System.out.println(j1.json(true, null));
        check("jbd voltage 13.44", j1.json(true, null).contains("\"voltage\":13.44,"));

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
