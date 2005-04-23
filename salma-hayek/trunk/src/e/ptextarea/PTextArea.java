package e.ptextarea;


import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import java.util.List;
import e.util.*;

/**
 * A PTextArea is a replacement for JTextArea.
 * 
 * @author Phil Norman
 */

public class PTextArea extends JComponent implements PLineListener, Scrollable {
    private static final int MIN_WIDTH = 50;
    private static final int MAX_CACHED_CHAR = 128;
    
    public static final int NO_MARGIN = -1;
    
    private static final Color MARGIN_BOUNDARY_COLOR = new Color(0.6f, 0.6f, 0.6f);
    private static final Color MARGIN_OUTSIDE_COLOR = new Color(0.96f, 0.96f, 0.96f);
    
    private static final Color FOCUSED_SELECTION_COLOR = new Color(0.70f, 0.83f, 1.00f, 0.5f);
    private static final Color FOCUSED_SELECTION_BOUNDARY_COLOR = new Color(0.5f, 0.55f, 0.7f, 0.75f);
    private static final Color UNFOCUSED_SELECTION_COLOR = new Color(0.83f, 0.83f, 0.83f, 0.5f);
    
    private SelectionHighlight selection;
    private boolean selectionEndIsAnchor;  // Otherwise, selection start is anchor.
    
    private PLineList lines;
    private List splitLines;  // TODO - Write a split buffer-style List implementation.
    
    // We cache the FontMetrics for readability rather than performance.
    private FontMetrics metrics;
    private int[] widthCache;
    
    private PAnchorSet anchorSet = new PAnchorSet();
    private ArrayList highlights = new ArrayList();
    private PTextStyler textStyler = new PPlainTextStyler(this);
    private int rightHandMarginColumn = NO_MARGIN;
    private ArrayList caretListeners = new ArrayList();
    
    private int rowCount;
    private int columnCount;
    
    private boolean wordWrap;
    
    private PIndenter indenter;
    private PTextAreaSpellingChecker spellingChecker;
    
    public PTextArea() {
        this(0, 0);
    }
    
    public PTextArea(int rowCount, int columnCount) {
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.selection = new SelectionHighlight(this, 0, 0);
        this.wordWrap = false;
        this.indenter = new PIndenter(this);
        setFont(UIManager.getFont("TextArea.font"));
        setAutoscrolls(true);
        setBackground(Color.WHITE);
        setText(new PTextBuffer());
        PMouseHandler mouseHandler = new PMouseHandler(this);
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addKeyListener(new PKeyHandler(this));
        addComponentListener(new ComponentAdapter() {
            private int lastWidth;
            
            public void componentResized(ComponentEvent event) {
                rewrap();
            }
            
            private void rewrap() {
                if (getWidth() != lastWidth) {
                    lastWidth = getWidth();
                    revalidateLineWrappings();
                    repaint();
                }
            }
        });
        setOpaque(true);
        setFocusTraversalKeysEnabled(false);
        requestFocus();
        initFocusListening();
        initKeyBindings();
        initSpellingChecking();
        addCaretListener(new PMatchingBracketHighlighter(this));
    }
    
    private void initKeyBindings() {
        initKeyBinding(PActionFactory.makeCopyAction());
        initKeyBinding(PActionFactory.makeCutAction());
        initKeyBinding(PActionFactory.makePasteAction());
        initKeyBinding(PActionFactory.makeRedoAction());
        initKeyBinding(PActionFactory.makeSelectAllAction());
        initKeyBinding(PActionFactory.makeUndoAction());
    }
    
    private void initKeyBinding(Action action) {
        String name = (String) action.getValue(Action.NAME);
        KeyStroke keyStroke = (KeyStroke) action.getValue(Action.ACCELERATOR_KEY);
        getActionMap().put(name, action);
        getInputMap().put(keyStroke, name);
    }
    
    public void addCaretListener(PCaretListener caretListener) {
        caretListeners.add(caretListener);
    }
    
    public void removeCaretListener(PCaretListener caretListener) {
        caretListeners.remove(caretListener);
    }
    
    private void fireCaretChangedEvent() {
        for (int i = 0; i < caretListeners.size(); i++) {
            ((PCaretListener) caretListeners.get(i)).caretMoved(this, getSelectionStart(), getSelectionEnd());
        }
    }
    
    public PTextStyler getTextStyler() {
        return textStyler;
    }
    
    // Selection methods.
    public String getSelectedText() {
        int start = selection.getStartIndex();
        int end = selection.getEndIndex();
        if (start == end) {
            return "";
        } else {
            return getTextBuffer().subSequence(start, end).toString();
        }
    }
    
    public int getSelectionStart() {
        return selection.getStartIndex();
    }
    
    public int getSelectionEnd() {
        return selection.getEndIndex();
    }
    
    public void setCaretPosition(int offset) {
        select(offset, offset);
    }
    
    public int getUnanchoredSelectionExtreme() {
        return selectionEndIsAnchor ? getSelectionStart() : getSelectionEnd();
    }
    
    public void changeUnanchoredSelectionExtreme(int newPosition) {
        int anchorPosition = selectionEndIsAnchor ? getSelectionEnd() : getSelectionStart();
        int minPosition = Math.min(newPosition, anchorPosition);
        int maxPosition = Math.max(newPosition, anchorPosition);
        boolean endIsAnchor = (maxPosition == anchorPosition);
        setSelection(minPosition, maxPosition, endIsAnchor);
    }
    
