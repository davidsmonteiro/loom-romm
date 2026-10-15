#!/usr/bin/env python3
"""
Writes the synthetic PSP images PspDiscIdReaderTest reads, with nothing but the standard library
(struct, zlib, hashlib), so the test checks Loom's ISO9660, CSO and PBP reading against an
independent implementation of the formats. No game's data is in them: each image holds only what
a disc ID is read from.

An ISO is 22 sectors of 2048 bytes:
  0-15  zeros (the system area)
  16    the Primary Volume Descriptor (01 "CD001" 01), the root directory's record at +156
  17    the descriptor set terminator (FF "CD001" 01)
  18    the root directory: ".", "..", PSP_GAME, UMD_DATA.BIN;1
  19    PSP_GAME: ".", "..", PARAM.SFO;1
  20    UMD_DATA.BIN: "ULUS-10064|0123456789ABCDEF|0001|G"
  21    PARAM.SFO, whose DISC_ID is ULUS10064
Variants leave a file out, or name another ID.

A CSO (CISO v1, as PPSSPP's BlockDevices.cpp reads it) is a 0x18-byte header, an index of
frames + 1 u32s (an offset shifted right by the alignment; bit 31 set for a frame stored as it
is), then each frame raw-deflated (zlib wbits -15) or stored. Frames that don't shrink are stored,
as maxcso does, and each image stores some on purpose so both kinds are read.

A PBP is "\\0PBP", a version, eight u32 offsets (PARAM.SFO first) and the files they point at.

expected.txt lists each file, its MD5 and what reading it must give.

Run from this folder: python3 make_fixtures.py
"""
import hashlib
import struct
import zlib

SECTOR = 2048


def both16(value):
    return struct.pack("<H", value) + struct.pack(">H", value)


def both32(value):
    return struct.pack("<I", value) + struct.pack(">I", value)


def record(name, extent, size, directory=False):
    """An ISO9660 directory record, padded to an even length."""
    length = 33 + len(name) + (1 - len(name) % 2)
    rec = bytearray(length)
    rec[0] = length
    rec[2:10] = both32(extent)
    rec[10:18] = both32(size)
    rec[18:25] = bytes([104, 12, 12, 0, 0, 0, 0])  # 2004-12-12 00:00:00 UTC
    rec[25] = 0x02 if directory else 0x00
    rec[28:32] = both16(1)
    rec[32] = len(name)
    rec[33:33 + len(name)] = name
    return bytes(rec)


def directory(self_extent, parent_extent, entries):
    """A directory's one sector: "." and "..", then [entries] (records), sorted as ISO9660 wants."""
    data = record(b"\x00", self_extent, SECTOR, True) + record(b"\x01", parent_extent, SECTOR, True) + b"".join(entries)
    assert len(data) <= SECTOR
    return data.ljust(SECTOR, b"\x00")


def text(value, size):
    return value.encode("ascii").ljust(size, b" ")


def pvd(total_sectors, root_extent):
    sector = bytearray(SECTOR)
    sector[0] = 0x01
    sector[1:6] = b"CD001"
    sector[6] = 0x01
    sector[8:40] = text("PSP GAME", 32)
    sector[40:72] = text("LOOM_TEST", 32)
    sector[80:88] = both32(total_sectors)
    sector[120:124] = both16(1)  # volume set size
    sector[124:128] = both16(1)  # volume sequence number
    sector[128:132] = both16(SECTOR)
    sector[156:190] = record(b"\x00", root_extent, SECTOR, True)
    sector[190:813] = b" " * (813 - 190)  # volume set, publisher, preparer, application, file IDs
    sector[881] = 0x01
    return bytes(sector)


def terminator():
    return (b"\xff" + b"CD001" + b"\x01").ljust(SECTOR, b"\x00")


