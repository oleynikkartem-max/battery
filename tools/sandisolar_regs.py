#!/usr/bin/env python3
"""
Sandisolar (Saihang, BLE name HL*/SH*) register tool, built on Bleak.

Frame format, checked against the known frames in index.html:
    a5 a5 | Modbus-RTU PDU (slave 0x00, func, ...) | CRC16/Modbus over the PDU, little-endian
  read : a5a5 00 03 <addr16> <count16> crc
  write: a5a5 00 10 <addr16> <count16> <bytecount> <data...> crc
  unlock (password "3Dshbms"): write 5 regs at 0x00D2

Safe by default:
  * no arguments = scan + read-only dump of the settings block, nothing gets written;
  * a write needs --write ADDR=RAW and --yes, and only goes to whitelisted registers;
  * before a write, the current values go to a JSON backup;
  * after a write the register is read back and compared;
  * --restore backup.json puts the old values back.

Examples:
  python sandisolar_regs.py --scan
  python sandisolar_regs.py --addr AA:BB:CC:DD:EE:FF                  # dump 0x0060..0x008F
  python sandisolar_regs.py --addr ... --read 0x0078 --read 0x006F
  python sandisolar_regs.py --addr ... --write 0x0078=50 --yes        # RAW value!
  python sandisolar_regs.py --addr ... --restore backup_*.json --yes
"""
import argparse, asyncio, json, sys, time
from bleak import BleakClient, BleakScanner

SVC = "0000fffa-0000-1000-8000-00805f9b34fb"
TX  = "0000fffb-0000-1000-8000-00805f9b34fb"
RX  = "0000fffc-0000-1000-8000-00805f9b34fb"
UNLOCK = bytes.fromhex("a5a5001000d200050a33447368626d7300000060b1")

# Only these registers may be written. Bounds are in RAW units and get
# confirmed by the user after reading the current value (the scaling is not documented).
WRITABLE = {
    0x0078: "мін. струм для балансування",
    0x006F: "напруга старту балансування",
}
# extra guard: start voltage must not go below 3.40 V (LFP plateau)
MIN_BAL_START_MV = 3400


def crc16(b: bytes) -> int:
    c = 0xFFFF
    for x in b:
        c ^= x
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def frame(pdu: bytes) -> bytes:
    return b"\xa5\xa5" + pdu + crc16(pdu).to_bytes(2, "little")


def read_frame(addr, count):
    return frame(bytes([0x00, 0x03]) + addr.to_bytes(2, "big") + count.to_bytes(2, "big"))


def write06_frame(addr, value):
    return frame(bytes([0x00, 0x06]) + addr.to_bytes(2, "big") + value.to_bytes(2, "big"))


def write_frame(addr, values):
    data = b"".join(v.to_bytes(2, "big") for v in values)
    return frame(bytes([0x00, 0x10]) + addr.to_bytes(2, "big")
                 + len(values).to_bytes(2, "big") + bytes([len(data)]) + data)


assert read_frame(0x0000, 0x36).hex() == "a5a5000300000036c40d"  # matches the app


class Bms:
    def __init__(self, client):
        self.c, self.buf, self.ev = client, bytearray(), asyncio.Event()

    def _on(self, _, data):
        if data[:2] == b"\xa5\xa5":
            self.buf = bytearray()
        self.buf += data
        if self._complete():
            self.ev.set()

    def _complete(self):
        b = self.buf
        if len(b) < 7 or b[:2] != b"\xa5\xa5":
            return False
        fn = b[3]
        if fn & 0x80: need = 7
        elif fn == 0x03: need = 5 + b[4] + 2
        elif fn in (0x10, 0x06): need = 10
        else: need = len(b)
        return len(b) >= need

    async def xfer(self, pkt, timeout=3.0):
        self.buf, _ = bytearray(), self.ev.clear()
        await self.c.write_gatt_char(TX, pkt, response=False)
        await asyncio.wait_for(self.ev.wait(), timeout)
        b = bytes(self.buf)
        body, crc = b[2:-2], int.from_bytes(b[-2:], "little")
        if crc16(body) != crc:
            raise IOError(f"bad CRC: {b.hex()}")
        if b[3] & 0x80:
            raise IOError(f"BMS returned Modbus error 0x{b[4]:02x} ({b.hex()})")
        return b

    async def read(self, addr, count=1):
        b = await self.xfer(read_frame(addr, count))
        d = b[5:5 + b[4]]
        return [int.from_bytes(d[i:i + 2], "big") for i in range(0, len(d), 2)]

    async def unlock(self):
        await self.xfer(UNLOCK)

    async def write06(self, addr, value):
        b = await self.xfer(write06_frame(addr, value))
        if b[4:8] != addr.to_bytes(2, "big") + value.to_bytes(2, "big"):
            raise IOError(f"unexpected 0x06 echo: {b.hex()}")

    async def write(self, addr, values):
        await self.xfer(write_frame(addr, values))


