package demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class Inventory implements Serializable {

    private static final long serialVersionUID = 1L;
    private static int counter = 0;

    private final List<String> items = new ArrayList<>();
    private int total;
    protected int visible;
    private int shared; // accessed as other.shared below: must stay

    public record Point(int x, int y) {
        public Point {
            if (x < 0) {
                throw new IllegalArgumentException("negative x");
            }
        }

        int sum() {
            int acc = x + y;
            return acc;
        }
    }

    enum Kind {
        SMALL {
            @Override
            int weight() {
                int base = 1;
                return base;
            }
        },
        LARGE;

        int weight() {
            return 10;
        }
    }

    static class Helper {
        int bump() {
            return Inventory.counter++ + nextId();
        }
    }

    public void add(String item) {
        String normalized = normalize(item);
        items.add(normalized);
        this.total += normalized.length();
    }

    // private + public overload on the same name: must not be renamed
    private int size(String s) {
        return s.length();
    }

    public int size() {
        return items.size();
    }

    private static int nextId() {
        return ++counter;
    }

    private String normalize(String item) {
        String result = item.trim();
        for (int i = 0, n = 2; i < n; i++) {
            result = result.toLowerCase();
        }
        return result;
    }

    public int copySharedFrom(Inventory other) {
        this.shared = other.shared + 1;
        return shared;
    }

    public String describe(Object o) {
        if (o instanceof String text && !text.isEmpty()) {
            return "string:" + text;
        }
        int total = 0; // shadows the field
        for (String item : items) {
            total += item.length();
        }
        int localTotal = total;
        switch (localTotal % 3) {
        case 0:
            int tmp = 1;
            localTotal += tmp;
            break;
        case 1:
            tmp = 2;
            localTotal += tmp;
            break;
        default:
            break;
        }
        return "items:" + this.total + "/" + total + "/" + localTotal;
    }

    public List<String> transform() {
        Function<String, String> f = this::decorate;
        Supplier<Integer> s = Inventory::nextId;
        List<String> out = new ArrayList<>();
        Runnable r = new Runnable() {
            private int calls;

            @Override
            public void run() {
                calls++;
                for (String item : items) {
                    out.add(f.apply(item) + calls);
                }
            }
        };
        r.run();
        items.forEach(item -> out.add(decorate(item)));
        out.add("id" + (s.get() > 0));
        return out;
    }

    private String decorate(String value) {
        return "[" + value + "]";
    }

    public int weights() {
        return Kind.SMALL.weight() + Kind.LARGE.weight() + new Helper().bump() * 0 + new Point(1, 2).sum();
    }

    public void explode(int depth) {
        recurse(depth);
    }

    private void recurse(int remaining) {
        if (remaining == 0) {
            items.forEach(item -> failWith(item));
        }
        recurse(remaining - 1);
    }

    private void failWith(String item) {
        int badIndex = item.length() + 100;
        items.get(badIndex);
    }

    public static String run() {
        Inventory inv = new Inventory();
        inv.add("  Apple ");
        inv.add("Pear");
        Inventory other = new Inventory();
        StringBuilder sb = new StringBuilder();
        sb.append(inv.size()).append(',');
        sb.append(inv.describe(42)).append(',');
        sb.append(inv.describe("hi")).append(',');
        sb.append(inv.transform()).append(',');
        sb.append(inv.weights()).append(',');
        sb.append(inv.copySharedFrom(other)).append(',');
        sb.append(inv.visible);
        return sb.toString();
    }
}
