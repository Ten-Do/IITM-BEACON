package com.iitm.beacon.submission;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.zip.InflaterInputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

/**
 * Normalises one uploaded photo into what the app actually stores and serves
 * (decision 2): any image an {@code ImageIO} reader can decode (the JDK's
 * plus TwelveMonkeys': JPEG incl. CMYK, PNG, GIF, BMP, TIFF, WebP, ...) goes
 * in; out come a full-size and a thumbnail lossy WebP, EXIF-oriented, 8-bit
 * sRGB, with alpha kept, and without any of the original's metadata — no
 * EXIF, GPS, XMP or ICC profile is ever copied.
 *
 * <p>The pipeline, in order:
 * <ol>
 *   <li>the format is detected from the bytes by the registered readers —
 *       never the client's Content-Type or filename (NFR-UPLOAD-SPOOFING);
 *       nothing readable (HEIC/AVIF included) is an "unsupported format";</li>
 *   <li>every image in the file has its dimensions read from its header
 *       first, and a file declaring one larger than {@code max-pixels} is
 *       rejected before anything is decoded (decompression bombs);</li>
 *   <li>the largest image that actually decodes is used (multi-page TIFF,
 *       DNG-as-TIFF), decoded with source subsampling so a 48 or 200 MP photo
 *       never sits in the heap at full resolution;</li>
 *   <li>colours are converted to sRGB from an embedded ICC profile (e.g. the
 *       iPhone's Display P3) — including PNG's, which the JDK reader ignores;</li>
 *   <li>the image is downscaled (progressive halving, then bicubic) to {@code
 *       full-max-edge} on its long side, never upscaled; EXIF orientation is
 *       applied; the thumbnail is derived from that full image;</li>
 *   <li>both are encoded as lossy WebP by webp-imageio's native libwebp.</li>
 * </ol>
 *
 * <p>Constructing it proves the native WebP encoder works on this platform,
 * so a broken deployment fails at startup, not on the first upload.
 */
@Component
public final class PhotoImageProcessor {

    /** The formats named to visitors: the common ones of all those the registered readers decode. */
    static final String ACCEPTED_FORMATS = "JPEG, PNG, WebP, GIF, TIFF or BMP";

    static final String UNSUPPORTED_FORMAT_MESSAGE =
            "Unsupported image format. Please upload " + ACCEPTED_FORMATS + ".";

    private static final Logger log = LoggerFactory.getLogger(PhotoImageProcessor.class);

    /** Upper bound on the images considered in one file (e.g. TIFF pages, GIF frames), against pathological files. */
    private static final int MAX_IMAGES_CONSIDERED = 32;

    private static final String WEBP_MIME_TYPE = "image/webp";
    private static final String PNG_NATIVE_METADATA_FORMAT = "javax_imageio_png_1.0";

    private final long maxPixels;
    private final int fullMaxEdge;
    private final int thumbMaxEdge;
    private final float fullQuality;
    private final float thumbQuality;
    private final Supplier<Optional<ImageWriter>> webpWriters;

    @Autowired
    public PhotoImageProcessor(PhotoStorageProperties properties) {
        this(properties, PhotoImageProcessor::newWebpWriter);
    }

    /**
     * @param webpWriters hands out a fresh WebP {@link ImageWriter} per call
     *     (writers aren't thread-safe), or none if no WebP writer is registered
     */
    PhotoImageProcessor(PhotoStorageProperties properties, Supplier<Optional<ImageWriter>> webpWriters) {
        this.maxPixels = properties.maxPixels();
        this.fullMaxEdge = properties.fullMaxEdge();
        this.thumbMaxEdge = properties.thumbMaxEdge();
        this.fullQuality = properties.webpQuality() / 100f;
        this.thumbQuality = properties.thumbWebpQuality() / 100f;
        this.webpWriters = webpWriters;
        // Re-scan with this thread's context class loader: inside Spring
        // Boot's executable jar, plugins in nested jars (TwelveMonkeys,
        // webp-imageio) are only found through it.
        ImageIO.scanForPlugins();
        verifyWebpEncoderWorks();
    }

