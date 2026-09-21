package local.sshcopy;
public class InputValuesTest {
    public static void main(String[] args) {
        for (String value : new String[]{"", "  ", "0"}) {
            if (InputValues.amount(value) != 0) throw new AssertionError(value);
        }
        if (InputValues.amount(" 42 ") != 42
                || InputValues.amount("2147483647") != Integer.MAX_VALUE) throw new AssertionError();
        for (String value : new String[]{"-1", "-3", "-2147483648", "1.5", "1,5", "abc", "2147483648", "-2147483649", "1 2"}) {
            try { InputValues.amount(value); throw new AssertionError(value); }
            catch (NumberFormatException expected) { }
        }
        System.out.println("PASS: empty/default, nonnegative integers, rejected negatives, bounds, invalid input and overflow");
    }
}
