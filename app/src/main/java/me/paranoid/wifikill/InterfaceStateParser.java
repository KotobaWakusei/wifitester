package me.paranoid.wifikill;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the administrative UP flag from output of `ip -o link show dev <name>`. */
final class InterfaceStateParser {
    private static final Pattern FLAGS = Pattern.compile("<([^>]*)>");

    private InterfaceStateParser() {
    }

    /**
     * @param output command output
     * @return "up" or "down"; null when no interface flags can be parsed
     */
    static String parseAdministrativeState(String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = FLAGS.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        for (String flag : matcher.group(1).split(",")) {
            if ("UP".equals(flag.trim())) {
                return "up";
            }
        }
        return "down";
    }
}
