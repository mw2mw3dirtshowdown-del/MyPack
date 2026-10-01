package com.operator.mypack.model.geo;

/** The six faces of a cube, named like the keys of a Java block model {@code faces} object. */
public enum Face {
    NORTH, EAST, SOUTH, WEST, UP, DOWN;

    /** Key used in model JSON, e.g. {@code north}. */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
