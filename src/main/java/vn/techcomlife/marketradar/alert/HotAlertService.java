package vn.techcomlife.marketradar.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.techcomlife.marketradar.domain.AlertLog;
import vn.techcomlife.marketradar.domain.EvidenceFact;
import vn.techcomlife.marketradar.domain.InterpretedClaim;
import vn.techcomlife.marketradar.repo.AlertLogRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Hot Alert (Batch 5, bước 8): tin tier T3-T4 đã *_APPROVED → Slack incoming webhook
 * (đúng 1 POST JSON {"text": ...} — spec handoff). Thiết kế:
 *
 *  1. GATE GIỮ NGUYÊN: chỉ AlertRules.shouldAlert (tier đủ + status *_APPROVED)
 *     mới bắn — "sốt dẻo" không đi tắt qua vòng đời duyệt.
 *  2. KHÔNG PHÁ TRANSACTION DUYỆT: payload dựng NGAY (trong transaction, khi
 *     entity còn attach); HTTP gửi SAU COMMIT (TransactionSynchronization) —
 *     duyệt fail thì không bắn nhầm, alert fail thì duyệt vẫn xong.
 *  3. IDEMPOTENT: mỗi claimCode chỉ SENT một lần (alert_log check).
 *  4. STUB MODE: không có SLACK_WEBHOOK_URL → không gọi mạng, ghi alert_log
 *     channel=STUB — demo offline vẫn xem được nội dung alert tại /alerts.
 *  5. Mọi lần cân nhắc đều thành record alert_log (audit, kể cả FAILED/SKIPPED).
 */
@Service
public class HotAlertService {

    private static final Logger log = LoggerFactory.getLogger(HotAlertService.class);

    private final AlertLogRepository alertLogs;
    private final boolean enabled;
    private final String webhookUrl;   // rỗng = stub
    private final String minTier;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public HotAlertService(AlertLogRepository alertLogs,
                           @Value("${marketradar.alert.enabled:true}") boolean enabled,
                           @Value("${marketradar.alert.webhook-url:}") String webhookUrl,
                           @Value("${marketradar.alert.min-tier:T3}") String minTier,
                           @Value("${marketradar.alert.base-url:http://localhost:8080}") String baseUrl) {
        this.alertLogs = alertLogs;
        this.enabled = enabled;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        this.minTier = minTier;
        this.baseUrl = baseUrl;

        String urlErr = AlertRules.validateWebhookUrl(this.webhookUrl);
        if (urlErr != null) throw new IllegalStateException("Config alert sai: " + urlErr);
        if (this.webhookUrl.isEmpty()) {
            log.warn("╔══════════════════════════════════════════════════════════╗");
            log.warn("║ HOT ALERT MODE: STUB — không có SLACK_WEBHOOK_URL.        ║");
            log.warn("║ Alert chỉ ghi vào /alerts, KHÔNG gửi Slack thật.          ║");
            log.warn("║ Set env SLACK_WEBHOOK_URL để bắn webhook thật.            ║");
            log.warn("╚══════════════════════════════════════════════════════════╝");
        } else {
            log.info("HOT ALERT MODE: SLACK webhook (min-tier={})", minTier);
        }
    }

