package com.dharaneesh.expensetracker.data;

import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class Vault {
  private final SecretKey key;

  public Vault() {
    try {
      KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
      ks.load(null);
      if (!ks.containsAlias("expense-v1")) {
        KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        gen.init(
            new KeyGenParameterSpec.Builder(
                    "expense-v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        gen.generateKey();
      }
      key = (SecretKey) ks.getKey("expense-v1", null);
    } catch (Exception e) {
      throw new IllegalStateException("Secure storage unavailable", e);
    }
  }

  public String encrypt(String value) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key);
      return Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
          + ":"
          + Base64.encodeToString(
              c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encrypt data", e);
    }
  }

  public String decrypt(String value) {
    try {
      String[] parts = value.split(":", 2);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
      return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot unlock stored data", e);
    }
  }
}
