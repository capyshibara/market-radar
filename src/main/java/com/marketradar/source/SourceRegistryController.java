package com.marketradar.source;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** JSON endpoints used by the Source Registry's Add source demonstration. */
@RestController
@RequestMapping(path = "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
public class SourceRegistryController {

    private final SourceRegistryService service;

    public SourceRegistryController(SourceRegistryService service) {
        this.service = service;
    }

    @PostMapping(path = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SourceRegistryService.TestResult test(@RequestBody TestRequest request) {
        return service.test(request.fetchUrl(), request.type());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> save(@RequestBody SaveRequest request) {
        try {
            SourceRegistryService.SaveResult result = service.save(new SourceRegistryService.SaveCommand(
                    request.code(), request.name(), request.fetchUrl(), request.type(), request.tier(),
                    request.language(), request.active(), request.testPassed()));
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (SourceRegistryRules.ValidationException invalid) {
            return ResponseEntity.badRequest().body(error(invalid.getMessage()));
        } catch (SourceRegistryService.DuplicateSourceException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(duplicate.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("success", false, "message", message);
    }

    public record TestRequest(String fetchUrl, String type) {}

    public record SaveRequest(String code, String name, String fetchUrl, String type,
                              int tier, String language, boolean active, boolean testPassed) {}
}
