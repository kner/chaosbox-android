package local.sshcopy;

public final class CategoryValuesTest {
    private static void check(String json, String expected) throws Exception {
        String actual = CategoryValues.read(json);
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }

    public static void main(String[] args) throws Exception {
        check("{\"Kategorie\":\"Elektronik>Stromversorgung>Netzteile\"}", "Elektronik");
        check("{\"Kategorie\":[\"Elektronik>Stromversorgung\",\"Elektronik>Sensoren\",\"Werkzeug>Bohrer\"]}", "Elektronik, Werkzeug");
        check("{\"category\":\"Elektronik>A\",\"Kategorie\":\"Werkzeug>B\",\"kategorie\":\"Elektronik>C\"}", "Elektronik, Werkzeug");
        check("{\"Kategorie\":\"Elektronik>A\",\"Kategorie\":\"Werkzeug>B\",\"Kategorie\":\"Elektronik>C\"}", "Elektronik, Werkzeug");
        check("{\"category\":\" Elektronik > A ; Werkzeug>B | Elektronik>C\\nMechanik>D\"}", "Elektronik, Werkzeug, Mechanik");
        check("{\"category\":\"Haus und Garten>Bewässerung\"}", "Haus und Garten");
        check("{\"category\":null,\"Kategorie\":[\"\",null,\" >leer\"]}", "");
        check("{\"comment\":\"Kategorie: kein Kategorie-Feld\",\"other\":{\"category\":\"ignorieren\"}}", "");
        check("[{\"Anzahl\":1,\"Kategorie\":\"Audio > Kopfhoerer > Bluetooth-In-Ear\",\"kommentar\":\"Kopfhörer\"},{\"Anzahl\":1,\"Kategorie\":\"Elektronik > Stromversorgung > Powerbank\",\"kommentar\":\"Powerbank\"}]", "Audio, Elektronik");
        check("[{\"Kategorie\":\"Elektronik>A\"},{\"Kategorie\":\"Elektronik>B\"},{\"Kategorie\":\"Audio>C\"}]", "Elektronik, Audio");
        check("[]", "");
        check("{}", "");
        System.out.println("PASS: category roots, lists, repeated keys, separators, duplicates and empty values");
    }
}