    public void select(int start, int end) {
        setSelection(start, end, false);
    }
    
    public void setSelection(int start, int end, boolean selectionEndIsAnchor) {
        setSelectionWithoutScrolling(start, end, selectionEndIsAnchor);
        ensureVisibilityOfOffset(getUnanchoredSelectionExtreme());
    }
    
    public void setSelectionWithoutScrolling(int start, int end, boolean selectionEndIsAnchor) {
        this.selectionEndIsAnchor = selectionEndIsAnchor;
        SelectionHighlight oldSelection = selection;
        repaintCaret();
        selection = new SelectionHighlight(this, start, end);
        repaintCaret();
        if (oldSelection.isEmpty() != selection.isEmpty()) {
            repaintAnchorRegion(selection.isEmpty() ? oldSelection : selection);
        } else if (oldSelection.isEmpty() == false && selection.isEmpty() == false) {
            int minStart = Math.min(oldSelection.getStartIndex(), selection.getStartIndex());
            int maxStart = Math.max(oldSelection.getStartIndex(), selection.getStartIndex());
            if (minStart != maxStart) {
                repaintLines(getCoordinates(minStart).getLineIndex(), getCoordinates(maxStart).getLineIndex() + 1);
            }
            int minEnd = Math.min(oldSelection.getEndIndex(), selection.getEndIndex());
            int maxEnd = Math.max(oldSelection.getEndIndex(), selection.getEndIndex());
            if (minEnd != maxEnd) {
                repaintLines(getCoordinates(minEnd).getLineIndex() - 1, getCoordinates(maxEnd).getLineIndex());
            }
        }
        fireCaretChangedEvent();
    }
    
    public void centerOffsetInDisplay(int offset) {
        if (isShowing() == false) {
            // Avoid problems if splitLines == null.
            return;
        }
        
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (viewport == null) {
            return;
        }
        
        Point point = getViewCoordinates(getCoordinates(offset));
        final int height = viewport.getExtentSize().height;
        int y = point.y - height/2;
        y = Math.max(0, y);
        y = Math.min(y, getHeight() - height);
        viewport.setViewPosition(new Point(0, y));
    }
    
    public void ensureVisibilityOfOffset(int offset) {
        if (isShowing() == false) {
            // Avoid problems if splitLines == null.
            return;
        }
        
        Point point = getViewCoordinates(getCoordinates(offset));
        scrollRectToVisible(new Rectangle(point.x - 1, point.y - metrics.getMaxAscent(), 3, metrics.getHeight()));
    }
    
    public void insertTab() {
        replaceSelection(getIndentationString());
    }
    
    public void insertNewline() {
        new PNewlineInserter(this).insertNewline();
    }
    
    public void autoIndent() {
        getIndenter().correctIndentation(false);
    }
    
    /**
     * Returns the indenter responsible for auto-indent (and other aspects of
     * indentation correction) in this text area.
     */
    public PIndenter getIndenter() {
        return indenter;
    }
    
    /**
     * Sets the indenter responsible for this text area. Typically useful when
     * you know more about the language of the content than PTextArea does.
     */
    public void setIndenter(PIndenter newIndenter) {
        this.indenter = newIndenter;
    }
    
    /**
     * Returns the text of the given line (without the newline).
     */
    public String getLineText(int lineNumber) {
        int start = getLineStartOffset(lineNumber);
        int end = getLineEndOffsetBeforeTerminator(lineNumber);
        return (start == end) ? "" : getTextBuffer().subSequence(start, end).toString();
    }
    
    /**
     * Returns the string to use as a single indent level in this text area.
     */
    public String getIndentationString() {
        String result = (String) getTextBuffer().getProperty(PTextBuffer.INDENTATION_PROPERTY);
        if (result == null) {
            result = "\t";
        }
        return result;
    }
    
    public void insert(CharSequence chars) {
        SelectionSetter endCaret = new SelectionSetter(getSelectionStart() + chars.length());
        int length = getSelectionEnd() - getSelectionStart();
        getTextBuffer().replace(new SelectionSetter(), getSelectionStart(), length, chars, endCaret);
    }
    
    public void replaceRange(CharSequence replacement, int start, int end) {
        SelectionSetter endCaret = new SelectionSetter(start + replacement.length());
        getTextBuffer().replace(new SelectionSetter(), start, end - start, replacement, endCaret);
    }
    
    public void replaceSelection(CharSequence replacement) {
        if (hasSelection()) {
            replaceRange(replacement, getSelectionStart(), getSelectionEnd());
        } else {
            insert(replacement);
        }
    }
    
    public void delete(int startFrom, int charCount) {
        SelectionSetter endCaret = new SelectionSetter(startFrom);
        getTextBuffer().replace(new SelectionSetter(), startFrom, charCount, "", endCaret);
    }
    
    private class SelectionSetter implements PTextBuffer.SelectionSetter {
        private int start;
        private int end;
        
        public SelectionSetter() {
            this(getSelectionStart(), getSelectionEnd());
        }
        
        public SelectionSetter(int offset) {
            this(offset, offset);
        }
        
        public SelectionSetter(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        public void modifySelection() {
            select(start, end);
        }
    }
    
