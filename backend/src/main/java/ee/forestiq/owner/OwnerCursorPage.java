package ee.forestiq.owner;

import java.util.List;

import ee.forestiq.owner.OwnerDtos.OwnerListItem;

/** Cursor-based owner list response that avoids synchronous exact totals. */
public record OwnerCursorPage(
    List<OwnerListItem> content,
    int size,
    boolean hasNext,
    String nextCursor
) { }
