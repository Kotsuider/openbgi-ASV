/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import java.util.Arrays;

public final class FontArgumentsTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    private static void invalid(int index, String value) {
        try { FontArguments.validate(index, value); throw new AssertionError(value); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        String initial = "--productname=ItsukaTodokuAnoSorani --font-file=\"repair/My Font.ttf\" --font-size=24 --font-width=0 --font-weight=400 --letter-spacing=2";
        String[] values = FontArguments.read(initial);
        check(Arrays.equals(values, new String[]{"24", "0", "2", "400"}));
        String updated = FontArguments.apply(initial, new String[]{"30", "0", "-2", "700"});
        check(updated.contains("--productname=ItsukaTodokuAnoSorani") && updated.contains("--font-file=\"repair/My Font.ttf\""));
        check(Arrays.equals(FontArguments.read(updated), new String[]{"30", "0", "-2", "700"}));
        check(!updated.contains("--font-size=24") && !updated.contains("--letter-spacing=2"));
        check(FontArguments.apply("--font-size=10 --font-size=12 --enable=C0:18", new String[]{"24", "", "", ""}).trim().equals("--enable=C0:18 --font-size=24"));
        String unknown = "--font-face=\"Family --font-size=80\" --unknown=\"spaces and \\paths\"";
        check(FontArguments.apply(unknown, new String[]{"", "", "", ""}).equals(unknown));
        check(FontArguments.apply("--font-size=24", new String[]{"", "", "", ""}).isEmpty());
        check(FontArguments.validate(0, " +024 ").equals("24"));
        for(int i=0;i<4;i++) { check(FontArguments.validate(i, "").isEmpty()); invalid(i, "999999999999999999"); invalid(i,"1.5"); invalid(i,"abc"); }
        invalid(0,"3"); invalid(0,"201"); invalid(1,"-1"); invalid(1,"201"); invalid(2,"-17"); invalid(2,"65"); invalid(3,"0"); invalid(3,"1001");
        check(FontArguments.validate(2,"-16").equals("-16") && FontArguments.validate(2,"64").equals("64"));
        try { FontArguments.read("--font-face=\"missing"); throw new AssertionError(); } catch(IllegalArgumentException expected) { }
        System.out.println("FontArguments: all checks passed");
    }
}