    public boolean hasSelection() {
        return (getSelectionStart() != getSelectionEnd());
    }
    
    public void selectAll() {
        select(0, getTextBuffer().length());
    }
    
    /**
     * Repaints us when we gain/lose focus, so we can re-color the selection,
     * like a native text component.
     */
    private void initFocusListening() {
        addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
                repaint();
            }
            
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }
    
    private void initSpellingChecking() {
        spellingChecker = new PTextAreaSpellingChecker(this);
        spellingChecker.checkSpelling();
    }
    
    public PTextAreaSpellingChecker getSpellingChecker() {
        return spellingChecker;
    }
    
    // Utility methods.
    public int getLineCount() {
        return lines.size();
    }
    
    /**
     * Returns a CharSequence providing access to all the characters in the given line up to but not
     * including the line terminator.
     */
    public CharSequence getLineContents(int line) {
        return lines.getLine(line).getContents();
    }
    
    public int getLineStartOffset(int line) {
        return lines.getLine(line).getStart();
    }
    
    /**
     * Returns the offset at the end of the given line, but before the
     * newline. This differs from JTextArea's getLineEndOffset, where the
     * line end offset is taken to include the newline.
     */
    public int getLineEndOffsetBeforeTerminator(int line) {
        return lines.getLine(line).getEndOffsetBeforeTerminator();
    }
    
    public int getLineOfOffset(int offset) {
        return lines.getLineIndex(offset);
    }
    
    public void setWrapStyleWord(boolean newWordWrapState) {
        if (wordWrap != newWordWrapState) {
            wordWrap = newWordWrapState;
            revalidateLineWrappings();
        }
    }
    
    /**
     * Sets the column number at which to draw the margin. Typically, this is
     * 80 when using a fixed-width font. Use the constant NO_MARGIN to suppress
     * the drawing of any margin.
     */
    public void showRightHandMarginAt(int rightHandMarginColumn) {
        this.rightHandMarginColumn = rightHandMarginColumn;
    }
    
    public void setTextStyler(PTextStyler textStyler) {
        this.textStyler = textStyler;
        repaint();
    }
    
    public void setFont(Font font) {
        super.setFont(font);
        cacheFontMetrics();
        showRightHandMarginAt(GuiUtilities.isFontFixedWidth(font) ? 80 : NO_MARGIN);
        revalidateLineWrappings();
        repaint();
    }
    
    private void cacheFontMetrics() {
        metrics = getFontMetrics(getFont());
    }
    
    public void addHighlight(PHighlight highlight) {
        highlights.add(highlight);
        repaintAnchorRegion(highlight);
    }
    
    public void removeHighlight(PHighlight highlight) {
        highlights.remove(highlight);
        repaintAnchorRegion(highlight);
    }
    
    public List getHighlights() {
        return Collections.unmodifiableList(highlights);
    }
    
    /**
     * Selects the first matching highlight (as judged by the given matcher),
     * moving through the entire list of highlights in the given direction,
     * ordered by FIXME: do we ensure the highlights are ordered? on what?
     */
    public void selectFirstMatchingHighlight(boolean searchingForwards, PHighlightMatcher matcher) {
        final int start = searchingForwards ? 0 : highlights.size() - 1;
        final int stop = searchingForwards ? highlights.size() : -1;
        final int step = searchingForwards ? 1 : -1;
        for (int i = start; i != stop; i += step) {
            PHighlight highlight = (PHighlight) highlights.get(i);
            if (matcher.matches(highlight)) {
                centerOffsetInDisplay(highlight.getStartIndex());
                select(highlight.getStartIndex(), highlight.getEndIndex());
                return;
            }
        }
    }
    
    public void removeHighlights(PHighlightMatcher matcher) {
        PHighlight match = null;
        boolean isOnlyOneMatch = true;
        for (int i = 0; i < highlights.size(); i++) {
            PHighlight highlight = (PHighlight) highlights.get(i);
            if (matcher.matches(highlight)) {
                if (match != null) {
                    isOnlyOneMatch = false;
                }
                match = highlight;
                highlights.remove(i);
                i--;
            }
        }
        if (isOnlyOneMatch) {
            if (match != null) {
                repaintAnchorRegion(match);
            }
            // If isOnlyOneMatch is true, but match is null, nothing was removed and so we don't repaint at all.
        } else {
            repaint();
        }
    }
    
    public PAnchorSet getAnchorSet() {
        return anchorSet;
    }
    
    public PLineList getLineList() {
        return lines;
    }
    
    public PTextBuffer getTextBuffer() {
        return lines.getTextBuffer();
    }
    
    public PCoordinates getNearestCoordinates(Point point) {
        if (point.y < 0) {
            return new PCoordinates(0, 0);
        }
        generateLineWrappings();
        final int lineIndex = getLineIndexAtLocation(point);
        PLineSegment[] segments = getLineSegmentsForSplitLine(lineIndex);
        int charOffset = 0;
        int x = 0;
        for (int i = 0; i < segments.length; i++) {
            int width = segments[i].getDisplayWidth(metrics, x);
            if (x + width > point.x) {
                charOffset += segments[i].getCharOffset(metrics, x, point.x);
                return new PCoordinates(lineIndex, charOffset);
            }
            charOffset += segments[i].getText().length();
            x += width;
        }
        return new PCoordinates(lineIndex, charOffset);
    }
    
