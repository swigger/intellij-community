// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.stickyLines;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.Processor;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.annotations.ApiStatus.Internal;

/**
 * Represents a storage of sticky lines.
 */
@Internal
public final class StickyLinesModel {
  private static final Key<Boolean> STICKY_LINES_FIRST_PASS_KEY = Key.create("editor.sticky.lines.first.pass");
  private static final Key<String> STICKY_LINE_SOURCE = Key.create("editor.sticky.lines.source");
  private static final Key<StickyLinesModel> STICKY_LINES_MODEL_KEY = Key.create("editor.sticky.lines.model");
  private static final Key<StickyLineImpl> STICKY_LINE_IMPL_KEY = Key.create("editor.sticky.line.impl");
  private static final TextAttributesKey STICKY_LINE_ATTRIBUTE = TextAttributesKey.createTextAttributesKey(
    "STICKY_LINE_MARKER"
  );

  public static @Nullable StickyLinesModel getModel(@NotNull Project project, @NotNull Document document) {
    if (project.isDisposed()) return null;
    MarkupModel markupModel = DocumentMarkupModel.forDocument(document, project, false);
    if (markupModel == null) return null;
    return getModel(markupModel);
  }

  static @NotNull StickyLinesModel getModel(@NotNull MarkupModel markupModel) {
    StickyLinesModel stickyModel = markupModel.getUserData(STICKY_LINES_MODEL_KEY);
    if (stickyModel == null) {
      stickyModel = new StickyLinesModel((MarkupModelEx) markupModel);
      markupModel.putUserData(STICKY_LINES_MODEL_KEY, stickyModel);
    }
    return stickyModel;
  }

  private final MarkupModelEx myMarkupModel;
  private final List<Listener> myListeners;

  private StickyLinesModel(MarkupModelEx markupModel) {
    myMarkupModel = markupModel;
    myListeners = new ArrayList<>();
  }

  public boolean isFirstUpdate() {
    boolean isFirstTime = myMarkupModel.getUserData(STICKY_LINES_FIRST_PASS_KEY) == null;
    if (isFirstTime) {
      myMarkupModel.putUserData(STICKY_LINES_FIRST_PASS_KEY, false);
    }
    return isFirstTime;
  }

  public @NotNull StickyLine addStickyLine(@NotNull String source, int textOffset, int endOffset, @Nullable String debugText) {
    if (textOffset >= endOffset) {
      throw new IllegalArgumentException(String.format(
        "sticky line endOffset %s should be less than textOffset %s", textOffset, endOffset
      ));
    }
    RangeHighlighter highlighter = myMarkupModel.addRangeHighlighter(
      STICKY_LINE_ATTRIBUTE,
      textOffset,
      endOffset,
      HighlighterLayer.SYNTAX,
      HighlighterTargetArea.EXACT_RANGE
    );
    StickyLineImpl stickyLine = new StickyLineImpl(highlighter.getDocument(), highlighter, debugText);
    highlighter.putUserData(STICKY_LINE_IMPL_KEY, stickyLine);
    highlighter.putUserData(STICKY_LINE_SOURCE, source);
    return stickyLine;
  }

  public void removeStickyLine(@NotNull StickyLine stickyLine) {
    RangeMarker rangeMarker = ((StickyLineImpl)stickyLine).rangeMarker();
    myMarkupModel.removeHighlighter((RangeHighlighter) rangeMarker);
  }

  void processStickyLines(int endOffset, @NotNull Processor<? super StickyLine> processor) {
    processStickyLines(null, endOffset, processor);
  }

  public void processStickyLines(@Nullable String source, @NotNull Processor<? super StickyLine> processor) {
    processStickyLines(source, myMarkupModel.getDocument().getTextLength(), processor);
  }

  private void processStickyLines(@Nullable String source, int endOffset, @NotNull Processor<? super StickyLine> processor) {
    myMarkupModel.processRangeHighlightersOverlappingWith(
      0,
      endOffset,
      highlighter -> {
        if (STICKY_LINE_ATTRIBUTE.equals(highlighter.getTextAttributesKey()) && isSuitableSource(highlighter, source)) {
          StickyLineImpl stickyLine = highlighter.getUserData(STICKY_LINE_IMPL_KEY);
          if (stickyLine == null) {
            // probably it is a zombie highlighter
            stickyLine = new StickyLineImpl(highlighter.getDocument(), highlighter, "StickyZombie");
          }
          return processor.process(stickyLine);
        } else {
          return true;
        }
      }
    );
  }

  private static boolean isSuitableSource(RangeHighlighterEx highlighter, @Nullable String source) {
    return source == null || source.equals(highlighter.getUserData(STICKY_LINE_SOURCE));
  }

  interface Listener {
    void modelChanged();
  }

  void addListener(Listener listener) {
    myListeners.add(listener);
  }

  void removeListener(Listener listener) {
    myListeners.remove(listener);
  }

  @RequiresEdt
  public void notifyListeners() {
    for (Listener listener : myListeners) {
      listener.modelChanged();
    }
  }

  private record StickyLineImpl(
    @NotNull Document document,
    @NotNull RangeMarker rangeMarker,
    @Nullable String debugText
  ) implements StickyLine {

    @Override
    public int primaryLine() {
      return document.getLineNumber(rangeMarker.getStartOffset());
    }

    @Override
    public int scopeLine() {
      return document.getLineNumber(rangeMarker.getEndOffset());
    }

    @Override
    public int navigateOffset() {
      return rangeMarker.getStartOffset();
    }

    @Override
    public @NotNull TextRange textRange() {
      return rangeMarker.getTextRange();
    }

    @Override
    public @Nullable String debugText() {
      return debugText;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof StickyLineImpl impl)) return false;
      return textRange().equals(impl.textRange());
    }

    @Override
    public int hashCode() {
      return textRange().hashCode();
    }

    @Override
    public int compareTo(@NotNull StickyLine other) {
      TextRange range = textRange();
      TextRange otherRange = other.textRange();
      int compare = Integer.compare(range.getStartOffset(), otherRange.getStartOffset());
      if (compare != 0) {
        return compare;
      }
      // reverse order
      return Integer.compare(otherRange.getEndOffset(), range.getEndOffset());
    }

    @Override
    public @NotNull String toString() {
      String prefix = debugText == null ? "" : debugText;
      return prefix + "(" + primaryLine() + ", " + scopeLine() + ")";
    }
  }
}
