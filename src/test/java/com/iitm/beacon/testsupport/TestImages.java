package com.iitm.beacon.testsupport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Synthetic image fixtures for the photo pipeline tests — every byte is
 * generated here, never read from a real photo. Covers what real uploads
 * carry but a plain {@code ImageIO.write} can't produce: EXIF orientation and
 * GPS, an embedded Display P3 ICC profile, CMYK JPEGs, multi-page TIFFs with
 * a broken page, header-only "decompression bomb" PNGs and HEIC-like bytes.
 */
public final class TestImages {

    public static final Color RED = new Color(230, 20, 20);
    public static final Color GREEN = new Color(20, 200, 20);
    public static final Color BLUE = new Color(20, 20, 230);
    public static final Color WHITE = new Color(250, 250, 250);

    /** TIFF compression tag value no reader implements — makes a page fail to decode. */
    public static final int UNSUPPORTED_TIFF_COMPRESSION = 99;

    private TestImages() {
    }

    // -- pixels --

    public static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    /** Top-left {@link #RED}, top-right {@link #GREEN}, bottom-left {@link #BLUE}, bottom-right {@link #WHITE}. */
    public static BufferedImage quadrants(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        int halfW = width / 2;
        int halfH = height / 2;
        g.setColor(RED);
        g.fillRect(0, 0, halfW, halfH);
        g.setColor(GREEN);
        g.fillRect(halfW, 0, width - halfW, halfH);
        g.setColor(BLUE);
        g.fillRect(0, halfH, halfW, height - halfH);
        g.setColor(WHITE);
        g.fillRect(halfW, halfH, width - halfW, height - halfH);
        g.dispose();
        return image;
    }

    /** Random noise: hard to compress, so encoder quality shows up clearly in the output size. */
    public static BufferedImage noise(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }

