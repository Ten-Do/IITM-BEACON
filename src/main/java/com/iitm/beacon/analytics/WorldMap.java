package com.iitm.beacon.analytics;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The country outlines of the homepage map (decision 30): ISO 3166-1
 * alpha-2 code → SVG path, plus the {@code viewBox} they are drawn in,
 * converted once from jsvectormap's world map into {@value #LOCATION}.
 * Read once, at construction; a missing or malformed file stops the
 * application from starting instead of rendering a broken map.
 */
@Component
final class WorldMap {

    static final String LOCATION = "classpath:analytics/world-map.json";

    private static final Pattern CODE = Pattern.compile("[A-Z]{2}");
    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final Pattern VIEW_BOX = Pattern.compile(NUMBER + "(?:\\s+" + NUMBER + "){3}");

    private final String viewBox;
    private final List<Region> regions;

    WorldMap(ObjectMapper objectMapper, @Value(LOCATION) Resource resource) {
        JsonNode root = read(objectMapper, resource);
        String source = resource.getDescription();
        this.viewBox = viewBox(root, source);
        this.regions = regions(root, source);
    }

    /** The SVG {@code viewBox} the region paths are drawn in, e.g. {@code "0 0 900 440.71"}. */
    String viewBox() {
        return viewBox;
    }

    /** Every region, ordered by code; unmodifiable. */
    List<Region> regions() {
        return regions;
    }

    private static JsonNode read(ObjectMapper objectMapper, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper
                    .reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(in);
            if (root == null || !root.isObject()) {
                throw malformed(resource.getDescription(), "not a JSON object");
            }
            return root;
        } catch (JsonProcessingException ex) {
            throw malformed(resource.getDescription(), ex.getOriginalMessage());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the world map " + resource.getDescription(), ex);
        }
    }

    private static String viewBox(JsonNode root, String source) {
        JsonNode node = root.get("viewBox");
        if (node == null || !node.isTextual() || !VIEW_BOX.matcher(node.asText()).matches()) {
            throw malformed(source, "viewBox must be four numbers separated by spaces");
        }
        return node.asText();
    }

    private static List<Region> regions(JsonNode root, String source) {
        JsonNode node = root.get("regions");
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw malformed(source, "regions must be a non-empty object of code to SVG path");
        }
        List<Region> found = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String code = entry.getKey();
            if (!CODE.matcher(code).matches()) {
                throw malformed(source, "region code '" + code + "' is not two uppercase letters");
            }
            JsonNode path = entry.getValue();
            if (!path.isTextual() || path.asText().isBlank()) {
                throw malformed(source, "region " + code + " has no SVG path");
            }
            found.add(new Region(code, path.asText()));
        }
        found.sort(Comparator.comparing(Region::code));
        return List.copyOf(found);
    }

    private static IllegalStateException malformed(String source, String reason) {
        return new IllegalStateException("Malformed world map " + source + ": " + reason);
    }

    /** One country outline: its ISO 3166-1 alpha-2 code and its SVG path data. */
    record Region(String code, String path) {
    }
}
