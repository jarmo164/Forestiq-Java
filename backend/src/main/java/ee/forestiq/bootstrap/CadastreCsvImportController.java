package ee.forestiq.bootstrap;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/admin/imports/cadastre")
@PreAuthorize("hasRole('ORG_ADMIN')")
@ConditionalOnProperty(name = "forestiq.bootstrap.legacy-public-datasets-enabled", havingValue = "true")
public class CadastreCsvImportController {
    private final CadastreCsvImportService service;
    private final OrganizationContext organizationContext;

    public CadastreCsvImportController(CadastreCsvImportService service,
                                       OrganizationContext organizationContext) {
        this.service = service;
        this.organizationContext = organizationContext;
    }

    @PostMapping(consumes = {"text/csv", "application/csv", "application/octet-stream"})
    public CadastreCsvImportService.ImportResult importCsv(
        HttpServletRequest request,
        @RequestHeader(value = "X-ForestIQ-Filename", required = false) String encodedFilename) {
        try {
            String filename = encodedFilename == null ? null
                : URLDecoder.decode(encodedFilename, StandardCharsets.UTF_8);
            return service.importCsv(
                organizationContext.requiredOrganizationId(),
                request.getInputStream(),
                filename,
                request.getContentLengthLong());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impordifaili nimi ei ole korrektne.", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impordifaili voogu ei õnnestunud avada.", exception);
        }
    }
}
