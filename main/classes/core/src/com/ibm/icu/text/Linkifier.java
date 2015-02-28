/*
 *******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation and
 * others. All Rights Reserved.
 *******************************************************************************
 */
package com.ibm.icu.text;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.text.ParsePosition;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.ibm.icu.text.IDNA.Info;

/**
 * @author markdavis
 * @internal
 */
@Deprecated
public class Linkifier {
    public static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

    static final List<String> SCHEMES = Arrays.asList("http://", "https://");

    static final UnicodeSet SKIPPED_ASCII = new UnicodeSet("[\\ \"#%<>\\[-\\^`\\{-\\}]").freeze();
    static final UnicodeSet URL_CODE_POINT = new UnicodeSet("[^[:NChar:][:Cc:][:Cs:]]").removeAll(SKIPPED_ASCII);
    // see https://url.spec.whatwg.org/#url-code-points

    static final UnicodeSet INCLUSIONS = new UnicodeSet("[, ; \\: ! ¡ ¿ . · ' \" @ * \\\\ \\& \\u200C \\u200D]").freeze();
//    static {
//        System.out.println(URL_CODE_POINT);
//        System.out.println(INCLUSIONS);
//    }
    static final UnicodeSet DOTS = new UnicodeSet("[.．。｡]").freeze();
    static final UnicodeSet ALWAYS_BAD = new UnicodeSet("[\\p{Cn}\\p{Cs}\\p{Cc}\\p{Deprecated}\\p{bidi_control}-[\\u061C\\u200E\\u200F]]").freeze();
    static final UnicodeSet NORMAL = new UnicodeSet("[\\p{L}\\p{N}\\p{M}\\p{S}\\p{Pd}\\p{Pc}%]").removeAll(ALWAYS_BAD).freeze();
    static final UnicodeSet OK_ESCAPED = new UnicodeSet("[\\p{di}\\p{Cf}\\p{Co}]").removeAll(ALWAYS_BAD).freeze();

    private static String[] GROUPS = {"scheme", "domain", "path", "query", "fragment"};
    private static String[] REGEX_SOURCE = {
        "$URL =        (:<scheme> $scheme ://)? (:<domain> $domain) (:<path> / $path)? (:<query> [?] $query)? (:<fragment> \\x{23} $fragment)? ;",
        "$scheme =     [-+.a-zA-Z0-9]+ ;",
        "$domain =     $label (?:$IDNSep $label)* $IDNSep? ;",
        "$label =      ($percentEncodedUtf8Char"
                + "|[^\\p{C}\\p{Z}\\x{0}-\\x{7F}][-0-9a-zA-Z \\u00AD\\u200B-\\u200D\\u2060\\u2064\\uFEFF\\x{1BCA0}-\\x{0001BCA3}])+ ;",
                "$IDNSep =     [.．。｡] ;",
                "$path =       (?: $percentEncodedUtf8Char | $char | /)* ;",
                "$query =      (?: $percentEncodedUtf8Char | $char | [/?])* ;",
                "$fragment =   (?: $percentEncodedUtf8Char | $char | [/?\\x{23}])* ;",
                "$percentEncodedUtf8Char = ( %\\p{XDigit}\\p{XDigit} )+ ;",
                "$char =       [\\p{L}\\p{N}\\p{M}\\p{S}\\p{Pd}\\p{Pc}$inclusionChar&[^$exclusionChar]] ;", // [^\\p{C}\\p{Z}] ;", // 
                "$inclusionChar = [, ; \\: ! ¡ ¿ . · ' \" @ * \\\\ \\& % \\u200C \\u200D];",
                "$exclusionChar = [<>] ;",
                //        "$hash =    \\u0023 ;",
                //        "$question = \\u003F ;",
                //        "$semi = \\u003B ;",
    };

    public enum Status {
        WELL_FORMED,
        ILL_FORMED,
        NOT_FOUND}

    private static abstract class Scanner {
        /**
         * Scans the input for a valid token. returns x > start if there is a token extends to x. Otherwise -1
         * @param input
         * @param index
         * @return
         */
        public abstract Status scan(String input, ParsePosition pos);
    }