    /**
     * Returns the line index corresponding to the given point. To properly
     * cope with clicks past the end of the text, this method may update point
     * to have a huge x-coordinate. The line index will be that of the last
     * line in the document, because that's how other text components behave;
     * it's just that all clicks after the end of the text should correspond
     * to the last character in the document, and a huge x-coordinate is the
     * easiest way to ensure that code that goes on to work out which
     * character we're pointing to on the returned line will behave correctly.
     */
    private int getLineIndexAtLocation(Point point) {
        final int maxLineIndex = splitLines.size() - 1;
        int lineIndex = point.y / metrics.getHeight();
        if (lineIndex > maxLineIndex) {
            point.x = Integer.MAX_VALUE;
        }
        lineIndex = Math.max(0, Math.min(maxLineIndex, lineIndex));
        return lineIndex;
    }
    
    public PLineSegment getLineSegmentAtLocation(Point point) {
        generateLineWrappings();
        PLineSegment[] segments = getLineSegmentsForSplitLine(getLineIndexAtLocation(point));
        int x = 0;
        for (int i = 0; i < segments.length; i++) {
            int width = segments[i].getDisplayWidth(metrics, x);
            if (x + width > point.x) {
                return segments[i];
            }
            x += width;
        }
        return null;
    }
    
    public PSegmentIterator getLogicalSegmentIterator(int offset) {
        return new PLogicalSegmentIterator(this, offset);
    }
    
    public PSegmentIterator getWrappedSegmentIterator(int offset) {
        return new PWrappedSegmentIterator(this, offset);
    }
    
    private PLineSegment[] getLineSegmentsForSplitLine(int splitLineIndex) {
        return getLineSegmentsForSplitLine(getSplitLine(splitLineIndex));
    }
    
    /**
     * Returns a series of segments of text describing how to render each part of the
     * specified line.
     * FIXME - delete once all this is sorted out properly.
     * FIXME - this is moved straight out of PAbstractTextStyler.  It needs major work.
     */
    private final PLineSegment[] getLineSegmentsForSplitLine(SplitLine splitLine) {
        int lineIndex = splitLine.getLineIndex();
        String fullLine = getLineList().getLine(lineIndex).getContents().toString();
        PLineSegment[] segments = getLineSegments(lineIndex);
        int index = 0;
        ArrayList result = new ArrayList();
        int start = splitLine.getOffset();
        int end = start + splitLine.getLength();
        
        for (int i = 0; index < end && i < segments.length; ++i) {
            PLineSegment segment = segments[i];
            if (start >= index + segment.getLength()) {
                index += segment.getLength();
                continue;
            }
            if (start > index) {
                int skip = start - index;
                segment = segment.subSegment(skip);
                index += skip;
            }
            if (end < index + segment.getLength()) {
                segment = segment.subSegment(0, end - index);
            }
            result.add(segment);
            index += segment.getLength();
        }
        return (PLineSegment[]) result.toArray(new PLineSegment[result.size()]);
    }
    
    public PLineSegment[] getLineSegments(int lineIndex) {
        return getTabbedSegments(textStyler.getLineSegments(lineIndex));
    }
    
    private PLineSegment[] getTabbedSegments(PLineSegment[] segments) {
        ArrayList result = new ArrayList();
        for (int i = 0; i < segments.length; i++) {
            addTabbedSegments(segments[i], result);
        }
        return (PLineSegment[]) result.toArray(new PLineSegment[result.size()]);
    }
    
    private void addTabbedSegments(PLineSegment segment, ArrayList target) {
        while (true) {
            String text = segment.getText();
            int tabIndex = text.indexOf('\t');
            if (tabIndex == -1) {
                target.add(segment);
                return;
            }
            target.add(segment.subSegment(0, tabIndex));
            int tabEnd = tabIndex;
            while (tabEnd < text.length() && text.charAt(tabEnd) == '\t') {
                tabEnd++;
            }
            int offset = segment.getOffset();
            target.add(new PTabSegment(this, offset + tabIndex, offset + tabEnd));
            segment = segment.subSegment(tabEnd);
            if (segment.getLength() == 0) {
                return;
            }
        }
    }
    
    public PCoordinates getCoordinates(int location) {
        if (isLineWrappingInvalid()) {
            return new PCoordinates(-1, -1);
        }
        int min = 0;
        int max = splitLines.size();
        while (max - min > 1) {
            int mid = (min + max) / 2;
            SplitLine line = getSplitLine(mid);
            if (line.containsIndex(location)) {
                return new PCoordinates(mid, location - line.getTextIndex());
            } else if (location < line.getTextIndex()) {
                max = mid;
            } else {
                min = mid;
            }
        }
        return new PCoordinates(min, location - getSplitLine(min).getTextIndex());
    }
    
    public Point getViewCoordinates(PCoordinates coordinates) {
        int baseline = getBaseline(coordinates.getLineIndex());
        PLineSegment[] segments = getLineSegmentsForSplitLine(coordinates.getLineIndex());
        int x = 0;
        int charOffset = 0;
        for (int i = 0; i < segments.length; i++) {
            String text = segments[i].getText();
            if (coordinates.getCharOffset() < charOffset + text.length()) {
                x += segments[i].getDisplayWidth(metrics, x, coordinates.getCharOffset() - charOffset);
                return new Point(x, baseline);
            }
            charOffset += text.length();
            x += segments[i].getDisplayWidth(metrics, x);
        }
        return new Point(x, baseline);
    }
    
