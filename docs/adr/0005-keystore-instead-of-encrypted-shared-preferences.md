# Encrypt credentials with a Keystore key, not EncryptedSharedPreferences

The brief allowed EncryptedSharedPreferences or the Keystore. `androidx.security:security-crypto` 1.1.0 deprecated all of its APIs with no further releases planned, so Loom keeps a non-exportable AES-256-GCM key in the Android Keystore and stores the encrypted token (IV + ciphertext) in DataStore.
