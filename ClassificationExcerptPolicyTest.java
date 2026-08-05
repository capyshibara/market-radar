import com.marketradar.classify.TopicClassifier;

/** Regression control: long PDFs are not classified from cover pages alone. */
public class ClassificationExcerptPolicyTest {
    public static void main(String[] args) {
        String source = "A".repeat(8500) + "MIDDLE_SIGNAL" + "B".repeat(6500)
                + "TAIL_SIGNAL" + "C".repeat(3000);
        String excerpt = TopicClassifier.representativeText(source, 12000);
        assert excerpt.contains("[ĐẦU TÀI LIỆU]");
        assert excerpt.contains("[GIỮA TÀI LIỆU]");
        assert excerpt.contains("[CUỐI TÀI LIỆU]");
        assert excerpt.contains("MIDDLE_SIGNAL");
        assert excerpt.contains("TAIL_SIGNAL");
        assert excerpt.length() <= 12100;
        assert TopicClassifier.representativeText("short", 12000).equals("short");
        System.out.println("ClassificationExcerptPolicyTest: ALL PASS");
    }
}
