package org.dcarew.pythontools.core.building;

import org.dcarew.pythontools.core.PythonCorePlugin;
import org.dcarew.pythontools.core.utils.ProcessRunner;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PylintJob extends WorkspaceJob {
  private List<IFile> files;

  public PylintJob(IProject project, List<IFile> files) {
    super("Pylint");

    this.files = files;

    // TODO: only for the given project?
    //setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
    setRule(ResourcesPlugin.getWorkspace().getRuleFactory().markerRule(project));
  }

  @Override
  public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
    monitor.beginTask("Running pylint...", files.size() * 9 + 1);

    monitor.worked(1);

    try {
      for (IFile file : files) {
        process(file);

        monitor.worked(9);
      }
    } catch (Throwable t) {
      PythonCorePlugin.logError(t);
    } finally {
      monitor.done();
    }

    return Status.OK_STATUS;
  }

  private void process(IFile file) {
    if (!file.exists()) {
      return;
    }

    String pylintPath = PythonCorePlugin.getPlugin().getPylintPath();

    if (pylintPath == null) {
      MarkerUtils.clearMarkers(file);
    } else {
      try {
        // TODO: use a pylintrc setting
        ProcessRunner runner = new ProcessRunner(getCwd(file), pylintPath, "-f", "parseable", "-r",
            "n", "-i", "y", "--indent-string=\"  \"", file.getName());

        int exit = runner.execute();

        MarkerUtils.clearMarkers(file);

        if (exit > 0 && exit < 32) {
          processOutput(file, runner.getStdout());
        }
      } catch (IOException e) {
        PythonCorePlugin.logError(e);
      }
    }
  }

  private void processOutput(IFile file, String str) throws IOException {
    // build.py:757: [C] Line too long (85/80)
    // build.py:1: [C] Too many lines in module (1063)

    Pattern pattern = Pattern.compile("(\\S+):(\\d+): \\[(.*)\\] (.*)");

    BufferedReader r = new BufferedReader(new StringReader(str));

    String line = r.readLine();

    while (line != null) {
      if (line.contains(": [")) {
        Matcher matcher = pattern.matcher(line);

        if (matcher.matches()) {
          createMarker(file, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
        }
      }

      line = r.readLine();
    }
  }

  private void createMarker(IFile file, String fileName, String lineStr, String errorType,
      String message) {
    // (C) convention, for programming standard violation
    // (R) refactor, for bad code smell
    // (W) warning, for python specific problems
    // (E) error, for probable bugs in the code
    // (F) fatal, if an error occurred which prevented pylint from doing further processing.

    int line = 1;

    try {
      line = Integer.parseInt(lineStr);
    } catch (NumberFormatException fne) {

    }

    String typeCode = errorType;

    if (typeCode.indexOf(',') != -1) {
      typeCode = typeCode.substring(0, typeCode.indexOf(','));
    }

    int severity = IMarker.SEVERITY_WARNING;

    if (typeCode.startsWith("E") || typeCode.startsWith("F")) {
      severity = IMarker.SEVERITY_ERROR;
    }

    MarkerUtils.createMarker(severity, file, line, typeCode + ": " + message, typeCode);
  }

  private File getCwd(IFile file) {
    return file.getParent().getLocation().toFile();
  }

}
