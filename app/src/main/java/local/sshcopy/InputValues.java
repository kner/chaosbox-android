package local.sshcopy;
final class InputValues {
    static int amount(String text) {
        String value = text.trim();
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }
}
