package ee.forestiq.owner;

/**
 * Default owner statuses. The system supports dynamic statuses stored in
 * the {@code owner_status_definition} table, so this enum is only used
 * for seed data and as a convenience reference.
 */
public final class OwnerStatus {
    private OwnerStatus() { }

    public static final String NEW = "NEW";
    public static final String CONTACTED = "CONTACTED";
    public static final String INTERESTED = "INTERESTED";
    public static final String NOT_INTERESTED = "NOT_INTERESTED";
    public static final String EVALUATION = "EVALUATION";
    public static final String CONTRACTED = "CONTRACTED";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String WAITS_FOR_EVALUATION = "WAITS_FOR_EVALUATION";
    public static final String DECEASED = "DECEASED";
    public static final String VALUED = "VALUED";
    public static final String NEGOTIATION = "NEGOTIATION";
}