    public int getTextIndex(PCoordinates coordinates) {
        return getSplitLine(coordinates.getLineIndex()).getTextIndex() + coordinates.getCharOffset();
    }
    
    private synchronized void repaintAnchorRegion(PAnchorRegion anchorRegion) {
        repaintIndexRange(anchorRegion.getStartIndex(), anchorRegion.getEndIndex());
    }
    
    private void repaintIndexRange(int startIndex, int endIndex) {
        if (isLineWrappingInvalid()) {
            return;
        }
        PCoordinates start = getCoordinates(startIndex);
        PCoordinates end = getCoordinates(endIndex);
        repaintLines(start.getLineIndex(), end.getLineIndex());
    }
    
    private void repaintCaret() {
        if (isLineWrappingInvalid()) {
            return;
        }
        Point point = getViewCoordinates(getCoordinates(getSelectionStart()));
        repaint(point.x - 1, point.y - metrics.getMaxAscent(), 3, metrics.getMaxAscent() + metrics.getMaxDescent());
    }
    
    public int getLineTop(int lineIndex) {
        return lineIndex * metrics.getHeight();
    }
    
    public int getLineHeight() {
        return metrics.getHeight();
    }
    
    private int getBaseline(int lineIndex) {
        return lineIndex * metrics.getHeight() + metrics.getMaxAscent();
    }
    
    public void paintComponent(Graphics oldGraphics) {
        //StopWatch watch = new StopWatch();
        generateLineWrappings();
        Graphics2D graphics = (Graphics2D) oldGraphics;
        Rectangle bounds = graphics.getClipBounds();
        int whiteBackgroundWidth = paintRightHandMargin(graphics, bounds);
        graphics.setColor(getBackground());
        if (isOpaque()) {
            graphics.fillRect(bounds.x, bounds.y, whiteBackgroundWidth, bounds.height);
        }
        int minLine = Math.min(splitLines.size() - 1, bounds.y / metrics.getHeight());
        int maxLine = Math.min(splitLines.size() - 1, (bounds.y + bounds.height) / metrics.getHeight());
        int baseline = getBaseline(minLine);
        paintHighlights(graphics, minLine, maxLine);
        int paintCharOffset = getSplitLine(minLine).getTextIndex();
        PSegmentIterator iterator = getWrappedSegmentIterator(paintCharOffset);
        int x = 0;
        int line = minLine;
        int caretOffset = hasSelection() ? -1 : getSelectionStart();
        while (iterator.hasNext()) {
            PLineSegment segment = iterator.next();
            paintCharOffset = segment.getEnd();
            applyColor(graphics, segment.getStyle().getColor());
            segment.paint(graphics, x, baseline);
            if (segment.getOffset() == caretOffset) {
                paintCaret(graphics, x, baseline);
            } else if (segment.getOffset() <= caretOffset && segment.getEnd() > caretOffset) {
                int caretX = x + segment.getDisplayWidth(metrics, x, caretOffset - segment.getOffset());
                paintCaret(graphics, caretX, baseline);
            }
            x += segment.getDisplayWidth(metrics, x);
            if (segment.isNewline()) {
                x = 0;
                baseline += metrics.getHeight();
                line++;
                if (line > maxLine) {
                    break;
                }
            }
        }
        if (caretOffset == paintCharOffset) {
            paintCaret(graphics, x, baseline);
        }
        //watch.print("Repaint");
    }
    
    /**
     * Draws the right-hand margin, and returns the width of the rectangle
     * from bounds.x that should be filled with the non-margin background color.
     * Using this in paintComponent lets us avoid unnecessary flicker caused
     * by filling the area twice.
     */
    private int paintRightHandMargin(Graphics2D graphics, Rectangle bounds) {
        int whiteBackgroundWidth = bounds.width;
        if (rightHandMarginColumn != NO_MARGIN) {
            int offset = metrics.stringWidth("n") * rightHandMarginColumn;
            graphics.setColor(MARGIN_BOUNDARY_COLOR);
            graphics.drawLine(offset, bounds.y, offset, bounds.y + bounds.height);
            graphics.setColor(MARGIN_OUTSIDE_COLOR);
            graphics.fillRect(offset + 1, bounds.y, bounds.x + bounds.width - offset - 1, bounds.height);
            whiteBackgroundWidth = (offset - bounds.x);
        }
        return whiteBackgroundWidth;
    }
    
    private void paintHighlights(Graphics2D graphics, int minLine, int maxLine) {
        int minChar = getSplitLine(minLine).getTextIndex();
        SplitLine max = getSplitLine(maxLine);
        int maxChar = max.getTextIndex() + max.getLength();
        selection.paint(graphics);
        for (int i = 0; i < highlights.size(); i++) {
            PHighlight highlight = (PHighlight) highlights.get(i);
            if (highlight.getStart().getIndex() <= maxChar && highlight.getEnd().getIndex() > minChar) {
                highlight.paint(graphics);
            }
        }
    }
    
    private void applyColor(Graphics2D graphics, Color color) {
        graphics.setColor(isEnabled() ? color : Color.GRAY);
    }
    