def sfo(entries):
    """A PARAM.SFO as PPSSPP writes one: entries sorted by key, text with its NUL, values padded to 4."""
    entries = sorted(entries)
    keys = b""
    data = b""
    index = b""
    for key, value in entries:
        if isinstance(value, int):
            fmt, body = 0x0404, struct.pack("<I", value)
        elif isinstance(value, bytes):
            fmt, body = 0x0004, value
        else:
            fmt, body = 0x0204, value.encode("utf-8") + b"\x00"
        room = (len(body) + 3) // 4 * 4
        index += struct.pack("<HHIII", len(keys), fmt, len(body), room, len(data))
        keys += key.encode("ascii") + b"\x00"
        data += body.ljust(room, b"\x00")
    keys = keys.ljust((len(keys) + 3) // 4 * 4, b"\x00")
    key_start = 0x14 + len(index)
    header = struct.pack("<IIIII", 0x46535000, 0x0101, key_start, key_start + len(keys), len(entries))
    return header + index + keys + data


def disc_sfo(disc_id):
    return sfo([
        ("BOOTABLE", 1),
        ("CATEGORY", "UG"),
        ("DISC_ID", disc_id),
        ("DISC_NUMBER", 1),
        ("DISC_TOTAL", 1),
        ("DISC_VERSION", "1.00"),
        ("PARENTAL_LEVEL", 3),
        ("PSP_SYSTEM_VER", "1.50"),
        ("REGION", 32768),
        ("TITLE", "Loom Test Game"),
    ])


UMD_DATA = b"ULUS-10064|0123456789ABCDEF|0001|G"


def iso(umd=UMD_DATA, param_sfo=disc_sfo("ULUS10064"), root_extra=()):
    """A PSP disc image: UMD_DATA.BIN and PSP_GAME/PARAM.SFO, either left out when None."""
    sectors = [bytes(SECTOR)] * 16 + [None, terminator(), None]
    root_entries = []
    if param_sfo is not None:
        game_dir = len(sectors)
        sectors.append(None)
        root_entries.append(record(b"PSP_GAME", game_dir, SECTOR, True))
    for name, body in root_extra:
        root_entries.append(record(name, len(sectors), len(body)))
        sectors.append(body.ljust(SECTOR, b"\x00"))
    if umd is not None:
        root_entries.append(record(b"UMD_DATA.BIN;1", len(sectors), len(umd)))
        sectors.append(umd.ljust(SECTOR, b"\x00"))
    if param_sfo is not None:
        sfo_sector = len(sectors)
        sectors.append(param_sfo.ljust(SECTOR, b"\x00"))
        sectors[game_dir] = directory(game_dir, 18, [record(b"PARAM.SFO;1", sfo_sector, len(param_sfo))])
    sectors[16] = pvd(len(sectors), 18)
    sectors[18] = directory(18, 18, sorted(root_entries, key=lambda r: r[33:33 + r[32]]))
    return b"".join(sectors)


def cso(image, block_size=0x800, align=0, stored=(), version=1):
    frames = (len(image) + block_size - 1) // block_size
    header = b"CISO" + struct.pack("<IQIBBH", 0x18, len(image), block_size, version, align, 0)
    position = len(header) + 4 * (frames + 1)
    index = []
    data = b""
    for frame in range(frames):
        padding = -position % (1 << align)
        data += b"\x00" * padding
        position += padding
        chunk = image[frame * block_size:(frame + 1) * block_size].ljust(block_size, b"\x00")
        compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
        deflated = compressor.compress(chunk) + compressor.flush()
        if frame in stored or len(deflated) >= len(chunk):
            index.append((position >> align) | 0x80000000)
            data += chunk
            position += len(chunk)
        else:
            index.append(position >> align)
            data += deflated
            position += len(deflated)
    padding = -position % (1 << align)
    data += b"\x00" * padding
    position += padding
    index.append(position >> align)
    return header + b"".join(struct.pack("<I", i) for i in index) + data


def pbp(param_sfo):
    """A PSN EBOOT.PBP: its PARAM.SFO, then empty icons and sounds, then a stand-in for DATA.PSP."""
    data_psp = hashlib.sha256(b"Loom's synthetic DATA.PSP").digest() * 8
    sfo_at = 0x28
    after_sfo = sfo_at + len(param_sfo)
    offsets = [sfo_at] + [after_sfo] * 6 + [after_sfo + len(data_psp)]
    return b"\x00PBP" + struct.pack("<I", 0x00010000) + struct.pack("<8I", *offsets) + param_sfo + data_psp


GAME = iso()
assert len(GAME) == 22 * SECTOR

FIXTURES = {
    "game.iso": (GAME, "FOUND ULUS10064 BOTH"),
    "umd-only.iso": (iso(param_sfo=None), "FOUND ULUS10064 UMD"),
    "sfo-only.iso": (iso(umd=None, param_sfo=disc_sfo("ULJM05001")), "FOUND ULJM05001 SFO"),
    "disagree.iso": (iso(param_sfo=disc_sfo("ULUS10065")), "DISAGREE ULUS10064 ULUS10065"),
    # A disc of another console: no UMD_DATA.BIN, no PSP_GAME.
    "other.iso": (iso(umd=None, param_sfo=None, root_extra=[(b"SYSTEM.CNF;1", b"BOOT2 = cdrom0:\\SLUS_000.00;1\r\n")]), "NONE"),
    # A download cut short inside PARAM.SFO's sector.
    "cut.iso": (GAME[:21 * SECTOR + 100], "NONE"),
    # The UMD_DATA.BIN sector (20) stored, the rest deflated.
    "game.cso": (cso(GAME, stored={20}), "FOUND ULUS10064 BOTH"),
    "game-align2.cso": (cso(GAME, align=2, stored={17, 21}), "FOUND ULUS10064 BOTH"),
    # Two sectors a frame: the frame holding UMD_DATA.BIN and PARAM.SFO (20, 21) stored.
    "game-bigframe.cso": (cso(GAME, block_size=0x1000, stored={10}), "FOUND ULUS10064 BOTH"),
    "game-v2.cso": (cso(GAME, version=2), "NOTREAD"),
    "game.pbp": (pbp(sfo([
        ("BOOTABLE", 1),
        ("CATEGORY", "MG"),
        ("DISC_ID", "NPUZ00001"),
        ("DISC_VERSION", "1.00"),
        ("PARENTAL_LEVEL", 1),
        ("PSP_SYSTEM_VER", "3.71"),
        ("REGION", 32768),
        ("TITLE", "Loom Test Mini"),
    ])), "FOUND NPUZ00001 SFO"),
}

with open("expected.txt", "w") as expected:
    for name, (data, answer) in FIXTURES.items():
        with open(name, "wb") as f:
            f.write(data)
        expected.write(f"{name} {hashlib.md5(data).hexdigest()} {answer}\n")
