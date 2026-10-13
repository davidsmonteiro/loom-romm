# Verify archives by their members, not by the file hash

RomM 5.3.1 computes a ROM file's CRC/MD5/SHA-1 over the *decompressed* member bytes when the file is a `.zip`, `.7z`, `.tar`, `.gz` or `.bz2` (`backend/handler/filesystem/roms_handler.py`), so hashing a downloaded archive's raw bytes would never match. Loom verifies plain files by SHA-1 (else MD5, else CRC32) of the raw bytes, verifies archives member by member against `archive_members`, falling back to the archive's own CRCs, and falls back to size only for platforms RomM never hashes (Switch, Wii U, PS3, …).