async def scan():
    for d in await BleakScanner.discover(timeout=6):
        if d.name and d.name.upper().startswith(("HL", "SH")):
            print(f"{d.address}  {d.name}")


def parse_kv(s):
    a, v = s.split("=")
    return int(a, 0), int(v, 0)


async def main(a):
    if a.scan or not a.addr:
        return await scan()

    async with BleakClient(a.addr) as client:
        bms = Bms(client)
        await client.start_notify(RX, bms._on)
        await asyncio.sleep(0.3)

        if a.restore:
            plan = {int(k, 0): v for k, v in json.load(open(a.restore)).items()}
        else:
            plan = dict(parse_kv(w) for w in a.write)

        if not plan:  # read-only mode
            regs = [int(r, 0) for r in a.read] or None
            if regs:
                for r in regs:
                    print(f"0x{r:04X} = {(await bms.read(r))[0]}")
            else:
                vals = await bms.read(0x0060, 0x30)
                for i, v in enumerate(vals):
                    tag = WRITABLE.get(0x60 + i, "")
                    print(f"0x{0x60 + i:04X} = {v:5d}  (0x{v:04X})  {tag}")
            return

        # ---- write mode ----
        before = {}
        for r, new in plan.items():
            if r not in WRITABLE:
                sys.exit(f"0x{r:04X} is not whitelisted, refusing to write")
            cur = (await bms.read(r))[0]
            before[r] = cur
            print(f"0x{r:04X} {WRITABLE[r]}: зараз {cur} -> буде {new}")
            if r == 0x006F and new < (3200 if a.allow_low_start else MIN_BAL_START_MV) and cur >= 1000:
                sys.exit("Старт балансування нижче 3.40 V на LFP не роблю (балансуватиме по шуму).")
            if cur and not (cur / 20 <= max(new, 1) <= cur * 20) and new != 0:
                sys.exit("New value differs from the current one by more than 20x, so the scaling "
                         "is probably wrong. Check it with --read.")

        if not a.yes:
            sys.exit("Dry run. Add --yes to actually write.")

        bk = f"backup_{time.strftime('%Y%m%d_%H%M%S')}.json"
        json.dump({f"0x{k:04X}": v for k, v in before.items()}, open(bk, "w"), indent=1)
        print("backup ->", bk)

        if not a.fc06:
            await bms.unlock()
        for r, new in plan.items():
            try:
                if a.fc06:
                    await bms.write06(r, new)
                else:
                    await bms.write(r, [new])
                print(f"0x{r:04X}: BMS прийняла команду")
            except IOError as e:
                print(f"0x{r:04X}: BMS ВІДХИЛИЛА: {e}")
            await asyncio.sleep(0.3)
            got = (await bms.read(r))[0]
            print(f"0x{r:04X}: записано {new}, прочитано {got}  {'OK' if got == new else 'MISMATCH!'}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--scan", action="store_true")
    p.add_argument("--addr")
    p.add_argument("--read", action="append", default=[])
    p.add_argument("--write", action="append", default=[], help="ADDR=RAW, e.g. 0x0078=50")
    p.add_argument("--restore")
    p.add_argument("--yes", action="store_true")
    p.add_argument("--fc06", action="store_true", help="write via Modbus 0x06, no unlock")
    p.add_argument("--allow-low-start", action="store_true", help="allow 0x006F down to 3200")
    asyncio.run(main(p.parse_args()))
