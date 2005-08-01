package e.gui;

import java.awt.*;
import java.awt.event.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.text.*;

import e.forms.*;

//
// TODO: automatically update results as the document changes
// TODO: support for something like BirdView?
// TODO: some way to add menu items to a menu?
//

public class JTextComponentFind {
    /**
     * Used to mark the matches in the text as if they'd been gone over with a highlighter pen. We use
     * full yellow with half-alpha so you can see the selection through, as a dirty smudge, just like a real
     * highlighter pen might do.
     */
    public static final Highlighter.HighlightPainter PAINTER = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 255, 0, 128));
    
    /**
     * The JTextComponent we're working on.
     */
    private JTextComponent textComponent;
    
    /**
     * Stores the initial start of the selection so we can restore it if the
     * user cancels the search.
     */
    private int initialSelectionStart;
    
    /**
     * Stores the initial end of the selection so we can restore it if the
     * user cancels the search.
     */
    private int initialSelectionEnd;
    
    private JTextField findField = new JTextField(40);
    
    /**
     * Used to show the number of matches, or report errors in the user's
     * regular expression.
     */
    private JLabel findStatus = new JLabel(" ");
    
    private Action findAction = new FindAction();
    private Action findNextAction = new FindNextAction();
    private Action findPreviousAction = new FindPreviousAction();
    
    public static void addFindFunctionalityTo(final JTextComponent textComponent) {
        new JTextComponentFind(textComponent);
    }
    
    private void initKeyStrokes(final JTextComponent component, boolean includeFind) {
        if (includeFind) {
            addKeyBinding(component, KeyEvent.VK_F, findAction);
        }
        addKeyBinding(component, KeyEvent.VK_D, findPreviousAction);
        addKeyBinding(component, KeyEvent.VK_G, findNextAction);
    }
    
    private static void addKeyBinding(final JTextComponent component, int keyEventVk, Action action) {
        InputMap inputMap = component.getInputMap();
        int eventMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        KeyStroke key = KeyStroke.getKeyStroke(keyEventVk, eventMask);
        inputMap.put(key, action);
    }
    
    private void showFindDialog() {
        Frame frame = (Frame) SwingUtilities.getAncestorOfClass(Frame.class, textComponent);
        
        FormBuilder form = new FormBuilder(frame, "Find");
        FormPanel formPanel = form.getFormPanel();
        formPanel.addRow("Find:", findField);
        formPanel.setStatusBar(findStatus);
        formPanel.setTypingTimeoutActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    findField.setForeground(UIManager.getColor("TextField.foreground"));
                    updateFindResults();
                } catch (PatternSyntaxException ex) {
                    findField.setForeground(Color.RED);
                    findStatus.setText(ex.getDescription());
                }
            }
        });
        form.showNonModal();
        
        findField.selectAll();
        findField.requestFocus();
        findStatus.setText(" ");
    }
    
    private void cancelFind() {
        removeAllMatches();
    }
    
    private void removeAllMatches() {
        JTextComponentUtilities.removeAllHighlightsUsingPainter(textComponent, PAINTER);
        //currentTextWindow.getBirdView().clearMatchingLines();
    }
    
    private void updateFindResults() {
        findAllMatches(findField.getText());
    }
    
    private void findAllMatches(String regularExpression) {
        removeAllMatches();
        
        // Do we have something to search for?
        if (regularExpression == null || regularExpression.length() == 0) {
            return;
        }
        
        // Do we have something to search in?
        String content = textComponent.getText();
        if (content == null) {
            return;
        }
        
        // Compile the regular expression.
        Pattern pattern = Pattern.compile(regularExpression);
        
        // Find all the matches.
        int matchCount = 0;
        Matcher matcher = pattern.matcher(content);
        Highlighter highlighter = textComponent.getHighlighter();
        while (matcher.find()) {
            try {
                //textComponent.getBirdView().addMatchingLine(textArea.getLineOfOffset(matcher.end()));
                highlighter.addHighlight(matcher.start(), matcher.end(), PAINTER);
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
            matchCount++;
        }
        findStatus.setText("Matches: " + matchCount);
    }
    
    private JTextComponentFind(final JTextComponent textComponent) {
        this.textComponent = textComponent;
        initKeyStrokes(textComponent, true);
        initKeyStrokes(findField, false);
    }
    
    private class FindAction extends AbstractAction {
        public FindAction() {
            super("Find...");
        }
        
        public void actionPerformed(ActionEvent e) {
            showFindDialog();
        }
    }
    
    private class FindNextAction extends AbstractAction {
        public FindNextAction() {
            super("Find Next");
        }
        
        public void actionPerformed(ActionEvent e) {
            updateFindResults();
            JTextComponentUtilities.findNextHighlight(textComponent, Position.Bias.Forward, PAINTER);
        }
    }
    
    private class FindPreviousAction extends AbstractAction {
        public FindPreviousAction() {
            super("Find Previous");
        }
        
        public void actionPerformed(ActionEvent e) {
            updateFindResults();
            JTextComponentUtilities.findNextHighlight(textComponent, Position.Bias.Backward, PAINTER);
        }
    }
}
