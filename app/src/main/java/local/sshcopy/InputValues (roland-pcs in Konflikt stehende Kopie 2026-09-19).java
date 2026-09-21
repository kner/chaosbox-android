package local.sshcopy;
final class InputValues {
    static int amount(String text) {
        String value = text.trim();
        int number = value.isEmpty() ? 0 : Integer.parseInt(value);
        if (number < 0) throw new NumberFormatException("Anzahl darf nicht negativ sein.");
        return number;
    }
}