    private void paintCaret(Graphics2D graphics, int x, int y) {
        if (isFocusOwner() == false) {
            // An unfocused component shouldn't render a caret. There should
            // be at most one caret on the display.
            return;
        }
        applyColor(graphics, Color.RED);
        int yTop = y - metrics.getMaxAscent();
        int yBottom = y + metrics.getMaxDescent() - 1;
        graphics.drawLine(x, yTop + 1, x, yBottom - 1);
        graphics.drawLine(x, yTop + 1, x + 1, yTop);
        graphics.drawLine(x, yTop + 1, x - 1, yTop);
        graphics.drawLine(x, yBottom - 1, x + 1, yBottom);
        graphics.drawLine(x, yBottom - 1, x - 1, yBottom);
    }
    
    private void initCharWidthCache() {
        if (widthCache == null) {
            widthCache = new int[MAX_CACHED_CHAR];
            for (int i = 0; i < MAX_CACHED_CHAR; i++) {
                widthCache[i] = metrics.charWidth(i);
            }
        }
    }

    public void linesAdded(PLineEvent event) {
        if (isLineWrappingInvalid()) {
            return;
        }
        int lineIndex = event.getIndex();
        int splitIndex = getSplitLineIndex(lineIndex);
        int firstSplitIndex = splitIndex;
        changeLineIndices(lineIndex, event.getLength());
        for (int i = 0; i < event.getLength(); i++) {
            splitIndex += addSplitLines(lineIndex++, splitIndex);
        }
        updateHeight();
        repaintFromLine(firstSplitIndex);
    }
    
    public void linesRemoved(PLineEvent event) {
        if (isLineWrappingInvalid()) {
            return;
        }
        int splitIndex = getSplitLineIndex(event.getIndex());
        for (int i = 0; i < event.getLength(); i++) {
            removeSplitLines(splitIndex);
        }
        changeLineIndices(event.getIndex() + event.getLength(), -event.getLength());
        updateHeight();
        repaintFromLine(splitIndex);
    }
    
    private void changeLineIndices(int lineIndex, int change) {
        for (int i = getSplitLineIndex(lineIndex); i < splitLines.size(); i++) {
            SplitLine line = getSplitLine(i);
            line.setLineIndex(line.getLineIndex() + change);
        }
    }
    
    public void linesChanged(PLineEvent event) {
        if (isLineWrappingInvalid()) {
            return;
        }
        int lineCountChange = 0;
        int minLine = Integer.MAX_VALUE;
        int visibleLineCount = 0;
        for (int i = 0; i < event.getLength(); i++) {
            PLineList.Line line = lines.getLine(event.getIndex() + i);
            setLineWidth(line);
            int splitIndex = getSplitLineIndex(event.getIndex());
            if (i == 0) {
                minLine = splitIndex;
            }
            int removedCount = removeSplitLines(splitIndex);
            lineCountChange -= removedCount;
            int addedCount = addSplitLines(event.getIndex(), splitIndex);
            lineCountChange += addedCount;
            visibleLineCount += addedCount;
        }
        if (lineCountChange != 0) {
            updateHeight();
            repaintFromLine(getSplitLineIndex(event.getIndex()));
        } else {
            repaintLines(minLine, minLine + visibleLineCount);
        }
    }
    
    public void repaintFromLine(int splitIndex) {
        int lineTop = getLineTop(splitIndex);
        Dimension size = getSize();
        repaint(0, lineTop, size.width, size.height - lineTop);
    }
    
    public void repaintLines(int minSplitIndex, int maxSplitIndex) {
        int top = getLineTop(minSplitIndex);
        int bottom = getLineTop(maxSplitIndex + 1);
        repaint(0, top, getWidth(), bottom - top);
    }
    
    public synchronized boolean isLineWrappingInvalid() {
        return (splitLines == null);
    }
    
    private synchronized void revalidateLineWrappings() {
        invalidateLineWrappings();
        generateLineWrappings();
    }
    
    private synchronized void invalidateLineWrappings() {
        splitLines = null;
    }

    private synchronized void generateLineWrappings() {
        initCharWidthCache();
        if (isLineWrappingInvalid() && isShowing()) {
            splitLines = new ArrayList();
            for (int i = 0; i < lines.size(); i++) {
                PLineList.Line line = lines.getLine(i);
                if (line.isWidthValid() == false) {
                    setLineWidth(line);
                }
                addSplitLines(i, splitLines.size());
            }
            updateHeight();
        }
    }
    
    private void updateHeight() {
        Dimension size = getSize();
        size.height = metrics.getHeight() * splitLines.size();
        setSize(size);
        setPreferredSize(size);
    }
    
    private int removeSplitLines(int splitIndex) {
        int lineIndex = getSplitLine(splitIndex).getLineIndex();
        int removedLineCount = 0;
        while (splitIndex < splitLines.size() && getSplitLine(splitIndex).getLineIndex() == lineIndex) {
            SplitLine line = getSplitLine(splitIndex);
            splitLines.remove(splitIndex);
            removedLineCount++;
        }
        return removedLineCount;
    }
    
    public int getSplitLineIndex(int lineIndex) {
        if (lineIndex > getSplitLine(splitLines.size() - 1).getLineIndex()) {
            return splitLines.size();
        }
        int min = 0;
        int max = splitLines.size();
        while (max - min > 1) {
            int mid = (min + max) / 2;
            int midIndex = getSplitLine(mid).getLineIndex();
            if (midIndex == lineIndex) {
                return backtrackToLineStart(mid);
            } else if (midIndex > lineIndex) {
                max = mid;
            } else {
                min = mid;
            }
        }
        return backtrackToLineStart(min);
    }
    