    static class MatchEnd {
        private Collection<String> source;
        MatchEnd(Collection<String> source) {
            this.source = source;
        }
        int matches(String s, int limit) {
            for (String item : source) {
                if (s.regionMatches(limit-item.length(), item, 0, item.length())) {
                    return item.length();
                }
            }
            return -1;
        }
    }

    static class MatchStart {
        private List<String> source;
        MatchStart(List<String> source) {
            this.source = source;
        }
        int matches(String s, int start) {
            for (String item : source) {
                if (s.regionMatches(start, item, 0, item.length())) {
                    return item.length();
                }
            }
            return -1;
        }
    }

    public static class UrlScanner extends Scanner {
        private static final char FRAGMENT_START = '#';
        private static final char QUERY_START = '?';
        private static final char PATH_START = '/';
        Scanner domainScanner = new DomainScanner();
        Scanner pathScanner = new SimpleScanner(new UnicodeSet(NORMAL).add(PATH_START), OK_ESCAPED, INCLUSIONS);
        Scanner queryScanner = new SimpleScanner(new UnicodeSet(NORMAL).add(PATH_START).add(QUERY_START), OK_ESCAPED, INCLUSIONS);
        Scanner fragmentScanner = new SimpleScanner(new UnicodeSet(NORMAL).add(PATH_START).add(QUERY_START).add(FRAGMENT_START), OK_ESCAPED, INCLUSIONS);
        MatchEnd tldMatcher;
        MatchStart schemeMatcher = new MatchStart(SCHEMES);

        public UrlScanner(Collection<String> tlds) {
            tldMatcher = new MatchEnd(tlds);
        }

        public Status scan(String input, ParsePosition pos) {
            int start = pos.getIndex();
            Status overallResult; // any invalid result will taint
            int schemeMatch = schemeMatcher.matches(input, start);
            if (schemeMatch > 0) {
                // we have a scheme, look for a domain
                pos.setIndex(start+schemeMatch);
                overallResult = domainScanner.scan(input, pos);
                if (overallResult == Status.NOT_FOUND) {
                    return overallResult;
                }
            } else { // no scheme. Look for domain from the beginning, but has to end with known TLD
                overallResult = domainScanner.scan(input, pos);
                if (overallResult == Status.NOT_FOUND) {
                    return overallResult;
                }
                if (tldMatcher.matches(input, pos.getIndex()) <= 0) {
                    return overallResult;
                }
            }
            // from this point on, we'll return a link no matter what, but it may be marked INVALID
            int cp;
            int current = pos.getIndex();
            cp = current < input.length() ? input.codePointAt(current) : 0;
            if (cp == PATH_START) {
                overallResult = pathScanner.scan(input, pos);
                int newPos = pos.getIndex();
                if (newPos != current) {
                    current = newPos;
                    cp = current < input.length() ? input.codePointAt(current) : 0;
                }
            }
            if (cp == QUERY_START) {
                overallResult = queryScanner.scan(input, pos);
                int newPos = pos.getIndex();
                if (newPos != current) {
                    current = newPos;
                    cp = current < input.length() ? input.codePointAt(current) : 0;
                }
            }
            if (cp == FRAGMENT_START) {
                overallResult = fragmentScanner.scan(input, pos);
                int newPos = pos.getIndex();
                if (newPos != current) {
                    current = newPos;
                    cp = current < input.length() ? input.codePointAt(current) : 0;
                }
            }
            return overallResult;
        }
    }

    private static class SimpleScanner extends Scanner {
        private UnicodeSet ok;
        private UnicodeSet softBreak;
        private UnicodeSet okEscaped;
        public SimpleScanner(UnicodeSet ok, UnicodeSet okEscaped, UnicodeSet softBreak) {
            checkOverlap("ALWAYS_BAD.containsSome(ok)", ALWAYS_BAD, ok);
            checkOverlap("ALWAYS_BAD.containsSome(okEscaped)", ALWAYS_BAD, okEscaped);
            checkOverlap("ALWAYS_BAD.containsSome(softBreak)", ALWAYS_BAD, softBreak);
            checkOverlap("softBreak.containsSome(ok)", softBreak, ok);
            this.ok = ok.freeze();
            this.okEscaped = okEscaped.freeze();
            this.softBreak = softBreak.freeze();
        }
        public void checkOverlap(String title, UnicodeSet a, UnicodeSet b) {
            if (a.containsSome(b)) {
                throw new IllegalArgumentException(title + ":\t" + new UnicodeSet(a).retainAll(b));
            }
        }
        
