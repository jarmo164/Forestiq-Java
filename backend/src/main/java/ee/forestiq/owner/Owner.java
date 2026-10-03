package ee.forestiq.owner;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "owner")
public class Owner {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "personal_or_registry_code", nullable = false)
    private String personalOrRegistryCode;

    private String email;
    private String phone;

    @Column(nullable = false)
    private String status;

    @Column(name = "assigned_to_subject")
    private String assignedToSubject;

    @Column(name = "owner_type")
    private String ownerType;

    private String address;
    private String info;

    @Column(name = "status_set_at")
    private Instant statusSetAt;

    @Column(name = "last_cadastre_list_refresh")
    private Long lastCadastreListRefresh;

    @Column(name = "out_of_admin_search_from")
    private Instant outOfAdminSearchFrom;

    @Column(name = "out_of_admin_search_to")
    private Instant outOfAdminSearchTo;

    @Column(name = "out_of_admin_search_reason")
    private String outOfAdminSearchReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long version;

    protected Owner() { }

    public Owner(UUID id, UUID organizationId, String displayName, String personalOrRegistryCode,
                 String email, String phone, String status, String assignedToSubject) {
        this.id = id;
        this.organizationId = organizationId;
        this.displayName = displayName;
        this.personalOrRegistryCode = personalOrRegistryCode;
        this.email = email;
        this.phone = phone;
        this.status = status;
        this.assignedToSubject = assignedToSubject;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // --- Getters ---
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getDisplayName() { return displayName; }
    public String getPersonalOrRegistryCode() { return personalOrRegistryCode; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getStatus() { return status; }
    public String getAssignedToSubject() { return assignedToSubject; }
    public String getOwnerType() { return ownerType; }
    public String getAddress() { return address; }
    public String getInfo() { return info; }
    public Instant getStatusSetAt() { return statusSetAt; }
    public Long getLastCadastreListRefresh() { return lastCadastreListRefresh; }
    public Instant getOutOfAdminSearchFrom() { return outOfAdminSearchFrom; }
    public Instant getOutOfAdminSearchTo() { return outOfAdminSearchTo; }
    public String getOutOfAdminSearchReason() { return outOfAdminSearchReason; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    // --- Setters for mutable fields ---
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public void setAddress(String address) { this.address = address; }
    public void setInfo(String info) { this.info = info; }
    public void setAssignedToSubject(String assignedToSubject) { this.assignedToSubject = assignedToSubject; }

    public void changeStatus(String newStatus) {
        this.status = newStatus;
        this.statusSetAt = Instant.now();
    }

    public void setOutOfAdminSearch(Instant from, Instant to, String reason) {
        this.outOfAdminSearchFrom = from;
        this.outOfAdminSearchTo = to;
        this.outOfAdminSearchReason = reason;
    }

    public void setLastCadastreListRefresh(Long timestamp) {
        this.lastCadastreListRefresh = timestamp;
    }

    /** Package-private hooks used by the jOOQ repository to keep this domain object in sync. */
    void restorePersistenceState(Instant statusSetAt, Long lastCadastreListRefresh,
                                 Instant outOfAdminSearchFrom, Instant outOfAdminSearchTo,
                                 String outOfAdminSearchReason, Instant createdAt,
                                 Instant updatedAt, Long version) {
        this.statusSetAt = statusSetAt;
        this.lastCadastreListRefresh = lastCadastreListRefresh;
        this.outOfAdminSearchFrom = outOfAdminSearchFrom;
        this.outOfAdminSearchTo = outOfAdminSearchTo;
        this.outOfAdminSearchReason = outOfAdminSearchReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    void markInserted(UUID id, Instant now, long version) {
        this.id = id;
        this.createdAt = now;
        this.updatedAt = now;
        this.version = version;
    }

    void markUpdated(Instant updatedAt, long version) {
        this.updatedAt = updatedAt;
        this.version = version;
    }
}
