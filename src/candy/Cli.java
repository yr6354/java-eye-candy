package candy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A 60-line command line parser, so that every program in this repo can be run
 * with plain {@code java}, without pulling in picocli or anything else.
 *
 * 极简命令行参数解析：支持 --key=value / --key value / -k value / 以及裸开关 --flag。
 */
public final class Cli {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final String[] raw;

    private Cli(String[] raw) {
        this.raw = raw;
        for (int i = 0; i < raw.length; i++) {
            String a = raw[i];
            if (!a.startsWith("-")) {
                continue;
            }
            String body = a.replaceFirst("^--?", "");
            int eq = body.indexOf('=');
            if (eq >= 0) {
                values.put(body.substring(0, eq).toLowerCase(), body.substring(eq + 1));
            } else if (i + 1 < raw.length && isValue(raw[i + 1])) {
                values.put(body.toLowerCase(), raw[++i]);
            } else {
                values.put(body.toLowerCase(), "true");
            }
        }
    }

    public static Cli parse(String[] args) {
        return new Cli(args);
    }

    /** A bare "-9" or "-.5" is a number, not another option. */
    private static boolean isValue(String token) {
        return !token.startsWith("-") || token.matches("-\\d.*") || token.matches("-\\.\\d.*");
    }

    public boolean has(String key) {
        return values.containsKey(key.toLowerCase());
    }

    public String s(String key, String def) {
        return values.getOrDefault(key.toLowerCase(), def);
    }

    public int i(String key, int def) {
        String v = values.get(key.toLowerCase());
        return v == null ? def : Integer.parseInt(v.trim());
    }

    public double d(String key, double def) {
        String v = values.get(key.toLowerCase());
        return v == null ? def : Double.parseDouble(v.trim());
    }

    public boolean flag(String key) {
        String v = values.get(key.toLowerCase());
        return v != null && !v.equalsIgnoreCase("false") && !v.equals("0");
    }

    /** Print the help text and exit when --help / -? was given. */
    public Cli help(String text) {
        if (flag("help") || flag("?")) {
            System.out.println(text.strip());
            System.exit(0);
        }
        return this;
    }

    public String[] raw() {
        return raw;
    }
}
