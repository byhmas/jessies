package e.gui;

import java.awt.*;
import javax.swing.*;
import e.util.*;

public class EListCellRenderer extends DefaultListCellRenderer {
    private boolean alternateLineColor;

    public EListCellRenderer(boolean alternateLineColor) {
        this.alternateLineColor = alternateLineColor;
    }
    
    public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        // Work around a couple of JLabel problems.
        String text = getText();
        if (text.length() == 0) {
            // Prevent blank lines from being squashed (mis-feature in JLabel).
            setText(" ");
        } else if (text.indexOf('\t') != -1) {
            // Prevent tabs from being squashed (mis-feature in JLabel).
            setText(text.replaceAll("\t", "    "));
        }
        
        // Optionally use the line-printer paper trick of alternating row color.
        if (alternateLineColor && !isSelected && index % 2 == 0) {
            setBackground(GuiUtilities.ALTERNATE_ROW_COLOR);
        }

        return this;
    }
}
