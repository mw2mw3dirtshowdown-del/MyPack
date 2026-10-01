package com.operator.mypack.gui;

/**
 * Page arithmetic for paginated inventories. Kept free of Bukkit types so it can be unit tested.
 *
 * @param index     zero based page that is shown (already clamped into range)
 * @param pageCount number of pages, at least 1
 * @param from      index of the first entry on the page (inclusive)
 * @param to        index after the last entry on the page (exclusive)
 */
public record Paging(int index, int pageCount, int from, int to) {

    /**
     * @param total     number of entries
     * @param perPage   entries per page (at least 1)
     * @param requested page that was asked for; values outside the valid range are clamped
     */
    public static Paging of(int total, int perPage, int requested) {
        int size = Math.max(1, perPage);
        int count = Math.max(1, (int) Math.ceil(Math.max(0, total) / (double) size));
        int index = Math.max(0, Math.min(requested, count - 1));
        int from = Math.min(Math.max(0, total), index * size);
        int to = Math.min(Math.max(0, total), from + size);
        return new Paging(index, count, from, to);
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return index < pageCount - 1;
    }
}
