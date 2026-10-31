package com.opencode.ide.cdt.internal;

import com.opencode.ide.core.context.ProjectContext;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * CDT-backed {@link ProjectContext} - the integration seam between the opencode core/UI layers and
 * the C/C++ (CDT) layer. Registered as an OSGi Declarative Service (see {@code
 * OSGI-INF/CdtProjectContext.xml}); when it registers, core drops its cached opencode client so the
 * server respawns in the right project.
 *
 * <p>Resolution strategy for the active CDT project, first match wins:
 *
 * <ol>
 *   <li>the active workbench editor's input ({@link IEditorInput} adapted to {@link IFile}) mapped
 *       to its {@link ICProject} via {@link CoreModel};
 *   <li>the current selection service selection (e.g. a file picked in the Project Explorer)
 *       adapted to {@link IFile};
 *   <li>the first open C project in the workspace.
 * </ol>
 *
 * <p>Fully null-tolerant and headless-safe: without a workbench, without a workspace, or when
 * nothing resolves, {@link #getWorkingDirectory()} returns {@link Optional#empty()} - it never
 * throws.
 */
public class CdtProjectContext implements ProjectContext {

  private static final Logger LOG = Logger.getLogger(CdtProjectContext.class.getName());

  @Override
  public Optional<Path> getWorkingDirectory() {
    try {
      ICProject cProject = resolveActiveCProject();
      if (cProject != null) {
        IProject project = cProject.getProject();
        if (project != null && project.isOpen()) {
          IPath location = project.getLocation();
          if (location != null) {
            return Optional.of(location.toFile().toPath());
          }
        }
      }
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "failed to resolve CDT project working directory", t);
    }
    return Optional.empty();
  }

  /**
   * @return the active CDT project per the strategy above, or {@code null} when nothing resolves
   *     (headless included).
   */
  ICProject resolveActiveCProject() {
    ICProject fromWorkbench = resolveFromWorkbench();
    if (fromWorkbench != null) {
      return fromWorkbench;
    }
    return firstWorkspaceCProject();
  }

  /** Editor input first, then selection service; {@code null} when headless or nothing selected. */
  private ICProject resolveFromWorkbench() {
    try {
      if (!PlatformUI.isWorkbenchRunning()) {
        return null;
      }
      IWorkbench workbench = PlatformUI.getWorkbench();
      IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
      if (window == null) {
        return null;
      }
      ICProject fromEditor = toCProject(fileFromActiveEditor(window));
      if (fromEditor != null) {
        return fromEditor;
      }
      return toCProject(fileFromSelection(window));
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "workbench-based CDT project resolution failed", t);
      return null;
    }
  }

  private IFile fileFromActiveEditor(IWorkbenchWindow window) {
    IWorkbenchPage page = window.getActivePage();
    if (page == null) {
      return null;
    }
    IEditorPart editor = page.getActiveEditor();
    if (editor == null) {
      return null;
    }
    IEditorInput input = editor.getEditorInput();
    if (input instanceof IFileEditorInput fileInput) {
      return fileInput.getFile();
    }
    return input instanceof IAdaptable adaptable ? adaptToFile(adaptable) : null;
  }

  private IFile fileFromSelection(IWorkbenchWindow window) {
    ISelection selection = window.getSelectionService().getSelection();
    if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
      Object element = structured.getFirstElement();
      if (element instanceof IAdaptable adaptable) {
        return adaptToFile(adaptable);
      }
    }
    return null;
  }

  private IFile adaptToFile(IAdaptable adaptable) {
    Object adapted = adaptable.getAdapter(IFile.class);
    return adapted instanceof IFile file ? file : null;
  }

  /** Maps a file to its CDT project via the CoreModel (file element, else project nature). */
  private ICProject toCProject(IFile file) {
    if (file == null) {
      return null;
    }
    try {
      ICElement element = CoreModel.getDefault().create(file);
      if (element != null && element.getCProject() != null) {
        return element.getCProject();
      }
      return CoreModel.getDefault().create(file.getProject());
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "failed to map file to CDT project: {0}", file);
      return null;
    }
  }

  /**
   * @return the first open C project in the workspace, or {@code null} (headless included).
   */
  private ICProject firstWorkspaceCProject() {
    try {
      for (ICProject cProject : CoreModel.getDefault().getCModel().getCProjects()) {
        IProject project = cProject.getProject();
        if (project != null && project.isOpen()) {
          return cProject;
        }
      }
    } catch (CModelException e) {
      LOG.log(Level.WARNING, "failed to enumerate workspace C projects", e);
    } catch (Throwable t) {
      // e.g. no workspace (headless) - no fallback possible
    }
    return null;
  }
}