    /**
     * Gọi từ VerificationJob (AUTO_APPROVED) và ReviewController (approve/edit/force).
     * An toàn gọi vô điều kiện — tự quyết có bắn hay không. KHÔNG BAO GIỜ throw.
     *
     * @param cited fact được claim trích dẫn (đã resolve sẵn trong transaction caller)
     */
    public void maybeAlert(InterpretedClaim c, List<EvidenceFact> cited, String trigger) {
        try {
            if (!enabled) return;
            if (!AlertRules.shouldAlert(c.getRiskTier(), c.getReviewStatus().name(), minTier))
                return;

            if (alertLogs.existsByClaimCodeAndStatus(c.getClaimCode(), AlertLog.Status.SENT)) {
                alertLogs.save(new AlertLog(c.getClaimCode(), c.getRiskTier(), trigger,
                        channel(), AlertLog.Status.SKIPPED_DUPLICATE, "(đã alert trước đó)",
                        null, "Idempotency: claim này đã SENT — không bắn lại."));
                return;
            }

            // Dựng payload NGAY — entity còn trong session, lazy field đọc được
            String payload = buildPayload(c, cited);
            String claimCode = c.getClaimCode();
            String tier = c.getRiskTier();

            Runnable send = () -> dispatch(claimCode, tier, trigger, payload);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override public void afterCommit() { send.run(); }
                        });
            } else {
                send.run();
            }
        } catch (Exception e) {
            // Alert là tính năng phụ trợ — không được phép phá hành động duyệt
            log.error("Hot alert lỗi (đã nuốt để không phá luồng duyệt): {}", e.toString());
        }
    }

    /** Gửi tin test để smoke-test webhook mà không cần claim thật. */
    public String sendTest() {
        String payload = AlertRules.buildAlertText("C-TEST", minTier, "APPROVED",
                "Đây là alert thử nghiệm từ Market Radar (POST /alerts/test).",
                "Fact demo [F-000]", "Nguồn demo (tier 1)", baseUrl + "/report/weekly");
        dispatch("C-TEST", minTier, "TEST", payload);
        return "Đã dispatch alert test (channel=" + channel() + ") — xem kết quả tại /alerts";
    }

    // ---------- internal ----------

    private String buildPayload(InterpretedClaim c, List<EvidenceFact> cited) {
        String factLine = null, sourceLine = null;
        if (cited != null && !cited.isEmpty()) {
            EvidenceFact f = cited.get(0);
            String sum = f.getSummaryVi() != null ? f.getSummaryVi() : f.getSpanText();
            factLine = sum + " [" + f.getFactCode() + "]"
                    + (cited.size() > 1 ? " (+" + (cited.size() - 1) + " fact khác)" : "");
        }
        if (c.getRawDoc() != null && c.getRawDoc().getSource() != null) {
            var s = c.getRawDoc().getSource();
            sourceLine = s.getName() + " (tier " + s.getTier() + ") — " + c.getRawDoc().getUrl();
        }
        return AlertRules.buildAlertText(c.getClaimCode(), c.getRiskTier(),
                c.getReviewStatus().name(), c.getTextVi(), factLine, sourceLine,
                baseUrl + "/report/weekly");
    }

    private void dispatch(String claimCode, String tier, String trigger, String payload) {
        if (webhookUrl.isEmpty()) {
            log.info("HOT ALERT (STUB) {}:\n{}", claimCode, payload);
            alertLogs.save(new AlertLog(claimCode, tier, trigger, AlertLog.Channel.STUB,
                    AlertLog.Status.SENT, payload, null,
                    "Stub mode — không có SLACK_WEBHOOK_URL, chỉ log."));
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"text\":\"" + AlertRules.jsonEscape(payload) + "\"}"))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            alertLogs.save(new AlertLog(claimCode, tier, trigger, AlertLog.Channel.SLACK,
                    ok ? AlertLog.Status.SENT : AlertLog.Status.FAILED, payload,
                    res.statusCode(), ok ? null : "Webhook trả " + res.statusCode()
                            + ": " + truncate(res.body())));
            log.info("HOT ALERT {} → Slack HTTP {}", claimCode, res.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            alertLogs.save(new AlertLog(claimCode, tier, trigger, AlertLog.Channel.SLACK,
                    AlertLog.Status.FAILED, payload, null, "Lỗi gửi: " + truncate(e.toString())));
            log.error("HOT ALERT {} gửi Slack lỗi: {}", claimCode, e.toString());
        }
    }

    private AlertLog.Channel channel() {
        return webhookUrl.isEmpty() ? AlertLog.Channel.STUB : AlertLog.Channel.SLACK;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 900 ? s.substring(0, 900) + "…" : s;
    }
}
