/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import java.util.ArrayList;
import java.util.List;

/** Font controls edit the existing command line, preserving every unrelated token. */
final class FontArguments {
    static final String[] KEYS = {"--font-size=", "--font-width=", "--letter-spacing=", "--font-weight="};
    static final int[] MIN = {4, 0, -16, 1}, MAX = {200, 200, 64, 1000};
    private static final class Token {
        final int start, end;
        final String value;
        Token(int start, int end, String value) { this.start = start; this.end = end; this.value = value; }
    }
    private static List<Token> tokens(String args) {
        List<Token> result = new ArrayList<>();
        int pos = 0;
        while (pos < args.length()) {
            while (pos < args.length() && (args.charAt(pos) == ' ' || args.charAt(pos) == '\t')) pos++;
            int start = pos;
            boolean quoted = false;
            StringBuilder value = new StringBuilder();
            while (pos < args.length() && (quoted || (args.charAt(pos) != ' ' && args.charAt(pos) != '\t'))) {
                char ch = args.charAt(pos++);
                if (ch == '"') quoted = !quoted;
                else value.append(ch);
            }
            if (quoted) throw new IllegalArgumentException("Unclosed quote");
            if (pos > start) result.add(new Token(start, pos, value.toString()));
        }
        return result;
    }
    static String[] read(String args) {
        String[] values = {"", "", "", ""};
        for (Token token : tokens(args))
            for (int i = 0; i < KEYS.length; i++)
                if (token.value.startsWith(KEYS[i])) values[i] = token.value.substring(KEYS[i].length());
        return values;
    }
    static String validate(int index, String value) {
        value = value.trim();
        if (value.isEmpty()) return "";
        if (!value.matches("[+-]?[0-9]+")) throw new IllegalArgumentException("Integer required");
        int number = Integer.parseInt(value);
        if (number < MIN[index] || number > MAX[index]) throw new IllegalArgumentException("Out of range");
        return Integer.toString(number);
    }
    static String apply(String args, String[] values) {
        if (values.length != KEYS.length) throw new IllegalArgumentException("Wrong number of settings");
        String[] checked = new String[values.length];
        for (int i = 0; i < values.length; i++) checked[i] = validate(i, values[i]);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        for (Token token : tokens(args)) {
            boolean font = false;
            for (String key : KEYS) font |= token.value.startsWith(key);
            if (font) { result.append(args, copied, token.start); copied = token.end; }
        }
        result.append(args, copied, args.length());
        String base = result.toString().trim();
        result = new StringBuilder(base);
        for (int i = 0; i < checked.length; i++) if (!checked[i].isEmpty()) {
            if (result.length() > 0) result.append(' ');
            result.append(KEYS[i]).append(checked[i]);
        }
        return result.toString();
    }
}
