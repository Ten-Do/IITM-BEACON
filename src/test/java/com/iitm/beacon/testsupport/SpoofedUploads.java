package com.iitm.beacon.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthetic non-image files for the upload-spoofing tests
 * (NFR-UPLOAD-SPOOFING): what a malicious visitor might send under a
 * {@code .jpg} name and an {@code image/jpeg} Content-Type. Each carries its
 * format's real magic bytes and header shape, so a check that only looked at
 * the first bytes, the name or the declared type would be fooled — but none
 * of them is an image. Also image polyglots: a real image with a payload
 * appended or embedded, which must come out re-encoded without it. Every
 * payload contains {@link #MARKER}, so a test can look for it in the stored
 * files.
 */
public final class SpoofedUploads {

    /** In every payload; must never be found in a stored file. */
    public static final String MARKER = "BEACON-PAYLOAD-7f3a9c";

    public static final String SCRIPT = "<script>alert('" + MARKER + "')</script>";

    private SpoofedUploads() {
    }

    /** A Windows PE executable: DOS stub ("MZ", "This program cannot be run in DOS mode."), then a PE header. */
    public static byte[] windowsExecutable() {
        ByteBuffer pe = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        pe.put(0, (byte) 'M').put(1, (byte) 'Z').put(2, (byte) 0x90);
        pe.putInt(0x3C, 0x80);
        put(pe, 0x4E, "This program cannot be run in DOS mode.\r\r\n$");
        put(pe, 0x80, "PE\0\0");
        pe.putShort(0x84, (short) 0x8664);
        pe.putShort(0x86, (short) 1);
        put(pe, 0x200, MARKER);
        return pe.array();
    }

    /** A 64-bit little-endian Linux ELF executable header. */
    public static byte[] linuxExecutable() {
        ByteBuffer elf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        elf.put(0, (byte) 0x7F);
        put(elf, 1, "ELF");
        elf.put(4, (byte) 2).put(5, (byte) 1).put(6, (byte) 1);
        elf.putShort(16, (short) 2);
        elf.putShort(18, (short) 0x3E);
        elf.putInt(20, 1);
        elf.putLong(24, 0x401000L);
        put(elf, 0x100, MARKER);
        return elf.array();
    }

    /** An SVG (an XML "image" that browsers run scripts in). */
    public static byte[] svgWithScript() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                        + "<rect width=\"10\" height=\"10\" fill=\"red\"/>" + SCRIPT + "</svg>\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] htmlWithScript() {
        return ("<!DOCTYPE html><html><head><title>x</title></head><body>" + SCRIPT + "</body></html>\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A minimal one-page PDF. */
    public static byte[] pdf() {
        return ("%PDF-1.4\n"
                        + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                        + "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
                        + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >> endobj\n"
                        + "% " + MARKER + "\n"
                        + "trailer << /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** A real ZIP archive with one text entry. */
    public static byte[] zip() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(MARKER + ".txt"));
            zip.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static byte[] shellScript() {
        return ("#!/bin/sh\necho " + MARKER + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** {@code image} followed by {@code payload}: still a valid image to any reader that stops at its end. */
    public static byte[] appended(byte[] image, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(image);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** {@code jpeg} with a COM (comment) segment holding {@link #SCRIPT}, right after its SOI marker. */
    public static byte[] jpegWithScriptComment(byte[] jpeg) {
        return TestImages.withJpegSegment(jpeg, 0xFE, SCRIPT.getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code png} with a {@code tEXt} chunk holding {@link #SCRIPT}, right after its IHDR chunk. */
    public static byte[] pngWithScriptText(byte[] png) {
        byte[] body = ("Comment\0" + SCRIPT).getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer chunk = ByteBuffer.allocate(12 + body.length);
        chunk.putInt(body.length);
        chunk.put("tEXt".getBytes(StandardCharsets.US_ASCII));
        chunk.put(body);
        CRC32 crc = new CRC32();
        crc.update("tEXt".getBytes(StandardCharsets.US_ASCII));
        crc.update(body);
        chunk.putInt((int) crc.getValue());
        int afterIhdr = 8 + 25;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        out.writeBytes(chunk.array());
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    /** True if {@code haystack} contains {@code needle} anywhere. */
    public static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }

    private static void put(ByteBuffer buffer, int offset, String ascii) {
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) {
            buffer.put(offset + i, bytes[i]);
        }
    }
}
