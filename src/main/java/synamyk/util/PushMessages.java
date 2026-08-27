package synamyk.util;

/**
 * Bilingual copy for triggered (non-broadcast) push notifications.
 */
public final class PushMessages {

    private PushMessages() {}

    public record Text(String titleRu, String bodyRu, String titleKy, String bodyKy) {

        public String title(String lang) {
            return "KY".equalsIgnoreCase(lang) && notBlank(titleKy) ? titleKy : titleRu;
        }

        public String body(String lang) {
            return "KY".equalsIgnoreCase(lang) && notBlank(bodyKy) ? bodyKy : bodyRu;
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /** AI error analysis finished for a completed sub-test. */
    public static Text errorAnalysisReady(String subTestRu, String subTestKy) {
        return new Text(
                "Разбор ошибок готов",
                "ИИ проанализировал твои ответы: " + subTestRu,
                "Каталардын талдоосу даяр",
                "ЖИ жоопторуңду талдады: " + coalesce(subTestKy, subTestRu));
    }

    /** A new sub-test was added to a test the user owns. */
    public static Text newSubTest(String testRu, String testKy) {
        return new Text(
                "Новый подтест",
                "В тесте «" + testRu + "» появился новый подтест",
                "Жаңы бөлүм",
                "«" + coalesce(testKy, testRu) + "» тестине жаңы бөлүм кошулду");
    }

    /** Nudge for users who haven't practiced recently. */
    public static Text inactiveReminder() {
        return new Text(
                "Давно не тренировались!",
                "Вернитесь и продолжите проходить тесты",
                "Көптөн бери машыккан жоксуз!",
                "Кайрылып, тесттерди улантыңыз");
    }

    private static String coalesce(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