    /** Left half opaque {@link #RED}, right half fully transparent. */
    public static BufferedImage halfTransparent(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x < width / 2 ? RED.getRGB() : 0x00000000);
            }
        }
        return image;
    }

    // -- plain encodings --

    public static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format + " / " + image.getType());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static byte[] jpeg(BufferedImage image) {
        return encode(image, "jpeg");
    }

    public static byte[] png(BufferedImage image) {
        return encode(image, "png");
    }

    /** Lossless WebP, so the input's colours are exact. */
    public static byte[] webpLossless(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossless");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // -- decoding helpers for assertions --

    public static boolean isWebp(byte[] bytes) {
        return bytes.length > 12
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    public static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IllegalStateException("No reader for the given bytes");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -- JPEG with extra segments (EXIF, ICC, Adobe) --

    /** Inserts one APPn segment right after the JPEG's SOI marker. */
    public static byte[] withJpegSegment(byte[] jpeg, int marker, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(marker);
        int length = payload.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    public static byte[] jpegWithExif(BufferedImage image, int orientation, boolean withGps) {
        return withJpegSegment(jpeg(image), 0xE1, exifPayload(orientation, withGps));
    }

    /**
     * An APP1 "Exif" payload: IFD0 with the Orientation tag and, optionally,
     * a GPS IFD with a latitude/longitude, like a phone camera writes.
     */
    public static byte[] exifPayload(int orientation, boolean withGps) {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN);
        b.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        int ifd0Entries = withGps ? 2 : 1;
        b.putShort((short) ifd0Entries);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        int gpsIfdOffset = 8 + 2 + ifd0Entries * 12 + 4;
        if (withGps) {
            b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfdOffset);
        }
        b.putInt(0);
        if (withGps) {
            int entries = 4;
            int dataOffset = gpsIfdOffset + 2 + entries * 12 + 4;
            b.putShort((short) entries);
            b.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
            b.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(dataOffset);
            b.putShort((short) 0x0003).putShort((short) 2).putInt(2).put((byte) 'E').put((byte) 0).putShort((short) 0);
            b.putShort((short) 0x0004).putShort((short) 5).putInt(3).putInt(dataOffset + 24);
            b.putInt(0);
            b.putInt(55).putInt(1).putInt(45).putInt(1).putInt(0).putInt(1);
            b.putInt(37).putInt(1).putInt(37).putInt(1).putInt(0).putInt(1);
        }
        byte[] payload = new byte[b.position()];
        b.flip();
        b.get(payload);
        return payload;
    }

    /**
     * A Display P3 ICC profile (P3 primaries, D65 white, sRGB tone curve —
     * what iPhones embed): the JDK's own sRGB profile with its three
     * colorant tags replaced by the P3 ones (D50-adapted, as ICC requires).
     */
    public static ICC_Profile displayP3Profile() {
        ICC_Profile profile = ICC_Profile.getInstance(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        profile.setData(ICC_Profile.icSigRedColorantTag, xyzTag(0.515102, 0.241196, -0.001053));
        profile.setData(ICC_Profile.icSigGreenColorantTag, xyzTag(0.291953, 0.692209, 0.041885));
        profile.setData(ICC_Profile.icSigBlueColorantTag, xyzTag(0.157248, 0.066595, 0.784336));
        return profile;
    }

    private static byte[] xyzTag(double x, double y, double z) {
        ByteBuffer b = ByteBuffer.allocate(20);
        b.put("XYZ ".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        b.putInt((int) Math.round(x * 65536)).putInt((int) Math.round(y * 65536)).putInt((int) Math.round(z * 65536));
        return b.array();
    }

    public static byte[] jpegWithIccProfile(BufferedImage image, ICC_Profile profile) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII));
        payload.write(1);
        payload.write(1);
        payload.writeBytes(profile.getData());
        return withJpegSegment(jpeg(image), 0xE2, payload.toByteArray());
    }

    /**
     * Adobe CMYK JPEG (APP14, transform 0 — stored inverted, as Photoshop
     * writes it): left half full cyan ink, right half no ink (white).
     */
    public static byte[] cmykJpegCyanLeftWhiteRight(int width, int height) {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
        int[] cyan = {0, 255, 255, 255};
        int[] paper = {255, 255, 255, 255};
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setPixel(x, y, x < width / 2 ? cyan : paper);
            }
        }
        ImageWriter writer = rasterCapableJpegWriter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        byte[] adobe = {'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 0};
        return withJpegSegment(out.toByteArray(), 0xEE, adobe);
    }

    private static ImageWriter rasterCapableJpegWriter() {
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg"); it.hasNext(); ) {
            ImageWriter writer = it.next();
            if (writer.canWriteRasters()) {
                return writer;
            }
        }
        throw new IllegalStateException("No JPEG writer can write rasters");
    }

    // -- PNG crafting --

    /** Inserts an iCCP chunk (embedded ICC profile) right after the PNG's IHDR chunk. */
    public static byte[] pngWithIccProfile(byte[] png, ICC_Profile profile) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(profile.getData());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("Display P3\0".getBytes(StandardCharsets.US_ASCII));
        body.write(0);
        body.writeBytes(compressed.toByteArray());
        byte[] chunk = pngChunk("iCCP", body.toByteArray());
        int afterIhdr = 8 + 25;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        out.writeBytes(chunk);
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    /**
     * A PNG that only declares its dimensions (IHDR, then IEND, no pixel
     * data) — the shape of a decompression bomb, without allocating one.
     */
    public static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        out.writeBytes(pngChunk("IHDR", ihdr.array()));
        out.writeBytes(pngChunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static byte[] pngChunk(String type, byte[] data) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(chunk)) {
            out.writeInt(data.length);
            byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            out.write(typeBytes);
            out.write(data);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            out.writeInt((int) crc.getValue());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return chunk.toByteArray();
    }

    // -- TIFF crafting --

    /** One page of a hand-built TIFF: a solid colour, uncompressed unless {@code compression} says otherwise. */
    public record TiffPage(int width, int height, Color color, int compression) {

        public static TiffPage of(int width, int height, Color color) {
            return new TiffPage(width, height, color, 1);
        }

        public static TiffPage undecodable(int width, int height) {
            return new TiffPage(width, height, Color.BLACK, UNSUPPORTED_TIFF_COMPRESSION);
        }
    }

    /** A little-endian, strip-per-page, 8-bit RGB TIFF with one IFD per page (like a multi-page scan or a DNG). */
    public static byte[] tiff(List<TiffPage> pages) {
        final int entries = 10;
        final int ifdSize = 2 + entries * 12 + 4;
        int[] bitsOffsets = new int[pages.size()];
        int[] dataOffsets = new int[pages.size()];
        int[] ifdOffsets = new int[pages.size()];
        int position = 8;
        for (int i = 0; i < pages.size(); i++) {
            TiffPage page = pages.get(i);
            bitsOffsets[i] = position;
            position += 8;
            dataOffsets[i] = position;
            position += even(page.width() * page.height() * 3);
            ifdOffsets[i] = position;
            position += even(ifdSize);
        }
        ByteBuffer b = ByteBuffer.allocate(position).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdOffsets[0]);
        for (int i = 0; i < pages.size(); i++) {
            TiffPage page = pages.get(i);
            b.position(bitsOffsets[i]);
            b.putShort((short) 8).putShort((short) 8).putShort((short) 8);
            b.position(dataOffsets[i]);
            for (int p = 0; p < page.width() * page.height(); p++) {
                Color c = page.color();
                b.put((byte) c.getRed()).put((byte) c.getGreen()).put((byte) c.getBlue());
            }
            b.position(ifdOffsets[i]);
            b.putShort((short) entries);
            longEntry(b, 256, page.width());
            longEntry(b, 257, page.height());
            b.putShort((short) 258).putShort((short) 3).putInt(3).putInt(bitsOffsets[i]);
            shortEntry(b, 259, page.compression());
            shortEntry(b, 262, 2);
            longEntry(b, 273, dataOffsets[i]);
            shortEntry(b, 277, 3);
            longEntry(b, 278, page.height());
            longEntry(b, 279, page.width() * page.height() * 3);
            shortEntry(b, 284, 1);
            b.putInt(i + 1 < pages.size() ? ifdOffsets[i + 1] : 0);
        }
        return b.array();
    }

    private static int even(int n) {
        return n + (n & 1);
    }

    private static void longEntry(ByteBuffer b, int tag, int value) {
        b.putShort((short) tag).putShort((short) 4).putInt(1).putInt(value);
    }

    private static void shortEntry(ByteBuffer b, int tag, int value) {
        b.putShort((short) tag).putShort((short) 3).putInt(1).putShort((short) value).putShort((short) 0);
    }

    // -- formats no ImageIO reader supports --

    /** The leading ISO-BMFF {@code ftyp} box of a HEIC/HEIF/AVIF file, e.g. brand {@code "heic"}. */
    public static byte[] isoBmffFile(String brand) {
        ByteBuffer b = ByteBuffer.allocate(64);
        b.putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put(brand.getBytes(StandardCharsets.US_ASCII));
        b.putInt(0).put("mif1".getBytes(StandardCharsets.US_ASCII)).put(brand.getBytes(StandardCharsets.US_ASCII));
        b.putInt(40).put("meta".getBytes(StandardCharsets.US_ASCII));
        return b.array();
    }
}
