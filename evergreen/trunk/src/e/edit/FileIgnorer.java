package e.edit;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import e.util.*;

public class FileIgnorer {
  /** Extensions that shouldn't be shown in directory windows. */
  private String[] ignoredExtensions;
  
  /** Names of directories that shouldn't be shown in directory windows. */
  private Pattern uninterestingDirectoryNames;
  
  public FileIgnorer(String rootDirectoryPath) {
    File rootDirectory = FileUtilities.fileFromString(rootDirectoryPath);
    ignoredExtensions = FileUtilities.getArrayOfPathElements(Parameters.getParameter("files.uninterestingExtensions", ""));
    uninterestingDirectoryNames = Pattern.compile(getUninterestingDirectoryPattern(rootDirectory));
  }
  
  public boolean isIgnored(File file) {
    if (file.isHidden() || file.getName().startsWith(".") || file.getName().endsWith("~")) {
      return true;
    }
    if (file.isDirectory()) {
      return isIgnoredDirectory(file);
    }
    return FileUtilities.nameEndsWithOneOf(file, ignoredExtensions);
  }
  
  private static String getUninterestingDirectoryPattern(File rootDirectory) {
    String[] command = new String[] { "echo-local-non-source-directory-pattern" };
    ArrayList<String> patterns = new ArrayList<String>();
    // A "not found" error is expected by default and ignored.
    ArrayList<String> errors = new ArrayList<String>();
    ProcessUtilities.backQuote(rootDirectory, command, patterns, errors);
    patterns.add("\\.deps");
    patterns.add("\\.svn");
    patterns.add("BitKeeper");
    patterns.add("CVS");
    patterns.add("SCCS");
    return StringUtilities.join(patterns, "|");
  }
  
  public boolean isIgnoredDirectory(File directory) {
    return uninterestingDirectoryNames.matcher(directory.getName()).matches();
  }
}