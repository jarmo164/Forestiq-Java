package ee.forestiq.owner;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared paging contract for SQL-backed public list endpoints. */
public final class Pagination {
    public static final int MAX_SIZE = 100;

    private Pagination() { }

    public static PageRequest normalize(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and " + MAX_SIZE);
        }
        long offset = (long) page * (long) size;
        if (offset > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
        }
        return new PageRequest(page, size, (int) offset);
    }

    public static int totalPages(long totalElements, int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and " + MAX_SIZE);
        }
        return (int) Math.ceil((double) totalElements / size);
    }

    public record PageRequest(int page, int size, int offset) { }
}