    /**
     * Converts one uploaded photo.
     *
     * @throws SubmissionValidationException if the bytes aren't a readable
     *     image, or declare an image larger than {@code max-pixels}
     */
    public ProcessedPhoto process(byte[] original) {
        DecodedImage decoded = decode(original);
        BufferedImage srgb = toSrgb(decoded.image(), decoded.embeddedProfile());
        BufferedImage full = applyOrientation(scaleToFit(srgb, fullMaxEdge), exifOrientationOf(original));
        BufferedImage thumb = scaleToFit(full, thumbMaxEdge);
        return new ProcessedPhoto(
                encodeWebp(full, fullQuality), encodeWebp(thumb, thumbQuality), full.getWidth(), full.getHeight());
    }

    /**
     * How many source pixels to step over per decoded pixel so the decoded
     * image's long edge is still at least {@code targetLongEdge}: the final
     * downscale then always starts from enough pixels, while a huge photo
     * never has to be held in the heap at full resolution.
     */
    static int subsamplingFor(int width, int height, int targetLongEdge) {
        return Math.max(1, Math.max(width, height) / targetLongEdge);
    }

    // -- decoding --

    private record DecodedImage(BufferedImage image, Optional<ICC_Profile> embeddedProfile) {
    }

    private record Candidate(int index, int width, int height) {

        long pixels() {
            return (long) width * height;
        }
    }

    private DecodedImage decode(byte[] bytes) {
        for (ImageReader reader : readersFor(bytes)) {
            try {
                Optional<DecodedImage> decoded = decodeLargestImage(reader, bytes);
                if (decoded.isPresent()) {
                    return decoded.get();
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof SubmissionValidationException rejection) {
                    throw rejection;
                }
                log.debug("{} could not decode the upload: {}", reader.getClass().getName(), e.toString());
            } finally {
                reader.dispose();
            }
        }
        throw new SubmissionValidationException(UNSUPPORTED_FORMAT_MESSAGE);
    }

