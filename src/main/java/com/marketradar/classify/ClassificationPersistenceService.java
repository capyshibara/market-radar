package com.marketradar.classify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.Classification;
import com.marketradar.domain.ClassificationAttempt;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClassificationAttemptRepository;
import com.marketradar.repo.ClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Makes active-result promotion and append-only audit recording one atomic commit. */
@Service
public class ClassificationPersistenceService {

    private final ClassificationRepository classifications;
    private final ClassificationAttemptRepository attempts;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClassificationPersistenceService(ClassificationRepository classifications,
                                            ClassificationAttemptRepository attempts) {
        this.classifications = classifications;
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClassificationAttempt.Outcome apply(RawDoc doc, Long expectedActiveId,
                                               String expectedActiveSignature,
                                               Classification candidate,
                                               ClassificationVersioning.CurrentVersion version) {
        Classification active = classifications.findByRawDoc(doc).orElse(null);

        if (!sameActive(expectedActiveId, expectedActiveSignature, active)) {
            saveAttempt(doc, version, active, candidate,
                    ClassificationAttempt.Outcome.CONCURRENT_CHANGE_PRESERVED,
                    "Active classification changed after planning; candidate was not promoted");
            return ClassificationAttempt.Outcome.CONCURRENT_CHANGE_PRESERVED;
        }

        if (active == null) {
            classifications.saveAndFlush(candidate);
            saveAttempt(doc, version, null, candidate,
                    ClassificationAttempt.Outcome.APPLIED_NEW, null);
            return ClassificationAttempt.Outcome.APPLIED_NEW;
        }

        if (!ClassificationVersioning.isPromotableStatus(candidate.getStatus().name())) {
            saveAttempt(doc, version, active, candidate,
                    ClassificationAttempt.Outcome.PRESERVED_PRIOR_REVIEW,
                    "Rerun was not decisive; preserved the prior active result");
            return ClassificationAttempt.Outcome.PRESERVED_PRIOR_REVIEW;
        }

        String priorSnapshot = snapshot(active);
        Long priorId = active.getId();
        active.replaceActiveResult(candidate);
        classifications.saveAndFlush(active);
        attempts.save(new ClassificationAttempt(doc, version.providerModel(),
                version.promptSha256(), version.contentSha256(), version.signature(),
                priorId, priorSnapshot, snapshot(candidate),
                ClassificationAttempt.Outcome.APPLIED_REPLACEMENT, null));
        return ClassificationAttempt.Outcome.APPLIED_REPLACEMENT;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(RawDoc doc, ClassificationVersioning.CurrentVersion version,
                            Classification candidate, String message) {
        Classification active = classifications.findByRawDoc(doc).orElse(null);
        saveAttempt(doc, version, active, candidate,
                ClassificationAttempt.Outcome.ERROR, message);
    }

    private void saveAttempt(RawDoc doc, ClassificationVersioning.CurrentVersion version,
                             Classification prior, Classification candidate,
                             ClassificationAttempt.Outcome outcome, String error) {
        attempts.save(new ClassificationAttempt(doc, version.providerModel(),
                version.promptSha256(), version.contentSha256(), version.signature(),
                prior == null ? null : prior.getId(), snapshot(prior), snapshot(candidate),
                outcome, error));
    }

    private String snapshot(Classification classification) {
        if (classification == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("classificationId", classification.getId());
        value.put("rowVersion", classification.getRowVersion());
        value.put("status", classification.getStatus().name());
        value.put("labels", classification.getLabels().stream()
                .map(Enum::name).sorted().toList());
        value.put("routingStatus", classification.getRoutingStatus().name());
        value.put("departments", classification.getDepartments().stream()
                .map(Enum::name).sorted().toList());
        value.put("votesJson", classification.getVotesJson());
        value.put("note", classification.getNote());
        value.put("providerModel", classification.getLlmProvider());
        value.put("promptSha256", classification.getClassifierPromptSha256());
        value.put("contentSha256", classification.getClassifierContentSha256());
        value.put("versionSignature", classification.getClassifierVersionSignature());
        value.put("createdAt", classification.getCreatedAt() == null
                ? null : classification.getCreatedAt().toString());
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize classification audit snapshot", e);
        }
    }

    private static boolean sameActive(Long expectedId, String expectedSignature,
                                      Classification actual) {
        if (expectedId == null) return actual == null;
        if (actual == null || !expectedId.equals(actual.getId())) return false;
        String actualSignature = actual.getClassifierVersionSignature();
        return expectedSignature == null
                ? actualSignature == null : expectedSignature.equals(actualSignature);
    }
}
