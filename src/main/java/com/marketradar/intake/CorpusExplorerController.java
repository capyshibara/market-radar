package com.marketradar.intake;

import com.marketradar.extract.ExtractionBackfillService;
import com.marketradar.extract.FactExtractionJob;
import com.marketradar.pipeline.ClassificationJob;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** Admin corpus browser. Role visibility is demo-scoped; production needs server auth. */
@Controller
public class CorpusExplorerController {
    private final CorpusExplorerService corpus;
    private final ClassificationJob classificationJob;
    private final FactExtractionJob extractionJob;
    private final ExtractionBackfillService extractionBackfill;

    public CorpusExplorerController(CorpusExplorerService corpus,
                                    ClassificationJob classificationJob,
                                    FactExtractionJob extractionJob,
                                    ExtractionBackfillService extractionBackfill) {
        this.corpus = corpus;
        this.classificationJob = classificationJob;
        this.extractionJob = extractionJob;
        this.extractionBackfill = extractionBackfill;
    }

    @GetMapping("/corpus")
    public String list(Model model) {
        CorpusExplorerService.Snapshot snapshot = corpus.snapshot();
        model.addAttribute("summary", snapshot.summary());
        model.addAttribute("documents", snapshot.documents());
        return "corpus";
    }

    @GetMapping("/corpus/{id}")
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("item", corpus.detail(id));
        return "corpus-detail";
    }

    @GetMapping(value = "/corpus.csv", produces = "text/csv")
    public ResponseEntity<String> csv() {
        StringBuilder csv = new StringBuilder("document_id,title,publisher,intake,language,characters,state,facts,claims,verified,url\n");
        for (CorpusExplorerService.DocumentRow doc : corpus.snapshot().documents()) {
            csv.append(doc.id()).append(',').append(cell(doc.title())).append(',')
                    .append(cell(doc.publisher())).append(',').append(doc.intakeMethod()).append(',')
                    .append(doc.language()).append(',').append(doc.textCharacters()).append(',')
                    .append(doc.state()).append(',').append(doc.factCount()).append(',')
                    .append(doc.claimCount()).append(',').append(doc.verifiedClaimCount()).append(',')
                    .append(cell(doc.url())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=market-radar-corpus.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString());
    }

    @PostMapping("/corpus/{id}/classify")
    public String classify(@PathVariable long id, RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("corpusSuccess", classificationJob.retryOne(id));
        } catch (RuntimeException error) {
            redirect.addFlashAttribute("corpusError", error.getMessage());
        }
        return "redirect:/corpus/" + id;
    }

    @PostMapping("/corpus/{id}/extract")
    public String extract(@PathVariable long id, RedirectAttributes redirect) {
        try {
            var selection = extractionBackfill.selectTargets(List.of(id));
            if (!selection.acceptedIds().contains(id)) {
                redirect.addFlashAttribute("corpusError", String.join("; ", selection.rejected()));
            } else {
                redirect.addFlashAttribute("corpusSuccess", extractionJob.runTargeted(List.of(id)));
            }
        } catch (RuntimeException error) {
            redirect.addFlashAttribute("corpusError", error.getMessage());
        }
        return "redirect:/corpus/" + id;
    }

    private static String cell(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }
}
