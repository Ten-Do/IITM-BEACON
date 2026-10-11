package com.iitm.beacon.testsupport;

import java.awt.geom.Path2D;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG path data as a {@link Path2D}, for tests that check geometry, e.g.
 * whether a point lies inside a country outline of
 * {@code analytics/world-map.json}. Only the subset that file uses is
 * supported: moveto, lineto and closepath ({@code M m L l Z z}), absolute
 * and relative, with plain decimal numbers ({@code 12}, {@code -0.92},
 * {@code 1.0}) separated by commas and/or whitespace. Anything else (other
 * commands, exponents, a missing coordinate) throws
 * {@link IllegalArgumentException} rather than producing a wrong shape. The
 * shape is filled like a browser fills the path: SVG's default fill rule,
 * nonzero.
 */
public final class SvgPaths {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private SvgPaths() {
    }

    /** {@code d} (an SVG path's {@code d} attribute) as a nonzero-filled shape. */
    public static Path2D parse(String d) {
        Objects.requireNonNull(d, "path data");
        return new Parser(d).parse();
    }

    private static final class Parser {

        private final String d;
        private final Matcher number;
        private final Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        private int pos;
        private double x;
        private double y;
        private double startX;
        private double startY;
        private boolean justClosed;

        Parser(String d) {
            this.d = d;
            this.number = NUMBER.matcher(d);
        }

        Path2D parse() {
            skipSeparators();
            if (pos == d.length()) {
                throw new IllegalArgumentException("Empty SVG path data");
            }
            char first = d.charAt(pos);
            if (first != 'M' && first != 'm') {
                throw new IllegalArgumentException("SVG path data must start with a moveto (M or m), found '"
                        + first + "' at index " + pos);
            }
            while (true) {
                skipSeparators();
                if (pos == d.length()) {
                    return path;
                }
                command(d.charAt(pos));
            }
        }

        private void command(char command) {
            int at = pos;
            pos++;
            switch (command) {
                case 'M', 'm' -> {
                    boolean relative = command == 'm';
                    moveTo(relative, at);
                    while (numberAhead()) {
                        lineTo(relative, at);
                    }
                }
                case 'L', 'l' -> {
                    boolean relative = command == 'l';
                    do {
                        lineTo(relative, at);
                    } while (numberAhead());
                }
                case 'Z', 'z' -> {
                    path.closePath();
                    x = startX;
                    y = startY;
                    justClosed = true;
                    if (numberAhead()) {
                        throw new IllegalArgumentException("Number right after a closepath at index " + pos);
                    }
                }
                default -> throw new IllegalArgumentException(Character.isLetter(command)
                        ? "Unsupported SVG path command '" + command + "' at index " + at
                                + " (only M, m, L, l, Z and z are supported)"
                        : "Unexpected character '" + command + "' at index " + at + " of SVG path data");
            }
        }

        private void moveTo(boolean relative, int commandAt) {
            double[] pair = pair(commandAt);
            x = relative ? x + pair[0] : pair[0];
            y = relative ? y + pair[1] : pair[1];
            startX = x;
            startY = y;
            path.moveTo(x, y);
            justClosed = false;
        }

        /** SVG: a lineto right after a closepath starts a new subpath at the closed one's start. */
        private void lineTo(boolean relative, int commandAt) {
            double[] pair = pair(commandAt);
            if (justClosed) {
                path.moveTo(x, y);
                justClosed = false;
            }
            x = relative ? x + pair[0] : pair[0];
            y = relative ? y + pair[1] : pair[1];
            path.lineTo(x, y);
        }

        private double[] pair(int commandAt) {
            return new double[] {coordinate(commandAt), coordinate(commandAt)};
        }

        private double coordinate(int commandAt) {
            if (!numberAhead()) {
                throw new IllegalArgumentException("Command '" + d.charAt(commandAt) + "' at index " + commandAt
                        + " needs a complete coordinate pair; no number at index " + pos);
            }
            number.region(pos, d.length()).lookingAt();
            pos = number.end();
            return Double.parseDouble(number.group());
        }

        /** Skips separators; true if a number (in the supported format) starts next. */
        private boolean numberAhead() {
            skipSeparators();
            return pos < d.length() && number.region(pos, d.length()).lookingAt();
        }

        private void skipSeparators() {
            while (pos < d.length() && (d.charAt(pos) == ',' || Character.isWhitespace(d.charAt(pos)))) {
                pos++;
            }
        }
    }
}
