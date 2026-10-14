#!/usr/bin/env python3
"""
Writes the synthetic Switch images SwitchTitleIdReaderTest reads, using an implementation of
AES-XTS that isn't Loom's (the `cryptography` package), so the test checks Loom's decryption
against an independent reference.

Nothing here comes from a console or a game: the header key is derived from a fixed phrase, and
the title IDs are made up. Each NCA is only its 0xC00-byte header (encrypted as Nintendo does:
AES-128-XTS, 0x200-byte sectors numbered 0..5, the sector number as a 16-byte big-endian tweak)
followed by a little filler.

Run from this folder: python3 make_fixtures.py
"""
import hashlib
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# 32 bytes, key1 || key2. Not a console's header_key.
KEY = hashlib.sha256(b"Loom's synthetic Switch header key, not a console key").digest()
OTHER_KEY = hashlib.sha256(b"Another synthetic key, for the wrong-key test").digest()

PROGRAM, META, CONTROL, DATA = 0, 1, 2, 4


def filler(seed, n):
    out = b""
    counter = 0
    while len(out) < n:
        out += hashlib.sha256(f"{seed}/{counter}".encode()).digest()
        counter += 1
    return out[:n]


def xts_encrypt(key, sector, data):
    encryptor = Cipher(algorithms.AES(key), modes.XTS(sector.to_bytes(16, "big"))).encryptor()
    return encryptor.update(data) + encryptor.finalize()


def nca(program_id, content_type, magic=b"NCA3", key=KEY, seed="nca"):
    """An NCA: its header, encrypted, then 0x100 bytes of filler standing in for the sections."""
    header = bytearray(filler(seed, 0xC00))
    header[0x200:0x204] = magic
    header[0x204] = 0  # distribution: download
    header[0x205] = content_type
    header[0x206] = 0
    header[0x207] = 0
    struct.pack_into("<Q", header, 0x208, 0xC00 + 0x100)  # content size
    struct.pack_into("<Q", header, 0x210, program_id)
    struct.pack_into("<I", header, 0x218, 0)
    header[0x220:0x400] = bytes(0x1E0)
    sectors = []
    for i in range(6):
        # NCA2 encrypts each section header (sectors 2..5) as if it were sector 0.
        number = 0 if magic == b"NCA2" and i >= 2 else i
        sectors.append(xts_encrypt(key, number, bytes(header[i * 0x200:(i + 1) * 0x200])))
    return b"".join(sectors) + filler(seed + "/body", 0x100)


def string_table(names, align):
    table = b""
    offsets = []
    for name in names:
        offsets.append(len(table))
        table += name.encode() + b"\0"
    table += bytes((-len(table)) % align)
    return table, offsets


def pfs0(files):
    names = [n for n, _ in files]
    table, name_offsets = string_table(names, 0x20)
    entries = b""
    offset = 0
    for (name, data), name_offset in zip(files, name_offsets):
        entries += struct.pack("<QQII", offset, len(data), name_offset, 0)
        offset += len(data)
    header = b"PFS0" + struct.pack("<III", len(files), len(table), 0)
    return header + entries + table + b"".join(data for _, data in files)


def hfs0(files):
    names = [n for n, _ in files]
    table, name_offsets = string_table(names, 0x20)
    entries = b""
    offset = 0
    for (name, data), name_offset in zip(files, name_offsets):
        hashed = min(len(data), 0x200)
        entries += struct.pack("<QQIIQ", offset, len(data), name_offset, hashed, 0) + hashlib.sha256(data[:hashed]).digest()
        offset += len(data)
    header = b"HFS0" + struct.pack("<III", len(files), len(table), 0)
    return header + entries + table + b"".join(data for _, data in files)


def xci(partitions, root_offset=0x200):
    """A card image: its header (magic HEAD at 0x100, the root HFS0's offset at 0x130), then the root HFS0."""
    header = bytearray(filler("xci", root_offset))
    header[0x100:0x104] = b"HEAD"
    struct.pack_into("<Q", header, 0x130, root_offset)
    struct.pack_into("<Q", header, 0x138, 0x200)
    return bytes(header) + hfs0(partitions)


def write(name, data):
    with open(name, "wb") as f:
        f.write(data)


