package com.opencode.ide.board.views;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

import com.opencode.ide.board.model.SessionDiffSides;

/**
 * The Fleet view's diff opened in Eclipse's built-in compare editor (user
 * direction 2026-09-17): one compare input over a session's file sides —
 * a root with one {@link DiffNode} per file, each connecting the BEFORE
 * and AFTER revisions resolved by {@link SessionDiffSides}. The compare
 * editor gives the side-by-side view, hunk navigation and (per file type)
 * syntax highlighting that the plain-text patch dialog cannot.
 *
 * <p>The sides are resolved off the UI thread BEFORE this input is
 * constructed; {@link #prepareInput} only assembles the node tree.</p>
 */
final class SessionDiffCompareInput extends org.eclipse.compare.CompareEditorInput {

    private final String title;
    private final List<SessionDiffSides.Side> sides;

    SessionDiffCompareInput(String title, List<SessionDiffSides.Side> sides) {
        super(new CompareConfiguration());
        this.title = title == null ? "Session diff" : title;
        this.sides = List.copyOf(sides);
        setTitle(this.title);
    }

    @Override
    protected Object prepareInput(IProgressMonitor monitor) {
        DiffNode root = new DiffNode(Differencer.NO_CHANGE);
        for (SessionDiffSides.Side side : sides) {
            root.add(new DiffNode(new FileSide(side.path(), side.before()),
                    new FileSide(side.path(), side.after())));
        }
        return root;
    }

    /** One side of one file: an in-memory, read-only typed element. */
    private static final class FileSide implements ITypedElement, IStreamContentAccessor {

        private final String path;
        private final String content;

        FileSide(String path, String content) {
            this.path = path;
            this.content = content == null ? "" : content;
        }

        @Override
        public String getName() {
            return path;
        }

        @Override
        public Image getImage() {
            return null;
        }

        @Override
        public String getType() {
            // the compare framework keys content viewers (syntax highlighting)
            // on this type: the file extension, without the dot
            String extension = org.eclipse.core.runtime.Path.fromOSString(path).getFileExtension();
            return extension == null ? "" : extension;
        }

        @Override
        public InputStream getContents() {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
