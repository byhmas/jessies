package e.ptextarea;

import e.gui.*;
import java.awt.event.*;
import java.util.*;
import java.util.regex.*;

/**
 * Styles any chunk of text matching a regular expression. Capturing group 1 is
 * used as the range to be styled.
 */
public class RegularExpressionStyleApplicator implements StyleApplicator {
    private static final EnumSet<PStyle> SOURCE_STYLES = EnumSet.of(PStyle.NORMAL);
    private PTextArea textArea;
    private Pattern pattern;
    private PStyle style;
    
    public RegularExpressionStyleApplicator(PTextArea textArea, String regularExpression, PStyle style) {
        this.textArea = textArea;
        this.pattern = Pattern.compile(regularExpression);
        this.style = style;
    }
    
    public List<PTextSegment> applyStylingTo(String line, PTextSegment segment) {
        ArrayList<PTextSegment> result = new ArrayList<PTextSegment>();
        Matcher matcher = pattern.matcher(segment.getCharSequence());
        int normalStart = 0;
        int offset = segment.getOffset();
        while (matcher.find()) {
            if (isAcceptableMatch(line, matcher)) {
                final int matchStart = matcher.start(1);
                final int matchEnd = matcher.end(1);
                if (matchStart > normalStart) {
                    result.add((PTextSegment) segment.subSegment(normalStart, matchStart));
                }
                // FIXME: underlining should be a PStyle attribute.
                PTextSegment newSegment;
                if (style != PStyle.HYPERLINK) {
                    newSegment = new PTextSegment(textArea, offset + matchStart, offset + matchEnd, style);
                } else {
                    newSegment = new PUnderlinedTextSegment(textArea, offset + matchStart, offset + matchEnd, style);
                    configureSegment(newSegment, matcher);
                }
                result.add(newSegment);
                normalStart = matchEnd;
            }
        }
        if (segment.getText().length() > normalStart) {
            result.add((PTextSegment) segment.subSegment(normalStart));
        }
        return result;
    }
    
    /**
     * Override this if you need to make a test that you can't express in the
     * regular expression. The keyword styler, for example, uses a regular
     * expression that matches any word and then checks whether the word is
     * a keyword here. That works around Java 1.5 performance problems with
     * regular expressions that are long sequences like "a|b|...|y|z".
     */
    public boolean isAcceptableMatch(CharSequence line, Matcher matcher) {
        return true;
    }
    
    public EnumSet<PStyle> getSourceStyles() {
        return SOURCE_STYLES;
    }
    
    /**
     * Override this to configure each matching text segment.
     */
    protected void configureSegment(PTextSegment segment, Matcher matcher) {
    }
}
