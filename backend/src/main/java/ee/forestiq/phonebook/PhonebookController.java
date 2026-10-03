package ee.forestiq.phonebook;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.owner.OwnerDtos.CreatePersonsDumpEntry;
import ee.forestiq.owner.OwnerDtos.PersonsDumpEntry;
import ee.forestiq.owner.Pagination;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/phonebook")
@Tag(name = "Phonebook", description = "Contact book / persons dump")
public class PhonebookController {

    private final PhonebookRepository phonebookRepository;
    private final OrganizationContext orgCtx;

    public PhonebookController(PhonebookRepository phonebookRepository, OrganizationContext orgCtx) {
        this.phonebookRepository = phonebookRepository;
        this.orgCtx = orgCtx;
    }

    @GetMapping
    @Operation(summary = "Search contacts")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public List<PersonsDumpEntry> search(
        @RequestParam(defaultValue = "") String name,
        @RequestParam(defaultValue = "") String phone,
        @RequestParam(defaultValue = "") String code,
        @RequestParam(defaultValue = "100") int limit) {
        UUID orgId = orgCtx.requiredOrganizationId();
        int safeLimit = Pagination.normalize(0, limit).size();
        return phonebookRepository.search(orgId, name, phone, code, safeLimit);
    }

    @PostMapping
    @Operation(summary = "Add contact")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@RequestBody CreatePersonsDumpEntry entry) {
        UUID orgId = orgCtx.requiredOrganizationId();
        phonebookRepository.create(orgId, entry);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete contact")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        phonebookRepository.delete(orgCtx.requiredOrganizationId(), id);
    }
}