GAME_XCI = 0x0100A0B00C0D0000
write("game.xci", xci([
    # The update partition carries system titles, which aren't the game.
    ("update", hfs0([("0b0c0d0e0f1011121314151617181920.nca", nca(0x0100000000000809, PROGRAM, seed="sys"))])),
    ("normal", hfs0([])),
    ("secure", hfs0([
        ("1a2b3c4d5e6f708192a3b4c5d6e7f801.cnmt.nca", nca(GAME_XCI, META, seed="meta")),
        ("2b3c4d5e6f708192a3b4c5d6e7f80112.nca", nca(GAME_XCI, CONTROL, seed="control")),
        ("3c4d5e6f708192a3b4c5d6e7f8011223.nca", nca(GAME_XCI, PROGRAM, seed="program")),
    ])),
    ("logo", hfs0([])),
]))

GAME_NSP = 0x01002B300C1D2000
write("game.nsp", pfs0([
    ("4d5e6f708192a3b4c5d6e7f801122334.nca", nca(GAME_NSP, PROGRAM, seed="nsp-program")),
    ("5e6f708192a3b4c5d6e7f80112233445.cnmt.nca", nca(GAME_NSP, META, seed="nsp-meta")),
    ("01002b300c1d20000000000000000000.tik", filler("tik", 0x2C0)),
    ("01002b300c1d20000000000000000000.cert", filler("cert", 0x700)),
]))

# A game with its update merged in, and a second program (a multi-program application).
BUNDLE = 0x0100F00D00BA5000
write("bundle.nsp", pfs0([
    ("6f708192a3b4c5d6e7f8011223344556.nca", nca(BUNDLE | 0x800, PROGRAM, seed="bundle-update")),
    ("708192a3b4c5d6e7f801122334455667.nca", nca(BUNDLE, PROGRAM, seed="bundle-game")),
    ("8192a3b4c5d6e7f80112233445566778.nca", nca(BUNDLE + 1, PROGRAM, seed="bundle-second")),
]))

# An update alone: its program is the game's id + 0x800, and the save belongs to the game.
UPDATE_OF = 0x0100C0FFEE000000
write("update.nsp", pfs0([("92a3b4c5d6e7f8011223344556677889.nca", nca(UPDATE_OF | 0x800, PROGRAM, seed="update"))]))

# Two games on one card: Loom can't tell which one's save it is.
write("two-games.xci", xci([
    ("secure", hfs0([
        ("a3b4c5d6e7f801122334455667788990.nca", nca(0x0100111111111000, PROGRAM, seed="one")),
        ("b4c5d6e7f8011223344556677889900a.nca", nca(0x0100222222222000, PROGRAM, seed="two")),
    ])),
]))

# Older NCA2 headers (section headers encrypted as sector 0) read the same.
NCA2 = 0x0100ABCDEF012000
write("nca2.nsp", pfs0([("c5d6e7f80112233445566778899000ab.nca", nca(NCA2, PROGRAM, magic=b"NCA2", seed="nca2"))]))

# An NSZ: compressed NCZ entries keep the NCA's encrypted header as it was.
NSZ = 0x0100D00D00D00000
write("game.nsz", pfs0([("d6e7f8011223344556677889900aabbc.ncz", nca(NSZ, PROGRAM, seed="ncz")[:0xC00] + b"NCZSECTN" + bytes(0x38))]))

# DLC holds data and meta, no program.
write("dlc.nsp", pfs0([
    ("e7f8011223344556677889900aabbccd.nca", nca(0x0100A0B00C0D1001, DATA, seed="dlc-data")),
    ("f8011223344556677889900aabbccdde.cnmt.nca", nca(0x0100A0B00C0D1001, META, seed="dlc-meta")),
]))

# IEEE 1619's AES-XTS test vector 4: a whole 512-byte data unit, so every step of the tweak counts.
VECTOR4_KEY = bytes.fromhex("27182818284590452353602874713526" "31415926535897932384626433832795")
with open("xts-ieee1619-vector4.txt", "w") as f:
    f.write(xts_encrypt(VECTOR4_KEY, 0, bytes(range(256)) * 2).hex() + "\n")

with open("test-header-key.txt", "w") as f:
    f.write(KEY.hex() + "\n")
with open("other-header-key.txt", "w") as f:
    f.write(OTHER_KEY.hex() + "\n")
with open("expected.txt", "w") as f:
    f.write(
        f"game.xci {GAME_XCI:016X}\n"
        f"game.nsp {GAME_NSP:016X}\n"
        f"bundle.nsp {BUNDLE:016X}\n"
        f"update.nsp {UPDATE_OF:016X}\n"
        f"nca2.nsp {NCA2:016X}\n"
        f"game.nsz {NSZ:016X}\n"
    )
