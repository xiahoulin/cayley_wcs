package com.cayleywcs.auth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class PasswordService {

    public boolean matches(String submittedPassword, String storedHash) {
        if (submittedPassword == null || storedHash == null) {
            return false;
        }
        return storedHash.equalsIgnoreCase(submittedPassword)
                || storedHash.equalsIgnoreCase(md5Hex(submittedPassword));
    }

    public String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("MD5 is not available", ex);
        }
    }
}
