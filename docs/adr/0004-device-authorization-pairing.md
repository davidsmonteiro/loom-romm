# Pair with RomM's device-authorization flow first

Typing on the Thor is slow, so Loom never asks for the long token. Its primary pairing is RomM's device-authorization flow (RomM ≥ 5.0, the same one Argosy uses): Loom shows a QR code and an 8-character code, the user approves on a phone in RomM's web UI, and Loom polls for a device-bound client token. The web-UI pair code (`/api/client-tokens/exchange`) and pasting a token remain as fallbacks. Minimum supported RomM is therefore 5.0.0; the contract is pinned to 5.3.1.
