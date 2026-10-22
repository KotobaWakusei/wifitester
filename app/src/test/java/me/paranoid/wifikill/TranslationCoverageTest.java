package me.paranoid.wifikill;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Guards the translation catalogue: every string that the Chinese resources
 * declare must have an English counterpart, otherwise the English build would
 * silently fall back to Chinese.
 */
public class TranslationCoverageTest {

    private static final String BASE = "src/main/res/";

    @Test public void englishCatalogueCoversEveryChineseString() throws Exception {
        List<String> chinese = stringNames(BASE + "values/strings.xml");
        List<String> english = stringNames(BASE + "values-en/strings.xml");
        assertFalse("no Chinese strings were parsed", chinese.isEmpty());
        assertFalse("no English strings were parsed", english.isEmpty());

        List<String> missing = new ArrayList<>();
        for (String name : chinese) {
            if (!english.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue("missing English translations: " + missing, missing.isEmpty());
    }

    @Test public void englishCatalogueHasNoOrphanEntries() throws Exception {
        List<String> chinese = stringNames(BASE + "values/strings.xml");
        List<String> english = stringNames(BASE + "values-en/strings.xml");
        List<String> orphan = new ArrayList<>();
        for (String name : english) {
            if (!chinese.contains(name)) {
                orphan.add(name);
            }
        }
        assertTrue("English strings without a Chinese counterpart: " + orphan,
                orphan.isEmpty());
    }

    @Test public void formatPlaceholdersMatchBetweenCatalogues() throws Exception {
        List<String> chinese = stringNames(BASE + "values/strings.xml");
        for (String name : chinese) {
            String zh = rawString(BASE + "values/strings.xml", name);
            String en = rawString(BASE + "values-en/strings.xml", name);
            assertNotNull("missing Chinese string " + name, zh);
            assertNotNull("missing English string " + name, en);
            assertEquals("placeholder mismatch in " + name,
                    placeholders(zh), placeholders(en));
        }
    }

    /** Collects the sorted set of placeholder indices used by a string. */
    private static List<String> placeholders(String value) {
        List<String> result = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("%(\\d+\\$)?[sd]").matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static List<String> stringNames(String path) throws Exception {
        Document document = parse(path);
        NodeList nodes = document.getElementsByTagName("string");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            names.add(((Element) nodes.item(i)).getAttribute("name"));
        }
        return names;
    }

    private static String rawString(String path, String name) throws Exception {
        Document document = parse(path);
        NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (name.equals(element.getAttribute("name"))) {
                return element.getTextContent();
            }
        }
        return null;
    }

    private static Document parse(String path) throws Exception {
        File file = new File(path);
        assertTrue("missing resource file: " + path + " (cwd=" + new File(".").getAbsolutePath() + ")",
                file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }
}
