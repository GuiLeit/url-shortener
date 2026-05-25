package com.shortener.encoding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class Base62Encoder {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final char[] shuffledAlphabet;
    private final Map<Character, Integer> reverseMap;

    public Base62Encoder(String secretKey) {
        char[] chars = ALPHABET.toCharArray();
        long seed = deriveSeed(secretKey);
        Random random = new Random(seed);
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        this.shuffledAlphabet = chars;
        this.reverseMap = new HashMap<>(62);
        for (int i = 0; i < chars.length; i++) {
            reverseMap.put(chars[i], i);
        }
    }

    public String encode(long id) {
        if (id <= 0) throw new IllegalArgumentException("id must be positive");
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(shuffledAlphabet[(int)(id % 62)]);
            id /= 62;
        }
        return sb.reverse().toString();
    }

    public long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            result = result * 62 + reverseMap.get(c);
        }
        return result;
    }

    private static long deriveSeed(String secretKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secretKey.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