    private int backtrackToLineStart(int splitIndex) {
        while (getSplitLine(splitIndex).getOffset() > 0) {
            splitIndex--;
        }
        return splitIndex;
    }
    
    public void printLineInfo() {
        for (int i = 0; i < splitLines.size(); i++) {
            SplitLine line = getSplitLine(i);
            System.err.println(i + ": line " + line.getLineIndex() + ", offset " + line.getOffset() + ", length " + line.getLength());
        }
    }
    
    public int getVisibleLineCount() {
        return splitLines.size();
    }
    
    public SplitLine getSplitLineOfOffset(int offset) {
        return getSplitLine(getCoordinates(offset).getLineIndex());
    }
    
    public SplitLine getSplitLine(int index) {
        return (SplitLine) splitLines.get(index);
    }
    
    private int addSplitLines(int lineIndex, int index) {
        PLineList.Line line = lines.getLine(lineIndex);
        final int initialSplitLineCount = splitLines.size();
        int width = getWidth();
        if (width == 0) {
            width = Integer.MAX_VALUE;  // Don't wrap if we don't have any size.
        }
        width = Math.max(width, MIN_WIDTH);  // Ensure we're at least a sensible width.
        if (line.getWidth() <= width) {
            // The whole line fits.
            splitLines.add(index, new SplitLine(lineIndex, 0, line.getContents().length()));
        } else {
            // The line's too long, so break it into SplitLines.
            int x = 0;
            CharSequence chars = line.getContents();
            int lastSplitOffset = 0;
            for (int i = 0; i < chars.length(); i++) {
                char ch = chars.charAt(i);
                x = addCharWidth(x, ch);
                if (x >= width - getMinimumWrapMarkWidth()) {
                    if (wordWrap) {
                        // Try to find a break before the last break.
                        for (int splitOffset = i; splitOffset >= lastSplitOffset; --splitOffset) {
                            if (chars.charAt(splitOffset) == ' ') {
                                // Break so that the word goes to the next line
                                // but the inter-word character stays where it
                                // was.
                                i = splitOffset + 1;
                                ch = chars.charAt(i);
                                break;
                            }
                        }
                    }
                    splitLines.add(index++, new SplitLine(lineIndex, lastSplitOffset, i - lastSplitOffset));
                    lastSplitOffset = i;
                    x = addCharWidth(0, ch);
                }
            }
            if (x > 0) {
                splitLines.add(index++, new SplitLine(lineIndex, lastSplitOffset, chars.length() - lastSplitOffset));
            }
        }
        return (splitLines.size() - initialSplitLineCount);
    }
    
    /**
     * Returns the amount of space that must remain to the right of a character
     * for that character not to cause a line wrap. We use this to ensure that
     * there's at least this much space for the wrap mark.
     */
    private int getMinimumWrapMarkWidth() {
        return widthCache['W'];
    }
    
    private int addCharWidth(int x, char ch) {
        if (ch == '\t') {
            x += PTabSegment.MIN_TAB_WIDTH;  // A tab's at least as wide as this.
            x += PTabSegment.TAB_WIDTH;
            return x - x % PTabSegment.TAB_WIDTH;
        } else if (ch < MAX_CACHED_CHAR) {
            return x + widthCache[(int) ch];
        } else {
            return x + metrics.charWidth(ch);
        }
    }
    
    private void setLineWidth(PLineList.Line line) {
        line.setWidth(metrics.stringWidth(line.getContents().toString()));
    }
    
    private void setText(PTextBuffer text) {
        if (lines != null) {
            lines.removeLineListener(this);
        }
        lines = new PLineList(text);
        lines.addLineListener(this);
        text.addTextListener(anchorSet);
        revalidateLineWrappings();
    }
    
    /**
     * Replaces the entire contents of this text area with the given string.
     */
    public void setText(String newText) {
        getTextBuffer().replace(new SelectionSetter(), 0, getTextBuffer().length(), newText, new SelectionSetter(0));
    }
    
    /**
     * Appends the given string to the end of the text.
     */
    public void append(String newText) {
        PTextBuffer buffer = getTextBuffer();
        synchronized (buffer) {
            buffer.replace(new SelectionSetter(), buffer.length(), 0, newText, new SelectionSetter(buffer.length() + newText.length()));
        }
    }
    