    /** Every registered reader that recognises the bytes, in registry order (TwelveMonkeys' first). */
    private static List<ImageReader> readersFor(byte[] bytes) {
        List<ImageReader> readers = new ArrayList<>();
        try (ImageInputStream probe = new ByteArrayImageInputStream(bytes)) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(probe);
            it.forEachRemaining(readers::add);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not probe the upload's format: {}", e.toString());
        }
        return readers;
    }

    private Optional<DecodedImage> decodeLargestImage(ImageReader reader, byte[] bytes) throws IOException {
        reader.setInput(new ByteArrayImageInputStream(bytes));
        List<Candidate> candidates = candidates(reader);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.get(0).pixels() > maxPixels) {
            throw new SubmissionValidationException(
                    "The image is too large: at most " + megapixels(maxPixels) + " megapixels are allowed.");
        }
        for (Candidate candidate : candidates) {
            try {
                reader.setInput(new ByteArrayImageInputStream(bytes));
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsamplingFor(candidate.width(), candidate.height(), fullMaxEdge);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage image = reader.read(candidate.index(), param);
                return Optional.of(new DecodedImage(image, ignoredEmbeddedProfile(reader, candidate.index())));
            } catch (IOException | RuntimeException e) {
                log.debug("Image {} of the upload did not decode: {}", candidate.index(), e.toString());
            }
        }
        return Optional.empty();
    }

    /** The file's images with readable dimensions, largest first (ties: file order). */
    private static List<Candidate> candidates(ImageReader reader) {
        int count;
        try {
            count = reader.getNumImages(true);
        } catch (IOException | RuntimeException e) {
            count = 1;
        }
        count = Math.min(count < 0 ? 1 : count, MAX_IMAGES_CONSIDERED);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                int width = reader.getWidth(i);
                int height = reader.getHeight(i);
                if (width > 0 && height > 0) {
                    candidates.add(new Candidate(i, width, height));
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Image {} of the upload has no readable dimensions: {}", i, e.toString());
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::pixels).reversed().thenComparingInt(Candidate::index));
        return candidates;
    }

    private static String megapixels(long pixels) {
        return BigDecimal.valueOf(pixels, 6).stripTrailingZeros().toPlainString();
    }

    /**
     * The ICC profile a PNG embeds in its iCCP chunk: the JDK's PNG reader
     * ignores it and hands back the raw values labelled as sRGB, so it has to
     * be applied here (iPhone screenshots are Display P3 PNGs). Every other
     * reader in use applies or exposes the embedded profile itself.
     */
    private static Optional<ICC_Profile> ignoredEmbeddedProfile(ImageReader reader, int index) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(index);
            if (metadata == null || !PNG_NATIVE_METADATA_FORMAT.equals(metadata.getNativeMetadataFormatName())) {
                return Optional.empty();
            }
            Node root = metadata.getAsTree(PNG_NATIVE_METADATA_FORMAT);
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if ("iCCP".equals(node.getNodeName())
                        && node instanceof IIOMetadataNode iccp
                        && iccp.getUserObject() instanceof byte[] compressed) {
                    try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                        return Optional.of(ICC_Profile.getInstance(in.readAllBytes()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring an unreadable embedded colour profile: {}", e.toString());
        }
        return Optional.empty();
    }

    // -- colour --

    /**
     * An 8-bit sRGB copy ({@code TYPE_INT_RGB}, or {@code TYPE_INT_ARGB} when
     * the source has alpha). A non-sRGB RGB source is colour-managed with
     * {@link ColorConvertOp} (far faster than letting Java2D convert per
     * pixel); everything else — gray, indexed, 16-bit, already-sRGB — is
     * drawn, which keeps gray levels as they are (as browsers show them).
     */
    private static BufferedImage toSrgb(BufferedImage decoded, Optional<ICC_Profile> embeddedProfile) {
        boolean alpha = decoded.getColorModel().hasAlpha();
        BufferedImage source = embeddedProfile.flatMap(profile -> relabel(decoded, profile)).orElse(decoded);
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(decoded.getWidth(), decoded.getHeight(), type);
        ColorSpace colorSpace = source.getColorModel().getColorSpace();
        if (colorSpace.getType() == ColorSpace.TYPE_RGB && !colorSpace.isCS_sRGB()) {
            try {
                new ColorConvertOp(null).filter(source, target);
                return target;
            } catch (RuntimeException e) {
                log.debug("Colour conversion to sRGB failed, drawing the pixels as they are: {}", e.toString());
            }
        }
        Graphics2D g = target.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(decoded, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** The same pixel values, re-labelled as being in {@code profile}'s colour space (RGB profiles only). */
    private static Optional<BufferedImage> relabel(BufferedImage decoded, ICC_Profile profile) {
        if (profile.getColorSpaceType() != ColorSpace.TYPE_RGB) {
            return Optional.empty();
        }
        try {
            boolean alpha = decoded.getColorModel().hasAlpha();
            BufferedImage bytes = new BufferedImage(
                    decoded.getWidth(),
                    decoded.getHeight(),
                    alpha ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bytes.createGraphics();
            try {
                g.setComposite(AlphaComposite.Src);
                g.drawImage(decoded, 0, 0, null);
            } finally {
                g.dispose();
            }
            ColorModel model = new ComponentColorModel(
                    new ICC_ColorSpace(profile),
                    alpha,
                    false,
                    alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                    DataBuffer.TYPE_BYTE);
            return Optional.of(new BufferedImage(model, bytes.getRaster(), false, null));
        } catch (RuntimeException e) {
            log.debug("Ignoring an unusable embedded colour profile: {}", e.toString());
            return Optional.empty();
        }
    }

    // -- geometry --

    /**
     * Downscales so the long edge is at most {@code maxEdge} — never
     * upscales. Halves with bilinear filtering while that doesn't undershoot
     * (an exact 2x box filter), then makes one bicubic step to the target,
     * which avoids the aliasing of a single large bilinear/bicubic step.
     */
    private static BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= maxEdge) {
            return source;
        }
        double scale = (double) maxEdge / longEdge;
        int targetWidth = width >= height ? maxEdge : Math.max(1, (int) Math.round(width * scale));
        int targetHeight = width >= height ? Math.max(1, (int) Math.round(height * scale)) : maxEdge;
        BufferedImage current = source;
        int currentWidth = width;
        int currentHeight = height;
        while (currentWidth / 2 >= targetWidth && currentHeight / 2 >= targetHeight) {
            currentWidth /= 2;
            currentHeight /= 2;
            current = resize(current, currentWidth, currentHeight, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (currentWidth != targetWidth || currentHeight != targetHeight) {
            current = resize(current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height, Object interpolation) {
        BufferedImage target = new BufferedImage(width, height, source.getType());
        Graphics2D g = target.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** EXIF Orientation 1-8, or 1 when absent, unreadable or out of range. */
    private static int exifOrientationOf(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int orientation = ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (orientation >= 1 && orientation <= 8) {
                    return orientation;
                }
            }
        } catch (ImageProcessingException | IOException | MetadataException | RuntimeException e) {
            log.debug("No usable EXIF orientation: {}", e.toString());
        }
        return 1;
    }

    /**
     * Turns the stored pixels into the upright image the EXIF Orientation
     * describes: 2/4 mirror, 3 rotates 180°, 6/8 rotate 90° clockwise/counter-
     * clockwise, 5/7 are the transpose/transverse (rotation plus mirror).
     */
    private static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation == 1) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swapsAxes = orientation >= 5;
        int outW = swapsAxes ? h : w;
        int outH = swapsAxes ? w : h;
        int[] in = image.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tx;
                int ty;
                switch (orientation) {
                    case 2 -> {
                        tx = w - 1 - x;
                        ty = y;
                    }
                    case 3 -> {
                        tx = w - 1 - x;
                        ty = h - 1 - y;
                    }
                    case 4 -> {
                        tx = x;
                        ty = h - 1 - y;
                    }
                    case 5 -> {
                        tx = y;
                        ty = x;
                    }
                    case 6 -> {
                        tx = h - 1 - y;
                        ty = x;
                    }
                    case 7 -> {
                        tx = h - 1 - y;
                        ty = w - 1 - x;
                    }
                    default -> {
                        tx = y;
                        ty = w - 1 - x;
                    }
                }
                out[ty * outW + tx] = in[y * w + x];
            }
        }
        BufferedImage oriented = new BufferedImage(outW, outH, image.getType());
        oriented.setRGB(0, 0, outW, outH, out, 0, outW);
        return oriented;
    }

    // -- encoding --

    private static Optional<ImageWriter> newWebpWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(WEBP_MIME_TYPE);
        return writers.hasNext() ? Optional.of(writers.next()) : Optional.empty();
    }

    private byte[] encodeWebp(BufferedImage image, float quality) {
        ImageWriter writer = webpWriters.get().orElseThrow(() -> new IllegalStateException(
                "No WebP image writer is registered (is webp-imageio on the classpath?)."));
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("Lossy");
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode the photo as WebP.", e);
        } finally {
            writer.dispose();
        }
    }

    /**
     * Encodes a 1x1 image. webp-imageio only loads its bundled native libwebp
     * on first use, so without this a platform it doesn't support (or a
     * non-executable temp dir) would only surface on the first upload.
     */
    private void verifyWebpEncoderWorks() {
        try {
            encodeWebp(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), fullQuality);
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException(
                    "WebP encoding is unavailable on this platform (webp-imageio's native libwebp could not be"
                            + " used), so uploaded photos could not be converted: " + e,
                    e);
        }
        log.info("Photo processing ready: WebP encoder OK; readable image formats: {}", readableFormats());
    }

    private static TreeSet<String> readableFormats() {
        TreeSet<String> formats = new TreeSet<>();
        for (String name : ImageIO.getReaderFormatNames()) {
            formats.add(name.toLowerCase(Locale.ROOT));
        }
        return formats;
    }
}
