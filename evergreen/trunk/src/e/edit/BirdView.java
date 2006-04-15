package e.edit;

import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.plaf.*;
import javax.swing.plaf.basic.*;
import e.ptextarea.*;
import e.util.*;

/**
 * A bird's-eye view of the document. Intended to sit alongside the vertical scrollbar.
 * The find code informs us of matches, and we display little marks corresponding
 * to the matches' locations.
 * 
 * If you hover over the view, the nearest mark (if it's close enough) will be highlighted.
 * Clicking will take you to that match.
 * 
 * A mark on the line with the caret will be highlighted in the caret color.
 */
public class BirdView extends JComponent {
    private PTextArea textArea;
    private JScrollBar scrollBar;

    private Method method;

    private BitSet matchingLines = new BitSet();
    
    private int currentLineInTextArea;
    private int nearestLineToMouseInBirdView = -1;

    public BirdView(PTextArea textArea, JScrollBar scrollBar) {
        this.textArea = textArea;
        this.scrollBar = scrollBar;
        try {
            method = BasicScrollBarUI.class.getDeclaredMethod("getTrackBounds", new Class[] {});
            method.setAccessible(true);
        } catch (Exception ex) {
            Log.warn("Couldn't get access to getTrackBounds", ex);
        }
        initCaretListener();
        initMouseListener();
    }
    
    private void initCaretListener() {
        PCaretListener listener = new PCaretListener() {
            public void caretMoved(PTextArea textArea, int selectionStart, int selectionEnd) {
                currentLineInTextArea = textArea.getLineOfOffset(selectionStart);
            }
        };
        textArea.addCaretListener(listener);
    }
    
    private void initMouseListener() {
        MouseInputListener listener = new MouseInputAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && nearestLineToMouseInBirdView != -1) {
                    // Humans and goToLine() number lines from 1.
                    textArea.goToLine(nearestLineToMouseInBirdView + 1);
                }
            }
            
            public void mouseExited(MouseEvent e) {
                nearestLineToMouseInBirdView = -1;
                repaint();
            }
            
            public void mouseMoved(MouseEvent e) {
                findNearestMatchingLineTo(viewToModel(e.getY()));
                repaint();
            }
        };
        addMouseListener(listener);
        addMouseMotionListener(listener);
    }

    public int viewToModel(int y) {
        Rectangle usableArea = getUsableArea();
        double scaleFactor = getLineScaleFactor(usableArea);
        int wrappedLineIndex = (int) ((y - usableArea.y) / scaleFactor);
        wrappedLineIndex = Math.max(0, Math.min(wrappedLineIndex, textArea.getSplitLineCount() - 1));
        int charIndex = textArea.getTextIndex(new PCoordinates(wrappedLineIndex, 0));
        return textArea.getLineOfOffset(charIndex);
    }
    
    private void findNearestMatchingLineTo(int exactLine) {
        nearestLineToMouseInBirdView = -1;
        for (int distance = 0; nearestLineToMouseInBirdView == -1 && distance < 10; ++distance) {
            setNearestLineIfMatching(exactLine - distance);
            setNearestLineIfMatching(exactLine + distance);
        }
    }
    
    private void setNearestLineIfMatching(final int line) {
        if (line >= 0 && line < matchingLines.length() && matchingLines.get(line)) {
            nearestLineToMouseInBirdView = line;
        }
    }

    public Dimension getPreferredSize() {
        return new Dimension(4, Integer.MAX_VALUE);
    }
    
    public Rectangle getUsableArea() {
        Rectangle usableArea = new Rectangle(0, 0, getWidth() - 1, getHeight() - 1);
        ScrollBarUI scrollUi = scrollBar.getUI();
        if (method != null && scrollUi instanceof BasicScrollBarUI) {
            BasicScrollBarUI basicUi = (BasicScrollBarUI) scrollUi;
            try {
                Rectangle trackArea = (Rectangle) method.invoke(basicUi, new Object[0]);
                JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, scrollBar);
                usableArea.y = trackArea.y + pane.getInsets().top;
                usableArea.height = trackArea.height;
            } catch (Exception ex) {
                Log.warn("Couldn't invoke getTrackBounds; won't try again", ex);
                method = null;
            }
        } else if (GuiUtilities.isMacOs()) {
            // These values were measured using Pixie. I don't know how to get them at run-time.
            usableArea.y += 10;
            usableArea.height -= 42;
        }
        return usableArea;
    }

    private void updateCursor() {
        Cursor newCursor = (nearestLineToMouseInBirdView != -1) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor();
        if (newCursor != getCursor()) {
            setCursor(newCursor);
        }
    }

    public void paintComponent(Graphics g) {
        g.setColor(SystemColor.control); //UIManager.getColor("Scrollbar.track"));
        g.fillRect(0, 0, getWidth(), getHeight());

        updateCursor();

        Rectangle usableArea = getUsableArea();

        if (false) {
            /* When testing, it's nice to see where we think the rectangle is. */
            g.setColor(Color.GRAY);
            g.drawRect(usableArea.x, usableArea.y, usableArea.width, usableArea.height);
        }

        double scaleFactor = getLineScaleFactor(usableArea);
        for (int i = 0; i < matchingLines.length(); i++) {
            if (matchingLines.get(i)) {
                Color color = Color.BLACK;
                if (i == nearestLineToMouseInBirdView) {
                    color = Color.CYAN;
                } else if (i == currentLineInTextArea) {
                    color = Color.RED;
                }
                g.setColor(color);
                int wrappedIndex = textArea.getSplitLineIndex(i);
                int y = usableArea.y + (int) ((double) wrappedIndex * scaleFactor);
                g.drawLine(usableArea.x, y, usableArea.width, y);
            }
        }
    }
    
    public double getLineScaleFactor(Rectangle usableArea) {
        // The '-1' in the following line is to force the last line of the file to be right at the bottom of
        // the bird view.  Otherwise the marker for the final line seems to be too high in short files.
        return ((double) usableArea.height) / (textArea.getSplitLineCount() - 1);
    }

    public synchronized void addMatchingLine(int lineNumber) {
        matchingLines.set(lineNumber);
        repaint();
    }

    public synchronized void clearMatchingLines() {
        matchingLines = new BitSet();
        repaint();
    }
}