    /**
     * Returns a copy of the text in this text area.
     */
    public String getText() {
        return getTextBuffer().toString();
    }
    
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }
    
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return visible.height;  // We should never be asked for orientation=H.
    }
    
    public boolean getScrollableTracksViewportHeight() {
        // If our parent is larger than we are, expand to fill the space.
        return getParent().getHeight() > getPreferredSize().height;
    }
    
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }
    
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return metrics.getHeight();
    }
    
    public Dimension getPreferredSize() {
        Dimension result = super.getPreferredSize();
        Insets insets = getInsets();
        if (columnCount != 0) {
            result.width = Math.max(result.width, columnCount * metrics.charWidth('m') + insets.left + insets.right);
        }
        if (rowCount != 0) {
            result.height = Math.max(result.height, rowCount * getLineHeight() + insets.top + insets.bottom);
        }
        return result;
    }
    
    /**
     * Pastes the clipboard contents or, if that's not available, the system
     * selection. The pasted content replaces the selection.
     */
    public void paste() {
        paste(false);
    }
    
    /**
     * Pastes X11's "selection", an activity usually associated with a
     * middle-button click. The pasted content replaces the selection.
     */
    public void pasteSystemSelection() {
        paste(true);
    }
    
    /**
     * Override this to modify pasted text before it replaces the selection.
     */
    protected String reformatPastedText(String pastedText) {
        return pastedText;
    }
    
    private void paste(boolean onlyPasteSystemSelection) {
        try {
            Toolkit toolkit = getToolkit();
            Transferable contents = toolkit.getSystemClipboard().getContents(this);
            if (onlyPasteSystemSelection || toolkit.getSystemSelection() != null) {
                contents = toolkit.getSystemSelection().getContents(this);
            }
            DataFlavor[] transferFlavors = contents.getTransferDataFlavors();
            String string = (String) contents.getTransferData(DataFlavor.stringFlavor);
            replaceSelection(reformatPastedText(string));
        } catch (Exception ex) {
            Log.warn("Couldn't paste.", ex);
        }
    }
    
    public void copy() {
        if (hasSelection() == false) {
            return;
        }
        StringSelection stringSelection = new StringSelection(getSelectedText());
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        toolkit.getSystemClipboard().setContents(stringSelection, null);
        if (toolkit.getSystemSelection() != null) {
            toolkit.getSystemSelection().setContents(stringSelection, null);
        }
    }
    
    public void cut() {
        if (hasSelection()) {
            copy();
            replaceSelection("");
        }
    }
    
    public class SplitLine {
        private int lineIndex;
        private int offset;
        private int length;
        
        public SplitLine(int lineIndex, int offset, int length) {
            this.lineIndex = lineIndex;
            this.offset = offset;
            this.length = length;
        }
        
        public int getLineIndex() {
            return lineIndex;
        }
        
        public int getOffset() {
            return offset;
        }
        
        public int getLength() {
            return length;
        }
        
        public void setLineIndex(int lineIndex) {
            this.lineIndex = lineIndex;
        }
        
        public void setOffset(int offset) {
            this.offset = offset;
        }
        
        public void setLength(int length) {
            this.length = length;
        }
        
        public int getTextIndex() {
            return lines.getLine(lineIndex).getStart() + offset;
        }
        
        public boolean containsIndex(int charIndex) {
            int startIndex = getTextIndex();
            return (charIndex >= startIndex) && (charIndex < startIndex + length);
        }
        
        public CharSequence getContents() {
            CharSequence parent = lines.getLine(lineIndex).getContents();
            int end = offset + length;
            if (length > 0 && parent.charAt(end - 1) == '\n') {
                end -= 1;
            }
            return parent.subSequence(offset, end);
        }
    }
    
    private class SelectionHighlight extends PHighlight {
        public SelectionHighlight(PTextArea textArea, int startIndex, int endIndex) {
            super(textArea, startIndex, endIndex);
        }
        
        public boolean isEmpty() {
            return (getStartIndex() == getEndIndex());
        }
        
        public void paint(Graphics2D graphics, PCoordinates start, PCoordinates end) {
            if (isEmpty()) {
                return;
            }
            Point startPt = textArea.getViewCoordinates(start);
            Point endPt = textArea.getViewCoordinates(end);
            Color oldColor = graphics.getColor();
            int y = textArea.getLineTop(start.getLineIndex());
            int lineHeight = textArea.getLineHeight();
            for (int i = start.getLineIndex(); i <= end.getLineIndex(); i++) {
                int xStart = (i == start.getLineIndex()) ? startPt.x : 0;
                int xEnd = (i == end.getLineIndex()) ? endPt.x : textArea.getWidth();
                graphics.setColor(isFocusOwner() ? FOCUSED_SELECTION_COLOR : UNFOCUSED_SELECTION_COLOR);
                paintRectangleContents(graphics, new Rectangle(xStart, y, xEnd - xStart, lineHeight));
                int yBottom = y + lineHeight - 1;
                if (isFocusOwner()) {
                    graphics.setColor(FOCUSED_SELECTION_BOUNDARY_COLOR);
                    if (i == start.getLineIndex()) {
                        if (xStart > 0) {
                            graphics.drawLine(xStart, y, xStart, yBottom);
                        }
                        graphics.drawLine(xStart, y, xEnd, y);
                    } else if (i == start.getLineIndex() + 1) {
                        graphics.drawLine(0, y, Math.min(xEnd, startPt.x), y);
                    }
                    if (i == end.getLineIndex()) {
                        if (xEnd < textArea.getWidth()) {
                            graphics.drawLine(xEnd, y, xEnd, yBottom);
                        }
                        graphics.drawLine(xStart, yBottom, xEnd, yBottom);
                    } else if (i == end.getLineIndex() - 1) {
                        graphics.drawLine(Math.max(endPt.x, xStart), yBottom, xEnd, yBottom);
                    }
                }
                y += lineHeight;
            }
            graphics.setColor(oldColor);
        }
        
        public void paintRectangleContents(Graphics2D graphics, Rectangle rectangle) {
            graphics.fill(rectangle);
        }
    }
}
