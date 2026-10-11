package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.geom.Path2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SvgPaths#parse}: SVG path data (moveto, lineto and closepath only,
 * absolute and relative, as in {@code analytics/world-map.json}) into a
 * shape filled like a browser fills it. Anything outside that subset must
 * fail loudly instead of being drawn wrong.
 */
class SvgPathsTest {

    // -- absolute and relative commands --

    @Test
    void absoluteCommands_drawTheGivenCorners() {
        Path2D square = SvgPaths.parse("M0,0L4,0L4,4L0,4Z");

        assertThat(square.contains(2, 2)).isTrue();
        assertThat(square.contains(5, 2)).isFalse();
        assertThat(square.contains(-1, 2)).isFalse();
    }

    @Test
    void relativeLineto_stepsFromTheCurrentPoint() {
        Path2D square = SvgPaths.parse("M1,1l2,0l0,2l-2,0Z");

        assertThat(square.contains(2, 2)).isTrue();
        assertThat(square.contains(0.5, 0.5)).isFalse();
        assertThat(square.contains(3.5, 2)).isFalse();
    }

    @Test
    void absoluteAndRelativeLineto_mixInOnePath() {
        Path2D square = SvgPaths.parse("M0,0L4,0l0,4L0,4z");

        assertThat(square.contains(3.9, 3.9)).isTrue();
        assertThat(square.contains(4.1, 3.9)).isFalse();
    }

    @Test
    void relativeMovetoFirst_countsFromTheOrigin() {
        Path2D square = SvgPaths.parse("m10,10l2,0l0,2l-2,0z");

        assertThat(square.contains(11, 11)).isTrue();
        assertThat(square.contains(1, 1)).isFalse();
    }

    @Test
    void negativeNumbersWithoutASeparator_andMinusZero_areRead() {
        Path2D square = SvgPaths.parse("M4,4l-4,0l0,-4l4,-0.0l-0.0,4Z");

        assertThat(square.contains(2, 2)).isTrue();
        assertThat(square.contains(2, 4.5)).isFalse();
    }

    @Test
    void spacesAndCommas_bothSeparateNumbers() {
        Path2D square = SvgPaths.parse(" M 0 0 L 4,0 l 0 4 L0 , 4 Z ");

        assertThat(square.contains(2, 2)).isTrue();
    }

    /** SVG: extra coordinate pairs after a moveto are implicit linetos of the same kind. */
    @Test
    void extraPairsAfterAMoveto_areImplicitLinetos() {
        Path2D absolute = SvgPaths.parse("M0,0 4,0 4,4 0,4Z");
        Path2D relative = SvgPaths.parse("m0,0 4,0 0,4 -4,0z");

        assertThat(absolute.contains(2, 2)).isTrue();
        assertThat(relative.contains(2, 2)).isTrue();
        assertThat(relative.contains(2, 5)).isFalse();
    }

    @Test
    void extraPairsAfterALineto_continueTheLineto() {
        Path2D square = SvgPaths.parse("M0,0l4,0 0,4 -4,0Z");

        assertThat(square.contains(2, 2)).isTrue();
        assertThat(square.contains(2, 5)).isFalse();
    }

    // -- several subpaths --

    @Test
    void severalAbsoluteSubpaths_areAllFilled_andTheSpaceBetweenIsNot() {
        Path2D twoSquares = SvgPaths.parse("M0,0l1,0l0,1l-1,0ZM5,5l1,0l0,1l-1,0Z");

        assertThat(twoSquares.contains(0.5, 0.5)).isTrue();
        assertThat(twoSquares.contains(5.5, 5.5)).isTrue();
        assertThat(twoSquares.contains(3, 3)).isFalse();
    }

    /** SVG: after a closepath the current point is the subpath's start, not its last drawn point. */
    @Test
    void relativeMovetoAfterClosepath_countsFromTheClosedSubpathsStart() {
        Path2D twoSquares = SvgPaths.parse("M10,10l1,0l0,1l-1,0zm5,0l1,0l0,1l-1,0z");

        assertThat(twoSquares.contains(15.5, 10.5)).isTrue();
        assertThat(twoSquares.contains(15.5, 11.5)).isFalse();
    }

    @Test
    void linetoRightAfterClosepath_startsANewSubpathAtTheClosedSubpathsStart() {
        Path2D twoTriangles = SvgPaths.parse("M0,0l4,0l0,4zl-4,0l0,-4z");

        assertThat(twoTriangles.contains(3, 1)).isTrue();
        assertThat(twoTriangles.contains(-3, -1)).isTrue();
        assertThat(twoTriangles.contains(-1, 3)).isFalse();
    }

    // -- fill rule --

    /** SVG's default fill-rule is nonzero: an inner ring drawn the other way round is a hole. */
    @Test
    void innerRingDrawnTheOtherWayRound_isAHole() {
        Path2D frame = SvgPaths.parse("M0,0L10,0L10,10L0,10ZM3,3L3,7L7,7L7,3Z");

        assertThat(frame.contains(1, 1)).isTrue();
        assertThat(frame.contains(5, 5)).isFalse();
    }

    /** Under nonzero (unlike even-odd) an inner ring drawn the same way round is still filled. */
    @Test
    void innerRingDrawnTheSameWayRound_isFilled() {
        Path2D doubled = SvgPaths.parse("M0,0L10,0L10,10L0,10ZM3,3L7,3L7,7L3,7Z");

        assertThat(doubled.contains(5, 5)).isTrue();
    }

    // -- fail loudly on anything outside the supported subset --

    @ParameterizedTest
    @ValueSource(strings = {"C", "c", "Q", "q", "A", "a", "H", "h", "V", "v", "S", "s", "T", "t", "X"})
    void unsupportedCommand_isRejectedNamingIt(String command) {
        assertThatThrownBy(() -> SvgPaths.parse("M0,0" + command + "1,1,2,2,3,3,4,4Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + command + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " , "})
    void emptyOrBlankPath_isRejected(String d) {
        assertThatThrownBy(() -> SvgPaths.parse(d)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPath_isRejected() {
        assertThatThrownBy(() -> SvgPaths.parse(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"L1,1L2,2Z", "l1,1Z", "Z", "z", "1,1L2,2Z"})
    void pathNotStartingWithAMoveto_isRejected(String d) {
        assertThatThrownBy(() -> SvgPaths.parse(d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moveto");
    }

    @ParameterizedTest
    @ValueSource(strings = {"M", "M0", "M0,0l", "M0,0l1", "M0,0l1,1,2Z", "M0,0L1,1Lz", "M0,0ZM"})
    void commandWithoutACompleteCoordinatePair_isRejected(String d) {
        assertThatThrownBy(() -> SvgPaths.parse(d)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numberRightAfterAClosepath_isRejected() {
        assertThatThrownBy(() -> SvgPaths.parse("M0,0l1,0l0,1Z5,5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"M0,0lx,1Z", "M0,0l1e3,1Z", "M0,0l--1,1Z", "M0,0l1.,1Z", "M0,0l1;1Z"})
    void characterOrNumberFormatOutsideTheSubset_isRejected(String d) {
        assertThatThrownBy(() -> SvgPaths.parse(d)).isInstanceOf(IllegalArgumentException.class);
    }
}