        public Status scan(String input, ParsePosition pos) {
            int start = pos.getIndex();
            int current = start;
            Status result = Status.WELL_FORMED;
            for (int i = start; i < input.length();) {
                int cp = input.codePointAt(i);
                if (cp == '%') {
                    pos.setIndex(i);
                    String s = getPercentEscaped(input, pos, UTF8_CHARSET);
                    if (ALWAYS_BAD.containsSome(s)) {
                        result = Status.ILL_FORMED;
                    }
                    i = pos.getIndex();
                } else if (ok.contains(cp) || softBreak.contains(cp)) {
                    i += Character.charCount(cp);
                } else {
                    break;
                }
                current = i; // how far we get
            }
            pos.setIndex(current);
            return start == current ? Status.NOT_FOUND : result;
        }

        @Override
        public String toString() {
            return new UnicodeSet(ok).complement().complement() + "/" + new UnicodeSet(softBreak).complement().complement();
        }
    }

    static class DomainScanner extends SimpleScanner {
        private IDNA idna = IDNA.getUTS46Instance(0);
        Info info = new Info();
        StringBuilder dest = new StringBuilder();
        DomainScanner() {
            super(NORMAL, OK_ESCAPED, DOTS);
        }
        @Override
        public Status scan(String input, ParsePosition pos) {
            // scan, then do syntax check
            int start = pos.getIndex();
            Status result = super.scan(input, pos);
            if (result == Status.NOT_FOUND) {
                return result;
            }
            StringBuilder unicode = idna.nameToUnicode(input.substring(start, pos.getIndex()), dest, info);
            return info.hasErrors() 
                    ? Status.ILL_FORMED
                            : Status.WELL_FORMED;
        }
    }


    //    public static void main(String[] args) {
    //        String[] tests = {
    //                "google.com",
    //                "google\uE0000.com",
    //                "google%F3%A0%80%80.com",
    //                "xn--a.com",
    //                "google.com./",
    //                "google.com./abc/def",
    //                "αβγ｡com/δεζ?η=θ?&ι=κ#λμ_?#ξ",
    //                "http://google.com/",
    //                "αβγ@δεζ.com",
    //                "mailto:αβγ@δεζ.com",
    //                "file:///c:/WINDOWS/clock.avi",
    //                "http://be.wikipedia.org/wiki/Вікіпедыя:Артыкулы,_якія_мусяць_быць_у_кожнай_Вікіпедыі",
    //                "http://bpy.wikipedia.org/wiki/উইকিপিডিয়া:থানা_থকিসে_নিবন্ধ",
    //                "http://bug.wikipedia.org/wiki/Wikipedia:Daftar_artikel_ᨆᨊᨛᨂ_ᨅᨔ_ᨄᨑᨛᨒᨘ_ᨂᨛᨀ",
    //                "http://fa.wikipedia.org/wiki/ویکی‌پدیا:فهرست_نوشتارهایی_که_هر_ویکی‌پدیا_باید_بدارد",
    //                "http://gu.wikipedia.org/wiki/વિકિપીડિયા:દરેક_ભાષાના_વિકિપીડિયામાં_હોય_એવા_પ્રારંભિક_લેખોની_યાદી",
    //                "http://hi.wikipedia.org/wiki/विकिपीडिया:कुछ_प्रारंभिक_लेख_जो_कि_हर_भाषा_के_विकिपीडिया_में_होने_चाहिए",
    //                "http://kn.wikipedia.org/wiki/ವಿಕಿಪೀಡಿಯ:ಅಗತ್ಯ_ಲೇಖನಗಳು",
    //                "http://ml.wikipedia.org/wiki/വിക്കിപീഡിയ:അവശ്യലേഖനങ്ങള്‍",
    //                "http://mr.wikipedia.org/wiki/विकिपीडिया:लेख_संपादन_स्पर्धा/लेखांची_यादी",
    //                "http://zh-min-nan.wikipedia.org/wiki/Wikipedia:Só͘-ū_ê_Wikipedia_pán-pún_lóng_èng-kai_ū_ê_bûn-chiuⁿ",
    //                "http://new.wikipedia.org/wiki/विकिपिडियाःहलिमसफूया_ख्यःत",
    //                "http://os.wikipedia.org/wiki/Википеди:Алы_æвзагыл_дæр_чи_хъуамæ_уа,_уыцы_статьятæ",
    //                "http://ru.wikipedia.org/wiki/Википедия:Список_статей,_которые_должны_быть_во_всех_языковых_версиях",
    //                "http://ta.wikipedia.org/wiki/Wikipedia:அனைத்து_மொழி_விக்கிபீடியாக்களிலும்_இருக்க_வேண்டிய_கட்டுரைகளின்_பட்டியல்",
    //                "http://te.wikipedia.org/wiki/Wikipedia:వికీపీడియాలో_తప్పకుండా_ఉండవలసిన_వ్యాసాలు",
    //                "http://th.wikipedia.org/wiki/วิกิพีเดีย:รายชื่อบทความที่วิกิพีเดียทุกภาษาควรมี",
    //                "http://uk.wikipedia.org/wiki/Вікіпедія:Статті,_які_повинні_бути_у_всіх_вікіпедіях",
    //                "http://yi.wikipedia.org/wiki/װיקיפּעדיע:וויכטיגע_ארטיקלן"
    //        };
    //        UrlScanner linkifier = new UrlScanner();
    //        for (String test : tests) {
    //            String source = "(" + test + ")";
    //            System.out.println(source);
    //            ParsePosition pos = new ParsePosition(0);
    //            for (int start = 0; start < source.length(); ++start) {
    //                pos.setIndex(start);
    //                Status result = linkifier.scan(source, pos);
    //                int end = pos.getIndex();
    //                if (result != Status.VALID) {
    //                    continue;
    //                }
    //                final int fromEnd = source.length()-end;
    //                System.out.println(end + "\t" + start + "\t" + fromEnd
    //                        + "\t«" + source.substring(0,start)
    //                        + "❴❴❴" + source.substring(start, end)
    //                        + "❵❵❵" + source.substring(end, source.length())
    //                        + "»");
    //                start = end - 1;
    //            }
    //        }
    //    }

