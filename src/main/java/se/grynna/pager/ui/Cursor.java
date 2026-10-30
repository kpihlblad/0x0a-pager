package se.grynna.pager.ui;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class Cursor implements Comparable<Cursor> {

    public static final int MAXLINE_LENGTH = 64 * 1024;
    public int line;
    private int col;
    public int intendedCol;

    Cursor(int line, int intendedCol) {
        this.line = line;
        this.col = intendedCol;
        this.intendedCol = intendedCol;
    }

    public Cursor(Cursor cursor) {
        this.line = cursor.line;
        this.col = cursor.col;
        this.intendedCol = cursor.col;
    }

    public void intendedCol(int intendedCol) {
        this.intendedCol = intendedCol;
        this.col = intendedCol;
    }

    @Override
    public int compareTo(Cursor o) {
        // line, then col
        var res = Integer.compare(line, o.line);
        if (res == 0) {
            return Integer.compare(col, o.col);
        }
        return res;
    }

    @Override
    public String toString() {
        return "(" + line + "," + col + (intendedCol != col ? ":" + intendedCol : "") + ")";
    }

    public void deltaLine(int deltaLines) {
        // Saturate instead of wrapping around on int overflow
        long moved = (long) this.line + deltaLines;
        this.line = (int) Math.clamp(moved, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public void deltaCol(int deltaCols) {

        if (deltaCols == 0) return;

        this.col += deltaCols;

        // clam to 0..MAXLINE
        if (col < 0) {
            col = 0;
        }
        if (deltaCols > 0 && col > MAXLINE_LENGTH) {
            col = MAXLINE_LENGTH;
        }

        // reset when scrolling left/right
        log.debug("col={}, deltaCols={}, intended {} will reset", col, deltaCols, intendedCol);
        this.intendedCol = col;
    }

    public int col() {
        return col;
    }

    public void clampLine(long maxLine, long minLine) {
        if (line > maxLine) {
            line = (int) maxLine;
        }
        if (line < minLine) {
            line = (int) minLine;
        }
    }

    public void clampCol(int maxCol) {
        col = Math.min(intendedCol, maxCol);
    }
}