    //    private String check(String groupName, String group) {
    //            switch (groupName) {
    //            case "domain": 
    //                Info info = new Info();
    //                StringBuilder result = idna.nameToUnicode(group, new StringBuilder(), info);
    //                if (info.hasErrors()) {
    //                    result.append("\tErrors: ").append(info.getErrors().toString());
    //                }
    //                return result.toString();
    //            }
    //            return "";
    //    }

    /**
     * Return unescaped string, and resets the position.
     * It eats as many escaped bytes (% Hex Hex) as possible, and returns the unescaped string. If the string
     * is invalid UTF-8, that can be checked by looking for FFFF.
     * If there is no valid escaped byte at the start, will return the first character (%).
     * <br>Examples:
     * <br>"%61x" => 3, "a"
     * <br>"%61%x" => 4, "a%"
     * <br>"%x" => 1, "%"
     * @param input
     * @param pos ignore the errorindex
     * @return
     */
    public static String getPercentEscaped(String input, ParsePosition pos, Charset charset) {
        int state = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int start = pos.getIndex();
        int end = start;
        int current = 0;
        int length = input.length();
        boolean extra = false;
        int i;
        main:
        for (i = start; i < length; ++i) {
            char c = input.charAt(i);
            switch (state) {
            case 0: 
                end = i;
                if (c != '%') {
                    break main;
                }
                extra = true;
                current = 0;
                state = 2;
                continue;
            case 1: case 2:
                if (c < '0' || c > 'f' || c > '9' && c < 'A' || c > 'F' && c < 'a') {
                    break main;
                }
                current *= 16;
                current += (c < 'A') ? c - '0' : (c & 0xF) + 9;
                --state;
                if (state == 0) {
                    bytes.write(current);
                    end = i+1;
                    extra = false;
                }
                continue;
            }
        }
        String result = new String(bytes.toByteArray(), charset);
        if (extra) {
            result += "%";
            end += 1;
        }
        pos.setIndex(end);
        return result;
    }
}